package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerAccountDao {
  @Query("SELECT * FROM server_accounts ORDER BY updatedAt DESC, id ASC")
  fun observeAllServers(): Flow<List<ServerAccountEntity>>

  @Query("SELECT * FROM server_accounts ORDER BY updatedAt DESC, id ASC")
  suspend fun getAllServersSnapshot(): List<ServerAccountEntity>

  @Query("SELECT * FROM server_accounts WHERE id = :id LIMIT 1")
  suspend fun getServerById(id: Long): ServerAccountEntity?

  @Query("SELECT * FROM server_accounts WHERE defaultNetworkId = :networkId ORDER BY name ASC")
  suspend fun getServersReferencingNetwork(networkId: Long): List<ServerAccountEntity>

  @Query("SELECT COUNT(*) FROM server_accounts WHERE defaultNetworkId = :networkId")
  suspend fun countServersReferencingNetwork(networkId: Long): Int

  @Query("UPDATE server_accounts SET defaultNetworkId = NULL WHERE defaultNetworkId = :networkId")
  suspend fun clearNetworkReferences(networkId: Long)

  @Query("SELECT COUNT(*) FROM server_accounts")
  suspend fun countServers(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertServer(server: ServerAccountEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertServers(servers: List<ServerAccountEntity>): List<Long>

  @Update
  suspend fun updateServer(server: ServerAccountEntity)

  @Query("UPDATE server_accounts SET lifetimeUsers = :lifetimeUsers WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name))")
  suspend fun updateLifetimeUsersByName(name: String, lifetimeUsers: Int)

  @Query("DELETE FROM server_accounts WHERE id = :id")
  suspend fun deleteServerById(id: Long)

  @Query("DELETE FROM server_accounts")
  suspend fun deleteAllServers()

  @Transaction
  suspend fun replaceAllServers(servers: List<ServerAccountEntity>): List<Long> {
    deleteAllServers()
    return insertServers(servers)
  }
}
