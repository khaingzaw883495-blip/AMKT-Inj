package com.example.vpn

import java.io.File
import org.amnezia.awg.hevtunnel.TProxyService

data class NativeTunStats(
  val txPackets: Long = 0L,
  val txBytes: Long = 0L,
  val rxPackets: Long = 0L,
  val rxBytes: Long = 0L,
)

interface HevTunnelAdapter {
  fun start(configPath: String, tunFd: Int)

  fun stop()

  fun getStats(): NativeTunStats
}

/**
 * Wraps the open-source native hev-socks5-tunnel JNI library
 * (com.zaneschepke:hevtunnel, Apache-2.0 wrapper around heiher/hev-socks5-tunnel MIT).
 */
class NativeHevTunnelAdapter : HevTunnelAdapter {
  override fun start(configPath: String, tunFd: Int) {
    TProxyService.TProxyStartService(configPath, tunFd)
  }

  override fun stop() {
    TProxyService.TProxyStopService()
  }

  override fun getStats(): NativeTunStats {
    val raw = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: return NativeTunStats()
    if (raw.size < 4) return NativeTunStats()
    return NativeTunStats(
      txPackets = raw[0].coerceAtLeast(0L),
      txBytes = raw[1].coerceAtLeast(0L),
      rxPackets = raw[2].coerceAtLeast(0L),
      rxBytes = raw[3].coerceAtLeast(0L),
    )
  }
}

/**
 * Generates standard RFC local-only YAML configuration for hev-socks5-tunnel.
 *
 * Uses RFC 2544 local TUN address (198.18.0.1) and mapdns (198.18.0.2 -> 100.64.0.0/10)
 * so Android UDP DNS queries are converted into domain-based SOCKS5 CONNECT requests
 * resolved remotely through the SSH server with zero DNS leaks and full compatibility
 * with TCP-only SSH dynamic forwarding.
 */
object HevSocks5ConfigBuilder {
  const val TUN_IPV4 = "198.18.0.1"
  const val TUN_IPV4_PREFIX = 32
  const val TUN_IPV6 = "fc00::1"
  const val TUN_IPV6_PREFIX = 128
  const val TUN_DNS_IPV4 = "198.18.0.2"
  const val MAPDNS_NETWORK = "100.64.0.0"
  const val MAPDNS_NETMASK = "255.192.0.0"
  const val DEFAULT_MTU = 8500

  fun buildYaml(
    socksHost: String,
    socksPort: Int,
    mtu: Int = DEFAULT_MTU,
  ): String {
    val safePort = socksPort.coerceIn(1, 65535)
    val safeMtu = mtu.coerceIn(1280, 9000)
    return """
      tunnel:
        mtu: $safeMtu
        ipv4: $TUN_IPV4
        ipv6: '$TUN_IPV6'

      socks5:
        port: $safePort
        address: $socksHost
        udp: 'udp'

      mapdns:
        address: $TUN_DNS_IPV4
        port: 53
        network: $MAPDNS_NETWORK
        netmask: $MAPDNS_NETMASK
        cache-size: 10000

      misc:
        task-stack-size: 86016
        tcp-buffer-size: 65536
        connect-timeout: 10000
        tcp-read-write-timeout: 300000
        udp-read-write-timeout: 60000
        log-level: warn
    """.trimIndent() + "\n"
  }

  fun writeConfigFile(directory: File, socksHost: String, socksPort: Int): File {
    if (!directory.exists()) directory.mkdirs()
    val file = File(directory, "hev_socks5_tunnel.yml")
    file.writeText(buildYaml(socksHost = socksHost, socksPort = socksPort), Charsets.UTF_8)
    return file
  }
}
