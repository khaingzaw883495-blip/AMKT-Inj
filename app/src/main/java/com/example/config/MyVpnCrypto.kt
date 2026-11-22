package com.example.config

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption/decryption for .myvpn binary config files.
 *
 * Envelope layout:
 * - Bytes 0..3 : Magic header "AMKT" (0x41 0x4D 0x4B 0x54)
 * - Byte  4    : Format version (0x01)
 * - Bytes 5..16: 12-byte random IV (SecureRandom)
 * - Bytes 17.. : AES-256-GCM ciphertext + 16-byte authentication tag
 */
object MyVpnCrypto {
  private val MAGIC = byteArrayOf(0x41, 0x4D, 0x4B, 0x54) // "AMKT"
  private const val VERSION: Byte = 0x01
  private const val IV_LENGTH = 12
  private const val GCM_TAG_BITS = 128
  private const val HEADER_LENGTH = 4 + 1 + IV_LENGTH
  private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

  private val secureRandom = SecureRandom()

  fun isEncryptedMyVpnEnvelope(bytes: ByteArray): Boolean {
    if (bytes.size <= HEADER_LENGTH + 16) return false
    return bytes[0] == MAGIC[0] &&
      bytes[1] == MAGIC[1] &&
      bytes[2] == MAGIC[2] &&
      bytes[3] == MAGIC[3] &&
      bytes[4] == VERSION
  }

  fun encryptJson(jsonUtf8: String): ByteArray {
    val plaintext = jsonUtf8.toByteArray(Charsets.UTF_8)
    val iv = ByteArray(IV_LENGTH).also { secureRandom.nextBytes(it) }
    val key = NativeKeyBridge.deriveAes256Key()

    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    // Bind header (MAGIC + VERSION) as AAD so tampering is detected immediately
    val aad = byteArrayOf(MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], VERSION)
    cipher.updateAAD(aad)
    val cipherText = cipher.doFinal(plaintext)

    val envelope = ByteArray(HEADER_LENGTH + cipherText.size)
    System.arraycopy(MAGIC, 0, envelope, 0, MAGIC.size)
    envelope[4] = VERSION
    System.arraycopy(iv, 0, envelope, 5, IV_LENGTH)
    System.arraycopy(cipherText, 0, envelope, HEADER_LENGTH, cipherText.size)
    return envelope
  }

  fun decryptToJson(rawInput: ByteArray): String {
    val envelope = unwrapRawOrBase64(rawInput)
    if (!isEncryptedMyVpnEnvelope(envelope)) {
      throw IllegalArgumentException("မမှန်ကန်သော .myvpn ဖိုင်ဖော်မတ်ဖြစ်နေပါသည်")
    }

    val iv = envelope.copyOfRange(5, HEADER_LENGTH)
    val cipherText = envelope.copyOfRange(HEADER_LENGTH, envelope.size)
    val key = NativeKeyBridge.deriveAes256Key()

    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    val aad = byteArrayOf(MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], VERSION)
    cipher.updateAAD(aad)

    val plainBytes =
      try {
        cipher.doFinal(cipherText)
      } catch (_: AEADBadTagException) {
        throw IllegalArgumentException("ဖိုင်ပျက်စီးနေသည် သို့မဟုတ် Key မကိုက်ညီပါ")
      }
    return plainBytes.toString(Charsets.UTF_8)
  }

  private fun unwrapRawOrBase64(rawInput: ByteArray): ByteArray {
    if (isEncryptedMyVpnEnvelope(rawInput)) return rawInput
    val trimmedText = rawInput.toString(Charsets.UTF_8).trim()
    val decoded = Base64Compat.decodeOrNull(trimmedText)
    if (decoded != null && isEncryptedMyVpnEnvelope(decoded)) {
      return decoded
    }
    return rawInput
  }
}

/**
 * API-24+ and JVM-test compatible RFC 4648 Base64 decoder (standard & URL-safe).
 */
internal object Base64Compat {
  private const val ENCODE_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  private val decodeTable =
    IntArray(256) { -1 }.apply {
      for (c in 'A'..'Z') this[c.code] = c - 'A'
      for (c in 'a'..'z') this[c.code] = c - 'a' + 26
      for (c in '0'..'9') this[c.code] = c - '0' + 52
      this['+'.code] = 62
      this['-'.code] = 62
      this['/'.code] = 63
      this['_'.code] = 63
    }

  fun encode(input: ByteArray): String {
    if (input.isEmpty()) return ""
    val out = StringBuilder(((input.size + 2) / 3) * 4)
    var i = 0
    while (i < input.size) {
      val b0 = input[i].toInt() and 0xFF
      val hasB1 = i + 1 < input.size
      val hasB2 = i + 2 < input.size
      val b1 = if (hasB1) input[i + 1].toInt() and 0xFF else 0
      val b2 = if (hasB2) input[i + 2].toInt() and 0xFF else 0

      out.append(ENCODE_ALPHABET[(b0 ushr 2) and 0x3F])
      out.append(ENCODE_ALPHABET[((b0 and 0x03) shl 4) or ((b1 ushr 4) and 0x0F)])
      out.append(
        if (hasB1) ENCODE_ALPHABET[((b1 and 0x0F) shl 2) or ((b2 ushr 6) and 0x03)] else '='
      )
      out.append(if (hasB2) ENCODE_ALPHABET[b2 and 0x3F] else '=')
      i += 3
    }
    return out.toString()
  }

  fun decodeOrNull(input: String): ByteArray? {
    val clean = input.filterNot { it.isWhitespace() }
    if (clean.isEmpty()) return null
    val padded =
      when (clean.length % 4) {
        0 -> clean
        2 -> "$clean=="
        3 -> "$clean="
        else -> return null
      }

    val output = ByteArray((padded.length / 4) * 3)
    var outIdx = 0
    var i = 0
    while (i < padded.length) {
      val c0 = padded[i].code
      val c1 = padded[i + 1].code
      val c2 = padded[i + 2].code
      val c3 = padded[i + 3].code

      val v0 = if (c0 < 256) decodeTable[c0] else -1
      val v1 = if (c1 < 256) decodeTable[c1] else -1
      val v2 = if (c2 == '='.code) -2 else if (c2 < 256) decodeTable[c2] else -1
      val v3 = if (c3 == '='.code) -2 else if (c3 < 256) decodeTable[c3] else -1

      if (v0 < 0 || v1 < 0 || v2 == -1 || v3 == -1) return null

      output[outIdx++] = ((v0 shl 2) or (v1 ushr 4)).toByte()
      if (v2 >= 0) {
        output[outIdx++] = (((v1 and 0x0F) shl 4) or (v2 ushr 2)).toByte()
        if (v3 >= 0) {
          output[outIdx++] = (((v2 and 0x03) shl 6) or v3).toByte()
        }
      }
      i += 4
    }
    return output.copyOf(outIdx)
  }
}
