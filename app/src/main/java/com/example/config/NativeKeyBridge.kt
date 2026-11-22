package com.example.config

import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Reconstructs the 256-bit AES key material for .myvpn config encryption.
 *
 * When built with Android NDK + CMake (standard Android Studio build), the seed
 * is assembled inside libamkt_crypto.so (app/src/main/cpp/amkt_crypto.cpp).
 * When the native shared library is absent (e.g., JVM unit tests or non-NDK
 * cloud preview builds), an equivalent masked byte reconstruction runs so
 * .myvpn files remain 100% compatible across all builds without ever storing a
 * plaintext key string in Kotlin/Java code.
 */
object NativeKeyBridge {
  val isNativeLibraryLoaded: Boolean

  init {
    isNativeLibraryLoaded =
      try {
        System.loadLibrary("amkt_crypto")
        true
      } catch (_: UnsatisfiedLinkError) {
        false
      } catch (_: SecurityException) {
        false
      }
  }

  private external fun nativeAssembleSeed(): ByteArray

  fun deriveAes256Key(): SecretKey {
    val seed = if (isNativeLibraryLoaded) nativeAssembleSeed() else reconstructFallbackSeed()
    val digest = MessageDigest.getInstance("SHA-256")
    // Domain separation prefix (non-string byte constants)
    digest.update(byteArrayOf(0x41, 0x4D, 0x4B, 0x54, 0x2D, 0x56, 0x31, 0x00))
    digest.update(seed)
    val keyBytes = digest.digest()
    seed.fill(0)
    return SecretKeySpec(keyBytes, "AES")
  }

  private fun reconstructFallbackSeed(): ByteArray {
    val maskA =
      intArrayOf(
        0x5A, 0xC3, 0x19, 0xE4, 0x72, 0x8B, 0x3D, 0xF0,
        0x46, 0xA5, 0x2E, 0x91, 0x6C, 0xD7, 0x08, 0xB3,
      )
    val tableA =
      intArrayOf(
        0x1B, 0x8E, 0x52, 0xB0, 0x31, 0xDC, 0x74, 0x98,
        0x03, 0xEB, 0x67, 0xF5, 0x21, 0x9B, 0x42, 0xFA,
      )
    val maskB =
      intArrayOf(
        0x33, 0x7C, 0xA9, 0x15, 0xD2, 0x48, 0xE6, 0x61,
        0x9B, 0x24, 0xF7, 0x50, 0x8E, 0x1A, 0xC5, 0x3F,
      )
    val tableB =
      intArrayOf(
        0x64, 0x09, 0xEE, 0x47, 0x99, 0x3C, 0xAD, 0x28,
        0xDA, 0x6D, 0x82, 0x11, 0xC0, 0x5F, 0x88, 0x74,
      )

    val seed = ByteArray(32)
    for (i in 0 until 16) {
      val raw = (tableA[i] xor maskA[i]) and 0xFF
      seed[i] = rotl8(raw, (i % 7) + 1).toByte()
    }
    for (i in 0 until 16) {
      val raw = (tableB[i] xor maskB[i]) and 0xFF
      val rotated = rotl8(raw, ((i + 3) % 7) + 1)
      seed[16 + i] = ((rotated xor ((i * 17) and 0xFF)) and 0xFF).toByte()
    }
    return seed
  }

  /**
   * Reconstructs the built-in default Remote Server List URL at runtime from split masked byte
   * tables + bit-rotation so no plaintext URL string exists in Kotlin/Java source or DEX strings.
   */
  fun resolveDefaultRemoteServerListUrl(): String {
    val maskA =
      intArrayOf(
        0x5D, 0x88, 0xB3, 0xDE, 0x09, 0x34, 0x5F, 0x8A, 0xB5, 0xE0,
        0x0B, 0x36, 0x61, 0x8C, 0xB7, 0xE2, 0x0D, 0x38, 0x63, 0x8E,
        0xB9, 0xE4, 0x0F, 0x3A, 0x65, 0x90, 0xBB, 0xE6, 0x11,
      )
    val tableA =
      intArrayOf(
        0x69, 0x95, 0x3D, 0xD9, 0x92, 0xDC, 0x01, 0x1D, 0x2E, 0xE7,
        0xED, 0x47, 0xD4, 0x4E, 0x8E, 0x7E, 0x21, 0x7F, 0x48, 0x5F,
        0x69, 0x5E, 0x84, 0xD4, 0x33, 0x83, 0x03, 0x34, 0x23,
      )
    val maskB =
      intArrayOf(
        0x37, 0x6C, 0xA1, 0xD6, 0x0B, 0x40, 0x75, 0xAA, 0xDF, 0x14,
        0x49, 0x7E, 0xB3, 0xE8, 0x1D, 0x52, 0x87, 0xBC, 0xF1, 0x26,
        0x5B, 0x90, 0xC5, 0xFA, 0x2F, 0x64, 0x99, 0xCE, 0x03, 0x38,
      )
    val tableB =
      intArrayOf(
        0xC5, 0x4F, 0xAC, 0x60, 0x19, 0x4C, 0xD6, 0x44, 0x62, 0x3F,
        0xEE, 0x23, 0x97, 0xB3, 0x2B, 0xC9, 0xEE, 0xF0, 0x6B, 0x61,
        0x39, 0x4F, 0xB9, 0xE1, 0x74, 0xB0, 0xBF, 0x42, 0x65, 0x9A,
      )

    val half = tableA.size
    val rem = tableB.size
    val out = ByteArray(half + rem)
    for (i in 0 until half) {
      val raw = (tableA[i] xor maskA[i]) and 0xFF
      out[i] = rotl8(raw, (i % 7) + 1).toByte()
    }
    for (i in 0 until rem) {
      val raw = (tableB[i] xor maskB[i]) and 0xFF
      val rotated = rotl8(raw, ((i + 3) % 7) + 1)
      out[half + i] = ((rotated xor ((i * 19) and 0xFF)) and 0xFF).toByte()
    }
    return String(out, Charsets.UTF_8)
  }

  private fun rotl8(value: Int, shift: Int): Int {
    val v = value and 0xFF
    return ((v shl shift) or (v ushr (8 - shift))) and 0xFF
  }
}
