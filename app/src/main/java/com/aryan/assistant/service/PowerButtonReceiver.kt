package com.aryan.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerButtonReceiver"
        private const val DOUBLE_PRESS_INTERVAL_MS = 650L
        private var lastPressTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
            val now = System.currentTimeMillis()
            val diff = now - lastPressTime
            lastPressTime = now

            if (diff in 1..DOUBLE_PRESS_INTERVAL_MS) {
                Log.d(TAG, "Double power button tap detected! Diff: $diff ms")
                triggerAryanAssistant(context)
            }
        }
    }

    private fun triggerAryanAssistant(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
            val serviceIntent = Intent(context, AryanOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
