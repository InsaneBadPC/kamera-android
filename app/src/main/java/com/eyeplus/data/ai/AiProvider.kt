package com.eyeplus.data.ai

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * Available AI provider types for chat.
 */
enum class ProviderType(
    val displayName: String,
    val defaultModel: String,
    val supportsVision: Boolean = false
) {
    GEMINI("Gemini 2.5 Flash", "gemini-2.5-flash", supportsVision = true),
    GROQ("Groq (Llama 3)", "llama-3.3-70b-versatile"),
    OPENROUTER("OpenRouter", "meta-llama/llama-3.2-3b-instruct:free")
}

/**
 * Abstract AI provider for chat conversations.
 *
 * Implementations communicate with different LLM APIs
 * (Gemini, Groq, OpenRouter, etc.) through their native REST formats.
 */
interface AiProvider {

    /** Unique provider type identifier. */
    val type: ProviderType

    /** Human-readable display name. */
    val displayName: String
        get() = type.displayName

    /** Whether this provider can analyze images. */
    val supportsVision: Boolean
        get() = type.supportsVision

    // ─── Chat ─────────────────────────────────────

    /** Send a text message and get a non-streaming response. */
    suspend fun chat(message: String): ChatMessage

    /** Send a text message and get a streaming response. */
    fun chatStream(message: String): Flow<ChatMessage>

    /** Analyze a camera frame (only if [supportsVision] is true). */
    suspend fun analyzeFrame(bitmap: Bitmap): FrameAnalysis?

    // ─── History ──────────────────────────────────

    fun getHistory(): List<ChatMessage>
    fun clearHistory()

    /** Add a system / event message to the conversation history. */
    fun addSystemMessage(text: String)
}
