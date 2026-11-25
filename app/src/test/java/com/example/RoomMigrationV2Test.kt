package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AmktDatabase
import com.example.data.ConnectionMode
import com.example.data.PayloadMode
import com.example.data.ServerProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationV2Test {

  @Test
  fun migration1To2PreservesExistingProfilesAndBackfillsPayloadMode() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "test_amkt_migration_1_2.db"
    context.deleteDatabase(dbName)

    // 1. Seed a genuine v1 SQLite database matching Milestone 1-3 schema
    val dbFile = context.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()
    val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `server_profiles` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL,
        `mode` TEXT NOT NULL,
        `sshHost` TEXT NOT NULL,
        `sshPort` INTEGER NOT NULL,
        `username` TEXT NOT NULL,
        `password` TEXT NOT NULL,
        `payload` TEXT NOT NULL,
        `proxyHost` TEXT NOT NULL,
        `proxyPort` INTEGER NOT NULL,
        `sni` TEXT NOT NULL,
        `dnsttNameserver` TEXT NOT NULL,
        `dnsttPublicKey` TEXT NOT NULL,
        `vlessLink` TEXT NOT NULL,
        `expiryDate` TEXT NOT NULL,
        `locked` INTEGER NOT NULL,
        `notes` TEXT NOT NULL,
        `isSelected` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      """
      INSERT INTO server_profiles
      (id, name, mode, sshHost, sshPort, username, password, payload, proxyHost, proxyPort, sni, dnsttNameserver, dnsttPublicKey, vlessLink, expiryDate, locked, notes, isSelected, createdAt, updatedAt)
      VALUES
      (1, 'Direct Profile', 'SSH_DIRECT', '198.51.100.1', 22, 'alice', 'secret1', '', '', 8080, '', '', '', '', '', 0, 'keep me', 1, 1000, 1000),
      (2, 'WS Profile', 'SSH_WEBSOCKET', 'ws.example.com', 80, 'bob', 'secret2', 'GET / HTTP/1.1[crlf][crlf]', '', 8080, 'front.example.com', '', '', '', '', 0, '', 0, 1000, 1000),
      (3, 'SSL Profile', 'SSH_SSL', 'ssl.example.com', 443, 'carol', 'secret3', '', '', 8080, 'sni.example.com', '', '', '', '', 0, '', 0, 1000, 1000)
      """.trimIndent()
    )
    rawDb.version = 1
    rawDb.close()

    // 2. Open with Room v3 + MIGRATION_1_2 + MIGRATION_2_3 and verify preservation + backfill
    val roomDb =
      Room.databaseBuilder(context, AmktDatabase::class.java, dbName)
        .addMigrations(
          AmktDatabase.MIGRATION_1_2,
          AmktDatabase.MIGRATION_2_3,
          AmktDatabase.MIGRATION_3_4,
          AmktDatabase.MIGRATION_4_5,
          AmktDatabase.MIGRATION_5_6,
        )
        .allowMainThreadQueries()
        .build()

    try {
      val dao = roomDb.serverProfileDao()
      val direct = dao.getProfileById(1L)
      val ws = dao.getProfileById(2L)
      val ssl = dao.getProfileById(3L)

      assertNotNull(direct)
      assertEquals("alice", direct!!.username)
      assertEquals("secret1", direct.password)
      assertEquals(PayloadMode.DIRECT, direct.resolvedPayloadMode)
      assertEquals("", direct.extraHeaders)
      assertEquals(ServerProfileEntity.DEFAULT_DNSTT_RESOLVER, direct.dnsttResolver)

      assertNotNull(ws)
      assertEquals(PayloadMode.WEBSOCKET, ws!!.resolvedPayloadMode)
      assertEquals("front.example.com", ws.sniHost)

      assertNotNull(ssl)
      assertEquals(PayloadMode.SSL_TLS_SNI, ssl!!.resolvedPayloadMode)
      assertEquals("sni.example.com", ssl.effectiveSniHost())

      // 3. Insert a new Milestone 4/5 profile with explicit payloadMode + extraHeaders + dnsttResolver
      val insertedId =
        dao.insertProfile(
          ServerProfileEntity(
            name = "Custom M4",
            mode = ConnectionMode.SSH_PROXY.code,
            sshHost = "203.0.113.99",
            sshPort = 80,
            payloadMode = PayloadMode.HTTP_PAYLOAD.code,
            payload = "CONNECT [host]:[port] HTTP/1.1[crlf][crlf]",
            sni = "custom-sni.example.org",
            extraHeaders = "X-Online-Host: [sni]\nUser-Agent: AMKT/4",
            dnsttResolver = "1.1.1.1:53",
          )
        )
      val reloaded = dao.getProfileById(insertedId)
      assertNotNull(reloaded)
      assertEquals(PayloadMode.HTTP_PAYLOAD, reloaded!!.resolvedPayloadMode)
      assertEquals("custom-sni.example.org", reloaded.sniHost)
      assertEquals("X-Online-Host: [sni]\nUser-Agent: AMKT/4", reloaded.extraHeaders)
      assertEquals("1.1.1.1:53", reloaded.dnsttResolver)
    } finally {
      roomDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun migration2To3PreservesProfilesAndAddsDefaultDnsttResolver() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "test_amkt_migration_2_3.db"
    context.deleteDatabase(dbName)

    val dbFile = context.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()
    val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `server_profiles` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL,
        `mode` TEXT NOT NULL,
        `sshHost` TEXT NOT NULL,
        `sshPort` INTEGER NOT NULL,
        `username` TEXT NOT NULL,
        `password` TEXT NOT NULL,
        `payload` TEXT NOT NULL,
        `proxyHost` TEXT NOT NULL,
        `proxyPort` INTEGER NOT NULL,
        `sni` TEXT NOT NULL,
        `payloadMode` TEXT NOT NULL DEFAULT 'DIRECT',
        `extraHeaders` TEXT NOT NULL DEFAULT '',
        `dnsttNameserver` TEXT NOT NULL,
        `dnsttPublicKey` TEXT NOT NULL,
        `vlessLink` TEXT NOT NULL,
        `expiryDate` TEXT NOT NULL,
        `locked` INTEGER NOT NULL,
        `notes` TEXT NOT NULL,
        `isSelected` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      """
      INSERT INTO server_profiles
      (id, name, mode, sshHost, sshPort, username, password, payload, proxyHost, proxyPort, sni, payloadMode, extraHeaders, dnsttNameserver, dnsttPublicKey, vlessLink, expiryDate, locked, notes, isSelected, createdAt, updatedAt)
      VALUES
      (10, 'Saved DNSTT M4', 'DNSTT', '', 22, 'tester', 'pw123', '', '', 8080, '', 'DIRECT', '', 'ns.example.org', '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '', '', 0, 'from v2', 1, 2000, 2000)
      """.trimIndent()
    )
    rawDb.version = 2
    rawDb.close()

    val roomDb =
      Room.databaseBuilder(context, AmktDatabase::class.java, dbName)
        .addMigrations(
          AmktDatabase.MIGRATION_1_2,
          AmktDatabase.MIGRATION_2_3,
          AmktDatabase.MIGRATION_3_4,
          AmktDatabase.MIGRATION_4_5,
          AmktDatabase.MIGRATION_5_6,
        )
        .allowMainThreadQueries()
        .build()

    try {
      val dao = roomDb.serverProfileDao()
      val migrated = dao.getProfileById(10L)
      assertNotNull(migrated)
      assertEquals("Saved DNSTT M4", migrated!!.name)
      assertEquals("ns.example.org", migrated.dnsttNameserver)
      assertEquals("tester", migrated.username)
      assertEquals("pw123", migrated.password)
      assertEquals(ServerProfileEntity.DEFAULT_DNSTT_RESOLVER, migrated.dnsttResolver)

      // Update resolver and verify persistence
      dao.updateProfile(migrated.copy(dnsttResolver = "9.9.9.9:53"))
      val updated = dao.getProfileById(10L)
      assertEquals("9.9.9.9:53", updated?.dnsttResolver)
    } finally {
      roomDb.close()
      context.deleteDatabase(dbName)
    }
  }
}
