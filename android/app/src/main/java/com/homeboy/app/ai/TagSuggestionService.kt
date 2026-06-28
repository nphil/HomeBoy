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
        // Reconcile the model's suggestions against the library HERE, not in the prompt: a
        // suggestion that maps to an existing tag is shown as already-existing (no "+"), the rest
        // are offered as new tags to create. Matching is plural/case-insensitive so a generated
        // "Spices" still takes precedence over an existing "Spice".
        val existingBySingular = existingTags.associateBy { singular(it.name) }
        val matched = LinkedHashSet<HBTag>()
        val novel = LinkedHashSet<String>()
        for (n in names) {
            val tag = existingBySingular[singular(n)]
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
     * Design note: the model's ONE job is to generate the best tags for the item itself. We
     * deliberately do NOT ask it to pick from the existing-tag list — small instruct models
     * (Qwen3 1.7B) read "reuse an existing tag whenever one fits" as "classify into this list" and
     * will force-fit unrelated tags (tagging "black pepper" as "Pesticides") instead of inventing
     * the obvious "Spices"/"Cooking". Existing-vs-novel is reconciled in code afterwards (see
     * [suggest]); the library is passed here only as a soft spelling hint so a generated tag aligns
     * to an existing one's wording. A worked example anchors the output format, and `/no_think`
     * suppresses Qwen3 chain-of-thought.
     */
    private fun buildMessages(
        name: String,
        description: String,
        existingTags: List<HBTag>
    ): Pair<String, String> {
        val tagList = existingTags.take(40).joinToString(", ") { it.name }
        val system = buildString {
            append("/no_think\n")
            append("You suggest organizing tags for an item in a home inventory. ")
            append("Reply with ONLY a comma-separated list of 4 to 6 short tags (1-2 words each) and nothing else. ")
            append("Pick tags that genuinely describe the item — its kind, category, use, or where it is kept. ")
            append("Always invent tags that fit the item; never force an unrelated tag just to reuse one. ")
            append("Example — \"cordless drill\": Tools, Power Tools, Garage, Hardware, DIY.")
            if (tagList.isNotBlank()) {
                append("\nIf a tag you choose means the same as one already in the library, copy its exact spelling: ")
                append(tagList).append('.')
            }
        }
        val user = buildString {
            append("Item: ").append(name)
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

    /**
     * Fold a tag name to a match key: lowercase, trimmed, collapsed spaces, with a simple plural
     * suffix removed so "Spices" matches an existing "Spice" and "Batteries" matches "Battery".
     * Sibilant "-es" plurals (boxes, glasses) aren't folded — rare among inventory tags, and the
     * worst case is just offering a near-duplicate as a new tag rather than a wrong match.
     */
    private fun singular(s: String): String {
        val n = s.lowercase().trim().replace(Regex("\\s+"), " ")
        return when {
            n.endsWith("ies") && n.length > 4 -> n.dropLast(3) + "y"
            n.endsWith("ss") -> n
            n.endsWith("s") && n.length > 3 -> n.dropLast(1)
            else -> n
        }
    }
}
