package com.eyeplus.data.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio backchannel manager (stub - pedroSG94 library unavailable on JitPack).
 *
 * TODO: Implement when library becomes available or use alternative:
 * - Option 1: Use android.net.rtp.AudioGroup API (deprecated but works)
 * - Option 2: Implement custom RTSP audio publisher
 * - Option 3: Use camera CGI audio upload endpoint
 *
 * Currently provides the interface but no actual streaming.
 */
class AudioBackchannel {

    companion object {
        private const val TAG = "AudioBackchannel"
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

    fun connect(rtspUrl: String, username: String, password: String) {
        Log.d(TAG, "Audio backchannel connect called (stub): $rtspUrl")
        _status.value = BackchannelStatus(
            state = BackchannelState.ERROR,
            errorMessage = "Audio backchannel not yet implemented"
        )
    }

    fun sendAudioData(audioData: ByteArray) {
        Log.d(TAG, "Audio data: ${audioData.size} bytes (stub, not sent)")
    }

    fun disconnect() {
        Log.d(TAG, "Audio backchannel disconnect (stub)")
        _status.value = BackchannelStatus(state = BackchannelState.DISCONNECTED)
    }
}
