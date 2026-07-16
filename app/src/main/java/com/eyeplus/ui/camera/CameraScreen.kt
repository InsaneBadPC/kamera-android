package com.eyeplus.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eyeplus.data.onvif.OnvifDiscovery
import com.eyeplus.ui.theme.*

/**
 * Main camera screen with video stream, PTZ controls and connection management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        when {
            !uiState.isConnected -> {
                ConnectionPanel(
                    state = uiState,
                    onDiscover = { viewModel.discoverCameras() },
                    onConnect = { ip, port, user, pass ->
                        viewModel.manualConnect(ip, port, user, pass)
                    },
                    onDeviceSelected = { device ->
                        viewModel.connectToCamera(device.ip, device.port)
                    }
                )
            }
            else -> {
                CameraConnectedContent(
                    state = uiState,
                    onDisconnect = { viewModel.disconnect() },
                    onPtzMove = { direction -> viewModel.startPtzMove(direction) },
                    onPtzStop = { viewModel.stopPtz() },
                    onPtzMoveRelative = { pan, tilt -> viewModel.startPtzMove(pan, tilt) },
                    onToggleRecording = { viewModel.toggleRecording() },
                    onToggleMonitoring = { viewModel.toggleMonitoring() }
                )
            }
        }

        // Error display
        uiState.error?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Default.Close, "Zavřít")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Connection Panel (shown when not connected)
// ─────────────────────────────────────────────────

@Composable
private fun ConnectionPanel(
    state: CameraUiState,
    onDiscover: () -> Unit,
    onConnect: (String, Int, String, String) -> Unit,
    onDeviceSelected: (OnvifDiscovery.DiscoveredDevice) -> Unit
) {
    var showManual by remember { mutableStateOf(true) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("80") }
    var manualUser by remember { mutableStateOf("admin") }
    var manualPass by remember { mutableStateOf("admin") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "EyePlus AI",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Inteligentní dozor nad PTZ IP kamerou",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Discover button
        Button(
            onClick = onDiscover,
            enabled = !state.isDiscovering,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isDiscovering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Vyhledávání kamer v síti...")
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Vyhledat kamery v síti")
            }
        }

        // Discovered devices
        if (state.discoveredDevices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Nalezené kamery (${state.discoveredDevices.size}):",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            state.discoveredDevices.forEach { device ->
                Card(
                    onClick = { onDeviceSelected(device) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.model.ifEmpty { "EYEPLUS kamera" },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "${device.ip}:${device.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Připojit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Manual connection section
        TextButton(onClick = { showManual = !showManual }) {
            Text(
                if (showManual) "Skrýt ruční připojení" else "Ruční připojení →",
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (showManual) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ruční připojení",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Zadejte IP adresu kamery (zjistíte v routeru nebo aplikaci pro správu sítě)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("IP adresa kamery *") },
                        placeholder = { Text("např. 192.168.1.100") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text("Port") },
                            placeholder = { Text("80") },
                            modifier = Modifier.width(100.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = manualUser,
                            onValueChange = { manualUser = it },
                            label = { Text("Uživatel") },
                            placeholder = { Text("admin") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualPass,
                        onValueChange = { manualPass = it },
                        label = { Text("Heslo") },
                        placeholder = { Text("admin") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Běžné porty: 80 (ONVIF), 554 (RTSP), 8080, 8899. Zkuste admin/123456 pokud admin/admin nefunguje.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            onConnect(manualIp, manualPort.toIntOrNull() ?: 80, manualUser, manualPass)
                        },
                        enabled = manualIp.isNotBlank() && !state.isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Připojování k $manualIp ...")
                        } else {
                            Icon(Icons.Default.Cable, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Připojit")
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Connected Content (video + PTZ + controls)
// ─────────────────────────────────────────────────

@Composable
private fun CameraConnectedContent(
    state: CameraUiState,
    onDisconnect: () -> Unit,
    onPtzMove: (String) -> Unit,
    onPtzStop: () -> Unit,
    onPtzMoveRelative: (Float, Float) -> Unit,
    onToggleRecording: () -> Unit = {},
    onToggleMonitoring: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Connection status bar
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Safe,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Připojeno k ${state.cameraIp}:${state.cameraPort}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (state.deviceInfo.isNotEmpty()) {
                        Text(
                            text = state.deviceInfo["Model"] ?: "EYEPLUS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledTonalButton(onClick = onDisconnect) {
                    Text("Odpojit")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Video player with RTSP stream
        if (state.mainStreamUrl.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                VideoPlayerSurface(
                    rtspUrl = state.mainStreamUrl,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stream info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AssistChip(
                onClick = {},
                label = { Text("Hlavní stream", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(Icons.Default.HighQuality, null, modifier = Modifier.size(14.dp))
                }
            )
            AssistChip(
                onClick = {},
                label = { Text("Sub stream (AI)", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(Icons.Default.Analytics, null, modifier = Modifier.size(14.dp))
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // PTZ Controls Section
        var ptzMode by remember { mutableStateOf(PtzMode.JOYSTICK) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PTZ Ovládání",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            PtzControlModeSelector(
                selectedMode = ptzMode,
                onModeSelected = { ptzMode = it }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (ptzMode) {
                    PtzMode.JOYSTICK -> {
                        PtzJoystick(
                            isMoving = state.isPtzMoving,
                            onMove = { pan, tilt ->
                                onPtzMoveRelative(pan, tilt)
                            },
                            onStop = onPtzStop
                        )
                    }
                    PtzMode.DPAD -> {
                        PtzDPad(
                            onMove = onPtzMove,
                            onStop = onPtzStop
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // AI monitoring status
        if (state.isMonitoring) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI dozor aktivní",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        state.lastAlert?.let { alert ->
                            Text(
                                text = alert,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.monitoringStatus == "Aktivní")
                                    Safe else AlertHigh
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Recording/AI controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Record button
            FilledTonalButton(
                onClick = onToggleRecording,
                modifier = Modifier.weight(1f),
                colors = if (state.isRecording) ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) else ButtonDefaults.filledTonalButtonColors()
            ) {
                Icon(
                    if (state.isRecording) Icons.Default.Stop
                    else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    tint = if (state.isRecording) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (state.isRecording) "Zastavit" else "Nahrávat")
            }

            // AI Monitoring button
            FilledTonalButton(
                onClick = onToggleMonitoring,
                modifier = Modifier.weight(1f),
                colors = if (state.isMonitoring) ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) else ButtonDefaults.filledTonalButtonColors()
            ) {
                Icon(
                    if (state.isMonitoring) Icons.Default.VisibilityOff
                    else Icons.Default.Psychology,
                    contentDescription = null,
                    tint = if (state.isMonitoring)
                        MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (state.isMonitoring) "Vypnout AI" else "AI dozor")
            }
        }
    }
}

/**
 * Composable that wraps the RtspVideoPlayer with state management.
 */
@Composable
private fun VideoPlayerSurface(
    rtspUrl: String,
    modifier: Modifier = Modifier
) {
    var playerState by remember { mutableStateOf(VideoPlayerState()) }

    Box(modifier = modifier) {
        RtspVideoPlayer(
            rtspUrl = rtspUrl,
            modifier = Modifier.fillMaxSize(),
            onStateChanged = { playerState = it }
        )

        // Buffering indicator overlay
        if (playerState.isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Připojování k video streamu...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Error overlay
        playerState.error?.let { error ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Play button overlay when not playing
        if (!playerState.isPlaying && !playerState.isBuffering && playerState.error == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                FilledIconButton(
                    onClick = { /* toggle play */ },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Přehrát",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
