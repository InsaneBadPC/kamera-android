package com.eyeplus.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eyeplus.data.ai.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the AI chat interface.
 *
 * Manages conversation with selected AI provider, including:
 * - Text message history
 * - Streaming responses
 * - Provider switching
 * - System event contextualization
 */
class ChatViewModel : ViewModel() {

    data class ChatUiState(
        val messages: List<ChatMessage> = emptyList(),
        val isWaitingForResponse: Boolean = false,
        val isStreaming: Boolean = false,
        val currentStreamingText: String = "",
        val selectedProvider: ProviderType = ProviderType.GEMINI,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentProvider: AiProvider? = null

    // Track whether the welcome message has been shown
    private var welcomeShown = false

    /**
     * Initialize with API keys from settings.
     * Creates the appropriate provider based on [selectedProvider].
     */
    fun initialize(
        selectedProvider: ProviderType,
        geminiKey: String,
        groqKey: String,
        openrouterKey: String
    ) {
        if (currentProvider != null && _uiState.value.selectedProvider == selectedProvider) return

        _uiState.update { it.copy(selectedProvider = selectedProvider) }

        currentProvider = when (selectedProvider) {
            ProviderType.GEMINI -> GeminiAnalyzer(apiKey = geminiKey)
            ProviderType.GROQ -> GroqProvider(apiKey = groqKey)
            ProviderType.OPENROUTER -> OpenRouterProvider(apiKey = openrouterKey)
        }

        if (!welcomeShown) {
            welcomeShown = true
            addSystemMessage("Dobrý den! Jsem EyePlus AI asistent " +
                "(${selectedProvider.displayName}). " +
                "Můžete se mnou komunikovat o dění ve vašem monitorovaném prostoru. " +
                "Co potřebujete vědět?")
        }
    }

    /**
     * Switch to a different AI provider.
     */
    fun switchProvider(
        provider: ProviderType,
        geminiKey: String,
        groqKey: String,
        openrouterKey: String
    ) {
        if (provider == _uiState.value.selectedProvider) return

        // Clear old provider
        currentProvider = null

        // Reset chat
        _uiState.update { ChatUiState(selectedProvider = provider) }
        welcomeShown = false

        // Initialize new provider
        initialize(provider, geminiKey, groqKey, openrouterKey)
    }

    /**
     * Send a chat message and get AI response.
     */
    fun sendMessage(text: String) {
        val provider = currentProvider ?: return
        if (text.isBlank() || _uiState.value.isWaitingForResponse) return

        // Add user message immediately
        val userMessage = ChatMessage(role = "user", text = text)
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isWaitingForResponse = true,
                isStreaming = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                // Use streaming for better UX
                provider.chatStream(text).collect { response ->
                    if (response.isStreaming) {
                        _uiState.update { state ->
                            val msgs = state.messages.toMutableList()
                            if (msgs.lastOrNull()?.isStreaming == true) {
                                msgs[msgs.lastIndex] = response
                            } else {
                                msgs.add(response)
                            }
                            state.copy(
                                messages = msgs,
                                isStreaming = true,
                                currentStreamingText = response.text
                            )
                        }
                    } else {
                        _uiState.update { state ->
                            val msgs = state.messages.toMutableList()
                            if (msgs.lastOrNull()?.isStreaming == true) {
                                msgs[msgs.lastIndex] = response
                            } else {
                                msgs.add(response)
                            }
                            state.copy(
                                messages = msgs,
                                isWaitingForResponse = false,
                                isStreaming = false,
                                currentStreamingText = ""
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isWaitingForResponse = false,
                        isStreaming = false,
                        currentStreamingText = "",
                        error = "Chyba komunikace: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Add a system/event message to the conversation context.
     */
    fun addSystemMessage(text: String) {
        val msg = ChatMessage(role = "system", text = text)
        _uiState.update { state ->
            state.copy(messages = state.messages + msg)
        }
    }

    /**
     * Add an AI event notification (e.g., person detected).
     */
    fun addEventNotification(text: String) {
        currentProvider?.addSystemMessage(text)
        addSystemMessage("🔔 $text")
    }

    /**
     * Clear the conversation history.
     */
    fun clearChat() {
        currentProvider?.clearHistory()
        _uiState.update {
            ChatUiState(selectedProvider = it.selectedProvider)
        }
        welcomeShown = false
    }

    override fun onCleared() {
        super.onCleared()
    }
}
