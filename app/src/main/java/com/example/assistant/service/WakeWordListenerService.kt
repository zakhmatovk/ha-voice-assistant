package com.example.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioRecord
import android.os.IBinder
import android.util.Log
import com.example.assistant.audio.VoskModelManager
import com.example.assistant.audio.VoskWakeWordDetector

/**
 * Foreground-сервис непрерывного прослушивания wake-word "Алекса".
 *
 * После срабатывания детектор передаёт живой [AudioRecord] и хвост PCM в [pendingAudioRecord]
 * и [pendingTailPcm] — AudioRecorderManager подхватывает запись без разрыва.
 * Перезапуск детектора происходит по завершении сессии через [ACTION_RESTART_DETECTOR] intent.
 */
class WakeWordListenerService : Service() {

    private var detector: VoskWakeWordDetector? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART_DETECTOR && detector == null) {
            Log.d(TAG, "Получен запрос на перезапуск детектора")
            startDetector()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        detector?.stop()
        detector = null
    }

    private fun startDetector() {
        val modelDir = VoskModelManager(this).modelDir()
        if (!modelDir.isDirectory) {
            Log.w(TAG, "Vosk-модель не найдена: ${modelDir.absolutePath}. " +
                       "Скачайте модель через экран настройки приложения.")
            return
        }
        try {
            detector = VoskWakeWordDetector(
                modelPath  = modelDir.absolutePath,
                keywords   = WAKE_WORDS,
                onDetected = ::onWakeWordDetected
            )
            detector!!.start()
            Log.d(TAG, "Vosk детектор запущен (слова: $WAKE_WORDS)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска Vosk детектора", e)
        }
    }

    private fun onWakeWordDetected(preRollPcm: ByteArray, audioRecord: AudioRecord) {
        val preRollMs = preRollPcm.size.toLong() * 1000L / (16_000 * 2)
        Log.i(TAG, "Wake-word → pre-roll ${preRollPcm.size} байт (~${preRollMs}мс), передаём AudioRecord")
        pendingTailPcm    = preRollPcm
        pendingAudioRecord = audioRecord
        detector = null // детектор отдал AudioRecord, считается остановленным

        val intent = Intent(AssistantVoiceInteractionService.ACTION_WAKE_WORD_DETECTED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        val channelId = "wake_word_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Wake Word Listener", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Ассистент активен")
            .setContentText("Скажите «Алекса», «Алекс» или «Алексей» для активации")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        private const val TAG             = "WakeWordListener"
        private const val NOTIFICATION_ID = 1
        private val WAKE_WORDS = setOf("алекса", "алекс", "алексей")

        /** Intent action для перезапуска детектора из ViewModel после завершения сессии. */
        const val ACTION_RESTART_DETECTOR = "com.example.assistant.RESTART_WAKE_WORD_DETECTOR"

        /**
         * Pre-roll PCM (16 кГц, моно, 16-bit): скользящий буфер детектора последних ~5с,
         * содержит "Алекса, <команда>" целиком если сказано без паузы.
         */
        @Volatile var pendingTailPcm: ByteArray? = null

        /**
         * Живой AudioRecord от детектора — уже пишет аудио.
         * AudioRecorderManager подхватывает его без разрыва.
         * После использования должен быть остановлен и освобождён рекордером.
         */
        @Volatile var pendingAudioRecord: AudioRecord? = null
    }
}
