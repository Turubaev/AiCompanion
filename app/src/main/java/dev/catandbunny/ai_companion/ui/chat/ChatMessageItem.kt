package dev.catandbunny.ai_companion.ui.chat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.Painter
import dev.catandbunny.ai_companion.R
import dev.catandbunny.ai_companion.model.ResponseMetadata

@Composable
fun ChatMessageItem(
    message: dev.catandbunny.ai_companion.model.ChatMessage,
    botAvatar: Painter? = null,
    onShowJson: (ResponseMetadata) -> Unit = {},
    onCopyText: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.isFromUser) {
            // Аватарка бота - получаем ресурс напрямую для стабильности
            val avatarPainter = painterResource(id = R.drawable.bot_avatar)
            
            androidx.compose.foundation.Image(
                painter = avatarPainter,
                contentDescription = "Bot Avatar",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Сообщение
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (message.isFromUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (message.isFromUser) 16.dp else 4.dp,
                            bottomEnd = if (message.isFromUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (message.isFromUser) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp,
                        // Используем моноширинный шрифт для JSON ответов (финальное ТЗ)
                        fontFamily = if (!message.isFromUser && 
                            message.responseMetadata != null && 
                            message.responseMetadata.isRequirementsResponse) {
                            FontFamily.Monospace
                        } else {
                            FontFamily.Default
                        }
                    ),
                    color = if (message.isFromUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = if (message.isFromUser) TextAlign.End else TextAlign.Start
                )
            }
            
            // Информация о токенах для сообщений пользователя
            if (message.isFromUser && (message.manualTokenCount != null || message.apiTokenCount != null)) {
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier.align(Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    message.apiTokenCount?.let { count ->
                        Text(
                            text = "Токенов (API): $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    message.manualTokenCount?.let { count ->
                        Text(
                            text = "Токенов (ручной): $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            
            // Параметры ответа бота под сообщением (вертикально)
            if (!message.isFromUser && message.responseMetadata != null) {
                val meta = message.responseMetadata!!
                val responseTimeFormatted = if (meta.responseTimeMs < 1000) {
                    "${meta.responseTimeMs}мс"
                } else {
                    String.format("%.2fс", meta.responseTimeMs / 1000.0)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier.align(Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Время ответа: $responseTimeFormatted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "Токенов (API): ${meta.tokensUsed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    meta.manualTokenCount?.let { count ->
                        Text(
                            text = "Токенов (ручной): $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    meta.costFormatted?.let { cost ->
                        Text(
                            text = "Стоимость: $cost",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            
            // Кнопки под сообщением бота - показываем для всех сообщений бота
            if (!message.isFromUser) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Кнопка "Копировать" для всех сообщений бота
                    TextButton(
                        onClick = {
                            onCopyText(message.text)
                        }
                    ) {
                        Text(
                            text = "Копировать",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Кнопка "Просмотреть JSON" только для финальных JSON-ответов (ТЗ)
                    val hasMetadata = message.responseMetadata != null
                    val isRequirementsResponse = message.responseMetadata?.isRequirementsResponse == true
                    val shouldShowJsonButton = hasMetadata && isRequirementsResponse
                    
                    // Логирование для отладки
                    Log.d("ChatMessageItem", "=== Проверка видимости кнопки 'Просмотреть JSON' ===")
                    Log.d("ChatMessageItem", "hasMetadata: $hasMetadata")
                    Log.d("ChatMessageItem", "isRequirementsResponse: $isRequirementsResponse")
                    Log.d("ChatMessageItem", "shouldShowJsonButton: $shouldShowJsonButton")
                    if (hasMetadata) {
                        val metadata = message.responseMetadata!!
                        Log.d("ChatMessageItem", "Metadata details:")
                        Log.d("ChatMessageItem", "  - questionText: ${metadata.questionText}")
                        Log.d("ChatMessageItem", "  - requirements: ${if (metadata.requirements != null) "present (${metadata.requirements?.length} chars)" else "null"}")
                        Log.d("ChatMessageItem", "  - recommendations: ${if (metadata.recommendations != null) "present (${metadata.recommendations?.length} chars)" else "null"}")
                        Log.d("ChatMessageItem", "  - confidence: ${metadata.confidence}")
                        Log.d("ChatMessageItem", "  - isRequirementsResponse: ${metadata.isRequirementsResponse}")
                    }
                    
                    if (shouldShowJsonButton) {
                        TextButton(
                            onClick = {
                                message.responseMetadata?.let { metadata ->
                                    Log.d("ChatMessageItem", "Кнопка 'Просмотреть JSON' нажата")
                                    onShowJson(metadata)
                                }
                            }
                        ) {
                            Text(
                                text = "Просмотреть JSON",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (message.isFromUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // Аватарка пользователя
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Я",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
