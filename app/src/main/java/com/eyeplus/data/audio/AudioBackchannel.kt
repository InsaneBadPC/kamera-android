package com.eyeplus.data.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import kotlin.random.Random
import kotlin.coroutines.resume

/**
 * RTSP audio backchannel for sending audio to an IP camera's speaker
 * via the ONVIF Profile T backchannel protocol.
 *
 * ## Protocol flow
 * 1. TCP connect to camera RTSP port (554)
 * 2. DESCRIBE with `Require: www.onvif.org/ver20/backchannel`
 * 3. Parse SDP for `a=sendonly` audio track
 * 4. SETUP the sendonly track with `Transport: RTP/AVP/TCP;interleaved=0-1`
 * 5. PLAY
 * 6. Send G.711 μ-law audio wrapped in RTP over RTSP interleaved TCP
 * 7. Keep-alive via GET_PARAMETER every 30 seconds
 *
 * ## RTSP interleaved frame format
 * ```
 * $<channel_byte><2-byte-length-big-endian><RTP-header><G.711-payload>
 * ```
 *
 * ## Usage
 * ```kotlin
 * val bc = AudioBackchannel(context)
 * bc.connect("192.168.1.100", username = "admin", password = "admin")
 * bc.sendAudio(pcmBytes, sampleRate = 16000)
 * bc.speakText("Intruder detected at front door")
 * bc.disconnect()
 * ```
 */
class AudioBackchannel(
    private val context: Context? = null
) {

    // ──────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AudioBackchannel"
        private const val RTSP_PORT = 554
        private const val RTP_HEADER_SIZE = 12
        private const val SAMPLES_PER_PACKET = 160   // 20 ms at 8 kHz
        private const val CLOCK_RATE = 8000
        private const val KEEP_ALIVE_MS = 30_000L
        private const val CHANNEL_SEND = 0
        private const val MARKER_DOLLAR = 0x24       // '$'
        private const val PT_PCMU = 0                 // G.711 μ-law
        private const val USER_AGENT = "EyePlus/1.0"
        private const val RECV_TIMEOUT_MS = 10_000
    }

    // ──────────────────────────────────────────────────────────────
    // Public types
    // ──────────────────────────────────────────────────────────────

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class Status(
        val state: State = State.DISCONNECTED,
        val errorMessage: String? = null,
        val isStreaming: Boolean = false
    )

    // ──────────────────────────────────────────────────────────────
    // Observable state
    // ──────────────────────────────────────────────────────────────

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // ──────────────────────────────────────────────────────────────
    // Network state
    // ──────────────────────────────────────────────────────────────

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var bufferedInput: BufferedInputStream? = null

    // ──────────────────────────────────────────────────────────────
    // RTSP / RTP session state
    // ──────────────────────────────────────────────────────────────

    private var sessionId: String? = null
    private var cseq = 1
    private var sequenceNumber = Random.nextInt() and 0xFFFF
    private var timestamp = 0L
    private val ssrc = Random.nextLong() and 0xFFFFFFFFL
    private var audioControlUrl: String? = null
    private var authHeader: String? = null
    private var rtspBaseUrl: String? = null

    // ──────────────────────────────────────────────────────────────
    // Coroutine infrastructure
    // ──────────────────────────────────────────────────────────────

    /** Guards all writes to the socket output stream. */
    private val writeMutex = Mutex()

    /** Shared IO scope – children are cancelled on disconnect. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var keepAliveJob: Job? = null
    private var readerJob: Job? = null
    private var ttsManager: TtsManager? = null

    // ══════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════

    /**
     * Connect to the camera's RTSP port and negotiate the audio backchannel.
     *
     * @param host     Camera IP address or hostname.
     * @param port     RTSP port (default 554).
     * @param username ONVIF camera username.
     * @param password ONVIF camera password.
     * @return `true` if the backchannel is ready for sending audio.
     */
    suspend fun connect(
        host: String,
        port: Int = RTSP_PORT,
        username: String = "admin",
        password: String = "admin"
    ): Boolean = withContext(Dispatchers.IO) {
        // Prevent duplicate connects.
        if (_status.value.state == State.CONNECTED) {
            Log.d(TAG, "Already connected")
            return@withContext true
        }

        _status.value = Status(state = State.CONNECTING)

        try {
            // 1. Build authorisation header.
            val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
            authHeader = "Basic $credentials"
            rtspBaseUrl = "rtsp://$host:$port/"

            // 2. Open TCP socket.
            Log.d(TAG, "Connecting to $host:$port ...")
            socket = Socket(host, port).apply {
                soTimeout = RECV_TIMEOUT_MS
                keepAlive = true
                tcpNoDelay = true
            }
            outputStream = socket!!.getOutputStream()
            bufferedInput = BufferedInputStream(socket!!.getInputStream(), 4096)
            Log.d(TAG, "TCP connected")

            // 3. DESCRIBE with ONVIF backchannel requirement.
            val describeResponse = sendRtspRequest(
                "DESCRIBE", rtspBaseUrl!!,
                headers = mapOf(
                    "Require" to "www.onvif.org/ver20/backchannel",
                    "Accept" to "application/sdp"
                )
            ) ?: throw IOException("No response to DESCRIBE")

            if (describeResponse.startsWith("RTSP/1.0 401")) {
                throw IOException("Authentication failed (check username/password)")
            }

            // 4. Parse SDP for the sendonly audio track.
            val sdp = extractSdpBody(describeResponse)
                ?: throw IOException("No SDP body in DESCRIBE response")

            val controlUrl = parseSdpForSendonlyAudio(sdp)
                ?: throw IOException("Camera does not advertise a sendonly audio track (no backchannel support)")

            audioControlUrl = controlUrl
            Log.d(TAG, "Found sendonly audio track: $controlUrl")

            // 5. SETUP the sendonly track with TCP interleaved transport.
            val setupUrl = resolveUrl(rtspBaseUrl!!, controlUrl)
            val setupResponse = sendRtspRequest(
                "SETUP", setupUrl,
                headers = mapOf(
                    "Transport" to "RTP/AVP/TCP;interleaved=$CHANNEL_SEND-1"
                )
            ) ?: throw IOException("No response to SETUP")

            val sessionMatch = Regex("Session:\\s*(\\S+)").find(setupResponse)
            sessionId = sessionMatch?.groupValues?.get(1)
                ?: throw IOException("No Session ID in SETUP response")
            Log.d(TAG, "Session ID: $sessionId")

            // 6. PLAY.
            val playResponse = sendRtspRequest(
                "PLAY", rtspBaseUrl!!,
                headers = mapOf("Session" to sessionId!!)
            ) ?: throw IOException("No response to PLAY")

            if (!playResponse.startsWith("RTSP/1.0 2")) {
                throw IOException("PLAY failed: ${playResponse.lines().firstOrNull()}")
            }

            // 7. Start background tasks.
            startKeepAlive()
            startReader()

            _status.value = Status(state = State.CONNECTED, isStreaming = true)
            Log.d(TAG, "Audio backchannel connected successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Connect failed", e)
            cleanup()
            _status.value = Status(
                state = State.ERROR,
                errorMessage = e.message ?: "Unknown error"
            )
            false
        }
    }

    /**
     * Send PCM audio data to the camera speaker.
     *
     * The PCM data **must** be 16-bit signed **little-endian** mono.
     * It is automatically resampled to 8 kHz, encoded to G.711 μ-law
     * and sent as a sequence of RTP packets over the RTSP interleaved
     * TCP connection.
     *
     * @param pcmData    16-bit PCM byte array (little-endian).
     * @param sampleRate Sample rate of the input PCM (default 8000).
     */
    suspend fun sendAudio(pcmData: ByteArray, sampleRate: Int = CLOCK_RATE) {
        if (_status.value.state != State.CONNECTED) {
            Log.w(TAG, "Cannot send audio: not connected")
            return
        }

        if (pcmData.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                // 1. Convert bytes → shorts and resample to 8 kHz.
                val pcmSamples = if (sampleRate == CLOCK_RATE) {
                    bytesToShorts(pcmData)
                } else {
                    resampleTo8kHz(pcmData, sampleRate)
                }

                if (pcmSamples.isEmpty()) return@withContext

                // 2. Encode to μ-law and send in RTP packets.
                val os = outputStream ?: return@withContext
                var offset = 0

                while (offset < pcmSamples.size) {
                    val chunkSize = minOf(SAMPLES_PER_PACKET, pcmSamples.size - offset)
                    val chunk = pcmSamples.sliceArray(offset until offset + chunkSize)

                    // Encode 16-bit PCM → 8-bit μ-law via the existing codec.
                    val encoded = G711Codec.encodeULaw(chunk)

                    // Build 12-byte RTP header.
                    val rtpHeader = buildRtpHeader(
                        marker = (offset + chunkSize >= pcmSamples.size),
                        pt = PT_PCMU,
                        seq = sequenceNumber,
                        ts = timestamp,
                        ssrc = ssrc
                    )

                    // Build interleaved frame: $<ch><2-byte-len><RTP><payload>.
                    val frame = buildInterleavedFrame(CHANNEL_SEND, rtpHeader, encoded)

                    // Send (thread-safe via writeMutex).
                    writeMutex.withLock {
                        os.write(frame)
                        os.flush()
                    }

                    // Advance RTP state.
                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    // Each 8 kHz sample advances the 32-bit timestamp by 1 clock tick.
                    timestamp = (timestamp + chunkSize) and 0xFFFFFFFFL
                    offset += chunkSize
                }

                Log.d(TAG, "Sent ${pcmSamples.size} samples (${pcmData.size} input bytes)")

            } catch (e: Exception) {
                Log.e(TAG, "sendAudio failed", e)
                _status.value = Status(
                    state = State.ERROR,
                    errorMessage = "Send failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Synthesise text to speech via Android TTS and send it to the
     * camera speaker.
     *
     * Requires a non-null `Context` to have been supplied in the
     * constructor – otherwise this method is a no-op.
     *
     * @param text The text to speak through the camera speaker.
     */
    suspend fun speakText(text: String) {
        if (context == null) {
            Log.w(TAG, "speakText requires a Context – none was provided")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // Synthesise to a temporary WAV file.
                val tempFile = File(context!!.cacheDir, "tts_${System.nanoTime()}.wav")
                try {
                    val ok = synthesizeToWav(text, tempFile)
                    if (!ok || !tempFile.exists() || tempFile.length() < 44L) {
                        Log.w(TAG, "TTS synthesis produced no audio")
                        return@withContext
                    }

                    // Read the PCM data and its sample rate from the WAV.
                    val pcmData = readWavFile(tempFile) ?: return@withContext
                    val wavSampleRate = getWavSampleRate(tempFile)

                    Log.d(TAG, "TTS synthesised ${pcmData.size} bytes at ${wavSampleRate}Hz")
                    sendAudio(pcmData, wavSampleRate)
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "speakText failed", e)
            }
        }
    }

    /**
     * Disconnect the audio backchannel and release all resources.
     */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                Log.d(TAG, "Disconnecting audio backchannel")
                cleanup()
                _status.value = Status(state = State.DISCONNECTED)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  RTSP helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * Send an RTSP request and return the full response (headers + body).
     *
     * **Must** be called while [writeMutex] is held to keep the
     * interleaved TCP stream from being corrupted.
     */
    private fun sendRtspRequest(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): String? {
        val os = outputStream ?: return null
        val cseqValue = cseq++

        try {
            // ── build request ──────────────────────────────────
            val request = buildString {
                append("$method $url RTSP/1.0\r\n")
                append("CSeq: $cseqValue\r\n")
                if (authHeader != null) append("Authorization: $authHeader\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (body != null) {
                    val bodyBytes = body.toByteArray()
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Content-Type: application/sdp\r\n")
                }
                append("\r\n")
                if (body != null) append(body)
            }

            Log.d(TAG, ">>> $method $url (CSeq: $cseqValue)")
            Log.v(TAG, "Request:\n$request")

            os.write(request.toByteArray())
            os.flush()

            // ── read response ─────────────────────────────────
            return readResponse()

        } catch (e: Exception) {
            Log.e(TAG, "RTSP $method failed", e)
            return null
        }
    }

    /**
     * Read one RTSP response from the buffered input stream.
     *
     * Handles interleaved binary data (‘$’ frames) that may appear
     * between RTSP text frames by skipping over them transparently.
     *
     * The response is returned as a single string with headers and
     * optional body.
     */
    private fun readResponse(): String? {
        val input = bufferedInput ?: return null

        try {
            // ── read header lines until blank line ────────────
            val headerBuf = StringBuilder()
            var prev = -1
            var consecutiveCrLf = 0

            while (true) {
                val b = input.read()
                if (b == -1) {
                    return if (headerBuf.isEmpty()) null else headerBuf.toString()
                }

                // Interleaved binary frame – skip it.
                if (b == MARKER_DOLLAR) {
                    input.read()      // channel (discard)
                    val lenHi = input.read()
                    val lenLo = input.read()
                    val frameLen = ((lenHi shl 8) or lenLo) and 0xFFFF
                    var remaining = frameLen.coerceAtMost(65535)
                    while (remaining > 0) {
                        val skipped = input.skip(remaining.toLong())
                        if (skipped <= 0) break
                        remaining -= skipped.toInt()
                    }
                    continue
                }

                headerBuf.append(b.toChar())

                // Detect \r\n\r\n (two consecutive CRLFs).
                if (b == '\n'.code && prev == '\r'.code) {
                    consecutiveCrLf++
                    if (consecutiveCrLf == 2) break
                } else if (b != '\r'.code && b != '\n'.code) {
                    consecutiveCrLf = 0
                }
                prev = b
            }

            val headerText = headerBuf.toString()

            // ── read body if Content-Length is present ────────
            val contentLength = Regex(
                "Content-Length:\\s*(\\d+)",
                RegexOption.IGNORE_CASE
            ).find(headerText)?.groupValues?.get(1)?.toIntOrNull()

            if (contentLength != null && contentLength > 0) {
                val body = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = input.read(body, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                return headerText + String(body, 0, totalRead)
            }

            return headerText

        } catch (e: Exception) {
            if (e !is SocketException) {
                Log.e(TAG, "Error reading RTSP response", e)
            }
            return null
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  RTP / Interleaved frame builders
    // ══════════════════════════════════════════════════════════════

    /**
     * Build a 12-byte RTP header for G.711 μ-law audio.
     *
     * Layout (per RFC 3550):
     * ```
     *  0  1  2  3   4  5  6  7   8  9 10 11
     * |V=2|P|X| CC=0 |M| PT=0|  sequence no.  |
     * |          timestamp (32-bit)            |
     * |              SSRC (32-bit)             |
     * ```
     */
    private fun buildRtpHeader(
        marker: Boolean,
        pt: Int,
        seq: Int,
        ts: Long,
        ssrc: Long
    ): ByteArray {
        val hdr = ByteArray(RTP_HEADER_SIZE)

        // Byte 0: V=2, P=0, X=0, CC=0 → 0x80
        hdr[0] = 0x80.toByte()

        // Byte 1: M (1 bit) | PT (7 bits)
        hdr[1] = ((if (marker) 0x80 else 0x00) or (pt and 0x7F)).toByte()

        // Bytes 2–3: sequence number (big-endian)
        hdr[2] = (seq shr 8).toByte()
        hdr[3] = (seq and 0xFF).toByte()

        // Bytes 4–7: timestamp (big-endian, 32-bit)
        val ts32 = ts and 0xFFFF_FFFFL
        hdr[4] = ((ts32 shr 24) and 0xFF).toByte()
        hdr[5] = ((ts32 shr 16) and 0xFF).toByte()
        hdr[6] = ((ts32 shr 8) and 0xFF).toByte()
        hdr[7] = (ts32 and 0xFF).toByte()

        // Bytes 8–11: SSRC (big-endian, 32-bit)
        val ssrc32 = ssrc and 0xFFFF_FFFFL
        hdr[8] = ((ssrc32 shr 24) and 0xFF).toByte()
        hdr[9] = ((ssrc32 shr 16) and 0xFF).toByte()
        hdr[10] = ((ssrc32 shr 8) and 0xFF).toByte()
        hdr[11] = (ssrc32 and 0xFF).toByte()

        return hdr
    }

    /**
     * Build an RTSP interleaved frame:
     * ```
     * $<channel-byte><2-byte-big-endian-length><RTP-header><payload>
     * ```
     */
    private fun buildInterleavedFrame(
        channel: Int,
        rtpHeader: ByteArray,
        payload: ByteArray
    ): ByteArray {
        val totalLen = rtpHeader.size + payload.size
        val frame = ByteArray(4 + totalLen)

        frame[0] = MARKER_DOLLAR.toByte()
        frame[1] = (channel and 0xFF).toByte()
        frame[2] = ((totalLen shr 8) and 0xFF).toByte()
        frame[3] = (totalLen and 0xFF).toByte()

        System.arraycopy(rtpHeader, 0, frame, 4, rtpHeader.size)
        System.arraycopy(payload, 0, frame, 4 + rtpHeader.size, payload.size)

        return frame
    }

    // ══════════════════════════════════════════════════════════════
    //  SDP parsing
    // ══════════════════════════════════════════════════════════════

    /**
     * Extract the SDP body (starting with `v=`) from the DESCRIBE response.
     */
    private fun extractSdpBody(response: String): String? {
        val lines = response.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("v=", ignoreCase = true) }
        if (start == -1) return null
        return lines.drop(start).joinToString("\n")
    }

    /**
     * Parse the SDP for a sendonly audio track.
     *
     * Looks for:
     * ```
     * m=audio 0 RTP/AVP 0
     * a=sendonly
     * a=control:trackID=1
     * ```
     *
     * @return The control URL for the sendonly audio track, or `null`.
     */
    private fun parseSdpForSendonlyAudio(sdp: String): String? {
        val lines = sdp.lines()
        var inAudio = false
        var isSendonly = false
        var controlUrl: String? = null

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("m=", ignoreCase = true) -> {
                    inAudio = t.startsWith("m=audio", ignoreCase = true)
                    isSendonly = false
                    controlUrl = null
                }
                inAudio && t.startsWith("a=sendonly", ignoreCase = true) -> isSendonly = true
                inAudio && t.startsWith("a=control:", ignoreCase = true) -> {
                    controlUrl = t.substringAfter("a=control:").trim()
                }
            }
        }

        return if (isSendonly && controlUrl != null) controlUrl else null
    }

    /**
     * Resolve a potentially relative control URL against the base RTSP URL.
     */
    private fun resolveUrl(base: String, control: String): String {
        if (control.startsWith("rtsp://")) return control
        if (control.startsWith("/")) {
            // Absolute path – resolve against host.
            val hostMatch = Regex("rtsp://([^/]+)").find(base)
            return if (hostMatch != null) "${hostMatch.value}$control" else "$base$control"
        }
        // Relative – append to base.
        return "${base.trimEnd('/')}/$control"
    }

    // ══════════════════════════════════════════════════════════════
    //  Audio processing
    // ══════════════════════════════════════════════════════════════

    /**
     * Convert a 16-bit signed little-endian PCM byte array to a [ShortArray].
     */
    private fun bytesToShorts(pcmBytes: ByteArray): ShortArray {
        val shorts = ShortArray(pcmBytes.size / 2)
        for (i in shorts.indices) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt() shl 8
            shorts[i] = (hi or lo).toShort()
        }
        return shorts
    }

    /**
     * Resample 16-bit PCM data to 8 kHz using linear interpolation.
     *
     * @param pcmBytes   16-bit signed little-endian PCM input.
     * @param sampleRate Source sample rate (e.g. 16000, 22050, 44100).
     * @return 8 kHz 16-bit PCM samples as a [ShortArray].
     */
    private fun resampleTo8kHz(pcmBytes: ByteArray, sampleRate: Int): ShortArray {
        if (sampleRate <= 0) return ShortArray(0)
        val input = bytesToShorts(pcmBytes)
        if (sampleRate == CLOCK_RATE) return input

        val outputLen = (input.size.toLong() * CLOCK_RATE / sampleRate).toInt()
        if (outputLen <= 0) return ShortArray(0)

        val output = ShortArray(outputLen)
        val ratio = sampleRate.toDouble() / CLOCK_RATE.toDouble()

        for (i in 0 until outputLen) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            if (srcIdx >= input.size - 1) {
                output[i] = input.lastOrNull() ?: 0
            } else {
                // Linear interpolation across unsigned 16-bit range
                // to avoid sign-extension artefacts.
                val s0 = input[srcIdx].toInt() and 0xFFFF
                val s1 = input[srcIdx + 1].toInt() and 0xFFFF
                val interpolated = (s0 * (1.0 - frac) + s1 * frac).toInt()
                output[i] = (interpolated and 0xFFFF).toShort()
            }
        }

        return output
    }

    // ══════════════════════════════════════════════════════════════
    //  TTS → WAV → PCM pipeline (used by speakText)
    // ══════════════════════════════════════════════════════════════

    /**
     * Synthesise [text] to a WAV file using Android's built-in TTS engine.
     *
     * @return `true` if the file was written successfully.
     */
    private suspend fun synthesizeToWav(text: String, outputFile: File): Boolean {
        if (context == null) return false

        return suspendCancellableCoroutine { continuation ->
            lateinit var tts: android.speech.tts.TextToSpeech
            tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) {
                                tts.shutdown()
                                continuation.resume(outputFile.exists() && outputFile.length() > 0L)
                            }

                            override fun onError(utteranceId: String?) {
                                tts.shutdown()
                                continuation.resume(false)
                            }
                        }
                    )
                    tts.synthesizeToFile(text, null, outputFile, "eyeplus_tts")
                } else {
                    tts.shutdown()
                    continuation.resume(false)
                }
            }
        }
    }

    /**
     * Extract raw 16-bit PCM data from a WAV file.
     *
     * Only standard PCM (format = 1), 16-bit mono is supported.
     *
     * @return Raw little-endian PCM byte array, or `null` on failure.
     */
    private fun readWavFile(file: File): ByteArray? {
        try {
            val data = file.readBytes()
            if (data.size < 44) return null

            // Validate RIFF header.
            if (data[0] != 'R'.code.toByte() || data[1] != 'I'.code.toByte() ||
                data[2] != 'F'.code.toByte() || data[3] != 'F'.code.toByte()
            ) {
                Log.w(TAG, "Not a valid WAV file")
                return null
            }

            val format = ((data[21].toInt() and 0xFF) shl 8) or (data[20].toInt() and 0xFF)
            if (format != 1) { // 1 = PCM
                Log.w(TAG, "Unsupported WAV format: $format (expected PCM=1)")
                return null
            }

            val bitsPerSample = ((data[35].toInt() and 0xFF) shl 8) or (data[34].toInt() and 0xFF)
            if (bitsPerSample != 16) {
                Log.w(TAG, "Unsupported bits/sample: $bitsPerSample (expected 16)")
                return null
            }

            // Walk RIFF chunks to find 'data'.
            var offset = 12 // skip RIFF header
            while (offset <= data.size - 8) {
                val chunkId = String(data, offset, 4)
                val chunkSize =
                    ((data[offset + 7].toInt() and 0xFF) shl 24) or
                            ((data[offset + 6].toInt() and 0xFF) shl 16) or
                            ((data[offset + 5].toInt() and 0xFF) shl 8) or
                            (data[offset + 4].toInt() and 0xFF)

                if (chunkId == "data") {
                    val pcmStart = offset + 8
                    val pcmLen = minOf(chunkSize, data.size - pcmStart)
                    return data.copyOfRange(pcmStart, pcmStart + pcmLen)
                }

                offset += 8 + chunkSize
                // Round up to even boundary per RIFF spec.
                if (chunkSize % 2 != 0) offset++
            }

            Log.w(TAG, "No 'data' chunk found in WAV")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV file", e)
            return null
        }
    }

    /**
     * Extract the sample rate from a WAV file header.
     */
    private fun getWavSampleRate(file: File): Int {
        try {
            val hdr = file.readBytes().take(44).toByteArray()
            if (hdr.size < 44) return CLOCK_RATE
            return ((hdr[27].toInt() and 0xFF) shl 24) or
                    ((hdr[26].toInt() and 0xFF) shl 16) or
                    ((hdr[25].toInt() and 0xFF) shl 8) or
                    (hdr[24].toInt() and 0xFF)
        } catch (_: Exception) {
            return CLOCK_RATE
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Background tasks
    // ══════════════════════════════════════════════════════════════

    /**
     * Start a keep-alive coroutine that sends RTSP GET_PARAMETER
     * every [KEEP_ALIVE_MS] to prevent the session from timing out.
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_MS)
                try {
                    writeMutex.withLock {
                        val sid = sessionId ?: return@withLock
                        Log.d(TAG, "Keep-alive GET_PARAMETER")
                        sendRtspRequest(
                            "GET_PARAMETER", rtspBaseUrl!!,
                            headers = mapOf("Session" to sid)
                        )
                        // Response is consumed by sendRtspRequest / readResponse
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Keep-alive failed", e)
                        if (e is IOException || e is SocketException) {
                            _status.value = Status(
                                state = State.ERROR,
                                errorMessage = "Connection lost: ${e.message}"
                            )
                            cleanup()
                        }
                    }
                }
            }
        }
    }

    /**
     * Start a background reader coroutine that consumes any data
     * arriving from the server (RTSP responses and interleaved binary
     * frames) so the TCP receive buffer does not fill up.
     *
     * Responses to keep-alive requests are discarded here; they are
     * also read in [sendRtspRequest] – the double-read is harmless
     * because the background reader and [sendRtspRequest] are
     * serialised by [writeMutex].
     */
    private fun startReader() {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val input = bufferedInput ?: return@launch
                val sock = socket ?: return@launch

                while (isActive && sock.isConnected && !sock.isClosed) {
                    if (input.available() > 0) {
                        // Peek the first byte without consuming it.
                        input.mark(1)
                        val firstByte = input.read()
                        if (firstByte == -1) break
                        input.reset()

                        writeMutex.withLock {
                            if (firstByte == MARKER_DOLLAR) {
                                // Interleaved binary frame – skip it.
                                input.read() // $
                                input.read() // channel
                                val lenHi = input.read()
                                val lenLo = input.read()
                                val frameLen = ((lenHi shl 8) or lenLo) and 0xFFFF
                                var remaining = frameLen.coerceAtMost(65535)
                                while (remaining > 0) {
                                    val skipped = input.skip(remaining.toLong())
                                    if (skipped <= 0) break
                                    remaining -= skipped.toInt()
                                }
                            } else {
                                // RTSP text response – read and discard.
                                readResponse()
                            }
                        }
                    } else {
                        delay(50)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && e !is SocketException) {
                    Log.d(TAG, "Reader stopped: ${e.message}")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════════════════════════════

    /** Release all network and coroutine resources. */
    private fun cleanup() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        readerJob?.cancel()
        readerJob = null

        try {
            bufferedInput?.close()
        } catch (_: Exception) {}

        try {
            outputStream?.close()
        } catch (_: Exception) {}

        try {
            socket?.close()
        } catch (_: Exception) {}

        bufferedInput = null
        outputStream = null
        socket = null

        sessionId = null
        audioControlUrl = null
        authHeader = null
        rtspBaseUrl = null

        // Reset RTP counters so the next connect starts fresh.
        sequenceNumber = Random.nextInt() and 0xFFFF
        timestamp = 0L
        cseq = 1

        Log.d(TAG, "Cleanup complete")
    }
}
