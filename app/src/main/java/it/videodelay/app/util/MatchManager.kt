package it.videodelay.app.util

import android.content.Context
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.*

/**
 * Gestisce la suddivisione dei contenuti (screenshot e clip) in sottocartelle per Partita / Sessione.
 */
object MatchManager {

    private const val PREFS_NAME = "videodelay_prefs"
    private const val KEY_ACTIVE_MATCH = "active_match_folder"
    private const val DEFAULT_MATCH_NAME = "Partita_Generale"

    /** Restituisce il nome della partita attiva corrente. */
    fun getActiveMatch(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_MATCH, DEFAULT_MATCH_NAME) ?: DEFAULT_MATCH_NAME
    }

    /** Imposta la partita attiva corrente. */
    fun setActiveMatch(context: Context, matchName: String) {
        val sanitized = sanitizeMatchName(matchName)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_MATCH, sanitized)
            .apply()
    }

    /** Crea una nuova partita con nome generato o personalizzato e la imposta come attiva. */
    fun createNewMatch(context: Context, customName: String? = null): String {
        val name = if (!customName.isNullOrBlank()) {
            customName.trim()
        } else {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            "Partita_$dateStr"
        }
        val sanitized = sanitizeMatchName(name)
        setActiveMatch(context, sanitized)
        return sanitized
    }

    /** Sanitizza il nome cartella per l'uso nei file system. */
    fun sanitizeMatchName(input: String): String {
        val cleaned = input.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return if (cleaned.isEmpty()) DEFAULT_MATCH_NAME else cleaned
    }

    /** Restituisce il percorso relativo per gli screenshot (es: "Pictures/VideoDelay/Partita_20260727"). */
    fun getScreenshotRelativePath(context: Context): String {
        val match = getActiveMatch(context)
        return "Pictures/VideoDelay/$match"
    }

    /** Restituisce il percorso relativo per le clip (es: "Movies/VideoDelay/Partita_20260727" o sotto-cartelle). */
    fun getClipRelativePath(context: Context, subFolder: String? = null): String {
        val match = getActiveMatch(context)
        return if (subFolder.isNullOrBlank()) {
            "Movies/VideoDelay/$match"
        } else {
            "Movies/VideoDelay/$match/$subFolder"
        }
    }

    /**
     * Cerca ed elenca tutte le cartelle partita esistenti interrogando MediaStore (immagini e video).
     */
    fun getAllMatchFolders(context: Context): List<String> {
        val matchesSet = LinkedHashSet<String>()
        val active = getActiveMatch(context)
        matchesSet.add(active) // La partita attiva è sempre presente

        // 1. Cerca tra le immagini in Pictures/VideoDelay/
        val imageProjection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
        val imageSelection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val imageArgs = arrayOf("Pictures/VideoDelay/%")

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                imageSelection,
                imageArgs,
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                if (col != -1) {
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(col) ?: continue
                        // path = "Pictures/VideoDelay/Partita_X/..."
                        val parts = path.split("/").filter { it.isNotEmpty() }
                        if (parts.size >= 3 && parts[0] == "Pictures" && parts[1] == "VideoDelay") {
                            matchesSet.add(parts[2])
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Cerca tra i video in Movies/VideoDelay/
        val videoProjection = arrayOf(MediaStore.Video.Media.RELATIVE_PATH)
        val videoSelection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val videoArgs = arrayOf("Movies/VideoDelay/%")

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                videoSelection,
                videoArgs,
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                if (col != -1) {
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(col) ?: continue
                        val parts = path.split("/").filter { it.isNotEmpty() }
                        if (parts.size >= 3 && parts[0] == "Movies" && parts[1] == "VideoDelay") {
                            matchesSet.add(parts[2])
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return matchesSet.toList().sorted()
    }
}
