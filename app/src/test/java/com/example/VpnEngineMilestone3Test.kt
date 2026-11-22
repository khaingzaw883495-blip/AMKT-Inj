package com.example

import com.example.vpn.AmktVpnService
import com.example.vpn.HevSocks5ConfigBuilder
import com.example.vpn.VpnRuntimeStateHolder
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEngineMilestone3Test {

  @Test
  fun hevSocks5YamlConfigContainsLocalSocks5AndMapDnsSettings() {
    val yaml = HevSocks5ConfigBuilder.buildYaml(socksHost = "127.0.0.1", socksPort = 1080)

    assertTrue(yaml.contains("address: 127.0.0.1"))
    assertTrue(yaml.contains("port: 1080"))
    assertTrue(yaml.contains("udp: 'tcp'"))
    assertTrue(yaml.contains("ipv4: 198.18.0.1"))
    assertTrue(yaml.contains("ipv6: 'fc00::1'"))
    assertTrue(yaml.contains("mapdns:"))
    assertTrue(yaml.contains("address: 198.18.0.2"))
    assertTrue(yaml.contains("network: 100.64.0.0"))
    assertTrue(yaml.contains("netmask: 255.192.0.0"))
  }

  @Test
  fun hevSocks5ConfigWriterCreatesYamlFileOnDisk() {
    val tempDir = File(System.getProperty("java.io.tmpdir"), "amkt_hev_test_${System.nanoTime()}")
    try {
      val file = HevSocks5ConfigBuilder.writeConfigFile(tempDir, "127.0.0.1", 1082)
      assertTrue(file.exists())
      val content = file.readText()
      assertTrue(content.contains("port: 1082"))
      assertTrue(content.contains("address: 127.0.0.1"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun autoReconnectBackoffIsBoundedAndMonotonic() {
    assertEquals(2, AmktVpnService.calculateBackoffSeconds(1))
    assertEquals(4, AmktVpnService.calculateBackoffSeconds(2))
    assertEquals(6, AmktVpnService.calculateBackoffSeconds(3))
    assertEquals(10, AmktVpnService.calculateBackoffSeconds(4))
    assertEquals(15, AmktVpnService.calculateBackoffSeconds(5))
    assertEquals(15, AmktVpnService.calculateBackoffSeconds(20))
    assertTrue(AmktVpnService.MAX_RECONNECT_ATTEMPTS in 1..20)
  }

  @Test
  fun durationAndByteFormattersWorkForHomeAndNotification() {
    assertEquals("00:00:00", AmktVpnService.formatDuration(0))
    assertEquals("00:01:05", AmktVpnService.formatDuration(65))
    assertEquals("01:02:03", AmktVpnService.formatDuration(3723))

    assertEquals("512 B", AmktVpnService.formatBytes(512L))
    assertEquals("2.0 KB", AmktVpnService.formatBytes(2048L))
    assertEquals("1.50 MB", AmktVpnService.formatBytes(1572864L))
  }

  @Test
  fun vpnRuntimeStateHolderTracksKillSwitchAndReconnectLifecycle() {
    VpnRuntimeStateHolder.updateState(VpnTunnelState.Disconnected)
    VpnRuntimeStateHolder.resetTraffic()

    VpnRuntimeStateHolder.updateState(
      VpnTunnelState.Connected(
        profileName = "Test Node",
        targetHost = "198.51.100.10",
        targetPort = 22,
        localSocksPort = 1080,
        killSwitchEnabled = true,
        autoReconnectEnabled = true,
      )
    )
    VpnRuntimeStateHolder.updateTraffic(VpnTrafficStats(bytesUp = 4096, bytesDown = 8192, activeStreams = 3))
    assertTrue(VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected)
    assertEquals(4096L, VpnRuntimeStateHolder.trafficStats.value.bytesUp)

    VpnRuntimeStateHolder.updateState(
      VpnTunnelState.Reconnecting(
        profileName = "Test Node",
        targetHost = "198.51.100.10",
        targetPort = 22,
        attempt = 1,
        nextRetrySeconds = 2,
        killSwitchBlocking = true,
        lastReason = "SSH drop",
      )
    )
    val reconnecting = VpnRuntimeStateHolder.state.value as VpnTunnelState.Reconnecting
    assertTrue(reconnecting.killSwitchBlocking)
    assertEquals(1, reconnecting.attempt)

    VpnRuntimeStateHolder.updateState(VpnTunnelState.Disconnected)
    VpnRuntimeStateHolder.resetTraffic()
    assertEquals(VpnTunnelState.Disconnected, VpnRuntimeStateHolder.state.value)
    assertEquals(0L, VpnRuntimeStateHolder.trafficStats.value.bytesDown)
  }
}
