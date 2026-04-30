package com.example.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Точка входа Android Voice Interaction API.
 *
 * Жизненный цикл:
 * 1. Пользователь выбирает приложение как ассистента в системных настройках.
 * 2. Android биндит этот сервис при старте системы.
 * 3. При триггере (долгий Home / кнопка ассистента) система вызывает
 *    showSession() → AssistantSessionService.onNewSession() → AssistantVoiceSession.
 *
 * Wake-word через аппаратный DSP (AlwaysOnHotwordDetector) требует системного разрешения
 * MANAGE_VOICE_KEYPHRASES — недоступно обычным приложениям.
 * Поэтому программный wake-word реализован в WakeWordListenerService (Vosk).
 */
class AssistantVoiceInteractionService : VoiceInteractionService() {

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_WAKE_WORD_DETECTED) {
                Log.i(TAG, "Wake-word получен → showSession()")
                showSession(null, 0)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService ready — запускаем wake-word детектор")
        registerReceiver(
            wakeWordReceiver,
            IntentFilter(ACTION_WAKE_WORD_DETECTED),
            if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
        )
        startForegroundService(Intent(this, WakeWordListenerService::class.java))
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d(TAG, "VoiceInteractionService shutdown")
        runCatching { unregisterReceiver(wakeWordReceiver) }
        stopService(Intent(this, WakeWordListenerService::class.java))
    }

    companion object {
        private const val TAG = "AssistantVIS"
        const val ACTION_WAKE_WORD_DETECTED = "com.example.assistant.WAKE_WORD_DETECTED"
    }
}
