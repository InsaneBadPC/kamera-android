package com.eyeplus.data.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Integration with Google Gemini 2.5 Flash API via direct REST calls.
 * Implements [AiProvider] for chat and frame analysis.
 */
class GeminiAnalyzer(private val apiKey: String) : AiProvider {

    companion object {
        private const val TAG = "GeminiAnalyzer"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL = "gemini-2.5-flash"
        private const val MAX_RETRIES = 3
        private const val MAX_HISTORY_SIZE = 20
        private const val MAX_IMAGE_DIMENSION = 1024

        val SYSTEM_PROMPT = """
You are an AI security camera guard named EyePlus, monitoring a PTZ camera.

Return JSON only:
{
    "people_detected": true/false,
    "person_count": number,
    "familiar_person": true/false,
    "suspicious_activity": true/false,
    "description": "brief scene description",
    "alert_level": "none"|"low"|"medium"|"high",
    "activity": "what people are doing"
}

For text chat, respond naturally and helpfully based on your surveillance history.
""".trimIndent()

        private val FRAME_ANALYSIS_PROMPT = """
Analyze this surveillance camera image. Return JSON:
{
    "people_detected": boolean,
    "person_count": number,
    "familiar_person": boolean,
    "suspicious_activity": boolean,
    "description": "what's happening",
    "alert_level": "none|low|medium|high",
    "activity": "what people are doing"
}
""".trimIndent()
    }

    override val type = ProviderType.GEMINI
    override val supportsVision = true

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val generateUrl = "$BASE_URL/$MODEL:generateContent?key=$apiKey"
    private val streamUrl = "$BASE_URL/$MODEL:streamGenerateContent?alt=sse&key=$apiKey"

    private val messageHistory = mutableListOf<ChatMessage>()

    // ─── AiProvider: Chat ────────────────────────

    override suspend fun chat(message: String): ChatMessage {
        return withContext(Dispatchers.IO) {
            try {
                messageHistory.add(ChatMessage(role = "user", text = message))
                val requestBody = buildChatRequest()
                val response = executeRequest(generateUrl, requestBody)
                val replyText = extractTextResponse(response)

                val modelMessage = ChatMessage(role = "model", text = replyText)
                messageHistory.add(modelMessage)
                trimHistory()
                modelMessage
            } catch (e: Exception) {
                ChatMessage(role = "model", text = "Chyba: ${e.message}")
            }
        }
    }

    override fun chatStream(message: String): Flow<ChatMessage> = flow {
        try {
            messageHistory.add(ChatMessage(role = "user", text = message))
            val requestBody = buildChatRequest()
            val fullResponse = StringBuilder()

            val request = Request.Builder()
                .url(streamUrl)
                .post(requestBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
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
            messageHistory.add(ChatMessage(role = "model", text = finalText))
            emit(ChatMessage(role = "model", text = finalText, isStreaming = false))
        } catch (e: Exception) {
            emit(ChatMessage(role = "model", text = "Chyba: ${e.message}", isStreaming = false))
        }
    }.flowOn(Dispatchers.IO)

    // ─── AiProvider: Frame analysis ──────────────

    override suspend fun analyzeFrame(bitmap: Bitmap): FrameAnalysis {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 1..MAX_RETRIES) {
                try {
                    val scaled = downscaleBitmap(bitmap, MAX_IMAGE_DIMENSION)
                    val base64Image = bitmapToBase64(scaled)

                    val requestBody = buildGenerateRequest(base64Image, FRAME_ANALYSIS_PROMPT)
                    val response = executeRequest(generateUrl, requestBody)
                    val result = parseFrameAnalysis(response)
                    if (result.error != null && attempt < MAX_RETRIES) continue
                    return@withContext result

                } catch (e: Exception) {
                    when {
                        e.message?.contains("429") == true -> {
                            val waitMs = 1000L * (1 shl attempt)
                            delay(waitMs)
                            lastError = e
                        }
                        e.message?.contains("403") == true ->
                            return@withContext FrameAnalysis(error = "Chyba autentizace - zkontrolujte API klíč")
                        else -> { lastError = e; delay(1000L) }
                    }
                }
            }
            FrameAnalysis(error = "Selhalo po $MAX_RETRIES pokusech: ${lastError?.message}")
        }
    }

    // ─── History Management ──────────────────────

    override fun getHistory(): List<ChatMessage> = messageHistory.toList()
    override fun clearHistory() { messageHistory.clear() }

    override fun addSystemMessage(text: String) {
        messageHistory.add(ChatMessage(role = "system", text = "[Událost] $text"))
    }

    // ─── JSON Builders ────────────────────────────

    private fun buildGenerateRequest(base64Image: String, prompt: String): String {
        return buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", JsonPrimitive(SYSTEM_PROMPT)) }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", JsonPrimitive("image/jpeg"))
                                put("data", JsonPrimitive(base64Image))
                            }
                        }
                        addJsonObject { put("text", JsonPrimitive(prompt)) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", JsonPrimitive(0.2))
                put("maxOutputTokens", JsonPrimitive(500))
            }
        }.toString()
    }

    private fun buildChatRequest(): String {
        val history = messageHistory.takeLast(MAX_HISTORY_SIZE * 2)
        return buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", JsonPrimitive(SYSTEM_PROMPT)) }
                }
            }
            putJsonArray("contents") {
                history.forEach { msg ->
                    addJsonObject {
                        put("role", JsonPrimitive(
                            when (msg.role) { "model" -> "model"; else -> "user" }
                        ))
                        putJsonArray("parts") {
                            addJsonObject { put("text", JsonPrimitive(msg.text)) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", JsonPrimitive(0.7))
                put("maxOutputTokens", JsonPrimitive(1000))
            }
        }.toString()
    }

    // ─── Response Parsers ─────────────────────────

    private fun parseFrameAnalysis(jsonStr: String): FrameAnalysis {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return FrameAnalysis()
            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return FrameAnalysis()
            val text = parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return FrameAnalysis()
            json.decodeFromString<FrameAnalysis>(cleanJson(text))
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
            FrameAnalysis(description = jsonStr.take(200), error = "Chyba parsování")
        }
    }

    private fun extractTextResponse(jsonStr: String): String {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
                ?: ""
        } catch (_: Exception) { "" }
    }

    private fun extractTextFromChunk(jsonStr: String): String {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        return root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
            ?: ""
    }

    // ─── HTTP Helpers ─────────────────────────────

    private suspend fun executeRequest(url: String, body: String): String {
        val request = Request.Builder().url(url).post(body.toRequestBody(jsonMediaType)).build()
        return client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: "{}"
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(200)}")
            bodyStr
        }
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (ratio >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun cleanJson(text: String): String = text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()

    private fun trimHistory() {
        while (messageHistory.size > MAX_HISTORY_SIZE * 2) {
            messageHistory.removeFirstOrNull()
        }
    }
}
