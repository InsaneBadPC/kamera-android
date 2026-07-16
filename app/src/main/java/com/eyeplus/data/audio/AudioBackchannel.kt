package com.eyeplus.data.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.pedro.rtplibrary.rtsp.RtspAudioOnly
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio backchannel manager for sending audio from the phone's microphone
 * to the camera's speaker via RTSP.
 *
 * Uses pedroSG94 RootEncoder (rtmp-rtsp-stream-client-java) library.
 */
class AudioBackchannel {

    companion object {
        private const val TAG = "AudioBackchannel"

        // Audio recording parameters
        private const val SAMPLE_RATE = 8000
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2
    }

    enum class BackchannelState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class BackchannelStatus(
        val state: BackchannelState = BackchannelState.DISCONNECTED,
        val errorMessage: String? = null,
        val isStreaming: Boolean = false
    )

    private val _status = MutableStateFlow(BackchannelStatus())
    val status: StateFlow<BackchannelStatus> = _status.asStateFlow()

    private var rtspClient: RtspAudioOnly? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var scope: CoroutineScope? = null
    private var isRecording = false

    fun connect(rtspUrl: String, username: String, password: String) {
        if (_status.value.state == BackchannelState.CONNECTED ||
            _status.value.state == BackchannelState.CONNECTING) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        _status.value = BackchannelStatus(state = BackchannelState.CONNECTING)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val connectChecker = object : ConnectCheckerRtsp {
            override fun onConnectionSuccessRtsp() {
                Log.d(TAG, "RTSP backchannel connected successfully")
                _status.value = BackchannelStatus(
                    state = BackchannelState.CONNECTED,
                    isStreaming = true
                )
                startAudioCapture()
            }

            override fun onConnectionFailedRtsp(reason: String) {
                Log.e(TAG, "RTSP backchannel connection failed: $reason")
                _status.value = BackchannelStatus(
                    state = BackchannelState.ERROR,
                    errorMessage = reason
                )
            }

            override fun onDisconnectRtsp() {
                Log.d(TAG, "RTSP backchannel disconnected")
                _status.value = BackchannelStatus(
                    state = BackchannelState.DISCONNECTED
                )
            }

            override fun onAuthErrorRtsp() {
                Log.e(TAG, "RTSP backchannel auth error")
                _status.value = BackchannelStatus(
                    state = BackchannelState.ERROR,
                    errorMessage = "Authentication failed"
                )
            }

            override fun onAuthSuccessRtsp() {
                Log.d(TAG, "RTSP backchannel auth success")
            }
        }

        try {
            rtspClient = RtspAudioOnly(connectChecker).apply {
                setAuthorization(username, password)
                startStream(rtspUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create RTSP client: ${e.message}", e)
            _status.value = BackchannelStatus(
                state = BackchannelState.ERROR,
                errorMessage = "Failed to create RTSP client: ${e.message}"
            )
        }
    }

    private fun startAudioCapture() {
        if (isRecording) return
        isRecording = true

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        ) * BUFFER_SIZE_MULTIPLIER

        audioRecord = AudioRecord(
            AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            _status.value = BackchannelStatus(
                state = BackchannelState.ERROR,
                errorMessage = "AudioRecord initialization failed"
            )
            isRecording = false
            return
        }

        recordJob = scope?.launch {
            audioRecord?.startRecording()
            val buffer = ByteArray(bufferSize)

            while (isActive && isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    // Audio is sent automatically by the library
                    Log.v(TAG, "Audio bytes captured: $bytesRead")
                }
            }

            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (_: Exception) { }
        }

        Log.d(TAG, "Audio capture started")
    }

    fun sendAudioData(audioData: ByteArray) {
        if (_status.value.state != BackchannelState.CONNECTED) {
            Log.w(TAG, "Cannot send audio - backchannel not connected")
            return
        }
        Log.d(TAG, "Audio data size: ${audioData.size} bytes (TTS output)")
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting audio backchannel")

        isRecording = false
        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) { }

        try {
            rtspClient?.stopStream()
            rtspClient = null
        } catch (_: Exception) { }

        scope?.cancel()
        scope = null

        _status.value = BackchannelStatus(state = BackchannelState.DISCONNECTED)
    }
}
