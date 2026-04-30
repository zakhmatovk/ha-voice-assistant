package com.example.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * Сессия голосового взаимодействия через Android Voice Interaction API.
 *
 * Когда система биндит AssistantVoiceInteractionService и пользователь
 * держит Home / нажимает кнопку ассистента, Android показывает эту сессию.
 *
 * Вся логика записи и инференса обрабатывается в AssistantViewModel,
 * чтобы избежать двойной инициализации LiteRT-LM движка
 * (движок поддерживает только одну Conversation одновременно).
 * Сессия запускает MainActivity с ACTION_ASSIST, которая подхватывает управление.
 */
class AssistantVoiceSession(private val context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Сессия открыта → передаём в MainActivity")

        // Запускаем MainActivity с ACTION_ASSIST — там AssistantViewModel обработает команду
        val intent = Intent(context, Class.forName("com.example.assistant.MainActivity")).apply {
            action = android.content.Intent.ACTION_ASSIST
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
        hide()
    }

    companion object {
        private const val TAG = "AssistantVoiceSession"
    }
}
