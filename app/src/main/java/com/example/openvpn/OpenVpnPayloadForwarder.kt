package com.example.openvpn

import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.LogLevel
import com.example.data.NetworkConfigEntity
import com.example.data.PayloadMode
import com.example.data.ServerProfileEntity
import com.example.ssh.InjectorSocketFactory
import com.example.ssh.LocalSocks5Proxy
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory

/**
 * Local TCP port forwarder (`127.0.0.1:11940`) that routes a TCP OpenVPN session through the
 * selected [NetworkConfigEntity]'s payload tunnel (`HTTP_PAYLOAD`, `WEBSOCKET`, `SSL_TLS_SNI`,
 * or `DIRECT`) using the exact same [InjectorSocketFactory] logic as SSH.
 *
 * Connect lifecycle:
 * 1. Establish the upstream payload tunnel FIRST (`[host]` = OpenVPN server host, `[port]` =
 *    OpenVPN server TCP port, `[split]`, `[ua]`, `http_proxy_host/port`, `sni_host`).
 *    If the payload tunnel fails, return failure immediately so OpenVPN is never started.
 * 2. Bind `127.0.0.1:11940` (guaranteed non-colliding with SOCKS5 `1080` and HTTP `8989`).
 * 3. Hand the already-established upstream socket to the first incoming OpenVPN TCP connection
 *    and relay bytes bidirectionally until either side closes or [stop] is called.
 *
 * Security / Lock-down: Never logs `.ovpn` content, local forward port `11940`, payload text,
 * OpenVPN server host, or proxy host.
 */
class OpenVpnPayloadForwarder(
  private val logStore: AppLogStore,
  private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
  private val connectTimeoutMs: Int = InjectorSocketFactory.DEFAULT_CONNECT_TIMEOUT_MS,
  private val handshakeTimeoutMs: Int = InjectorSocketFactory.DEFAULT_HANDSHAKE_TIMEOUT_MS,
  private val splitDelayMs: Long = InjectorSocketFactory.DEFAULT_SPLIT_DELAY_MS,
) {
  private val running = AtomicBoolean(false)
  private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
  private val preEstablishedUpstream = AtomicReference<Socket?>(null)

  @Volatile private var serverSocket: ServerSocket? = null
  @Volatile private var executor: ExecutorService? = null
  @Volatile private var activeFactory: InjectorSocketFactory? = null

  @Volatile
  var boundPort: Int = 0
    private set

  fun isRunning(): Boolean = running.get()

  /**
   * Establishes the network's payload tunnel to `[targetHost]:[targetPort]` FIRST, then binds
   * `127.0.0.1:[preferredPort]` (default `11940`) and starts forwarding accepted OpenVPN TCP
   * connections through that payload tunnel.
   */
  fun start(
    network: NetworkConfigEntity,
    targetHost: String,
    targetPort: Int,
    preferredPort: Int = DEFAULT_LOCAL_PORT,
    socketProtector: ((Socket) -> Boolean)? = null,
    onUpstreamClosed: (() -> Unit)? = null,
  ): Result<Int> {
    if (preferredPort in RESERVED_PORTS) {
      return Result.failure(
        IllegalArgumentException("Local OpenVPN forward port conflicts with reserved listener port")
      )
    }
    stop()

    val cleanHost = targetHost.trim()
    val cleanPort = targetPort.coerceIn(1, 65535)
    if (cleanHost.isBlank()) {
      return Result.failure(IllegalArgumentException("OpenVPN remote host is blank"))
    }

    val payloadProfile = buildPayloadProfileForOpenVpn(network, cleanHost, cleanPort)
    val factory =
      InjectorSocketFactory(
        profile = payloadProfile,
        logStore = logStore,
        sslSocketFactory = sslSocketFactory,
        connectTimeoutMs = connectTimeoutMs,
        handshakeTimeoutMs = handshakeTimeoutMs,
        splitDelayMs = splitDelayMs,
        redactHostLogs = true,
        socketProtector = socketProtector,
      )
    activeFactory = factory

    logStore.append(
      tag = "OVPN",
      message = "Payload Tunnel စတင်ချိတ်ဆက်နေသည် (${network.name})...",
      level = LogLevel.INFO,
    )

    // Step (a): Establish the selected network's payload tunnel BEFORE starting OpenVPN.
    val upstream =
      try {
        factory.createSocket(cleanHost, cleanPort)
      } catch (e: IOException) {
        activeFactory = null
        val msg = e.message?.takeIf { it.isNotBlank() } ?: "Payload Tunnel ချိတ်ဆက်မှု မအောင်မြင်ပါ"
        logStore.append(tag = "OVPN", message = msg, level = LogLevel.ERROR)
        return Result.failure(IOException(msg, e))
      } catch (t: Throwable) {
        activeFactory = null
        val msg = "Payload Tunnel ချိတ်ဆက်မှု မအောင်မြင်ပါ (${t.javaClass.simpleName})"
        logStore.append(tag = "OVPN", message = msg, level = LogLevel.ERROR)
        return Result.failure(IOException(msg, t))
      }

    // Step (b): Bind local TCP listener on 127.0.0.1:11940 (never colliding with 1080 or 8989).
    val listener =
      try {
        bindLoopbackListener(preferredPort)
      } catch (e: IOException) {
        runCatching { upstream.close() }
        activeFactory = null
        val msg = "Local TCP forwarder စတင်မရပါ"
        logStore.append(tag = "OVPN", message = msg, level = LogLevel.ERROR)
        return Result.failure(IOException(msg, e))
      }

    preEstablishedUpstream.set(upstream)
    activeSockets.add(upstream)
    serverSocket = listener
    boundPort = listener.localPort

    val pool =
      Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "amkt-ovpn-payload-fwd").apply { isDaemon = true }
      }
    executor = pool
    running.set(true)

    pool.execute {
      acceptLoop(
        listener = listener,
        factory = factory,
        targetHost = cleanHost,
        targetPort = cleanPort,
        pool = pool,
        onUpstreamClosed = onUpstreamClosed,
      )
    }

    logStore.append(
      tag = "OVPN",
      message = "Payload Tunnel အောင်မြင်ပြီးပါပြီ (${network.name})",
      level = LogLevel.SUCCESS,
    )
    return Result.success(boundPort)
  }

  /**
   * Immediately interrupts any in-flight payload handshake and tears down the local listener.
   */
  fun abortActiveConnect() {
    runCatching { activeFactory?.abortActiveHandshake() }
    stop()
  }

  /**
   * Stops accepting connections and closes all active local/upstream TCP sockets cleanly.
   */
  fun stop() {
    running.set(false)
    runCatching { activeFactory?.abortActiveHandshake() }
    activeFactory = null

    val ss = serverSocket
    serverSocket = null
    boundPort = 0
    runCatching { ss?.close() }

    preEstablishedUpstream.getAndSet(null)?.let { sock ->
      activeSockets.remove(sock)
      runCatching { sock.close() }
    }

    val snapshot = activeSockets.toList()
    activeSockets.clear()
    snapshot.forEach { sock -> runCatching { sock.close() } }

    val pool = executor
    executor = null
    runCatching { pool?.shutdownNow() }
  }

  private fun acceptLoop(
    listener: ServerSocket,
    factory: InjectorSocketFactory,
    targetHost: String,
    targetPort: Int,
    pool: ExecutorService,
    onUpstreamClosed: (() -> Unit)?,
  ) {
    while (running.get() && !listener.isClosed) {
      val localClient =
        try {
          listener.accept()
        } catch (_: IOException) {
          break
        }
      if (!running.get()) {
        runCatching { localClient.close() }
        break
      }
      runCatching { localClient.tcpNoDelay = true }
      activeSockets.add(localClient)
      pool.execute {
        bridgeClient(
          localClient = localClient,
          factory = factory,
          targetHost = targetHost,
          targetPort = targetPort,
          pool = pool,
          onUpstreamClosed = onUpstreamClosed,
        )
      }
    }
  }

  private fun bridgeClient(
    localClient: Socket,
    factory: InjectorSocketFactory,
    targetHost: String,
    targetPort: Int,
    pool: ExecutorService,
    onUpstreamClosed: (() -> Unit)?,
  ) {
    var upstream = preEstablishedUpstream.getAndSet(null)
    if (upstream == null || upstream.isClosed || !upstream.isConnected) {
      upstream?.let {
        activeSockets.remove(it)
        runCatching { it.close() }
      }
      upstream =
        try {
          factory.createSocket(targetHost, targetPort)
        } catch (_: IOException) {
          activeSockets.remove(localClient)
          runCatching { localClient.close() }
          if (running.get()) {
            onUpstreamClosed?.invoke()
          }
          return
        }
      activeSockets.add(upstream)
    }

    val upstreamSock = upstream
    val closed = AtomicBoolean(false)
    fun closePair(notifyDrop: Boolean) {
      if (closed.compareAndSet(false, true)) {
        activeSockets.remove(localClient)
        activeSockets.remove(upstreamSock)
        runCatching { localClient.close() }
        runCatching { upstreamSock.close() }
        if (notifyDrop && running.get()) {
          onUpstreamClosed?.invoke()
        }
      }
    }

    // Pump local OpenVPN -> upstream payload tunnel
    pool.execute {
      try {
        val inp = localClient.getInputStream()
        val out = upstreamSock.getOutputStream()
        val buf = ByteArray(RELAY_BUFFER_BYTES)
        while (running.get() && !localClient.isClosed && !upstreamSock.isClosed) {
          val n = inp.read(buf)
          if (n <= 0) break
          out.write(buf, 0, n)
          out.flush()
        }
      } catch (_: IOException) {
        // Socket closed or reset
      } finally {
        closePair(notifyDrop = false)
      }
    }

    // Pump upstream payload tunnel -> local OpenVPN
    try {
      val inp = upstreamSock.getInputStream()
      val out = localClient.getOutputStream()
      val buf = ByteArray(RELAY_BUFFER_BYTES)
      while (running.get() && !localClient.isClosed && !upstreamSock.isClosed) {
        val n = inp.read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
        out.flush()
      }
    } catch (_: IOException) {
      // Upstream dropped or closed
    } finally {
      closePair(notifyDrop = true)
    }
  }

  @Throws(IOException::class)
  private fun bindLoopbackListener(preferredPort: Int): ServerSocket {
    val loopback = InetAddress.getByName(LOOPBACK_HOST)
    val candidates =
      buildList {
        if (preferredPort > 0 && preferredPort !in RESERVED_PORTS) {
          add(preferredPort)
        }
        for (offset in 1..15) {
          val port = DEFAULT_LOCAL_PORT + offset
          if (port !in RESERVED_PORTS && port != preferredPort) {
            add(port)
          }
        }
      }

    var lastError: IOException? = null
    for (port in candidates) {
      try {
        return ServerSocket().apply {
          reuseAddress = true
          bind(InetSocketAddress(loopback, port), 8)
        }
      } catch (e: IOException) {
        lastError = e
      }
    }
    // Final fallback: ephemeral loopback port guaranteed != 1080/8989
    for (attempt in 0 until 5) {
      val sock =
        ServerSocket().apply {
          reuseAddress = true
          bind(InetSocketAddress(loopback, 0), 8)
        }
      if (sock.localPort !in RESERVED_PORTS) {
        return sock
      }
      runCatching { sock.close() }
    }
    throw lastError ?: IOException("Unable to bind local TCP forward port on 127.0.0.1")
  }

  companion object {
    const val LOOPBACK_HOST = "127.0.0.1"
    const val DEFAULT_LOCAL_PORT = 11940
    const val HTTP_PROXY_RESERVED_PORT = 8989

    val RESERVED_PORTS: Set<Int> =
      setOf(LocalSocks5Proxy.DEFAULT_PORT, HTTP_PROXY_RESERVED_PORT)

    const val OVPN_TCP_ONLY_ERROR =
      "This network works with TCP OpenVPN only — pick a TCP OpenVPN server, or connect without a network."

    private const val RELAY_BUFFER_BYTES = 16 * 1024

    /**
     * Maps a reusable [NetworkConfigEntity] + OpenVPN target host/port into the exact
     * [ServerProfileEntity] shape consumed by [InjectorSocketFactory] for SSH payloads.
     */
    fun buildPayloadProfileForOpenVpn(
      network: NetworkConfigEntity,
      targetHost: String,
      targetPort: Int,
    ): ServerProfileEntity {
      val mode = network.resolvedPayloadMode
      val mappedConnectionMode =
        when (mode) {
          PayloadMode.WEBSOCKET -> ConnectionMode.SSH_WEBSOCKET
          PayloadMode.SSL_TLS_SNI -> ConnectionMode.SSH_SSL
          PayloadMode.HTTP_PAYLOAD -> ConnectionMode.SSH_PROXY
          PayloadMode.DIRECT -> ConnectionMode.SSH_DIRECT
        }
      return ServerProfileEntity(
        id = network.id,
        name = network.name,
        mode = mappedConnectionMode.code,
        sshHost = targetHost.trim(),
        sshPort = targetPort.coerceIn(1, 65535),
        payload = network.payloadTemplate,
        proxyHost = network.httpProxyHost.trim(),
        proxyPort = network.httpProxyPort.coerceIn(1, 65535),
        sni = network.sniHost.trim(),
        payloadMode = mode.code,
        extraHeaders = network.extraHeaders,
      )
    }
  }
}
