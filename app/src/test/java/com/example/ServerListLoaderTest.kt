package com.example

import com.example.data.ConnectionMode
import com.example.data.ServerListLoader
import com.example.dnstt.DnsttValidator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListLoaderTest {
  private val loader = ServerListLoader()

  @Test
  fun parsesServersEnvelopeWithAllEditableFields() {
    val json =
      """
      {
        "servers": [
          {
            "name": "Node Alpha",
            "mode": "ssh_ws",
            "ssh_host": "alpha.example.com",
            "ssh_port": 80,
            "username": "",
            "password": "",
            "payload": "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]",
            "proxy_host": "198.51.100.2",
            "proxy_port": 8080,
            "sni": "alpha.example.com",
            "dnstt_nameserver": "ns.example.net",
            "dnstt_public_key": "0123456789abcdef",
            "dnstt_resolver": "1.1.1.1:53",
            "vless_link": "vless://uuid@alpha.example.com:443#Alpha",
            "expiry_date": "2026-12-31",
            "locked": false,
            "notes": "Remote editable profile"
          }
        ]
      }
      """.trimIndent()

    val result = loader.parseServerListJson(json)
    assertTrue(result.isSuccess)
    val profiles = result.getOrThrow()
    assertEquals(1, profiles.size)
    val p = profiles.first()
    assertEquals("Node Alpha", p.name)
    assertEquals(ConnectionMode.SSH_WEBSOCKET, p.connectionMode)
    assertEquals("alpha.example.com", p.sshHost)
    assertEquals(80, p.sshPort)
    assertEquals("198.51.100.2", p.proxyHost)
    assertEquals(8080, p.proxyPort)
    assertEquals("alpha.example.com", p.sni)
    assertEquals("ns.example.net", p.dnsttNameserver)
    assertEquals("0123456789abcdef", p.dnsttPublicKey)
    assertEquals("1.1.1.1:53", p.dnsttResolver)
    assertEquals("vless://uuid@alpha.example.com:443#Alpha", p.vlessLink)
    assertEquals("2026-12-31", p.expiryDate)
    assertFalse(p.locked)
    assertEquals("Remote editable profile", p.notes)
  }

  @Test
  fun parsesProfilesEnvelopeAndTopLevelArray() {
    val profilesEnvelope =
      """
      {"profiles": [{"name": "Node B", "mode": "ssh_direct", "ssh_host": "198.51.100.5", "ssh_port": 22}]}
      """.trimIndent()
    val arrayJson =
      """
      [
        {"name": "Node C", "mode": "dnstt", "dnstt_nameserver": "dns.example.net", "dnstt_public_key": "abcd"},
        {"name": "Node D", "mode": "vless_ws", "ssh_host": "edge.example.com", "ssh_port": 80}
      ]
      """.trimIndent()

    val r1 = loader.parseServerListJson(profilesEnvelope).getOrThrow()
    assertEquals(1, r1.size)
    assertEquals("Node B", r1[0].name)
    assertEquals(ConnectionMode.SSH_DIRECT, r1[0].connectionMode)

    val r2 = loader.parseServerListJson(arrayJson).getOrThrow()
    assertEquals(2, r2.size)
    assertEquals(ConnectionMode.DNSTT, r2[0].connectionMode)
    assertEquals(ConnectionMode.VLESS_WS, r2[1].connectionMode)
  }

  @Test
  fun bundledStarterAssetIsRemovedAndDefaultRemoteUrlIsEmbedded() {
    val assetFile =
      listOf(
        File("src/main/assets/starter_profiles.json"),
        File("app/src/main/assets/starter_profiles.json"),
      ).firstOrNull { it.exists() }

    assertTrue("starter_profiles.json must be removed so fresh installs start empty", assetFile == null)
    assertEquals(
      "https://n8n.marratethu.web.id/webhook/amkt-injector-servers",
      com.example.data.RemoteServerListRepository.DEFAULT_REMOTE_SERVER_LIST_URL,
    )
  }
}
