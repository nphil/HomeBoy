// JNI bridge: com.homeboy.llmkit.LlmKit  <->  llama.cpp (pinned submodule tag b9827).
//
// One GGUF model serves both generation (tag suggestions) and embeddings (semantic search).
// llama.cpp owns tokenization, sampling, the KV-cache and ggml backend selection; this file is
// the marshalling layer plus L2-normalization for embeddings.
//
// Backend (NPU/GPU/CPU) tier reporting here is a heuristic based on which ggml devices the runtime
// exposes plus the requested n_gpu_layers — good enough for the UI badge. Precise per-tensor buffer
// inspection can replace it later without touching the Kotlin surface.

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "llmkit"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Tier indices must match com.homeboy.llmkit.Backend.fromNative().
static constexpr int TIER_NPU = 0;
static constexpr int TIER_GPU = 1;
static constexpr int TIER_CPU = 2;

namespace {

struct Engine {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    bool           embeddings = false;
    int            tier = TIER_CPU;
};

inline Engine *as_engine(jlong handle) {
    return reinterpret_cast<Engine *>(handle);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Lowercased device name, for cheap substring classification.
std::string dev_name_lower(ggml_backend_dev_t dev) {
    const char *n = ggml_backend_dev_name(dev);
    std::string s = n ? n : "";
    for (char &c : s) c = static_cast<char>(::tolower(static_cast<unsigned char>(c)));
    return s;
}

bool name_is_npu(const std::string &n) {
    return n.find("hexagon") != std::string::npos || n.find("htp") != std::string::npos ||
           n.find("npu") != std::string::npos;
}

bool name_is_gpu(const std::string &n) {
    return n.find("opencl") != std::string::npos || n.find("adreno") != std::string::npos ||
           n.find("gpu") != std::string::npos;
}

// Classify which tier most likely engaged, from the devices the runtime sees + the load request.
int detect_tier(int n_gpu_layers, int backend_hint) {
    if (n_gpu_layers <= 0) return TIER_CPU;

    bool has_npu = false, has_gpu = false;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const std::string n = dev_name_lower(dev);
        if (name_is_npu(n)) has_npu = true;
        else if (name_is_gpu(n)) has_gpu = true;
    }

    // Honor an explicit NPU/GPU hint when that device exists; otherwise prefer NPU > GPU > CPU.
    if (backend_hint == TIER_NPU && has_npu) return TIER_NPU;
    if (backend_hint == TIER_GPU && has_gpu) return TIER_GPU;
    if (has_npu) return TIER_NPU;
    if (has_gpu) return TIER_GPU;
    return TIER_CPU;
}

// Tokenize with a grow-on-overflow retry (llama_tokenize returns -needed when the buffer is short).
std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_bos) {
    int n_max = static_cast<int>(text.size()) + 16;
    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           tokens.data(), n_max, add_bos, /*parse_special=*/true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           tokens.data(), -n, add_bos, /*parse_special=*/true);
    }
    if (n < 0) n = 0;
    tokens.resize(n);
    return tokens;
}

std::string token_to_piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), /*lstrip=*/0, /*special=*/false);
    if (n < 0) return {};
    return std::string(buf, n);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeSetLibraryPath(JNIEnv *env, jobject, jstring jdir) {
    const std::string dir = jstr(env, jdir);
    // FastRPC loads the Hexagon HTP skel (libggml-htp-v79.so) from ADSP_LIBRARY_PATH; the ggml
    // backend libs are dlopen'd from LD_LIBRARY_PATH. On Android both live in nativeLibraryDir.
    setenv("ADSP_LIBRARY_PATH", dir.c_str(), 1);
    setenv("LD_LIBRARY_PATH", dir.c_str(), 1);
    LOGI("library path set to %s", dir.c_str());
}

JNIEXPORT void JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeInit(JNIEnv *, jobject) {
    ggml_backend_load_all();
    llama_backend_init();
    LOGI("llama backend initialized; %zu ggml device(s)", ggml_backend_dev_count());
}

JNIEXPORT jlong JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeLoadModel(JNIEnv *env, jobject, jstring jpath,
                                               jint n_gpu_layers, jint backend_hint,
                                               jboolean embeddings) {
    const std::string path = jstr(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    // Pin the offload target to the requested tier instead of letting llama.cpp auto-pick when
    // several non-CPU backends (Hexagon + OpenCL) are both registered. Embeddings want NPU,
    // generation wants CPU. devices[] is a NULL-terminated array consumed during load.
    std::vector<ggml_backend_dev_t> devices;
    int resolved_tier = TIER_CPU;
    if (n_gpu_layers > 0 && (backend_hint == TIER_NPU || backend_hint == TIER_GPU)) {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            const std::string n = dev_name_lower(dev);
            if (backend_hint == TIER_NPU && name_is_npu(n)) { devices.push_back(dev); resolved_tier = TIER_NPU; }
            else if (backend_hint == TIER_GPU && name_is_gpu(n)) { devices.push_back(dev); resolved_tier = TIER_GPU; }
        }
        if (!devices.empty()) {
            devices.push_back(nullptr);
            mparams.devices = devices.data();
        }
    }

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = 4096;
    cparams.n_batch = 512;
    if (embeddings) {
        cparams.embeddings   = true;
        cparams.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    }

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create context for: %s", path.c_str());
        llama_model_free(model);
        return 0;
    }

    auto *eng = new Engine();
    eng->model      = model;
    eng->ctx        = ctx;
    eng->embeddings = embeddings;
    // If we pinned an explicit device, report that tier; otherwise fall back to the heuristic.
    eng->tier       = mparams.devices ? resolved_tier : detect_tier(n_gpu_layers, backend_hint);
    LOGI("loaded %s (embeddings=%d, tier=%d)", path.c_str(), embeddings ? 1 : 0, eng->tier);
    return reinterpret_cast<jlong>(eng);
}

JNIEXPORT jint JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeEngagedBackend(JNIEnv *, jobject, jlong handle) {
    Engine *eng = as_engine(handle);
    return eng ? eng->tier : TIER_CPU;
}

JNIEXPORT jstring JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeGenerateChat(JNIEnv *env, jobject, jlong handle,
                                                  jstring jsystem, jstring juser,
                                                  jint max_tokens, jfloat temperature, jint top_k) {
    Engine *eng = as_engine(handle);
    if (eng == nullptr || eng->ctx == nullptr) return env->NewStringUTF("");

    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    const std::string sys_str = jstr(env, jsystem);
    const std::string usr_str = jstr(env, juser);

    // Build the chat messages. Pointers into local std::strings are valid for this call's scope.
    std::vector<llama_chat_message> msgs;
    if (!sys_str.empty()) msgs.push_back({"system", sys_str.c_str()});
    msgs.push_back({"user", usr_str.c_str()});

    // Apply the model's built-in chat template (embedded in GGUF metadata). The pinned llama.cpp
    // takes the template *string* (not the model) — fetch it from the model, then apply. add_ass=true
    // appends the assistant turn prefix so the model generates a completion rather than echoing.
    const char *tmpl = llama_model_chat_template(eng->model, /*name=*/nullptr);
    std::string formatted(8192, '\0');
    int32_t n = tmpl ? llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                                 /*add_ass=*/true,
                                                 formatted.data(), (int32_t)formatted.size())
                     : -1;
    if (n < 0) {
        // No chat template in this GGUF (or apply failed) — fall back to raw concatenation.
        formatted = (sys_str.empty() ? "" : sys_str + "\n") + usr_str;
        LOGI("no chat template found, using raw concatenation");
    } else if (n > (int32_t)formatted.size()) {
        formatted.assign(static_cast<size_t>(n + 1), '\0');
        llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                  true, formatted.data(), n + 1);
        formatted.resize(n);
    } else {
        formatted.resize(n);
    }
    LOGI("chat: %zu tokens after template", formatted.size());

    // From here identical to nativeGenerate, but add_bos=false — the template already emitted it.
    llama_memory_clear(llama_get_memory(eng->ctx), true);

    std::vector<llama_token> tokens = tokenize(vocab, formatted, /*add_bos=*/false);
    if (tokens.empty()) return env->NewStringUTF("");

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int>(tokens.size()));
    if (llama_decode(eng->ctx, batch) != 0) {
        LOGE("decode failed on chat prompt");
        return env->NewStringUTF("");
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature > 0.f ? temperature : 0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    llama_token tok = 0;
    for (int i = 0; i < max_tokens; ++i) {
        tok = llama_sampler_sample(smpl, eng->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        out += token_to_piece(vocab, tok);
        llama_batch step = llama_batch_get_one(&tok, 1);
        if (llama_decode(eng->ctx, step) != 0) break;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring jprompt,
                                              jint max_tokens, jfloat temperature, jint top_k) {
    Engine *eng = as_engine(handle);
    if (eng == nullptr || eng->ctx == nullptr) return env->NewStringUTF("");

    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    const std::string prompt = jstr(env, jprompt);

    // Fresh sequence each call.
    llama_memory_clear(llama_get_memory(eng->ctx), true);

    std::vector<llama_token> tokens = tokenize(vocab, prompt, /*add_bos=*/true);
    if (tokens.empty()) return env->NewStringUTF("");

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int>(tokens.size()));
    if (llama_decode(eng->ctx, batch) != 0) {
        LOGE("decode failed on prompt");
        return env->NewStringUTF("");
    }

    // Sampler chain: top-k -> temperature -> distribution sampling.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature > 0.f ? temperature : 0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    llama_token tok = 0;
    for (int i = 0; i < max_tokens; ++i) {
        tok = llama_sampler_sample(smpl, eng->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        out += token_to_piece(vocab, tok);
        llama_batch step = llama_batch_get_one(&tok, 1);
        if (llama_decode(eng->ctx, step) != 0) break;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jfloatArray JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeEmbed(JNIEnv *env, jobject, jlong handle, jstring jtext) {
    Engine *eng = as_engine(handle);
    if (eng == nullptr || eng->ctx == nullptr) return env->NewFloatArray(0);

    const llama_vocab *vocab = llama_model_get_vocab(eng->model);
    const int n_embd = llama_model_n_embd(eng->model);
    const std::string text = jstr(env, jtext);

    llama_memory_clear(llama_get_memory(eng->ctx), true);

    std::vector<llama_token> tokens = tokenize(vocab, text, /*add_bos=*/true);
    if (tokens.empty()) return env->NewFloatArray(0);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int>(tokens.size()));
    if (llama_decode(eng->ctx, batch) != 0) {
        LOGE("decode failed on embed input");
        return env->NewFloatArray(0);
    }

    const float *emb = llama_get_embeddings_seq(eng->ctx, 0);
    if (emb == nullptr) {
        // Pooling disabled / non-pooling model: fall back to the last-token embeddings.
        emb = llama_get_embeddings(eng->ctx);
    }
    if (emb == nullptr) return env->NewFloatArray(0);

    // L2-normalize so the host can use plain dot-product as cosine similarity.
    double norm = 0.0;
    for (int i = 0; i < n_embd; ++i) norm += static_cast<double>(emb[i]) * emb[i];
    norm = std::sqrt(norm);
    const float inv = norm > 0.0 ? static_cast<float>(1.0 / norm) : 0.f;

    std::vector<float> vec(n_embd);
    for (int i = 0; i < n_embd; ++i) vec[i] = emb[i] * inv;

    jfloatArray result = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(result, 0, n_embd, vec.data());
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeProbeBackends(JNIEnv *env, jobject) {
    const size_t count = ggml_backend_dev_count();
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), strClass, nullptr);
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);
        std::string entry = std::string(name ? name : "?") + " — " + (desc ? desc : "");
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), env->NewStringUTF(entry.c_str()));
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_homeboy_llmkit_LlmKit_nativeFree(JNIEnv *, jobject, jlong handle) {
    Engine *eng = as_engine(handle);
    if (eng == nullptr) return;
    if (eng->ctx) llama_free(eng->ctx);
    if (eng->model) llama_model_free(eng->model);
    delete eng;
}

} // extern "C"
