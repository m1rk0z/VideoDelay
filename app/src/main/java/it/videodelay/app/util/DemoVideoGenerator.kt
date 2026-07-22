package it.videodelay.app.util

import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

object DemoVideoGenerator {

    private const val TAG = "DemoVideoGenerator"

    // ──────────────────────────── Pre-generazione ──────────────────
    // Permette di avviare la generazione già dalla schermata elenco camere e di bloccare
    // l'ingresso nella Demo Camera finché non è pronta, invece di farla partire (e attendere)
    // dentro il player stesso.

    private val _isReady = MutableLiveData(false)
    /** True quando il video demo è pronto per essere riprodotto (o il tentativo è concluso). */
    val isReady: LiveData<Boolean> = _isReady
    private var generationStarted = false

    /** Avvia la generazione in background se non è già stata avviata in questa sessione app. */
    fun ensureGenerated(context: Context) {
        val outputFile = File(context.cacheDir, "demo_video.mp4")
        if (isValidMp4(outputFile)) {
            _isReady.value = true
            return
        }
        if (generationStarted) return
        generationStarted = true
        _isReady.value = false
        thread(name = "DemoPreGenerator") {
            generateDemoVideoIfNeeded(outputFile)
            _isReady.postValue(true)
        }
    }

    /**
     * Genera un video di test da 180 secondi (scena di pallavolo sintetica) nella cache
     * dell'app se non esiste già.
     * - Campo visto di lato: parete palestra, parquet, rete al centro
     * - Pallone che rimbalza campo a campo con archi paraboloici (rende visibile il delay)
     * - Timestamp + contatore secondi in ALTO (zona libera dai controlli in basso)
     * - Badge "● DEMO" in alto a destra e barra di avanzamento in basso
     */
    // Dimensione minima attesa per il video demo 180s a 1280x720 @ 2Mbps (circa 45MB).
    // Tenuta prudenziale per tollerare variazioni del bitrate reale dell'encoder.
    private const val MIN_EXPECTED_BYTES = 22_000_000L // 22 MB

    /**
     * Verifica che il file sia un MP4 completo e decodificabile (contiene il moov atom).
     * Necessario perché una generazione interrotta a metà (app in background, processo ucciso
     * dal sistema) lascia un file grande ma privo del moov atom, scritto solo da muxer.stop()
     * a fine generazione: senza questo controllo verrebbe riusato per sempre come se fosse valido.
     */
    private fun isValidMp4(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_EXPECTED_BYTES) return false
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()
            durationMs > 0
        } catch (e: Exception) {
            Log.w(TAG, "Video demo cache non valido: ${e.message}")
            false
        }
    }

    fun generateDemoVideoIfNeeded(outputFile: File): Boolean {
        if (isValidMp4(outputFile)) {
            Log.d(TAG, "Il video demo esiste già ed è valido: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            return true
        }
        if (outputFile.exists()) {
            Log.d(TAG, "Video demo trovato ma non valido/obsoleto. Rigenero...")
            outputFile.delete()
        }

        // Genera su un file temporaneo e lo rinomina solo a generazione completata: se il
        // processo viene interrotto a metà, il file finale "demo_video.mp4" non esiste mai
        // in stato parziale/corrotto.
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (tempFile.exists()) tempFile.delete()

        Log.d(TAG, "Avvio generazione video demo 180s (scena pallavolo) in corso...")
        try {
            val width = 1280
            val height = 720
            val bitRate = 2_000_000 // 2 Mbps
            val frameRate = 30
            val durationSec = 180
            val totalFrames = durationSec * frameRate // 5400

            // COLOR_FormatYUV420Flexible + getInputImage(): l'unica combinazione con layout
            // (stride/pixel-stride) garantito corretto su tutti gli encoder hardware reali.
            // Il vecchio approccio (SemiPlanar "tight-packed" via ByteBuffer) produceva video
            // che si mux-avano senza errori ma con frame neri/corrotti su molti telefoni.
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val argb = IntArray(width * height)

            // ── Palette scena pallavolo (palestra) ──
            val colorWall = Color.parseColor("#1E3A5F")     // parete blu palestra
            val colorFloor = Color.parseColor("#D98A3D")    // parquet arancione
            val colorFloorLine = Color.parseColor("#F5F5F5") // righe campo bianche
            val colorNet = Color.parseColor("#F5F5F5")       // rete bianca

            val paintFill = Paint().apply { isAntiAlias = true }

            val paintCourtLine = Paint().apply {
                color = colorFloorLine
                strokeWidth = 6f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val paintNet = Paint().apply {
                color = colorNet
                strokeWidth = 2f
                style = Paint.Style.STROKE
                alpha = 200
                isAntiAlias = true
            }

            val paintBall = Paint().apply {
                color = Color.parseColor("#FFEB3B") // pallone giallo
                isAntiAlias = true
            }
            val paintBallSeam = Paint().apply {
                color = Color.parseColor("#0D47A1")
                strokeWidth = 4f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val paintCounter = Paint().apply {
                color = Color.WHITE
                textSize = 120f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setShadowLayer(8f, 4f, 4f, Color.BLACK)
            }

            val paintLabel = Paint().apply {
                color = Color.WHITE
                textSize = 40f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            // Timestamp in ALTO (non coperto dai controlli che sono in basso)
            val paintTime = Paint().apply {
                color = Color.WHITE
                textSize = 40f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            // Badge "DEMO" in alto a destra
            val paintDemo = Paint().apply {
                color = Color.parseColor("#FF5722")
                textSize = 38f
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }

            // Geometria campo (vista laterale)
            val floorTop = height * 0.62f
            val netX = width / 2f
            val netTopY = height * 0.28f
            val ballRadius = 34f
            // Traiettoria: il pallone va avanti e indietro sopra la rete con archi paraboloici.
            // Un "rally" completo (andata+ritorno) dura ~4s per rendere ben visibile il delay.
            val rallyFrames = frameRate * 4

            var frameIndex = 0
            var inputDone = false
            var outputDone = false
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(5000)
                    if (inputBufferIndex >= 0) {
                        if (frameIndex >= totalFrames) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val seconds = frameIndex / frameRate

                            // ── PARETE PALESTRA ──
                            paintFill.color = colorWall
                            canvas.drawRect(0f, 0f, width.toFloat(), floorTop, paintFill)

                            // ── PARQUET ──
                            paintFill.color = colorFloor
                            canvas.drawRect(0f, floorTop, width.toFloat(), height.toFloat(), paintFill)

                            // Righe campo (linea di fondo prospettica + linea centrale sotto la rete)
                            canvas.drawLine(0f, floorTop, width.toFloat(), floorTop, paintCourtLine)
                            canvas.drawLine(netX, floorTop, netX, height.toFloat(), paintCourtLine)
                            canvas.drawLine(width * 0.18f, height.toFloat(), width * 0.32f, floorTop, paintCourtLine)
                            canvas.drawLine(width * 0.82f, height.toFloat(), width * 0.68f, floorTop, paintCourtLine)

                            // ── RETE al centro ──
                            paintFill.color = Color.parseColor("#37474F")
                            canvas.drawRect(netX - 5f, netTopY, netX + 5f, floorTop, paintFill) // palo
                            // banda superiore rete
                            paintFill.color = colorNet
                            canvas.drawRect(netX - 70f, netTopY, netX + 70f, netTopY + 14f, paintFill)
                            // maglie rete
                            for (gx in -70..70 step 14) {
                                canvas.drawLine(netX + gx, netTopY + 14f, netX + gx, floorTop, paintNet)
                            }
                            for (gy in netTopY.toInt()..floorTop.toInt() step 16) {
                                canvas.drawLine(netX - 70f, gy.toFloat(), netX + 70f, gy.toFloat(), paintNet)
                            }

                            // ── PALLONE con traiettoria a parabola sopra la rete ──
                            val phase = (frameIndex % rallyFrames).toFloat() / rallyFrames // 0..1
                            // t va 0→1→0 (andata e ritorno) per far rimbalzare il pallone campo a campo
                            val t = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                            val ballX = width * 0.2f + (width * 0.6f) * t
                            // arco: massimo in alto a metà percorso
                            val arc = Math.sin(Math.PI * t).toFloat()
                            val ballY = floorTop - 40f - (floorTop - netTopY - 60f) * arc
                            paintBall.color = Color.parseColor("#FFEB3B")
                            canvas.drawCircle(ballX, ballY, ballRadius, paintBall)
                            // cuciture pallone
                            canvas.drawCircle(ballX, ballY, ballRadius, paintBallSeam)
                            canvas.drawLine(ballX - ballRadius, ballY, ballX + ballRadius, ballY, paintBallSeam)
                            canvas.drawLine(ballX, ballY - ballRadius, ballX, ballY + ballRadius, paintBallSeam)

                            // ── TIMESTAMP IN ALTO (zona libera) ──
                            val simTime = System.currentTimeMillis() - (durationSec - seconds) * 1000L
                            val timeStr = sdf.format(Date(simTime))
                            canvas.drawText(timeStr, width / 2f, 60f, paintTime)

                            // ── BADGE "● DEMO" in alto a destra ──
                            canvas.drawText("● DEMO", width - 120f, 55f, paintDemo)

                            // ── CONTATORE SECONDI in alto (per misurare il delay) ──
                            canvas.drawText("${seconds}s", width / 2f, 150f, paintCounter)

                            // ── LABEL in alto ──
                            canvas.drawText("VideoDelay • Pallavolo Demo", width / 2f, 195f, paintLabel)

                            // ── BARRA PROGRESSO in basso (ma sopra i controlli UI) ──
                            val progressWidth = (width.toFloat() * seconds / durationSec)
                            val progressPaint = Paint().apply {
                                color = Color.WHITE
                                alpha = 100
                            }
                            canvas.drawRect(0f, height - 12f, progressWidth, height.toFloat(), progressPaint)

                            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                            val image = codec.getInputImage(inputBufferIndex)!!
                            fillYuvImageFromArgb(image, argb, width, height)
                            val ptsUs = (frameIndex * 1_000_000L) / frameRate
                            codec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.capacity(), ptsUs, 0)
                            frameIndex++
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
                if (outputBufferIndex >= 0) {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
            }

            codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()

            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
            Log.d(TAG, "Video demo 180s (pallavolo) generato: ${outputFile.length()} bytes")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Errore generazione video demo", e)
            tempFile.delete()
            return false
        }
    }

    /**
     * Scrive i pixel ARGB nei piani Y/U/V dell'[android.media.Image] restituito da
     * [MediaCodec.getInputImage], rispettando rowStride/pixelStride reali dell'encoder.
     * Necessario perché molti encoder hardware Android non usano un layout NV12 "tight-packed":
     * scrivere assumendo stride == width produce frame neri o corrotti su diversi telefoni reali.
     */
    private fun fillYuvImageFromArgb(image: android.media.Image, argb: IntArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (j in 0 until height) {
            val rowOffset = j * yPlane.rowStride
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(rowOffset + i * yPlane.pixelStride, y.coerceIn(0, 255).toByte())
            }
        }

        for (j in 0 until height / 2) {
            val uRowOffset = j * uPlane.rowStride
            val vRowOffset = j * vPlane.rowStride
            for (i in 0 until width / 2) {
                val color = argb[(j * 2) * width + (i * 2)]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuffer.put(uRowOffset + i * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                vBuffer.put(vRowOffset + i * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
            }
        }
    }
}
