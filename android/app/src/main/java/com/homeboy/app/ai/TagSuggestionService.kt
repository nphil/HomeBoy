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
        val raw = LlmEngineManager.generate(buildPrompt(name, description, existingTags)) ?: run {
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

    private fun buildPrompt(name: String, description: String, existingTags: List<HBTag>): String {
        val tagList = existingTags.take(40).joinToString(", ") { it.name }
        return buildString {
            append("You label home-inventory items with short tags. ")
            append("Reply with ONLY a comma-separated list of 3 to 5 short tags and nothing else.\n")
            if (tagList.isNotBlank()) {
                append("Reuse these existing tags whenever one fits — only invent a new tag when none of them match: ")
                append(tagList).append(".\n")
            }
            append("Item name: ").append(name).append('\n')
            if (description.isNotBlank()) append("Description: ").append(description).append('\n')
            append("Tags:")
        }
    }

    /** Pull a clean tag list out of the model's free-form reply. */
    private fun parse(raw: String): List<String> {
        val body = if (raw.contains("Tags:")) raw.substringAfterLast("Tags:") else raw
        val firstLine = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?: return emptyList()
        return firstLine.split(',', ';')
            .map { it.trim().trim('-', '*', '.', '"', '\'', '#', '`', ' ') }
            .filter { it.length in 2..30 && it.split(' ').size <= 3 }
            .distinctBy { it.lowercase() }
            .take(6)
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
