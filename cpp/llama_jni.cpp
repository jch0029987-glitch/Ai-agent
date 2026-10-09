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

static bool recreate_context() {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (!g_model) return false;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;                
    ctx_params.n_batch = 512;               
    ctx_params.n_threads = 4;               
    ctx_params.n_threads_batch = 4;         
    
    ctx_params.type_k = GGML_TYPE_Q8_0;      
    ctx_params.type_v = GGML_TYPE_Q8_0;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    return g_ctx != nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_jeremy_ai_agent_LlamaBridge_nativeInitModel(JNIEnv* env, jobject thiz, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing llama backend (Pure CPU Mode): %s", path);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; 

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("CRITICAL: Failed to load GGUF model from path.");
        return JNI_FALSE;
    }

    if (!recreate_context()) {
        LOGE("CRITICAL: Failed to create initial llama context.");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Success: CPU-accelerated context active.");
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
Java_com_jeremy_ai_agent_LlamaBridge_nativeGenerateWithGrammar(JNIEnv* env, jobject thiz, jstring prompt, jstring grammar) {
    if (!g_model) {
        return env->NewStringUTF("Error: Engine offline or uninitialized.");
    }

    if (!recreate_context()) {
        return env->NewStringUTF("Error: Failed to reset llama context state.");
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    const char* grammar_str = env->GetStringUTFChars(grammar, nullptr);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        env->ReleaseStringUTFChars(grammar, grammar_str);
        return env->NewStringUTF("Error: Failed to retrieve model vocabulary.");
    }

    bool add_bos = true;
    int prompt_len = strlen(prompt_str);
    int n_tokens_required = -llama_tokenize(vocab, prompt_str, prompt_len, nullptr, 0, add_bos, true);
    
    if (n_tokens_required <= 0) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        env->ReleaseStringUTFChars(grammar, grammar_str);
        return env->NewStringUTF("Error: Tokenizer length calculation failed.");
    }

    std::vector<llama_token> tokens(n_tokens_required);
    int actual_tokens = llama_tokenize(vocab, prompt_str, prompt_len, tokens.data(), tokens.size(), add_bos, true);
    
    if (actual_tokens < 0) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        env->ReleaseStringUTFChars(grammar, grammar_str);
        return env->NewStringUTF("Error: Tokenization process failed.");
    }
    tokens.resize(actual_tokens);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    
    llama_sampler_chain_add(smpl, llama_sampler_init_grammar(vocab, grammar_str, "root"));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    env->ReleaseStringUTFChars(grammar, grammar_str);

    // SAFE BATCH INIT: Use llama.cpp built-in allocation to prevent layout crashes
    int num_tokens = static_cast<int>(tokens.size());
    llama_batch batch = llama_batch_init(num_tokens, 0, 1);
    batch.n_tokens = num_tokens;

    for (int i = 0; i < num_tokens; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == num_tokens - 1) ? 1 : 0;
    }

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Inference Error: llama_decode failed on prompt evaluation.");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        return env->NewStringUTF("Error: Prompt evaluation decode failed.");
    }

    std::string generated_text;
    generated_text.reserve(64);
    
    int max_tokens_to_generate = 48; 
    int cur_pos = num_tokens;

    for (int i = 0; i < max_tokens_to_generate; i++) {
        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char buf[128];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            generated_text.append(buf, n);
        }

        // Reuse the batch structure safely for single-token generation steps
        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = static_cast<llama_pos>(cur_pos);
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        cur_pos++;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Inference Error: failed to decode generated token step.");
            break;
        }
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);
    LOGI("Grammar generation complete. Output length: %zu chars.", generated_text.length());
    return env->NewStringUTF(generated_text.c_str());
}

}
