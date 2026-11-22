package com.example.dnstt

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Wire-compatible implementation of DNSTT's Noise layer (`noise/noise.go`):
 * - Cipher suite: `Noise_NK_25519_ChaChaPoly_BLAKE2s`
 * - Prologue: `"dnstt 2020-04-13"`
 * - Message framing: 16-bit big-endian length prefix (`uint16`)
 * - Transport chunking: up to 4096 plaintext bytes per AEAD frame
 */
class DnsttNoiseChannel private constructor(
  private val rawInput: InputStream,
  private val rawOutput: OutputStream,
  private val sendKey: ByteArray,
  private val recvKey: ByteArray,
) : Closeable {

  private val writeLock = Any()
  private val readLock = Any()
  private var sendNonce: Long = 0L
  private var recvNonce: Long = 0L
  private var pendingPlaintext: ByteArray = ByteArray(0)
  private var pendingOffset: Int = 0
  @Volatile private var closed: Boolean = false

  val inputStream: InputStream =
    object : InputStream() {
      override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        return readPlaintext(b, off, len)
      }

      override fun close() {
        this@DnsttNoiseChannel.close()
      }
    }

  val outputStream: OutputStream =
    object : OutputStream() {
      override fun write(b: Int) {
        writePlaintext(byteArrayOf(b.toByte()), 0, 1)
      }

      override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        writePlaintext(b, off, len)
      }

      override fun flush() {
        rawOutput.flush()
      }

      override fun close() {
        this@DnsttNoiseChannel.close()
      }
    }

  @Throws(IOException::class)
  private fun writePlaintext(data: ByteArray, off: Int, len: Int) {
    synchronized(writeLock) {
      if (closed) throw IOException("Noise channel closed")
      var pos = off
      var remaining = len
      while (remaining > 0) {
        val chunkLen = minOf(MAX_PLAINTEXT_CHUNK, remaining)
        val chunk = data.copyOfRange(pos, pos + chunkLen)
        val ciphertext =
          DnsttCrypto.ChaCha20Poly1305.encrypt(
            key = sendKey,
            nonce = DnsttCrypto.ChaCha20Poly1305.noiseNonce(sendNonce++),
            aad = EMPTY_BYTES,
            plaintext = chunk,
          )
        writeFramedMessage(rawOutput, ciphertext)
        pos += chunkLen
        remaining -= chunkLen
      }
      rawOutput.flush()
    }
  }

  @Throws(IOException::class)
  private fun readPlaintext(dst: ByteArray, off: Int, len: Int): Int {
    synchronized(readLock) {
      if (closed) return -1
      while (pendingOffset >= pendingPlaintext.size) {
        val frame = readFramedMessage(rawInput) ?: return -1
        val plain =
          DnsttCrypto.ChaCha20Poly1305.decrypt(
            key = recvKey,
            nonce = DnsttCrypto.ChaCha20Poly1305.noiseNonce(recvNonce++),
            aad = EMPTY_BYTES,
            ciphertextWithTag = frame,
          ) ?: throw IOException("Noise AEAD decryption failed (invalid authentication tag)")
        pendingPlaintext = plain
        pendingOffset = 0
      }
      val avail = pendingPlaintext.size - pendingOffset
      val copyLen = minOf(len, avail)
      System.arraycopy(pendingPlaintext, pendingOffset, dst, off, copyLen)
      pendingOffset += copyLen
      return copyLen
    }
  }

  override fun close() {
    closed = true
    runCatching { rawInput.close() }
    runCatching { rawOutput.close() }
  }

  companion object {
    const val PROTOCOL_NAME = "Noise_NK_25519_ChaChaPoly_BLAKE2s"
    const val PROLOGUE = "dnstt 2020-04-13"
    private const val MAX_PLAINTEXT_CHUNK = 4096
    private val EMPTY_BYTES = ByteArray(0)

    /**
     * Performs the Noise_NK initiator (client) handshake against a DNSTT server whose
     * 32-byte X25519 static public key is [serverPublicKey].
     */
    @Throws(IOException::class)
    fun connectClient(
      input: InputStream,
      output: OutputStream,
      serverPublicKey: ByteArray,
    ): DnsttNoiseChannel {
      require(serverPublicKey.size == DnsttCrypto.X25519.KEY_LEN) {
        "Server public key must be 32 bytes"
      }
      val hs = HandshakeState.initializeNk(serverPublicKey)

      // Message 1 (-> e, es)
      val ephPriv = DnsttCrypto.X25519.generatePrivateKey()
      val ephPub = DnsttCrypto.X25519.publicKeyFromPrivate(ephPriv)
      hs.mixHash(ephPub)
      hs.mixKey(DnsttCrypto.X25519.sharedSecret(ephPriv, serverPublicKey))
      val tag1 = hs.encryptAndHash(EMPTY_BYTES)
      val msg1 = ByteArray(ephPub.size + tag1.size)
      System.arraycopy(ephPub, 0, msg1, 0, ephPub.size)
      System.arraycopy(tag1, 0, msg1, ephPub.size, tag1.size)
      writeFramedMessage(output, msg1)
      output.flush()

      // Message 2 (<- e, ee)
      val msg2 =
        readFramedMessage(input)
          ?: throw IOException("DNSTT Noise Handshake မအောင်မြင်ပါ — ဆာဗာမှ တုံ့ပြန်ချက် မရရှိပါ (EOF)")
      if (msg2.size < DnsttCrypto.X25519.KEY_LEN + DnsttCrypto.ChaCha20Poly1305.TAG_LEN) {
        throw IOException("DNSTT Noise Handshake တုံ့ပြန်ချက် မပြည့်စုံပါ (${msg2.size} bytes)")
      }
      val remoteEphPub = msg2.copyOfRange(0, DnsttCrypto.X25519.KEY_LEN)
      val encryptedPayload = msg2.copyOfRange(DnsttCrypto.X25519.KEY_LEN, msg2.size)
      hs.mixHash(remoteEphPub)
      hs.mixKey(DnsttCrypto.X25519.sharedSecret(ephPriv, remoteEphPub))
      hs.decryptAndHash(encryptedPayload)
        ?: throw IOException("DNSTT Public Key မကိုက်ညီပါ — Noise Handshake အတည်ပြုမှု မအောင်မြင်ပါ")

      val (c1, c2) = hs.split()
      return DnsttNoiseChannel(rawInput = input, rawOutput = output, sendKey = c1, recvKey = c2)
    }

    /**
     * Performs the Noise_NK responder (server) handshake — used by JVM unit tests to verify
     * full end-to-end Noise_NK compatibility.
     */
    @Throws(IOException::class)
    internal fun acceptServer(
      input: InputStream,
      output: OutputStream,
      serverPrivateKey: ByteArray,
    ): DnsttNoiseChannel {
      val serverPub = DnsttCrypto.X25519.publicKeyFromPrivate(serverPrivateKey)
      val hs = HandshakeState.initializeNk(serverPub)

      val msg1 = readFramedMessage(input) ?: throw EOFException("Missing Noise client message 1")
      if (msg1.size < DnsttCrypto.X25519.KEY_LEN + DnsttCrypto.ChaCha20Poly1305.TAG_LEN) {
        throw IOException("Malformed Noise message 1")
      }
      val remoteEphPub = msg1.copyOfRange(0, DnsttCrypto.X25519.KEY_LEN)
      val payload1 = msg1.copyOfRange(DnsttCrypto.X25519.KEY_LEN, msg1.size)
      hs.mixHash(remoteEphPub)
      hs.mixKey(DnsttCrypto.X25519.sharedSecret(serverPrivateKey, remoteEphPub))
      hs.decryptAndHash(payload1) ?: throw IOException("Noise message 1 auth failed")

      val ephPriv = DnsttCrypto.X25519.generatePrivateKey()
      val ephPub = DnsttCrypto.X25519.publicKeyFromPrivate(ephPriv)
      hs.mixHash(ephPub)
      hs.mixKey(DnsttCrypto.X25519.sharedSecret(ephPriv, remoteEphPub))
      val tag2 = hs.encryptAndHash(EMPTY_BYTES)
      val msg2 = ByteArray(ephPub.size + tag2.size)
      System.arraycopy(ephPub, 0, msg2, 0, ephPub.size)
      System.arraycopy(tag2, 0, msg2, ephPub.size, tag2.size)
      writeFramedMessage(output, msg2)
      output.flush()

      val (c1, c2) = hs.split()
      return DnsttNoiseChannel(rawInput = input, rawOutput = output, sendKey = c2, recvKey = c1)
    }

    @Throws(IOException::class)
    internal fun writeFramedMessage(output: OutputStream, msg: ByteArray) {
      require(msg.size <= 0xFFFF) { "Noise frame exceeds uint16 max: ${msg.size}" }
      val header = byteArrayOf(((msg.size ushr 8) and 0xFF).toByte(), (msg.size and 0xFF).toByte())
      output.write(header)
      output.write(msg)
    }

    @Throws(IOException::class)
    internal fun readFramedMessage(input: InputStream): ByteArray? {
      val b0 = input.read()
      if (b0 == -1) return null
      val b1 = input.read()
      if (b1 == -1) throw EOFException("Unexpected EOF reading Noise frame length")
      val length = ((b0 and 0xFF) shl 8) or (b1 and 0xFF)
      val msg = ByteArray(length)
      var offset = 0
      while (offset < length) {
        val n = input.read(msg, offset, length - offset)
        if (n <= 0) throw EOFException("Unexpected EOF reading Noise frame body ($offset/$length)")
        offset += n
      }
      return msg
    }

    private class HandshakeState(
      private var ck: ByteArray,
      private var h: ByteArray,
    ) {
      private var k: ByteArray? = null
      private var n: Long = 0L

      fun mixHash(data: ByteArray) {
        h = DnsttCrypto.Blake2s.hashConcat(h, data)
      }

      fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = DnsttCrypto.Blake2s.hkdf2(ck, ikm)
        ck = newCk
        k = tempK
        n = 0L
      }

      fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val key = k
        val out =
          if (key == null) {
            plaintext
          } else {
            DnsttCrypto.ChaCha20Poly1305.encrypt(
              key = key,
              nonce = DnsttCrypto.ChaCha20Poly1305.noiseNonce(n++),
              aad = h,
              plaintext = plaintext,
            )
          }
        mixHash(out)
        return out
      }

      fun decryptAndHash(ciphertext: ByteArray): ByteArray? {
        val key = k
        val plain =
          if (key == null) {
            ciphertext
          } else {
            DnsttCrypto.ChaCha20Poly1305.decrypt(
              key = key,
              nonce = DnsttCrypto.ChaCha20Poly1305.noiseNonce(n++),
              aad = h,
              ciphertextWithTag = ciphertext,
            )
          }
        if (plain != null) {
          mixHash(ciphertext)
        }
        return plain
      }

      fun split(): Pair<ByteArray, ByteArray> {
        return DnsttCrypto.Blake2s.hkdf2(ck, EMPTY_BYTES)
      }

      companion object {
        fun initializeNk(responderStaticPubKey: ByteArray): HandshakeState {
          val protoBytes = PROTOCOL_NAME.toByteArray(StandardCharsets.US_ASCII)
          // Protocol name is 33 bytes (> 32), so Noise initializes h = HASH(protocol_name)
          val initialH = DnsttCrypto.Blake2s.hash(protoBytes)
          val state = HandshakeState(ck = initialH.copyOf(), h = initialH)
          state.mixHash(PROLOGUE.toByteArray(StandardCharsets.US_ASCII))
          // NK pre-message pattern: <- s
          state.mixHash(responderStaticPubKey)
          return state
        }
      }
    }
  }
}
