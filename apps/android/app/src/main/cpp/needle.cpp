// JNI bridge to Cactus's C API, for Needle (tool calling on the phone).
// The runtime holds one process-wide model, so Kotlin serialises calls.
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include "cactus.h"

namespace {
// cactus_load may keep pointing at these bytes, so they live as long as the process.
std::vector<unsigned char>* weights = nullptr;

std::string str(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_gbot_app_local_Needle_nativeLoad(JNIEnv* env, jobject, jstring path) {
    std::string p = str(env, path);
    FILE* f = fopen(p.c_str(), "rb");
    if (!f) return -100;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    auto* buf = new std::vector<unsigned char>(n > 0 ? n : 0);
    size_t got = n > 0 ? fread(buf->data(), 1, n, f) : 0;
    fclose(f);
    if (n <= 0 || got != (size_t)n) { delete buf; return -101; }
    int rc = cactus_load(buf->data(), (unsigned long long)n);
    if (rc < 0) { delete buf; return rc; }
    delete weights;
    weights = buf;
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_gbot_app_local_Needle_nativeInit(JNIEnv* env, jobject, jstring system, jstring tools) {
    return cactus_init(str(env, system).c_str(), str(env, tools).c_str(), nullptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gbot_app_local_Needle_nativeComplete(JNIEnv* env, jobject, jstring input, jint maxTokens) {
    std::vector<char> out(16384, 0);
    int rc = cactus_complete(str(env, input).c_str(), nullptr, 0, maxTokens, out.data(), (int)out.size());
    if (rc < 0) return nullptr;
    return env->NewStringUTF(out.data());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gbot_app_local_Needle_nativeReset(JNIEnv*, jobject) { cactus_reset(); }

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gbot_app_local_Needle_nativeLastError(JNIEnv* env, jobject) {
    const char* e = cactus_last_error();
    return env->NewStringUTF(e ? e : "");
}
