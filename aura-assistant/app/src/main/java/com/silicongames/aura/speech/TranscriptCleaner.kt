package com.silicongames.aura.speech

/**
 * Strips wake-word leftovers, filler words, and false starts from a raw
 * speech-to-text transcript so the downstream classifier and answer prompts
 * see something close to what the user actually meant.
 *
 * Conservative on purpose — only removes things that are almost certainly
 * noise. We never want to delete a real word from a question.
 */
object TranscriptCleaner {

    // Common wake-word/preamble noise we may have caught on the front
    private val LEADING_NOISE = listOf(
        "hey aura", "hi aura", "ok aura", "okay aura", "yo aura", "aura",
        "hey there", "hey google", "hey siri", "alexa", "computer"
    )

    // Filler words that add no semantic content. Removed only when they
    // appear as standalone tokens, not when embedded in another word.
    private val FILLERS = setOf(
        "um", "uh", "uhh", "umm", "er", "erm", "ah",
        "like", "basically", "literally", "honestly",
        "you know", "i mean", "kind of", "sort of"
    )

    fun clean(raw: String): String {
        if (raw.isBlank()) return raw

        var text = raw.trim()

        // Strip a leading wake-word phrase if present
        val lower = text.lowercase()
        for (prefix in LEADING_NOISE) {
            if (lower.startsWith("$prefix ") || lower == prefix) {
                text = text.substring(prefix.length).trim()
                break
            }
            // Also handle wake word followed by punctuation: "Aura, what's..."
            if (lower.startsWith("$prefix,") || lower.startsWith("$prefix.")) {
                text = text.substring(prefix.length + 1).trim()
                break
            }
        }

        // Remove standalone filler words. Multi-word fillers first so we
        // remove "you know" before "know" gets a chance.
        for (filler in FILLERS.sortedByDescending { it.length }) {
            text = text.replace(
                Regex("(?i)\\b${Regex.escape(filler)}\\b"),
                ""
            )
        }

        // Collapse repeated whitespace and stray leading punctuation.
        text = text
            .replace(Regex("\\s{2,}"), " ")
            .replace(Regex("^[,.\\s]+"), "")
            .trim()

        return text.ifBlank { raw.trim() }
    }

    /**
     * Heuristic: does this look like a question or wondering-out-loud,
     * even if it doesn't start with a classic interrogative word?
     * Used as a fallback when nothing else matches.
     */
    fun looksLikeQuestion(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.endsWith("?")) return true
        if (lower.length < 4) return false

        val questionStarts = listOf(
            "what", "who", "where", "when", "why", "how", "which",
            "is", "are", "was", "were", "do", "does", "did", "can",
            "could", "would", "should", "will", "i wonder", "tell me",
            "explain", "name", "list"
        )
        for (start in questionStarts) {
            if (lower.startsWith("$start ") || lower == start) return true
        }
        if (lower.contains("i wonder") ||
            lower.contains("does anyone know") ||
            lower.contains("any idea") ||
            lower.contains("not sure")) return true

        return false
    }
}
