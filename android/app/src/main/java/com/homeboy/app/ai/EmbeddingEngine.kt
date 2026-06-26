package com.homeboy.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Runs a BERT-family sentence-embedding model (all-MiniLM-L6-v2) under ONNX Runtime.
 *
 * Tries the Qualcomm QNN Execution Provider first (direct Hexagon NPU path on Snapdragon),
 * then falls back to the CPU provider, so it works everywhere and simply runs faster on a
 * Snapdragon 8 Elite. Produces L2-normalized vectors so cosine similarity is a plain dot product.
 *
 * Thread-safety: [embed] is synchronized; create one instance and reuse it.
 */
class EmbeddingEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: WordPieceTokenizer,
    val usingNpu: Boolean
) {
    private val inputNames: Set<String> = session.inputNames

    /** Embed [text] into a unit-length vector. Returns null on any inference failure. */
    @Synchronized
    fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        return runCatching {
            val enc = tokenizer.encode(text)
            val seqLen = enc.inputIds.size
            val shape = longArrayOf(1, seqLen.toLong())

            val tensors = HashMap<String, OnnxTensor>()
            tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), shape)
            tensors["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape)
            if ("token_type_ids" in inputNames) {
                tensors["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.tokenTypeIds), shape)
            }
            // Only pass inputs the graph actually declares.
            val feed = tensors.filterKeys { it in inputNames }

            val vec = try {
                session.run(feed).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val hidden = result[0].value as Array<Array<FloatArray>> // [1][seq][dim]
                    meanPool(hidden[0], enc.attentionMask)
                }
            } finally {
                tensors.values.forEach { runCatching { it.close() } }
            }
            normalize(vec)
            vec
        }.getOrNull()
    }

    fun close() {
        runCatching { session.close() }
    }

    private fun meanPool(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = tokens.firstOrNull()?.size ?: 0
        val sum = FloatArray(dim)
        var count = 0f
        for (i in tokens.indices) {
            if (mask.getOrElse(i) { 1L } == 0L) continue
            val row = tokens[i]
            for (d in 0 until dim) sum[d] += row[d]
            count += 1f
        }
        if (count > 0f) for (d in 0 until dim) sum[d] /= count
        return sum
    }

    private fun normalize(v: FloatArray) {
        var norm = 0.0
        for (x in v) norm += (x * x).toDouble()
        norm = sqrt(norm)
        if (norm > 1e-8) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
    }

    companion object {
        /** Cosine similarity of two L2-normalized vectors (= dot product). */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }

        /**
         * Build an engine from a model file + vocab file. Returns null if either is missing
         * or the session cannot be created at all. Fully fault-tolerant: catches even
         * UnsatisfiedLinkError so a device without the native ORT lib (e.g. a non-arm64
         * emulator) degrades to keyword search instead of crashing.
         *
         * NOTE: the Maven ORT-QNN artifact bundles only libonnxruntime.so — it does NOT
         * include the Qualcomm QNN backend (libQnnHtp.so). Until those backend libraries are
         * added to jniLibs, the QNN provider fails to initialise and we run on CPU, which is
         * perfectly fast for this small embedding model.
         */
        fun create(modelFile: File, vocabFile: File): EmbeddingEngine? = try {
            if (!modelFile.exists() || !vocabFile.exists()) return null
            val tokenizer = WordPieceTokenizer.fromVocabFile(vocabFile) ?: return null
            val env = OrtEnvironment.getEnvironment()

            // 1. Try the QNN (Hexagon NPU) provider; 2. fall back to CPU.
            val qnnSession = tryCreate(env, modelFile, qnn = true)
            if (qnnSession != null) EmbeddingEngine(env, qnnSession, tokenizer, usingNpu = true)
            else tryCreate(env, modelFile, qnn = false)
                ?.let { EmbeddingEngine(env, it, tokenizer, usingNpu = false) }
        } catch (_: Throwable) {
            null
        }

        /**
         * Create a session, optionally registering the QNN EP. The exact Java binding for QNN
         * differs across ORT releases, so we register it reflectively and just fall back to CPU
         * if it isn't available — keeping this code compilable and crash-free on any device.
         */
        private fun tryCreate(env: OrtEnvironment, modelFile: File, qnn: Boolean): OrtSession? = runCatching {
            val opts = OrtSession.SessionOptions()
            if (qnn) {
                val registered = registerQnn(opts)
                if (!registered) { opts.close(); return null }
            }
            env.createSession(modelFile.absolutePath, opts)
        }.getOrNull()

        /** Reflectively add the QNN EP (HTP/NPU backend). Returns false if unsupported. */
        private fun registerQnn(opts: OrtSession.SessionOptions): Boolean {
            val providerOptions = mapOf("backend_path" to "libQnnHtp.so")
            // Preferred: dedicated addQnn(Map) helper present in recent ORT builds.
            val viaAddQnn = runCatching {
                opts.javaClass.getMethod("addQnn", Map::class.java).invoke(opts, providerOptions)
            }.isSuccess
            if (viaAddQnn) return true
            // Generic fallback: addExecutionProvider("QNN", Map).
            return runCatching {
                opts.javaClass.getMethod("addExecutionProvider", String::class.java, Map::class.java)
                    .invoke(opts, "QNN", providerOptions)
            }.isSuccess
        }
    }
}
