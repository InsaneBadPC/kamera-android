package com.eyeplus.data.ai

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Integration with OpenRouter API (OpenAI-compatible).
 *
 * Provides access to many free and paid models through a single API.
 * Free models: meta-llama/llama-3.2-3b-instruct:free, mistralai/mistral-7b-instruct:free, etc.
 *
 * API docs: https://openrouter.ai/docs/api-reference
 */
class OpenRouterProvider(private val apiKey: String) : AiProvider {

    companion object {
        private const val TAG = "OpenRouterProvider"
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val MODEL = "meta-llama/llama-3.2-3b-instruct:free"
        private const val MAX_RETRIES = 3
        private const val MAX_HISTORY_SIZE = 20

        private val CHAT_SYSTEM_PROMPT = """
You are an AI security camera guard named EyePlus, monitoring a PTZ camera.

For text chat, respond naturally and helpfully based on your surveillance history.
You can discuss what you've observed, answer questions about the monitored area,
and provide security insights. Be concise and factual.
""".trimIndent()
    }

    override val type = ProviderType.OPENROUTER
    override val supportsVision = false

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val messageHistory = mutableListOf<ChatMessage>()

    // ─── AiProvider: Chat ────────────────────────

    override suspend fun chat(message: String): ChatMessage {
        return withContext(Dispatchers.IO) {
            try {
                messageHistory.add(ChatMessage(role = "user", text = message))
                val requestBody = buildChatRequest(stream = false)
                val response = executeRequest(requestBody)
                val replyText = parseChatResponse(response)

                val modelMessage = ChatMessage(role = "model", text = replyText)
                messageHistory.add(modelMessage)
                trimHistory()
                modelMessage
            } catch (e: Exception) {
                ChatMessage(role = "model", text = "Chyba (OpenRouter): ${e.message}")
            }
        }
    }

    override fun chatStream(message: String): Flow<ChatMessage> = flow {
        try {
            messageHistory.add(ChatMessage(role = "user", text = message))
            val requestBody = buildChatRequest(stream = true)
            val fullResponse = StringBuilder()

            val request = Request.Builder()
                .url(BASE_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://github.com/InsaneBadPC/kamera-android")
                .header("X-Title", "EyePlus AI")
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(ChatMessage(role = "model", text = "Chyba (OpenRouter): HTTP ${response.code}", isStreaming = false))
                    return@flow
                }

                val bodyStr = response.body?.string() ?: ""
                bodyStr.lines().forEach { line ->
                    if (line.startsWith("data: ")) {
                        val chunk = line.removePrefix("data: ").trim()
                        if (chunk == "[DONE]") return@forEach
                        try {
                            val text = extractTextFromChunk(chunk)
                            fullResponse.append(text)
                            emit(ChatMessage(role = "model", text = fullResponse.toString(), isStreaming = true))
                        } catch (_: Exception) { }
                    }
                }
            }

            val finalText = fullResponse.toString()
            if (finalText.isNotEmpty()) {
                messageHistory.add(ChatMessage(role = "model", text = finalText))
                emit(ChatMessage(role = "model", text = finalText, isStreaming = false))
            }
        } catch (e: Exception) {
            emit(ChatMessage(role = "model", text = "Chyba (OpenRouter): ${e.message}", isStreaming = false))
        }
    }.flowOn(Dispatchers.IO)

    // ─── Frame analysis ──────────────────────────

    override suspend fun analyzeFrame(bitmap: Bitmap): FrameAnalysis? {
        // OpenRouter has vision models but this provider uses a free text-only model
        return null
    }

    // ─── History Management ──────────────────────

    override fun getHistory(): List<ChatMessage> = messageHistory.toList()
    override fun clearHistory() { messageHistory.clear() }

    override fun addSystemMessage(text: String) {
        messageHistory.add(ChatMessage(role = "system", text = "[Událost] $text"))
    }

    // ─── JSON Builders ────────────────────────────

    private fun buildChatRequest(stream: Boolean): String {
        val history = messageHistory.takeLast(MAX_HISTORY_SIZE * 2)
        return buildJsonObject {
            put("model", JsonPrimitive(MODEL))
            put("stream", JsonPrimitive(stream))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(CHAT_SYSTEM_PROMPT))
                }
                history.forEach { msg ->
                    addJsonObject {
                        put("role", JsonPrimitive(
                            when (msg.role) {
                                "model" -> "assistant"
                                "system" -> "system"
                                else -> "user"
                            }
                        ))
                        put("content", JsonPrimitive(msg.text))
                    }
                }
            }
            put("temperature", JsonPrimitive(0.7))
            put("max_tokens", JsonPrimitive(1000))
        }.toString()
    }

    // ─── Response Parsers ─────────────────────────

    private fun parseChatResponse(jsonStr: String): String {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val choices = root["choices"]?.jsonArray ?: return ""
            val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return ""
            message["content"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
            ""
        }
    }

    private fun extractTextFromChunk(jsonStr: String): String {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val choices = root["choices"]?.jsonArray ?: return ""
            val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return ""
            delta["content"]?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) { "" }
    }

    // ─── HTTP Helpers ─────────────────────────────

    private suspend fun executeRequest(body: String): String {
        val request = Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/InsaneBadPC/kamera-android")
            .header("X-Title", "EyePlus AI")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: "{}"
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(200)}")
            bodyStr
        }
    }

    private fun trimHistory() {
        while (messageHistory.size > MAX_HISTORY_SIZE * 2) {
            messageHistory.removeFirstOrNull()
        }
    }
}
