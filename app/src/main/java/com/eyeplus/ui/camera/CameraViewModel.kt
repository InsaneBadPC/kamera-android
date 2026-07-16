package com.eyeplus.ui.camera

import android.app.Application
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eyeplus.data.ai.MonitoringService
import com.eyeplus.data.onvif.OnvifClient
import com.eyeplus.data.onvif.OnvifDiscovery
import com.eyeplus.data.onvif.MediaProfile
import com.eyeplus.data.onvif.StreamUri
import com.eyeplus.data.onvif.PtzPosition
import com.eyeplus.data.audio.AudioBackchannel
import com.eyeplus.data.recording.RecorderManager
import com.eyeplus.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CameraUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isDiscovering: Boolean = false,
    val cameraIp: String = "",
    val cameraPort: Int = 80,
    val username: String = "admin",
    val password: String = "admin",
    val mainStreamUrl: String = "",
    val subStreamUrl: String = "",
    val profiles: List<MediaProfile> = emptyList(),
    val deviceInfo: Map<String, String> = emptyMap(),
    val error: String? = null,
    val ptzPosition: PtzPosition = PtzPosition(),
    val isPtzMoving: Boolean = false,
    val discoveredDevices: List<OnvifDiscovery.DiscoveredDevice> = emptyList(),
    // Recording state
    val isRecording: Boolean = false,
    val recordingError: String? = null,
    // AI Monitoring state
    val isMonitoring: Boolean = false,
    val monitoringStatus: String = "",
    val lastAlert: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var onvifClient: OnvifClient? = null
    private val discovery = OnvifDiscovery()
    private var multicastLock: WifiManager.MulticastLock? = null

    // Recording
    private var recorderManager: RecorderManager? = null

    // AI Monitoring
    private var monitoringServiceIntent: android.content.Intent? = null

    // Audio backchannel for camera speaker alerts
    private var audioBackchannel: AudioBackchannel? = null

    companion object {
        private const val ALERT_DEBOUNCE_MS = 60_000L // 1 min between audio alerts
    }
    private var lastAlertTimeMs = 0L

    /**
     * Discover ONVIF cameras on the local network.
     */
    fun discoverCameras() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDiscovering = true,
                error = null
            )

            try {
                val context = getApplication<Application>()
                multicastLock = NetworkUtils.acquireMulticastLock(context)

                val devices = discovery.discover(timeoutMs = 8000L)
                Log.d("CameraViewModel", "Discovery found ${devices.size} devices")
                devices.forEach { Log.d("CameraViewModel", "  ${it.source}: ${it.ip}:${it.port}") }

                _uiState.value = _uiState.value.copy(
                    isDiscovering = false,
                    discoveredDevices = devices
                )

                if (devices.isNotEmpty()) {
                    val first = devices.first()
                    connectToCamera(first.ip, first.port)
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Nebyla nalezena kamera.\n\n" +
                            "Tipy:\n" +
                            "1. Ujistěte se, že telefon a kamera jsou ve stejné WiFi síti\n" +
                            "2. Použijte 'Ruční připojení' a zadejte IP adresu kamery\n" +
                            "3. Zkontrolujte, zda má kamera zapnutý ONVIF\n" +
                            "4. Zkuste zadat IP ve tvaru: http://192.168.x.x:80"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDiscovering = false,
                    error = "Chyba při discovery: ${e.message}"
                )
            } finally {
                NetworkUtils.releaseMulticastLock(multicastLock)
                multicastLock = null
            }
        }
    }

    /**
     * Connect to a camera at a specific IP address.
     */
    fun connectToCamera(ip: String, port: Int = 80) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
                cameraIp = ip,
                cameraPort = port,
                error = null
            )

            try {
                val state = _uiState.value
                val client = OnvifClient(
                    host = ip,
                    port = port,
                    username = state.username,
                    password = state.password
                )

                val testResult = client.testConnection()
                if (!testResult.success) {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isConnected = false,
                        error = "Nelze se připojit ke kameře na $ip:$port\n\n" +
                            "Zkontrolujte:\n" +
                            "1. Je kamera zapnutá a připojená k síti?\n" +
                            "2. Je ONVIF povolený v nastavení kamery?\n" +
                            "3. Zkuste port 80, 8080 nebo 8899\n" +
                            "4. Zkuste heslo: admin / 123456\n" +
                            "5. Připojte se na IP kamery v prohlížeči: http://$ip"
                    )
                    Log.w("CameraViewModel", "Connection test failed: ${testResult.message}")
                    return@launch
                }

                val deviceInfo = client.getDeviceInformation()
                val profiles = client.getProfiles()
                val mainStream = client.getRtspUrl(0)
                val subStream = client.getRtspUrl(1)

                onvifClient = client

                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    isConnected = true,
                    profiles = profiles,
                    mainStreamUrl = mainStream,
                    subStreamUrl = subStream,
                    deviceInfo = deviceInfo
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    isConnected = false,
                    error = "Chyba připojení: ${e.message}"
                )
            }
        }
    }

    /**
     * Disconnect from the camera.
     */
    fun disconnect() {
        onvifClient = null
        _uiState.value = CameraUiState()
    }

    /**
     * Manual connect to camera with custom credentials.
     */
    fun manualConnect(ip: String, port: Int, username: String, password: String) {
        _uiState.value = _uiState.value.copy(
            username = username,
            password = password
        )
        connectToCamera(ip, port)
    }

    // ──────────────────────────────────────────────
    // PTZ Control
    // ──────────────────────────────────────────────

    /**
     * Move PTZ via joystick with direct float values.
     * Joystick pan/tilt range: -1.0 to 1.0
     */
    fun startPtzMove(pan: Float, tilt: Float) {
        val client = onvifClient ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPtzMoving = true)
            val success = client.relativeMove(pan, -tilt) // negate Y for natural joystick
            if (!success) {
                // Fallback: convert to direction string
                val dir = when {
                    pan > 0.3f && tilt > 0.3f -> "UP_RIGHT"
                    pan > 0.3f && tilt < -0.3f -> "DOWN_RIGHT"
                    pan < -0.3f && tilt > 0.3f -> "UP_LEFT"
                    pan < -0.3f && tilt < -0.3f -> "DOWN_LEFT"
                    tilt > 0.3f -> "UP"
                    tilt < -0.3f -> "DOWN"
                    pan > 0.3f -> "RIGHT"
                    pan < -0.3f -> "LEFT"
                    else -> "STOP"
                }
                if (dir != "STOP") {
                    client.cgiPtzCommand(dir)
                }
            }
        }
    }

    /**
     * Move PTZ via D-pad direction string.
     */
    fun startPtzMove(direction: String) {
        val client = onvifClient ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPtzMoving = true)

            val (pan, tilt) = when (direction.uppercase()) {
                "UP" -> 0f to 0.5f
                "DOWN" -> 0f to -0.5f
                "LEFT" -> -0.5f to 0f
                "RIGHT" -> 0.5f to 0f
                "UP_LEFT" -> -0.35f to 0.35f
                "UP_RIGHT" -> 0.35f to 0.35f
                "DOWN_LEFT" -> -0.35f to -0.35f
                "DOWN_RIGHT" -> 0.35f to -0.35f
                else -> 0f to 0f
            }

            // Try ONVIF PTZ first, fallback to CGI
            val success = client.relativeMove(pan, tilt)
            if (!success) {
                client.cgiPtzCommand(direction.uppercase())
            }
        }
    }

    fun stopPtz() {
        val client = onvifClient ?: return
        viewModelScope.launch {
            val stopped = client.stopPtz()
            if (!stopped) {
                client.cgiPtzCommand("STOP")
            }
            _uiState.value = _uiState.value.copy(isPtzMoving = false)
        }
    }

    fun refreshPtzStatus() {
        val client = onvifClient ?: return
        viewModelScope.launch {
            val status = client.getPtzStatus()
            if (status != null) {
                _uiState.value = _uiState.value.copy(ptzPosition = status)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Connection settings
    // ──────────────────────────────────────────────

    fun updateCredentials(username: String, password: String) {
        _uiState.value = _uiState.value.copy(
            username = username,
            password = password
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ──────────────────────────────────────────────
    // Recording
    // ──────────────────────────────────────────────

    /**
     * Toggle recording on/off.
     */
    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        try {
            val context = getApplication<Application>()
            recorderManager = RecorderManager(context)
            val surface = recorderManager?.startRecording()
            if (surface != null) {
                _uiState.value = _uiState.value.copy(
                    isRecording = true,
                    recordingError = null,
                    error = null
                )

                // Register state listener
                recorderManager?.addStateListener { state ->
                    when (state) {
                        is RecorderManager.RecordingState.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isRecording = false,
                                recordingError = state.message
                            )
                        }
                        is RecorderManager.RecordingState.Completed -> {
                            _uiState.value = _uiState.value.copy(
                                isRecording = false,
                                error = null
                            )
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isRecording = false,
                error = "Nahrávání selhalo: ${e.message}"
            )
        }
    }

    private fun stopRecording() {
        recorderManager?.stopRecording()
        recorderManager = null
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    // ──────────────────────────────────────────────
    // AI Monitoring
    // ──────────────────────────────────────────────

    /**
     * Toggle AI monitoring on/off.
     */
    fun toggleMonitoring() {
        if (_uiState.value.isMonitoring) {
            stopMonitoring()
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val state = _uiState.value
        if (state.subStreamUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                error = "Pro AI monitoring je potřeba nejprve připojit kameru"
            )
            return
        }

        try {
            val context = getApplication<Application>()
            monitoringServiceIntent = MonitoringService.startIntent(context).apply {
                putExtra("subStreamUrl", state.subStreamUrl)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(monitoringServiceIntent!!)
            } else {
                @Suppress("DEPRECATION")
                context.startService(monitoringServiceIntent!!)
            }

            _uiState.value = _uiState.value.copy(
                isMonitoring = true,
                monitoringStatus = "Aktivní",
                error = null
            )

            // Observe monitoring state
            viewModelScope.launch {
                MonitoringService.state.collect { serviceState ->
                    _uiState.value = _uiState.value.copy(
                        isMonitoring = serviceState.isRunning,
                        monitoringStatus = if (serviceState.isRunning) "Aktivní" else "",
                        lastAlert = serviceState.lastAnalysis?.let { analysis ->
                            if (analysis.peopleDetected) {
                                "Detekce: ${analysis.personCount} osob, ${analysis.alertLevel}"
                            } else null
                        }
                    )
                }
            }

            // Connect audio backchannel for camera speaker alerts
            viewModelScope.launch {
                val bc = AudioBackchannel(context)
                val connected = bc.connect(
                    host = state.cameraIp,
                    port = 554,
                    username = state.username,
                    password = state.password
                )
                if (connected) {
                    audioBackchannel = bc
                    Log.d("CameraViewModel", "Audio backchannel connected")
                } else {
                    Log.w("CameraViewModel", "Audio backchannel connection failed")
                }
            }

            // Wire callbacks: auto-record on person detection, audio alert on suspicious
            MonitoringService.onPersonDetected = { analysis ->
                if (!_uiState.value.isRecording) {
                    Log.d("CameraViewModel", "Auto-recording: person detected")
                    startRecording()
                }
            }
            MonitoringService.onSuspiciousActivity = { analysis ->
                val now = System.currentTimeMillis()
                if (now - lastAlertTimeMs > ALERT_DEBOUNCE_MS) {
                    lastAlertTimeMs = now
                    Log.d("CameraViewModel", "Suspicious activity alert: ${analysis.description}")
                    // Ensure recording is active
                    if (!_uiState.value.isRecording) {
                        startRecording()
                    }
                    // Speak alert through camera speaker
                    val bc = audioBackchannel
                    if (bc != null && bc.status.value.state == AudioBackchannel.State.CONNECTED) {
                        viewModelScope.launch {
                            bc.speakText(
                                "Upozornění: ${analysis.description.take(100)}"
                            )
                        }
                    }
                }
            }

        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                error = "AI monitoring selhal: ${e.message}"
            )
        }
    }

    private fun stopMonitoring() {
        // Unwire monitoring callbacks
        MonitoringService.onPersonDetected = null
        MonitoringService.onSuspiciousActivity = null

        try {
            val context = getApplication<Application>()
            context.stopService(MonitoringService.stopIntent(context))
            monitoringServiceIntent = null
        } catch (_: Exception) { }

        // Disconnect audio backchannel
        audioBackchannel?.let { bc ->
            viewModelScope.launch {
                bc.disconnect()
                audioBackchannel = null
            }
        }

        _uiState.value = _uiState.value.copy(
            isMonitoring = false,
            monitoringStatus = "",
            lastAlert = null
        )
    }

    override fun onCleared() {
        super.onCleared()

        // Clean up monitoring callbacks
        MonitoringService.onPersonDetected = null
        MonitoringService.onSuspiciousActivity = null

        recorderManager?.stopRecording()
        recorderManager = null
        try {
            val context = getApplication<Application>()
            context.stopService(MonitoringService.stopIntent(context))
        } catch (_: Exception) { }

        // Clean up audio backchannel
        audioBackchannel?.let { bc ->
            viewModelScope.launch {
                bc.disconnect()
                audioBackchannel = null
            }
        }
    }
}
