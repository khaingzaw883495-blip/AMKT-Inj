package com.example.openvpn

import com.example.config.Base64Compat
import com.example.config.MyVpnCrypto

/**
 * Encrypts and decrypts OpenVPN `.ovpn` config payloads and `auth-user-pass` credentials at rest
 * using AMKT Injector's existing AES-256-GCM envelope ([MyVpnCrypto] + [com.example.config.NativeKeyBridge]).
 *
 * Neither raw `.ovpn` certificates/keys/remotes nor user credentials are stored in plaintext in Room.
 */
object OvpnSecretStore {
  private const val ENCRYPTED_PREFIX = "AMKTENC1:"

  fun isEncrypted(value: String): Boolean = value.startsWith(ENCRYPTED_PREFIX)

  fun encrypt(plaintext: String): String {
    if (plaintext.isEmpty()) return ""
    if (isEncrypted(plaintext)) return plaintext
    val envelope = MyVpnCrypto.encryptJson(plaintext)
    return ENCRYPTED_PREFIX + Base64Compat.encode(envelope)
  }

  fun decrypt(stored: String): String {
    if (stored.isEmpty()) return ""
    if (!isEncrypted(stored)) return stored
    val payload = stored.removePrefix(ENCRYPTED_PREFIX)
    val bytes =
      Base64Compat.decodeOrNull(payload)
        ?: throw IllegalArgumentException("Encrypted OpenVPN secret is corrupted")
    return MyVpnCrypto.decryptToJson(bytes)
  }

  fun decryptOrBlank(stored: String): String {
    return runCatching { decrypt(stored) }.getOrDefault("")
  }
}
