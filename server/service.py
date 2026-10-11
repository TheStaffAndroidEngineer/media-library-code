"""Shelf: immutable progressive media with controlled corruption and replacement."""

import argparse
import hashlib
import json
from pathlib import Path
from http.server import ThreadingHTTPServer
from base import BaseService, Problem, fields, integer, handler, require

MEDIA = Path(__file__).with_name("media")


def asset(version):
    data = (MEDIA / f"stillness-v{version}.mp4").read_bytes()
    return {
        "id": "stillness",
        "title": "Eight seconds of stillness",
        "version": version,
        "length": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "path": f"/media/stillness-v{version}.mp4",
    }


class Service(BaseService):
    KIND = "shelf"

    def seed(self):
        return {"version": 1, "corrupt": False}

    def view(self, state, now):
        return {"film": asset(state["version"])}

    def apply(self, state, action, args, now):
        if action == "replace":
            fields(args, {"version"})
            state["version"] = integer(args["version"], 1, 2)
        elif action == "corrupt":
            fields(args, {"enabled"})
            require(type(args["enabled"]) is bool, "Boolean required")
            state["corrupt"] = args["enabled"]
        else:
            raise Problem("Unknown action")
        return {"accepted": True}

    def binary(self, path):
        if path not in {"/media/stillness-v1.mp4", "/media/stillness-v2.mp4"}:
            raise Problem("Unknown asset", 404)
        with self.transaction() as db:
            if self.controls(db).get("unavailable"):
                raise Problem("Fixture unavailable", 503)
            state = json.loads(db.execute("SELECT value FROM state WHERE id=1").fetchone()[0])
        raw = (MEDIA / path.rsplit("/", 1)[1]).read_bytes()
        # Corruption deliberately violates the declared immutable representation for the integrity lab.
        if state["corrupt"]:
            raw = bytes([raw[0] ^ 1]) + raw[1:]
        return raw, '"' + asset(1 if "v1" in path else 2)["sha256"] + '"'


def media_handler(service):
    parent = handler(service)

    class Handler(parent):
        def do_GET(self):
            if not self.path.startswith("/media/"):
                return super().do_GET()
            try:
                data, etag = service.binary(self.path)
                start, end = 0, len(data) - 1
                status = 200
                value = self.headers.get("Range")
                if value and self.headers.get("If-Range", etag) == etag:
                    import re

                    if len(value) > 100:
                        raise Problem("Range header too long", 416)
                    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value)
                    if not match or not any(match.groups()):
                        raise Problem("Only one byte range is supported", 416)
                    if not match[1]:
                        suffix = int(match[2])
                        if suffix == 0:
                            raise Problem("Empty suffix range", 416)
                        start = max(0, len(data) - suffix)
                    else:
                        start = int(match[1])
                        end = min(int(match[2]), end) if match[2] else end
                    if not 0 <= start <= end < len(data):
                        raise Problem("Range outside asset", 416)
                    status = 206
                self.send_response(status)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("ETag", etag)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(end - start + 1))
                if status == 206:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(data[start : end + 1])
                self.close_connection = True
            except Problem as error:
                if error.status == 416:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{len(data)}")
                    self.send_header("Content-Length", "0")
                    self.send_header("Connection", "close")
                    self.end_headers()
                else:
                    self.send_error(error.status, str(error))
                self.close_connection = True
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True

    return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="runtime/state.sqlite")
    parser.add_argument("--port", type=int, default=8917)
    args = parser.parse_args()
    Path(args.database).parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), media_handler(Service(args.database)))
    print("Shelf media fixture on loopback port", args.port, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
