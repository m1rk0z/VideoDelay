package it.videodelay.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import it.videodelay.app.R
import it.videodelay.app.buffer.CircularHlsBuffer
import it.videodelay.app.ui.main.MainActivity
import it.videodelay.app.util.DemoVideoGenerator
import it.videodelay.app.util.RtspCSeqProxy
import java.io.File
import java.net.URI
import kotlin.concurrent.thread

/** Stato della connessione streaming */
sealed class StreamingState {
    object Idle : StreamingState()
    object Connecting : StreamingState()
    object Streaming : StreamingState()
    data class Error(val message: String) : StreamingState()
}

/**
 * Foreground Service che mantiene il processo attivo durante la riproduzione RTSP.
 * Avvia FFmpeg per convertire lo stream RTSP (o la camera demo) in segmenti HLS circular.
 */
class StreamingForegroundService : Service() {

    companion object {
        private const val TAG = "StreamingService"
        const val CHANNEL_ID = "videodelay_stream_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START = "it.videodelay.app.ACTION_START"
        const val ACTION_STOP = "it.videodelay.app.ACTION_STOP"
        const val EXTRA_RTSP_URL = "extra_rtsp_url"
        const val EXTRA_CAMERA_NAME = "extra_camera_name"
        const val EXTRA_BUFFER_SEC = "extra_buffer_sec"

        // Buffer dei log in memoria RAM per la diagnostica
        val logBuffer = java.util.Collections.synchronizedList(java.util.ArrayList<String>())
    }

    inner class StreamingBinder : Binder() {
        fun getService(): StreamingForegroundService = this@StreamingForegroundService
    }

    private val binder = StreamingBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ──────────────────────────── State ────────────────────────────

    private val _state = MutableLiveData<StreamingState>(StreamingState.Idle)
    val state: LiveData<StreamingState> = _state

    private val _bufferMs = MutableLiveData(0L)
    val bufferMs: LiveData<Long> = _bufferMs

    private var currentCameraName: String = ""
    private var isActive = false
    private var bufferDurationSec = 300
    private var maxBufferMs = 300_000L

    private lateinit var buffer: CircularHlsBuffer
    private var ffmpegSession: FFmpegSession? = null
    private var rtspProxy: RtspCSeqProxy? = null

    private var rtmpSession: FFmpegSession? = null
    private val _isRtmpStreaming = MutableLiveData(false)
    val isRtmpStreaming: LiveData<Boolean> = _isRtmpStreaming

    // ──────────────────────────── Buffer tick ──────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private var bufferStartTime = 0L

    private val bufferTickRunnable = object : Runnable {
        override fun run() {
            if (isActive) {
                val elapsed = System.currentTimeMillis() - bufferStartTime
                _bufferMs.postValue(elapsed.coerceAtMost(maxBufferMs))

                if (_state.value == StreamingState.Connecting && buffer.isActive()) {
                    onStreamConnected()
                }
                handler.postDelayed(this, 500)
            }
        }
    }

    // ──────────────────────────── Lifecycle ────────────────────────

    override fun onCreate() {
        super.onCreate()
        buffer = CircularHlsBuffer(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_RTSP_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_CAMERA_NAME) ?: "Camera"
                bufferDurationSec = intent.getIntExtra(EXTRA_BUFFER_SEC, 300)
                maxBufferMs = bufferDurationSec * 1000L
                startForegroundStream(url, name)
            }
            ACTION_STOP -> stopAndSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopInternal()
    }

    // ──────────────────────────── Control ──────────────────────────

    private fun startForegroundStream(rtspUrl: String, cameraName: String) {
        currentCameraName = cameraName
        isActive = true
        bufferStartTime = System.currentTimeMillis()
        _state.postValue(StreamingState.Connecting)
        _bufferMs.postValue(0L)

        // Imposta il log level globale a DEBUG per catturare l'handshake RTSP completo
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_DEBUG)

        // Pulisci e avvia il log listener in memoria
        logBuffer.clear()
        FFmpegKitConfig.enableLogCallback { log ->
            if (isActive && ffmpegSession != null && log.sessionId == ffmpegSession?.sessionId) {
                logBuffer.add(log.message)
                if (logBuffer.size > 5000) {
                    logBuffer.removeAt(0)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(cameraName, "⏳ Inizializzazione..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(cameraName, "⏳ Inizializzazione..."))
        }
        buffer.clear()

        handler.postDelayed(bufferTickRunnable, 500)

        // Esegui FFmpeg in base al tipo di telecamera
        thread(start = true) {
            val maxSegments = bufferDurationSec / CircularHlsBuffer.SEGMENT_DURATION_SEC

                // Comando FFmpeg per registrare da stream RTSP reale (con log di debug abilitato)
                val command = if (rtspUrl.startsWith("demo://")) {
                    // Configura il video demo in loop
                    val demoFile = File(cacheDir, "demo_video.mp4")
                    val demoOk = DemoVideoGenerator.generateDemoVideoIfNeeded(demoFile)

                    if (demoOk && demoFile.exists() && demoFile.length() > 0) {
                        Log.d(TAG, "Video demo pronto (${demoFile.length()} bytes). Avvio FFmpeg HLS loop.")
                        // Comando FFmpeg per loop del file locale ad andatura reale (-re)
                        "-y -re -stream_loop -1 -i ${demoFile.absolutePath} -c copy -f hls -hls_time 1 -hls_list_size $maxSegments -hls_flags delete_segments+program_date_time+temp_file -hls_segment_filename ${buffer.bufferDir.absolutePath}/segment_%05d.ts ${buffer.playlistFile.absolutePath}"
                    } else {
                        // Fallback: genera un segnale sintetico colorato con FFmpeg (testsrc2)
                        Log.w(TAG, "Video demo non disponibile o vuoto. Uso segnale sintetico FFmpeg testsrc2.")
                        "-y -f lavfi -i testsrc2=size=640x480:rate=30 -c:v libx264 -preset ultrafast -tune zerolatency -f hls -hls_time 1 -hls_list_size $maxSegments -hls_flags delete_segments+program_date_time+temp_file -hls_segment_filename ${buffer.bufferDir.absolutePath}/segment_%05d.ts ${buffer.playlistFile.absolutePath}"
                    }
                } else {
                    // Avvia proxy RTSP locale per correggere il bug CSeq del server AndroidIPCamLive.
                    // Il proxy intercetta le risposte del server e aggiusta i CSeq errati prima di
                    // passarli a FFmpegKit, che è strict RFC-compliant.
                    val ffmpegUrl = try {
                        val uri = URI(rtspUrl)
                        val host = uri.host ?: ""
                        val port = if (uri.port > 0) uri.port else 554
                        val userInfo = uri.userInfo
                        val query = uri.query
                        val rawPath = uri.rawPath
                        val pathPart = if (rawPath.isNullOrEmpty()) "/" else rawPath

                        val proxy = RtspCSeqProxy()
                        proxy.start(host, port)
                        rtspProxy = proxy

                        val userPart = if (userInfo != null) "$userInfo@" else ""
                        val queryPart = if (query != null) "?$query" else ""
                        val url = "rtsp://${userPart}127.0.0.1:${proxy.proxyPort}$pathPart$queryPart"
                        Log.i(TAG, "Proxy RTSP avviato: $rtspUrl → $url")
                        url
                    } catch (e: Exception) {
                        Log.e(TAG, "Impossibile avviare proxy RTSP, uso URL diretto: ${e.message}")
                        rtspUrl
                    }
                    Log.d(TAG, "Avvio registrazione HLS buffer per $cameraName: $ffmpegUrl")
                    Log.d("RTSP", "Avvio connessione con URL (via proxy): $ffmpegUrl")
                    // TCP interleaved tramite proxy locale
                    "-y -loglevel debug -rtsp_transport tcp -rtsp_flags prefer_tcp -allowed_media_types video -thread_queue_size 1024 -analyzeduration 100000 -probesize 100000 -i $ffmpegUrl -c copy -f hls -hls_time 1 -hls_list_size $maxSegments -hls_flags delete_segments+program_date_time+temp_file -hls_segment_filename ${buffer.bufferDir.absolutePath}/segment_%05d.ts ${buffer.playlistFile.absolutePath}"
                }

                Log.d("BUFFER", "Comando FFmpeg: $command")

                try {
                    ffmpegSession = FFmpegKit.executeAsync(command) { session ->
                        val rc = session.returnCode
                        val logs = session.allLogsAsString
                        try {
                            File(cacheDir, "ffmpeg_log.txt").writeText(logs)
                        } catch (e: Exception) {
                            Log.e("BUFFER", "Errore scrittura ffmpeg_log.txt", e)
                        }
                        if (ReturnCode.isSuccess(rc)) {
                            Log.d("BUFFER", "FFmpeg completato con successo.")
                        } else {
                            Log.e("BUFFER", "FFmpeg fallito. Codice: $rc. Logs: $logs")
                            if (isActive) {
                                val lines = logs.split("\n")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                val lastLines = lines.takeLast(4).joinToString("\n")
                                val errorDetail = if (lastLines.isNotEmpty()) lastLines else "Codice uscita FFmpeg: $rc"
                                onStreamError(errorDetail)
                            }
                        }
                    }
            } catch (t: Throwable) {
                Log.e("BUFFER", "Errore nel lancio di FFmpegKit in background", t)
                if (isActive) {
                    onStreamError("Errore caricamento modulo FFmpeg: ${t.javaClass.simpleName} - ${t.message}")
                }
            }
        }
    }

    fun onStreamConnected() {
        _state.postValue(StreamingState.Streaming)
        updateNotification(currentCameraName, "● LIVE")
    }

    fun onStreamError(message: String) {
        _state.postValue(StreamingState.Error(message))
        updateNotification(currentCameraName, "❌ Errore: $message")
        Log.e("RTSP", "Errore connessione RTSP: $message")
    }

    fun startRtmpStream(rtspUrl: String, rtmpUrl: String, streamKey: String) {
        if (_isRtmpStreaming.value == true) return
        
        val fullRtmpUrl = if (rtmpUrl.endsWith("/")) "$rtmpUrl$streamKey" else "$rtmpUrl/$streamKey"
        
        val command = if (rtspUrl.startsWith("demo://")) {
            val demoFile = File(cacheDir, "demo_video.mp4")
            "-y -re -stream_loop -1 -i ${demoFile.absolutePath} -c:v copy -c:a aac -f flv $fullRtmpUrl"
        } else {
            // Usa il flusso RTSP della camera con traccia audio silenziosa per compatibilità social
            "-y -rtsp_transport tcp -rtsp_flags prefer_tcp -i $rtspUrl -f lavfi -i anullsrc=channel_layout=mono:sample_rate=44100 -c:v copy -c:a aac -shortest -f flv $fullRtmpUrl"
        }

        Log.i(TAG, "Avvio diretta RTMP: $command")
        
        thread(start = true) {
            try {
                rtmpSession = FFmpegKit.executeAsync(command) { session ->
                    val rc = session.returnCode
                    Log.d("RTMP", "Sessione RTMP terminata con codice: $rc")
                    _isRtmpStreaming.postValue(false)
                }
                _isRtmpStreaming.postValue(true)
            } catch (e: Exception) {
                Log.e(TAG, "Errore avvio diretta RTMP", e)
                _isRtmpStreaming.postValue(false)
            }
        }
    }

    fun stopRtmpStream() {
        rtmpSession?.let {
            Log.d("RTMP", "Interruzione sessione RTMP.")
            try {
                FFmpegKit.cancel(it.sessionId)
            } catch (t: Throwable) {
                Log.e("RTMP", "Errore arresto sessione RTMP", t)
            }
            rtmpSession = null
        }
        _isRtmpStreaming.postValue(false)
    }

    fun stopAndSelf() {
        stopInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopInternal() {
        isActive = false
        handler.removeCallbacks(bufferTickRunnable)
        
        stopRtmpStream()
        
        ffmpegSession?.let {
            Log.d("BUFFER", "Interruzione sessione FFmpeg.")
            try {
                FFmpegKit.cancel(it.sessionId)
            } catch (t: Throwable) {
                Log.e("BUFFER", "Errore arresto sessione FFmpeg", t)
            }
            ffmpegSession = null
        }

        rtspProxy?.stop()
        rtspProxy = null

        _state.postValue(StreamingState.Idle)
        _bufferMs.postValue(0L)
        buffer.clear()
    }

    fun isBufferActive(): Boolean = isActive
    fun getBufferDurationMs(): Long = _bufferMs.value ?: 0L
    fun getCircularHlsBuffer(): CircularHlsBuffer = buffer

    // ──────────────────────────── Notifica ─────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "VideoDelay Streaming", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Streaming RTSP attivo – bordo campo"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(cameraName: String, status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VideoDelay – $cameraName")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(cameraName: String, status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(cameraName, status))
    }
}
