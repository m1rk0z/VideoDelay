package it.videodelay.app.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import it.videodelay.app.data.model.Camera
import java.io.File

object JsonImportExport {

    private val gson = Gson()

    /** Esporta la lista telecamere come JSON nel file indicato */
    fun exportToFile(cameras: List<Camera>, file: File) {
        val json = gson.toJson(cameras)
        file.writeText(json, Charsets.UTF_8)
    }

    /** Importa una lista telecamere da stringa JSON */
    fun importFromJson(json: String): List<Camera> {
        val type = object : TypeToken<List<CameraJson>>() {}.type
        val parsed: List<CameraJson> = gson.fromJson(json, type)
        return parsed.map { it.toCamera() }
    }

    /** DTO per import/export (reset id per evitare conflitti) */
    private data class CameraJson(
        val name: String = "",
        val rtspUrl: String = "",
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toCamera() = Camera(id = 0, name = name, rtspUrl = rtspUrl, createdAt = createdAt)
    }
}
