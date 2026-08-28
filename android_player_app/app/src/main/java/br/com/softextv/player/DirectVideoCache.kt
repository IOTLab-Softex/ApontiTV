package br.com.softextv.player

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DirectVideoCache(context: Context) {
    private val cacheDir = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    fun cachedFileFor(videoUrl: String): File? {
        val file = cacheFile(videoUrl)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    fun delete(videoUrl: String) {
        val file = cacheFile(videoUrl)
        if (file.exists()) file.delete()
    }

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) {
        val percent: Int?
            get() = totalBytes.takeIf { it > 0L }?.let { ((downloadedBytes * 100L) / it).toInt().coerceIn(0, 100) }
    }

    fun download(videoUrl: String, onProgress: (Progress) -> Unit = {}): File {
        cachedFileFor(videoUrl)?.let { return it }

        val destination = cacheFile(videoUrl)
        val tempFile = File(cacheDir, "${destination.name}.download")
        if (tempFile.exists()) tempFile.delete()
        deleteOtherVideos(destination)

        val connection = (URL(videoUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Servidor retornou HTTP ${connection.responseCode}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            onProgress(Progress(downloadedBytes, totalBytes))

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(Progress(downloadedBytes, totalBytes))
                    }
                }
            }

            if (tempFile.length() <= 0L) {
                tempFile.delete()
                throw IllegalStateException("Download do video ficou vazio")
            }

            if (destination.exists()) destination.delete()
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }

            deleteOtherVideos(destination)
            return destination
        } finally {
            connection.disconnect()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun cacheFile(videoUrl: String): File {
        val extension = URL(videoUrl).path.substringAfterLast('.', "mp4")
            .takeIf { it.length in 2..5 }
            ?: "mp4"
        return File(cacheDir, "${sha256(videoUrl)}.$extension")
    }

    private fun deleteOtherVideos(keepFile: File) {
        cacheDir.listFiles()?.forEach { file ->
            if (file.absolutePath != keepFile.absolutePath) {
                file.delete()
            }
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CACHE_DIR_NAME = "direct_videos"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
