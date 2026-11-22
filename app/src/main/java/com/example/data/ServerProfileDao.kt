package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {
  @Query("SELECT * FROM server_profiles ORDER BY isSelected DESC, updatedAt DESC")
  fun observeAllProfiles(): Flow<List<ServerProfileEntity>>

  @Query("SELECT * FROM server_profiles WHERE isSelected = 1 LIMIT 1")
  fun observeSelectedProfile(): Flow<ServerProfileEntity?>

  @Query("SELECT * FROM server_profiles ORDER BY isSelected DESC, updatedAt DESC")
  suspend fun getAllProfilesSnapshot(): List<ServerProfileEntity>

  @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
  suspend fun getProfileById(id: Long): ServerProfileEntity?

  @Query("SELECT * FROM server_profiles ORDER BY updatedAt DESC LIMIT 1")
  suspend fun getMostRecentProfile(): ServerProfileEntity?

  @Query("SELECT COUNT(*) FROM server_profiles")
  suspend fun countProfiles(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertProfile(profile: ServerProfileEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertProfiles(profiles: List<ServerProfileEntity>): List<Long>

  @Update
  suspend fun updateProfile(profile: ServerProfileEntity)

  @Query("DELETE FROM server_profiles WHERE id = :id")
  suspend fun deleteProfileById(id: Long)

  @Query("DELETE FROM server_profiles")
  suspend fun deleteAllProfiles()

  @Query("UPDATE server_profiles SET isSelected = 0")
  suspend fun clearSelection()

  @Query("UPDATE server_profiles SET isSelected = 1, updatedAt = :timestamp WHERE id = :id")
  suspend fun markSelected(id: Long, timestamp: Long = System.currentTimeMillis())

  @Transaction
  suspend fun selectProfile(id: Long) {
    clearSelection()
    markSelected(id)
  }

  @Transaction
  suspend fun replaceAllAndSelectFirst(profiles: List<ServerProfileEntity>): List<Long> {
    deleteAllProfiles()
    if (profiles.isEmpty()) return emptyList()
    val ids = insertProfiles(profiles)
    ids.firstOrNull()?.let { firstId ->
      clearSelection()
      markSelected(firstId)
    }
    return ids
  }
}
