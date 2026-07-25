#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>
#include "llama.h"

namespace { std::mutex mutex; llama_model * model = nullptr; }

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeLoad(JNIEnv *env, jclass, jstring path) {
    const char *raw = env->GetStringUTFChars(path, nullptr);
    std::lock_guard<std::mutex> lock(mutex);
    if (model) { llama_model_free(model); model = nullptr; }
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    model = llama_model_load_from_file(raw, params);
    env->ReleaseStringUTFChars(path, raw);
    return model != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    if (model) { llama_model_free(model); model = nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(mutex); return model != nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeGenerate(JNIEnv *env, jclass, jstring prompt, jint maxTokens) {
    const char *raw = env->GetStringUTFChars(prompt, nullptr);
    std::lock_guard<std::mutex> lock(mutex);
    if (!model) { env->ReleaseStringUTFChars(prompt, raw); return nullptr; }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    int nPrompt = -llama_tokenize(vocab, raw, strlen(raw), nullptr, 0, true, true);
    if (nPrompt <= 0 || nPrompt > 2048) { env->ReleaseStringUTFChars(prompt, raw); return nullptr; }
    std::vector<llama_token> tokens(nPrompt);
    if (llama_tokenize(vocab, raw, strlen(raw), tokens.data(), tokens.size(), true, true) < 0) { env->ReleaseStringUTFChars(prompt, raw); return nullptr; }
    env->ReleaseStringUTFChars(prompt, raw);
    llama_context_params cp = llama_context_default_params(); cp.n_ctx = 2048; cp.n_batch = nPrompt; cp.n_threads = 2; cp.n_threads_batch = 2;
    llama_context *ctx = llama_init_from_model(model, cp); if (!ctx) return nullptr;
    llama_sampler_chain_params sp = llama_sampler_chain_default_params(); llama_sampler *sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    std::string output;
    if (llama_decode(ctx, batch) != 0) { llama_sampler_free(sampler); llama_free(ctx); return nullptr; }
    for (int i = 0; i < maxTokens; ++i) {
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[256]; int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true); if (n > 0) output.append(piece, n);
        batch = llama_batch_get_one(&token, 1); if (llama_decode(ctx, batch) != 0) break;
    }
    llama_sampler_free(sampler); llama_free(ctx);
    return env->NewStringUTF(output.c_str());
}
