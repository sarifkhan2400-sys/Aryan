package com.aryan.assistant.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioEngine"
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val CHUNK_SIZE = 1024
    }

    interface Listener {
        fun onAudioCaptured(pcmBytes: ByteArray)
        fun onAmplitudeChanged(rms: Float)
        fun onSpeakingStarted()
        fun onSpeakingStopped()
    }

    var listener: Listener? = null

    var isMuted = false
    val isSpeaking = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    private var recordingThread: Thread? = null
    private var playbackThread: Thread? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.get()) return

        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize, CHUNK_SIZE * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val buffer = ByteArray(CHUNK_SIZE)

                while (isRecording.get()) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        val rms = calculateRms(buffer, bytesRead)
                        scope.launch(Dispatchers.Main) {
                            listener?.onAmplitudeChanged(rms)
                        }

                        // Echo suppression: do not send mic audio if ARYAN is speaking aloud or muted
                        if (!isSpeaking.get() && !isMuted) {
                            val chunk = buffer.copyOf(bytesRead)
                            listener?.onAudioCaptured(chunk)
                        }
                    }
                }
            }, "AryanRecordThread").apply { start() }

            Log.d(TAG, "AudioRecord started at $MIC_SAMPLE_RATE Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        } finally {
            audioRecord = null
            recordingThread = null
        }
    }

    fun startPlayback() {
        if (isPlaying.get()) return

        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SPEAKER_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize, CHUNK_SIZE * 8)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying.set(true)

            playbackThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                while (isPlaying.get()) {
                    try {
                        val chunk = playbackQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (chunk != null && chunk.isNotEmpty()) {
                            if (!isSpeaking.get()) {
                                isSpeaking.set(true)
                                scope.launch(Dispatchers.Main) {
                                    listener?.onSpeakingStarted()
                                }
                            }
                            audioTrack?.write(chunk, 0, chunk.size)
                        } else {
                            if (isSpeaking.get() && playbackQueue.isEmpty()) {
                                isSpeaking.set(false)
                                scope.launch(Dispatchers.Main) {
                                    listener?.onSpeakingStopped()
                                }
                            }
                        }
                    } catch (ie: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in playback loop", e)
                    }
                }
            }, "AryanPlayThread").apply { start() }

            Log.d(TAG, "AudioTrack started at $SPEAKER_SAMPLE_RATE Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
        }
    }

    fun playAudio(pcmBytes: ByteArray) {
        if (!isPlaying.get()) {
            startPlayback()
        }
        playbackQueue.offer(pcmBytes)
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack", e)
        }
        if (isSpeaking.get()) {
            isSpeaking.set(false)
            scope.launch(Dispatchers.Main) {
                listener?.onSpeakingStopped()
            }
        }
    }

    fun stopPlayback() {
        isPlaying.set(false)
        clearPlaybackQueue()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
            playbackThread?.interrupt()
            playbackThread = null
        }
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }

    private fun calculateRms(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0.0
        val sampleCount = bytesRead / 2
        if (sampleCount == 0) return 0f

        for (i in 0 until bytesRead step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
        }
        val rms = sqrt(sum / sampleCount) / 32768.0
        return rms.coerceIn(0.0, 1.0).toFloat()
    }
}
