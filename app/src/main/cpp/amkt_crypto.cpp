#include <jni.h>
#include <cstdint>
#include <cstring>

namespace {

// Split masked tables reconstructed at runtime so no plaintext key string
// is stored in either Java/Kotlin bytecode or static C++ string literals.
// Note: Client-side obfuscation prevents casual inspection of passwords/payloads,
// not cryptographic resistance against full reverse engineering.
constexpr uint8_t kMaskA[16] = {
    0x5A, 0xC3, 0x19, 0xE4, 0x72, 0x8B, 0x3D, 0xF0,
    0x46, 0xA5, 0x2E, 0x91, 0x6C, 0xD7, 0x08, 0xB3
};

constexpr uint8_t kTableA[16] = {
    0x1B, 0x8E, 0x52, 0xB0, 0x31, 0xDC, 0x74, 0x98,
    0x03, 0xEB, 0x67, 0xF5, 0x21, 0x9B, 0x42, 0xFA
};

constexpr uint8_t kMaskB[16] = {
    0x33, 0x7C, 0xA9, 0x15, 0xD2, 0x48, 0xE6, 0x61,
    0x9B, 0x24, 0xF7, 0x50, 0x8E, 0x1A, 0xC5, 0x3F
};

constexpr uint8_t kTableB[16] = {
    0x64, 0x09, 0xEE, 0x47, 0x99, 0x3C, 0xAD, 0x28,
    0xDA, 0x6D, 0x82, 0x11, 0xC0, 0x5F, 0x88, 0x74
};

inline uint8_t rotl8(uint8_t value, unsigned int shift) {
    return static_cast<uint8_t>((value << shift) | (value >> (8U - shift)));
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_config_NativeKeyBridge_nativeAssembleSeed(JNIEnv* env, jobject /* thiz */) {
    uint8_t seed[32];

    for (int i = 0; i < 16; ++i) {
        const uint8_t raw = static_cast<uint8_t>(kTableA[i] ^ kMaskA[i]);
        seed[i] = rotl8(raw, static_cast<unsigned int>(i % 7 + 1));
    }

    for (int i = 0; i < 16; ++i) {
        const uint8_t raw = static_cast<uint8_t>(kTableB[i] ^ kMaskB[i]);
        seed[16 + i] = static_cast<uint8_t>(rotl8(raw, static_cast<unsigned int>((i + 3) % 7 + 1)) ^ (i * 17));
    }

    jbyteArray output = env->NewByteArray(32);
    if (output != nullptr) {
        env->SetByteArrayRegion(output, 0, 32, reinterpret_cast<const jbyte*>(seed));
    }

    // Clear stack buffer before returning
    volatile uint8_t* p = seed;
    for (int i = 0; i < 32; ++i) {
        p[i] = 0;
    }

    return output;
}
