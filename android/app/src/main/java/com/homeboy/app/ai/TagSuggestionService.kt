package com.homeboy.app.ai

import android.content.Context
import com.homeboy.app.api.HBTag
import java.io.File

/**
 * Turns an item's name + description into tag suggestions using the on-device LLM. Output is
 * split into tags that already exist (so we can select them directly) and novel suggestions the
 * user can create with one tap. Returns null whenever generation is unavailable, so callers
 * simply show nothing.
 */
object TagSuggestionService {

    data class Suggestions(
        val existing: List<HBTag>,
        val novel: List<String>,
        val modelLabel: String,
        val backend: AiBackend
    ) {
        val isEmpty: Boolean get() = existing.isEmpty() && novel.isEmpty()
    }

    suspend fun suggest(
        context: Context,
        modelId: String,
        modelName: String,
        modelFile: File,
        name: String,
        description: String,
        existingTags: List<HBTag>,
        unloadMinutes: Int,
        preferred: AiBackend? = null
    ): Suggestions? {
        if (name.isBlank()) return null
        if (!LlmEngineManager.ensureLoaded(context, modelId, modelFile, preferred)) return null

        val (system, user) = buildMessages(name, description, existingTags)
        val raw = LlmEngineManager.generateChat(system, user) ?: run {
            LlmEngineManager.scheduleUnload(unloadMinutes)
            return null
        }
        LlmEngineManager.scheduleUnload(unloadMinutes)

        val names = parse(raw)
        val existingByLower = existingTags.associateBy { it.name.lowercase() }
        val matched = LinkedHashSet<HBTag>()
        val novel = LinkedHashSet<String>()
        for (n in names) {
            val tag = existingByLower[n.lowercase()]
            // Existing tags keep their stored casing; new tags get title-cased for display.
            if (tag != null) matched.add(tag) else novel.add(titleCase(n))
        }
        val backend = (LlmEngineManager.state.value as? LlmEngineManager.State.Ready)?.backend
            ?: AiBackend.CPU
        return Suggestions(matched.toList(), novel.toList(), modelName, backend)
    }

    /**
     * Returns (systemPrompt, userMessage) for a chat-template-aware model call.
     *
     * The system prompt carries the task definition. Qwen3/3.5 models accept `/no_think` in the
     * system message to suppress chain-of-thought output when thinking mode is on (it is off by
     * default for non-Thinking variants, but the guard costs nothing). The user message contains
     * only the item data so the model answers with just the tags.
     */
    private fun buildMessages(
        name: String,
        description: String,
        existingTags: List<HBTag>
    ): Pair<String, String> {
        val tagList = existingTags.take(40).joinToString(", ") { it.name }
        val system = buildString {
            append("/no_think\n")
            append("You label home-inventory items with short tags. ")
            append("Reply with ONLY a comma-separated list of 3 to 5 short tags and nothing else.")
            if (tagList.isNotBlank()) {
                append("\nReuse these existing tags whenever one fits — only invent a new tag when none of them match: ")
                append(tagList).append('.')
            }
        }
        val user = buildString {
            append("Item name: ").append(name)
            if (description.isNotBlank()) append("\nDescription: ").append(description)
            append("\nTags:")
        }
        return system to user
    }

    /** Pull a clean tag list out of the model's free-form reply. */
    private fun parse(raw: String): List<String> {
        // Strip <think>...</think> blocks produced by reasoning models that ignore /no_think.
        val stripped = stripThinking(raw)
        val body = if (stripped.contains("Tags:")) stripped.substringAfterLast("Tags:") else stripped
        val firstLine = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?: return emptyList()
        return firstLine.split(',', ';')
            .map { it.trim().trim('-', '*', '.', '"', '\'', '#', '`', ' ') }
            .filter { it.length in 2..30 && it.split(' ').size <= 3 }
            .distinctBy { it.lowercase() }
            .take(6)
    }

    private fun stripThinking(raw: String): String {
        var s = raw
        while (true) {
            val start = s.indexOf("<think>")
            if (start < 0) break
            val end = s.indexOf("</think>", start)
            s = if (end < 0) s.substring(0, start)
                else s.substring(0, start) + s.substring(end + 8)
        }
        return s.trim()
    }

    /**
     * Capitalise a model-proposed tag for display, e.g. "home improvement" -> "Home Improvement".
     * Only all-lowercase words are touched, so deliberate casing like "USB" or "iPhone" survives.
     */
    private fun titleCase(s: String): String =
        s.split(' ').joinToString(" ") { word ->
            if (word.isNotEmpty() && word.none { it.isUpperCase() })
                word.replaceFirstChar { it.uppercase() }
            else word
        }
}
