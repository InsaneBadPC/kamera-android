package com.eyeplus.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eyeplus.data.SettingsRepository
import com.eyeplus.data.ai.ChatMessage
import com.eyeplus.data.ai.ProviderType
import kotlinx.coroutines.launch

/**
 * AI Chat screen - text conversation with the AI guard.
 *
 * Features:
 * - Provider selection (Gemini / Groq / OpenRouter)
 * - Message history with role-based styling
 * - Streaming response display
 * - Input field with send button
 * - Clear chat option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Read persisted settings
    val geminiKey = remember { SettingsRepository.getGeminiApiKey(context) }
    val groqKey = remember { SettingsRepository.getGroqApiKey(context) }
    val openrouterKey = remember { SettingsRepository.getOpenRouterApiKey(context) }
    val savedProvider = remember { SettingsRepository.getSelectedProvider(context) }

    // Initialize on first composition with persisted settings
    LaunchedEffect(Unit) {
        viewModel.initialize(
            selectedProvider = savedProvider,
            geminiKey = geminiKey,
            groqKey = groqKey,
            openrouterKey = openrouterKey
        )
    }

    // Provider dropdown state
    var providerExpanded by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size, uiState.currentStreamingText) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp) // Bottom nav padding
    ) {
        // Header with provider selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AI Chat",
                style = MaterialTheme.typography.titleLarge
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Provider selector dropdown
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = !providerExpanded }
                ) {
                    AssistChip(
                        onClick = { providerExpanded = true },
                        label = {
                            Text(
                                text = uiState.selectedProvider.displayName,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                        },
                        modifier = Modifier.menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        ProviderType.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(provider.displayName)
                                        Text(
                                            text = provider.defaultModel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    providerExpanded = false
                                    if (provider != uiState.selectedProvider) {
                                        viewModel.switchProvider(
                                            provider = provider,
                                            geminiKey = geminiKey,
                                            groqKey = groqKey,
                                            openrouterKey = openrouterKey
                                        )
                                        SettingsRepository.setSelectedProvider(context, provider)
                                    }
                                },
                                leadingIcon = {
                                    if (provider == uiState.selectedProvider) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // Clear chat button
                if (uiState.messages.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Smazat konverzaci"
                        )
                    }
                }
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Začněte konverzaci s AI asistentem.\n" +
                                "Zeptejte se na dění v monitorovaném prostoru.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            items(
                items = uiState.messages,
                key = { "${it.role}_${it.timestamp}" }
            ) { message ->
                ChatBubble(message = message)
            }

            // Streaming indicator
            if (uiState.isStreaming && uiState.currentStreamingText.isNotEmpty()) {
                item {
                    ChatBubble(
                        message = ChatMessage(
                            role = "model",
                            text = uiState.currentStreamingText,
                            isStreaming = true
                        )
                    )
                }
            }

            // Typing indicator
            if (uiState.isStreaming && uiState.currentStreamingText.isEmpty()) {
                item {
                    TypingIndicator()
                }
            }
        }

        // Error display
        uiState.error?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Input field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Napište zprávu...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank() && !uiState.isWaitingForResponse) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    }
                ),
                singleLine = true,
                enabled = !uiState.isWaitingForResponse
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = {
                    if (inputText.isNotBlank() && !uiState.isWaitingForResponse) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !uiState.isWaitingForResponse,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Odeslat"
                )
            }
        }
    }
}

/**
 * Individual chat message bubble with role-based styling.
 */
@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = when {
        isSystem -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        isSystem -> MaterialTheme.colorScheme.onTertiaryContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Role label
        if (!isUser && !isSystem) {
            Text(
                text = "EyePlus AI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        // Message bubble
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = backgroundColor,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )

                // Streaming indicator
                if (message.isStreaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "●",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}

/**
 * Animated typing indicator (three bouncing dots).
 */
@Composable
fun TypingIndicator() {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "EyePlus AI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "⏳ Přemýšlím...",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic
            )
        }
    }
}
