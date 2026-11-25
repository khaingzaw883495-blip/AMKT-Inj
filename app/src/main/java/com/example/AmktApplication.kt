package com.example

import android.app.Application
import com.example.config.ConfigFileCodec
import com.example.config.NativeKeyBridge
import com.example.data.AmktDatabase
import com.example.data.AppLogStore
import com.example.data.LogLevel
import com.example.data.NetworkServerRepository
import com.example.data.RemoteServerListRepository
import com.example.data.ServerProfileRepository
import com.example.dnstt.DnsttEngine
import com.example.dnstt.NativeKotlinDnsttEngine
import com.example.membership.MembershipRepository
import com.example.openvpn.IcsOpenVpn3TunnelEngine
import com.example.openvpn.OpenVpnTunnelEngine
import com.example.redeem.RedeemRepository
import com.example.ssh.JschSshTunnelEngine
import com.example.ssh.SshTunnelEngine
import com.example.vless.NativeVlessTunnelEngine
import com.example.vless.VlessTunnelEngine
import com.example.vpn.AndroidVpnEngineController
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository

class AmktApplication : Application() {
  lateinit var database: AmktDatabase
    private set

  lateinit var remoteServerListRepository: RemoteServerListRepository
    private set

  lateinit var networkServerRepository: NetworkServerRepository
    private set

  lateinit var repository: ServerProfileRepository
    private set

  lateinit var membershipRepository: MembershipRepository
    private set

  lateinit var redeemRepository: RedeemRepository
    private set

  lateinit var vpnPreferencesRepository: VpnPreferencesRepository
    private set

  lateinit var configCodec: ConfigFileCodec
    private set

  lateinit var logStore: AppLogStore
    private set

  lateinit var sshEngine: SshTunnelEngine
    private set

  lateinit var vpnController: VpnEngineController
    private set

  lateinit var dnsttEngine: DnsttEngine
    private set

  lateinit var vlessEngine: VlessTunnelEngine
    private set

  lateinit var openVpnEngine: OpenVpnTunnelEngine
    private set

  override fun onCreate() {
    super.onCreate()
    database = AmktDatabase.getInstance(this)
    remoteServerListRepository = RemoteServerListRepository(this)
    networkServerRepository =
      NetworkServerRepository(
        networkDao = database.networkConfigDao(),
        serverDao = database.serverAccountDao(),
        remoteServerListRepository = remoteServerListRepository,
      )
    repository = ServerProfileRepository(database.serverProfileDao(), remoteServerListRepository)
    membershipRepository = MembershipRepository(this)
    redeemRepository = RedeemRepository(this)
    vpnPreferencesRepository = VpnPreferencesRepository(this)
    configCodec = ConfigFileCodec()
    logStore = AppLogStore()
    sshEngine = JschSshTunnelEngine(logStore)
    vpnController = AndroidVpnEngineController(this)
    dnsttEngine = NativeKotlinDnsttEngine(logStore)
    vlessEngine = NativeVlessTunnelEngine(logStore)
    openVpnEngine = IcsOpenVpn3TunnelEngine(logStore)

    logStore.append(
      tag = "BOOT",
      message = "AMKT Injector (v1.0.0-m15 — SSH/Payload/DNSTT/VLESS/OpenVPN + VPN) စတင်ဖွင့်လှစ်ပြီးပါပြီ",
      level = LogLevel.INFO,
    )
    val cryptoBackend =
      if (NativeKeyBridge.isNativeLibraryLoaded) "Native NDK C++ (libamkt_crypto.so)"
      else "JVM Obfuscated Fallback (Android Studio NDK build ဖြင့် native .so ထည့်နိုင်သည်)"
    logStore.append(
      tag = "CRYPTO",
      message = ".myvpn AES-256-GCM Key Bridge: $cryptoBackend",
      level = LogLevel.SUCCESS,
    )
  }
}
