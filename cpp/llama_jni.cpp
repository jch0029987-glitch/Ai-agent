#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_jeremy_ai_agent_LlamaBridge_nativeInitModel(JNIEnv* env, jobject thiz, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing llama backend and loading checkpoint: %s", path);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 99; // Offload fully to Vulkan GPU backend

    // Updated to modern non-deprecated API
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("CRITICAL: Failed to load GGUF model from path.");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 512;

    // Updated to modern non-deprecated API
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("CRITICAL: Failed to create llama context.");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Success: Model & Vulkan-accelerated context active.");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_jeremy_ai_agent_LlamaBridge_nativeReleaseModel(JNIEnv* env, jobject thiz) {
    LOGI("Releasing model and context resources.");
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_jeremy_ai_agent_LlamaBridge_nativeGenerate(JNIEnv* env, jobject thiz, jstring prompt) {
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("Error: Engine offline or uninitialized.");
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Executing diagnostic test generation for prompt: %s", prompt_str);

    // Fetch vocabulary from the model for modern tokenization functions
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("Test Failed: Failed to retrieve model vocabulary.");
    }

    // 1. Tokenization using modern vocabulary-based API
    bool add_bos = true;
    int n_tokens_required = -llama_tokenize(vocab, prompt_str, strlen(prompt_str), nullptr, 0, add_bos, true);
    
    if (n_tokens_required <= 0) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("Test Failed: Tokenizer length calculation returned 0 or negative.");
    }

    std::vector<llama_token> tokens(n_tokens_required);
    int actual_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), tokens.data(), tokens.size(), add_bos, true);
    
    if (actual_tokens < 0) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("Test Failed: Tokenization process failed.");
    }
    tokens.resize(actual_tokens);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    LOGI("Tokenization successful. Generated %d tokens.", actual_tokens);

    // 2. Modern batch construction and population
    llama_batch batch = llama_batch_init(512, 0, 1);
    batch.n_tokens = tokens.size();
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == tokens.size() - 1); // Request logits only for the last token
    }

    // Clear KV cache using modern memory manager API if needed, or skip if handled by context free
    // Note: If kv cache clear is needed across turns, use standard context reset patterns.

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        LOGE("Test Failed: llama_decode execution returned non-zero error code.");
        return env->NewStringUTF("Test Failed: Inference decode step failed on GPU/CPU.");
    }

    std::string result = "[Test Passed: Tokenized " + std::to_string(actual_tokens) + 
                         " tokens, executed Vulkan batch decode successfully.]";

    llama_batch_free(batch);
    LOGI("Diagnostic run completed successfully.");
    return env->NewStringUTF(result.c_str());
}

}
