package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Frequently changing Server / Account configuration (HTTP Injector "SSH / Account Settings" model).
 * Contains host, port, credentials, DNSTT/VLESS details, and expiry — NO payload, proxy, or SNI.
 */
@Entity(tableName = "server_accounts")
data class ServerAccountEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val name: String,
  val connectionMode: String = ConnectionMode.SSH_DIRECT.code,
  val host: String = "",
  val port: Int = DEFAULT_SSH_PORT,
  val username: String = "",
  val password: String = "",
  val dnsttNameserver: String = "",
  val dnsttPublicKey: String = "",
  val dnsttResolver: String = ServerProfileEntity.DEFAULT_DNSTT_RESOLVER,
  val vlessLink: String = "",
  val vlessUuid: String = "",
  val expiryDate: String = "",
  val region: String = "",
  val tags: String = "",
  val notes: String = "",
  val locked: Boolean = false,
  val defaultNetworkId: Long? = null,
  val wasSelectedOnMigration: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
) {
  /**
   * Canonical server-level connection mode: SSH_DIRECT (SSH), DNSTT, or VLESS_WS.
   * Legacy SSH_PROXY / SSH_SSL / SSH_WEBSOCKET codes normalize cleanly to SSH_DIRECT because
   * transport payload / proxy / SSL settings live on [NetworkConfigEntity].
   */
  val resolvedConnectionMode: ConnectionMode
    get() =
      when (ConnectionMode.fromCode(connectionMode)) {
        ConnectionMode.DNSTT -> ConnectionMode.DNSTT
        ConnectionMode.VLESS_WS -> ConnectionMode.VLESS_WS
        else -> ConnectionMode.SSH_DIRECT
      }

  fun isDnsttMode(): Boolean = resolvedConnectionMode == ConnectionMode.DNSTT

  fun isVlessMode(): Boolean = resolvedConnectionMode == ConnectionMode.VLESS_WS

  fun isSshMode(): Boolean = resolvedConnectionMode == ConnectionMode.SSH_DIRECT

  fun effectiveDnsttResolver(): String =
    dnsttResolver.trim().ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER }

  fun effectiveVlessUuid(): String = vlessUuid.trim().ifBlank { username.trim() }

  fun isExpired(todayYmd: Int = ProfileExpiryChecker.currentLocalYmd()): Boolean =
    ProfileExpiryChecker.isExpired(expiryDate, todayYmd)

  fun primaryEndpointSummary(): String {
    return when (resolvedConnectionMode) {
      ConnectionMode.VLESS_WS -> {
        if (host.isNotBlank()) "$host:$port"
        else if (vlessLink.isNotBlank()) "vless:// configured"
        else "—"
      }
      ConnectionMode.DNSTT -> {
        if (dnsttNameserver.isNotBlank()) dnsttNameserver
        else host.ifBlank { "—" }
      }
      else -> {
        if (host.isNotBlank()) "$host:$port" else "—"
      }
    }
  }

  fun requiresAccountInput(): Boolean {
    return when (resolvedConnectionMode) {
      ConnectionMode.VLESS_WS -> vlessLink.isBlank() && effectiveVlessUuid().isBlank()
      else -> username.isBlank() || password.isBlank()
    }
  }

  companion object {
    const val DEFAULT_SSH_PORT = 22

    val SUPPORTED_SERVER_MODES: List<ConnectionMode> =
      listOf(ConnectionMode.SSH_DIRECT, ConnectionMode.DNSTT, ConnectionMode.VLESS_WS)
  }
}
