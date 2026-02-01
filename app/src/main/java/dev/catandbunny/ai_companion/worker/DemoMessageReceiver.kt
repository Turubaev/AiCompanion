package dev.catandbunny.ai_companion.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Принимает broadcast от adb для демо на эмуляторе.
 * adb shell am broadcast -a dev.catandbunny.ai_companion.DEMO_SEND_MESSAGE --es text "сообщение"
 */
class DemoMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEMO_SEND_MESSAGE) return
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            Log.d(TAG, "Demo message received, length=${text.length}")
            PendingSharedText.setTextToSend(text)
        }
    }

    companion object {
        const val ACTION_DEMO_SEND_MESSAGE = "dev.catandbunny.ai_companion.DEMO_SEND_MESSAGE"
        const val EXTRA_TEXT = "text"
        private const val TAG = "DemoMessageReceiver"
    }
}
