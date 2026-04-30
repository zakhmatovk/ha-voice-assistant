package com.example.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.assistant.service.AssistantVoiceInteractionService
import com.example.assistant.service.WakeWordListenerService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.assistant.model.ModelDownloader
import com.example.assistant.ui.AssistUiState
import com.example.assistant.ui.AssistantViewModel
import com.example.assistant.ui.DownloadState
import com.example.assistant.ui.HaConnectionState
import com.example.assistant.ui.ImportState
import com.example.assistant.ui.VoskDownloadState
import com.example.assistant.ui.theme.AssistantTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var filePicker: ActivityResultLauncher<Array<String>>

    // Принимает broadcast от WakeWordListenerService и запускает сессию ассистента
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AssistantVoiceInteractionService.ACTION_WAKE_WORD_DETECTED) {
                viewModel.setAssistMode(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { viewModel.refresh() }

        // SAF-пикер: открывает системный файловый менеджер для выбора .litertlm
        filePicker = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { viewModel.importModelFromUri(it) } }

        enableEdgeToEdge()
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        val launchedByAssist = intent?.action == Intent.ACTION_ASSIST
        viewModel.setAssistMode(launchedByAssist)

        setContent {
            AssistantTheme {
                SetupScreen(
                    viewModel = viewModel,
                    onOpenAssistantSettings = {
                        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    },
                    onPickModelFile = {
                        // "*/*" — любой файл; некоторые менеджеры не знают .litertlm MIME
                        filePicker.launch(arrayOf("*/*"))
                    }
                )
            }
        }
    }

    // Срабатывает когда Activity уже запущена и получает новый ACTION_ASSIST
    // (например, от AssistantVoiceSession после showSession())
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) {
            viewModel.setAssistMode(true)  // guard в ViewModel не даст запустить вторую сессию
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        // Запускаем детектор wake-word (сервис сам проверит наличие Vosk-модели)
        startForegroundService(Intent(this, WakeWordListenerService::class.java))
        // Слушаем broadcast о срабатывании wake-word
        registerReceiver(
            wakeWordReceiver,
            IntentFilter(AssistantVoiceInteractionService.ACTION_WAKE_WORD_DETECTED),
            if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
        )
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(wakeWordReceiver) }
    }
}

@Composable
private fun SetupScreen(
    viewModel: AssistantViewModel,
    onOpenAssistantSettings: () -> Unit,
    onPickModelFile: () -> Unit
) {
    val modelExists by viewModel.modelExists.collectAsState()
    val isDefaultAssistant by viewModel.isDefaultAssistant.collectAsState()
    val isAssistMode by viewModel.isAssistMode.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val voskModelExists by viewModel.voskModelExists.collectAsState()
    val voskDownloadState by viewModel.voskDownloadState.collectAsState()
    val llmBackend by viewModel.llmBackend.collectAsState()
    val llmLoading by viewModel.llmLoading.collectAsState()
    val haConnectionState by viewModel.haConnectionState.collectAsState()
    val haUrl by viewModel.haUrl.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    if (isAssistMode) {
        AssistOverlay(uiState = uiState, backend = llmBackend)
        return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Home Assistant Voice", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Голосовое управление умным домом на базе Gemma 4 e2b",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // --- Статус ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Статус", style = MaterialTheme.typography.titleMedium)
                    StatusRow("Разрешение RECORD_AUDIO", ok = true)
                    StatusRow("Выбран ассистентом по умолчанию", ok = isDefaultAssistant)
                    StatusRow("Wake-word модель (Алекса / Алекс / Алексей)", ok = voskModelExists)
                    StatusRow("Модель gemma-4-E2B-it.litertlm", ok = modelExists)
                    when (val ha = haConnectionState) {
                        is HaConnectionState.Idle      -> StatusRow("Home Assistant", ok = false)
                        is HaConnectionState.Checking  -> StatusRow("Home Assistant: проверка…", ok = false)
                        is HaConnectionState.Connected -> StatusRow("Home Assistant: $haUrl", ok = true)
                        is HaConnectionState.Error     -> StatusRow("Home Assistant: ${ha.message}", ok = false)
                    }
                    if (modelExists) {
                        if (llmLoading) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Загрузка модели в память…", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            BackendRow(backend = llmBackend)
                        }
                    }
                }
            }

            // --- Шаг 1: ассистент ---
            if (!isDefaultAssistant) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Шаг 1: Установить как ассистент", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Настройки → Приложения → Ассистент по умолчанию → выберите «Home Assistant Voice».",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = onOpenAssistantSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Открыть настройки ассистента")
                        }
                    }
                }
            }

            // --- Шаг 2: Wake-word модель (Vosk, ~45 МБ) ---
            if (!voskModelExists || voskDownloadState !is VoskDownloadState.Idle) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Шаг 2: Модель wake-word", style = MaterialTheme.typography.titleSmall)

                        when (val v = voskDownloadState) {
                            is VoskDownloadState.Idle -> {
                                Text(
                                    "Офлайн-модель распознавания (~45 МБ). Слова: «Алекса», «Алекс», «Алексей».",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { viewModel.downloadVoskModel() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Скачать модель распознавания")
                                }
                            }

                            is VoskDownloadState.Downloading -> {
                                val progress = if (v.totalBytes > 0)
                                    v.bytesDone.toFloat() / v.totalBytes else -1f
                                Text(
                                    if (v.totalBytes > 0)
                                        "Скачиваю… ${formatBytes(v.bytesDone)} / ${formatBytes(v.totalBytes)}"
                                    else
                                        "Подключаюсь…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (progress >= 0f)
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                else
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                OutlinedButton(
                                    onClick = { viewModel.cancelVoskDownload() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Отмена") }
                            }

                            is VoskDownloadState.Extracting -> {
                                Text("Распаковка архива…", style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            is VoskDownloadState.Done -> {
                                Text("Модель wake-word установлена ✓", style = MaterialTheme.typography.bodySmall)
                            }

                            is VoskDownloadState.Error -> {
                                Text(
                                    "Ошибка: ${v.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Button(
                                    onClick = { viewModel.downloadVoskModel() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Повторить") }
                            }
                        }
                    }
                }
            }

            // --- Шаг 3: LLM модель ---
            if (!modelExists || importState !is ImportState.Idle || downloadState !is DownloadState.Idle) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Шаг 3: Установить LLM модель", style = MaterialTheme.typography.titleSmall)

                        // --- Прогресс импорта из файла ---
                        when (val s = importState) {
                            is ImportState.Idle -> { /* см. блок скачивания ниже */ }

                            is ImportState.Copying -> {
                                val progress = if (s.totalBytes > 0)
                                    s.bytesCopied.toFloat() / s.totalBytes else -1f
                                Text(
                                    if (s.totalBytes > 0)
                                        "Копирую… ${formatBytes(s.bytesCopied)} / ${formatBytes(s.totalBytes)}"
                                    else
                                        "Копирую… ${formatBytes(s.bytesCopied)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (progress >= 0f)
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                else
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                return@Column
                            }

                            is ImportState.Done -> {
                                Text("Модель успешно импортирована ✓", style = MaterialTheme.typography.bodySmall)
                                return@Column
                            }

                            is ImportState.Error -> {
                                Text(
                                    "Ошибка импорта: ${s.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        // --- Скачивание с серверов Google / выбор файла ---
                        when (val d = downloadState) {
                            is DownloadState.Idle -> {
                                Button(
                                    onClick = { viewModel.downloadModel() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Скачать модель (~2.4 ГБ)")
                                }
                                Text(
                                    "Источник: Google AI Edge Gallery",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "— или выберите уже скачанный файл —",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = onPickModelFile, modifier = Modifier.fillMaxWidth()) {
                                    Text("Выбрать файл модели…")
                                }
                            }

                            is DownloadState.Downloading -> {
                                val progress = if (d.totalBytes > 0)
                                    d.bytesDone.toFloat() / d.totalBytes else -1f
                                Text(
                                    if (d.totalBytes > 0)
                                        "Скачиваю… ${formatBytes(d.bytesDone)} / ${formatBytes(d.totalBytes)}"
                                    else
                                        "Подключаюсь…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (progress >= 0f)
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                else
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                OutlinedButton(
                                    onClick = { viewModel.cancelDownload() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Отмена")
                                }
                            }

                            is DownloadState.Done -> {
                                Text("Модель успешно скачана ✓", style = MaterialTheme.typography.bodySmall)
                            }

                            is DownloadState.Error -> {
                                Text(
                                    "Ошибка загрузки: ${d.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                OutlinedButton(onClick = onPickModelFile, modifier = Modifier.fillMaxWidth()) {
                                    Text("Выбрать файл вручную")
                                }
                            }
                        }
                    }
                }
            }

            // --- Шаг 4: Home Assistant ---
            HaSetupCard(
                connectionState = haConnectionState,
                currentUrl = haUrl,
                onSave = { url, token -> viewModel.saveHaConfig(url, token) },
                onRetry = { viewModel.checkHaConnection() }
            )

            // --- Готово ---
            if (isDefaultAssistant && voskModelExists && modelExists && llmBackend != null && !llmLoading && haConnectionState is HaConnectionState.Connected) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Готово!", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Скажите «Алекса», «Алекс» или «Алексей», либо удержите кнопку Home для активации ассистента.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HaSetupCard(
    connectionState: HaConnectionState,
    currentUrl: String,
    onSave: (url: String, token: String) -> Unit,
    onRetry: () -> Unit
) {
    val connected = connectionState is HaConnectionState.Connected
    var expanded by rememberSaveable { mutableStateOf(!connected) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Шаг 4: Home Assistant", style = MaterialTheme.typography.titleSmall)
                if (connected) {
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Свернуть" else "Изменить")
                    }
                }
            }

            // Статус проверки
            when (connectionState) {
                is HaConnectionState.Checking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Проверяем соединение…", style = MaterialTheme.typography.bodySmall)
                }
                is HaConnectionState.Connected -> if (!expanded) {
                    Text(
                        "Подключено: $currentUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    return@Column
                }
                is HaConnectionState.Error -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Ошибка: ${connectionState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onRetry) { Text("Повторить") }
                }
                else -> Unit
            }

            if (connectionState is HaConnectionState.Checking) return@Column

            var url   by rememberSaveable { mutableStateOf(currentUrl) }
            var token by rememberSaveable { mutableStateOf("") }

            Text(
                "Введите адрес вашего Home Assistant и Long-Lived Access Token.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL (например http://192.168.1.10:8123)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Long-Lived Access Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSave(url, token) },
                enabled = url.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить и проверить")
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f МБ".format(bytes / 1_048_576.0)
    else                    -> "%.0f КБ".format(bytes / 1024.0)
}

@Composable
private fun AssistOverlay(uiState: AssistUiState, backend: String?) {
    val (statusText, subText) = when (uiState) {
        is AssistUiState.Idle            -> "Ассистент" to ""
        is AssistUiState.InitializingLlm -> "Загружаю модель…" to ""
        is AssistUiState.Recording       -> "Слушаю…" to "Говорите команду"
        is AssistUiState.Processing      -> "Думаю…" to ""
        is AssistUiState.Responding      -> "Готово" to uiState.text
        is AssistUiState.Error           -> "Ошибка" to uiState.message
    }
    Scaffold(containerColor = Color(0xCC000000)) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            // --- Иконка бэкенда в правом верхнем углу ---
            if (backend != null) {
                val isGpu = backend == "GPU"
                val chipColor = if (isGpu) Color(0xFF6650A4) else Color(0xFF444444)
                val icon = if (isGpu) "⚡" else "🖥"
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(chipColor, shape = RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(icon, style = MaterialTheme.typography.labelMedium)
                    Text(
                        backend,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }

            // --- Центральный статус ---
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(statusText, style = MaterialTheme.typography.headlineLarge, color = Color.White)
                if (subText.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(subText, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}

@Composable
private fun BackendRow(backend: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("LLM бэкенд", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = backend ?: "—",
            style = MaterialTheme.typography.labelLarge,
            color = when (backend) {
                "GPU" -> MaterialTheme.colorScheme.primary
                "CPU" -> MaterialTheme.colorScheme.onSurfaceVariant
                else  -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (ok) "✓" else "✗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
