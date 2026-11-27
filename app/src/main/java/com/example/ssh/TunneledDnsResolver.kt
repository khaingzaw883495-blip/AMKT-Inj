package com.example.ssh

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performs RFC 1035 DNS-over-TCP lookups strictly through [TunnelConnector] so zero DNS traffic
 * ever leaks outside the active VPN tunnel, and resolves IPv6-only targets (e.g., Facebook
 * `2a03:2880:...` on IPv4-only VPS servers) to equivalent IPv4 endpoints inside the tunnel.
 */
internal object TunneledDnsResolver {
  private const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L
  private const val MAX_DNS_TCP_MSG_BYTES = 65_535

  private val PRIMARY_TUNNEL_DNS = listOf("1.1.1.1", "8.8.8.8")
  private val txIdGen = AtomicInteger(0x3100)

  private data class CachedEntry(
    val ipv4: String,
    val expiresAtMs: Long,
  )

  private val ipv6ToIpv4Cache = ConcurrentHashMap<String, CachedEntry>()
  private val domainToIpv4Cache = ConcurrentHashMap<String, CachedEntry>()

  fun normalizeHostLiteral(rawHost: String): String {
    val trimmed = rawHost.trim().trim('[', ']')
    val noScope = trimmed.substringBefore('%')
    if (noScope.isBlank()) return noScope

    // Convert IPv4-mapped IPv6 literals (::ffff:a.b.c.d) directly into dotted IPv4 strings.
    if (noScope.contains(':')) {
      val parsed = runCatching { InetAddress.getByName(noScope) }.getOrNull()
      if (parsed is Inet4Address) {
        return parsed.hostAddress ?: noScope
      }
      if (parsed is Inet6Address) {
        val addr = parsed.address
        if (isIpv4MappedBytes(addr)) {
          return "${addr[12].toInt() and 0xFF}.${addr[13].toInt() and 0xFF}.${addr[14].toInt() and 0xFF}.${addr[15].toInt() and 0xFF}"
        }
      }
    }
    return noScope
  }

  fun isIpv6Literal(host: String): Boolean {
    val clean = host.trim().trim('[', ']').substringBefore('%')
    if (!clean.contains(':')) return false
    val addr = runCatching { InetAddress.getByName(clean) }.getOrNull()
    return addr is Inet6Address && !isIpv4MappedBytes(addr.address)
  }

  fun isMetaIpv6Address(host: String): Boolean {
    val clean = host.trim().trim('[', ']').substringBefore('%')
    val addr = runCatching { InetAddress.getByName(clean) }.getOrNull() as? Inet6Address ?: return false
    val bytes = addr.address
    // 2a03:2880::/32 (Meta / Facebook / Instagram / WhatsApp IPv6 range)
    return (bytes[0].toInt() and 0xFF) == 0x2A &&
      (bytes[1].toInt() and 0xFF) == 0x03 &&
      (bytes[2].toInt() and 0xFF) == 0x28 &&
      (bytes[3].toInt() and 0xFF) == 0x80
  }

  fun normalizeDnsServerForTunnel(rawHost: String): String {
    val clean = normalizeHostLiteral(rawHost)
    return when {
      clean == "198.18.0.2" || clean == "198.18.0.1" || clean == "127.0.0.1" -> "1.1.1.1"
      clean == "2606:4700:4700::1111" || clean == "2606:4700:4700::1001" -> "1.1.1.1"
      clean == "2001:4860:4860::8888" || clean == "2001:4860:4860::8844" -> "8.8.8.8"
      isIpv6Literal(clean) -> "1.1.1.1"
      else -> clean
    }
  }

  fun getCachedIpv4ForIpv6(ipv6Literal: String): String? {
    val key = normalizeHostLiteral(ipv6Literal).lowercase()
    val entry = ipv6ToIpv4Cache[key] ?: return null
    if (System.currentTimeMillis() > entry.expiresAtMs) {
      ipv6ToIpv4Cache.remove(key, entry)
      return null
    }
    return entry.ipv4
  }

  fun cacheIpv6ToIpv4(ipv6Literal: String, ipv4: String) {
    val key = normalizeHostLiteral(ipv6Literal).lowercase()
    ipv6ToIpv4Cache[key] = CachedEntry(ipv4, System.currentTimeMillis() + DNS_CACHE_TTL_MS)
  }

  /**
   * Sends a raw UDP-style DNS message over TCP (`2-byte length + DNS query`) through [connector]
   * and returns the raw DNS response packet.
   */
  @Throws(IOException::class)
  fun sendDnsQueryOverTcp(
    connector: TunnelConnector,
    dnsServerHost: String,
    dnsServerPort: Int,
    dnsQueryBytes: ByteArray,
    clientPort: Int = 0,
  ): ByteArray {
    val targetDns = normalizeDnsServerForTunnel(dnsServerHost)
    val candidates =
      if (targetDns in PRIMARY_TUNNEL_DNS) {
        listOf(targetDns) + PRIMARY_TUNNEL_DNS.filter { it != targetDns }
      } else {
        listOf(targetDns) + PRIMARY_TUNNEL_DNS
      }

    var lastError: IOException? = null
    for (dnsHost in candidates) {
      try {
        connector.openChannel(dnsHost, dnsServerPort.coerceIn(1, 65535), clientPort).use { ch ->
          val out = ch.outputStream
          val framed = ByteArray(2 + dnsQueryBytes.size)
          framed[0] = ((dnsQueryBytes.size ushr 8) and 0xFF).toByte()
          framed[1] = (dnsQueryBytes.size and 0xFF).toByte()
          System.arraycopy(dnsQueryBytes, 0, framed, 2, dnsQueryBytes.size)
          out.write(framed)
          out.flush()

          val inp = DataInputStream(ch.inputStream)
          val respLen = inp.readUnsignedShort()
          if (respLen <= 0 || respLen > MAX_DNS_TCP_MSG_BYTES) {
            throw IOException("Invalid DNS-over-TCP response length: $respLen")
          }
          val resp = ByteArray(respLen)
          inp.readFully(resp)
          return resp
        }
      } catch (e: IOException) {
        lastError = e
      }
    }
    throw lastError ?: IOException("Tunneled DNS query failed")
  }

  /**
   * Resolves a domain name's IPv4 (`A`) address through the tunnel via DNS-over-TCP.
   */
  fun resolveIpv4ViaTunnel(connector: TunnelConnector, domain: String): String? {
    val cleanDomain = domain.trim().trimEnd('.').lowercase()
    if (cleanDomain.isBlank() || cleanDomain.contains(':')) return null

    domainToIpv4Cache[cleanDomain]?.let { cached ->
      if (System.currentTimeMillis() <= cached.expiresAtMs) return cached.ipv4
      domainToIpv4Cache.remove(cleanDomain, cached)
    }

    val query = buildDnsQuery(qname = cleanDomain, qtype = QTYPE_A)
    val resp =
      runCatching {
        sendDnsQueryOverTcp(connector, PRIMARY_TUNNEL_DNS.first(), 53, query)
      }.getOrNull() ?: return null

    val ipv4 = parseFirstARecord(resp) ?: return null
    domainToIpv4Cache[cleanDomain] = CachedEntry(ipv4, System.currentTimeMillis() + DNS_CACHE_TTL_MS)
    return ipv4
  }

  /**
   * Resolves an IPv6 destination (such as Facebook's `2a03:2880:f348:1:face:b00c:0:25de`)
   * to an equivalent IPv4 destination strictly through the VPN tunnel (`PTR` -> `A`),
   * without leaking any DNS query to the local ISP network.
   */
  fun resolveIpv6ToIpv4ViaTunnel(connector: TunnelConnector, ipv6Literal: String): String? {
    val normalized = normalizeHostLiteral(ipv6Literal)
    if (!isIpv6Literal(normalized)) {
      return normalized.takeIf { it.isNotBlank() && !it.contains(':') }
    }

    getCachedIpv4ForIpv6(normalized)?.let { return it }

    val inet6 = runCatching { InetAddress.getByName(normalized) }.getOrNull() as? Inet6Address ?: return null
    val ptrName = buildIp6ArpaName(inet6.address)

    val ptrDomain =
      runCatching {
        val ptrQuery = buildDnsQuery(qname = ptrName, qtype = QTYPE_PTR)
        val ptrResp = sendDnsQueryOverTcp(connector, PRIMARY_TUNNEL_DNS.first(), 53, ptrQuery)
        parseFirstPtrRecord(ptrResp)
      }.getOrNull()

    val candidateHostnames = buildCandidateIpv4Hostnames(ptrDomain, isMeta = isMetaIpv6Address(normalized))
    for (candidate in candidateHostnames) {
      val resolved = resolveIpv4ViaTunnel(connector, candidate)
      if (resolved != null) {
        cacheIpv6ToIpv4(normalized, resolved)
        return resolved
      }
    }
    return null
  }

  internal fun buildCandidateIpv4Hostnames(ptrDomain: String?, isMeta: Boolean): List<String> {
    val out = LinkedHashSet<String>()
    val cleanPtr = ptrDomain?.trim()?.trimEnd('.')?.lowercase().orEmpty()
    if (cleanPtr.isNotBlank()) {
      // Rewrite common IPv6-only edge hostnames to their dual-stack/IPv4 counterparts first
      if (cleanPtr.contains("mini6")) {
        out.add(cleanPtr.replace("mini6", "mini"))
      }
      if (cleanPtr.contains("-v6.")) {
        out.add(cleanPtr.replace("-v6.", "-v4."))
        out.add(cleanPtr.replace("-v6.", "."))
      }
      if (cleanPtr.contains("sin6.")) {
        out.add(cleanPtr.replace("sin6.", "sin."))
      }
      if (cleanPtr.contains("v6.")) {
        out.add(cleanPtr.replace("v6.", ""))
      }
      out.add(cleanPtr)
    }

    val isMetaHost =
      isMeta ||
        cleanPtr.endsWith(".facebook.com") ||
        cleanPtr.endsWith(".fbcdn.net") ||
        cleanPtr.endsWith(".instagram.com") ||
        cleanPtr.endsWith(".whatsapp.net")
    if (isMetaHost) {
      if (cleanPtr.endsWith(".fbcdn.net")) {
        out.add("scontent.xx.fbcdn.net")
        out.add("static.xx.fbcdn.net")
      }
      if (cleanPtr.endsWith(".instagram.com")) {
        out.add("i.instagram.com")
      }
      if (cleanPtr.endsWith(".whatsapp.net")) {
        out.add("g.whatsapp.net")
      }
      out.add("star-mini.c10r.facebook.com")
      out.add("www.facebook.com")
    }
    return out.toList()
  }

  internal fun buildIp6ArpaName(ipv6Bytes: ByteArray): String {
    require(ipv6Bytes.size == 16)
    val sb = StringBuilder(74)
    for (i in 15 downTo 0) {
      val b = ipv6Bytes[i].toInt() and 0xFF
      sb.append(Character.forDigit(b and 0x0F, 16)).append('.')
      sb.append(Character.forDigit((b ushr 4) and 0x0F, 16)).append('.')
    }
    sb.append("ip6.arpa")
    return sb.toString()
  }

  internal fun buildDnsQuery(qname: String, qtype: Int): ByteArray {
    val txId = txIdGen.incrementAndGet() and 0xFFFF
    val out = ByteArrayOutputStream(64)
    // Header: ID, RD=1, QDCOUNT=1
    out.write((txId ushr 8) and 0xFF)
    out.write(txId and 0xFF)
    out.write(0x01) // RD = 1
    out.write(0x00)
    out.write(0x00)
    out.write(0x01) // QDCOUNT = 1
    out.write(0x00)
    out.write(0x00) // ANCOUNT = 0
    out.write(0x00)
    out.write(0x00) // NSCOUNT = 0
    out.write(0x00)
    out.write(0x00) // ARCOUNT = 0

    for (label in qname.trimEnd('.').split('.')) {
      val bytes = label.toByteArray(Charsets.US_ASCII)
      if (bytes.isEmpty() || bytes.size > 63) continue
      out.write(bytes.size)
      out.write(bytes)
    }
    out.write(0x00) // root terminator
    out.write((qtype ushr 8) and 0xFF)
    out.write(qtype and 0xFF)
    out.write(0x00)
    out.write(0x01) // QCLASS = IN
    return out.toByteArray()
  }

  internal fun parseFirstARecord(packet: ByteArray): String? {
    if (packet.size < 12) return null
    val qdCount = readU16(packet, 4)
    val anCount = readU16(packet, 6)
    if (anCount <= 0) return null

    var offset = 12
    for (i in 0 until qdCount) {
      offset = skipDnsName(packet, offset) ?: return null
      if (offset + 4 > packet.size) return null
      offset += 4 // QTYPE + QCLASS
    }

    for (i in 0 until anCount) {
      offset = skipDnsName(packet, offset) ?: return null
      if (offset + 10 > packet.size) return null
      val type = readU16(packet, offset)
      val clazz = readU16(packet, offset + 2)
      val rdLength = readU16(packet, offset + 8)
      offset += 10
      if (offset + rdLength > packet.size) return null
      if (type == QTYPE_A && clazz == 1 && rdLength == 4) {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}.${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
      }
      offset += rdLength
    }
    return null
  }

  internal fun parseFirstPtrRecord(packet: ByteArray): String? {
    if (packet.size < 12) return null
    val qdCount = readU16(packet, 4)
    val anCount = readU16(packet, 6)
    if (anCount <= 0) return null

    var offset = 12
    for (i in 0 until qdCount) {
      offset = skipDnsName(packet, offset) ?: return null
      if (offset + 4 > packet.size) return null
      offset += 4
    }

    for (i in 0 until anCount) {
      offset = skipDnsName(packet, offset) ?: return null
      if (offset + 10 > packet.size) return null
      val type = readU16(packet, offset)
      val clazz = readU16(packet, offset + 2)
      val rdLength = readU16(packet, offset + 8)
      offset += 10
      if (offset + rdLength > packet.size) return null
      if (type == QTYPE_PTR && clazz == 1) {
        return readDnsName(packet, offset)
      }
      offset += rdLength
    }
    return null
  }

  private fun skipDnsName(packet: ByteArray, startOffset: Int): Int? {
    var pos = startOffset
    var steps = 0
    while (pos < packet.size && steps++ < 128) {
      val len = packet[pos].toInt() and 0xFF
      if (len == 0) return pos + 1
      if ((len and 0xC0) == 0xC0) {
        return if (pos + 2 <= packet.size) pos + 2 else null
      }
      pos += 1 + len
    }
    return null
  }

  private fun readDnsName(packet: ByteArray, startOffset: Int): String? {
    val labels = ArrayList<String>()
    var pos = startOffset
    var steps = 0
    while (pos < packet.size && steps++ < 128) {
      val len = packet[pos].toInt() and 0xFF
      if (len == 0) break
      if ((len and 0xC0) == 0xC0) {
        if (pos + 1 >= packet.size) return null
        val ptr = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
        if (ptr >= packet.size) return null
        pos = ptr
      } else {
        pos += 1
        if (pos + len > packet.size) return null
        labels.add(String(packet, pos, len, Charsets.US_ASCII))
        pos += len
      }
    }
    return if (labels.isEmpty()) null else labels.joinToString(".")
  }

  private fun readU16(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

  private fun isIpv4MappedBytes(addr: ByteArray): Boolean {
    if (addr.size != 16) return false
    for (i in 0 until 10) if (addr[i] != 0.toByte()) return false
    return addr[10] == 0xFF.toByte() && addr[11] == 0xFF.toByte()
  }

  private const val QTYPE_A = 1
  private const val QTYPE_PTR = 12
}
