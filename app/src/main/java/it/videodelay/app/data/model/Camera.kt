package it.videodelay.app.data.model

import it.videodelay.app.data.db.CameraEntity

data class Camera(
    val id: Long = 0,
    val name: String,
    val rtspUrl: String,
    val createdAt: Long = System.currentTimeMillis()
)

fun Camera.toEntity() = CameraEntity(
    id = id,
    name = name,
    rtspUrl = rtspUrl,
    createdAt = createdAt
)

fun CameraEntity.toDomain() = Camera(
    id = id,
    name = name,
    rtspUrl = rtspUrl,
    createdAt = createdAt
)
