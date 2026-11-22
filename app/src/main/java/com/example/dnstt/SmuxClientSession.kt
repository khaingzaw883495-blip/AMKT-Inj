package com.example.dnstt

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wire-compatible `xtaci/smux` Version 2 client multiplexer running on top of [DnsttNoiseChannel].
 *
 * Header layout (8 bytes, little-endian):
 * - `[0]` version = 2
 * - `[1]` cmd: 0=SYN, 1=FIN, 2=PSH, 3=NOP, 4=UPD
 * - `[2..3]` length (uint16 LE)
 * - `[4..7]` stream id (uint32 LE; client uses odd IDs starting at 1)
 */
class SmuxClientSession(
  private val input: InputStream,
  private val output: OutputStream,
  private val isClient: Boolean = true,
  private val onSessionClosed: (Throwable?) -> Unit = {},
) : Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val writeLock = Any()
  private val closed = AtomicBoolean(false)
  private val nextStreamId = AtomicInteger(if (isClient) 1 else 2)
  private val streams = ConcurrentHashMap<Int, SmuxStream>()
  private val incomingQueue = ArrayDeque<SmuxStream>()
  private val acceptLock = Object()

  private var readerJob: Job? = null
  private var keepAliveJob: Job? = null

  fun start() {
    readerJob = scope.launch { readLoop() }
    keepAliveJob =
      scope.launch {
        while (isActive && !closed.get()) {
          delay(KEEPALIVE_INTERVAL_MS)
          if (closed.get()) break
          try {
            writeFrame(CMD_NOP, 0, EMPTY_BYTES)
          } catch (e: IOException) {
            closeInternal(e)
            break
          }
        }
      }
  }

  val isClosed: Boolean
    get() = closed.get()

  @Throws(IOException::class)
  fun openStream(): SmuxStream {
    if (closed.get()) throw IOException("smux session is closed")
    val sid = nextStreamId.getAndAdd(2)
    val stream = SmuxStream(sid)
    streams[sid] = stream
    writeFrame(CMD_SYN, sid, EMPTY_BYTES)
    return stream
  }

  /** Accepts an incoming stream (used by mock `dnstt-server` JVM tests). */
  @Throws(IOException::class)
  internal fun acceptStream(): SmuxStream {
    synchronized(acceptLock) {
      while (incomingQueue.isEmpty()) {
        if (closed.get()) throw EOFException("smux session closed")
        try {
          acceptLock.wait(250L)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          throw EOFException("interrupted")
        }
      }
      return incomingQueue.removeFirst()
    }
  }

  override fun close() {
    closeInternal(null)
  }

  private fun closeInternal(cause: Throwable?) {
    if (!closed.compareAndSet(false, true)) return
    readerJob?.cancel()
    keepAliveJob?.cancel()
    streams.values.forEach { it.markRemoteClosed() }
    streams.clear()
    synchronized(acceptLock) { acceptLock.notifyAll() }
    runCatching { input.close() }
    runCatching { output.close() }
    scope.cancel()
    onSessionClosed(cause)
  }

  private fun readLoop() {
    val hdr = ByteArray(HEADER_SIZE)
    try {
      while (!closed.get()) {
        readFully(input, hdr, 0, HEADER_SIZE)
        val ver = hdr[0].toInt() and 0xFF
        val cmd = hdr[1].toInt() and 0xFF
        val length = (hdr[2].toInt() and 0xFF) or ((hdr[3].toInt() and 0xFF) shl 8)
        val sid = DnsttCrypto.readIntLE(hdr, 4)

        if (ver != VERSION_2 && ver != VERSION_1) {
          throw IOException("Unsupported smux protocol version: $ver")
        }

        val payload =
          if (length > 0) {
            ByteArray(length).also { readFully(input, it, 0, length) }
          } else {
            EMPTY_BYTES
          }

        when (cmd) {
          CMD_SYN -> {
            if (!streams.containsKey(sid)) {
              val stream = SmuxStream(sid)
              streams[sid] = stream
              synchronized(acceptLock) {
                incomingQueue.addLast(stream)
                acceptLock.notifyAll()
              }
            }
          }
          CMD_PSH -> {
            streams[sid]?.enqueueIncoming(payload)
          }
          CMD_FIN -> {
            streams[sid]?.markRemoteClosed()
          }
          CMD_UPD -> {
            // Peer window update (8 bytes: consumed uint32 LE + window uint32 LE)
          }
          CMD_NOP -> {
            // Keep-alive frame
          }
        }
      }
    } catch (e: Throwable) {
      if (!closed.get()) {
        closeInternal(e)
      }
    }
  }

  @Throws(IOException::class)
  private fun writeFrame(cmd: Int, sid: Int, data: ByteArray, off: Int = 0, len: Int = data.size) {
    synchronized(writeLock) {
      if (closed.get()) throw IOException("smux session closed")
      val hdr = ByteArray(HEADER_SIZE)
      hdr[0] = VERSION_2.toByte()
      hdr[1] = cmd.toByte()
      hdr[2] = (len and 0xFF).toByte()
      hdr[3] = ((len ushr 8) and 0xFF).toByte()
      DnsttCrypto.writeIntLE(hdr, 4, sid)
      output.write(hdr)
      if (len > 0) {
        output.write(data, off, len)
      }
      output.flush()
    }
  }

  @Throws(IOException::class)
  private fun readFully(src: InputStream, dst: ByteArray, off: Int, len: Int) {
    var pos = 0
    while (pos < len) {
      val n = src.read(dst, off + pos, len - pos)
      if (n <= 0) throw EOFException("smux stream EOF")
      pos += n
    }
  }

  inner class SmuxStream internal constructor(val sid: Int) : Closeable {
    private val bufLock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var totalConsumed: Long = 0L
    private var lastReportedConsumed: Long = 0L
    @Volatile private var remoteEof = false
    @Volatile private var localClosed = false

    val inputStream: InputStream =
      object : InputStream() {
        override fun read(): Int {
          val single = ByteArray(1)
          val n = read(single, 0, 1)
          return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (len == 0) return 0
          return readFromStream(b, off, len)
        }

        override fun close() {
          this@SmuxStream.close()
        }
      }

    val outputStream: OutputStream =
      object : OutputStream() {
        override fun write(b: Int) {
          writeToStream(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          if (len == 0) return
          writeToStream(b, off, len)
        }

        override fun close() {
          this@SmuxStream.close()
        }
      }

    internal fun enqueueIncoming(data: ByteArray) {
      if (data.isEmpty()) return
      synchronized(bufLock) {
        if (!localClosed) {
          queue.addLast(data)
          bufLock.notifyAll()
        }
      }
    }

    internal fun markRemoteClosed() {
      synchronized(bufLock) {
        remoteEof = true
        bufLock.notifyAll()
      }
    }

    private fun readFromStream(dst: ByteArray, off: Int, len: Int): Int {
      var shouldSendUpdate = false
      var consumedSnapshot = 0L
      val copied =
        synchronized(bufLock) {
          while (queue.isEmpty()) {
            if (remoteEof || localClosed || closed.get()) return -1
            try {
              bufLock.wait(250L)
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
              return -1
            }
          }
          var total = 0
          while (total < len && queue.isNotEmpty()) {
            val head = queue.first()
            val avail = head.size - headOffset
            val toCopy = minOf(len - total, avail)
            System.arraycopy(head, headOffset, dst, off + total, toCopy)
            total += toCopy
            headOffset += toCopy
            if (headOffset >= head.size) {
              queue.removeFirst()
              headOffset = 0
            }
          }
          totalConsumed += total
          if (totalConsumed - lastReportedConsumed >= WINDOW_UPDATE_THRESHOLD) {
            lastReportedConsumed = totalConsumed
            consumedSnapshot = totalConsumed
            shouldSendUpdate = true
          }
          total
        }

      if (shouldSendUpdate && !localClosed && !closed.get()) {
        val upd = ByteArray(8)
        DnsttCrypto.writeIntLE(upd, 0, (consumedSnapshot and 0xFFFFFFFFL).toInt())
        DnsttCrypto.writeIntLE(upd, 4, MAX_STREAM_BUFFER)
        runCatching { writeFrame(CMD_UPD, sid, upd) }
      }
      return copied
    }

    @Throws(IOException::class)
    private fun writeToStream(src: ByteArray, off: Int, len: Int) {
      if (localClosed || closed.get()) throw IOException("smux stream closed")
      var pos = off
      var remaining = len
      while (remaining > 0) {
        val chunk = minOf(MAX_FRAME_PAYLOAD, remaining)
        writeFrame(CMD_PSH, sid, src, pos, chunk)
        pos += chunk
        remaining -= chunk
      }
    }

    override fun close() {
      if (localClosed) return
      localClosed = true
      streams.remove(sid)
      synchronized(bufLock) {
        queue.clear()
        bufLock.notifyAll()
      }
      if (!closed.get()) {
        runCatching { writeFrame(CMD_FIN, sid, EMPTY_BYTES) }
      }
    }
  }

  companion object {
    const val HEADER_SIZE = 8
    const val VERSION_1 = 1
    const val VERSION_2 = 2
    const val CMD_SYN = 0
    const val CMD_FIN = 1
    const val CMD_PSH = 2
    const val CMD_NOP = 3
    const val CMD_UPD = 4

    const val MAX_STREAM_BUFFER = 1 * 1024 * 1024 // Matches dnstt-client MaxStreamBuffer = 1 MB
    private const val MAX_FRAME_PAYLOAD = 4096
    private const val WINDOW_UPDATE_THRESHOLD = 4096L
    private const val KEEPALIVE_INTERVAL_MS = 10_000L
    private val EMPTY_BYTES = ByteArray(0)
  }
}
