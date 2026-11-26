package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.vless.VlessConfigParser

@Database(
  entities = [
    ServerProfileEntity::class,
    NetworkConfigEntity::class,
    ServerAccountEntity::class,
  ],
  version = 7,
  exportSchema = false,
)
abstract class AmktDatabase : RoomDatabase() {
  abstract fun serverProfileDao(): ServerProfileDao
  abstract fun networkConfigDao(): NetworkConfigDao
  abstract fun serverAccountDao(): ServerAccountDao

  companion object {
    @Volatile private var instance: AmktDatabase? = null

    /**
     * Migrates v1 -> v2 adding per-profile payloadMode and extraHeaders columns
     * without losing any saved profiles, credentials, or user edits.
     */
    val MIGRATION_1_2: Migration =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE server_profiles ADD COLUMN payloadMode TEXT NOT NULL DEFAULT 'DIRECT'"
          )
          db.execSQL(
            "ALTER TABLE server_profiles ADD COLUMN extraHeaders TEXT NOT NULL DEFAULT ''"
          )
          db.execSQL(
            """
            UPDATE server_profiles
            SET payloadMode = CASE
              WHEN mode = 'SSH_WEBSOCKET' THEN 'WEBSOCKET'
              WHEN mode = 'SSH_SSL' THEN 'SSL_TLS_SNI'
              WHEN mode = 'SSH_PROXY' THEN 'HTTP_PAYLOAD'
              ELSE 'DIRECT'
            END
            """.trimIndent()
          )
        }
      }

    /**
     * Migrates v2 -> v3 adding per-profile dnsttResolver (default public UDP resolver 8.8.8.8:53)
     * without overwriting any saved profiles, credentials, or user edits.
     */
    val MIGRATION_2_3: Migration =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE server_profiles ADD COLUMN dnsttResolver TEXT NOT NULL DEFAULT '8.8.8.8:53'"
          )
        }
      }

    /**
     * Migrates v3 -> v4 (Milestone 7):
     * Creates separate `network_configs` and `server_accounts` tables and automatically splits
     * EVERY existing `server_profiles` row into:
     * - a Network row (deduplicated whenever two profiles share identical normalized network settings)
     * - a Server/Account row pointing `defaultNetworkId` at its Network row.
     * Preserves 100% of host, port, credentials, DNSTT keys/resolver, VLESS link/UUID, expiry,
     * payload, headers, SNI, proxy, locked flags, notes, and previously selected state.
     */
    val MIGRATION_3_4: Migration =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `network_configs` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `name` TEXT NOT NULL,
              `payloadMode` TEXT NOT NULL,
              `payloadTemplate` TEXT NOT NULL,
              `sniHost` TEXT NOT NULL,
              `extraHeaders` TEXT NOT NULL,
              `httpProxyHost` TEXT NOT NULL,
              `httpProxyPort` INTEGER NOT NULL,
              `locked` INTEGER NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `server_accounts` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `name` TEXT NOT NULL,
              `connectionMode` TEXT NOT NULL,
              `host` TEXT NOT NULL,
              `port` INTEGER NOT NULL,
              `username` TEXT NOT NULL,
              `password` TEXT NOT NULL,
              `dnsttNameserver` TEXT NOT NULL,
              `dnsttPublicKey` TEXT NOT NULL,
              `dnsttResolver` TEXT NOT NULL,
              `vlessLink` TEXT NOT NULL,
              `vlessUuid` TEXT NOT NULL,
              `expiryDate` TEXT NOT NULL,
              `region` TEXT NOT NULL,
              `tags` TEXT NOT NULL,
              `notes` TEXT NOT NULL,
              `locked` INTEGER NOT NULL,
              `defaultNetworkId` INTEGER,
              `wasSelectedOnMigration` INTEGER NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
          )

          val signatureToNetworkId = LinkedHashMap<String, Long>()
          val cursor =
            db.query(
              """
              SELECT name, mode, sshHost, sshPort, username, password, payload, proxyHost, proxyPort,
                     sni, payloadMode, extraHeaders, dnsttNameserver, dnsttPublicKey, dnsttResolver,
                     vlessLink, expiryDate, locked, notes, isSelected, createdAt, updatedAt
              FROM server_profiles
              ORDER BY id ASC
              """.trimIndent()
            )

          cursor.use { c ->
            while (c.moveToNext()) {
              val name = c.getString(0).orEmpty()
              val legacyModeCode = c.getString(1).orEmpty()
              val sshHost = c.getString(2).orEmpty()
              val sshPort = c.getInt(3)
              val username = c.getString(4).orEmpty()
              val password = c.getString(5).orEmpty()
              val payload = c.getString(6).orEmpty()
              val proxyHost = c.getString(7).orEmpty()
              val proxyPort = c.getInt(8)
              val sni = c.getString(9).orEmpty()
              val rawPayloadMode = c.getString(10).orEmpty()
              val extraHeaders = c.getString(11).orEmpty()
              val dnsttNameserver = c.getString(12).orEmpty()
              val dnsttPublicKey = c.getString(13).orEmpty()
              val dnsttResolver =
                c.getString(14).orEmpty().ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER }
              val vlessLink = c.getString(15).orEmpty()
              val expiryDate = c.getString(16).orEmpty()
              val locked = c.getInt(17)
              val notes = c.getString(18).orEmpty()
              val isSelected = c.getInt(19)
              val createdAt = c.getLong(20)
              val updatedAt = c.getLong(21)

              val parsedLegacyMode = ConnectionMode.fromCode(legacyModeCode) ?: ConnectionMode.SSH_DIRECT
              val resolvedPayloadMode =
                PayloadMode.fromCode(rawPayloadMode)
                  ?: PayloadMode.defaultForConnectionMode(parsedLegacyMode)

              val signature =
                NetworkConfigEntity.computeSignature(
                  payloadMode = resolvedPayloadMode.code,
                  payloadTemplate = payload,
                  sniHost = sni,
                  extraHeaders = extraHeaders,
                  httpProxyHost = proxyHost,
                  httpProxyPort = proxyPort,
                )

              val networkId =
                signatureToNetworkId.getOrPut(signature) {
                  val netValues =
                    ContentValues().apply {
                      put("name", name.trim().ifBlank { "Network" })
                      put("payloadMode", resolvedPayloadMode.code)
                      put("payloadTemplate", payload)
                      put("sniHost", sni.trim())
                      put("extraHeaders", extraHeaders)
                      put("httpProxyHost", proxyHost.trim())
                      put("httpProxyPort", proxyPort.coerceIn(1, 65535))
                      put("locked", locked)
                      put("createdAt", createdAt)
                      put("updatedAt", updatedAt)
                    }
                  db.insert("network_configs", SQLiteDatabase.CONFLICT_ABORT, netValues)
                }

              val serverModeCode =
                when (parsedLegacyMode) {
                  ConnectionMode.DNSTT -> ConnectionMode.DNSTT.code
                  ConnectionMode.VLESS_WS -> ConnectionMode.VLESS_WS.code
                  else -> ConnectionMode.SSH_DIRECT.code
                }

              val vlessUuid =
                if (serverModeCode == ConnectionMode.VLESS_WS.code) {
                  VlessConfigParser.parseLink(vlessLink).getOrNull()?.uuid
                    ?: username.trim()
                } else {
                  ""
                }

              val srvValues =
                ContentValues().apply {
                  put("name", name.trim().ifBlank { "Server" })
                  put("connectionMode", serverModeCode)
                  put("host", sshHost.trim())
                  put("port", sshPort.coerceIn(1, 65535))
                  put("username", if (serverModeCode == ConnectionMode.VLESS_WS.code) "" else username.trim())
                  put("password", password)
                  put("dnsttNameserver", dnsttNameserver.trim())
                  put("dnsttPublicKey", dnsttPublicKey.trim())
                  put("dnsttResolver", dnsttResolver)
                  put("vlessLink", vlessLink.trim())
                  put("vlessUuid", vlessUuid)
                  put("expiryDate", expiryDate.trim())
                  put("region", "")
                  put("tags", "")
                  put("notes", notes.trim())
                  put("locked", locked)
                  put("defaultNetworkId", networkId)
                  put("wasSelectedOnMigration", isSelected)
                  put("createdAt", createdAt)
                  put("updatedAt", updatedAt)
                }
              db.insert("server_accounts", SQLiteDatabase.CONFLICT_ABORT, srvValues)
            }
          }
        }
      }

    /**
     * Migrates v4 -> v5 (OpenVPN transport):
     * Adds encrypted `.ovpn` storage columns and `isUserImported` ownership flag without touching
     * any existing Networks, Servers, credentials, or legacy profiles.
     */
    val MIGRATION_4_5: Migration =
      object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE server_accounts ADD COLUMN ovpnConfigEncrypted TEXT NOT NULL DEFAULT ''"
          )
          db.execSQL(
            "ALTER TABLE server_accounts ADD COLUMN ovpnAuthRequired INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL(
            "ALTER TABLE server_accounts ADD COLUMN isUserImported INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL(
            "ALTER TABLE server_profiles ADD COLUMN ovpnConfigEncrypted TEXT NOT NULL DEFAULT ''"
          )
          db.execSQL(
            "ALTER TABLE server_profiles ADD COLUMN ovpnAuthRequired INTEGER NOT NULL DEFAULT 0"
          )
        }
      }

    /**
     * Migrates v5 -> v6 (Remote deletion tracking + Server Online/Limit):
     * Adds `isUserImported` / `isFromRemote` ownership flags on `network_configs` and
     * `isFromRemote` / `onlineCount` / `userLimit` on `server_accounts`.
     * Existing remote rows default to `isFromRemote = 1` so stale remote entries removed by the
     * owner clean up on the next Update, while user-imported and INJ-redeemed rows are marked
     * `isFromRemote = 0` so Update never deletes them.
     */
    val MIGRATION_5_6: Migration =
      object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE network_configs ADD COLUMN isUserImported INTEGER NOT NULL DEFAULT 0"
          )
          db.execSQL(
            "ALTER TABLE network_configs ADD COLUMN isFromRemote INTEGER NOT NULL DEFAULT 1"
          )
          db.execSQL(
            "ALTER TABLE server_accounts ADD COLUMN isFromRemote INTEGER NOT NULL DEFAULT 1"
          )
          db.execSQL(
            "UPDATE server_accounts SET isFromRemote = 0 WHERE isUserImported = 1 OR UPPER(tags) LIKE '%REDEEM:%'"
          )
          db.execSQL(
            "ALTER TABLE server_accounts ADD COLUMN onlineCount INTEGER DEFAULT NULL"
          )
          db.execSQL(
            "ALTER TABLE server_accounts ADD COLUMN userLimit INTEGER NOT NULL DEFAULT 100"
          )
        }
      }

    /**
     * Migrates v6 -> v7 (Per-server lifetime distinct user count):
     * Adds nullable `lifetimeUsers` column to `server_accounts`.
     */
    val MIGRATION_6_7: Migration =
      object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE server_accounts ADD COLUMN lifetimeUsers INTEGER DEFAULT NULL"
          )
        }
      }

    fun getInstance(context: Context): AmktDatabase {
      return instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                AmktDatabase::class.java,
                "amkt_injector_profiles.db",
              )
              .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
              )
              .build()
              .also { instance = it }
        }
    }
  }
}
