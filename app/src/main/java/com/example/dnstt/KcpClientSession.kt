package com.example.dnstt

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap
import kotlin.math.abs

/**
 * Wire-compatible KCP (`xtaci/kcp-go` v5) reliable stream session designed for DNSTT's
 * small DNS-encoded MTU. Uses exact 24-byte little-endian KCP headers and stream mode (`frg = 0`).
 */
class KcpClientSession(
  val conv: Int,
  val mtu: Int,
  private val sendPacket: (ByteArray) -> Unit,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
  private val mss: Int = (mtu - HEADER_SIZE).coerceAtLeast(24)
  private val lock = Object()

  private var sndUna: Long = 0L
  private var sndNxt: Long = 0L
  private var rcvNxt: Long = 0L
  private var rmtWnd: Int = DEFAULT_WND
  private val sndWnd: Int = DEFAULT_WND
  private val rcvWnd: Int = DEFAULT_WND

  private var rxSrtt: Int = 0
  private var rxRttval: Int = 0
  private var rxRto: Int = INITIAL_RTO_MS
  private val rxMinRto: Int = MIN_RTO_MS

  private var currentMs: Long = clockMs()
  private var probeWait: Int = 0
  private var tsProbe: Long = 0L
  private var probeFlags: Int = 0

  private val sndQueue = ArrayDeque<ByteArray>()
  private val sndBuf = ArrayDeque<Segment>()
  private val rcvBuf = TreeMap<Long, ByteArray>()
  private val ackList = ArrayList<Pair<Long, Long>>()
  private val recvStream = ArrayDeque<ByteArray>()
  private var recvChunkOffset = 0

  @Volatile private var closed: Boolean = false
  @Volatile private var closeReason: String? = null

  private data class Segment(
    val sn: Long,
    var ts: Long,
    var rto: Int,
    var resendTs: Long,
    var fastAck: Int,
    var xmit: Int,
    val data: ByteArray,
  )

  val inputStream: InputStream =
    object : InputStream() {
      override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        return readStream(b, off, len)
      }

      override fun close() {
        this@KcpClientSession.close("input closed")
      }
    }

  val outputStream: OutputStream =
    object : OutputStream() {
      override fun write(b: Int) {
        writeStream(byteArrayOf(b.toByte()), 0, 1)
      }

      override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        writeStream(b, off, len)
      }

      override fun flush() {
        update(clockMs())
      }

      override fun close() {
        this@KcpClientSession.close("output closed")
      }
    }

  @Throws(IOException::class)
  fun writeStream(data: ByteArray, off: Int, len: Int) {
    synchronized(lock) {
      if (closed) throw IOException(closeReason ?: "KCP session closed")
      var pos = off
      var remaining = len
      while (remaining > 0) {
        val chunkLen = minOf(mss, remaining)
        val chunk = data.copyOfRange(pos, pos + chunkLen)
        sndQueue.addLast(chunk)
        pos += chunkLen
        remaining -= chunkLen
      }
      flushLocked(clockMs())
    }
  }

  @Throws(IOException::class)
  fun readStream(dst: ByteArray, off: Int, len: Int): Int {
    synchronized(lock) {
      while (recvStream.isEmpty()) {
        if (closed) return -1
        try {
          lock.wait(250L)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return -1
        }
      }
      var copied = 0
      while (copied < len && recvStream.isNotEmpty()) {
        val head = recvStream.first()
        val avail = head.size - recvChunkOffset
        val toCopy = minOf(len - copied, avail)
        System.arraycopy(head, recvChunkOffset, dst, off + copied, toCopy)
        copied += toCopy
        recvChunkOffset += toCopy
        if (recvChunkOffset >= head.size) {
          recvStream.removeFirst()
          recvChunkOffset = 0
        }
      }
      return copied
    }
  }

  /**
   * Feeds one raw datagram received from a DNS TXT response (may contain 1 or more
   * concatenated KCP segments).
   */
  fun input(datagram: ByteArray) {
    if (datagram.size < HEADER_SIZE) return
    val now = clockMs()
    synchronized(lock) {
      if (closed) return
      var offset = 0
      var maxAckSn = -1L
      var hasAck = false

      while (offset + HEADER_SIZE <= datagram.size) {
        val segConv = DnsttCrypto.readIntLE(datagram, offset)
        if (segConv != conv) return
        val cmd = datagram[offset + 4].toInt() and 0xFF
        val wnd =
          (datagram[offset + 6].toInt() and 0xFF) or
            ((datagram[offset + 7].toInt() and 0xFF) shl 8)
        val ts = DnsttCrypto.readIntLE(datagram, offset + 8).toLong() and 0xFFFFFFFFL
        val sn = DnsttCrypto.readIntLE(datagram, offset + 12).toLong() and 0xFFFFFFFFL
        val una = DnsttCrypto.readIntLE(datagram, offset + 16).toLong() and 0xFFFFFFFFL
        val dataLen = DnsttCrypto.readIntLE(datagram, offset + 20)
        offset += HEADER_SIZE

        if (dataLen < 0 || offset + dataLen > datagram.size) break
        val payload =
          if (dataLen > 0) datagram.copyOfRange(offset, offset + dataLen) else ByteArray(0)
        offset += dataLen

        rmtWnd = wnd
        parseUnaLocked(una)
        shrinkBufLocked()

        when (cmd) {
          CMD_ACK -> {
            val rtt = (now and 0xFFFFFFFFL) - ts
            if (rtt in 0..60_000) {
              updateAckRttLocked(rtt.toInt())
            }
            parseAckLocked(sn)
            shrinkBufLocked()
            if (!hasAck || sn > maxAckSn) {
              maxAckSn = sn
              hasAck = true
            }
          }
          CMD_PUSH -> {
            if (sn < rcvNxt + rcvWnd) {
              ackList.add(sn to ts)
              if (sn >= rcvNxt) {
                parseDataLocked(sn, payload)
              }
            }
          }
          CMD_WASK -> {
            probeFlags = probeFlags or PROBE_TELL
          }
          CMD_WINS -> {
            // Remote window already updated from header
          }
          else -> return
        }
      }

      if (hasAck) {
        parseFastAckLocked(maxAckSn)
      }
      flushLocked(now)
    }
  }

  /** Periodic KCP flush/retransmit tick (call every ~25ms). */
  fun update(nowMs: Long = clockMs()) {
    synchronized(lock) {
      if (closed) return
      flushLocked(nowMs)
    }
  }

  fun hasPendingWork(): Boolean {
    synchronized(lock) {
      return !closed && (sndQueue.isNotEmpty() || sndBuf.isNotEmpty() || ackList.isNotEmpty())
    }
  }

  fun close(reason: String = "KCP closed") {
    synchronized(lock) {
      if (closed) return
      closed = true
      closeReason = reason
      sndQueue.clear()
      sndBuf.clear()
      rcvBuf.clear()
      ackList.clear()
      lock.notifyAll()
    }
  }

  private fun parseUnaLocked(una: Long) {
    while (sndBuf.isNotEmpty() && sndBuf.first().sn < una) {
      sndBuf.removeFirst()
    }
  }

  private fun parseAckLocked(sn: Long) {
    if (sn < sndUna || sn >= sndNxt) return
    val iter = sndBuf.iterator()
    while (iter.hasNext()) {
      val seg = iter.next()
      if (seg.sn == sn) {
        iter.remove()
        break
      } else if (seg.sn > sn) {
        break
      }
    }
  }

  private fun parseFastAckLocked(maxAckSn: Long) {
    if (maxAckSn < sndUna || maxAckSn >= sndNxt) return
    for (seg in sndBuf) {
      if (seg.sn < maxAckSn) {
        seg.fastAck += 1
      } else {
        break
      }
    }
  }

  private fun shrinkBufLocked() {
    sndUna = if (sndBuf.isNotEmpty()) sndBuf.first().sn else sndNxt
  }

  private fun parseDataLocked(sn: Long, payload: ByteArray) {
    if (sn >= rcvNxt + rcvWnd || sn < rcvNxt) return
    if (!rcvBuf.containsKey(sn)) {
      rcvBuf[sn] = payload
    }
    var delivered = false
    while (rcvBuf.containsKey(rcvNxt)) {
      val chunk = rcvBuf.remove(rcvNxt) ?: break
      rcvNxt += 1
      if (chunk.isNotEmpty()) {
        recvStream.addLast(chunk)
        delivered = true
      }
    }
    if (delivered) {
      lock.notifyAll()
    }
  }

  private fun updateAckRttLocked(rtt: Int) {
    if (rxSrtt == 0) {
      rxSrtt = rtt
      rxRttval = rtt / 2
    } else {
      val delta = abs(rtt - rxSrtt)
      rxRttval = (3 * rxRttval + delta) / 4
      rxSrtt = (7 * rxSrtt + rtt) / 8
      if (rxSrtt < 1) rxSrtt = 1
    }
    val rto = rxSrtt + maxOf(INTERVAL_MS, 4 * rxRttval)
    rxRto = rto.coerceIn(rxMinRto, MAX_RTO_MS)
  }

  private fun flushLocked(nowMs: Long) {
    currentMs = nowMs
    val currentTs = nowMs and 0xFFFFFFFFL
    val wndAvailable = (rcvWnd - recvStream.size).coerceIn(0, rcvWnd)

    val outBuf = ByteArrayOutputStream(mtu)
    fun emitIfNeeded(nextSize: Int) {
      if (outBuf.size() > 0 && outBuf.size() + nextSize > mtu) {
        sendPacket(outBuf.toByteArray())
        outBuf.reset()
      }
    }

    // 1. Flush pending ACKs
    if (ackList.isNotEmpty()) {
      for ((ackSn, ackTs) in ackList) {
        emitIfNeeded(HEADER_SIZE)
        writeHeader(
          out = outBuf,
          cmd = CMD_ACK,
          wnd = wndAvailable,
          ts = ackTs,
          sn = ackSn,
          una = rcvNxt,
          len = 0,
        )
      }
      ackList.clear()
    }

    // 2. Probe window if remote window is 0
    if (rmtWnd == 0) {
      if (probeWait == 0) {
        probeWait = PROBE_INIT_MS
        tsProbe = nowMs + probeWait
      } else if (nowMs >= tsProbe) {
        if (probeWait < PROBE_INIT_MS) probeWait = PROBE_INIT_MS
        probeWait = (probeWait + probeWait / 2).coerceAtMost(PROBE_LIMIT_MS)
        tsProbe = nowMs + probeWait
        probeFlags = probeFlags or PROBE_ASK
      }
    } else {
      tsProbe = 0L
      probeWait = 0
    }

    if ((probeFlags and PROBE_ASK) != 0) {
      emitIfNeeded(HEADER_SIZE)
      writeHeader(outBuf, CMD_WASK, wndAvailable, currentTs, 0L, rcvNxt, 0)
    }
    if ((probeFlags and PROBE_TELL) != 0) {
      emitIfNeeded(HEADER_SIZE)
      writeHeader(outBuf, CMD_WINS, wndAvailable, currentTs, 0L, rcvNxt, 0)
    }
    probeFlags = 0

    // 3. Move chunks from sndQueue -> sndBuf within window
    val cwnd = minOf(sndWnd, rmtWnd.coerceAtLeast(1))
    while (sndNxt < sndUna + cwnd && sndQueue.isNotEmpty()) {
      val data = sndQueue.removeFirst()
      sndBuf.addLast(
        Segment(
          sn = sndNxt++,
          ts = currentTs,
          rto = rxRto,
          resendTs = nowMs,
          fastAck = 0,
          xmit = 0,
          data = data,
        )
      )
    }

    // 4. Transmit new / timed-out / fast-retransmit segments
    for (seg in sndBuf) {
      var needSend = false
      if (seg.xmit == 0) {
        needSend = true
        seg.xmit = 1
        seg.rto = rxRto
        seg.resendTs = nowMs + seg.rto
      } else if (nowMs >= seg.resendTs) {
        needSend = true
        seg.xmit += 1
        seg.rto = (seg.rto + (seg.rto / 2).coerceAtLeast(100)).coerceAtMost(MAX_RTO_MS)
        seg.resendTs = nowMs + seg.rto
      } else if (seg.fastAck >= FAST_RESEND) {
        needSend = true
        seg.xmit += 1
        seg.fastAck = 0
        seg.resendTs = nowMs + seg.rto
      }

      if (needSend) {
        seg.ts = currentTs
        val segSize = HEADER_SIZE + seg.data.size
        emitIfNeeded(segSize)
        writeHeader(outBuf, CMD_PUSH, wndAvailable, seg.ts, seg.sn, rcvNxt, seg.data.size)
        outBuf.write(seg.data)
      }
    }

    if (outBuf.size() > 0) {
      sendPacket(outBuf.toByteArray())
    }
  }

  private fun writeHeader(
    out: ByteArrayOutputStream,
    cmd: Int,
    wnd: Int,
    ts: Long,
    sn: Long,
    una: Long,
    len: Int,
  ) {
    val hdr = ByteArray(HEADER_SIZE)
    DnsttCrypto.writeIntLE(hdr, 0, conv)
    hdr[4] = cmd.toByte()
    hdr[5] = 0 // frg = 0 (stream mode)
    hdr[6] = (wnd and 0xFF).toByte()
    hdr[7] = ((wnd ushr 8) and 0xFF).toByte()
    DnsttCrypto.writeIntLE(hdr, 8, ts.toInt())
    DnsttCrypto.writeIntLE(hdr, 12, sn.toInt())
    DnsttCrypto.writeIntLE(hdr, 16, una.toInt())
    DnsttCrypto.writeIntLE(hdr, 20, len)
    out.write(hdr)
  }

  companion object {
    const val HEADER_SIZE = 24
    const val CMD_PUSH = 81
    const val CMD_ACK = 82
    const val CMD_WASK = 83
    const val CMD_WINS = 84

    private const val DEFAULT_WND = 128
    private const val INTERVAL_MS = 25
    private const val INITIAL_RTO_MS = 450
    private const val MIN_RTO_MS = 200
    private const val MAX_RTO_MS = 6_000
    private const val FAST_RESEND = 2
    private const val PROBE_ASK = 1
    private const val PROBE_TELL = 2
    private const val PROBE_INIT_MS = 2_000
    private const val PROBE_LIMIT_MS = 30_000
  }
}
