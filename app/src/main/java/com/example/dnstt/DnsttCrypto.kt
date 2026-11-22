package com.example.dnstt

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Pure-Kotlin RFC-compliant cryptographic primitives for DNSTT's
 * Noise_NK_25519_ChaChaPoly_BLAKE2s cipher suite:
 * - BLAKE2s-256 (RFC 7693) + HMAC + Noise HKDF
 * - X25519 Diffie-Hellman (RFC 7748)
 * - ChaCha20-Poly1305 AEAD (RFC 8439)
 */
object DnsttCrypto {

  private val secureRandom = SecureRandom()

  fun randomBytes(length: Int): ByteArray = ByteArray(length).also { secureRandom.nextBytes(it) }

  fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim()
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
      val hi = Character.digit(clean[i * 2], 16)
      val lo = Character.digit(clean[i * 2 + 1], 16)
      require(hi >= 0 && lo >= 0) { "Invalid hex character at index ${i * 2}" }
      out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
  }

  fun bytesToHex(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    val digits = "0123456789abcdef"
    for (i in bytes.indices) {
      val v = bytes[i].toInt() and 0xFF
      chars[i * 2] = digits[v ushr 4]
      chars[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(chars)
  }

  fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) {
      diff = diff or ((a[i].toInt() and 0xFF) xor (b[i].toInt() and 0xFF))
    }
    return diff == 0
  }

  internal fun readIntLE(src: ByteArray, offset: Int): Int {
    return (src[offset].toInt() and 0xFF) or
      ((src[offset + 1].toInt() and 0xFF) shl 8) or
      ((src[offset + 2].toInt() and 0xFF) shl 16) or
      ((src[offset + 3].toInt() and 0xFF) shl 24)
  }

  internal fun writeIntLE(dst: ByteArray, offset: Int, value: Int) {
    dst[offset] = (value and 0xFF).toByte()
    dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
  }

  internal fun writeLongLE(dst: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
      dst[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
    }
  }

  /** RFC 7693 BLAKE2s-256, HMAC-BLAKE2s, and Noise HKDF. */
  object Blake2s {
    private const val BLOCK_SIZE = 64
    const val HASH_LEN = 32

    private val IV =
      intArrayOf(
        0x6A09E667,
        0xBB67AE85.toInt(),
        0x3C6EF372,
        0xA54FF53A.toInt(),
        0x510E527F,
        0x9B05688C.toInt(),
        0x1F83D9AB,
        0x5BE0CD19,
      )

    private val SIGMA =
      arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
      )

    fun hash(data: ByteArray): ByteArray {
      val h = IV.copyOf()
      h[0] = h[0] xor 0x01010020 // fanout=1, depth=1, keylen=0, outlen=32

      val block = ByteArray(BLOCK_SIZE)
      var offset = 0
      var remaining = data.size
      var bytesCompressed = 0L

      while (remaining > BLOCK_SIZE) {
        System.arraycopy(data, offset, block, 0, BLOCK_SIZE)
        bytesCompressed += BLOCK_SIZE
        compress(h, block, bytesCompressed, last = false)
        offset += BLOCK_SIZE
        remaining -= BLOCK_SIZE
      }

      block.fill(0)
      if (remaining > 0) {
        System.arraycopy(data, offset, block, 0, remaining)
      }
      bytesCompressed += remaining
      compress(h, block, bytesCompressed, last = true)

      val out = ByteArray(HASH_LEN)
      for (i in 0 until 8) {
        writeIntLE(out, i * 4, h[i])
      }
      return out
    }

    fun hashConcat(a: ByteArray, b: ByteArray): ByteArray {
      val combined = ByteArray(a.size + b.size)
      System.arraycopy(a, 0, combined, 0, a.size)
      System.arraycopy(b, 0, combined, a.size, b.size)
      return hash(combined)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
      val k = if (key.size > BLOCK_SIZE) hash(key) else key
      val ipad = ByteArray(BLOCK_SIZE) { 0x36 }
      val opad = ByteArray(BLOCK_SIZE) { 0x5C }
      for (i in k.indices) {
        val kb = k[i].toInt() and 0xFF
        ipad[i] = (kb xor 0x36).toByte()
        opad[i] = (kb xor 0x5C).toByte()
      }
      val inner = hashConcat(ipad, data)
      return hashConcat(opad, inner)
    }

    fun hkdf2(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
      val tempKey = hmac(chainingKey, inputKeyMaterial)
      val out1 = hmac(tempKey, byteArrayOf(0x01))
      val out2Input = ByteArray(out1.size + 1)
      System.arraycopy(out1, 0, out2Input, 0, out1.size)
      out2Input[out1.size] = 0x02
      val out2 = hmac(tempKey, out2Input)
      return out1 to out2
    }

    private fun compress(h: IntArray, block: ByteArray, t: Long, last: Boolean) {
      val m = IntArray(16) { i -> readIntLE(block, i * 4) }
      val v = IntArray(16)
      for (i in 0 until 8) {
        v[i] = h[i]
        v[i + 8] = IV[i]
      }
      v[12] = v[12] xor (t and 0xFFFFFFFFL).toInt()
      v[13] = v[13] xor ((t ushr 32) and 0xFFFFFFFFL).toInt()
      if (last) {
        v[14] = v[14] xor -1
      }

      for (round in 0 until 10) {
        val s = SIGMA[round]
        mix(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
        mix(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
        mix(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
        mix(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
        mix(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
        mix(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
        mix(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
        mix(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
      }

      for (i in 0 until 8) {
        h[i] = h[i] xor v[i] xor v[i + 8]
      }
    }

    private fun mix(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
      v[a] = v[a] + v[b] + x
      v[d] = Integer.rotateRight(v[d] xor v[a], 16)
      v[c] = v[c] + v[d]
      v[b] = Integer.rotateRight(v[b] xor v[c], 12)
      v[a] = v[a] + v[b] + y
      v[d] = Integer.rotateRight(v[d] xor v[a], 8)
      v[c] = v[c] + v[d]
      v[b] = Integer.rotateRight(v[b] xor v[c], 7)
    }
  }

  /** RFC 7748 X25519 scalar multiplication over Curve25519. */
  object X25519 {
    const val KEY_LEN = 32
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val P_MINUS_2: BigInteger = P.subtract(BigInteger.TWO)
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASEPOINT: ByteArray = ByteArray(KEY_LEN).apply { this[0] = 9 }

    fun generatePrivateKey(): ByteArray = randomBytes(KEY_LEN)

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
      require(privateKey.size == KEY_LEN) { "X25519 private key must be 32 bytes" }
      return scalarMult(privateKey, BASEPOINT)
    }

    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
      require(privateKey.size == KEY_LEN && peerPublicKey.size == KEY_LEN) {
        "X25519 keys must be 32 bytes"
      }
      return scalarMult(privateKey, peerPublicKey)
    }

    fun scalarMult(scalarBytes: ByteArray, uBytes: ByteArray): ByteArray {
      val clamped = scalarBytes.copyOf(KEY_LEN)
      clamped[0] = (clamped[0].toInt() and 248).toByte()
      clamped[31] = (clamped[31].toInt() and 127).toByte()
      clamped[31] = (clamped[31].toInt() or 64).toByte()
      val k = decodeLittleEndianUnsigned(clamped)

      val uMasked = uBytes.copyOf(KEY_LEN)
      uMasked[31] = (uMasked[31].toInt() and 127).toByte()
      val x1 = decodeLittleEndianUnsigned(uMasked).mod(P)

      var x2 = BigInteger.ONE
      var z2 = BigInteger.ZERO
      var x3 = x1
      var z3 = BigInteger.ONE
      var swap = 0

      for (t in 254 downTo 0) {
        val kt = if (k.testBit(t)) 1 else 0
        swap = swap xor kt
        if (swap != 0) {
          val tx = x2
          x2 = x3
          x3 = tx
          val tz = z2
          z2 = z3
          z3 = tz
        }
        swap = kt

        val a = x2.add(z2).mod(P)
        val aa = a.multiply(a).mod(P)
        val b = x2.subtract(z2).mod(P)
        val bb = b.multiply(b).mod(P)
        val e = aa.subtract(bb).mod(P)
        val c = x3.add(z3).mod(P)
        val d = x3.subtract(z3).mod(P)
        val da = d.multiply(a).mod(P)
        val cb = c.multiply(b).mod(P)

        val daPlusCb = da.add(cb).mod(P)
        val daMinusCb = da.subtract(cb).mod(P)
        x3 = daPlusCb.multiply(daPlusCb).mod(P)
        z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)
        x2 = aa.multiply(bb).mod(P)
        z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
      }

      if (swap != 0) {
        val tx = x2
        x2 = x3
        x3 = tx
        val tz = z2
        z2 = z3
        z3 = tz
      }

      val result = x2.multiply(z2.modPow(P_MINUS_2, P)).mod(P)
      return encodeLittleEndian(result, KEY_LEN)
    }

    private fun decodeLittleEndianUnsigned(bytes: ByteArray): BigInteger {
      val bigEndian = ByteArray(bytes.size)
      for (i in bytes.indices) {
        bigEndian[bytes.size - 1 - i] = bytes[i]
      }
      return BigInteger(1, bigEndian)
    }

    private fun encodeLittleEndian(value: BigInteger, length: Int): ByteArray {
      val be = value.toByteArray()
      val out = ByteArray(length)
      var outIdx = 0
      var beIdx = be.size - 1
      while (outIdx < length && beIdx >= 0) {
        out[outIdx++] = be[beIdx--]
      }
      return out
    }
  }

  /** RFC 8439 ChaCha20-Poly1305 AEAD. */
  object ChaCha20Poly1305 {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    private val POLY_P: BigInteger = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val MASK_128: BigInteger = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    fun noiseNonce(counter: Long): ByteArray {
      val nonce = ByteArray(NONCE_LEN)
      // Noise spec: 4 zero bytes followed by 64-bit little-endian nonce counter
      writeLongLE(nonce, 4, counter)
      return nonce
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
      require(key.size == KEY_LEN && nonce.size == NONCE_LEN)
      val otk = chacha20Block(key, 0, nonce).copyOf(32)
      val ciphertext = chacha20Xor(key, 1, nonce, plaintext)
      val tag = poly1305Mac(otk, aad, ciphertext)
      val out = ByteArray(ciphertext.size + TAG_LEN)
      System.arraycopy(ciphertext, 0, out, 0, ciphertext.size)
      System.arraycopy(tag, 0, out, ciphertext.size, TAG_LEN)
      return out
    }

    fun decrypt(
      key: ByteArray,
      nonce: ByteArray,
      aad: ByteArray,
      ciphertextWithTag: ByteArray,
    ): ByteArray? {
      require(key.size == KEY_LEN && nonce.size == NONCE_LEN)
      if (ciphertextWithTag.size < TAG_LEN) return null
      val ctLen = ciphertextWithTag.size - TAG_LEN
      val ciphertext = ciphertextWithTag.copyOfRange(0, ctLen)
      val providedTag = ciphertextWithTag.copyOfRange(ctLen, ciphertextWithTag.size)
      val otk = chacha20Block(key, 0, nonce).copyOf(32)
      val expectedTag = poly1305Mac(otk, aad, ciphertext)
      if (!constantTimeEquals(providedTag, expectedTag)) {
        return null
      }
      return chacha20Xor(key, 1, nonce, ciphertext)
    }

    private fun chacha20Xor(
      key: ByteArray,
      initialCounter: Int,
      nonce: ByteArray,
      input: ByteArray,
    ): ByteArray {
      val out = ByteArray(input.size)
      var counter = initialCounter
      var offset = 0
      while (offset < input.size) {
        val keystream = chacha20Block(key, counter++, nonce)
        val chunk = minOf(64, input.size - offset)
        for (i in 0 until chunk) {
          out[offset + i] = ((input[offset + i].toInt() and 0xFF) xor (keystream[i].toInt() and 0xFF)).toByte()
        }
        offset += chunk
      }
      return out
    }

    private fun chacha20Block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
      val state =
        intArrayOf(
          0x61707865,
          0x3320646E,
          0x79622D32,
          0x6B206574,
          readIntLE(key, 0),
          readIntLE(key, 4),
          readIntLE(key, 8),
          readIntLE(key, 12),
          readIntLE(key, 16),
          readIntLE(key, 20),
          readIntLE(key, 24),
          readIntLE(key, 28),
          counter,
          readIntLE(nonce, 0),
          readIntLE(nonce, 4),
          readIntLE(nonce, 8),
        )
      val working = state.copyOf()
      repeat(10) {
        quarterRound(working, 0, 4, 8, 12)
        quarterRound(working, 1, 5, 9, 13)
        quarterRound(working, 2, 6, 10, 14)
        quarterRound(working, 3, 7, 11, 15)
        quarterRound(working, 0, 5, 10, 15)
        quarterRound(working, 1, 6, 11, 12)
        quarterRound(working, 2, 7, 8, 13)
        quarterRound(working, 3, 4, 9, 14)
      }
      val out = ByteArray(64)
      for (i in 0 until 16) {
        writeIntLE(out, i * 4, working[i] + state[i])
      }
      return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
      s[a] += s[b]
      s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
      s[c] += s[d]
      s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
      s[a] += s[b]
      s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
      s[c] += s[d]
      s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun poly1305Mac(otk32: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
      val rBytes = otk32.copyOfRange(0, 16)
      rBytes[3] = (rBytes[3].toInt() and 15).toByte()
      rBytes[7] = (rBytes[7].toInt() and 15).toByte()
      rBytes[11] = (rBytes[11].toInt() and 15).toByte()
      rBytes[15] = (rBytes[15].toInt() and 15).toByte()
      rBytes[4] = (rBytes[4].toInt() and 252).toByte()
      rBytes[8] = (rBytes[8].toInt() and 252).toByte()
      rBytes[12] = (rBytes[12].toInt() and 252).toByte()

      val r = decodeLittleEndianUnsigned(rBytes)
      val s = decodeLittleEndianUnsigned(otk32.copyOfRange(16, 32))

      val aadPad = (16 - (aad.size % 16)) % 16
      val ctPad = (16 - (ciphertext.size % 16)) % 16
      val macData = ByteArray(aad.size + aadPad + ciphertext.size + ctPad + 16)
      var pos = 0
      System.arraycopy(aad, 0, macData, pos, aad.size)
      pos += aad.size + aadPad
      System.arraycopy(ciphertext, 0, macData, pos, ciphertext.size)
      pos += ciphertext.size + ctPad
      writeLongLE(macData, pos, aad.size.toLong())
      writeLongLE(macData, pos + 8, ciphertext.size.toLong())

      var acc = BigInteger.ZERO
      var offset = 0
      while (offset < macData.size) {
        val len = minOf(16, macData.size - offset)
        val block = ByteArray(len + 1)
        System.arraycopy(macData, offset, block, 0, len)
        block[len] = 1
        val n = decodeLittleEndianUnsigned(block)
        acc = acc.add(n).multiply(r).mod(POLY_P)
        offset += len
      }
      val tagInt = acc.add(s).and(MASK_128)
      return encodeLittleEndian(tagInt, TAG_LEN)
    }

    private fun decodeLittleEndianUnsigned(bytes: ByteArray): BigInteger {
      val be = ByteArray(bytes.size)
      for (i in bytes.indices) {
        be[bytes.size - 1 - i] = bytes[i]
      }
      return BigInteger(1, be)
    }

    private fun encodeLittleEndian(value: BigInteger, length: Int): ByteArray {
      val be = value.toByteArray()
      val out = ByteArray(length)
      var outIdx = 0
      var beIdx = be.size - 1
      while (outIdx < length && beIdx >= 0) {
        out[outIdx++] = be[beIdx--]
      }
      return out
    }
  }
}
