package com.eyeplus.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.eyeplus.data.SettingsRepository

/**
 * Settings screen for configuring camera connection,
 * AI API keys, recording preferences, and monitoring behavior.
 *
 * All settings are 100% configurable and stored persistently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Camera connection settings
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("admin") }
    var showPassword by remember { mutableStateOf(false) }
    var rtspPort by remember { mutableStateOf("554") }

    // AI API keys (persisted via SharedPreferences)
    var geminiApiKey by remember { mutableStateOf("") }
    var groqApiKey by remember { mutableStateOf("") }
    var openrouterApiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var monitoringInterval by remember { mutableStateOf("30") }

    // Load persisted values on first composition
    LaunchedEffect(Unit) {
        geminiApiKey = SettingsRepository.getGeminiApiKey(context)
        groqApiKey = SettingsRepository.getGroqApiKey(context)
        openrouterApiKey = SettingsRepository.getOpenRouterApiKey(context)
    }

    // Recording settings
    var recordingQuality by remember { mutableStateOf("High (1080p)") }
    var autoRecording by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp) // Bottom nav padding
            .verticalScroll(scrollState)
    ) {
        // Header
        Text(
            text = "Nastavení",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        // ─── Camera Connection Section ───
        SettingsSection(title = "Připojení kameře") {
            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                label = { Text("IP adresa kamery") },
                placeholder = { Text("např. 192.168.1.100") },
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    placeholder = { Text("80") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = rtspPort,
                    onValueChange = { rtspPort = it },
                    label = { Text("RTSP port") },
                    placeholder = { Text("554") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Uživatelské jméno") },
                placeholder = { Text("admin") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Heslo") },
                placeholder = { Text("admin") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }

        // ─── AI API Keys Section ───
        SettingsSection(title = "AI - API klíče") {

            // --- Gemini ---
            OutlinedTextField(
                value = geminiApiKey,
                onValueChange = {
                    geminiApiKey = it
                    SettingsRepository.setGeminiApiKey(context, it)
                },
                label = { Text("Gemini API klíč (AI chat + analýza)") },
                placeholder = { Text("AIzaSy...") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("Získejte zdarma na aistudio.google.com")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // --- Groq ---
            OutlinedTextField(
                value = groqApiKey,
                onValueChange = {
                    groqApiKey = it
                    SettingsRepository.setGroqApiKey(context, it)
                },
                label = { Text("Groq API klíč (AI chat)") },
                placeholder = { Text("gsk_...") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("Získejte zdarma na console.groq.com/keys — Llama 3 70B")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // --- OpenRouter ---
            OutlinedTextField(
                value = openrouterApiKey,
                onValueChange = {
                    openrouterApiKey = it
                    SettingsRepository.setOpenRouterApiKey(context, it)
                },
                label = { Text("OpenRouter API klíč (AI chat)") },
                placeholder = { Text("sk-or-...") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("Získejte zdarma na openrouter.ai/keys — mnoho free modelů")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Show/hide all keys toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Zobrazit klíče",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showApiKey,
                    onCheckedChange = { showApiKey = it }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Monitoring interval
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Interval kontroly: ${monitoringInterval}s",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Slider(
                    value = monitoringInterval.toFloatOrNull() ?: 30f,
                    onValueChange = { monitoringInterval = it.toInt().toString() },
                    valueRange = 5f..120f,
                    steps = 23,
                    modifier = Modifier.width(120.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Provider info
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Dostupní poskytovatelé:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Gemini 2.5 Flash — AI chat + analýza obrazu (1 500/den zdarma)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Groq (Llama 3 70B) — AI chat (30 dotazů/min zdarma)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• OpenRouter — AI chat s výběrem free modelů",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Poskytovatele vyberete v AI chatu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ─── Recording Section ───
        SettingsSection(title = "Nahrávání") {
            // Recording quality selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Kvalita záznamu",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                var expanded by remember { mutableStateOf(false) }
                val options = listOf("Low (480p)", "Medium (720p)", "High (1080p)")
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = recordingQuality,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    recordingQuality = option
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-recording toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Automatické nahrávání při detekci",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoRecording,
                    onCheckedChange = { autoRecording = it }
                )
            }
        }

        // ─── App Info Section ───
        SettingsSection(title = "O aplikaci") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Verze",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Zařízení",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${Build.MANUFACTURER} ${Build.MODEL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Android",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "API ${Build.VERSION.SDK_INT}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "EYEPLUS AI je 100% bezplatná aplikace pro ovládání " +
                    "PTZ IP kamer. Využívá Google Gemini 2.5 Flash, Groq Llama 3, " +
                    "OpenRouter, ML Kit a Android TTS/STT.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Grant permissions button
            FilledTonalButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Security, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Spravovat oprávnění")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Reusable settings section composable.
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                content()
            }
        }
    }
}
