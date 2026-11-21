// JNI bridge to Cactus's C API, for Needle (tool calling on the phone).
// The runtime holds one process-wide model, so Kotlin serialises calls.
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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

// Text crosses JNI as standard UTF-8 bytes, not jstring: JNI's string calls
// speak Modified UTF-8, which mangles emoji on the way in and aborts on the
// engine's standard UTF-8 (or a cut-off character) on the way out.
std::string utf8(JNIEnv* env, jbyteArray b) {
    if (!b) return "";
    jsize n = env->GetArrayLength(b);
    std::string out((size_t)n, '\0');
    if (n > 0) env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte*>(&out[0]));
    return out;
}

jbyteArray bytes(JNIEnv* env, const char* s, size_t n) {
    jbyteArray out = env->NewByteArray((jsize)n);
    if (out && n > 0) env->SetByteArrayRegion(out, 0, (jsize)n, reinterpret_cast<const jbyte*>(s));
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
Java_dev_gbot_app_local_Needle_nativeInit(JNIEnv* env, jobject, jbyteArray system, jbyteArray tools) {
    return cactus_init(utf8(env, system).c_str(), utf8(env, tools).c_str(), nullptr);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_gbot_app_local_Needle_nativeComplete(JNIEnv* env, jobject, jbyteArray input, jint maxTokens) {
    std::vector<char> out(16384, 0);
    int rc = cactus_complete(utf8(env, input).c_str(), nullptr, 0, maxTokens, out.data(), (int)out.size() - 1);
    if (rc < 0) return nullptr;
    return bytes(env, out.data(), strnlen(out.data(), out.size()));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gbot_app_local_Needle_nativeReset(JNIEnv*, jobject) { cactus_reset(); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_gbot_app_local_Needle_nativeLastError(JNIEnv* env, jobject) {
    const char* e = cactus_last_error();
    return bytes(env, e ? e : "", e ? strlen(e) : 0);
}
