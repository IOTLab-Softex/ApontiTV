package br.com.softextv.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class PlaylistMediaCache(context: Context) {
    private val cacheDir = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    fun localUri(url: String): String? = cachedFile(url)?.let { Uri.fromFile(it).toString() }

    fun delete(url: String) {
        val file = fileFor(url)
        file.delete()
        File(cacheDir, "${file.name}.download").delete()
    }

    @Synchronized
    fun synchronize(urls: Collection<String>) {
        val normalizedUrls = urls.map(String::trim).filter(String::isNotBlank).distinct()
        val keepNames = normalizedUrls.mapTo(hashSetOf()) { fileFor(it).name }
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.removeSuffix(".download") !in keepNames) file.delete()
        }
        normalizedUrls.forEach { url ->
            if (cachedFile(url) == null) runCatching { download(url) }
        }
    }

    private fun cachedFile(url: String): File? = fileFor(url).takeIf { it.isFile && it.length() > 0L }

    private fun download(url: String) {
        val destination = fileFor(url)
        val temporary = File(cacheDir, "${destination.name}.download")
        temporary.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> temporary.outputStream().buffered().use(input::copyTo) }
            if (temporary.length() <= 0L) error("Arquivo de mídia vazio")
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) { temporary.copyTo(destination, overwrite = true); temporary.delete() }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun fileFor(url: String): File {
        val extension = runCatching { URL(url).path.substringAfterLast('.', "bin") }.getOrDefault("bin")
            .lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "bin"
        return File(cacheDir, "${sha256(url)}.$extension")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CACHE_DIR_NAME = "playlist_media"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 180_000
    }
}
