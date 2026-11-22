package com.example

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AmktDatabase
import com.example.data.ConnectionMode
import com.example.data.NetworkConfigEntity
import com.example.data.NetworkServerRepository
import com.example.data.PayloadMode
import com.example.data.RemoteServerListRepository
import com.example.data.ServerAccountEntity
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteJsonSplitHardeningTest {
  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var splitRepo: NetworkServerRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    val prefsFile = File(context.cacheDir, "remote_v2_${UUID.randomUUID()}.preferences_pb")
    remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { prefsFile },
      )
    splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun loadFormatVersion2AddsNewNetworkAndServerAndEmitsImmediately() = runBlocking {
    val json =
      """
      {
        "format_version": 2,
        "networks": [
          {
            "name": "Carrier WS Net",
            "payload_mode": "WEBSOCKET",
            "payload_template": "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]",
            "sni_host": "cdn.example.com",
            "extra_headers": "X-Custom: ok",
            "http_proxy_host": "",
            "http_proxy_port": 0
          }
        ],
        "servers": [
          {
            "name": "SG Edge 01",
            "connection_mode": "SSH_DIRECT",
            "host": "sg01.example.com",
            "port": 443,
            "username": "user01",
            "password": "",
            "expiry_date": "2027-10-10",
            "region": "SG",
            "network_name": "Carrier WS Net"
          }
        ]
      }
      """.trimIndent()

    val http = OneShotGetServer(200, json)
    try {
      val result = splitRepo.fetchAndMergeSplitFromRemoteUrl(http.url, replaceExisting = false)
      assertTrue("Expected success, got $result", result.isSuccess)
      val summary = result.getOrThrow()
      assertEquals(1, summary.networksAdded)
      assertEquals(0, summary.networksUpdated)
      assertEquals(1, summary.serversAdded)
      assertEquals(0, summary.serversUpdated)

      // Immediately visible via reactive Room Flows (no restart needed)
      val liveNetworks = splitRepo.allNetworks.first()
      val liveServers = splitRepo.allServers.first()
      assertEquals(1, liveNetworks.size)
      assertEquals(1, liveServers.size)
      assertEquals("Carrier WS Net", liveNetworks.first().name)
      assertEquals("SG Edge 01", liveServers.first().name)
      assertEquals(liveNetworks.first().id, liveServers.first().defaultNetworkId)
    } finally {
      http.close()
    }
  }

  @Test
  fun loadUpdatesExistingNetworkAndServerAndPreservesOmittedLocalEdits() = runBlocking {
    // Seed existing locally-edited Network and Server
    val netId =
      splitRepo.saveNetwork(
        NetworkConfigEntity(
          name = "Carrier WS Net",
          payloadMode = PayloadMode.WEBSOCKET.code,
          payloadTemplate = "GET /local HTTP/1.1[crlf]Host: [host][crlf][crlf]",
          sniHost = "local-sni.example.com",
          extraHeaders = "X-Local-Header: keep-me",
        )
      )
    splitRepo.saveServer(
      ServerAccountEntity(
        name = "SG Edge 01",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "old-sg.example.com",
        port = 22,
        username = "local_user",
        password = "my-local-password",
        notes = "My private local note",
        defaultNetworkId = netId,
      )
    )

    // Remote JSON supplies updated host/port/sni, but OMITS extra_headers, password, notes
    val partialUpdateJson =
      """
      {
        "format_version": 2,
        "networks": [
          {
            "name": "carrier ws net",
            "payload_mode": "SSL_TLS_SNI",
            "sni_host": "updated-sni.example.com"
          }
        ],
        "servers": [
          {
            "name": "SG EDGE 01",
            "host": "new-sg.example.com",
            "port": 443,
            "expiry_date": "2027-12-31"
          }
        ]
      }
      """.trimIndent()

    val http = OneShotGetServer(200, partialUpdateJson)
    try {
      val summary = splitRepo.fetchAndMergeSplitFromRemoteUrl(http.url, replaceExisting = false).getOrThrow()
      assertEquals(0, summary.networksAdded)
      assertEquals(1, summary.networksUpdated)
      assertEquals(0, summary.serversAdded)
      assertEquals(1, summary.serversUpdated)

      val nets = splitRepo.getAllNetworksSnapshot()
      val srvs = splitRepo.getAllServersSnapshot()
      assertEquals(1, nets.size)
      assertEquals(1, srvs.size)

      val net = nets.first()
      assertEquals(PayloadMode.SSL_TLS_SNI, net.resolvedPayloadMode)
      assertEquals("updated-sni.example.com", net.sniHost)
      assertEquals("Omitted payload_template must keep local value", "GET /local HTTP/1.1[crlf]Host: [host][crlf][crlf]", net.payloadTemplate)
      assertEquals("Omitted extra_headers must keep local value", "X-Local-Header: keep-me", net.extraHeaders)

      val srv = srvs.first()
      assertEquals("new-sg.example.com", srv.host)
      assertEquals(443, srv.port)
      assertEquals("2027-12-31", srv.expiryDate)
      assertEquals("Omitted username must keep local value", "local_user", srv.username)
      assertEquals("Omitted password must keep local value", "my-local-password", srv.password)
      assertEquals("Omitted notes must keep local value", "My private local note", srv.notes)
    } finally {
      http.close()
    }
  }

  @Test
  fun loadingSameJsonTwiceProducesZeroDuplicates() = runBlocking {
    val json =
      """
      {
        "format_version": 2,
        "networks": [
          {"name": "Direct Clean", "payload_mode": "DIRECT"},
          {"name": "WS Clean", "payload_mode": "WEBSOCKET", "payload_template": "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"}
        ],
        "servers": [
          {"name": "JP Node 1", "connection_mode": "SSH_DIRECT", "host": "jp1.example.com", "port": 22, "network_name": "Direct Clean"},
          {"name": "SG Node 2", "connection_mode": "SSH_DIRECT", "host": "sg2.example.com", "port": 443, "network_name": "WS Clean"}
        ]
      }
      """.trimIndent()

    val firstHttp = OneShotGetServer(200, json)
    try {
      val s1 = splitRepo.fetchAndMergeSplitFromRemoteUrl(firstHttp.url, replaceExisting = false).getOrThrow()
      assertEquals(2, s1.networksAdded)
      assertEquals(2, s1.serversAdded)
    } finally {
      firstHttp.close()
    }

    val secondHttp = OneShotGetServer(200, json)
    try {
      val s2 = splitRepo.fetchAndMergeSplitFromRemoteUrl(secondHttp.url, replaceExisting = false).getOrThrow()
      assertEquals(0, s2.networksAdded)
      assertEquals(2, s2.networksUpdated)
      assertEquals(0, s2.serversAdded)
      assertEquals(2, s2.serversUpdated)
    } finally {
      secondHttp.close()
    }

    assertEquals(2, splitRepo.getAllNetworksSnapshot().size)
    assertEquals(2, splitRepo.getAllServersSnapshot().size)
  }

  @Test
  fun serverReferencingMissingNetworkNameSavesGracefullyWithNullLink() = runBlocking {
    val existingNetId =
      splitRepo.saveNetwork(
        NetworkConfigEntity(name = "Existing Local Net", payloadMode = PayloadMode.DIRECT.code)
      )

    val json =
      """
      {
        "format_version": 2,
        "networks": [],
        "servers": [
          {
            "name": "Orphan Linked Server",
            "connection_mode": "SSH_DIRECT",
            "host": "orphan.example.com",
            "port": 22,
            "network_name": "Does Not Exist Anywhere"
          }
        ]
      }
      """.trimIndent()

    val http = OneShotGetServer(200, json)
    try {
      val result =
        splitRepo.fetchAndMergeSplitFromRemoteUrl(
          url = http.url,
          replaceExisting = false,
          fallbackSelectedNetworkId = existingNetId,
        )
      assertTrue("Missing network_name must not crash or fail", result.isSuccess)
      val srv = splitRepo.getAllServersSnapshot().firstOrNull { it.name == "Orphan Linked Server" }
      assertNotNull(srv)
      assertNull(
        "Unmatched explicit network_name must stay null rather than mis-linking to unrelated network",
        srv!!.defaultNetworkId,
      )
    } finally {
      http.close()
    }
  }

  @Test
  fun invalidJsonInvalidUrlAndHttpErrorNeverWipeLocalData() = runBlocking {
    splitRepo.saveNetwork(
      NetworkConfigEntity(name = "Protected Net", payloadMode = PayloadMode.DIRECT.code)
    )
    splitRepo.saveServer(
      ServerAccountEntity(
        name = "Protected Server",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "safe.example.com",
        port = 22,
      )
    )

    // 1. Invalid URL scheme
    val badUrl = splitRepo.fetchAndMergeSplitFromRemoteUrl("not-a-url", replaceExisting = true)
    assertTrue(badUrl.isFailure)
    assertTrue(badUrl.exceptionOrNull()?.message.orEmpty().isNotBlank())

    // 2. HTTP 500 error
    val http500 = OneShotGetServer(500, "Internal Error")
    try {
      val res500 = splitRepo.fetchAndMergeSplitFromRemoteUrl(http500.url, replaceExisting = true)
      assertTrue(res500.isFailure)
      assertTrue(res500.exceptionOrNull()?.message.orEmpty().contains("500"))
    } finally {
      http500.close()
    }

    // 3. Invalid JSON syntax
    val badJson = OneShotGetServer(200, "{broken json!!!")
    try {
      val resJson = splitRepo.fetchAndMergeSplitFromRemoteUrl(badJson.url, replaceExisting = true)
      assertTrue(resJson.isFailure)
      assertTrue(resJson.exceptionOrNull()?.message.orEmpty().contains("JSON"))
    } finally {
      badJson.close()
    }

    // Local Room tables must remain 100% intact even though replaceExisting=true was passed!
    val nets = splitRepo.getAllNetworksSnapshot()
    val srvs = splitRepo.getAllServersSnapshot()
    assertEquals(1, nets.size)
    assertEquals("Protected Net", nets.first().name)
    assertEquals(1, srvs.size)
    assertEquals("Protected Server", srvs.first().name)
  }

  private class OneShotGetServer(
    private val statusCode: Int,
    private val body: String,
  ) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
      soTimeout = 5_000
    }
    private val executor = Executors.newSingleThreadExecutor()
    val url: String = "http://127.0.0.1:${serverSocket.localPort}/servers.json"

    init {
      executor.submit {
        try {
          serverSocket.accept().use { client ->
            client.soTimeout = 3_000
            val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            while (true) {
              val line = reader.readLine() ?: break
              if (line.isEmpty()) break
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val reason = if (statusCode == 200) "OK" else "Error"
            val headers =
              "HTTP/1.1 $statusCode $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
              write(headers.toByteArray(StandardCharsets.UTF_8))
              write(bytes)
              flush()
            }
          }
        } catch (_: Exception) {
          // Bounded test server exits cleanly
        }
      }
    }

    override fun close() {
      try {
        serverSocket.close()
      } catch (_: Exception) {}
      executor.shutdownNow()
      try {
        executor.awaitTermination(2, TimeUnit.SECONDS)
      } catch (_: Exception) {}
    }
  }
}
