package com.homeboy.app.ai

import java.io.File
import java.text.Normalizer

/**
 * Minimal BERT WordPiece tokenizer — enough to feed all-MiniLM-L6-v2 (and any other
 * uncased BERT-family embedding model) running under ONNX Runtime.
 *
 * Loads a standard `vocab.txt` (one token per line; the line index is the token id).
 * Produces `[CLS] … [SEP]` id sequences with an attention mask. No external deps.
 */
class WordPieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val unkId: Int,
    private val clsId: Int,
    private val sepId: Int,
    private val padId: Int,
    private val maxInputChars: Int = 200
) {
    data class Encoding(val inputIds: LongArray, val attentionMask: LongArray, val tokenTypeIds: LongArray)

    /** Encode [text] to model inputs, truncating to [maxLen] total tokens (incl. CLS/SEP). */
    fun encode(text: String, maxLen: Int = 128): Encoding {
        val pieces = ArrayList<Int>(maxLen)
        pieces += clsId
        outer@ for (token in basicTokenize(text)) {
            for (id in wordPiece(token)) {
                if (pieces.size >= maxLen - 1) break@outer
                pieces += id
            }
        }
        pieces += sepId

        val ids = LongArray(pieces.size) { pieces[it].toLong() }
        val mask = LongArray(pieces.size) { 1L }
        val types = LongArray(pieces.size) { 0L }
        return Encoding(ids, mask, types)
    }

    /** Lowercase, strip accents, split on whitespace, then peel punctuation into its own tokens. */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .filter { it.category != CharCategory.NON_SPACING_MARK }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        for (ch in cleaned) {
            when {
                ch.isWhitespace() -> flush()
                isPunctuation(ch) -> { flush(); out += ch.toString() }
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    /** Greedy longest-match-first WordPiece, using `##` for continuation pieces. */
    private fun wordPiece(token: String): List<Int> {
        if (token.length > maxInputChars) return listOf(unkId)
        val out = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var curId: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + token.substring(start, end)
                val id = vocab[sub]
                if (id != null) { curId = id; break }
                end--
            }
            if (curId == null) return listOf(unkId) // whole token is unknown
            out += curId
            start = end
        }
        return out
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (ch.category) {
            CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    companion object {
        /** Build from a `vocab.txt` file (line index = token id). Null if unreadable/empty. */
        fun fromVocabFile(file: File): WordPieceTokenizer? {
            if (!file.exists()) return null
            val vocab = HashMap<String, Int>()
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { i, line -> vocab[line.trim()] = i }
                }
            }.getOrElse { return null }
            if (vocab.isEmpty()) return null
            return WordPieceTokenizer(
                vocab = vocab,
                unkId = vocab["[UNK]"] ?: 100,
                clsId = vocab["[CLS]"] ?: 101,
                sepId = vocab["[SEP]"] ?: 102,
                padId = vocab["[PAD]"] ?: 0
            )
        }
    }
}
