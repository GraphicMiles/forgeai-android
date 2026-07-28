#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ForgeAI", __VA_ARGS__)

namespace {
std::mutex model_mutex;
std::mutex request_mutex;
std::once_flag backend_once;
llama_model * model = nullptr;
std::atomic<bool> cancel_requested{false};
std::string active_request_id;

enum GenerationStatus : int64_t {
    STATUS_COMPLETE = 0,
    STATUS_CANCELLED = 1,
    STATUS_MODEL_NOT_LOADED = 2,
    STATUS_PROMPT_TOO_LONG = 3,
    STATUS_TOKENIZE_FAILED = 4,
    STATUS_CONTEXT_CREATE_FAILED = 5,
    STATUS_PREFILL_FAILED = 6,
    STATUS_DECODE_FAILED = 7,
    STATUS_CALLBACK_FAILED = 8,
};

bool abort_callback(void *) {
    return cancel_requested.load(std::memory_order_relaxed);
}

void clear_request(const std::string & request_id) {
    std::lock_guard<std::mutex> lock(request_mutex);
    if (active_request_id == request_id) active_request_id.clear();
}

bool java_cancelled(JNIEnv * env, jobject runtime, jmethodID method, jstring request_id) {
    if (!method) return false;
    jboolean value = env->CallBooleanMethod(runtime, method, request_id);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return value == JNI_TRUE;
}

size_t complete_utf8_prefix(const std::string & text) {
    size_t index = 0;
    while (index < text.size()) {
        unsigned char first = static_cast<unsigned char>(text[index]);
        size_t length = 1;
        if ((first & 0x80) == 0) length = 1;
        else if ((first & 0xE0) == 0xC0) length = 2;
        else if ((first & 0xF0) == 0xE0) length = 3;
        else if ((first & 0xF8) == 0xF0) length = 4;
        else { index += 1; continue; }
        if (index + length > text.size()) break;
        bool valid = true;
        for (size_t offset = 1; offset < length; ++offset) {
            if ((static_cast<unsigned char>(text[index + offset]) & 0xC0) != 0x80) { valid = false; break; }
        }
        if (!valid) { index += 1; continue; }
        index += length;
    }
    return index;
}

bool emit_token(JNIEnv * env, jobject runtime, jmethodID method, jstring request_id, std::string & pending, bool final_flush) {
    if (!method || pending.empty()) return true;
    size_t count = final_flush ? pending.size() : complete_utf8_prefix(pending);
    if (count == 0) return true;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(count));
    if (!bytes) return false;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte *>(pending.data()));
    env->CallVoidMethod(runtime, method, request_id, bytes);
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    pending.erase(0, count);
    return true;
}

jlongArray result(JNIEnv * env, int64_t status, int64_t output_tokens, int64_t prompt_tokens,
                  int64_t prefill_us, int64_t generation_us, int64_t context_tokens, int64_t threads) {
    jlong values[] = { status, output_tokens, prompt_tokens, prefill_us, generation_us, context_tokens, threads };
    jlongArray output = env->NewLongArray(7);
    if (output) env->SetLongArrayRegion(output, 0, 7, values);
    return output;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    char stack_buffer[256];
    int32_t count = llama_token_to_piece(vocab, token, stack_buffer, sizeof(stack_buffer), 0, true);
    if (count >= 0) return std::string(stack_buffer, static_cast<size_t>(count));
    std::vector<char> dynamic_buffer(static_cast<size_t>(-count));
    count = llama_token_to_piece(vocab, token, dynamic_buffer.data(), static_cast<int32_t>(dynamic_buffer.size()), 0, true);
    return count > 0 ? std::string(dynamic_buffer.data(), static_cast<size_t>(count)) : std::string();
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeLoad(JNIEnv * env, jclass, jstring path) {
    const char * raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(model_mutex);
    if (model) { llama_model_free(model); model = nullptr; }
    std::call_once(backend_once, [] { llama_backend_init(); });
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    model = llama_model_load_from_file(raw, params);
    if (!model) LOGE("llama_model_load_from_file failed for %s", raw);
    env->ReleaseStringUTFChars(path, raw);
    return model != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(model_mutex);
    if (model) { llama_model_free(model); model = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeCancel(JNIEnv * env, jclass, jstring request_id) {
    const char * raw = env->GetStringUTFChars(request_id, nullptr);
    if (!raw) return;
    {
        std::lock_guard<std::mutex> lock(request_mutex);
        if (active_request_id == raw) cancel_requested.store(true, std::memory_order_relaxed);
    }
    env->ReleaseStringUTFChars(request_id, raw);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ai_forgeai_app_OnDeviceRuntime_nativeGenerate(
    JNIEnv * env,
    jclass,
    jobject runtime,
    jbyteArray prompt_utf8,
    jint max_tokens,
    jint context_tokens,
    jint threads,
    jstring request_id
) {
    const char * raw_request = env->GetStringUTFChars(request_id, nullptr);
    if (!raw_request) return result(env, STATUS_CALLBACK_FAILED, 0, 0, 0, 0, context_tokens, threads);
    std::string request(raw_request);
    env->ReleaseStringUTFChars(request_id, raw_request);

    {
        std::lock_guard<std::mutex> lock(request_mutex);
        active_request_id = request;
        cancel_requested.store(false, std::memory_order_relaxed);
    }

    jclass runtime_class = env->GetObjectClass(runtime);
    jmethodID token_method = runtime_class ? env->GetMethodID(runtime_class, "onNativeToken", "(Ljava/lang/String;[B)V") : nullptr;
    jmethodID cancelled_method = runtime_class ? env->GetMethodID(runtime_class, "isCancellationRequested", "(Ljava/lang/String;)Z") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    auto finish = [&](int64_t status, int64_t output_tokens, int64_t prompt_tokens, int64_t prefill_us, int64_t generation_us) {
        clear_request(request);
        if (runtime_class) env->DeleteLocalRef(runtime_class);
        return result(env, status, output_tokens, prompt_tokens, prefill_us, generation_us, context_tokens, threads);
    };

    if (java_cancelled(env, runtime, cancelled_method, request_id)) cancel_requested.store(true, std::memory_order_relaxed);
    if (cancel_requested.load(std::memory_order_relaxed)) return finish(STATUS_CANCELLED, 0, 0, 0, 0);

    if (!prompt_utf8) return finish(STATUS_TOKENIZE_FAILED, 0, 0, 0, 0);
    jsize prompt_bytes = env->GetArrayLength(prompt_utf8);
    std::string raw_prompt(static_cast<size_t>(prompt_bytes), '\0');
    if (prompt_bytes > 0) env->GetByteArrayRegion(prompt_utf8, 0, prompt_bytes, reinterpret_cast<jbyte *>(raw_prompt.data()));

    std::lock_guard<std::mutex> model_lock(model_mutex);
    if (!model) return finish(STATUS_MODEL_NOT_LOADED, 0, 0, 0, 0);

    const llama_vocab * vocab = llama_model_get_vocab(model);
    int32_t prompt_count = -llama_tokenize(vocab, raw_prompt.data(), raw_prompt.size(), nullptr, 0, true, true);
    if (prompt_count <= 0) return finish(STATUS_TOKENIZE_FAILED, 0, 0, 0, 0);
    if (prompt_count + max_tokens > context_tokens) return finish(STATUS_PROMPT_TOO_LONG, 0, prompt_count, 0, 0);

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(prompt_count));
    int32_t tokenized = llama_tokenize(vocab, raw_prompt.data(), raw_prompt.size(), prompt_tokens.data(), prompt_count, true, true);
    if (tokenized < 0 || tokenized != prompt_count) return finish(STATUS_TOKENIZE_FAILED, 0, prompt_count, 0, 0);
    if (java_cancelled(env, runtime, cancelled_method, request_id)) cancel_requested.store(true, std::memory_order_relaxed);
    if (cancel_requested.load(std::memory_order_relaxed)) return finish(STATUS_CANCELLED, 0, prompt_count, 0, 0);

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_tokens);
    context_params.n_batch = static_cast<uint32_t>(std::min(512, context_tokens));
    context_params.n_ubatch = static_cast<uint32_t>(std::min(256, context_tokens));
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.abort_callback = abort_callback;
    context_params.abort_callback_data = nullptr;
    llama_context * context = llama_init_from_model(model, context_params);
    if (!context) return finish(STATUS_CONTEXT_CREATE_FAILED, 0, prompt_count, 0, 0);

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto prefill_start = std::chrono::steady_clock::now();
    int32_t offset = 0;
    while (offset < prompt_count) {
        if (cancel_requested.load(std::memory_order_relaxed)) {
            llama_sampler_free(sampler); llama_free(context);
            auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - prefill_start).count();
            return finish(STATUS_CANCELLED, 0, prompt_count, elapsed, 0);
        }
        int32_t batch_count = std::min(256, prompt_count - offset);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, batch_count);
        int32_t decode_status = llama_decode(context, batch);
        if (decode_status != 0) {
            llama_sampler_free(sampler); llama_free(context);
            auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - prefill_start).count();
            return finish(decode_status == 2 || cancel_requested.load() ? STATUS_CANCELLED : STATUS_PREFILL_FAILED, 0, prompt_count, elapsed, 0);
        }
        offset += batch_count;
    }
    auto prefill_us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - prefill_start).count();

    auto generation_start = std::chrono::steady_clock::now();
    auto last_emit = generation_start;
    int64_t output_count = 0;
    std::string pending;
    int64_t final_status = STATUS_COMPLETE;

    for (int32_t index = 0; index < max_tokens; ++index) {
        if (cancel_requested.load(std::memory_order_relaxed)) { final_status = STATUS_CANCELLED; break; }
        llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        pending += token_piece(vocab, token);
        output_count++;

        auto now = std::chrono::steady_clock::now();
        if (pending.size() >= 1024 || now - last_emit >= std::chrono::milliseconds(50)) {
            if (!emit_token(env, runtime, token_method, request_id, pending, false)) { final_status = STATUS_CALLBACK_FAILED; break; }
            last_emit = now;
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        int32_t decode_status = llama_decode(context, batch);
        if (decode_status != 0) {
            final_status = decode_status == 2 || cancel_requested.load() ? STATUS_CANCELLED : STATUS_DECODE_FAILED;
            break;
        }
    }

    if (!pending.empty() && !emit_token(env, runtime, token_method, request_id, pending, true)) final_status = STATUS_CALLBACK_FAILED;
    auto generation_us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - generation_start).count();
    llama_sampler_free(sampler);
    llama_free(context);
    return finish(final_status, output_count, prompt_count, prefill_us, generation_us);
}
