package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkConfigDao {
  @Query("SELECT * FROM network_configs ORDER BY updatedAt DESC, id ASC")
  fun observeAllNetworks(): Flow<List<NetworkConfigEntity>>

  @Query("SELECT * FROM network_configs ORDER BY updatedAt DESC, id ASC")
  suspend fun getAllNetworksSnapshot(): List<NetworkConfigEntity>

  @Query("SELECT * FROM network_configs WHERE id = :id LIMIT 1")
  suspend fun getNetworkById(id: Long): NetworkConfigEntity?

  @Query("SELECT COUNT(*) FROM network_configs")
  suspend fun countNetworks(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertNetwork(network: NetworkConfigEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertNetworks(networks: List<NetworkConfigEntity>): List<Long>

  @Update
  suspend fun updateNetwork(network: NetworkConfigEntity)

  @Query("DELETE FROM network_configs WHERE id = :id")
  suspend fun deleteNetworkById(id: Long)

  @Query("DELETE FROM network_configs")
  suspend fun deleteAllNetworks()

  @Transaction
  suspend fun replaceAllNetworks(networks: List<NetworkConfigEntity>): List<Long> {
    deleteAllNetworks()
    return insertNetworks(networks)
  }
}
