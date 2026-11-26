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
  val ovpnConfigEncrypted: String = "",
  val ovpnAuthRequired: Boolean = false,
  val isUserImported: Boolean = false,
  val isFromRemote: Boolean = false,
  val onlineCount: Int? = null,
  val userLimit: Int = DEFAULT_USER_LIMIT,
  val lifetimeUsers: Int? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
) {
  /**
   * Canonical server-level connection mode: SSH_DIRECT (SSH), DNSTT, VLESS_WS, or OPENVPN.
   * Legacy SSH_PROXY / SSH_SSL / SSH_WEBSOCKET codes normalize cleanly to SSH_DIRECT because
   * transport payload / proxy / SSL settings live on [NetworkConfigEntity].
   */
  val resolvedConnectionMode: ConnectionMode
    get() =
      when (ConnectionMode.fromCode(connectionMode)) {
        ConnectionMode.DNSTT -> ConnectionMode.DNSTT
        ConnectionMode.VLESS_WS -> ConnectionMode.VLESS_WS
        ConnectionMode.OPENVPN -> ConnectionMode.OPENVPN
        else -> ConnectionMode.SSH_DIRECT
      }

  fun isDnsttMode(): Boolean = resolvedConnectionMode == ConnectionMode.DNSTT

  fun isVlessMode(): Boolean = resolvedConnectionMode == ConnectionMode.VLESS_WS

  fun isOpenVpnMode(): Boolean = resolvedConnectionMode == ConnectionMode.OPENVPN

  fun isSshMode(): Boolean = resolvedConnectionMode == ConnectionMode.SSH_DIRECT

  fun effectiveDnsttResolver(): String =
    dnsttResolver.trim().ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER }

  fun effectiveVlessUuid(): String = vlessUuid.trim().ifBlank { username.trim() }

  fun isExpired(todayYmd: Int = ProfileExpiryChecker.currentLocalYmd()): Boolean =
    ProfileExpiryChecker.isExpired(expiryDate, todayYmd)

  fun isRedeemedServer(): Boolean =
    tags.split(',').any { it.trim().startsWith("REDEEM:", ignoreCase = true) }

  fun primaryEndpointSummary(): String {
    return when (resolvedConnectionMode) {
      ConnectionMode.OPENVPN -> "OpenVPN"
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
      ConnectionMode.OPENVPN -> ovpnAuthRequired && username.isBlank()
      ConnectionMode.VLESS_WS -> vlessLink.isBlank() && effectiveVlessUuid().isBlank()
      else -> username.isBlank() || password.isBlank()
    }
  }

  val effectiveUserLimit: Int
    get() = if (userLimit > 0) userLimit else DEFAULT_USER_LIMIT

  /**
   * Formats GOOD VPN-style online/limit indicator.
   * When [onlineCount] is provided (>= 0), shows `"Online X · Limit Y"`.
   * When [onlineCount] is absent/null, shows `"Limit Y"` only — never fabricates an online count.
   */
  fun formatOnlineLimitLabel(): String {
    val count = onlineCount
    return if (count != null && count >= 0) {
      "Online $count · Limit $effectiveUserLimit"
    } else {
      "Limit $effectiveUserLimit"
    }
  }

  /**
   * Formats lifetime distinct user count (`"Used by: 128 users"`), or `null` when unknown.
   * Never invents or guesses a count.
   */
  fun formatLifetimeUsersLabel(): String? {
    val count = lifetimeUsers ?: return null
    if (count < 0) return null
    val formatted = "%,d".format(java.util.Locale.US, count)
    return "Used by: $formatted users"
  }

  /**
   * Returns normalized usage ratio (`0f..1f`) when [onlineCount] is known and `effectiveUserLimit > 0`,
   * or `null` when [onlineCount] is absent — never fabricates a percentage.
   */
  fun onlineUsageFraction(): Float? {
    val count = onlineCount ?: return null
    if (count < 0 || effectiveUserLimit <= 0) return null
    return (count.toFloat() / effectiveUserLimit.toFloat()).coerceIn(0f, 1f)
  }

  companion object {
    const val DEFAULT_SSH_PORT = 22
    const val DEFAULT_USER_LIMIT = 100

    val SUPPORTED_SERVER_MODES: List<ConnectionMode> =
      listOf(
        ConnectionMode.SSH_DIRECT,
        ConnectionMode.DNSTT,
        ConnectionMode.VLESS_WS,
        ConnectionMode.OPENVPN,
      )
  }
}
