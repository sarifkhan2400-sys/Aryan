package com.aryan.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.aryan.assistant.ui.main.MainActivity

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerButtonReceiver"
        private const val DOUBLE_PRESS_INTERVAL_MS = 1000L
        private var lastPressTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
            val now = System.currentTimeMillis()
            val diff = now - lastPressTime
            lastPressTime = now

            if (diff in 50..DOUBLE_PRESS_INTERVAL_MS) {
                Log.d(TAG, "Power button double tap detected! Diff: $diff ms")
                lastPressTime = 0L // reset
                triggerAryanAssistant(context)
            }
        }
    }

    private fun triggerAryanAssistant(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
                val serviceIntent = Intent(context, AryanOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("EXTRA_VOICE_ASSISTANT_TRIGGERED", true)
                }
                context.startActivity(mainIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering Aryan assistant", e)
        }
    }
}

