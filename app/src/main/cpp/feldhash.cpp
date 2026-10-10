// JNI bridge to the reference xxHash implementation (XXH64, XXH3-64, XXH3-128), streaming.
// NEON-accelerated on arm64; results are bit-identical to `xxhsum`.
#define XXH_INLINE_ALL
#include "xxhash.h"
#include <jni.h>
#include <cstdint>

namespace {
enum Algo { XXH64_ = 1, XXH3_64_ = 2, XXH3_128_ = 3 };
struct State {
    int algo;
    XXH64_state_t* s64;
    XXH3_state_t* s3;
};

void putBE(uint8_t* out, uint64_t v) { for (int i = 7; i >= 0; i--) { out[i] = (uint8_t)(v & 0xFF); v >>= 8; } }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_app_feldkit_hash_NativeXxh_create(JNIEnv*, jclass, jint algo) {
    State* st = new State{algo, nullptr, nullptr};
    if (algo == XXH64_) { st->s64 = XXH64_createState(); XXH64_reset(st->s64, 0); }
    else {
        st->s3 = XXH3_createState();
        if (algo == XXH3_64_) XXH3_64bits_reset(st->s3); else XXH3_128bits_reset(st->s3);
    }
    return (jlong)(intptr_t)st;
}

JNIEXPORT void JNICALL Java_app_feldkit_hash_NativeXxh_update(JNIEnv* env, jclass, jlong h, jbyteArray data, jint off, jint len) {
    State* st = (State*)(intptr_t)h;
    jbyte* p = (jbyte*)env->GetPrimitiveArrayCritical(data, nullptr);
    if (!p) return;
    if (st->algo == XXH64_) XXH64_update(st->s64, p + off, (size_t)len);
    else if (st->algo == XXH3_64_) XXH3_64bits_update(st->s3, p + off, (size_t)len);
    else XXH3_128bits_update(st->s3, p + off, (size_t)len);
    env->ReleasePrimitiveArrayCritical(data, p, JNI_ABORT);
}

JNIEXPORT jbyteArray JNICALL Java_app_feldkit_hash_NativeXxh_digest(JNIEnv* env, jclass, jlong h) {
    State* st = (State*)(intptr_t)h;
    uint8_t buf[16];
    jsize n;
    if (st->algo == XXH3_128_) {
        XXH128_hash_t r = XXH3_128bits_digest(st->s3);
        putBE(buf, r.high64); putBE(buf + 8, r.low64); n = 16;       // canonical form: high word first
    } else {
        uint64_t r = st->algo == XXH64_ ? XXH64_digest(st->s64) : XXH3_64bits_digest(st->s3);
        putBE(buf, r); n = 8;
    }
    jbyteArray out = env->NewByteArray(n);
    env->SetByteArrayRegion(out, 0, n, (const jbyte*)buf);
    return out;
}

JNIEXPORT void JNICALL Java_app_feldkit_hash_NativeXxh_free(JNIEnv*, jclass, jlong h) {
    State* st = (State*)(intptr_t)h;
    if (!st) return;
    if (st->s64) XXH64_freeState(st->s64);
    if (st->s3) XXH3_freeState(st->s3);
    delete st;
}

}
