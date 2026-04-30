package com.example.assistant.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Загружает и распаковывает vosk-model-small-ru (~45 МБ) во внутреннее хранилище.
 *
 * После распаковки модель доступна по пути [modelDir].
 * Передайте [modelDir].absolutePath в конструктор VoskWakeWordDetector.
 */
class VoskModelManager(private val context: Context) {

    fun modelExists(): Boolean = modelDir().isDirectory

    fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    fun download(): Flow<VoskDownloadProgress> = flow {
        val zipTemp = File(context.filesDir, "$MODEL_DIR_NAME.zip.tmp")

        try {
            // --- 1. Скачивание ZIP ---
            var connection: HttpURLConnection? = null
            try {
                var url = URL(MODEL_URL)
                var redirects = 0
                while (redirects < MAX_REDIRECTS) {
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15_000
                        readTimeout    = 30_000
                        connect()
                    }
                    val code = connection.responseCode
                    if (code in 301..308) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IllegalStateException("Redirect без Location header")
                        connection.disconnect()
                        url = URL(url, location)
                        redirects++
                        continue
                    }
                    if (code != 200) throw IllegalStateException("HTTP $code: ${connection.responseMessage}")
                    break
                }

                val total = connection!!.contentLengthLong
                Log.d(TAG, "Скачиваю Vosk-модель: $total байт")

                zipTemp.outputStream().use { out ->
                    connection.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            downloaded += read
                            emit(VoskDownloadProgress.Downloading(downloaded, total))
                        }
                    }
                }
            } finally {
                connection?.disconnect()
            }

            // --- 2. Распаковка ZIP ---
            emit(VoskDownloadProgress.Extracting)
            val target = modelDir()
            target.deleteRecursively()

            ZipInputStream(zipTemp.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(context.filesDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            zipTemp.delete()

            Log.d(TAG, "Vosk-модель распакована: ${target.absolutePath}")
            emit(VoskDownloadProgress.Done)

        } catch (e: Exception) {
            zipTemp.delete()
            Log.e(TAG, "Ошибка загрузки Vosk-модели", e)
            emit(VoskDownloadProgress.Error(e.message ?: "Неизвестная ошибка"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG            = "VoskModelManager"
        private const val MODEL_DIR_NAME = "vosk-model-small-ru-0.22"
        private const val MAX_REDIRECTS  = 10
        const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        const val MODEL_SIZE_APPROX = 45_000_000L // ~45 МБ
    }
}

sealed class VoskDownloadProgress {
    data class Downloading(val bytesDone: Long, val totalBytes: Long) : VoskDownloadProgress()
    data object Extracting : VoskDownloadProgress()
    data object Done : VoskDownloadProgress()
    data class Error(val message: String) : VoskDownloadProgress()
}
