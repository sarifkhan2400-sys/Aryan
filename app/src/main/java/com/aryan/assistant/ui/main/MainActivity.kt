package com.aryan.assistant.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aryan.assistant.ai.AudioEngine
import com.aryan.assistant.ai.CommandParser
import com.aryan.assistant.ai.GeminiLiveClient
import com.aryan.assistant.service.AryanOverlayService
import com.aryan.assistant.service.CallMonitorService
import com.aryan.assistant.ui.settings.SettingsActivity
import com.aryan.assistant.viewmodel.MainViewModel
import com.example.R
import com.example.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AryanMainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var geminiClient: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    private lateinit var chatAdapter: ChatAdapter

    private var isMuted = false
    private var statsJob: Job? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            initAssistant()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice assistant", Toast.LENGTH_LONG).show()
        }
    }

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallMonitorService.ACTION_INCOMING_CALL) {
                val callerName = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: "Someone"
                val callerNumber = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NUMBER) ?: ""
                handleIncomingCall(callerName, callerNumber)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChatRecycler()
        setupTopBar()
        setupControls()
        observeViewModel()
        startStatsUpdateLoop()

        registerReceiver(
            callReceiver,
            IntentFilter(CallMonitorService.ACTION_INCOMING_CALL),
            Context.RECEIVER_NOT_EXPORTED
        )

        checkAndRequestPermissions()
        startCallMonitorService()
        checkIncomingCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIncomingCallIntent(intent)
    }

    private fun checkIncomingCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("INCOMING_CALL", false) == true) {
            val name = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: "Someone"
            val number = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NUMBER) ?: ""
            handleIncomingCall(name, number)
        }
    }

    private fun handleIncomingCall(callerName: String, callerNumber: String) {
        val announcePrompt = "An incoming phone call is ringing right now from $callerName ($callerNumber). Announce to the user in BF/Assistant persona and ask if they want to answer or reject it."
        chatAdapter.addMessage(ChatMessage("📞 Incoming call from $callerName", isUser = false))
        binding.chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
        geminiClient?.sendText(announcePrompt)
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            initAssistant()
        }

        // Overlay permission check (optional, does not block)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission not granted yet")
        }
    }

    private fun setupChatRecycler() {
        chatAdapter = ChatAdapter()
        binding.chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecycler.adapter = chatAdapter

        // Initial welcome message
        chatAdapter.addMessage(ChatMessage("ARYAN Voice Assistant ready. Tap mic or speak 💙", isUser = false))
    }

    private fun setupTopBar() {
        binding.settingsBtn.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupControls() {
        binding.micButton.setOnClickListener {
            toggleMute()
        }

        binding.micButton.setOnLongClickListener {
            interruptAssistant()
            true
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        audioEngine?.isMuted = isMuted
        if (isMuted) {
            binding.micButton.setImageResource(R.drawable.ic_mic_off)
            binding.micButton.alpha = 0.5f
            binding.statusText.text = "মাইক মিউট করা হয়েছে / Mic muted 🔇"
        } else {
            binding.micButton.setImageResource(R.drawable.ic_mic_on)
            binding.micButton.alpha = 1.0f
            binding.statusText.text = "শুনছি... / Listening 🎙️"
        }
    }

    private fun interruptAssistant() {
        audioEngine?.clearPlaybackQueue()
        geminiClient?.interrupt()
        binding.orbView.setState(OrbState.Active)
        binding.waveformView.stopAnimation()
        setRedOverlayActive(false)
        binding.statusText.text = "থামানো হয়েছে / Interrupted ⏹️"
        Toast.makeText(this, "ARYAN Interrupted", Toast.LENGTH_SHORT).show()
    }

    private fun initAssistant() {
        if (geminiClient != null) return

        geminiClient = GeminiLiveClient(this, lifecycleScope).apply {
            listener = object : GeminiLiveClient.Listener {
                override fun onConnected() {
                    binding.orbView.setState(OrbState.Active)
                    binding.statusText.text = "সংযুক্ত / Connected 🟢"
                }

                override fun onSetupComplete() {
                    audioEngine?.startRecording()
                    audioEngine?.startPlayback()
                    binding.statusText.text = "কথা বলুন / Speak now 💬"
                    binding.micButton.setImageResource(R.drawable.ic_mic_on)
                    binding.micButton.alpha = 1.0f
                }

                override fun onDisconnected(reason: String) {
                    binding.orbView.setState(OrbState.Idle)
                    binding.statusText.text = "পুনরায় সংযোগ করা হচ্ছে... / Reconnecting..."
                }

                override fun onError(error: String) {
                    binding.orbView.setState(OrbState.Idle)
                    binding.statusText.text = error
                }

                override fun onAudioReceived(pcmData: ByteArray) {
                    audioEngine?.playAudio(pcmData)
                }

                override fun onInputTranscript(text: String) {
                    runOnUiThread {
                        chatAdapter.addMessage(ChatMessage(text, isUser = true))
                        binding.chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)

                        // Check for phone commands
                        handleUserSpeech(text)
                    }
                }

                override fun onOutputTranscript(text: String) {
                    runOnUiThread {
                        chatAdapter.updateLastAryanMessage(text)
                        binding.chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }

                override fun onTurnComplete() {
                    binding.orbView.setState(OrbState.Active)
                }

                override fun onInterrupted() {
                    audioEngine?.clearPlaybackQueue()
                    binding.orbView.setState(OrbState.Active)
                    setRedOverlayActive(false)
                }
            }
        }

        audioEngine = AudioEngine(this, lifecycleScope).apply {
            listener = object : AudioEngine.Listener {
                override fun onAudioCaptured(pcmBytes: ByteArray) {
                    geminiClient?.sendMicAudio(pcmBytes)
                }

                override fun onAmplitudeChanged(rms: Float) {
                    binding.orbView.setAmplitude(rms)
                    binding.waveformView.setAmplitude(rms)
                    if (rms > 0.04f && !audioEngine!!.isSpeaking.get() && !isMuted) {
                        binding.orbView.setState(OrbState.Listening)
                    }
                }

                override fun onSpeakingStarted() {
                    binding.orbView.setState(OrbState.Speaking)
                    binding.waveformView.startAnimation()
                    setRedOverlayActive(true)
                    binding.statusText.text = "ARYAN কথা বলছে... / Speaking 🔊"
                }

                override fun onSpeakingStopped() {
                    binding.orbView.setState(OrbState.Active)
                    binding.waveformView.stopAnimation()
                    setRedOverlayActive(false)
                    binding.statusText.text = "শুনছি... / Listening 🎙️"
                }
            }
        }

        geminiClient?.connect()
    }

    private fun handleUserSpeech(text: String) {
        val lower = text.lowercase()

        // Incoming call response check
        if (lower.contains("রিসিভ করো") || lower.contains("ধরো") || lower.contains("answer") || lower.contains("pick up")) {
            viewModel.answerIncomingCall()
            return
        }
        if (lower.contains("কেটে দাও") || lower.contains("রিজেক্ট") || lower.contains("reject") || lower.contains("decline") || lower.contains("cut call")) {
            viewModel.rejectIncomingCall()
            return
        }

        // General phone commands
        val command = CommandParser.parse(text)
        if (command != null) {
            viewModel.executeCommand(command)
        }
    }

    private fun observeViewModel() {
        viewModel.commandResult.observe(this) { result ->
            if (!result.isNullOrEmpty()) {
                chatAdapter.addMessage(ChatMessage("⚡ $result", isUser = false))
                binding.chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
                geminiClient?.sendText("System action performed: $result. Confirm briefly to user naturally in current language.")
            }
        }
    }

    private fun setRedOverlayActive(active: Boolean) {
        val targetAlpha = if (active) 0.08f else 0.0f
        binding.redOverlay.animate()
            .alpha(targetAlpha)
            .setDuration(400)
            .start()
    }

    private fun startCallMonitorService() {
        val serviceIntent = Intent(this, CallMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting CallMonitorService", e)
        }
    }

    private fun startStatsUpdateLoop() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.ENGLISH)
            while (isActive) {
                // Update Time
                binding.timeText.text = timeFormat.format(Date())

                // Update Battery
                val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                binding.batteryText.text = "$batteryPct%"

                // Update RAM
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                val usedGb = (memInfo.totalMem - memInfo.availMem) / (1024.0 * 1024.0 * 1024.0)
                binding.ramText.text = String.format(Locale.US, "%.1fGB", usedGb)

                delay(20000)
            }
        }
    }

    override fun onDestroy() {
        statsJob?.cancel()
        try {
            unregisterReceiver(callReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        audioEngine?.release()
        audioEngine = null
        geminiClient?.disconnect()
        geminiClient = null
        super.onDestroy()
    }
}
