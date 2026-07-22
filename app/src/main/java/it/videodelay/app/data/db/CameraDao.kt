package it.videodelay.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {

    @Query("SELECT * FROM cameras ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<CameraEntity>>

    @Query("SELECT * FROM cameras ORDER BY createdAt DESC")
    suspend fun getAll(): List<CameraEntity>

    @Query("SELECT * FROM cameras WHERE id = :id")
    suspend fun getById(id: Long): CameraEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(camera: CameraEntity): Long

    @Update
    suspend fun update(camera: CameraEntity)

    @Delete
    suspend fun delete(camera: CameraEntity)

    @Query("DELETE FROM cameras WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM cameras")
    suspend fun deleteAll()
}
