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
         * The Qualcomm QNN backend libs (libQnnHtp.so + the Hexagon Vxx skel) ship in the
         * APK via the transitive com.qualcomm.qti:qnn-runtime dependency, so the QNN EP can
         * run the model on the NPU. NOTE: the HTP/NPU backend only accepts QUANTIZED (QDQ)
         * models — for a float model QNN silently runs unsupported ops on CPU. We attempt QNN
         * first and fall back to a pure CPU session.
         *
         * QNN graph compilation is slow on first load, so we enable context-binary caching:
         * the compiled context is written next to the model as `<name>_ctx.onnx` and reused
         * on subsequent launches for near-instant init.
         */
        fun create(modelFile: File, vocabFile: File): EmbeddingEngine? {
            return try {
                if (!modelFile.exists() || !vocabFile.exists()) return null
                val tokenizer = WordPieceTokenizer.fromVocabFile(vocabFile) ?: return null
                val env = OrtEnvironment.getEnvironment()
                val ctxFile = File(modelFile.parentFile, modelFile.nameWithoutExtension + "_ctx.onnx")

                // 1a. Fast path: load a previously compiled QNN context (already on NPU).
                if (ctxFile.exists() && ctxFile.length() > 0) {
                    buildSession(env, ctxFile, qnn = true, genCtxPath = null)
                        ?.let { return EmbeddingEngine(env, it, tokenizer, usingNpu = true) }
                }
                // 1b. Compile on the NPU from source, writing the context for next time.
                buildSession(env, modelFile, qnn = true, genCtxPath = ctxFile.absolutePath)
                    ?.let { return EmbeddingEngine(env, it, tokenizer, usingNpu = true) }
                // 2. CPU fallback.
                buildSession(env, modelFile, qnn = false, genCtxPath = null)
                    ?.let { return EmbeddingEngine(env, it, tokenizer, usingNpu = false) }
                null
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Create a session, optionally registering the QNN EP with HTP performance tuning and
         * (when [genCtxPath] is set) context-binary generation. The exact Java binding for QNN
         * differs across ORT releases, so we register it reflectively and fall back to CPU if
         * it isn't available — keeping this compilable and crash-free on any device.
         */
        private fun buildSession(
            env: OrtEnvironment,
            modelFile: File,
            qnn: Boolean,
            genCtxPath: String?
        ): OrtSession? = runCatching {
            val opts = OrtSession.SessionOptions()
            if (qnn) {
                if (!registerQnn(opts)) { opts.close(); return@runCatching null }
                if (genCtxPath != null) {
                    // Persist the compiled QNN context so later launches skip recompilation.
                    runCatching { opts.addConfigEntry("ep.context_enable", "1") }
                    runCatching { opts.addConfigEntry("ep.context_embed_mode", "1") }
                    runCatching { opts.addConfigEntry("ep.context_file_path", genCtxPath) }
                }
            }
            env.createSession(modelFile.absolutePath, opts)
        }.getOrNull()

        /** Reflectively add the QNN EP (HTP/NPU backend) with flagship-grade perf options. */
        private fun registerQnn(opts: OrtSession.SessionOptions): Boolean {
            val providerOptions = hashMapOf(
                "backend_path" to "libQnnHtp.so",
                "htp_performance_mode" to "burst",
                "htp_graph_finalization_optimization_mode" to "3"
            )
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
