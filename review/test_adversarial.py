"""Independent Shelf HTTP/representation checks; no Android or decoder execution."""
import hashlib
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from service import Service, asset, media_handler


class ShelfReview(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='shelf-review-')
        self.database = Path(self.temp.name) / 'state.sqlite'
        self.service = Service(self.database)
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), media_handler(self.service))
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=.01), daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(2)
        self.temp.cleanup()

    def request(self, path, headers=None, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        try:
            connection.request('GET' if body is None else 'POST', path,
                               body=None if body is None else json.dumps(body), headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def command(self, action, args, identity=None):
        return {'id': identity or str(uuid.uuid4()), 'action': action, 'args': args}

    def test_catalog_and_complete_body_have_one_exact_representation(self):
        for version in (1, 2):
            film = asset(version)
            status, headers, body = self.request(film['path'])
            self.assertEqual(200, status)
            self.assertEqual(film['length'], len(body))
            self.assertEqual(str(len(body)), headers['Content-Length'])
            self.assertEqual(film['sha256'], hashlib.sha256(body).hexdigest())
            self.assertEqual('"' + film['sha256'] + '"', headers['ETag'])
            self.assertEqual('video/mp4', headers['Content-Type'])
            self.assertEqual('bytes', headers['Accept-Ranges'])
            self.assertEqual('close', headers['Connection'])

    def test_explicit_and_open_ranges_reconstruct_original_bytes(self):
        film = asset(1); original = self.request(film['path'])[2]
        chunks = []
        for start, end in ((0, 1023), (1024, 4095), (4096, len(original)-1)):
            value = f'bytes={start}-' + (str(end) if start != 4096 else '')
            status, headers, body = self.request(film['path'], {'Range': value})
            self.assertEqual(206, status)
            self.assertEqual(original[start:end+1], body)
            self.assertEqual(f'bytes {start}-{end}/{len(original)}', headers['Content-Range'])
            chunks.append(body)
        self.assertEqual(original, b''.join(chunks))

    def test_oversized_end_is_clipped_and_suffix_is_satisfiable(self):
        film = asset(1); original = self.request(film['path'])[2]
        for value, expected in [('bytes=10-999999999', original[10:]),
                                ('bytes=-37', original[-37:]),
                                ('bytes=-999999999', original)]:
            with self.subTest(value=value):
                status, headers, body = self.request(film['path'], {'Range': value})
                self.assertEqual(206, status)
                self.assertEqual(expected, body)
                self.assertEqual(str(len(expected)), headers['Content-Length'])

    def test_unsatisfiable_ranges_report_current_length(self):
        film = asset(1)
        for value in (f"bytes={film['length']}-", 'bytes=12-11', 'bytes=-0'):
            with self.subTest(value=value):
                status, headers, _ = self.request(film['path'], {'Range': value})
                self.assertEqual(416, status)
                self.assertEqual(f"bytes */{film['length']}", headers['Content-Range'])

    def test_extremely_large_numeral_does_not_abort_connection(self):
        film = asset(1)
        status, _, _ = self.request(film['path'], {'Range': 'bytes=' + '9'*5000 + '-'})
        self.assertIn(status, (400, 416, 431))
        self.assertEqual(200, self.request(film['path'])[0])

    def test_if_range_strong_match_and_mismatch_select_different_results(self):
        film = asset(1); original = self.request(film['path'])[2]
        exact = '"' + film['sha256'] + '"'
        status, _, body = self.request(film['path'], {'Range': 'bytes=0-7', 'If-Range': exact})
        self.assertEqual((206, original[:8]), (status, body))
        for old in ('"old-version"', 'W/' + exact, 'Wed, 21 Oct 2015 07:28:00 GMT'):
            status, headers, body = self.request(film['path'], {'Range': 'bytes=0-7', 'If-Range': old})
            self.assertEqual((200, original), (status, body))
            self.assertNotIn('Content-Range', headers)

    def test_replacing_catalog_never_changes_old_version_identity(self):
        old = asset(1); before = self.request(old['path'])
        self.assertEqual(200, self.request('/commands', body=self.command('replace', {'version': 2}))[0])
        current = json.loads(self.request('/view')[2])['data']['film']
        self.assertEqual(2, current['version'])
        self.assertNotEqual(old['sha256'], current['sha256'])
        after = self.request(old['path'])
        self.assertEqual(before[2], after[2]); self.assertEqual(before[1]['ETag'], after[1]['ETag'])

    def test_corrupt_bytes_keep_length_but_fail_declared_hash_and_recovery(self):
        film = asset(1); original = self.request(film['path'])[2]
        self.request('/commands', body=self.command('corrupt', {'enabled': True}))
        status, headers, corrupt = self.request(film['path'])
        self.assertEqual(200, status); self.assertEqual(len(original), len(corrupt))
        self.assertNotEqual(film['sha256'], hashlib.sha256(corrupt).hexdigest())
        self.assertEqual('"' + film['sha256'] + '"', headers['ETag'])
        self.assertEqual(original[1:], corrupt[1:])
        self.request('/commands', body=self.command('corrupt', {'enabled': False}))
        self.assertEqual(original, self.request(film['path'])[2])

    def test_lost_replace_receipt_replays_after_reconstruction_without_current_rollback(self):
        command = self.command('replace', {'version': 2})
        self.request('/__control', body={'drop_reply': True})
        with self.assertRaises(http.client.RemoteDisconnected): self.request('/commands', body=command)
        self.request('/__control', body={})
        reconstructed = Service(self.database)
        historical = reconstructed.route('POST', '/commands', command)[1]
        self.assertEqual(2, historical['data']['film']['version'])
        self.request('/commands', body=self.command('replace', {'version': 1}))
        replay = json.loads(self.request('/commands', body=command)[2])
        self.assertEqual(historical, replay)
        self.assertEqual(1, json.loads(self.request('/view')[2])['data']['film']['version'])
        self.assertEqual(2, json.loads(self.request('/__control')[2])['commandCount'])

    def test_unavailable_and_unknown_assets_never_return_media_success(self):
        self.assertEqual(404, self.request('/media/../base.py')[0])
        self.assertEqual(404, self.request('/media/stillness-v3.mp4')[0])
        self.request('/__control', body={'unavailable': True})
        self.assertEqual(503, self.request(asset(1)['path'])[0])
        self.assertEqual(503, self.request('/view')[0])
        self.request('/__control', body={})
        self.assertEqual(200, self.request(asset(1)['path'])[0])


if __name__ == '__main__': unittest.main()
