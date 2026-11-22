package com.example

import com.example.config.ConfigFileCodec
import com.example.config.ConfigImportResult
import com.example.config.MyVpnCrypto
import com.example.data.ConnectionMode
import com.example.data.ServerProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigFileCodecTest {
  private val codec = ConfigFileCodec()

  @Test
  fun myVpnEncryptDecryptRoundTripPreservesAllFieldsAndHidesSecrets() {
    val original =
      ServerProfileEntity(
        name = "Test WS Profile",
        mode = ConnectionMode.SSH_WEBSOCKET.code,
        sshHost = "ws.example.com",
        sshPort = 80,
        username = "test_user_01",
        password = "super_secret_pw_999",
        payload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]",
        proxyHost = "127.0.0.1",
        proxyPort = 8080,
        sni = "ws.example.com",
        dnsttNameserver = "ns.example.net",
        dnsttPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        vlessLink = "vless://11111111-2222-3333-4444-555555555555@ws.example.com:80?type=ws#Test",
        expiryDate = "2026-12-31",
        locked = true,
        notes = "One account per device",
      )

    val encrypted = codec.encodeToMyVpnBytes(original)
    assertTrue("Envelope must carry AMKT header", MyVpnCrypto.isEncryptedMyVpnEnvelope(encrypted))

    // Verify password & payload are NOT visible in plaintext inside .myvpn bytes
    val rawAscii = encrypted.toString(Charsets.ISO_8859_1)
    assertFalse(rawAscii.contains("super_secret_pw_999"))
    assertFalse(rawAscii.contains("Upgrade: websocket"))

    val result = codec.decodeImportedBytes(encrypted, "sample_test.myvpn")
    assertTrue(result is ConfigImportResult.Success)
    val imported = (result as ConfigImportResult.Success).profiles.single()

    assertEquals(original.name, imported.name)
    assertEquals(original.mode, imported.mode)
    assertEquals(original.sshHost, imported.sshHost)
    assertEquals(original.sshPort, imported.sshPort)
    assertEquals(original.username, imported.username)
    assertEquals(original.password, imported.password)
    assertEquals(original.payload, imported.payload)
    assertEquals(original.proxyHost, imported.proxyHost)
    assertEquals(original.proxyPort, imported.proxyPort)
    assertEquals(original.sni, imported.sni)
    assertEquals(original.dnsttNameserver, imported.dnsttNameserver)
    assertEquals(original.dnsttPublicKey, imported.dnsttPublicKey)
    assertEquals(original.vlessLink, imported.vlessLink)
    assertEquals(original.expiryDate, imported.expiryDate)
    assertEquals(true, imported.locked)
    assertEquals(original.notes, imported.notes)
  }

  @Test
  fun tamperedMyVpnFileFailsGcmAuthentication() {
    val sample =
      ServerProfileEntity(
        name = "Sample Direct",
        mode = ConnectionMode.SSH_DIRECT.code,
        sshHost = "198.51.100.10",
        sshPort = 22,
      )
    val encrypted = codec.encodeToMyVpnBytes(sample)
    // Flip a byte in the ciphertext payload
    encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0xFF).toByte()

    val result = codec.decodeImportedBytes(encrypted, "corrupted.myvpn")
    assertTrue(result is ConfigImportResult.Failure)
  }

  @Test
  fun txtImportParsesVlessAndShadowsocksLinks() {
    val txt =
      """
      # Comment line
      vless://11111111-2222-3333-4444-555555555555@cdn.example.com:80?type=ws&path=%2Fws-test&host=cdn.example.com&security=none#Edge-WS
      ss://YWVzLTI1Ni1nY206dGVzdHBhc3M@198.51.100.25:8388#Edge-Backup
      """.trimIndent()

    val result = codec.decodeImportedBytes(txt.toByteArray(Charsets.UTF_8), "links.txt")
    assertTrue(result is ConfigImportResult.Success)
    val profiles = (result as ConfigImportResult.Success).profiles
    assertEquals(2, profiles.size)

    val vless = profiles[0]
    assertEquals("Edge-WS", vless.name)
    assertEquals(ConnectionMode.VLESS_WS, vless.connectionMode)
    assertEquals("cdn.example.com", vless.sshHost)
    assertEquals(80, vless.sshPort)
    assertEquals("/ws-test", vless.payload)
    assertEquals("11111111-2222-3333-4444-555555555555", vless.username)

    val ss = profiles[1]
    assertEquals("Edge-Backup", ss.name)
    assertEquals("198.51.100.25", ss.sshHost)
    assertEquals(8388, ss.sshPort)
  }
}
