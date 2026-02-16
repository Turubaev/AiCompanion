package dev.catandbunny.ai_companion.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.catandbunny.ai_companion.data.repository.DatabaseRepository
import dev.catandbunny.ai_companion.worker.CurrencyScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    databaseRepository: DatabaseRepository? = null,
    viewModel: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(databaseRepository) as T
            }
        }
    )
) {
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val historyCompressionEnabled by viewModel.historyCompressionEnabled.collectAsState()
    val currencyNotificationEnabled by viewModel.currencyNotificationEnabled.collectAsState()
    val currencyIntervalMinutes by viewModel.currencyIntervalMinutes.collectAsState()
    val telegramChatId by viewModel.telegramChatId.collectAsState()
    val ragEnabled by viewModel.ragEnabled.collectAsState()
    val ragMinScore by viewModel.ragMinScore.collectAsState()
    val ragUseReranker by viewModel.ragUseReranker.collectAsState()
    val githubUsername by viewModel.githubUsername.collectAsState()
    val prReviewRegisterResult by viewModel.prReviewRegisterResult.collectAsState()
    val availableModels = viewModel.availableModels
    val context = LocalContext.current
    val requestNotificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CurrencyScheduler.schedule(context, viewModel.getCurrencyIntervalMinutes())
        }
    }
    var showEditDialog by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var showCurrencyIntervalDropdown by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Заголовок секции
            Text(
                text = "Системный промпт",
                style = MaterialTheme.typography.titleLarge
            )
            
            // Отображение текущего промпта (предпросмотр)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Текущий промпт",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (systemPrompt.length > 200) {
                            systemPrompt.take(200) + "..."
                        } else {
                            systemPrompt
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 5
                    )
                }
            }
            
            // Кнопка редактирования
            Button(
                onClick = { showEditDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Редактировать")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Заголовок секции температуры
            Text(
                text = "Температура модели",
                style = MaterialTheme.typography.titleLarge
            )
            
            // Описание температуры
            Text(
                text = "Температура контролирует случайность ответов модели. " +
                        "Низкие значения (0.0-0.5) делают ответы более детерминированными и фокусированными. " +
                        "Высокие значения (1.0-2.0) делают ответы более креативными и разнообразными.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Карточка с настройкой температуры
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Текущее значение температуры
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Температура",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = String.format("%.1f", temperature),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Слайдер температуры
                    Slider(
                        value = temperature.toFloat(),
                        onValueChange = { newValue ->
                            viewModel.updateTemperature(newValue.toDouble())
                        },
                        valueRange = 0f..2f,
                        steps = 19, // Шаги по 0.1 (0.0, 0.1, 0.2, ..., 2.0)
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Подсказки по значениям
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "0.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "1.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "2.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Заголовок секции модели
            Text(
                text = "Модель бота",
                style = MaterialTheme.typography.titleLarge
            )
            
            // Описание модели
            Text(
                text = "Выберите модель OpenAI для генерации ответов. Разные модели имеют разные возможности и стоимость.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Карточка с выбором модели
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Модель",
                        style = MaterialTheme.typography.labelLarge
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Выпадающий список моделей
                    Box {
                        OutlinedTextField(
                            value = selectedModel,
                            onValueChange = { },
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showModelDropdown = true },
                            trailingIcon = {
                                IconButton(onClick = { showModelDropdown = true }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Выбрать модель"
                                    )
                                }
                            },
                            label = { Text("Выберите модель") }
                        )
                        
                        DropdownMenu(
                            expanded = showModelDropdown,
                            onDismissRequest = { showModelDropdown = false }
                        ) {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        viewModel.updateSelectedModel(model)
                                        showModelDropdown = false
                                    },
                                    leadingIcon = {
                                        RadioButton(
                                            selected = model == selectedModel,
                                            onClick = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Заголовок секции сжатия истории
            Text(
                text = "Сжатие истории диалога",
                style = MaterialTheme.typography.titleLarge
            )
            
            // Описание сжатия истории
            Text(
                text = "При включении каждые 10 сообщений автоматически сжимаются в краткое резюме. " +
                        "Это помогает экономить токены и поддерживать контекст в длинных диалогах.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Карточка с настройкой сжатия истории
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Включить сжатие истории",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (historyCompressionEnabled) {
                                "Сжатие активно"
                            } else {
                                "Сжатие отключено"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = historyCompressionEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateHistoryCompressionEnabled(enabled)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Заголовок секции уведомлений о курсе
            Text(
                text = "Уведомления о курсе USD/RUB",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Периодически запрашивать курс рубля к доллару через MCP сервер и показывать пуш-уведомление.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Карточка: включить уведомления + интервал
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Уведомления о курсе",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (currencyNotificationEnabled) {
                                    "Каждые $currencyIntervalMinutes мин"
                                } else {
                                    "Выключено"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = currencyNotificationEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateCurrencyNotificationEnabled(enabled)
                                if (enabled) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        when (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                                            android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                                CurrencyScheduler.schedule(context, viewModel.getCurrencyIntervalMinutes())
                                            else ->
                                                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    } else {
                                        CurrencyScheduler.schedule(context, viewModel.getCurrencyIntervalMinutes())
                                    }
                                } else {
                                    CurrencyScheduler.cancel(context)
                                }
                            }
                        )
                    }
                    if (currencyNotificationEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Интервал запроса",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box {
                            OutlinedTextField(
                                value = "$currencyIntervalMinutes мин",
                                onValueChange = { },
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCurrencyIntervalDropdown = true },
                                trailingIcon = {
                                    IconButton(onClick = { showCurrencyIntervalDropdown = true }) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Выбрать интервал"
                                        )
                                    }
                                },
                                label = { Text("Интервал") }
                            )
                            DropdownMenu(
                                expanded = showCurrencyIntervalDropdown,
                                onDismissRequest = { showCurrencyIntervalDropdown = false }
                            ) {
                                viewModel.currencyIntervalOptions.forEach { minutes ->
                                    DropdownMenuItem(
                                        text = { Text("$minutes мин") },
                                        onClick = {
                                            viewModel.updateCurrencyIntervalMinutes(minutes)
                                            CurrencyScheduler.schedule(context, minutes)
                                            showCurrencyIntervalDropdown = false
                                        },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = minutes == currencyIntervalMinutes,
                                                onClick = null
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Telegram для рекомендаций",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Chat ID для отправки рекомендаций по портфелю/инвестициям в Telegram. Узнать свой chat_id можно у @userinfobot в Telegram.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = telegramChatId,
                onValueChange = { viewModel.updateTelegramChatId(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Telegram Chat ID") },
                placeholder = { Text("Например: 123456789") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Ревью PR (GitHub)",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "GitHub username для получения ревью PR в чат. Укажите логин, под которым вы открываете PR (например в CloudBuddy). При открытии чата непрочитанные ревью подтягиваются и добавляются как сообщения ассистента.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = githubUsername,
                onValueChange = { viewModel.updateGitHubUsername(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitHub username") },
                placeholder = { Text("Например: Turubaev") },
                singleLine = true
            )

            Button(
                onClick = { viewModel.registerPrReviewForTelegram() },
                modifier = Modifier.fillMaxWidth(),
                enabled = githubUsername.isNotBlank()
            ) {
                Text("Привязать к ревью PR (отправка в Telegram)")
            }

            if (prReviewRegisterResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = prReviewRegisterResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (prReviewRegisterResult == "OK")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { viewModel.clearPrReviewRegisterResult() }) {
                    Text("Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Режим RAG (поиск по базе знаний)",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "При включении перед каждым запросом к боту выполняется поиск релевантных фрагментов по локальному индексу документов (профиль, тестовые материалы). Эти фрагменты подставляются в контекст запроса к LLM.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Включить RAG",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (ragEnabled) {
                                    "Ответы с учётом базы знаний"
                                } else {
                                    "Выключено"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ragEnabled,
                            onCheckedChange = { viewModel.updateRagEnabled(it) }
                        )
                    }
                    if (ragEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Использовать Reranker",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Переранжирование результатов cross-encoder моделью",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = ragUseReranker,
                                onCheckedChange = { viewModel.updateRagUseReranker(it) }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Порог релевантности (чанки с score ниже не попадают в контекст)",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format("%.2f", ragMinScore),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Slider(
                                value = ragMinScore.toFloat(),
                                onValueChange = { viewModel.updateRagMinScore(it.toDouble()) },
                                valueRange = 0f..1f,
                                steps = 19,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Диалог редактирования системного промпта
    if (showEditDialog) {
        EditSystemPromptDialog(
            currentPrompt = systemPrompt,
            onDismiss = { showEditDialog = false },
            onSave = { newPrompt ->
                viewModel.updateSystemPrompt(newPrompt)
                showEditDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSystemPromptDialog(
    currentPrompt: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var editedPrompt by remember { mutableStateOf(currentPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать системный промпт") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                OutlinedTextField(
                    value = editedPrompt,
                    onValueChange = { editedPrompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp),
                    label = { Text("Системный промпт") },
                    placeholder = { Text("Введите системный промпт...") },
                    singleLine = false,
                    maxLines = 20,
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(editedPrompt) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
        modifier = Modifier.fillMaxWidth(0.9f)
    )
}
