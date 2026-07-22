package it.videodelay.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.videodelay.app.R
import it.videodelay.app.buffer.CircularHlsBuffer
import it.videodelay.app.data.model.Camera
import it.videodelay.app.databinding.FragmentPlayerBinding
import it.videodelay.app.service.StreamingForegroundService
import it.videodelay.app.service.StreamingState
import it.videodelay.app.util.DemoVideoGenerator
import it.videodelay.app.util.ScreenshotUtil
import it.videodelay.app.util.sanitizedForFilename
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

@UnstableApi
class PlayerFragment : Fragment() {

    companion object {
        const val TAG = "PlayerFragment"
        private const val ARG_CAMERA_ID = "camera_id"
        private const val ARG_CAMERA_NAME = "camera_name"
        private const val ARG_RTSP_URL = "rtsp_url"

        /** Delay fisso applicato all'avvio (secondi) */
        private const val DEFAULT_DELAY_SEC = 5

        /** Durata del video demo in loop (deve coincidere con DemoVideoGenerator). */
        private const val DEMO_DURATION_MS = 180_000L

        private const val PREFS_NAME = "videodelay_prefs"
        private const val KEY_CONTROLS_EXPANDED = "controls_expanded"

        fun newInstance(cameraId: Long, cameraName: String, rtspUrl: String) =
            PlayerFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CAMERA_ID, cameraId)
                    putString(ARG_CAMERA_NAME, cameraName)
                    putString(ARG_RTSP_URL, rtspUrl)
                }
            }
    }

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()

    private var exoPlayer: ExoPlayer? = null
    private var rtspUrl: String = ""
    private var isDemo: Boolean = false

    // Stato play/pausa
    private var isPlaying = true

    // Flag: applica il delay demo solo alla prima STATE_READY, non ad ogni loop
    private var demoDelayApplied = false
    private var demoDelayRunnable: Runnable? = null
    private var demoStartTimeMs: Long = 0L

    // Real-time stats
    private var lastRenderedFrames = 0
    private var lastFpsCalculationTime = 0L
    private var actualFps = 0.0
    private var totalDroppedFrames = 0

    // RTSP reconnection
    private var useRtpTcp = true
    private var lastPlayerError: String? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        if (isAdded && _binding != null) startPlayer()
    }

    // HLS retry (solo RTSP reale)
    private var hlsRetryCount = 0
    private val maxHlsRetries = 30
    private var initialDelayApplied = false

    // Diagnostica
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentFps = 0f
    private var currentVideoCodec = "N/D"
    private var currentAudioCodec = "N/D"

    private val handler = Handler(Looper.getMainLooper())

    private val clockRunnable = object : Runnable {
        override fun run() {
            if (_binding == null) return
            _binding?.hudClock?.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val bufferMs = viewModel.bufferDurationMs.value ?: 0L
            _binding?.hudBufferDuration?.text = formatMs(bufferMs)
            handler.postDelayed(this, 1000)
        }
    }

    private val timelineRunnable = object : Runnable {
        override fun run() {
            if (_binding == null) return
            updateTimeline()
            updateRealtimeStats()
            handler.postDelayed(this, 500)
        }
    }

    private val logoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.setTeamLogoUri(uri.toString())
            }
        }
    }

    // ──────────────────────────── Lifecycle ────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Forza la visibilità della barra delle notifiche (status bar) anche in landscape
        activity?.window?.let { window ->
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }

        val cameraId = arguments?.getLong(ARG_CAMERA_ID) ?: 0L
        val cameraName = arguments?.getString(ARG_CAMERA_NAME) ?: ""
        rtspUrl = arguments?.getString(ARG_RTSP_URL) ?: ""
        isDemo = rtspUrl.startsWith("demo://")

        viewModel.setCamera(Camera(id = cameraId, name = cameraName, rtspUrl = rtspUrl))

        setupHud(cameraName)
        setupControls()
        setupTimeline()
        observeViewModel()

        if (isDemo) {
            _binding?.progressBuffering?.isVisible = true
            startDemoPlayer()
        } else {
            viewModel.startStreaming(requireContext(), rtspUrl, cameraName)
            startPlayer()
        }

        handler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying) exoPlayer?.play()
        handler.post(timelineRunnable)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        handler.removeCallbacks(timelineRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        cancelDemoDelayRunnable()
        reconnectHandler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isDemo) viewModel.stopStreaming(requireContext())
    }

    // ──────────────────────────── Demo Player ──────────────────────

    /**
     * Avvia la Demo Camera con ExoPlayer direttamente sul file MP4 locale in loop.
     * Dopo che il player è pronto, applica il delay fisso di DEFAULT_DELAY_SEC.
     */
    private fun startDemoPlayer() {
        if (_binding == null || !isAdded) return

        val demoFile = File(requireContext().cacheDir, "demo_video.mp4")

        if (!demoFile.exists() || demoFile.length() == 0L) {
            Log.d(TAG, "Generazione video demo in background...")
            _binding?.hudStatus?.text = "⏳ Generazione demo..."
            _binding?.progressBuffering?.isVisible = true

            thread(name = "DemoGenerator") {
                val ok = DemoVideoGenerator.generateDemoVideoIfNeeded(demoFile)
                handler.post {
                    if (_binding != null && isAdded) {
                        if (ok && demoFile.exists() && demoFile.length() > 0) {
                            _binding?.progressBuffering?.isVisible = false
                            demoDelayApplied = false  // reset flag per la nuova istanza
                            initDemoExoPlayer(demoFile)
                        } else {
                            _binding?.progressBuffering?.isVisible = false
                            _binding?.hudStatus?.text = "❌ Demo non disponibile"
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Video demo in cache: ${demoFile.length()} bytes")
            _binding?.progressBuffering?.isVisible = false
            demoDelayApplied = false  // reset flag per la nuova istanza
            initDemoExoPlayer(demoFile)
        }
    }

    private fun initDemoExoPlayer(demoFile: File) {
        if (_binding == null || !isAdded) return
        releasePlayer()

        demoStartTimeMs = System.currentTimeMillis()
        val ctx = context ?: return
        Log.d(TAG, "Avvio ExoPlayer demo (avvio da 0 per i primi ${DEFAULT_DELAY_SEC}s, poi differita)")

        exoPlayer = ExoPlayer.Builder(ctx).build().also { player ->
            _binding?.playerView?.player = player
            _binding?.playerView?.useController = false

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _binding?.progressBuffering?.isVisible = (state == Player.STATE_BUFFERING)
                    if (state == Player.STATE_READY) {
                        val format = player.videoFormat
                        currentFps = format?.frameRate ?: 0f
                        currentVideoCodec = format?.sampleMimeType ?: "N/D"

                        // Simulazione ritardo: parte da 0s visualizzando "LIVE".
                        // Dopo 8 secondi, esegue il seek di nuovo a 0s ed imposta lo stato a "DELAY 8s".
                        if (!demoDelayApplied) {
                            demoDelayApplied = true
                            _binding?.hudStatus?.text = "▶ LIVE"
                            _binding?.hudStatus?.setTextColor(Color.parseColor("#F44336")) // Rosso

                            cancelDemoDelayRunnable()
                            demoDelayRunnable = Runnable {
                                if (isAdded && _binding != null && exoPlayer != null) {
                                    exoPlayer?.seekTo(0L)
                                    _binding?.hudStatus?.text = "⏱ DELAY ${DEFAULT_DELAY_SEC}s"
                                    _binding?.hudStatus?.setTextColor(Color.parseColor("#FF6D00")) // Arancione
                                    Toast.makeText(context, "Inizio differita (ritardo ${DEFAULT_DELAY_SEC}s)", Toast.LENGTH_SHORT).show()
                                }
                            }
                            handler.postDelayed(demoDelayRunnable!!, DEFAULT_DELAY_SEC * 1000L)
                        }
                    }
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    currentWidth = videoSize.width
                    currentHeight = videoSize.height
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Demo player error: ${error.message}", error)
                    lastPlayerError = error.localizedMessage
                }
            })

            val mediaItem = MediaItem.fromUri(Uri.fromFile(demoFile))
            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            player.playWhenReady = true
        }
        isPlaying = true
        updatePlayPauseIcon()
    }

    private fun cancelDemoDelayRunnable() {
        demoDelayRunnable?.let {
            handler.removeCallbacks(it)
            demoDelayRunnable = null
        }
    }

    // ──────────────────────────── RTSP Player ──────────────────────

    private fun startPlayer() {
        if (_binding == null || !isAdded) return
        releasePlayer()
        hlsRetryCount = 0

        val ctx = context ?: return
        // Buffer un po' più ampio del minimo indispensabile: la sorgente è HLS locale scritta
        // in tempo reale da FFmpeg, e un margine troppo risicato (era 500ms) fa svuotare il
        // buffer per il minimo ritardo di scrittura segmento, causando scatti/stalli frequenti.
        // NB: DefaultLoadControl impone minBufferMs >= bufferForPlayback(AfterRebuffer)Ms.
        // Valori che violano l'invariante fanno lanciare IllegalArgumentException a build()
        // → crash all'apertura di qualsiasi camera reale. minBuffer (3000) è quindi tenuto
        // >= sia di bufferForPlayback (1500) che di bufferForPlaybackAfterRebuffer (2500).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 15_000, 1_500, 2_500)
            .build()

        exoPlayer = ExoPlayer.Builder(ctx).setLoadControl(loadControl).build().also { player ->
            _binding?.playerView?.player = player
            _binding?.playerView?.useController = false

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _binding?.progressBuffering?.isVisible = (state == Player.STATE_BUFFERING)
                    if (state == Player.STATE_READY) {
                        viewModel.notifyConnected()
                        val format = player.videoFormat
                        currentFps = format?.frameRate ?: 0f
                        currentVideoCodec = format?.sampleMimeType ?: "N/D"
                        currentAudioCodec = player.audioFormat?.sampleMimeType ?: "N/D"
                        
                        // Applica il delay iniziale solo la prima volta
                        if (!initialDelayApplied) {
                            initialDelayApplied = true
                            applyInitialDelay(player)
                        }
                    }
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    currentWidth = videoSize.width
                    currentHeight = videoSize.height
                }
                override fun onPlayerError(error: PlaybackException) {
                    var msg = error.localizedMessage ?: "Errore"
                    var cause = error.cause
                    while (cause != null) {
                        msg += "\nCausa: ${cause.javaClass.simpleName} - ${cause.message}"
                        cause = cause.cause
                    }
                    lastPlayerError = msg
                    viewModel.notifyError(msg)
                    Log.e("EXOPLAYER", "Errore: $msg", error)
                    scheduleReconnection()
                }
            })
        }

        isPlaying = true
        updatePlayPauseIcon()

        loadHlsBuffer()
    }

    /**
     * Applica il delay iniziale di DEFAULT_DELAY_SEC sulla sorgente HLS buffer.
     */
    private fun applyInitialDelay(player: ExoPlayer) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        val currentDelay = viewModel.delaySeconds.value ?: DEFAULT_DELAY_SEC
        val target = maxOf(0L, duration - currentDelay * 1000L)
        Log.d(TAG, "RTSP buffer: seek a ${target}ms (delay ${currentDelay}s)")
        player.seekTo(target)
        _binding?.sliderDelay?.progress = currentDelay
        _binding?.tvDelayValue?.text = "${currentDelay}s"
        
        val label = if (currentDelay == 0) "▶ LIVE" else "⏱ DELAY ${currentDelay}s"
        val color = if (currentDelay == 0) "#F44336" else "#FF6D00"
        _binding?.hudStatus?.text = label
        _binding?.hudStatus?.setTextColor(Color.parseColor(color))
    }

    private fun loadHlsBuffer() {
        if (_binding == null || !isAdded) return
        val service = viewModel.getStreamingService()
        val bufferPlaylist = service?.getCircularHlsBuffer()?.playlistFile
        if (bufferPlaylist != null && bufferPlaylist.exists() && bufferPlaylist.length() > 0) {
            _binding?.progressBuffering?.isVisible = false
            val ctx = context ?: return
            val mediaItem = MediaItem.fromUri(Uri.fromFile(bufferPlaylist))
            val hlsSource = HlsMediaSource.Factory(DefaultDataSource.Factory(ctx)).createMediaSource(mediaItem)
            initialDelayApplied = false // reset flag per la nuova sorgente
            exoPlayer?.apply { setMediaSource(hlsSource); prepare(); playWhenReady = true }
        } else {
            hlsRetryCount++
            if (hlsRetryCount > maxHlsRetries) {
                _binding?.progressBuffering?.isVisible = false
                _binding?.hudStatus?.text = "❌ Timeout buffer"
                return
            }
            _binding?.progressBuffering?.isVisible = true
            handler.postDelayed({ if (_binding != null && isAdded) loadHlsBuffer() }, 1000)
        }
    }

    private fun scheduleReconnection() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, 5000)
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // ──────────────────────────── Play/Pause ───────────────────────

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        cancelDemoDelayRunnable()
        isPlaying = !isPlaying
        if (isPlaying) player.play() else player.pause()
        updatePlayPauseIcon()
        showCenterPlayPauseFeedback()
    }

    private fun updatePlayPauseIcon() {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        _binding?.btnPlayPause?.setImageResource(icon)
        _binding?.ivPlayPauseFeedback?.setImageResource(icon)
    }

    private fun showCenterPlayPauseFeedback() {
        val iv = _binding?.ivPlayPauseFeedback ?: return
        iv.alpha = 0f
        iv.animate().alpha(1f).setDuration(150).withEndAction {
            handler.postDelayed({
                _binding?.ivPlayPauseFeedback?.animate()?.alpha(0f)?.setDuration(300)?.start()
            }, 600)
        }.start()
    }

    // ──────────────────────────── HUD ──────────────────────────────

    private fun setupHud(cameraName: String) {
        binding.hudCameraName.text = cameraName
        binding.hudLogo.setOnClickListener { pickTeamLogo() }
    }

    private fun pickTeamLogo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        logoPickerLauncher.launch(intent)
    }

    // ──────────────────────────── Controls ─────────────────────────

    private fun setupControls() {
        val controlsExpanded = requireContext()
            .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_CONTROLS_EXPANDED, true)
        binding.layoutFullControls.isVisible = controlsExpanded

        binding.btnReplay5.setOnClickListener { replaySeconds(5) }
        binding.btnReplay10.setOnClickListener { replaySeconds(10) }
        binding.btnReplay30.setOnClickListener { replaySeconds(30) }

        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnPlay.setOnClickListener {
            // Esce da Replay e torna alla differita impostata
            val delay = binding.sliderDelay.progress
            applyDelay(delay)
            exoPlayer?.playWhenReady = true
            isPlaying = true
            updatePlayPauseIcon()
        }
        binding.playerTapOverlay.setOnClickListener { togglePlayPause() }

        binding.btnMark.setOnClickListener {
            AttackTypeSheet.show(binding.btnMark) { attackType -> saveMarkClip(attackType) }
        }

        // Delay slider – range 0-maxBufferSec, default 10s o il valore corrente nel ViewModel
        binding.sliderDelay.max = viewModel.maxBufferSec
        val currentDelay = viewModel.delaySeconds.value ?: DEFAULT_DELAY_SEC
        binding.sliderDelay.progress = currentDelay
        binding.tvDelayValue.text = "${currentDelay}s"

        binding.sliderDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvDelayValue.text = if (progress == 0) "0s" else "${progress}s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val delay = sb?.progress ?: DEFAULT_DELAY_SEC
                applyDelay(delay)
            }
        })

        binding.btnGoLive.setOnClickListener {
            binding.sliderDelay.progress = 0
            applyDelay(0)
        }

        binding.btnAudio.setOnClickListener { viewModel.toggleAudio() }
        binding.btnScreenshot.setOnClickListener { takeScreenshot() }
        binding.btnSaveClip.setOnClickListener { showSaveClipDialog() }
        binding.btnClipGallery.setOnClickListener { openClipGallery() }
        binding.btnToggleControls.setOnClickListener { toggleFullControls() }
        binding.btnRtmp.setOnClickListener {
            if (viewModel.isRtmpStreaming.value == true) {
                confirmStopRtmpStream()
            } else {
                showRtmpConfigDialog()
            }
        }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnDiagnostics.setOnClickListener {
            showDiagnosticsDialog()
        }
    }

    private fun setupTimeline() {
        binding.seekBarTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = exoPlayer?.duration?.takeIf { it > 0 } ?: 0L
                    binding.tvTimelinePosition.text = formatMs(duration * progress / 100L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                cancelDemoDelayRunnable()
                val progress = sb?.progress ?: 0
                val duration = exoPlayer?.duration?.takeIf { it > 0 } ?: 0L
                exoPlayer?.seekTo(duration * progress / 100L)
                if (!isDemo) viewModel.setPlaybackMode(PlaybackMode.REPLAY)
                _binding?.hudStatus?.text = "◀ REPLAY"
                _binding?.hudStatus?.setTextColor(Color.parseColor("#2979FF"))
            }
        })
    }

    // ──────────────────────────── Observers ────────────────────────

    private fun observeViewModel() {
        viewModel.streamingState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is StreamingState.Idle -> binding.hudStatus.text = "IDLE"
                is StreamingState.Connecting -> {
                    binding.hudStatus.text = "⏳ CONNESSIONE"
                    binding.progressBuffering.isVisible = true
                }
                is StreamingState.Streaming -> {
                    binding.progressBuffering.isVisible = false
                    if (!isDemo && exoPlayer == null) startPlayer()
                }
                is StreamingState.Error -> {
                    val errMsg = state.message
                    binding.hudStatus.text = "❌ ERRORE: $errMsg"
                    binding.progressBuffering.isVisible = false
                    lastPlayerError = errMsg
                }
            }
        }

        viewModel.audioEnabled.observe(viewLifecycleOwner) { enabled ->
            exoPlayer?.volume = if (enabled) 1f else 0f
            binding.btnAudio.setImageResource(
                if (enabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
            )
        }

        viewModel.teamLogoUri.observe(viewLifecycleOwner) { uriString ->
            if (uriString != null) {
                binding.hudLogo.setImageURI(Uri.parse(uriString))
                binding.hudLogo.isVisible = true
            }
        }

        viewModel.serviceBound.observe(viewLifecycleOwner) { bound ->
            if (bound && !isDemo && exoPlayer == null) loadHlsBuffer()
        }

        viewModel.isRtmpStreaming.observe(viewLifecycleOwner) { isStreaming ->
            if (isStreaming) {
                binding.btnRtmp.imageTintList = android.content.res.ColorStateList.valueOf(Color.RED)
            } else {
                binding.btnRtmp.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
        }
    }

    // ──────────────────────────── Playback helpers ─────────────────

    private fun replaySeconds(seconds: Int) {
        val player = exoPlayer ?: return
        cancelDemoDelayRunnable()
        val current = player.currentPosition
        val target = maxOf(0L, current - seconds * 1000L)
        player.seekTo(target)
        if (!isDemo) viewModel.setPlaybackMode(PlaybackMode.REPLAY)
        _binding?.hudStatus?.text = "◀ REPLAY"
        _binding?.hudStatus?.setTextColor(Color.parseColor("#2979FF"))
    }

    private fun applyDelay(delaySec: Int) {
        val player = exoPlayer ?: return
        cancelDemoDelayRunnable()
        val pos = if (isDemo) {
            val elapsed = System.currentTimeMillis() - demoStartTimeMs
            val livePos = elapsed % DEMO_DURATION_MS
            maxOf(0L, livePos - delaySec * 1000L)
        } else {
            val duration = player.duration.takeIf { it > 0 }
                ?: (viewModel.bufferDurationMs.value ?: 0L)
            maxOf(0L, duration - delaySec * 1000L)
        }
        player.seekTo(pos)
        if (!isDemo) viewModel.setDelay(delaySec)
        val label = if (delaySec == 0) "▶ LIVE" else "⏱ DELAY ${delaySec}s"
        val color = if (delaySec == 0) "#F44336" else "#FF6D00"
        _binding?.hudStatus?.text = label
        _binding?.hudStatus?.setTextColor(Color.parseColor(color))
    }

    private fun updateTimeline() {
        val player = exoPlayer ?: return
        val duration = player.duration.takeIf { it > 0 } ?: (viewModel.bufferDurationMs.value ?: 0L)
        if (duration > 0) {
            val position = player.currentPosition
            val progress = ((position.toFloat() / duration.toFloat()) * 100).toInt()
            _binding?.seekBarTimeline?.progress = progress.coerceIn(0, 100)
            _binding?.tvTimelinePosition?.text = formatMs(position)
        }
    }

    // ──────────────────────────── Screenshot / Clip ─────────────────

    private fun takeScreenshot() {
        val act = activity ?: return
        
        // Rileva lo stato degli overlay per ripristinarli dopo la cattura
        val statsVisible = binding.panelRealtimeStats.visibility == View.VISIBLE

        // Nasconde temporaneamente tutti gli elementi HUD dall'inquadratura
        binding.hudOverlay.visibility = View.GONE
        binding.hudBottomControls.visibility = View.GONE
        binding.panelRealtimeStats.visibility = View.GONE

        Toast.makeText(requireContext(), "Cattura screenshot...", Toast.LENGTH_SHORT).show()

        // Attende il ciclo di layout successivo per registrare lo schermo pulito
        binding.root.post {
            ScreenshotUtil.captureForEditing(act, binding.root) { path ->
                handler.post {
                    // Ripristina gli elementi grafici dell'interfaccia
                    binding.hudOverlay.visibility = View.VISIBLE
                    binding.hudBottomControls.visibility = View.VISIBLE
                    if (statsVisible) {
                        binding.panelRealtimeStats.visibility = View.VISIBLE
                    }

                    if (path != null) {
                        val intent = Intent(act, ScreenshotEditorActivity::class.java).apply {
                            putExtra(ScreenshotEditorActivity.EXTRA_SCREENSHOT_PATH, path)
                        }
                        startActivity(intent)
                    } else {
                        Toast.makeText(act, "Errore durante la cattura dello screenshot", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun toggleFullControls() {
        val full = binding.layoutFullControls
        val expanded = !full.isVisible
        full.isVisible = expanded
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONTROLS_EXPANDED, expanded)
            .apply()
    }

    private fun openClipGallery() {
        val intent = Intent(requireContext(), ClipGalleryActivity::class.java).apply {
            putExtra(ClipGalleryActivity.EXTRA_CAMERA_NAME, viewModel.camera.value?.name ?: "Camera")
        }
        startActivity(intent)
    }

    private fun showSaveClipDialog() {
        val options = arrayOf("5 secondi", "10 secondi", "15 secondi", "30 secondi", "60 secondi")
        val values = intArrayOf(5, 10, 15, 30, 60)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Esporta Clip Video")
            .setItems(options) { _, which -> exportClip(values[which]) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun exportClip(seconds: Int) {
        val appCtx = context?.applicationContext ?: run {
            Toast.makeText(requireContext(), "Contesto non disponibile", Toast.LENGTH_SHORT).show()
            return
        }
        if (isDemo) {
            val demoFile = File(appCtx.cacheDir, "demo_video.mp4")
            if (!demoFile.exists()) {
                Toast.makeText(requireContext(), "File demo non disponibile", Toast.LENGTH_SHORT).show()
                return
            }
            val currentPos = exoPlayer?.currentPosition ?: 0L
            val startSec = maxOf(0L, (currentPos / 1000L) - seconds)
            Toast.makeText(requireContext(), "Esportazione clip ${seconds}s...", Toast.LENGTH_SHORT).show()
            exportFromFile(demoFile.absolutePath, startSec, seconds.toLong())
            return
        }
        
        val service = viewModel.getStreamingService()
        val buffer = service?.getCircularHlsBuffer()
        if (buffer == null || !buffer.isActive()) {
            Toast.makeText(requireContext(), "Buffer non ancora pronto", Toast.LENGTH_SHORT).show()
            return
        }

        // Esportazione basata sulla concatenazione binaria dei segmenti HLS (.ts)
        val segments = buffer.getSegmentFiles()
        if (segments.isEmpty()) {
            Toast.makeText(requireContext(), "Nessun segmento nel buffer", Toast.LENGTH_SHORT).show()
            return
        }

        // Ogni segmento dura circa 2 secondi. Calcoliamo quanti segmenti servono per coprire i secondi richiesti.
        val numSegments = Math.ceil(seconds.toDouble() / 2.0).toInt().coerceAtLeast(1)
        val targetSegments = segments.takeLast(numSegments)

        Toast.makeText(requireContext(), "Esportazione clip ${seconds}s...", Toast.LENGTH_SHORT).show()

        // Uniamo i segmenti in background per non bloccare il thread UI
        thread(name = "ClipExporter") {
            val tempTsFile = File(appCtx.cacheDir, "concat_temp_${System.currentTimeMillis()}.ts")
            var ok = false
            try {
                tempTsFile.outputStream().use { fos ->
                    for (seg in targetSegments) {
                        if (seg.exists() && seg.length() > 0) {
                            seg.inputStream().use { fis ->
                                fis.copyTo(fos)
                            }
                        }
                    }
                }
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante l'unione dei segmenti", e)
            }

            handler.post {
                if (isAdded && _binding != null) {
                    if (ok && tempTsFile.exists() && tempTsFile.length() > 0) {
                        // Passiamo il file unito a FFmpeg per la conversione rapida in MP4
                        exportFromFile(tempTsFile.absolutePath, 0L, -1L)
                    } else {
                        Toast.makeText(appCtx, "Errore nella creazione del file temporaneo", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Pulisci se il fragment è stato chiuso nel frattempo
                    if (tempTsFile.exists()) tempTsFile.delete()
                }
            }
        }
    }

    /** Estrae il numero progressivo di un file segmento (es. "segment_00042.ts" → 42). */
    private fun segmentNumber(file: File): Int? =
        file.nameWithoutExtension.substringAfterLast("_").toIntOrNull()

    private fun saveMarkClip(attackType: AttackType) {
        val appCtx = context?.applicationContext ?: run {
            Toast.makeText(requireContext(), "Contesto non disponibile", Toast.LENGTH_SHORT).show()
            return
        }
        val durationSec = AttackTypeSheet.getSavedDurationSec(appCtx)
        val markPos = exoPlayer?.currentPosition ?: 0L
        val relativeFolder = "Movies/VideoDelay/Marks/${attackType.code}"

        showMarkAdded(attackType)

        if (isDemo) {
            val demoFile = File(appCtx.cacheDir, "demo_video.mp4")
            if (!demoFile.exists()) {
                Toast.makeText(requireContext(), "File demo non disponibile", Toast.LENGTH_SHORT).show()
                return
            }
            val startSec = markPos / 1000L
            exportFromFile(demoFile.absolutePath, startSec, durationSec.toLong(), relativeFolder, attackType.code)
            return
        }

        val service = viewModel.getStreamingService()
        val buffer = service?.getCircularHlsBuffer()
        if (buffer == null || !buffer.isActive()) {
            Toast.makeText(requireContext(), "Buffer non ancora pronto", Toast.LENGTH_SHORT).show()
            return
        }

        // Il buffer su disco è un anello: i segmenti più vecchi vengono cancellati man mano che
        // ne arrivano di nuovi (delete_segments), quindi l'indice 0 della lista NON corrisponde
        // alla posizione 0 di ExoPlayer se lo streaming è attivo da più del buffer configurato
        // (es. dopo i primi 5 minuti col buffer di default). Ancoriamo invece il MARK al numero
        // di segmento reale (dal nome file, monotono e mai riassegnato), calcolato in base a
        // quanto il playhead è indietro rispetto al "vivo" (duration - currentPosition): in
        // modalità LIVE/senza delay questo scarto è ~0, quindi il MARK punta all'ultimo segmento
        // disponibile invece che a un indice ormai fuori range.
        val player = exoPlayer
        val duration = player?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: markPos
        val behindLiveSec = ((duration - markPos) / 1000L).toInt().coerceAtLeast(0)

        val segmentsNow = buffer.getSegmentFiles()
        if (segmentsNow.isEmpty()) {
            Toast.makeText(requireContext(), "Buffer non ancora pronto", Toast.LENGTH_SHORT).show()
            return
        }
        val anchorListIndex = (segmentsNow.size - 1 - behindLiveSec).coerceIn(0, segmentsNow.lastIndex)
        val anchorSegmentNumber = segmentNumber(segmentsNow[anchorListIndex])
        if (anchorSegmentNumber == null) {
            Toast.makeText(requireContext(), "Errore nella lettura del buffer", Toast.LENGTH_SHORT).show()
            return
        }

        val newestSegmentNumber = segmentNumber(segmentsNow.last()) ?: anchorSegmentNumber
        val missingSeconds = (anchorSegmentNumber + durationSec) - newestSegmentNumber
        if (missingSeconds > 0) {
            // Il delay impostato è più corto della durata della clip: aspettiamo che il buffer
            // registri i secondi mancanti prima di tagliare la clip in avanti dal MARK.
            handler.postDelayed({
                exportMarkSegments(appCtx, buffer, anchorSegmentNumber, durationSec, relativeFolder, attackType.code)
            }, (missingSeconds * 1000L) + 300L)
        } else {
            exportMarkSegments(appCtx, buffer, anchorSegmentNumber, durationSec, relativeFolder, attackType.code)
        }
    }

    private fun exportMarkSegments(
        appCtx: android.content.Context,
        buffer: CircularHlsBuffer,
        anchorSegmentNumber: Int,
        durationSec: Int,
        relativeFolder: String,
        fileNamePrefix: String
    ) {
        val targetSegments = buffer.getSegmentFiles().filter { file ->
            val n = segmentNumber(file) ?: return@filter false
            n in anchorSegmentNumber..(anchorSegmentNumber + durationSec)
        }
        if (targetSegments.isEmpty()) {
            Toast.makeText(appCtx, "Segmenti buffer non disponibili per la clip", Toast.LENGTH_SHORT).show()
            return
        }

        thread(name = "MarkClipExporter") {
            val tempTsFile = File(appCtx.cacheDir, "concat_temp_${System.currentTimeMillis()}.ts")
            var ok = false
            try {
                tempTsFile.outputStream().use { fos ->
                    for (seg in targetSegments) {
                        if (seg.exists() && seg.length() > 0) {
                            seg.inputStream().use { fis -> fis.copyTo(fos) }
                        }
                    }
                }
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante l'unione dei segmenti MARK", e)
            }

            handler.post {
                if (isAdded && _binding != null) {
                    if (ok && tempTsFile.exists() && tempTsFile.length() > 0) {
                        exportFromFile(tempTsFile.absolutePath, 0L, durationSec.toLong(), relativeFolder, fileNamePrefix)
                    } else {
                        Toast.makeText(appCtx, "Errore nella creazione della clip MARK", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (tempTsFile.exists()) tempTsFile.delete()
                }
            }
        }
    }

    private fun exportFromFile(
        inputPath: String,
        startSec: Long,
        durationSec: Long,
        relativeFolder: String = "Movies/VideoDelay",
        fileNamePrefix: String = "VideoDelay_Clip"
    ) {
        val appCtx = context?.applicationContext ?: return
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cameraName = (viewModel.camera.value?.name ?: "Camera").sanitizedForFilename()
        val fileName = "${fileNamePrefix}_${cameraName}_$timeStamp.mp4"
        val cacheDir = appCtx.cacheDir
        val tempFile = File(cacheDir, fileName)

        // IMPORTANTE: Nessuna virgoletta interna ai percorsi dei file in cmd. 
        // FFmpegKit non viene eseguito in una shell e tratterebbe le virgolette letteralmente, fallendo.
        val cmd = if (startSec >= 0) {
            if (durationSec >= 0) {
                "-y -ss $startSec -t $durationSec -i $inputPath -c copy -movflags +faststart ${tempFile.absolutePath}"
            } else {
                "-y -ss $startSec -i $inputPath -c copy -movflags +faststart ${tempFile.absolutePath}"
            }
        } else {
            "-y -sseof -$durationSec -i $inputPath -c copy -movflags +faststart ${tempFile.absolutePath}"
        }

        Log.d("BUFFER", "Export clip command: $cmd")

        try {
            FFmpegKit.executeAsync(cmd) { session ->
                var success = false
                var failReason: String? = null
                try {
                    if (session != null) {
                        val rc = session.returnCode
                        if (ReturnCode.isSuccess(rc)) {
                            saveVideoToGallery(appCtx, tempFile, fileName, relativeFolder)
                            success = true
                        } else {
                            val logs = session.allLogsAsString
                            failReason = "Codice di ritorno FFmpeg: $rc\n\nLogs:\n$logs"
                            Log.e("BUFFER", "FFmpeg fallito: $failReason")
                        }
                    } else {
                        failReason = "La sessione FFmpeg è nulla."
                        Log.e("BUFFER", "FFmpeg sessione nulla")
                    }
                } catch (t: Throwable) {
                    val sw = java.io.StringWriter()
                    t.printStackTrace(java.io.PrintWriter(sw))
                    failReason = "Eccezione durante salvataggio: ${t.javaClass.simpleName} - ${t.message}\n\n$sw"
                    Log.e("BUFFER", "Errore imprevisto durante salvataggio o esecuzione FFmpeg", t)
                }

                // Pulizia dei file temporanei
                if (inputPath.contains("concat_temp_")) {
                    try { File(inputPath).delete() } catch (e: Exception) {}
                }
                try { tempFile.delete() } catch (e: Exception) {}

                handler.post {
                    if (isAdded && _binding != null) {
                        val ctx = context ?: appCtx
                        if (success) {
                            Toast.makeText(ctx, "Clip salvata in Galleria!", Toast.LENGTH_SHORT).show()
                        } else {
                            val details = failReason ?: "Errore sconosciuto"
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("Errore di Esportazione")
                                .setMessage("Non è stato possibile completare l'esportazione della clip.\n\nDettagli errore:\n$details")
                                .setPositiveButton("OK", null)
                                .setNeutralButton("Copia Errore") { _, _ ->
                                    try {
                                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Errore VideoDelay", details)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(ctx, "Copiato negli appunti!", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Log.e("BUFFER", "Errore copia clipboard", e)
                                    }
                                }
                                .show()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("BUFFER", "Errore nel lancio sincrono di FFmpegKit.executeAsync", t)
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val stackTraceStr = sw.toString()
            val fullError = "Eccezione: ${t.javaClass.name}\nMessaggio: ${t.message}\n\nStack Trace:\n$stackTraceStr"
            
            handler.post {
                val ctx = context ?: appCtx
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Errore Modulo Esportazione")
                    .setMessage("Si è verificato un errore nel modulo di esportazione video.\n\n$fullError")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copia Errore") { _, _ ->
                        try {
                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Errore VideoDelay", fullError)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(ctx, "Copiato negli appunti!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("BUFFER", "Errore copia clipboard", e)
                        }
                    }
                    .show()
            }
            
            // Pulizia dei file temporanei in caso di eccezione immediata
            if (inputPath.contains("concat_temp_")) {
                try { File(inputPath).delete() } catch (e: Exception) {}
            }
            try { tempFile.delete() } catch (e: Exception) {}
        }
    }

    private fun saveVideoToGallery(ctx: android.content.Context, file: File, name: String, relativeFolder: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, relativeFolder)
            }
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { os -> file.inputStream().use { ins -> ins.copyTo(os) } }
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf("30 secondi", "60 secondi", "120 secondi", "300 secondi (5 Min)")
        val values = intArrayOf(30, 60, 120, 300)
        var checkedItem = values.indexOf(viewModel.maxBufferSec)
        if (checkedItem < 0) checkedItem = 3
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Dimensione Buffer Replay")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selectedSec = values[which]
                viewModel.changeBufferDuration(requireContext(), selectedSec, rtspUrl, viewModel.camera.value?.name ?: "Camera")
                binding.sliderDelay.max = selectedSec
                dialog.dismiss()
                Toast.makeText(requireContext(), "Riavvio buffer: ${selectedSec}s", Toast.LENGTH_SHORT).show()
                if (!isDemo) startPlayer()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showDiagnosticsDialog() {
        val stateStr = when {
            isDemo -> "● DEMO (file locale, loop)"
            else -> when (viewModel.streamingState.value) {
                is StreamingState.Idle -> "Inattivo"
                is StreamingState.Connecting -> "⏳ Connessione..."
                is StreamingState.Streaming -> "● LIVE"
                is StreamingState.Error -> "❌ Errore"
                else -> "N/D"
            }
        }
        val playerBuffer = exoPlayer?.let { p ->
            "${(p.bufferedPosition - p.currentPosition) / 1000}s"
        } ?: "N/D"
        val message = """
            URL: $rtspUrl
            Stato: $stateStr
            
            Risoluzione: ${currentWidth}x${currentHeight}
            FPS: ${"%.1f".format(currentFps)}
            Codec Video: $currentVideoCodec
            Codec Audio: $currentAudioCodec
            
            Buffer player: $playerBuffer
            Delay corrente: ${binding.sliderDelay.progress}s
            
            Ultimo Errore: ${lastPlayerError ?: "Nessuno"}
        """.trimIndent()
        val statsVisible = binding.panelRealtimeStats.visibility == View.VISIBLE
        val negativeBtnText = if (statsVisible) "Nascondi Overlay" else "Mostra Overlay"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Diagnostica")
            .setMessage(message)
            .setPositiveButton("Chiudi", null)
            .setNegativeButton(negativeBtnText) { _, _ ->
                val nextVis = if (statsVisible) View.GONE else View.VISIBLE
                binding.panelRealtimeStats.visibility = nextVis
                if (nextVis == View.VISIBLE) {
                    updateRealtimeStats()
                }
            }
            .setNeutralButton("Copia Log FFmpeg") { _, _ ->
                try {
                    val buffer = StreamingForegroundService.logBuffer
                    val logText = if (buffer != null) {
                        synchronized(buffer) {
                            buffer.joinToString("\n")
                        }
                    } else {
                        ""
                    }
                    val finalLog = logText.takeIf { it.isNotEmpty() } ?: "Nessun log disponibile o stream non avviato."
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Log FFmpeg VideoDelay", finalLog)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), "Log completo copiato negli appunti!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Errore copia: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showMarkAdded(attackType: AttackType) {
        binding.btnMark.animate()
            .scaleX(1.3f).scaleY(1.3f).setDuration(150)
            .withEndAction {
                binding.btnMark.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
        Toast.makeText(requireContext(), "📌 ${attackType.code} (${attackType.label}): creazione clip...", Toast.LENGTH_SHORT).show()
    }

    private fun updateRealtimeStats() {
        if (_binding == null || _binding?.panelRealtimeStats?.isVisible == false) return
        
        val player = exoPlayer
        if (player == null) {
            _binding?.tvStatsFps?.text = "FPS Reali: N/D"
            return
        }

        val counters = player.videoDecoderCounters
        if (counters != null) {
            val currentTime = System.currentTimeMillis()
            if (lastFpsCalculationTime > 0) {
                val dt = (currentTime - lastFpsCalculationTime) / 1000.0
                if (dt >= 0.5) {
                    val rendered = counters.renderedOutputBufferCount
                    val diff = rendered - lastRenderedFrames
                    actualFps = diff / dt
                    lastRenderedFrames = rendered
                    lastFpsCalculationTime = currentTime
                }
            } else {
                lastRenderedFrames = counters.renderedOutputBufferCount
                lastFpsCalculationTime = currentTime
            }
            totalDroppedFrames = counters.droppedBufferCount
        }

        val metaFps = player.videoFormat?.frameRate ?: 0f
        _binding?.tvStatsFps?.text = "FPS Reali: ${"%.1f".format(actualFps)} (Stream: ${"%.1f".format(metaFps)})"
        _binding?.tvStatsResolution?.text = "Risoluzione: ${currentWidth}x${currentHeight}"
        _binding?.tvStatsDropped?.text = "Frame persi: $totalDroppedFrames"
        
        val playerBuffer = "${(player.bufferedPosition - player.currentPosition) / 1000}s"
        _binding?.tvStatsBuffer?.text = "Buffer player: $playerBuffer"
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun showRtmpConfigDialog() {
        val prefs = requireContext().getSharedPreferences("videodelay_prefs", android.content.Context.MODE_PRIVATE)
        val lastRtmpUrl = prefs.getString("last_rtmp_url", "rtmp://a.rtmp.youtube.com/live2") ?: "rtmp://a.rtmp.youtube.com/live2"
        val lastStreamKey = prefs.getString("last_stream_key", "") ?: ""

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_rtmp_config, null)
        val etRtmpUrl = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_rtmp_url)
        val etStreamKey = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_stream_key)
        
        etRtmpUrl.setText(lastRtmpUrl)
        etStreamKey.setText(lastStreamKey)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Avvia Diretta Streaming (RTMP)")
            .setView(dialogView)
            .setPositiveButton("Avvia") { _, _ ->
                val rtmpUrl = etRtmpUrl.text.toString().trim()
                val streamKey = etStreamKey.text.toString().trim()

                if (rtmpUrl.isEmpty() || streamKey.isEmpty()) {
                    Toast.makeText(requireContext(), "Inserisci URL e Chiave di Flusso", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Salva le preferenze
                prefs.edit().apply {
                    putString("last_rtmp_url", rtmpUrl)
                    putString("last_stream_key", streamKey)
                    apply()
                }

                viewModel.startRtmpStream(rtmpUrl, streamKey)
                Toast.makeText(requireContext(), "Avvio diretta in corso...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmStopRtmpStream() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Interrompi Diretta")
            .setMessage("Vuoi fermare la trasmissione live in corso?")
            .setPositiveButton("Interrompi") { _, _ ->
                viewModel.stopRtmpStream()
                Toast.makeText(requireContext(), "Diretta interrotta", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
