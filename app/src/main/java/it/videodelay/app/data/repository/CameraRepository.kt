package it.videodelay.app.data.repository

import it.videodelay.app.data.db.AppDatabase
import it.videodelay.app.data.model.Camera
import it.videodelay.app.data.model.toDomain
import it.videodelay.app.data.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CameraRepository(private val db: AppDatabase) {

    /** Flow reattivo: emette la lista aggiornata ad ogni modifica */
    val allCameras: Flow<List<Camera>> =
        db.cameraDao().getAllFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<Camera> =
        db.cameraDao().getAll().map { it.toDomain() }

    suspend fun getById(id: Long): Camera? =
        db.cameraDao().getById(id)?.toDomain()

    suspend fun insert(camera: Camera): Long =
        db.cameraDao().insert(camera.toEntity())

    suspend fun update(camera: Camera) =
        db.cameraDao().update(camera.toEntity())

    suspend fun delete(camera: Camera) =
        db.cameraDao().delete(camera.toEntity())

    suspend fun deleteById(id: Long) =
        db.cameraDao().deleteById(id)

    suspend fun deleteAll() =
        db.cameraDao().deleteAll()
}
