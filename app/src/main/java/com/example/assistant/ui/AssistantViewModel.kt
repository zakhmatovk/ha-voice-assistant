package com.example.assistant.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.assistant.audio.AudioRecorderManager
import com.example.assistant.audio.VoskDownloadProgress
import com.example.assistant.audio.VoskModelManager
import com.example.assistant.llm.LlmManager
import com.example.assistant.model.DownloadProgress
import com.example.assistant.service.WakeWordListenerService
import com.example.assistant.model.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- Состояние настроек ---
    private val _modelExists = MutableStateFlow(false)
    val modelExists: StateFlow<Boolean> = _modelExists.asStateFlow()

    private val _isDefaultAssistant = MutableStateFlow(false)
    val isDefaultAssistant: StateFlow<Boolean> = _isDefaultAssistant.asStateFlow()

    // --- Прогресс импорта модели (SAF) ---
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    // --- Прогресс загрузки модели с HF ---
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null

    // --- Prогресс загрузки Vosk-модели ---
    private val _voskModelExists = MutableStateFlow(false)
    val voskModelExists: StateFlow<Boolean> = _voskModelExists.asStateFlow()

    private val _voskDownloadState = MutableStateFlow<VoskDownloadState>(VoskDownloadState.Idle)
    val voskDownloadState: StateFlow<VoskDownloadState> = _voskDownloadState.asStateFlow()

    private var voskDownloadJob: Job? = null

    // --- Режим ассистента (запущен через ASSIST intent) ---
    private val _isAssistMode = MutableStateFlow(false)
    val isAssistMode: StateFlow<Boolean> = _isAssistMode.asStateFlow()

    // --- Состояние записи/обработки ---
    private val _uiState = MutableStateFlow<AssistUiState>(AssistUiState.Idle)
    val uiState: StateFlow<AssistUiState> = _uiState.asStateFlow()

    private val audioRecorder = AudioRecorderManager()
    private val llmManager = LlmManager(app)
    private var llmReady = false
    private var sessionJob: Job? = null

    private val _llmBackend = MutableStateFlow<String?>(null)
    val llmBackend: StateFlow<String?> = _llmBackend.asStateFlow()

    private val _llmLoading = MutableStateFlow(false)
    val llmLoading: StateFlow<Boolean> = _llmLoading.asStateFlow()

    private var preloadJob: Job? = null

    fun setAssistMode(active: Boolean) {
        _isAssistMode.value = active
        if (active) startAssistSession()
    }

    /** Сбрасывает состояние ассистента в Idle (например, после закрытия оверлея). */
    fun resetAssistMode() {
        _isAssistMode.value = false
        _uiState.value = AssistUiState.Idle
    }

    fun refresh() {
        _modelExists.value = findModelFile() != null
        _isDefaultAssistant.value = checkIsDefaultAssistant()
        _voskModelExists.value = VoskModelManager(getApplication()).modelExists()
        if (_modelExists.value) preloadModel()
    }

    /**
     * Загружает модель в память в фоне сразу при старте.
     * Повторные вызовы — no-op если загрузка уже идёт или завершена.
     * [startAssistSession] дожидается завершения через [preloadJob.join].
     */
    fun preloadModel() {
        if (llmReady || preloadJob?.isActive == true) return
        val modelFile = findModelFile() ?: return
        preloadJob = scope.launch(Dispatchers.IO) {
            _llmLoading.value = true
            try {
                Log.d(TAG, "Предзагрузка модели: ${modelFile.name}")
                llmManager.initialize(modelFile.absolutePath)
                llmReady = true
                _llmBackend.value = llmManager.activeBackend
                Log.d(TAG, "Предзагрузка завершена: ${llmManager.activeBackend}")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка предзагрузки модели", e)
            } finally {
                _llmLoading.value = false
            }
        }
    }

    /**
     * Ищет файл модели сначала во внутреннем хранилище, затем во внешнем.
     * Это позволяет переиспользовать модель из Edge Gallery без копирования во внутреннее хранилище.
     */
    private fun findModelFile(): java.io.File? {
        val app = getApplication<Application>()
        val internal = app.getFileStreamPath(MODEL_FILE)
        if (internal.exists()) return internal
        val external = app.getExternalFilesDir(null)?.let { java.io.File(it, MODEL_FILE) }
        if (external?.exists() == true) return external
        return null
    }

    /**
     * Копирует файл модели из URI (SAF-пикер) во внутреннее хранилище приложения.
     * Показывает прогресс в байтах — модели могут быть несколько ГБ.
     */
    fun importModelFromUri(uri: Uri) {
        scope.launch {
            _importState.value = ImportState.Copying(0, 0)
            try {
                withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val dest = File(ctx.filesDir, MODEL_FILE)
                    val tempDest = File(ctx.filesDir, "$MODEL_FILE.tmp")

                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val total = ctx.contentResolver
                            .query(uri, arrayOf("_size"), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                            ?: -1L

                        tempDest.outputStream().use { output ->
                            val buf = ByteArray(256 * 1024) // 256 КБ буфер
                            var copied = 0L
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                copied += read
                                _importState.value = ImportState.Copying(copied, total)
                            }
                        }
                    } ?: throw IllegalStateException("Не удалось открыть файл")

                    // Атомарная замена только после успешного копирования
                    tempDest.renameTo(dest)
                    Log.d(TAG, "Модель импортирована: ${dest.absolutePath}")
                }
                _importState.value = ImportState.Done
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка импорта модели", e)
                _importState.value = ImportState.Error(e.message ?: "Неизвестная ошибка")
                // Удаляем временный файл при ошибке
                getApplication<Application>().getFileStreamPath("$MODEL_FILE.tmp").delete()
            }
        }
    }

    /** Скачивает модель с серверов Google (Edge Gallery source). */
    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            ModelDownloader(getApplication()).download().collect { progress ->
                _downloadState.value = when (progress) {
                    is DownloadProgress.Connecting ->
                        DownloadState.Downloading(0L, -1L)
                    is DownloadProgress.Downloading ->
                        DownloadState.Downloading(progress.bytesDone, progress.totalBytes)
                    is DownloadProgress.Done -> {
                        refresh()
                        DownloadState.Done
                    }
                    is DownloadProgress.Error ->
                        DownloadState.Error(progress.message)
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (_downloadState.value !is DownloadState.Done) {
            _downloadState.value = DownloadState.Idle
        }
    }

    /** Скачивает vosk-model-small-ru (~45 МБ) для wake-word "Кузя". */
    fun downloadVoskModel() {
        if (voskDownloadJob?.isActive == true) return
        voskDownloadJob = scope.launch {
            VoskModelManager(getApplication()).download().collect { progress ->
                _voskDownloadState.value = when (progress) {
                    is VoskDownloadProgress.Downloading ->
                        VoskDownloadState.Downloading(progress.bytesDone, progress.totalBytes)
                    is VoskDownloadProgress.Extracting ->
                        VoskDownloadState.Extracting
                    is VoskDownloadProgress.Done -> {
                        refresh()
                        VoskDownloadState.Done
                    }
                    is VoskDownloadProgress.Error ->
                        VoskDownloadState.Error(progress.message)
                }
            }
        }
    }

    fun cancelVoskDownload() {
        voskDownloadJob?.cancel()
        voskDownloadJob = null
        if (_voskDownloadState.value !is VoskDownloadState.Done) {
            _voskDownloadState.value = VoskDownloadState.Idle
        }
    }

    /** Запускает сессию: инициализация LLM → запись → инференс.
     *  Если сессия уже запущена — игнорирует повторный вызов. */
    private fun startAssistSession() {
        if (sessionJob?.isActive == true) {
            Log.d(TAG, "Сессия уже выполняется, пропускаем повторный запуск")
            return
        }
        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                // Ждём предзагрузку если она ещё идёт
                if (preloadJob?.isActive == true) {
                    _uiState.value = AssistUiState.InitializingLlm
                    preloadJob?.join()
                }

                if (!llmReady) {
                    // Предзагрузка не запускалась или упала — пробуем сейчас
                    _uiState.value = AssistUiState.InitializingLlm
                    val modelFile = findModelFile()
                    if (modelFile == null) {
                        _uiState.value = AssistUiState.Error("Файл модели не найден: $MODEL_FILE")
                        return@launch
                    }
                    llmManager.initialize(modelFile.absolutePath)
                    llmReady = true
                    _llmBackend.value = llmManager.activeBackend
                }

                _uiState.value = AssistUiState.Recording
                val tailPcm      = WakeWordListenerService.pendingTailPcm ?: ByteArray(0)
                val handoffRecord = WakeWordListenerService.pendingAudioRecord
                WakeWordListenerService.pendingTailPcm    = null
                WakeWordListenerService.pendingAudioRecord = null
                val wav = audioRecorder.record(
                    preRollPcm    = tailPcm,
                    handoffRecord = handoffRecord
                )
                val wavDurationMs = (wav.size - 44).toLong() * 1000L / (16_000 * 2)
                Log.d(TAG, "WAV готов: ${wav.size} байт, ~${wavDurationMs}мс " +
                           "(preRoll=${tailPcm.size} байт, handoff=${handoffRecord != null})")

                _uiState.value = AssistUiState.Processing
                val responseBuilder = StringBuilder()
                llmManager.processCommand(wav).collect { chunk ->
                    responseBuilder.append(chunk)
                    _uiState.value = AssistUiState.Responding(responseBuilder.toString())
                }
                Log.d(TAG, "Полный ответ LLM: ${responseBuilder}")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сессии ассистента", e)
                _uiState.value = AssistUiState.Error(e.message ?: "Неизвестная ошибка")
            } finally {
                restartWakeWordDetector()
            }
        }
    }

    /** Сигнализирует WakeWordListenerService немедленно перезапустить детектор. */
    private fun restartWakeWordDetector() {
        val ctx = getApplication<Application>()
        val intent = android.content.Intent(ctx, com.example.assistant.service.WakeWordListenerService::class.java)
        intent.action = com.example.assistant.service.WakeWordListenerService.ACTION_RESTART_DETECTOR
        ctx.startService(intent)
    }

    private fun checkIsDefaultAssistant(): Boolean {
        val ctx = getApplication<Application>()
        val cr = ctx.contentResolver

        val assistSetting = Settings.Secure.getString(cr, "assistant")
        val visSetting    = Settings.Secure.getString(cr, "voice_interaction_service")

        val cnAssist = ComponentName(ctx.packageName, MAIN_ACTIVITY_CLASS)
        val cnVis    = ComponentName(ctx.packageName, SERVICE_CLASS)

        return assistSetting == cnAssist.flattenToShortString()
            || assistSetting == cnAssist.flattenToString()
            || visSetting    == cnVis.flattenToShortString()
            || visSetting    == cnVis.flattenToString()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
        llmManager.close()
    }

    companion object {
        private const val TAG = "AssistantViewModel"
        private const val MODEL_FILE = "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
        private const val SERVICE_CLASS =
            "com.example.assistant.service.AssistantVoiceInteractionService"
        private const val MAIN_ACTIVITY_CLASS =
            "com.example.assistant.MainActivity"
    }
}

sealed class ImportState {
    data object Idle : ImportState()
    data class Copying(val bytesCopied: Long, val totalBytes: Long) : ImportState()
    data object Done : ImportState()
    data class Error(val message: String) : ImportState()
}

sealed class VoskDownloadState {
    data object Idle : VoskDownloadState()
    data class Downloading(val bytesDone: Long, val totalBytes: Long) : VoskDownloadState()
    data object Extracting : VoskDownloadState()
    data object Done : VoskDownloadState()
    data class Error(val message: String) : VoskDownloadState()
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val bytesDone: Long, val totalBytes: Long) : DownloadState()
    data object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

sealed class AssistUiState {
    data object Idle : AssistUiState()
    data object InitializingLlm : AssistUiState()
    data object Recording : AssistUiState()
    data object Processing : AssistUiState()
    data class Responding(val text: String) : AssistUiState()
    data class Error(val message: String) : AssistUiState()
}
