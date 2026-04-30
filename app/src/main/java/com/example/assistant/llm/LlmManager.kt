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
     * Создаёт и возвращает готовую Conversation.
     * Вызывать параллельно с записью аудио, чтобы к моменту окончания записи
     * объект уже был готов и не добавлял задержку перед инференсом.
     *
     * Предыдущая Conversation закрывается автоматически.
     */
    fun prepareConversation(): Conversation {
        val eng = checkNotNull(engine) { "LlmManager не инициализирован" }

        conversation?.close()
        conversation = null

        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            tools = listOf(tool(haTools)),
            samplerConfig = SamplerConfig(temperature = 0.6, topK = 40, topP = 0.95),
            automaticToolCalling = true
        )
        return eng.createConversation(config).also {
            conversation = it
            Log.d(TAG, "Conversation подготовлен заранее")
        }
    }

    /**
     * Отправляет аудио (WAV) в заранее подготовленный [conv] и возвращает Flow<String> токенов.
     * Если [conv] не передан — создаёт Conversation самостоятельно (медленнее).
     */
    fun processCommand(audioWavBytes: ByteArray, conv: Conversation? = null): Flow<String> {
        val wavDurationMs = (audioWavBytes.size - 44).toLong() * 1000L / (16_000 * 2)
        Log.d(TAG, "processCommand: WAV ${audioWavBytes.size} байт, ~${wavDurationMs} мс аудио")

        val activeConv = conv ?: prepareConversation()

        val contents = Contents.of(
            Content.AudioBytes(bytes = audioWavBytes),
            Content.Text(
                "Это голосовая команда управления умным домом. " +
                "Выполни запрос пользователя с помощью доступных инструментов."
            )
        )

        Log.d(TAG, "Стриминг ответа начат")
        return activeConv.sendMessageAsync(Message.user(contents)).map { it.toString() }
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
