package com.silicongames.aura.ai

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.silicongames.aura.DebugLog
import com.silicongames.aura.speech.TranscriptCleaner
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
 * Sends transcribed text to Claude API for intent classification.
 *
 * The classifier determines what type of action the user's speech implies:
 * - QUESTION: "I wonder how many planets there are"
 * - LIST_ADD: "We need more onions"
 * - REMINDER: "Remind me to call mom at 5"
 * - CALENDAR: "Schedule a meeting for Tuesday at 3pm"
 * - SEND_TEXT: "Text John that I'll be late"
 * - WEB_LOOKUP: "Look up the weather forecast"
 * - NONE: Regular conversation with no actionable intent
 */
class IntentClassifier(private val context: Context) {

    companion object {
        private const val TAG = "IntentClassifier"
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        // Haiku is fast + cheap and plenty smart for routing. Update here
        // if you want to use Sonnet/Opus instead.
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ClassifiedIntent(
        val type: IntentType,
        val originalText: String,
        val extractedData: Map<String, String> = emptyMap()
    )

    enum class IntentType {
        QUESTION,       // User is asking or wondering about something
        LIST_ADD,       // User wants to add a tangible item ("we need onions")
        TODO_ADD,       // User wants to add an action item ("we need to call John")
        REMINDER,       // User wants to set a time-based reminder
        CALENDAR,       // User wants to create a calendar event
        SEND_TEXT,      // User wants to send a text message
        WEB_LOOKUP,     // User wants live/current web data
        NONE            // No actionable intent detected
    }

    /**
     * Classify the transcribed text into an intent.
     * Returns null if the text has no actionable intent (NONE).
     */
    suspend fun classify(transcribedText: String): ClassifiedIntent? {
        return withContext(Dispatchers.IO) {
            // Step 0: clean transcript before anything else looks at it.
            val cleaned = TranscriptCleaner.clean(transcribedText)
            if (cleaned.isBlank() || cleaned.length < 3) {
                DebugLog.log("Classifier", "Skipping — transcript too short after cleaning")
                return@withContext null
            }
            if (cleaned != transcribedText) {
                DebugLog.log("Classifier", "Cleaned: \"$transcribedText\" → \"$cleaned\"")
            }

            try {
                val apiKey = getApiKey()
                if (apiKey.isNullOrBlank()) {
                    DebugLog.log("Classifier", "No API key — using local classifier")
                    return@withContext localClassify(cleaned, hasApiKey = false)
                }

                DebugLog.log("Classifier", "Sending to Claude API...")
                val result = cloudClassify(cleaned, apiKey)
                if (result != null) {
                    DebugLog.log("Classifier", "Cloud result: ${result.type}")
                    return@withContext result
                }

                DebugLog.log("Classifier", "Cloud returned NONE, trying local fallback")
                return@withContext localClassify(cleaned, hasApiKey = true)
            } catch (e: Exception) {
                DebugLog.log("Classifier", "Cloud FAILED: ${e.message} — using local")
                Log.e(TAG, "Cloud classification failed, falling back to local", e)
                localClassify(cleaned, hasApiKey = true)
            }
        }
    }

    /**
     * Cloud-based classification using Claude API.
     */
    private fun cloudClassify(text: String, apiKey: String): ClassifiedIntent? {
        val systemPrompt = """You are an intent classifier for a passive listening assistant app called Aura.
Given a transcribed speech snippet, classify it into one of these intents:

- QUESTION: The speaker is asking, wondering, or unsure about something factual.
  Examples: "I wonder how many planets there are", "what's the capital of France",
  "how does mitosis work", "who wrote Hamlet", "is the sky actually blue"
- LIST_ADD: The speaker says they need/want to buy or acquire a tangible NOUN.
  Trigger only when the thing needed is a physical item, not an action.
  Examples: "we need more onions", "don't forget milk", "add eggs to the list",
  "running low on coffee", "out of paper towels"
- TODO_ADD: The speaker says they need/want to DO something — a verb-phrase task,
  not a tangible item, and not tied to a specific time (use REMINDER for that).
  Examples: "we need to call John", "I should email Sarah about the report",
  "got to fix the sink this weekend", "we have to renew the registration"
- REMINDER: The speaker wants to be reminded to do something at a specific time.
  Distinguishing rule: REMINDER needs an explicit time word ("at 5", "tomorrow",
  "in an hour", "tonight"). Without a time, it's TODO_ADD.
  Examples: "remind me to call mom at 5", "don't let me forget the dentist tomorrow"
- CALENDAR: The speaker mentions a specific event with a date or time.
  Examples: "schedule a meeting Tuesday at 3", "dentist appointment next Monday"
- SEND_TEXT: The speaker wants to send a message to a named person.
  Examples: "text John that I'll be late", "tell Sarah I'm on my way"
- WEB_LOOKUP: The speaker wants live/current data (weather, news, prices, scores).
  Examples: "what's the weather tomorrow", "look up Knicks score", "search for ramen near me"
- NONE: Mundane conversation with no actionable intent. Use SPARINGLY.

CRITICAL RULES:
1. Output STRICT JSON only. No prose, no markdown fences, no comments.
2. Every value in "data" MUST be a string. Never an object, array, or null.
3. If unsure between QUESTION and NONE, prefer QUESTION when the snippet
   contains any factual curiosity. Aura's whole point is answering questions.
4. The "query" field for QUESTION should be the cleaned-up question phrased
   naturally (e.g. "how many planets are in the solar system" — not the raw
   filler-laden snippet).

Examples of valid responses:
{"intent":"QUESTION","data":{"query":"how many planets are in the solar system"}}
{"intent":"LIST_ADD","data":{"item":"onions","list":"grocery"}}
{"intent":"TODO_ADD","data":{"task":"call John about the project"}}
{"intent":"REMINDER","data":{"task":"call mom","time":"5:00 PM"}}
{"intent":"CALENDAR","data":{"event":"dentist appointment","date":"Tuesday","time":"3:00 PM"}}
{"intent":"SEND_TEXT","data":{"recipient":"John","message":"I'll be late"}}
{"intent":"WEB_LOOKUP","data":{"query":"weather forecast tomorrow"}}
{"intent":"NONE","data":{}}"""

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Classify this speech: \"$text\"")
            })
        }

        val requestBody = JSONObject().apply {
            put("model", CLAUDE_MODEL)
            put("max_tokens", 200)
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
        val body = response.body?.string() ?: return null

        if (!response.isSuccessful) {
            Log.e(TAG, "API error ${response.code}: $body")
            return null
        }

        return parseClaudeResponse(body, text)
    }

    private fun parseClaudeResponse(responseBody: String, originalText: String): ClassifiedIntent? {
        try {
            val json = JSONObject(responseBody)
            val content = json.optJSONArray("content") ?: run {
                DebugLog.log("Classifier", "Response had no content array: ${responseBody.take(200)}")
                return null
            }
            if (content.length() == 0) return null
            val textContent = content.getJSONObject(0).optString("text", "").trim()
            if (textContent.isEmpty()) return null

            // Extract JSON from response (Claude might wrap it in markdown
            // despite our instructions, or include leading prose).
            val jsonStr = extractJsonObject(textContent) ?: textContent
                .replace("```json", "").replace("```", "")
                .trim()

            val result = JSONObject(jsonStr)
            val intentStr = result.optString("intent", "NONE")
            val intentType = try {
                IntentType.valueOf(intentStr)
            } catch (e: IllegalArgumentException) {
                IntentType.NONE
            }

            if (intentType == IntentType.NONE) return null

            val data = result.optJSONObject("data") ?: JSONObject()
            val extractedData = mutableMapOf<String, String>()
            data.keys().forEach { key ->
                // optString tolerates non-string values (numbers, bools) and
                // returns "" for null. Avoids the JSONException that
                // getString throws when Claude responds with a nested object.
                val value = data.opt(key)?.toString().orEmpty()
                if (value.isNotBlank() && value != "null") {
                    extractedData[key] = value
                }
            }

            return ClassifiedIntent(
                type = intentType,
                originalText = originalText,
                extractedData = extractedData
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Claude response", e)
            DebugLog.log("Classifier", "Parse error: ${e.message}")
            return null
        }
    }

    /**
     * Pull the first {...} JSON object out of a string. Survives Claude
     * occasionally adding prose before/after the JSON.
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Local keyword-based classification as fallback when API is unavailable
     * or returned NONE. When [hasApiKey] is true we're more aggressive about
     * defaulting to QUESTION, since the question handler can actually answer.
     */
    private fun localClassify(text: String, hasApiKey: Boolean = false): ClassifiedIntent? {
        val lower = text.lowercase().trim()

        return when {
            // TODO patterns — check BEFORE LIST_ADD so "we need to call John" isn't
            // misread as "we need [grocery item]". TODO requires a verb after the trigger.
            isTodoIntent(lower) -> {
                ClassifiedIntent(
                    type = IntentType.TODO_ADD,
                    originalText = text,
                    extractedData = mapOf("task" to extractTodoTask(lower))
                )
            }

            // List patterns — check BEFORE questions so "add X" isn't treated as a question
            isListIntent(lower) -> {
                val (item, listName) = extractListItem(lower)
                ClassifiedIntent(
                    type = IntentType.LIST_ADD,
                    originalText = text,
                    extractedData = mapOf("item" to item, "list" to listName)
                )
            }

            // Reminder patterns
            lower.startsWith("remind me") || lower.contains("don't let me forget") ||
            lower.contains("i need to remember") || lower.contains("set a reminder") ||
            lower.contains("reminder for") || lower.contains("remind us") -> {
                ClassifiedIntent(
                    type = IntentType.REMINDER,
                    originalText = text,
                    extractedData = mapOf("task" to text)
                )
            }

            // Calendar patterns
            lower.contains("schedule") || lower.contains("appointment") ||
            lower.contains("meeting at") || lower.contains("meeting on") ||
            lower.contains("book a") || lower.contains("calendar") -> {
                ClassifiedIntent(
                    type = IntentType.CALENDAR,
                    originalText = text,
                    extractedData = mapOf("event" to text)
                )
            }

            // Text message patterns
            lower.startsWith("text ") || lower.startsWith("tell ") ||
            lower.contains("send a message") || lower.contains("send a text") ||
            lower.contains("message to") -> {
                ClassifiedIntent(
                    type = IntentType.SEND_TEXT,
                    originalText = text,
                    extractedData = mapOf("message" to text)
                )
            }

            // Web lookup patterns
            lower.startsWith("look up") || lower.startsWith("search for") ||
            lower.contains("what's the weather") || lower.contains("google") ||
            lower.startsWith("find out") -> {
                ClassifiedIntent(
                    type = IntentType.WEB_LOOKUP,
                    originalText = text,
                    extractedData = mapOf("query" to text)
                )
            }

            // Question patterns — broad catch-all
            TranscriptCleaner.looksLikeQuestion(text) -> {
                ClassifiedIntent(
                    type = IntentType.QUESTION,
                    originalText = text,
                    extractedData = mapOf("query" to text)
                )
            }

            // If we have an API key, be aggressive: anything substantive that
            // isn't clearly a list/reminder/calendar/text becomes a QUESTION.
            // Better to send too much to Claude than to silently ignore the user.
            hasApiKey && text.split(Regex("\\s+")).size >= 3 -> {
                ClassifiedIntent(
                    type = IntentType.QUESTION,
                    originalText = text,
                    extractedData = mapOf("query" to text)
                )
            }

            else -> null // No actionable intent
        }
    }

    /**
     * TODO_ADD detection: "{trigger} to {verb} {rest}" — the "to + verb" is the
     * key signal that distinguishes a task from a tangible item.
     * Conservative: only trigger when we see "{need|have|got|got to|gotta} to {word}".
     */
    private fun isTodoIntent(lower: String): Boolean {
        val triggers = listOf(
            "i need to ", "we need to ",
            "i have to ", "we have to ",
            "i got to ", "we got to ",
            "i gotta ", "we gotta ",
            "i should ", "we should ",
            "i must ", "we must ",
            "don't forget to ", "remember to "
        )
        for (t in triggers) {
            if (lower.startsWith(t) || lower.contains(" $t")) {
                val tail = lower.substringAfter(t)
                // tail must start with a word (the verb). Reject if it looks like
                // a tangible item ("more onions", "some milk").
                if (tail.startsWith("more ") || tail.startsWith("some ") ||
                    tail.startsWith("a ") || tail.startsWith("an ") ||
                    tail.startsWith("the ")) return false
                // Reject if "remind" — that's REMINDER's territory.
                if (tail.startsWith("remind ")) return false
                return tail.isNotBlank()
            }
        }
        return false
    }

    private fun extractTodoTask(lower: String): String {
        return lower
            .replace(Regex("^(i|we) (need|have|got|gotta|should|must) (to )?"), "")
            .replace(Regex("^(don't forget|remember) to "), "")
            .trim()
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * Detect list-related intents with broad pattern matching.
     */
    private fun isListIntent(lower: String): Boolean {
        return lower.startsWith("add ") ||
            lower.startsWith("we need ") || lower.startsWith("i need ") ||
            lower.contains("to the list") || lower.contains("to the grocery") ||
            lower.contains("to the shopping") || lower.contains("to my list") ||
            lower.contains("to our list") ||
            lower.contains("don't forget to buy") || lower.contains("don't forget to get") ||
            lower.contains("need to get ") || lower.contains("need to buy ") ||
            lower.contains("need more ") || lower.contains("running low on") ||
            lower.contains("pick up some") || lower.contains("pick up ") ||
            lower.contains("buy some") || lower.contains("get some") ||
            lower.contains("out of ") || lower.contains("ran out of") ||
            lower.contains("put on the list") || lower.contains("put on list") ||
            lower.contains("grocery list") || lower.contains("shopping list")
    }

    /**
     * Extract the item name and list name from a list-intent phrase.
     */
    private fun extractListItem(lower: String): Pair<String, String> {
        // Detect which list (default to grocery)
        val listName = when {
            lower.contains("shopping list") -> "shopping"
            lower.contains("todo list") || lower.contains("to do list") || lower.contains("to-do") -> "todo"
            lower.contains("grocery list") || lower.contains("grocery") -> "grocery"
            else -> "grocery"
        }

        // Extract the item by removing common framing phrases
        val item = lower
            .replace(Regex("(add|put)\\s+"), "")
            .replace(Regex("to (the |my |our )?(grocery |shopping |todo |to-do )?list"), "")
            .replace(Regex("^(we |i )?(need|need to|got to|gotta) (get |buy |pick up )?"), "")
            .replace(Regex("don't forget (to )?(buy |get )?"), "")
            .replace(Regex("(running low on|ran out of|out of)\\s*"), "")
            .replace(Regex("(pick up|buy|get) (some |more )?"), "")
            .replace(Regex("^(some |more |a |an |the )"), "")
            .trim()
            .replaceFirstChar { it.uppercase() }

        return Pair(item.ifBlank { lower.replaceFirstChar { it.uppercase() } }, listName)
    }

    private fun getApiKey(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("api_key", null)
    }
}
