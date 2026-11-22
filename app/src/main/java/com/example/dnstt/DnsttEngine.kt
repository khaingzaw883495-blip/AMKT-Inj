package com.example.dnstt

import com.example.data.AppLogStore
import com.example.data.LogLevel
import com.example.data.ServerProfileEntity
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Milestone 5 DNSTT (SlowDNS) configuration for a single tunnel session.
 */
data class DnsttConfig(
  val nameserver: String,
  val publicKeyHex: String,
  val resolverAddress: String = ServerProfileEntity.DEFAULT_DNSTT_RESOLVER,
  val localListenPort: Int = 0, // 0 = pick a guaranteed free loopback port
  val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
) {
  companion object {
    const val LOOPBACK_HOST = "127.0.0.1"
    const val DEFAULT_RESOLVER_PORT = 53
    const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 20_000L
  }
}

sealed interface DnsttState {
  data object Stopped : DnsttState

  data class Starting(val nameserver: String, val resolverAddress: String) : DnsttState

  data class Running(
    val nameserver: String,
    val resolverAddress: String,
    val localHost: String,
    val localPort: Int,
  ) : DnsttState

  data class Error(val message: String) : DnsttState
}

/**
 * Burmese pre-flight validation for DNSTT / SlowDNS profiles.
 */
object DnsttValidator {
  private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")
  private val DOMAIN_LABEL_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

  fun isValidPublicKeyHex(hex: String): Boolean = HEX_64_REGEX.matches(hex.trim())

  fun isValidNameserverDomain(domain: String): Boolean {
    val trimmed = domain.trim().trimEnd('.')
    if (trimmed.isBlank() || trimmed.length > 180 || !trimmed.contains('.')) return false
    val labels = trimmed.split('.')
    return labels.size >= 2 && labels.all { DOMAIN_LABEL_REGEX.matches(it) }
  }

  fun parseResolverEndpoint(raw: String): Pair<String, Int>? {
    val trimmed = raw.trim().ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER }
    if (trimmed.any { it.isWhitespace() }) return null
    val colonIdx = trimmed.lastIndexOf(':')
    return if (colonIdx > 0 && colonIdx == trimmed.indexOf(':')) {
      val host = trimmed.substring(0, colonIdx).trim()
      val port = trimmed.substring(colonIdx + 1).trim().toIntOrNull() ?: return null
      if (host.isBlank() || port !in 1..65535) null else host to port
    } else {
      trimmed to DnsttConfig.DEFAULT_RESOLVER_PORT
    }
  }

  /**
   * Validates all fields required to start a DNSTT + SSH tunnel.
   * Returns `null` if valid, or a user-facing Burmese error message if invalid.
   */
  fun validateProfile(profile: ServerProfileEntity): String? {
    val ns = profile.dnsttNameserver.trim()
    if (ns.isBlank()) {
      return "DNSTT Nameserver (Tunnel Domain) မထည့်ရသေးပါ"
    }
    if (!isValidNameserverDomain(ns)) {
      return "DNSTT Nameserver ပုံစံ မမှန်ကန်ပါ (ဥပမာ: ns.example.com)"
    }
    val pubKey = profile.dnsttPublicKey.trim()
    if (pubKey.isBlank()) {
      return "DNSTT Public Key (64-char hex) မထည့်ရသေးပါ"
    }
    if (!isValidPublicKeyHex(pubKey)) {
      return "DNSTT Public Key မမှန်ကန်ပါ — 64 လုံးပါ Hex စာသား ဖြစ်ရပါမည် (လက်ရှိ: ${pubKey.length} လုံး)"
    }
    if (parseResolverEndpoint(profile.effectiveDnsttResolver()) == null) {
      return "DNS Resolver လိပ်စာ မမှန်ကန်ပါ (ဥပမာ: 8.8.8.8:53)"
    }
    if (profile.username.isBlank() || profile.password.isBlank()) {
      return "SSH Username သို့မဟုတ် Password မထည့်ရသေးပါ"
    }
    return null
  }
}

interface DnsttEngine {
  val state: StateFlow<DnsttState>
  val isImplemented: Boolean

  /**
   * Starts the DNSTT client tunnel, waits until the Noise_NK + smux tunnel and local TCP
   * forwarding port are ready, and returns the bound loopback port on `127.0.0.1`.
   */
  suspend fun start(config: DnsttConfig): Result<Int>

  suspend fun stop()

  /** Non-blocking best-effort teardown safe to call from `Service.onDestroy()`. */
  fun stopNow()
}

/**
 * Pure-Kotlin native DNSTT (SlowDNS) client engine implementing the exact upstream `dnstt`
 * wire protocol (`UDP DNS TXT + Base32 + KCP + Noise_NK_25519_ChaChaPoly_BLAKE2s + smux v2`).
 *
 * Listens on a local loopback TCP port (`127.0.0.1:<port>`) and forwards incoming SSH connections
 * through the encrypted DNS tunnel without spawning external binaries or requiring root/execve.
 */
class NativeKotlinDnsttEngine(
  private val logStore: AppLogStore,
) : DnsttEngine {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleMutex = Mutex()
  private val txIdGen = AtomicInteger((DnsttCrypto.randomBytes(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }))

  private val _state = MutableStateFlow<DnsttState>(DnsttState.Stopped)
  override val state: StateFlow<DnsttState> = _state.asStateFlow()
  override val isImplemented: Boolean = true

  @Volatile private var activeResources: ActiveTunnelResources? = null
  @Volatile private var userRequestedStop: Boolean = false

  private class ActiveTunnelResources(
    val udpSocket: DatagramSocket,
    val kcpSession: KcpClientSession,
    val serverSocket: ServerSocket,
    val jobs: MutableList<Job> = Collections.synchronizedList(mutableListOf()),
    val clientSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf()),
    @Volatile var noiseChannel: DnsttNoiseChannel? = null,
    @Volatile var smuxSession: SmuxClientSession? = null,
  ) {
    fun closeAll() {
      jobs.forEach { it.cancel() }
      jobs.clear()
      runCatching { serverSocket.close() }
      val socketsSnapshot = synchronized(clientSockets) { clientSockets.toList() }
      socketsSnapshot.forEach { runCatching { it.close() } }
      clientSockets.clear()
      runCatching { smuxSession?.close() }
      runCatching { noiseChannel?.close() }
      runCatching { kcpSession.close("DNSTT stopped") }
      runCatching { udpSocket.close() }
    }
  }

  override suspend fun start(config: DnsttConfig): Result<Int> {
    val cleanDomain = config.nameserver.trim().trimEnd('.')
    val cleanPubKeyHex = config.publicKeyHex.trim()
    val resolverStr = config.resolverAddress.trim().ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER }

    if (!DnsttValidator.isValidNameserverDomain(cleanDomain)) {
      val msg = "DNSTT Nameserver မမှန်ကန်ပါ ($cleanDomain)"
      logStore.append(tag = "DNSTT", message = msg, level = LogLevel.ERROR)
      _state.value = DnsttState.Error(msg)
      return Result.failure(IllegalArgumentException(msg))
    }
    if (!DnsttValidator.isValidPublicKeyHex(cleanPubKeyHex)) {
      val msg = "DNSTT Public Key (64-char hex) မမှန်ကန်ပါ"
      logStore.append(tag = "DNSTT", message = msg, level = LogLevel.ERROR)
      _state.value = DnsttState.Error(msg)
      return Result.failure(IllegalArgumentException(msg))
    }
    val resolverEndpoint =
      DnsttValidator.parseResolverEndpoint(resolverStr)
        ?: run {
          val msg = "DNS Resolver လိပ်စာ မမှန်ကန်ပါ ($resolverStr)"
          logStore.append(tag = "DNSTT", message = msg, level = LogLevel.ERROR)
          _state.value = DnsttState.Error(msg)
          return Result.failure(IllegalArgumentException(msg))
        }

    // Ensure any previous tunnel is completely released before starting
    teardownInternal(emitLog = false)
    userRequestedStop = false

    _state.value = DnsttState.Starting(nameserver = cleanDomain, resolverAddress = resolverStr)
    logStore.append(
      tag = "DNSTT",
      message = "DNSTT (SlowDNS) စတင်နေသည် — Domain: $cleanDomain | Resolver: ${resolverEndpoint.first}:${resolverEndpoint.second}...",
      level = LogLevel.INFO,
    )

    return withContext(Dispatchers.IO) {
      var resources: ActiveTunnelResources? = null
      try {
        val pubKeyBytes = DnsttCrypto.hexToBytes(cleanPubKeyHex)
        val resolverAddr = InetSocketAddress(InetAddress.getByName(resolverEndpoint.first), resolverEndpoint.second)
        val clientId = DnsttCrypto.randomBytes(DnsttDnsCodec.CLIENT_ID_LEN)
        val convBytes = DnsttCrypto.randomBytes(4)
        var conv = DnsttCrypto.readIntLE(convBytes, 0)
        if (conv == 0) conv = 1

        val kcpMtu = DnsttDnsCodec.maxUpstreamKcpPacketSize(cleanDomain)
        val outboundQueue = LinkedBlockingQueue<ByteArray>(512)
        val udpSocket = DatagramSocket().apply { soTimeout = UDP_READ_TIMEOUT_MS }
        val serverSocket =
          ServerSocket(config.localListenPort, 16, InetAddress.getByName(DnsttConfig.LOOPBACK_HOST))
        val localPort = serverSocket.localPort

        val kcpSession =
          KcpClientSession(
            conv = conv,
            mtu = kcpMtu,
            sendPacket = { pkt ->
              if (outboundQueue.remainingCapacity() == 0) {
                outboundQueue.poll()
              }
              outboundQueue.offer(pkt)
            },
          )

        resources = ActiveTunnelResources(udpSocket, kcpSession, serverSocket)
        activeResources = resources

        val pollBoostUntilMs = AtomicLong(System.currentTimeMillis() + 5_000L)

        // 1. UDP DNS Receiver loop
        val recvJob =
          scope.launch {
            val rxBuf = ByteArray(DnsttDnsCodec.EDNS0_UDP_PAYLOAD_SIZE)
            while (isActive && !udpSocket.isClosed) {
              try {
                val packet = DatagramPacket(rxBuf, rxBuf.size)
                udpSocket.receive(packet)
                val data = rxBuf.copyOf(packet.length)
                val kcpPackets = DnsttDnsCodec.parseDnsTxtResponsePackets(data)
                if (!kcpPackets.isNullOrEmpty()) {
                  pollBoostUntilMs.set(System.currentTimeMillis() + POLL_BOOST_WINDOW_MS)
                  kcpPackets.forEach { kcpPkt -> kcpSession.input(kcpPkt) }
                  // Trigger an immediate follow-up poll so queued server data drains quickly
                  if (outboundQueue.isEmpty()) {
                    outboundQueue.offer(EMPTY_POLL_MARKER)
                  }
                }
              } catch (_: SocketTimeoutException) {
                // Normal poll interval timeout
              } catch (_: SocketException) {
                break
              } catch (_: IOException) {
                if (udpSocket.isClosed) break
              }
            }
          }
        resources.jobs.add(recvJob)

        // 2. UDP DNS Sender + Adaptive Poll loop
        val sendJob =
          scope.launch {
            var lastSendMs = 0L
            while (isActive && !udpSocket.isClosed) {
              try {
                val now = System.currentTimeMillis()
                val pollInterval =
                  if (now < pollBoostUntilMs.get() || kcpSession.hasPendingWork()) ACTIVE_POLL_INTERVAL_MS
                  else IDLE_POLL_INTERVAL_MS
                val waitMs = (pollInterval - (now - lastSendMs)).coerceIn(10L, pollInterval)
                val nextPkt = outboundQueue.poll(waitMs, TimeUnit.MILLISECONDS)
                val payloadToSend =
                  when {
                    nextPkt == null -> ByteArray(0) // Empty poll query
                    nextPkt === EMPTY_POLL_MARKER -> ByteArray(0)
                    else -> nextPkt
                  }

                val txId = txIdGen.incrementAndGet() and 0xFFFF
                val dnsQuery =
                  DnsttDnsCodec.buildDnsTxtQuery(
                    txId = txId,
                    clientId = clientId,
                    domain = cleanDomain,
                    kcpPacket = payloadToSend,
                  )
                udpSocket.send(DatagramPacket(dnsQuery, dnsQuery.size, resolverAddr))
                lastSendMs = System.currentTimeMillis()
              } catch (_: InterruptedException) {
                break
              } catch (_: SocketException) {
                break
              } catch (_: IOException) {
                if (udpSocket.isClosed) break
              }
            }
          }
        resources.jobs.add(sendJob)

        // 3. KCP 25ms flush/retransmit timer
        val kcpTimerJob =
          scope.launch {
            while (isActive && !udpSocket.isClosed) {
              kcpSession.update(System.currentTimeMillis())
              delay(KCP_TICK_MS)
            }
          }
        resources.jobs.add(kcpTimerJob)

        // 4. Perform real Noise_NK_25519_ChaChaPoly_BLAKE2s handshake with enforceable timeout
        logStore.append(
          tag = "DNSTT",
          message = "DNS Tunnel (KCP + Noise_NK) Handshake စတင်နေသည် ($cleanDomain, MTU=$kcpMtu)...",
          level = LogLevel.INFO,
        )
        val handshakeTimedOut = AtomicBoolean(false)
        val handshakeTimer =
          scope.launch {
            delay(config.handshakeTimeoutMs)
            handshakeTimedOut.set(true)
            kcpSession.close("DNSTT handshake timeout")
          }
        val noiseChannel =
          try {
            DnsttNoiseChannel.connectClient(
              input = kcpSession.inputStream,
              output = kcpSession.outputStream,
              serverPublicKey = pubKeyBytes,
            )
          } catch (e: IOException) {
            if (handshakeTimedOut.get()) {
              val timeoutSec = ((config.handshakeTimeoutMs + 999L) / 1000L).coerceAtLeast(1L)
              throw IOException(
                "DNSTT DNS Tunnel ချိတ်ဆက်ချိန် ကျော်လွန်သွားပါသည် (${timeoutSec}s) — Nameserver သို့မဟုတ် Public Key နှင့် UDP DNS (${resolverEndpoint.first}:${resolverEndpoint.second}) ကို စစ်ဆေးပါ",
                e,
              )
            }
            throw e
          } finally {
            handshakeTimer.cancel()
          }

        if (userRequestedStop) {
          noiseChannel.close()
          throw CancellationException("DNSTT ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ")
        }
        resources.noiseChannel = noiseChannel

        // 5. Start smux v2 session over authenticated Noise channel
        val currentRes = resources
        val smuxSession =
          SmuxClientSession(
            input = noiseChannel.inputStream,
            output = noiseChannel.outputStream,
            isClient = true,
            onSessionClosed = { cause ->
              if (!userRequestedStop && activeResources === currentRes) {
                val dropReason =
                  "DNSTT DNS Tunnel ပြတ်တောက်သွားပါသည် (${cause?.message ?: "session closed"})"
                logStore.append(tag = "DNSTT", message = dropReason, level = LogLevel.WARNING)
                scope.launch {
                  teardownInternal(emitLog = false)
                  _state.value = DnsttState.Error(dropReason)
                }
              }
            },
          )
        resources.smuxSession = smuxSession
        smuxSession.start()

        // 6. Start Local TCP Loopback Accept Loop (forwards SSH connections into smux streams)
        val acceptJob =
          scope.launch {
            while (isActive && !serverSocket.isClosed) {
              val clientSocket =
                try {
                  serverSocket.accept()
                } catch (_: IOException) {
                  break
                }
              currentRes.clientSockets.add(clientSocket)
              launch { bridgeLocalSocketToSmux(clientSocket, smuxSession, currentRes) }
            }
          }
        resources.jobs.add(acceptJob)

        lifecycleMutex.withLock {
          if (userRequestedStop) {
            resources.closeAll()
            activeResources = null
            _state.value = DnsttState.Stopped
            return@withContext Result.failure(CancellationException("DNSTT ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ"))
          }
          _state.value =
            DnsttState.Running(
              nameserver = cleanDomain,
              resolverAddress = "${resolverEndpoint.first}:${resolverEndpoint.second}",
              localHost = DnsttConfig.LOOPBACK_HOST,
              localPort = localPort,
            )
        }

        logStore.append(
          tag = "DNSTT",
          message = "DNSTT DNS Tunnel အောင်မြင်ပြီး Local Port ${DnsttConfig.LOOPBACK_HOST}:$localPort အဆင်သင့်ဖြစ်ပါပြီ",
          level = LogLevel.SUCCESS,
        )
        Result.success(localPort)
      } catch (e: CancellationException) {
        resources?.closeAll()
        teardownInternal(emitLog = false)
        Result.failure(e)
      } catch (e: Throwable) {
        resources?.closeAll()
        teardownInternal(emitLog = false)
        val reason =
          e.message?.takeIf { it.isNotBlank() }
            ?: "DNSTT ချိတ်ဆက်မှု မအောင်မြင်ပါ ($cleanDomain)"
        _state.value = DnsttState.Error(reason)
        logStore.append(tag = "DNSTT", message = reason, level = LogLevel.ERROR)
        Result.failure(IOException(reason, e))
      }
    }
  }

  override suspend fun stop() {
    userRequestedStop = true
    teardownInternal(emitLog = true)
  }

  override fun stopNow() {
    userRequestedStop = true
    val prev = activeResources
    activeResources = null
    runCatching { prev?.closeAll() }
    _state.value = DnsttState.Stopped
  }

  private suspend fun teardownInternal(emitLog: Boolean) {
    val hadResources =
      lifecycleMutex.withLock {
        val prev = activeResources
        activeResources = null
        val wasActive =
          prev != null || _state.value is DnsttState.Running || _state.value is DnsttState.Starting
        withContext(Dispatchers.IO) { prev?.closeAll() }
        _state.value = DnsttState.Stopped
        wasActive
      }
    if (emitLog && hadResources) {
      logStore.append(
        tag = "DNSTT",
        message = "DNSTT Client နှင့် Local Forward Port ကို ပိတ်သိမ်းပြီးပါပြီ",
        level = LogLevel.INFO,
      )
    }
  }

  private suspend fun bridgeLocalSocketToSmux(
    localSocket: Socket,
    smuxSession: SmuxClientSession,
    res: ActiveTunnelResources,
  ) {
    var stream: SmuxClientSession.SmuxStream? = null
    try {
      localSocket.tcpNoDelay = true
      stream = smuxSession.openStream()
      val activeStream = stream
      val upstream =
        scope.launch {
          try {
            copyStream(localSocket.getInputStream(), activeStream.outputStream)
          } finally {
            runCatching { activeStream.close() }
          }
        }
      val downstream =
        scope.launch {
          try {
            copyStream(activeStream.inputStream, localSocket.getOutputStream())
          } finally {
            runCatching { localSocket.close() }
          }
        }
      upstream.join()
      downstream.join()
    } catch (_: Throwable) {
      // Closed cleanly during disconnect
    } finally {
      runCatching { stream?.close() }
      runCatching { localSocket.close() }
      res.clientSockets.remove(localSocket)
    }
  }

  private fun copyStream(src: InputStream, dst: OutputStream) {
    val buf = ByteArray(4096)
    try {
      while (true) {
        val n = src.read(buf)
        if (n <= 0) break
        dst.write(buf, 0, n)
        dst.flush()
      }
    } catch (_: IOException) {
      // Stream terminated
    }
  }

  companion object {
    private const val UDP_READ_TIMEOUT_MS = 1_000
    private const val KCP_TICK_MS = 25L
    private const val ACTIVE_POLL_INTERVAL_MS = 60L
    private const val IDLE_POLL_INTERVAL_MS = 350L
    private const val POLL_BOOST_WINDOW_MS = 1_500L
    private val EMPTY_POLL_MARKER = ByteArray(0)
  }
}
