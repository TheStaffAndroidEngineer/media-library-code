package staff.android.shelf

import android.util.AtomicFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

fun validateFilm(film: JSONObject) {
    require(film.getString("id") == "stillness" && film.getString("title").isNotBlank())
    require(film.get("version") is Int && film.getInt("version") in 1..2)
    require(film.get("length") is Int && film.getInt("length") in 1..2_000_000)
    require(film.getString("sha256").matches(Regex("[0-9a-f]{64}")))
    require(film.getString("path") == "/media/stillness-v${film.getInt("version")}.mp4")
}

class OfflineStore(private val directory: File) {
    private val manifest = AtomicFile(File(directory, "offline-film.json"))
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

    private fun file(film: JSONObject) = File(directory, "${film.getString("sha256")}.mp4")

    fun saved(): JSONObject? {
        if (!manifest.baseFile.exists() && !File(manifest.baseFile.path + ".bak").exists())
            return null
        val wrapper = JSONObject(String(manifest.readFully(), Charsets.UTF_8))
        require(wrapper.has("film"))
        if (wrapper.isNull("film")) return null
        val film = wrapper.getJSONObject("film")
        validateFilm(film)
        val local = file(film)
        require(local.length() == film.getInt("length").toLong())
        val bytes =
            local.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(n <= film.getInt("length") - out.size())
                    out.write(buffer, 0, n)
                }
                out.toByteArray().also { require(it.size == film.getInt("length")) }
            }
        require(digest(bytes) == film.getString("sha256"))
        return film
    }

    fun cleanUnreferenced() {
        val current = saved()?.getString("sha256")
        directory
            .listFiles()
            ?.filter {
                it.name == "download.partial" ||
                    (it.name.matches(Regex("[0-9a-f]{64}\\.mp4")) && it.name != "$current.mp4")
            }
            ?.forEach { it.delete() }
    }

    fun uri(film: JSONObject) = android.net.Uri.fromFile(file(film)).toString()

    private fun publish(film: JSONObject?) {
        val output = manifest.startWrite()
        try {
            output.write(JSONObject().put("film", film ?: JSONObject.NULL).toString().toByteArray())
            manifest.finishWrite(output)
        } catch (e: Exception) {
            manifest.failWrite(output)
            throw e
        }
    }

    fun download(film: JSONObject, checkActive: () -> Unit) {
        validateFilm(film)
        val temporary = File(directory, "download.partial")
        try {
            client
                .newCall(
                    Request.Builder().url("http://10.0.2.2:8917${film.getString("path")}").build())
                .execute()
                .use { response ->
                    require(
                        response.code == 200 &&
                            response.header("ETag") == "\"${film.getString("sha256")}\"")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0
                    FileOutputStream(temporary).use { output ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                checkActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                require(n <= film.getInt("length") - count)
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                count += n
                            }
                            require(
                                count == film.getInt("length") &&
                                    digest.digest().joinToString("") { "%02x".format(it) } ==
                                        film.getString("sha256"))
                            output.fd.sync()
                        }
                    }
                    checkActive()
                    val final = file(film)
                    android.system.Os.rename(temporary.path, final.path)
                    // The complete immutable file precedes the pointer. A crash can orphan bytes,
                    // never advertise partial bytes.
                    publish(film)
                }
        } finally {
            temporary.delete()
        }
    }

    fun remove() {
        val film = saved()
        publish(null)
        if (film != null) file(film).delete()
    }

    companion object {
        fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
