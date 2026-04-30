package com.example.assistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Обёртка над LiteRT-LM Engine для инференса Gemma 4 e2b на устройстве.
 *
 * LiteRT-LM допускает только ОДНУ активную Conversation на Engine одновременно.
 * Поэтому [conversation] закрывается перед каждым новым вызовом [processCommand].
 * [mutex] защищает от одновременных вызовов из нескольких корутин.
 */
class LlmManager(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val mutex = Mutex()
    private val haTools = HomeAssistantTools()

    /** Бэкенд на котором запущена модель, null — ещё не инициализирована. */
    var activeBackend: String? = null
        private set

    private val systemPrompt =
        "Ты голосовой ассистент умного дома на базе Home Assistant. " +
        "В аудио может присутствовать шум или речь до обращения к тебе по имени «Алекса». " +
        "Игнорируй всё, что звучит до слова «Алекса» — команда начинается после него. " +
        "Когда пользователь просит управлять устройствами — используй доступные инструменты. " +
        "Начинай ответ со строки «[Распознано: <дословный текст команды после слова Алекса>]», затем с новой строки подтверди, что именно ты сделал. " +
        "Отвечай на языке пользователя. " +
        "Если команда неясна — уточни одним вопросом."

    /**
     * Загружает модель и инициализирует движок.
     * Если движок уже инициализирован — ничего не делает.
     * Занимает до 10 с — вызывать из Dispatchers.IO.
     */
    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (engine != null) {
                Log.d(TAG, "Движок уже инициализирован, пропускаем")
                return@withContext
            }
            val (eng, backend) =
                tryInitEngine(modelPath, Backend.GPU(), "GPU")
                ?: tryInitEngine(modelPath, Backend.NPU(), "NPU")
                ?: tryInitEngine(modelPath, Backend.CPU(), "CPU")
                ?: error("Не удалось инициализировать движок")
            engine = eng
            activeBackend = backend
        }
    }

    private fun tryInitEngine(modelPath: String, backend: Backend, label: String): Pair<Engine, String>? = try {
        val config = EngineConfig(
            modelPath    = modelPath,
            backend      = backend,
            audioBackend = Backend.CPU()
        )
        val eng = Engine(config).also {
            it.initialize()
            Log.d(TAG, "Движок инициализирован на $label: $modelPath")
        }
        eng to label
    } catch (e: Exception) {
        Log.w(TAG, "$label недоступен: ${e.message}")
        null
    }

    /**
     * Обрабатывает голосовую команду:
     * аудио (WAV) → Gemma 4 e2b → function calling → Flow<String> с токенами ответа.
     *
     * Предыдущая Conversation закрывается автоматически перед созданием новой.
     */
    fun processCommand(audioWavBytes: ByteArray): Flow<String> {
        val eng = checkNotNull(engine) { "LlmManager не инициализирован" }

        val wavDurationMs = (audioWavBytes.size - 44).toLong() * 1000L / (16_000 * 2)
        Log.d(TAG, "processCommand: WAV ${audioWavBytes.size} байт, ~${wavDurationMs} мс аудио")

        // Закрываем предыдущую сессию — движок поддерживает только одну одновременно
        conversation?.close()
        conversation = null

        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            tools = listOf(tool(haTools)),
            samplerConfig = SamplerConfig(temperature = 0.6, topK = 40, topP = 0.95),
            automaticToolCalling = true
        )
        val conv = eng.createConversation(conversationConfig)
        conversation = conv

        val contents = Contents.of(
            Content.AudioBytes(bytes = audioWavBytes),
            Content.Text(
                "Это голосовая команда управления умным домом. " +
                "Выполни запрос пользователя с помощью доступных инструментов."
            )
        )

        val responseBuilder = StringBuilder()
        return conv.sendMessageAsync(Message.user(contents)).map { chunk ->
            val token = chunk.toString()
            responseBuilder.append(token)
            token
        }.also {
            // Логируем финальный ответ после завершения flow (best-effort через отдельный поток)
            // Полный ответ будет видён в logcat по мере стриминга токенов
            Log.d(TAG, "Стриминг ответа начат")
        }
    }

    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        activeBackend = null
        Log.d(TAG, "Движок закрыт")
    }

    companion object {
        private const val TAG = "LlmManager"
    }
}
