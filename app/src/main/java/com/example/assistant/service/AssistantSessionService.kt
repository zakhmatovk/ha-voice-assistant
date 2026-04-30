package com.example.assistant.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Фабрика сессий. Система создаёт новый экземпляр AssistantVoiceSession
 * при каждом срабатывании ассистента (кнопка Home / wake-word).
 */
class AssistantSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantVoiceSession(this)
    }
}
