package dev.catandbunny.ai_companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.catandbunny.ai_companion.ui.chat.ChatScreen
import dev.catandbunny.ai_companion.ui.theme.Ai_CompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ai_CompanionTheme {
                ChatScreen(
                    modifier = Modifier.fillMaxSize(),
                    botAvatar = painterResource(id = R.drawable.bot_avatar)
                )
            }
        }
    }
}