package com.silicongames.aura.actions

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
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
 * Handles WEB_LOOKUP intents by querying Claude API with web search capabilities,
 * or falling back to a simple web search.
 */
class WebLookupHandler(private val context: Context) {

    companion object {
        private const val TAG = "WebLookupHandler"
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Look up current information for the given query.
     */
    suspend fun lookup(query: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = getApiKey()
                if (apiKey.isNullOrBlank()) {
                    return@withContext "Set your API key in Aura settings to enable web lookups."
                }

                val cleanQuery = query
                    .replace(Regex("(?i)^(look up |search for |google |find out )"), "")
                    .trim()

                val systemPrompt = """You are Aura, a helpful assistant providing current information.
Answer the following query concisely (2-3 sentences max).
Format for a small phone overlay — no markdown, no bullet points.
If you're unsure about current data, say so briefly."""

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", cleanQuery)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", CLAUDE_MODEL)
                    put("max_tokens", 150)
                    put("system", systemPrompt)
                    put("messages", messagesArray)
                }

                val request = Request.Builder()
                    .url(CLAUDE_API_URL)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext "No result found."

                if (!response.isSuccessful) {
                    Log.e(TAG, "API error: $body")
                    return@withContext "Couldn't look that up right now."
                }

                val json = JSONObject(body)
                val content = json.getJSONArray("content")
                content.getJSONObject(0).getString("text").trim()
            } catch (e: Exception) {
                Log.e(TAG, "Web lookup failed", e)
                "Sorry, I couldn't look that up."
            }
        }
    }

    private fun getApiKey(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("api_key", null)
    }
}
