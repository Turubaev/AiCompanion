package dev.catandbunny.ai_companion.ui.chat

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
                        fontFamily = if (!message.isFromUser && message.responseMetadata != null) {
                            // Используем моноширинный шрифт для JSON ответов бота
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
            
            // Кнопки под сообщением бота
            if (!message.isFromUser && message.responseMetadata != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    TextButton(
                        onClick = {
                            message.responseMetadata?.let { metadata ->
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
