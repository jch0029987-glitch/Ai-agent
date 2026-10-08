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
    LOGI("Initializing llama backend for Pixel 9 (Pure CPU Mode): %s", path);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; 

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("CRITICAL: Failed to load GGUF model from path.");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;                
    ctx_params.n_batch = 512;               
    ctx_params.n_threads = 4;               
    ctx_params.n_threads_batch = 4;         
    
    ctx_params.type_k = GGML_TYPE_F16;      
    ctx_params.type_v = GGML_TYPE_F16;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("CRITICAL: Failed to create llama context.");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Success: Pixel 9 pure CPU-accelerated context active.");
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
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("Error: Engine offline or uninitialized.");
    }

    // CRITICAL FIX: Clear KV cache/state so sequential prompt turns start fresh at position 0
    llama_kv_cache_clear(g_ctx);

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    const char* grammar_str = env->GetStringUTFChars(grammar, nullptr);
    LOGI("Executing grammar-constrained generation.");

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        env->ReleaseStringUTFChars(grammar, grammar_str);
        return env->NewStringUTF("Error: Failed to retrieve model vocabulary.");
    }

    bool add_bos = true;
    int n_tokens_required = -llama_tokenize(vocab, prompt_str, strlen(prompt_str), nullptr, 0, add_bos, true);
    
    if (n_tokens_required <= 0) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        env->ReleaseStringUTFChars(grammar, grammar_str);
        return env->NewStringUTF("Error: Tokenizer length calculation failed.");
    }

    std::vector<llama_token> tokens(n_tokens_required);
    int actual_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), tokens.data(), tokens.size(), add_bos, true);
    
    if (actual_tokens < 0) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        env->ReleaseStringUTFChars(grammar, grammar_str);
        return env->NewStringUTF("Error: Tokenization process failed.");
    }
    tokens.resize(actual_tokens);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Initialize sampler chain using modern sampler API
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    
    // Pass vocab pointer, grammar string, and root rule directly
    llama_sampler_chain_add(smpl, llama_sampler_init_grammar(vocab, grammar_str, "root"));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    env->ReleaseStringUTFChars(grammar, grammar_str);

    llama_batch batch = {
        static_cast<int32_t>(tokens.size()),
        tokens.data(),
        nullptr,
        nullptr,
        nullptr,
        nullptr,
        nullptr
    };

    std::vector<llama_pos> pos(tokens.size());
    std::vector<int32_t> n_seq_id(tokens.size(), 1);
    std::vector<llama_seq_id*> seq_id(tokens.size());
    std::vector<llama_seq_id> single_seq_id = {0};
    std::vector<int8_t> logits(tokens.size(), 0);

    for (size_t i = 0; i < tokens.size(); i++) {
        pos[i] = static_cast<llama_pos>(i);
        seq_id[i] = single_seq_id.data();
        if (i == tokens.size() - 1) {
            logits[i] = 1;
        }
    }

    batch.pos = pos.data();
    batch.n_seq_id = n_seq_id.data();
    batch.seq_id = seq_id.data();
    batch.logits = logits.data();

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Inference Error: llama_decode failed on prompt evaluation.");
        llama_sampler_free(smpl);
        return env->NewStringUTF("Error: Prompt evaluation decode failed.");
    }

    std::string generated_text = "";
    int max_tokens_to_generate = 32; 
    int cur_pos = tokens.size();

    for (int i = 0; i < max_tokens_to_generate; i++) {
        llama_token new_token_id = llama_sampler_sample(smpl, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            generated_text.append(buf, n);
        }

        batch.n_tokens = 1;
        batch.token = &new_token_id;
        
        llama_pos p = cur_pos;
        batch.pos = &p;
        
        int32_t ns = 1;
        batch.n_seq_id = &ns;
        
        llama_seq_id* sid_ptr = single_seq_id.data();
        batch.seq_id = &sid_ptr;
        
        int8_t log = 1;
        batch.logits = &log;

        cur_pos++;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Inference Error: failed to decode generated token step.");
            break;
        }
    }

    llama_sampler_free(smpl);
    LOGI("Grammar generation complete. Output length: %zu chars.", generated_text.length());
    return env->NewStringUTF(generated_text.c_str());
}

}
