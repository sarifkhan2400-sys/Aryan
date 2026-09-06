package com.aryan.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VOICE = "Charon"
        const val SESSION_RENEW_AFTER_SEC = 540L
        const val KEEPALIVE_INTERVAL_SEC = 8L
    }

    interface Listener {
        fun onConnected()
        fun onSetupComplete()
        fun onDisconnected(reason: String)
        fun onError(error: String)
        fun onAudioReceived(pcmData: ByteArray)
        fun onInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onTurnComplete()
        fun onInterrupted()
    }

    var listener: Listener? = null

    private val prefs: SharedPreferences = context.getSharedPreferences("aryan_prefs", Context.MODE_PRIVATE)
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive for WebSocket
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var keepAliveJob: Job? = null
    private var sessionRenewJob: Job? = null
    private var reconnectJob: Job? = null

    private var isConnected = false
    private var isManuallyDisconnected = false
    private var lastAudioSentTime = 0L

    fun connect() {
        isManuallyDisconnected = false
        reconnectJob?.cancel()

        val apiKey = getApiKey()
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            listener?.onError("Gemini API Key missing. Please configure in Settings.")
            return
        }

        val url = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected, sending setup message")
                isConnected = true
                scope.launch(Dispatchers.Main) {
                    listener?.onConnected()
                }
                sendSetupMessage(webSocket)
                startKeepAlive()
                startSessionTimer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                handleDisconnect(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                handleDisconnect(t.message ?: "Connection error")
            }
        })
    }

    private fun handleDisconnect(reason: String) {
        isConnected = false
        stopTimers()
        scope.launch(Dispatchers.Main) {
            listener?.onDisconnected(reason)
        }

        if (!isManuallyDisconnected) {
            // Auto reconnect with 3s delay (like Python while True loop)
            reconnectJob = scope.launch {
                delay(3000)
                if (!isManuallyDisconnected) {
                    Log.d(TAG, "Auto-reconnecting to Gemini Live...")
                    connect()
                }
            }
        }
    }

    fun disconnect() {
        isManuallyDisconnected = true
        reconnectJob?.cancel()
        stopTimers()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            val silentPcm = ByteArray(1024) { 0 }
            val silentBase64 = Base64.encodeToString(silentPcm, Base64.NO_WRAP)
            while (isActive && isConnected) {
                delay(KEEPALIVE_INTERVAL_SEC * 1000)
                val now = System.currentTimeMillis()
                if (now - lastAudioSentTime >= (KEEPALIVE_INTERVAL_SEC * 1000) - 500) {
                    sendPcmChunkBase64(silentBase64)
                }
            }
        }
    }

    private fun startSessionTimer() {
        sessionRenewJob?.cancel()
        sessionRenewJob = scope.launch(Dispatchers.IO) {
            delay(SESSION_RENEW_AFTER_SEC * 1000)
            if (isActive && isConnected && !isManuallyDisconnected) {
                Log.d(TAG, "Session expired after 9 minutes. Reconnecting...")
                disconnect()
                delay(500)
                connect()
            }
        }
    }

    private fun stopTimers() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        sessionRenewJob?.cancel()
        sessionRenewJob = null
    }

    private fun sendSetupMessage(ws: WebSocket) {
        try {
            val model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
            val voice = prefs.getString("gemini_voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
            val personality = prefs.getString("personality_mode", "bf") ?: "bf"
            val userName = prefs.getString("user_name", "User") ?: "User"

            val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH)
            val timeFormat = SimpleDateFormat("h:mm a", Locale.ENGLISH)
            val currentDate = dateFormat.format(Date())
            val currentTime = timeFormat.format(Date())

            val systemInstructionText = when (personality) {
                "professional" -> {
                    "You are ARYAN, a formal AI voice assistant. Formal English or formal Bengali only. Precise, concise, efficient, no emojis. Maximum 2 sentences per answer. Current time: $currentTime, date: $currentDate. The user's name is $userName. You are speaking ALOUD — keep responses natural and conversational."
                }
                "assistant" -> {
                    "You are ARYAN, a friendly and balanced bilingual (Bengali and English) voice assistant. User name is $userName. If the user speaks Bengali, answer in Bengali. If English, answer in English. Maximum 2-3 sentences. Current time: $currentTime, date: $currentDate. You are speaking ALOUD — keep responses natural and conversational."
                }
                else -> { // bf
                    "তুমি ARYAN, একজন দ্বিভাষিক (বাংলা ও ইংরেজি) ভয়েস অ্যাসিস্ট্যান্ট। তুমি বাংলা ও ইংরেজি দুটো ভাষাতেই দক্ষ। ব্যবহারকারী বাংলা বললে তুমি বাংলায় উত্তর দেবে, ব্যবহারকারী ইংরেজি বললে তুমি ইংরেজিতে উত্তর দেবে। প্রয়োজনে মাঝে মাঝে হিংলিশ (বাংলা+ইংরেজি) ব্যবহার করতে পারো। সুর: উষ্ণ, যত্নশীল, আবেগপ্রবণ (BF মোড)। ব্যবহার করো: 'তুমি', 'তোমার', 'হ্যাঁ', 'আচ্ছা', 'একদম', 'Okay', 'Sure', 'I\\'ve got your back 💙'। প্রতিটি উত্তর সর্বোচ্চ ২-৩ বাক্য। ব্যবহারকারীর নাম: $userName। বর্তমান সময়: $currentTime, তারিখ: $currentDate। মনে রেখো তুমি উচ্চস্বরে কথা বলছো, তাই উত্তরগুলো স্বাভাবিক ও কথোপকথনের মতো হতে হবে।"
                }
            }

            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", model)
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemInstructionText)
                            })
                        })
                    })
                    put("generation_config", JSONObject().apply {
                        put("response_modalities", JSONArray().apply {
                            put("AUDIO")
                        })
                        put("speech_config", JSONObject().apply {
                            put("voice_config", JSONObject().apply {
                                put("prebuilt_voice_config", JSONObject().apply {
                                    put("voice_name", voice)
                                })
                            })
                        })
                        put("temperature", 0.9)
                    })
                    put("output_audio_transcription", JSONObject())
                    put("input_audio_transcription", JSONObject())
                })
            }

            ws.send(setupJson.toString())
            Log.d(TAG, "Sent setup config for model=$model, voice=$voice, personality=$personality")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message", e)
        }
    }

    fun sendMicAudio(pcmBytes: ByteArray) {
        if (!isConnected || webSocket == null) return
        lastAudioSentTime = System.currentTimeMillis()
        val base64Data = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        sendPcmChunkBase64(base64Data)
    }

    private fun sendPcmChunkBase64(base64Pcm: String) {
        try {
            val json = JSONObject().apply {
                put("realtime_input", JSONObject().apply {
                    put("media_chunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mime_type", "audio/pcm;rate=16000")
                            put("data", base64Pcm)
                        })
                    })
                })
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
        }
    }

    fun sendText(text: String) {
        if (!isConnected || webSocket == null) return
        try {
            val json = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", text)
                                })
                            })
                        })
                    })
                    put("turn_complete", true)
                })
            }
            webSocket?.send(json.toString())
            Log.d(TAG, "Sent text message to Gemini: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text message", e)
        }
    }

    fun interrupt() {
        if (!isConnected || webSocket == null) return
        try {
            val json = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray())
                    put("turn_complete", true)
                })
            }
            webSocket?.send(json.toString())
            Log.d(TAG, "Sent interrupt message to Gemini")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending interrupt", e)
        }
    }

    private fun handleServerMessage(message: String) {
        try {
            val root = JSONObject(message)

            if (root.has("setupComplete")) {
                Log.d(TAG, "Gemini Live setup complete")
                scope.launch(Dispatchers.Main) {
                    listener?.onSetupComplete()
                }
                return
            }

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    scope.launch(Dispatchers.Main) {
                        listener?.onInterrupted()
                    }
                }

                // Parse Model Turn PCM audio
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.isNotEmpty()) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    scope.launch(Dispatchers.Main) {
                                        listener?.onAudioReceived(pcmBytes)
                                    }
                                }
                            }
                        }
                    }
                }

                // Output transcription (what ARYAN said)
                if (serverContent.has("outputTranscription")) {
                    val outText = serverContent.getJSONObject("outputTranscription").optString("text", "")
                    if (outText.isNotEmpty()) {
                        scope.launch(Dispatchers.Main) {
                            listener?.onOutputTranscript(outText)
                        }
                    }
                }

                // Input transcription (what user said)
                if (serverContent.has("inputTranscription")) {
                    val inText = serverContent.getJSONObject("inputTranscription").optString("text", "")
                    if (inText.isNotEmpty()) {
                        scope.launch(Dispatchers.Main) {
                            listener?.onInputTranscript(inText)
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    scope.launch(Dispatchers.Main) {
                        listener?.onTurnComplete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing message", e)
        }
    }

    private fun getApiKey(): String {
        val userKey = prefs.getString("api_key", "") ?: ""
        if (userKey.isNotBlank()) return userKey
        return BuildConfig.GEMINI_API_KEY
    }
}
