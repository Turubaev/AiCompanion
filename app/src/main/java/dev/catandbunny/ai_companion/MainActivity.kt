package dev.catandbunny.ai_companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.lifecycleScope
import dev.catandbunny.ai_companion.data.local.AppDatabase
import dev.catandbunny.ai_companion.ui.chat.ChatScreen
import dev.catandbunny.ai_companion.ui.theme.Ai_CompanionTheme
import dev.catandbunny.ai_companion.worker.CurrencyScheduler
import dev.catandbunny.ai_companion.worker.EXTRA_CURRENCY_NOTIFICATION_MESSAGE
import android.util.Base64
import dev.catandbunny.ai_companion.worker.EXTRA_DEMO_MESSAGE_B64
import dev.catandbunny.ai_companion.worker.PendingCurrencyNotification
import dev.catandbunny.ai_companion.worker.PendingSharedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        passNotificationMessageToChat(intent)
        scheduleCurrencyWorkerIfEnabled()
        setContent {
            Ai_CompanionTheme {
                ChatScreen(
                    modifier = Modifier.fillMaxSize(),
                    botAvatar = painterResource(id = R.drawable.bot_avatar)
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        passNotificationMessageToChat(intent)
    }

    private fun passNotificationMessageToChat(intent: Intent?) {
        val message = intent?.getStringExtra(EXTRA_CURRENCY_NOTIFICATION_MESSAGE)
        if (!message.isNullOrBlank()) {
            PendingCurrencyNotification.setPendingMessage(message)
            intent?.removeExtra(EXTRA_CURRENCY_NOTIFICATION_MESSAGE)
            return
        }
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank()) {
            PendingSharedText.setTextToSend(sharedText)
            intent.removeExtra(Intent.EXTRA_TEXT)
            return
        }
        val demoB64 = intent?.getStringExtra(EXTRA_DEMO_MESSAGE_B64)
        if (!demoB64.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(demoB64, Base64.DEFAULT)
                val demoText = String(bytes, Charsets.UTF_8)
                if (demoText.isNotBlank()) {
                    android.util.Log.d("MainActivity", "DEMO: received message (base64), length=${demoText.length}")
                    PendingSharedText.setTextToSend(demoText)
                }
            } catch (_: Exception) { }
            intent.removeExtra(EXTRA_DEMO_MESSAGE_B64)
        }
    }

    private fun scheduleCurrencyWorkerIfEnabled() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val settings = AppDatabase.getDatabase(this@MainActivity).settingsDao().getSettingsSync()
                if (settings?.currencyNotificationEnabled == true) {
                    CurrencyScheduler.schedule(this@MainActivity, settings.currencyIntervalMinutes)
                }
            }
        }
    }
}