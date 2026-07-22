package it.videodelay.app.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.lifecycle.*
import it.videodelay.app.data.model.Camera
import it.videodelay.app.service.StreamingForegroundService
import it.videodelay.app.service.StreamingState

/** Modalità di riproduzione corrente */
enum class PlaybackMode { LIVE, DELAY, REPLAY }

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    // ──────────────────────────── Camera ─────────────────────────
    private val _camera = MutableLiveData<Camera?>()
    val camera: LiveData<Camera?> = _camera

    // ──────────────────────────── Stato streaming ────────────────
    val streamingState: LiveData<StreamingState>
        get() = _streamingState
    private val _streamingState = MutableLiveData<StreamingState>(StreamingState.Idle)

    val bufferDurationMs: LiveData<Long>
        get() = _bufferDurationMs
    private val _bufferDurationMs = MutableLiveData(0L)

    // ──────────────────────────── Playback ────────────────────────
    private val _playbackMode = MutableLiveData(PlaybackMode.LIVE)
    val playbackMode: LiveData<PlaybackMode> = _playbackMode

    private val _delaySeconds = MutableLiveData(5)
    val delaySeconds: LiveData<Int> = _delaySeconds

    private val _audioEnabled = MutableLiveData(false)
    val audioEnabled: LiveData<Boolean> = _audioEnabled

    // ──────────────────────────── Logo squadra ────────────────────
    private val _teamLogoUri = MutableLiveData<String?>(null)
    val teamLogoUri: LiveData<String?> = _teamLogoUri

    // ──────────────────────────── Service binding ─────────────────
    private var streamingService: StreamingForegroundService? = null
    private var _serviceBoundFlag = false
    var maxBufferSec = 300 // Valore di default: 5 minuti (300s)

    private val _serviceBound = MutableLiveData(false)
    val serviceBound: LiveData<Boolean> = _serviceBound

    val isRtmpStreaming: LiveData<Boolean>
        get() = _isRtmpStreaming
    private val _isRtmpStreaming = MutableLiveData(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? StreamingForegroundService.StreamingBinder ?: return
            streamingService = b.getService()
            _serviceBoundFlag = true
            _serviceBound.postValue(true)
            streamingService?.state?.observeForever { _streamingState.postValue(it) }
            streamingService?.bufferMs?.observeForever { _bufferDurationMs.postValue(it) }
            streamingService?.isRtmpStreaming?.observeForever { _isRtmpStreaming.postValue(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            _serviceBoundFlag = false
            _serviceBound.postValue(false)
        }
    }

    // ──────────────────────────── API pubblica ─────────────────────

    fun startRtmpStream(rtmpUrl: String, streamKey: String) {
        val cam = _camera.value ?: return
        streamingService?.startRtmpStream(cam.rtspUrl, rtmpUrl, streamKey)
    }

    fun stopRtmpStream() {
        streamingService?.stopRtmpStream()
    }

    fun setCamera(camera: Camera) { _camera.value = camera }

    fun bindService(context: Context) {
        val intent = Intent(context, StreamingForegroundService::class.java)
        context.bindService(intent, serviceConnection as ServiceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (_serviceBoundFlag) {
            context.unbindService(serviceConnection as ServiceConnection)
            _serviceBoundFlag = false
            _serviceBound.postValue(false)
        }
    }

    fun startStreaming(context: Context, rtspUrl: String, cameraName: String) {
        val intent = Intent(context, StreamingForegroundService::class.java).apply {
            action = StreamingForegroundService.ACTION_START
            putExtra(StreamingForegroundService.EXTRA_RTSP_URL, rtspUrl)
            putExtra(StreamingForegroundService.EXTRA_CAMERA_NAME, cameraName)
            putExtra(StreamingForegroundService.EXTRA_BUFFER_SEC, maxBufferSec)
        }
        context.startForegroundService(intent)
        bindService(context)
    }

    fun stopStreaming(context: Context) {
        unbindService(context)
        val intent = Intent(context, StreamingForegroundService::class.java).apply {
            action = StreamingForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun changeBufferDuration(context: Context, seconds: Int, rtspUrl: String, cameraName: String) {
        maxBufferSec = seconds
        stopStreaming(context)
        Handler(Looper.getMainLooper()).postDelayed({
            startStreaming(context, rtspUrl, cameraName)
        }, 500)
    }

    fun notifyConnected() = streamingService?.onStreamConnected()
    fun notifyError(msg: String) = streamingService?.onStreamError(msg)
    fun getStreamingService(): StreamingForegroundService? = streamingService
    fun isBufferActive(): Boolean = streamingService?.isBufferActive() == true

    fun setDelay(seconds: Int) {
        _delaySeconds.value = seconds
        _playbackMode.value = if (seconds == 0) PlaybackMode.LIVE else PlaybackMode.DELAY
    }

    fun goLive() {
        _delaySeconds.value = 0
        _playbackMode.value = PlaybackMode.LIVE
    }

    fun setPlaybackMode(mode: PlaybackMode) { _playbackMode.value = mode }

    fun toggleAudio() { _audioEnabled.value = !(_audioEnabled.value ?: true) }

    fun setTeamLogoUri(uri: String?) { _teamLogoUri.value = uri }
}
