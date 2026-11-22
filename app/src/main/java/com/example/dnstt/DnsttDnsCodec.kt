package com.example.dnstt

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Exact wire-compatible DNSTT DNS packet codec matching upstream `dnstt-client/dns.go`:
 * - Outgoing payload: `ClientID (8 bytes) || (224 + numPadding) || random(numPadding) || [len(p) || p]`
 * - Encoded as lowercase RFC 4648 Base32 (no padding), split into <=63-byte DNS labels + tunnel domain
 * - Sent as DNS TXT (16) IN (1) query with RD=1 and EDNS0 OPT (UDP payload size 4096)
 * - Incoming TXT RDATA: concatenated character-strings unpacked into `uint16 BE` length-prefixed KCP packets
 */
object DnsttDnsCodec {

  const val CLIENT_ID_LEN = 8
  const val NUM_PADDING = 3
  const val NUM_PADDING_FOR_POLL = 8
  const val MAX_PACKET_LEN = 223 // < 0xE0 (224)
  const val EDNS0_UDP_PAYLOAD_SIZE = 4096

  private const val DNS_TYPE_TXT = 16
  private const val DNS_TYPE_OPT = 41
  private const val DNS_CLASS_IN = 1
  private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

  /**
   * Computes the maximum raw KCP packet length that fits inside one DNS TXT query for [domain].
   */
  fun maxUpstreamKcpPacketSize(domain: String): Int {
    val domainLabels = parseDomainLabels(domain)
    if (domainLabels.isEmpty()) return 64
    // Wire length of suffix domain: sum(1 + label.length) + 1 (root null byte)
    val domainWireLen = domainLabels.sumOf { 1 + it.length } + 1
    // Max DNS wire name length is 255 octets
    val availableWireBytes = (255 - domainWireLen).coerceAtLeast(0)
    // Each 63-byte label consumes 64 wire bytes (1 length octet + 63 chars)
    val fullLabels = availableWireBytes / 64
    val remWire = availableWireBytes % 64
    val remChars = if (remWire >= 2) remWire - 1 else 0
    val maxBase32Chars = fullLabels * 63 + remChars
    val maxDecodedBytes = (maxBase32Chars * 5) / 8
    // Overhead inside decoded payload: ClientID(8) + padTag(1) + NUM_PADDING(3) + pktLen(1)
    val maxKcpPayload = maxDecodedBytes - (CLIENT_ID_LEN + 1 + NUM_PADDING + 1)
    return maxKcpPayload.coerceIn(48, MAX_PACKET_LEN)
  }

  /**
   * Packs [clientId] (8 bytes), random cache-busting padding, and optional KCP [packet]
   * in the exact byte layout expected by `dnstt-server`.
   */
  fun encodeUpstreamPayload(
    clientId: ByteArray,
    packet: ByteArray = ByteArray(0),
    paddingBytesOverride: ByteArray? = null,
  ): ByteArray {
    require(clientId.size == CLIENT_ID_LEN) { "ClientID must be 8 bytes" }
    require(packet.size <= MAX_PACKET_LEN) {
      "Upstream packet ${packet.size} exceeds max $MAX_PACKET_LEN bytes"
    }
    val padCount = if (packet.isEmpty()) NUM_PADDING_FOR_POLL else NUM_PADDING
    val padding =
      paddingBytesOverride?.also { require(it.size == padCount) } ?: DnsttCrypto.randomBytes(padCount)

    val out = ByteArrayOutputStream(CLIENT_ID_LEN + 1 + padCount + 1 + packet.size)
    out.write(clientId)
    out.write(224 + padCount)
    out.write(padding)
    if (packet.isNotEmpty()) {
      out.write(packet.size)
      out.write(packet)
    }
    return out.toByteArray()
  }

  /**
   * Decodes an upstream DNSTT payload (used in unit tests / mock dnstt-server verification).
   */
  internal fun decodeUpstreamPayload(raw: ByteArray): Pair<ByteArray, List<ByteArray>> {
    require(raw.size >= CLIENT_ID_LEN) { "Upstream payload shorter than ClientID" }
    val clientId = raw.copyOfRange(0, CLIENT_ID_LEN)
    val packets = mutableListOf<ByteArray>()
    var pos = CLIENT_ID_LEN
    while (pos < raw.size) {
      val tag = raw[pos++].toInt() and 0xFF
      if (tag >= 224) {
        val padLen = tag - 224
        if (pos + padLen > raw.size) break
        pos += padLen
      } else {
        if (pos + tag > raw.size) break
        packets.add(raw.copyOfRange(pos, pos + tag))
        pos += tag
      }
    }
    return clientId to packets
  }

  /** Builds a complete UDP DNS TXT query packet with EDNS0 OPT record. */
  fun buildDnsTxtQuery(
    txId: Int,
    clientId: ByteArray,
    domain: String,
    kcpPacket: ByteArray = ByteArray(0),
  ): ByteArray {
    val rawPayload = encodeUpstreamPayload(clientId = clientId, packet = kcpPacket)
    val encoded = encodeBase32LowerNoPad(rawPayload)
    val dataLabels = encoded.chunked(63)
    val domainLabels = parseDomainLabels(domain)
    val allLabels = dataLabels + domainLabels

    val out = ByteArrayOutputStream(320)
    // Header (12 bytes): ID, Flags=0x0100 (RD=1), QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=1 (OPT)
    writeU16BE(out, txId and 0xFFFF)
    writeU16BE(out, 0x0100)
    writeU16BE(out, 1)
    writeU16BE(out, 0)
    writeU16BE(out, 0)
    writeU16BE(out, 1)

    // Question section
    for (label in allLabels) {
      val bytes = label.toByteArray(StandardCharsets.US_ASCII)
      out.write(bytes.size)
      out.write(bytes)
    }
    out.write(0) // root label
    writeU16BE(out, DNS_TYPE_TXT)
    writeU16BE(out, DNS_CLASS_IN)

    // EDNS0 OPT pseudo-RR (RFC 6891)
    out.write(0) // root name
    writeU16BE(out, DNS_TYPE_OPT)
    writeU16BE(out, EDNS0_UDP_PAYLOAD_SIZE)
    out.write(0) // extended RCODE
    out.write(0) // EDNS version 0
    writeU16BE(out, 0) // Z flags
    writeU16BE(out, 0) // RDLENGTH = 0

    return out.toByteArray()
  }

  /**
   * Parses a DNS response packet, validates RCODE == NOERROR, extracts TXT answer RDATA,
   * and returns the list of `uint16 BE` length-prefixed downstream KCP packets.
   * Returns `null` if the response is invalid or has a non-zero RCODE (e.g. NXDOMAIN/SERVFAIL).
   */
  fun parseDnsTxtResponsePackets(response: ByteArray, expectedTxId: Int? = null): List<ByteArray>? {
    if (response.size < 12) return null
    val txId = readU16BE(response, 0)
    if (expectedTxId != null && txId != (expectedTxId and 0xFFFF)) return null

    val flags = readU16BE(response, 2)
    val isResponse = (flags and 0x8000) != 0
    val rcode = flags and 0x000F
    if (!isResponse || rcode != 0) return null

    val qdCount = readU16BE(response, 4)
    val anCount = readU16BE(response, 6)
    var offset = 12

    // Skip Question section
    repeat(qdCount) {
      offset = skipDnsName(response, offset) ?: return null
      if (offset + 4 > response.size) return null
      offset += 4 // QTYPE + QCLASS
    }

    val packets = mutableListOf<ByteArray>()
    repeat(anCount) {
      offset = skipDnsName(response, offset) ?: return null
      if (offset + 10 > response.size) return null
      val rrType = readU16BE(response, offset)
      val rdLength = readU16BE(response, offset + 8)
      offset += 10
      if (offset + rdLength > response.size) return null

      if (rrType == DNS_TYPE_TXT) {
        val txtStream = decodeTxtRdata(response, offset, rdLength) ?: return null
        var pos = 0
        while (pos + 2 <= txtStream.size) {
          val pktLen = readU16BE(txtStream, pos)
          pos += 2
          if (pos + pktLen > txtStream.size) break
          if (pktLen > 0) {
            packets.add(txtStream.copyOfRange(pos, pos + pktLen))
          }
          pos += pktLen
        }
      }
      offset += rdLength
    }
    return packets
  }

  /**
   * Builds a valid DNS TXT response for a query — used by JVM unit tests to simulate `dnstt-server`.
   */
  internal fun buildMockDnsTxtResponse(
    queryPacket: ByteArray,
    downstreamKcpPackets: List<ByteArray>,
  ): ByteArray {
    val txId = readU16BE(queryPacket, 0)
    val questionEnd = (skipDnsName(queryPacket, 12) ?: 12) + 4
    val questionBytes = queryPacket.copyOfRange(12, questionEnd)

    // Pack downstream KCP packets with uint16 BE length prefixes
    val payloadStream = ByteArrayOutputStream()
    for (pkt in downstreamKcpPackets) {
      writeU16BE(payloadStream, pkt.size)
      payloadStream.write(pkt)
    }
    val payloadBytes = payloadStream.toByteArray()

    // Encode as DNS TXT character-strings (max 255 bytes per string)
    val txtRdata = ByteArrayOutputStream()
    if (payloadBytes.isEmpty()) {
      txtRdata.write(0)
    } else {
      var pos = 0
      while (pos < payloadBytes.size) {
        val chunk = minOf(255, payloadBytes.size - pos)
        txtRdata.write(chunk)
        txtRdata.write(payloadBytes, pos, chunk)
        pos += chunk
      }
    }
    val rdataBytes = txtRdata.toByteArray()

    val out = ByteArrayOutputStream()
    writeU16BE(out, txId)
    writeU16BE(out, 0x8180) // QR=1, RD=1, RA=1, NOERROR
    writeU16BE(out, 1) // QDCOUNT
    writeU16BE(out, 1) // ANCOUNT
    writeU16BE(out, 0) // NSCOUNT
    writeU16BE(out, 0) // ARCOUNT
    out.write(questionBytes)
    // Answer name pointer to offset 12 (0xC00C)
    writeU16BE(out, 0xC00C)
    writeU16BE(out, DNS_TYPE_TXT)
    writeU16BE(out, DNS_CLASS_IN)
    // TTL = 0
    out.write(0)
    out.write(0)
    out.write(0)
    out.write(0)
    writeU16BE(out, rdataBytes.size)
    out.write(rdataBytes)
    return out.toByteArray()
  }

  /** Extracts QNAME labels from a DNS query (used by mock `dnstt-server` tests). */
  internal fun extractQueryLabels(packet: ByteArray): List<String>? {
    if (packet.size < 13) return null
    val labels = mutableListOf<String>()
    var offset = 12
    while (offset < packet.size) {
      val len = packet[offset++].toInt() and 0xFF
      if (len == 0) return labels
      if ((len and 0xC0) != 0 || offset + len > packet.size) return null
      labels.add(String(packet, offset, len, StandardCharsets.US_ASCII))
      offset += len
    }
    return null
  }

  fun encodeBase32LowerNoPad(data: ByteArray): String {
    if (data.isEmpty()) return ""
    val out = StringBuilder((data.size * 8 + 4) / 5)
    var buffer = 0
    var bitsLeft = 0
    for (b in data) {
      buffer = (buffer shl 8) or (b.toInt() and 0xFF)
      bitsLeft += 8
      while (bitsLeft >= 5) {
        bitsLeft -= 5
        val idx = (buffer ushr bitsLeft) and 0x1F
        out.append(BASE32_ALPHABET[idx])
      }
    }
    if (bitsLeft > 0) {
      val idx = (buffer shl (5 - bitsLeft)) and 0x1F
      out.append(BASE32_ALPHABET[idx])
    }
    return out.toString()
  }

  internal fun decodeBase32NoPad(encoded: String): ByteArray {
    val clean = encoded.lowercase()
    val out = ByteArrayOutputStream((clean.length * 5) / 8)
    var buffer = 0
    var bitsLeft = 0
    for (ch in clean) {
      val v =
        when (ch) {
          in 'a'..'z' -> ch - 'a'
          in '2'..'7' -> (ch - '2') + 26
          else -> continue
        }
      buffer = (buffer shl 5) or v
      bitsLeft += 5
      if (bitsLeft >= 8) {
        bitsLeft -= 8
        out.write((buffer ushr bitsLeft) and 0xFF)
      }
    }
    return out.toByteArray()
  }

  fun parseDomainLabels(domain: String): List<String> {
    return domain.trim().trimEnd('.').split('.').filter { it.isNotBlank() }
  }

  private fun decodeTxtRdata(packet: ByteArray, offset: Int, rdLength: Int): ByteArray? {
    val end = offset + rdLength
    val out = ByteArrayOutputStream(rdLength)
    var pos = offset
    while (pos < end) {
      val strLen = packet[pos++].toInt() and 0xFF
      if (pos + strLen > end) return null
      out.write(packet, pos, strLen)
      pos += strLen
    }
    return out.toByteArray()
  }

  private fun skipDnsName(packet: ByteArray, startOffset: Int): Int? {
    var offset = startOffset
    var jumps = 0
    while (offset < packet.size && jumps < 64) {
      val len = packet[offset].toInt() and 0xFF
      when {
        len == 0 -> return offset + 1
        (len and 0xC0) == 0xC0 -> {
          if (offset + 2 > packet.size) return null
          return offset + 2
        }
        else -> {
          offset += 1 + len
          if (offset > packet.size) return null
        }
      }
      jumps += 1
    }
    return null
  }

  private fun readU16BE(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
  }

  private fun writeU16BE(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
  }
}
