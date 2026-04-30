package com.example.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

/**
 * Детектор wake-word на базе Vosk (офлайн, русский язык).
 *
 * Защита от ложных срабатываний:
 * - Только финальные результаты (partial убран — слишком шумный)
 * - Точное совпадение: text.trim() == keyword
 * - Энергетический gate: RMS последних буферов должен превышать [MIN_SPEECH_RMS]
 * - Cooldown [COOLDOWN_MS] между срабатываниями
 *
 * Скользящий буфер [ROLLING_BUFFER_MS]:
 * Все прочитанные блоки PCM складываются в скользящий буфер последних N секунд.
 * При срабатывании весь буфер передаётся как pre-roll в AudioRecorderManager —
 * это гарантирует сохранность команды даже если пользователь говорит
 * "Алекса, выключи свет" без паузы (Vosk читает команду до детектирования).
 *
 * После срабатывания AudioRecord передаётся живым — без разрыва записи.
 */
class VoskWakeWordDetector(
    private val modelPath: String,
    private val keyword: String = "алекса",
    private val onDetected: (preRollPcm: ByteArray, audioRecord: AudioRecord) -> Unit
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    @Volatile private var audioRecord: AudioRecord? = null

    @Volatile private var running = false
    private var detectorThread: Thread? = null
    private var lastDetectionTime = 0L

    // Скользящее окно RMS последних буферов (~3 с аудио)
    private val rmsWindow = FloatArray(RMS_WINDOW_SIZE)
    private var rmsWindowIdx = 0

    // Скользящий буфер PCM: все прочитанные блоки, суммарно не более ROLLING_BUFFER_MAX_BYTES
    private val rollingChunks = ArrayDeque<ByteArray>()
    private var rollingBufferBytes = 0

    fun start() {
        if (running) return
        running = true

        model      = Model(modelPath)
        recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply {
            setGrammar("""["$keyword", "[unk]"]""")
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Большой internal буфер AudioRecord чтобы не терять аудио пока идёт broadcast-цепочка
        val bufferSize = maxOf(minBuf, SAMPLE_RATE * 2 * 2) // 2 секунды

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord = record
        record.startRecording()

        detectorThread = Thread({
            val buf = ByteArray(CHUNK_SIZE)
            Log.d(TAG, "Wake-word listening for '$keyword' (rolling buffer: ${ROLLING_BUFFER_MS}мс)")

            try {
                while (running) {
                    val read = record.read(buf, 0, buf.size)
                    if (read <= 0) continue

                    // Добавляем блок в скользящий буфер
                    val chunk = buf.copyOf(read)
                    rollingChunks.addLast(chunk)
                    rollingBufferBytes += read
                    // Обрезаем старое если буфер переполнен
                    while (rollingBufferBytes > ROLLING_BUFFER_MAX_BYTES && rollingChunks.isNotEmpty()) {
                        rollingBufferBytes -= rollingChunks.removeFirst().size
                    }

                    // Обновляем скользящее окно энергии
                    rmsWindow[rmsWindowIdx % RMS_WINDOW_SIZE] = calculateRms(buf, read).toFloat()
                    rmsWindowIdx++

                    // Только финальные результаты
                    if (!recognizer!!.acceptWaveForm(buf, read)) continue

                    val text = JSONObject(recognizer!!.result).optString("text").trim()
                    if (text != keyword) continue

                    // Энергетический gate
                    val maxRms = rmsWindow.max()
                    if (maxRms < MIN_SPEECH_RMS) {
                        Log.d(TAG, "Отклонено (тихо): maxRms=${"%.0f".format(maxRms)}")
                        continue
                    }

                    // Cooldown
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime < COOLDOWN_MS) {
                        Log.d(TAG, "Отклонено (cooldown): ${now - lastDetectionTime}мс")
                        continue
                    }

                    Log.i(TAG, "Wake word: '$keyword' maxRms=${"%.0f".format(maxRms)}")
                    lastDetectionTime = now
                    rmsWindow.fill(0f)
                    recognizer!!.reset()
                    running = false

                    // Собираем весь скользящий буфер как pre-roll
                    val preRoll = buildPreRoll()
                    val preRollMs = preRoll.size.toLong() * 1000L / (SAMPLE_RATE * 2)
                    Log.d(TAG, "Pre-roll: ${preRoll.size} байт (~${preRollMs}мс), передаём AudioRecord")

                    // Передаём AudioRecord живым — AudioRecorderManager читает дальше без разрыва
                    audioRecord = null // чтобы finally не остановил переданный AudioRecord
                    onDetected(preRoll, record)
                }
            } finally {
                // Если вышли по stop() извне (audioRecord != null), освобождаем
                val r = audioRecord
                audioRecord = null
                try { r?.stop(); r?.release() } catch (_: Exception) {}
                recognizer?.close()
                recognizer = null
                model?.close()
                model = null
                Log.d(TAG, "Wake-word detection stopped")
            }
        }, "vosk-wake-word").also { it.start() }
    }

    fun stop() {
        running = false
        detectorThread?.interrupt()
        detectorThread = null
        val r = audioRecord
        audioRecord = null
        try { r?.stop(); r?.release() } catch (_: Exception) {}
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    /** Собирает все накопленные чанки скользящего буфера в один ByteArray. */
    private fun buildPreRoll(): ByteArray {
        val out = ByteArrayOutputStream(rollingBufferBytes)
        for (chunk in rollingChunks) out.write(chunk)
        return out.toByteArray()
    }

    /** RMS PCM-16 LE буфера (амплитуда 0..32767). */
    private fun calculateRms(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var i = 0
        while (i < len - 1) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample
            i += 2
        }
        val samples = len / 2
        return if (samples > 0) Math.sqrt(sum / samples) else 0.0
    }

    companion object {
        private const val TAG              = "VoskWakeWordDetector"
        private const val SAMPLE_RATE      = 16_000
        private const val MIN_SPEECH_RMS   = 400.0   // тишина ~50-150, речь ~800-4000
        private const val COOLDOWN_MS      = 3_000L
        private const val RMS_WINDOW_SIZE  = 6        // последние ~3 с при чанке ~500мс
        private const val CHUNK_SIZE       = SAMPLE_RATE / 2 * 2   // ~500мс (16000 байт)
        private const val ROLLING_BUFFER_MS = 5_000L  // держим последние 5 секунд
        private val ROLLING_BUFFER_MAX_BYTES =
            (SAMPLE_RATE * 2 * ROLLING_BUFFER_MS / 1000).toInt()   // 160000 байт
    }
}
