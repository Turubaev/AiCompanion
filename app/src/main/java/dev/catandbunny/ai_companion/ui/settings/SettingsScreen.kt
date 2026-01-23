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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val historyCompressionEnabled by viewModel.historyCompressionEnabled.collectAsState()
    val availableModels = viewModel.availableModels
    var showEditDialog by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }

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
