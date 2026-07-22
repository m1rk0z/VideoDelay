package it.videodelay.app.buffer

import android.content.Context
import java.io.File

/**
 * Gestisce il buffer circolare HLS su disco.
 * FFmpeg scrive segmenti .ts in [bufferDir] e aggiorna [playlistFile] (stream.m3u8).
 */
class CircularHlsBuffer(private val context: Context) {

    companion object {
        /** Durata di ogni segmento HLS in secondi */
        const val SEGMENT_DURATION_SEC = 1
        /** Massimo buffer desiderato in secondi */
        const val MAX_BUFFER_SEC = 300
        /** Numero massimo di segmenti nel playlist (hls_list_size) */
        const val MAX_SEGMENTS = MAX_BUFFER_SEC / SEGMENT_DURATION_SEC // 150
    }

    /** Directory cache privata dell'app, non richiede permessi */
    val bufferDir: File
        get() = File(context.cacheDir, "hls_buffer").also { it.mkdirs() }

    /** Playlist HLS principale letta da ExoPlayer */
    val playlistFile: File
        get() = File(bufferDir, "stream.m3u8")

    /** URI del playlist per ExoPlayer */
    val playlistUri: String
        get() = playlistFile.absolutePath

    /** Restituisce la durata totale attualmente disponibile nel buffer in ms */
    fun getAvailableBufferMs(): Long =
        getSegmentFiles().size.toLong() * SEGMENT_DURATION_SEC * 1000L

    /** Lista ordinata dei file .ts presenti nel buffer */
    fun getSegmentFiles(): List<File> =
        bufferDir.listFiles { f -> f.extension == "ts" }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()

    /** True se il playlist esiste e il buffering è attivo */
    fun isActive(): Boolean = playlistFile.exists() && playlistFile.length() > 0

    /** Elimina tutti i file nel buffer (segmenti + playlist) */
    fun clear() {
        bufferDir.listFiles()?.forEach { it.delete() }
    }
}
