package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val name: String,
  val mode: String = ConnectionMode.SSH_DIRECT.code,
  val sshHost: String = "",
  val sshPort: Int = 22,
  val username: String = "",
  val password: String = "",
  val payload: String = "",
  val proxyHost: String = "",
  val proxyPort: Int = 8080,
  val sni: String = "",
  val payloadMode: String = PayloadMode.DIRECT.code,
  val extraHeaders: String = "",
  val dnsttNameserver: String = "",
  val dnsttPublicKey: String = "",
  val dnsttResolver: String = DEFAULT_DNSTT_RESOLVER,
  val vlessLink: String = "",
  val expiryDate: String = "",
  val locked: Boolean = false,
  val notes: String = "",
  val isSelected: Boolean = false,
  val ovpnConfigEncrypted: String = "",
  val ovpnAuthRequired: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
) {
  val connectionMode: ConnectionMode
    get() = ConnectionMode.fromCode(mode) ?: ConnectionMode.SSH_DIRECT

  val resolvedPayloadMode: PayloadMode
    get() = PayloadMode.fromCode(payloadMode) ?: PayloadMode.defaultForConnectionMode(connectionMode)

  val payloadTemplate: String
    get() = payload

  val sniHost: String
    get() = sni

  fun effectiveSniHost(): String = sni.trim().ifBlank { sshHost.trim() }

  fun effectiveDnsttResolver(): String = dnsttResolver.trim().ifBlank { DEFAULT_DNSTT_RESOLVER }

  fun isDnsttMode(): Boolean = connectionMode == ConnectionMode.DNSTT

  fun isVlessMode(): Boolean = connectionMode == ConnectionMode.VLESS_WS

  fun isOpenVpnMode(): Boolean = connectionMode == ConnectionMode.OPENVPN

  fun isExpired(todayYmd: Int = ProfileExpiryChecker.currentLocalYmd()): Boolean =
    ProfileExpiryChecker.isExpired(expiryDate, todayYmd)

  fun supportsSshTunnel(): Boolean {
    return when (connectionMode) {
      ConnectionMode.SSH_DIRECT,
      ConnectionMode.SSH_PROXY,
      ConnectionMode.SSH_SSL,
      ConnectionMode.SSH_WEBSOCKET,
      ConnectionMode.DNSTT -> true
      ConnectionMode.VLESS_WS,
      ConnectionMode.OPENVPN -> false
    }
  }

  fun supportsVpnTunnel(): Boolean = supportsSshTunnel() || isVlessMode() || isOpenVpnMode()

  fun primaryEndpointSummary(): String {
    return when (connectionMode) {
      ConnectionMode.OPENVPN -> "OpenVPN"
      ConnectionMode.VLESS_WS -> {
        if (sshHost.isNotBlank()) "$sshHost:$sshPort"
        else if (vlessLink.isNotBlank()) "vless:// configured"
        else "—"
      }
      ConnectionMode.DNSTT -> {
        if (dnsttNameserver.isNotBlank()) dnsttNameserver
        else sshHost.ifBlank { "—" }
      }
      else -> {
        if (sshHost.isNotBlank()) "$sshHost:$sshPort" else "—"
      }
    }
  }

  fun requiresAccountInput(): Boolean {
    return when (connectionMode) {
      ConnectionMode.OPENVPN -> ovpnAuthRequired && username.isBlank()
      ConnectionMode.VLESS_WS -> vlessLink.isBlank() && username.isBlank()
      else -> username.isBlank() || password.isBlank()
    }
  }

  companion object {
    const val DEFAULT_DNSTT_RESOLVER: String = "8.8.8.8:53"
  }
}
