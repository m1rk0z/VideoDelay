package it.videodelay.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val rtspUrl: String,
    val createdAt: Long = System.currentTimeMillis()
)
