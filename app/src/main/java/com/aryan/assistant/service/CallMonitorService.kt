package com.aryan.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aryan.assistant.ui.main.MainActivity
import com.example.R

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val CHANNEL_ID = "aryan_call_monitor_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_INCOMING_CALL = "com.aryan.INCOMING_CALL"
        const val ACTION_CALL_ENDED = "com.aryan.CALL_ENDED"
        const val EXTRA_CALLER_NAME = "CALLER_NAME"
        const val EXTRA_CALLER_NUMBER = "CALLER_NUMBER"

        var isRunning = false
            private set
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null // For Android 12+ (TelephonyCallback)
    private var powerButtonReceiver: PowerButtonReceiver? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        try {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    serviceType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting foreground notification", e)
        }
        registerCallListener()

        // Register PowerButtonReceiver dynamically for screen on/off events
        try {
            powerButtonReceiver = PowerButtonReceiver()
            val screenFilter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(powerButtonReceiver, screenFilter)
            Log.d(TAG, "Registered screen action receiver for power button shortcut")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering powerButtonReceiver", e)
        }

        Log.d(TAG, "CallMonitorService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            powerButtonReceiver?.let { unregisterReceiver(it) }
            powerButtonReceiver = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering powerButtonReceiver", e)
        }
        unregisterCallListener()
        isRunning = false
        super.onDestroy()
        Log.d(TAG, "CallMonitorService stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ARYAN Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors incoming calls for ARYAN AI voice announcements"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARYAN AI Active")
            .setContentText("Monitoring calls and voice automation ready")
            .setSmallIcon(R.drawable.ic_aryan_notif)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun registerCallListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, null)
                }
            }
            telephonyCallback = callback
            try {
                telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering TelephonyCallback", e)
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            try {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering PhoneStateListener", e)
            }
        }
    }

    private fun unregisterCallListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
    }

    private fun handleCallState(state: Int, rawNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val number = rawNumber ?: "Unknown"
                val contactName = resolveContactName(number)
                Log.d(TAG, "Incoming call ringing from $contactName ($number)")

                val intent = Intent(ACTION_INCOMING_CALL).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CALLER_NAME, contactName)
                    putExtra(EXTRA_CALLER_NUMBER, number)
                }
                sendBroadcast(intent)

                // Also launch or bring MainActivity to front so ARYAN can announce
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("INCOMING_CALL", true)
                    putExtra(EXTRA_CALLER_NAME, contactName)
                    putExtra(EXTRA_CALLER_NUMBER, number)
                }
                try {
                    startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching MainActivity for incoming call", e)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call ended / idle")
                sendBroadcast(Intent(ACTION_CALL_ENDED).apply { setPackage(packageName) })
            }
        }
    }

    private fun resolveContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return "Unknown Number"

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = it.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving contact name", e)
        }
        return phoneNumber
    }
}
