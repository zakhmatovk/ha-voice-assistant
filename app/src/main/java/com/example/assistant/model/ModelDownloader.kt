package com.example.assistant.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает модель с серверов Google (тот же источник, что использует Edge Gallery).
 * Авторизация не требуется.
 *
 * Скачивание идёт во временный .tmp файл — при ошибке основной файл не портится.
 */
class ModelDownloader(private val context: Context) {

    fun download(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Connecting)

        val dest    = File(context.filesDir, MODEL_FILE)
        val tmpFile = File(context.filesDir, "$MODEL_FILE.tmp")

        var connection: HttpURLConnection? = null
        try {
            var url = URL(DOWNLOAD_URL)
            var redirects = 0

            while (redirects < MAX_REDIRECTS) {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "AssistantApp/1.0")
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

                if (code != 200) {
                    throw IllegalStateException("HTTP $code: ${connection.responseMessage}")
                }
                break
            }

            val totalBytes = connection!!.contentLengthLong
            Log.d(TAG, "Скачиваю: $totalBytes байт")

            tmpFile.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        emit(DownloadProgress.Downloading(downloaded, totalBytes))
                    }
                }
            }

            tmpFile.renameTo(dest)
            Log.d(TAG, "Модель сохранена: ${dest.absolutePath}")
            emit(DownloadProgress.Done)

        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "Ошибка скачивания", e)
            emit(DownloadProgress.Error(e.message ?: "Неизвестная ошибка"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "ModelDownloader"
        const val MODEL_FILE = "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
        private const val MAX_REDIRECTS = 10
        const val DOWNLOAD_URL =
            "https://dl.google.com/google-ai-edge-gallery/android/gemma4/20260325/" +
            "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
    }
}

sealed class DownloadProgress {
    data object Connecting : DownloadProgress()
    data class Downloading(val bytesDone: Long, val totalBytes: Long) : DownloadProgress()
    data object Done : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
}
