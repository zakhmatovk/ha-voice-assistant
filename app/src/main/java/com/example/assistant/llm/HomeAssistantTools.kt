package com.example.assistant.llm

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Инструменты function calling для управления Home Assistant.
 * Implements [ToolSet] — LiteRT-LM использует рефлексию для генерации OpenAPI-схемы
 * из методов, помеченных @Tool / @ToolParam.
 *
 * TODO: замените заглушки на реальные HTTP-вызовы Home Assistant REST API:
 *   POST /api/services/<domain>/<service>
 *   GET  /api/states/<entity_id>
 *   Authorization: Bearer <LONG_LIVED_ACCESS_TOKEN>
 */
class HomeAssistantTools : ToolSet {

    @Tool(description = "Включить или выключить свет в указанной комнате или зоне")
    fun controlLight(
        @ToolParam(description = "Название комнаты или зоны, например: гостиная, спальня") room: String,
        @ToolParam(description = "true — включить, false — выключить") turnOn: Boolean,
        @ToolParam(description = "Яркость от 0 до 255 (необязательно)") brightness: Int?
    ): String {
        Log.d(TAG, "controlLight: room=$room, on=$turnOn, brightness=$brightness")
        // TODO: val entity = "light.${room.lowercase().replace(' ', '_')}"
        // TODO: haApi.callService(if (turnOn) "light/turn_on" else "light/turn_off", entity, brightness)
        return buildResponse("light.$room", if (turnOn) "on" else "off")
    }

    @Tool(description = "Установить целевую температуру термостата в комнате или зоне")
    fun setTemperature(
        @ToolParam(description = "Зона или комната") zone: String,
        @ToolParam(description = "Целевая температура в градусах Цельсия") temperature: Float
    ): String {
        Log.d(TAG, "setTemperature: zone=$zone, temp=$temperature")
        // TODO: haApi.callService("climate/set_temperature", "climate.$zone", mapOf("temperature" to temperature))
        return buildResponse("climate.$zone", "heat", "temperature" to temperature)
    }

    @Tool(description = "Заблокировать или разблокировать замок")
    fun controlLock(
        @ToolParam(description = "Название замка или двери, например: входная дверь, гараж") door: String,
        @ToolParam(description = "true — заблокировать, false — разблокировать") lock: Boolean
    ): String {
        Log.d(TAG, "controlLock: door=$door, lock=$lock")
        // TODO: haApi.callService(if (lock) "lock/lock" else "lock/unlock", "lock.$door")
        return buildResponse("lock.$door", if (lock) "locked" else "unlocked")
    }

    @Tool(description = "Получить текущее состояние устройства или сенсора")
    fun getDeviceState(
        @ToolParam(description = "ID объекта Home Assistant, например: sensor.temperature_living_room") entityId: String
    ): String {
        Log.d(TAG, "getDeviceState: entityId=$entityId")
        // TODO: return haApi.getState(entityId)
        return """{"entity_id":"$entityId","state":"unknown","attributes":{}}"""
    }

    @Tool(description = "Активировать сцену в Home Assistant")
    fun activateScene(
        @ToolParam(description = "Название сцены, например: вечер кино, доброе утро") sceneName: String
    ): String {
        Log.d(TAG, "activateScene: scene=$sceneName")
        // TODO: haApi.callService("scene/turn_on", "scene.${sceneName.lowercase().replace(' ', '_')}")
        return """{"status":"ok","scene":"$sceneName","result":"activated"}"""
    }

    @Tool(description = "Запустить или остановить медиапроигрыватель")
    fun controlMediaPlayer(
        @ToolParam(description = "Комната или название плеера") player: String,
        @ToolParam(description = "play, pause или stop") action: String
    ): String {
        Log.d(TAG, "controlMediaPlayer: player=$player, action=$action")
        // TODO: haApi.callService("media_player/$action", "media_player.$player")
        return buildResponse("media_player.$player", action)
    }

    private fun buildResponse(entity: String, state: String, vararg extras: Pair<String, Any>): String {
        val extraJson = extras.joinToString(",") { (k, v) ->
            """"$k":${if (v is String) "\"$v\"" else v}"""
        }
        val comma = if (extraJson.isNotEmpty()) "," else ""
        return """{"status":"ok","entity_id":"$entity","new_state":"$state"$comma$extraJson}"""
    }

    companion object {
        private const val TAG = "HATools"
    }
}
