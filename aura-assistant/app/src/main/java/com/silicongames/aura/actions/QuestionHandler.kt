package com.silicongames.aura.actions

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.silicongames.aura.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles QUESTION intents by sending the query to Claude API
 * and returning a concise answer.
 */
class QuestionHandler(private val context: Context) {

    companion object {
        private const val TAG = "Question"
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Answer a question using the Claude API.
     * Returns a concise, overlay-friendly answer.
     */
    suspend fun answer(query: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = getApiKey()
                if (apiKey.isNullOrBlank()) {
                    DebugLog.log(TAG, "No API key set — cannot answer questions")
                    return@withContext "Set your Anthropic API key in Aura settings to enable question answering."
                }

                DebugLog.log(TAG, "Sending question to Claude API: \"$query\"")

                val systemPrompt = """You are Aura, a helpful assistant that answers questions concisely.
The user's question was detected from ambient conversation, so keep your answer:
- Brief (2-3 sentences max)
- Direct and factual
- Easy to read at a glance on a small overlay
Do NOT use markdown formatting. Plain text only."""

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", query)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", CLAUDE_MODEL)
                    put("max_tokens", 150)
                    put("system", systemPrompt)
                    put("messages", messagesArray)
                }

                DebugLog.log(TAG, "API request sent, waiting for response...")

                val request = Request.Builder()
                    .url(CLAUDE_API_URL)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext "No response received."

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errorJson = JSONObject(body)
                        val errorObj = errorJson.optJSONObject("error")
                        errorObj?.optString("message") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}"
                    }
                    DebugLog.log(TAG, "API ERROR ${response.code}: $errorMsg")

                    return@withContext when (response.code) {
                        401 -> "Invalid API key. Check your key in Aura settings."
                        403 -> "API key doesn't have permission. Check your Anthropic account."
                        429 -> "Rate limited — too many requests. Try again in a moment."
                        500, 502, 503 -> "Claude API is temporarily down. Try again shortly."
                        else -> "API error ($errorMsg). Check debug log."
                    }
                }

                val json = JSONObject(body)
                val content = json.getJSONArray("content")
                val answerText = content.getJSONObject(0).getString("text").trim()

                DebugLog.log(TAG, "Got answer: \"${answerText.take(80)}...\"")
                answerText

            } catch (e: java.net.UnknownHostException) {
                DebugLog.log(TAG, "No internet connection")
                "No internet connection. Can't reach Claude API."
            } catch (e: java.net.SocketTimeoutException) {
                DebugLog.log(TAG, "Request timed out")
                "Request timed out. Check your internet connection."
            } catch (e: Exception) {
                DebugLog.log(TAG, "ERROR: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Error answering question", e)
                "Sorry, something went wrong: ${e.message}"
            }
        }
    }

    private fun getApiKey(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("api_key", null)
    }
}
