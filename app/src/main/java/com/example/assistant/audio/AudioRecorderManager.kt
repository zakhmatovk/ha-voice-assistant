package com.example.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Запись аудио с микрофона и конвертация в WAV-формат для подачи в LiteRT-LM.
 *
 * Параметры: 16 кГц, моно, PCM 16-bit — стандарт для речевых моделей.
 * Возвращает полноценный WAV (с заголовком RIFF/fmt/data).
 */
class AudioRecorderManager {

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null

    /**
     * Записывает аудио до конца фразы пользователя и возвращает WAV-байты.
     *
     * Логика остановки (VAD):
     * - Ждём начала речи (RMS > [SPEECH_RMS_THRESHOLD]) минимум [MIN_SPEECH_WAIT_MS].
     * - После того как речь была зафиксирована, ждём паузы [SILENCE_STOP_MS].
     * - Жёсткий потолок [MAX_RECORD_MS] — на случай если речь не прерывается.
     *
     * [preRollPcm] — скользящий буфер детектора (содержит "Алекса, <команда>").
     * [handoffRecord] — живой AudioRecord от детектора (уже пишет, без разрыва).
     *
     * Вызывается из IO-корутины.
     */
    suspend fun record(
        preRollPcm: ByteArray = ByteArray(0),
        handoffRecord: AudioRecord? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)

        val record = handoffRecord ?: AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            encoding,
            minBuf
        ).also { it.startRecording() }

        audioRecord = record
        isRecording = true

        val preRollMs = preRollPcm.size.toLong() * 1000L / (sampleRate * 2)
        Log.d(TAG, "Запись начата (handoff=${handoffRecord != null}, preRoll=${preRollMs}мс)")

        val output = ByteArrayOutputStream(preRollPcm.size + sampleRate * 2 * 6)
        if (preRollPcm.isNotEmpty()) output.write(preRollPcm)

        val buffer = ByteArray(minBuf)

        // VAD-состояние
        var speechDetected = false
        var silenceBytesAfterSpeech = 0
        var totalNewBytes = 0

        val silenceStopBytes  = (sampleRate * 2 * SILENCE_STOP_MS  / 1000).toInt()
        val maxNewBytes       = (sampleRate * 2 * MAX_NEW_RECORD_MS / 1000).toInt()
        val minSpeechWaitBytes = (sampleRate * 2 * MIN_SPEECH_WAIT_MS / 1000).toInt()

        try {
            while (isRecording && totalNewBytes < maxNewBytes) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                output.write(buffer, 0, read)
                totalNewBytes += read

                val rms = calculateRms(buffer, read)

                if (rms >= SPEECH_RMS_THRESHOLD) {
                    if (!speechDetected) {
                        Log.d(TAG, "Речь обнаружена (RMS=${"%.0f".format(rms)}) через ${totalNewBytes * 1000 / (sampleRate * 2)}мс")
                    }
                    speechDetected = true
                    silenceBytesAfterSpeech = 0
                } else if (speechDetected) {
                    silenceBytesAfterSpeech += read
                    if (silenceBytesAfterSpeech >= silenceStopBytes) {
                        Log.d(TAG, "Тишина ${SILENCE_STOP_MS}мс — завершаем запись")
                        break
                    }
                } else if (totalNewBytes >= minSpeechWaitBytes) {
                    // Речи не было совсем — заканчиваем (команда уже в preRoll)
                    Log.d(TAG, "Речи нет в новом аудио после ${MIN_SPEECH_WAIT_MS}мс — используем только preRoll")
                    break
                }
            }
        } finally {
            record.stop()
            record.release()
            audioRecord = null
            isRecording = false
            val totalMs = (preRollPcm.size + totalNewBytes).toLong() * 1000L / (sampleRate * 2)
            Log.d(TAG, "Запись завершена: ${preRollPcm.size + totalNewBytes} байт (~${totalMs}мс)")
        }

        buildWav(output.toByteArray(), sampleRate, channels = 1)
    }

    /** Немедленно остановить запись (например, при закрытии сессии). */
    fun stop() {
        isRecording = false
        audioRecord?.stop()
    }

    private fun calculateRms(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var i = 0
        while (i < len - 1) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample
            i += 2
        }
        val samples = len / 2
        return if (samples > 0) sqrt(sum / samples) else 0.0
    }

    /**
     * Оборачивает сырой PCM в корректный WAV-контейнер (RIFF/WAVE, 44-байтный заголовок).
     * LiteRT-LM ожидает Content.AudioBytes с mimeType = "audio/wav".
     */
    private fun buildWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buf = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcm.size)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcm.size)
        buf.put(pcm)

        return buf.array()
    }

    companion object {
        private const val TAG = "AudioRecorder"

        /** RMS выше которого считается речью (тишина ~50-150, речь ~800-4000). */
        private const val SPEECH_RMS_THRESHOLD = 500.0

        /** Тишина после речи — сигнал конца фразы. */
        private const val SILENCE_STOP_MS = 700L

        /** Если речи в новом аудио нет совсем — используем только preRoll. */
        private const val MIN_SPEECH_WAIT_MS = 2_000L

        /** Жёсткий потолок новой записи (не считая preRoll). */
        private const val MAX_NEW_RECORD_MS = 15_000L
    }
}
