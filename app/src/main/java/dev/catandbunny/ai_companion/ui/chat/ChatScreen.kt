package dev.catandbunny.ai_companion.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import dev.catandbunny.ai_companion.config.ApiConfig
import dev.catandbunny.ai_companion.data.local.AppDatabase
import dev.catandbunny.ai_companion.data.repository.DatabaseRepository
import dev.catandbunny.ai_companion.model.ResponseMetadata
import dev.catandbunny.ai_companion.ui.json.JsonViewScreen
import dev.catandbunny.ai_companion.ui.mcp.McpToolsScreen
import dev.catandbunny.ai_companion.ui.settings.SettingsScreen
import dev.catandbunny.ai_companion.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    botAvatar: Painter? = null
) {
    val context = LocalContext.current
    
    // Создаем базу данных и репозиторий
    val database = remember { AppDatabase.getDatabase(context) }
    val databaseRepository = remember { DatabaseRepository(database) }
    
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(databaseRepository) as T
            }
        }
    )
    
    // Создаем ChatViewModel с функцией получения системного промпта, температуры, модели и флага сжатия истории
    // Функция всегда будет получать актуальное значение из StateFlow
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(
            apiKey = ApiConfig.OPENAI_API_KEY,
            getSystemPrompt = { settingsViewModel.getSystemPrompt() },
            getTemperature = { settingsViewModel.getTemperature() },
            getModel = { settingsViewModel.getSelectedModel() },
            getHistoryCompressionEnabled = { settingsViewModel.getHistoryCompressionEnabled() },
            databaseRepository = databaseRepository
        )
    )
    
    var messageText by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val totalApiTokens by viewModel.totalApiTokens.collectAsState()
    var selectedMetadata by remember { mutableStateOf<ResponseMetadata?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showMcpTools by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Отслеживаем lifecycle Activity
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            android.util.Log.d("ChatScreen", "Activity Lifecycle event: $event")
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                android.util.Log.d("ChatScreen", "Activity ON_PAUSE/ON_STOP, вызываем saveHistoryOnAppPause")
                viewModel.saveHistoryOnAppPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            android.util.Log.d("ChatScreen", "DisposableEffect onDispose, вызываем saveHistoryOnAppPause")
            viewModel.saveHistoryOnAppPause()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Отслеживаем lifecycle процесса приложения (для принудительного завершения)
    DisposableEffect(Unit) {
        val processObserver = LifecycleEventObserver { _, event ->
            android.util.Log.d("ChatScreen", "Process Lifecycle event: $event")
            if (event == Lifecycle.Event.ON_STOP) {
                android.util.Log.d("ChatScreen", "Process ON_STOP, вызываем saveHistoryOnAppPause")
                viewModel.saveHistoryOnAppPause()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        onDispose {
            android.util.Log.d("ChatScreen", "ProcessLifecycleOwner DisposableEffect onDispose, вызываем saveHistoryOnAppPause")
            viewModel.saveHistoryOnAppPause()
            ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        }
    }
    
    // Функция для копирования текста в буфер обмена
    val onCopyText: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Ответ бота", text)
        clipboard.setPrimaryClip(clip)
        
        scope.launch {
            snackbarHostState.showSnackbar("Текст скопирован в буфер обмена")
        }
        Unit
    }
    
    // Автопрокрутка при добавлении нового сообщения
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // Показываем ошибку, если есть
    LaunchedEffect(error) {
        error?.let {
            // Ошибка уже обрабатывается в ViewModel
            viewModel.clearError()
        }
    }

    // Показываем экран настроек, если открыт
    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            databaseRepository = databaseRepository
        )
        return
    }
    
    // Показываем экран инструментов MCP, если открыт
    if (showMcpTools) {
        McpToolsScreen(
            onBack = { showMcpTools = false }
        )
        return
    }
    
    // Показываем экран JSON, если выбран
    selectedMetadata?.let { metadata ->
        JsonViewScreen(
            metadata = metadata,
            onBack = { 
                selectedMetadata = null 
            }
        )
        return
    }
    
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    if (totalApiTokens > 0) {
                        Text(
                            text = "Токены: $totalApiTokens",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Меню",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Настройки") },
                                onClick = {
                                    showSettings = true
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Новый чат") },
                                onClick = {
                                    viewModel.createNewChat()
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Инструменты MCP") },
                                onClick = {
                                    showMcpTools = true
                                    showMenu = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        },
        bottomBar = {
            // Поле ввода и кнопка отправки с поддержкой edgeToEdge - всегда закреплено снизу
            // Учитываем IME (клавиатуру) и navigation bars
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars)
                    ),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                        placeholder = { Text("Введите сообщение...") },
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        singleLine = false,
                        maxLines = 4
                    )

                    FloatingActionButton(
                        onClick = {
                            if (messageText.isNotBlank() && !isLoading) {
                                viewModel.sendMessage(messageText)
                                messageText = ""
                            }
                        },
                        modifier = Modifier.size(56.dp),
                        containerColor = if (isLoading) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Отправить",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Список сообщений - скроллится между заголовком и полем ввода
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(
                items = messages,
                key = { index, message ->
                    // Индекс гарантирует уникальность ключа (timestamp+hashCode могут совпадать у разных сообщений)
                    index
                }
            ) { _, message ->
                ChatMessageItem(
                    message = message,
                    botAvatar = botAvatar,
                    onShowJson = { metadata ->
                        selectedMetadata = metadata
                    },
                    onCopyText = onCopyText
                )
            }
            
            // Индикатор загрузки внизу списка
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Бот печатает...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
