package com.example.vless

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Decoded VLESS v0 binary request header (used for protocol verification & testing).
 */
data class DecodedVlessRequestHeader(
  val version: Int,
  val uuidBytes: ByteArray,
  val addons: ByteArray,
  val command: Int,
  val port: Int,
  val addressType: Int,
  val targetHost: String,
  val headerLength: Int,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DecodedVlessRequestHeader) return false
    return version == other.version &&
      uuidBytes.contentEquals(other.uuidBytes) &&
      addons.contentEquals(other.addons) &&
      command == other.command &&
      port == other.port &&
      addressType == other.addressType &&
      targetHost == other.targetHost &&
      headerLength == other.headerLength
  }

  override fun hashCode(): Int {
    var result = version
    result = 31 * result + uuidBytes.contentHashCode()
    result = 31 * result + addons.contentHashCode()
    result = 31 * result + command
    result = 31 * result + port
    result = 31 * result + addressType
    result = 31 * result + targetHost.hashCode()
    result = 31 * result + headerLength
    return result
  }
}

/**
 * Decoded VLESS v0 binary response header sent by the server before downstream payload bytes.
 */
data class VlessResponseHeader(
  val version: Int,
  val addons: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is VlessResponseHeader) return false
    return version == other.version && addons.contentEquals(other.addons)
  }

  override fun hashCode(): Int {
    var result = version
    result = 31 * result + addons.contentHashCode()
    return result
  }
}

/**
 * VLESS v0 binary wire protocol encoder & decoder (Xray-core / V2Ray specification).
 *
 * Request layout:
 * `[Version: 1B (0x00)] [UUID: 16B] [AddonsLen: 1B] [Addons: N B] [Command: 1B (1=TCP)]
 *  [Port: 2B BE] [AddrType: 1B (1=IPv4, 2=Domain, 3=IPv6)] [Address: 4B | (1B len + domain) | 16B]`
 *
 * Response layout:
 * `[Version: 1B (0x00)] [AddonsLen: 1B] [Addons: N B]`
 */
object VlessProtocol {
  const val VERSION_0: Byte = 0x00
  const val COMMAND_TCP: Byte = 0x01
  const val COMMAND_UDP: Byte = 0x02

  const val ADDRESS_TYPE_IPV4: Byte = 0x01
  const val ADDRESS_TYPE_DOMAIN: Byte = 0x02
  const val ADDRESS_TYPE_IPV6: Byte = 0x03

  private val IPV4_LITERAL_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
  private val HEX_32_REGEX = Regex("^[0-9a-fA-F]{32}$")

  /**
   * Parses a standard hyphenated UUID (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) or a 32-character
   * hex UUID string into its exact 16-byte big-endian wire representation.
   */
  @Throws(IllegalArgumentException::class)
  fun parseUuidBytes(uuidString: String): ByteArray {
    val trimmed = uuidString.trim()
    require(trimmed.isNotEmpty()) { "UUID cannot be empty" }

    val normalizedHex =
      if (HEX_32_REGEX.matches(trimmed)) {
        trimmed
      } else {
        val parsed = UUID.fromString(trimmed)
        val bb = ByteBuffer.allocate(16)
        bb.putLong(parsed.mostSignificantBits)
        bb.putLong(parsed.leastSignificantBits)
        return bb.array()
      }

    val bytes = ByteArray(16)
    for (i in 0 until 16) {
      val hi = Character.digit(normalizedHex[i * 2], 16)
      val lo = Character.digit(normalizedHex[i * 2 + 1], 16)
      require(hi >= 0 && lo >= 0) { "Invalid hex character in UUID" }
      bytes[i] = ((hi shl 4) or lo).toByte()
    }
    return bytes
  }

  /**
   * Encodes a VLESS v0 request header for connecting to [targetHost]:[targetPort].
   */
  @Throws(IllegalArgumentException::class)
  fun encodeRequestHeader(
    uuidBytes: ByteArray,
    targetHost: String,
    targetPort: Int,
    command: Byte = COMMAND_TCP,
    addons: ByteArray = ByteArray(0),
  ): ByteArray {
    require(uuidBytes.size == 16) { "VLESS UUID must be exactly 16 bytes (was ${uuidBytes.size})" }
    require(targetPort in 1..65535) { "Target port must be in 1..65535 (was $targetPort)" }
    require(addons.size <= 255) { "VLESS addons length cannot exceed 255 bytes" }

    val cleanHost = targetHost.trim().trim('[', ']')
    require(cleanHost.isNotEmpty()) { "Target host cannot be empty" }

    val out = ByteArrayOutputStream(32 + cleanHost.length)
    // 1. Version (0x00)
    out.write(VERSION_0.toInt())
    // 2. 16-byte UUID
    out.write(uuidBytes)
    // 3. Addons length + addons
    out.write(addons.size and 0xFF)
    if (addons.isNotEmpty()) {
      out.write(addons)
    }
    // 4. Command (1 = TCP, 2 = UDP)
    out.write(command.toInt() and 0xFF)
    // 5. Big-endian 16-bit port
    out.write((targetPort ushr 8) and 0xFF)
    out.write(targetPort and 0xFF)

    // 6. Address type + address bytes
    val ipv4Bytes = parseIpv4LiteralOrNull(cleanHost)
    when {
      ipv4Bytes != null -> {
        out.write(ADDRESS_TYPE_IPV4.toInt())
        out.write(ipv4Bytes)
      }
      cleanHost.contains(':') -> {
        val addr = InetAddress.getByName(cleanHost)
        require(addr is Inet6Address) { "Invalid IPv6 literal: $cleanHost" }
        out.write(ADDRESS_TYPE_IPV6.toInt())
        out.write(addr.address)
      }
      else -> {
        val domainBytes = cleanHost.toByteArray(StandardCharsets.US_ASCII)
        require(domainBytes.size in 1..255) {
          "Domain length must be 1..255 bytes (was ${domainBytes.size})"
        }
        out.write(ADDRESS_TYPE_DOMAIN.toInt())
        out.write(domainBytes.size and 0xFF)
        out.write(domainBytes)
      }
    }

    return out.toByteArray()
  }

  /**
   * Decodes a VLESS v0 request header from [bytes] (starting at offset 0).
   */
  @Throws(IOException::class)
  fun decodeRequestHeader(bytes: ByteArray): DecodedVlessRequestHeader {
    var idx = 0
    if (bytes.size < 22) {
      throw IOException("VLESS request header too short (${bytes.size} bytes)")
    }

    val version = bytes[idx++].toInt() and 0xFF
    if (version != 0) {
      throw IOException("Unsupported VLESS version: $version")
    }

    val uuidBytes = bytes.copyOfRange(idx, idx + 16)
    idx += 16

    val addonsLen = bytes[idx++].toInt() and 0xFF
    if (idx + addonsLen + 4 > bytes.size) {
      throw IOException("Truncated VLESS request header (addonsLen=$addonsLen)")
    }
    val addons = bytes.copyOfRange(idx, idx + addonsLen)
    idx += addonsLen

    val command = bytes[idx++].toInt() and 0xFF
    val port = ((bytes[idx].toInt() and 0xFF) shl 8) or (bytes[idx + 1].toInt() and 0xFF)
    idx += 2

    val addressType = bytes[idx++].toInt() and 0xFF
    val targetHost =
      when (addressType) {
        ADDRESS_TYPE_IPV4.toInt() -> {
          if (idx + 4 > bytes.size) throw IOException("Truncated VLESS IPv4 address")
          val addr = bytes.copyOfRange(idx, idx + 4)
          idx += 4
          InetAddress.getByAddress(addr).hostAddress ?: throw IOException("Invalid IPv4 bytes")
        }
        ADDRESS_TYPE_DOMAIN.toInt() -> {
          if (idx >= bytes.size) throw IOException("Truncated VLESS domain length")
          val len = bytes[idx++].toInt() and 0xFF
          if (idx + len > bytes.size) throw IOException("Truncated VLESS domain name")
          val domain = String(bytes, idx, len, StandardCharsets.US_ASCII)
          idx += len
          domain
        }
        ADDRESS_TYPE_IPV6.toInt() -> {
          if (idx + 16 > bytes.size) throw IOException("Truncated VLESS IPv6 address")
          val addr = bytes.copyOfRange(idx, idx + 16)
          idx += 16
          InetAddress.getByAddress(addr).hostAddress ?: throw IOException("Invalid IPv6 bytes")
        }
        else -> throw IOException("Unsupported VLESS address type: $addressType")
      }

    return DecodedVlessRequestHeader(
      version = version,
      uuidBytes = uuidBytes,
      addons = addons,
      command = command,
      port = port,
      addressType = addressType,
      targetHost = targetHost,
      headerLength = idx,
    )
  }

  /**
   * Decodes the VLESS v0 response header (`[version: 1B] [addonsLen: 1B] [addons: N B]`) from [input].
   */
  @Throws(IOException::class)
  fun decodeResponseHeader(input: InputStream): VlessResponseHeader {
    val version = input.read()
    if (version == -1) {
      throw EOFException("VLESS ဆာဗာမှ Response Header မရရှိဘဲ ချိတ်ဆက်မှု ပိတ်သွားပါသည် (EOF)")
    }
    if (version != VERSION_0.toInt()) {
      throw IOException("VLESS Response Version မမှန်ကန်ပါ: $version")
    }
    val addonsLen = input.read()
    if (addonsLen == -1) {
      throw EOFException("VLESS Response Addons Length မရရှိဘဲ EOF ဖြစ်သွားပါသည်")
    }
    val addons = ByteArray(addonsLen)
    var readTotal = 0
    while (readTotal < addonsLen) {
      val n = input.read(addons, readTotal, addonsLen - readTotal)
      if (n == -1) {
        throw EOFException("VLESS Response Addons ဖတ်နေစဉ် EOF ဖြစ်သွားပါသည်")
      }
      readTotal += n
    }
    return VlessResponseHeader(version = version, addons = addons)
  }

  /**
   * Encodes a VLESS v0 response header (useful for server-side mocks in unit tests).
   */
  fun encodeResponseHeader(addons: ByteArray = ByteArray(0)): ByteArray {
    require(addons.size <= 255) { "Addons length cannot exceed 255 bytes" }
    val out = ByteArray(2 + addons.size)
    out[0] = VERSION_0
    out[1] = (addons.size and 0xFF).toByte()
    if (addons.isNotEmpty()) {
      System.arraycopy(addons, 0, out, 2, addons.size)
    }
    return out
  }

  private fun parseIpv4LiteralOrNull(host: String): ByteArray? {
    if (!IPV4_LITERAL_REGEX.matches(host)) return null
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (i in 0 until 4) {
      val octet = parts[i].toIntOrNull() ?: return null
      if (octet !in 0..255) return null
      bytes[i] = octet.toByte()
    }
    val addr = InetAddress.getByAddress(bytes)
    return if (addr is Inet4Address) bytes else null
  }
}
