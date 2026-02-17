package dev.catandbunny.ai_companion.ui.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import dev.catandbunny.ai_companion.mcp.github.McpRepository
import dev.catandbunny.ai_companion.mcp.model.McpTool
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsScreen(
    onBack: () -> Unit
) {
    val mcpRepository = remember { McpRepository() }
    val scope = rememberCoroutineScope()
    
    var tools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    
    // Инициализация при первом открытии
    LaunchedEffect(Unit) {
        isLoading = true
        error = null
        
        val initResult = mcpRepository.initialize()
        if (initResult.isSuccess) {
            isConnected = true
            val toolsResult = mcpRepository.getAvailableTools()
            toolsResult.onSuccess { 
                tools = it
                isLoading = false
            }.onFailure { 
                error = it.message ?: "Ошибка получения инструментов"
                isLoading = false
            }
        } else {
            error = initResult.exceptionOrNull()?.message ?: "Ошибка подключения к MCP серверу"
            isLoading = false
        }
    }
    
    // Очистка при закрытии
    DisposableEffect(Unit) {
        onDispose {
            mcpRepository.disconnect()
        }
    }

    BackHandler(onBack = onBack)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Инструменты MCP") },
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
        ) {
            // Статус подключения
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isConnected) "Подключено" else "Не подключено",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (error != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = error!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
            
            // Список инструментов
            if (tools.isNotEmpty()) {
                Text(
                    text = "Доступные инструменты (${tools.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tools) { tool ->
                        McpToolItem(tool = tool)
                    }
                }
            } else if (!isLoading && error == null) {
                // Пустое состояние
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Инструменты не найдены",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun McpToolItem(tool: McpTool) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Параметры инструмента
            tool.inputSchema.properties?.let { properties ->
                if (properties.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Параметры:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    properties.forEach { (paramName, paramSchema) ->
                        Text(
                            text = "• $paramName: ${paramSchema.type}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
