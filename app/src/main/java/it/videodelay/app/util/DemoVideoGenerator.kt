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

    /** Restituisce il file permanente del video demo in filesDir (mai rimosso automaticamente dal sistema). */
    fun getDemoFile(context: Context): File = File(context.filesDir, "demo_video.mp4")

    private val _isReady = MutableLiveData(false)
    /** True quando il video demo è pronto per essere riprodotto (o il tentativo è concluso). */
    val isReady: LiveData<Boolean> = _isReady
    private var generationStarted = false

    /** Avvia la generazione in background se non è già stata avviata in questa sessione app. */
    fun ensureGenerated(context: Context) {
        val outputFile = getDemoFile(context)
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
     * Dimensione minima attesa per il video demo 180s a 1280x720 @ 1.5Mbps (circa 30MB).
     */
    private const val MIN_EXPECTED_BYTES = 12_000_000L // 12 MB

    /**
     * Verifica che il file sia un MP4 completo e decodificabile.
     */
    fun isValidMp4(file: File): Boolean {
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

        val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (tempFile.exists()) tempFile.delete()

        Log.d(TAG, "Avvio generazione video demo 180s (scena pallavolo dinamica vista da dietro)...")
        try {
            val width = 1280
            val height = 720
            val bitRate = 1_500_000 // 1.5 Mbps
            val frameRate = 30
            val durationSec = 180
            val totalFrames = durationSec * frameRate // 5400 frames

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

            // ── Palette Scena Pallavolo (Vista da dietro la linea di fondo) ──
            val colorSky = Color.parseColor("#0F172A")       // Parete palestra blu navy
            val colorCourtBlue = Color.parseColor("#1D4ED8")  // Area di gioco blu
            val colorSurround = Color.parseColor("#C2410C")   // Perimetro arancione
            val colorLine = Color.parseColor("#FFFFFF")       // Righe di campo bianche
            val colorNetBand = Color.parseColor("#FFFFFF")   // Banda superiore rete
            val colorAntenna = Color.parseColor("#EF4444")   // Aste antenne rosse

            val paintFill = Paint().apply { isAntiAlias = true }
            val paintLine = Paint().apply {
                color = colorLine
                strokeWidth = 6f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val paintNetMesh = Paint().apply {
                color = Color.parseColor("#D1D5DB")
                strokeWidth = 2f
                style = Paint.Style.STROKE
                alpha = 200
                isAntiAlias = true
            }

            val paintAntenna = Paint().apply {
                color = colorAntenna
                strokeWidth = 5f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val paintBallYellow = Paint().apply {
                color = Color.parseColor("#FACC15")
                isAntiAlias = true
            }
            val paintBallBlue = Paint().apply {
                color = Color.parseColor("#2563EB")
                isAntiAlias = true
            }
            val paintBallSeam = Paint().apply {
                color = Color.parseColor("#1E293B")
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            val paintShadow = Paint().apply {
                color = Color.parseColor("#40000000")
                isAntiAlias = true
            }

            val paintCounter = Paint().apply {
                color = Color.WHITE
                textSize = 100f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setShadowLayer(8f, 4f, 4f, Color.BLACK)
            }

            val paintLabel = Paint().apply {
                color = Color.parseColor("#38BDF8")
                textSize = 34f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            val paintTime = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            val paintDemo = Paint().apply {
                color = Color.parseColor("#F97316")
                textSize = 36f
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }

            // Geometria campo in prospettiva 3D vista da dietro (dalla linea di fondo vicina)
            val floorTopY = height * 0.38f
            val floorBottomY = height.toFloat()

            val courtFarLeft = width * 0.36f
            val courtFarRight = width * 0.64f
            val courtNearLeft = -width * 0.05f
            val courtNearRight = width * 1.05f

            // Rete al centro del campo
            val netCenterY = height * 0.52f
            val netTopY = height * 0.36f
            val netLeftX = width * 0.22f
            val netRightX = width * 0.78f

            // ── SEQUENZA RALLY DINAMICA (Traiettorie tra i due campi e cambi di direzione tra le aste) ──
            // Ogni waypoint definisce: side (0 = Campo Vicino/Casa, 1 = Campo Avversario) e xRatio (0.15..0.85 tra le antenne)
            val waypoints = listOf(
                Pair(0, 0.75f), // Rimbalzo campo casa a destra
                Pair(1, 0.25f), // Schiacciata nel campo avversario a sinistra
                Pair(0, 0.60f), // Risposta campo casa centro-destra
                Pair(1, 0.85f), // Attacco campo avversario a destra vicina all'asta
                Pair(0, 0.15f), // Difesa campo casa a sinistra vicino all'asta
                Pair(1, 0.50f), // Alzata/Attacco al centro del campo avversario
                Pair(0, 0.80f), // Contro-attacco campo casa a destra
                Pair(1, 0.30f), // Rimbalzo campo avversario centro-sinistra
                Pair(0, 0.20f), // Diagonale stretta verso campo casa sinistra
                Pair(1, 0.70f)  // Lungolinea verso campo avversario destra
            )

            val strokeFrames = (frameRate * 1.8f).toInt() // Ogni passaggio del pallone dura ~1.8 secondi

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

                            // ── 1. SPALTI E PARETE PALESTRA (Sfondo) ──
                            paintFill.color = colorSky
                            canvas.drawRect(0f, 0f, width.toFloat(), floorTopY, paintFill)

                            // Luci palazzetto in alto
                            paintFill.color = Color.parseColor("#38BDF8")
                            paintFill.alpha = 80
                            canvas.drawCircle(width * 0.25f, 60f, 40f, paintFill)
                            canvas.drawCircle(width * 0.75f, 60f, 40f, paintFill)
                            paintFill.alpha = 255

                            // ── 2. PAVIMENTO FUORI CAMPO (Arancione) ──
                            val surroundPath = Path().apply {
                                moveTo(0f, floorTopY)
                                lineTo(width.toFloat(), floorTopY)
                                lineTo(width.toFloat(), floorBottomY)
                                lineTo(0f, floorBottomY)
                                close()
                            }
                            paintFill.color = colorSurround
                            canvas.drawPath(surroundPath, paintFill)

                            // ── 3. CAMPO DA GIOCO BLU IN PROSPETTIVA ──
                            val courtPath = Path().apply {
                                moveTo(courtFarLeft, floorTopY)
                                lineTo(courtFarRight, floorTopY)
                                lineTo(courtNearRight, floorBottomY)
                                lineTo(courtNearLeft, floorBottomY)
                                close()
                            }
                            paintFill.color = colorCourtBlue
                            canvas.drawPath(courtPath, paintFill)

                            // ── 4. RIGHE BIANCHE DEL CAMPO IN PROSPETTIVA ──
                            // Linee laterali
                            canvas.drawLine(courtNearLeft, floorBottomY, courtFarLeft, floorTopY, paintLine)
                            canvas.drawLine(courtNearRight, floorBottomY, courtFarRight, floorTopY, paintLine)
                            // Linea di fondo vicina
                            canvas.drawLine(0f, floorBottomY - 4f, width.toFloat(), floorBottomY - 4f, paintLine)
                            // Linea di fondo opposta
                            canvas.drawLine(courtFarLeft, floorTopY, courtFarRight, floorTopY, paintLine)

                            // Linea centrale sotto la rete (y = netCenterY)
                            val centerLeftX = courtNearLeft + (courtFarLeft - courtNearLeft) * 0.68f
                            val centerRightX = courtNearRight - (courtNearRight - courtFarRight) * 0.68f
                            canvas.drawLine(centerLeftX, netCenterY, centerRightX, netCenterY, paintLine)

                            // Linea dei 3 metri vicina (campo del giocatore)
                            val att3mNearY = height * 0.74f
                            val attNearLeftX = courtNearLeft + (courtFarLeft - courtNearLeft) * 0.35f
                            val attNearRightX = courtNearRight - (courtNearRight - courtFarRight) * 0.35f
                            canvas.drawLine(attNearLeftX, att3mNearY, attNearRightX, att3mNearY, paintLine)

                            // Linea dei 3 metri opposta (campo avversario)
                            val att3mFarY = height * 0.44f
                            val attFarLeftX = courtNearLeft + (courtFarLeft - courtNearLeft) * 0.84f
                            val attFarRightX = courtNearRight - (courtNearRight - courtFarRight) * 0.84f
                            canvas.drawLine(attFarLeftX, att3mFarY, attFarRightX, att3mFarY, paintLine)

                            // ── 5. RETE DA PALLAVOLO FRONTALE / PROSPETTICA ──
                            // Pali di sostegno laterali
                            paintFill.color = Color.parseColor("#475569")
                            canvas.drawRect(netLeftX - 6f, netTopY - 10f, netLeftX + 6f, netCenterY + 40f, paintFill)
                            canvas.drawRect(netRightX - 6f, netTopY - 10f, netRightX + 6f, netCenterY + 40f, paintFill)

                            // Maglia della rete
                            val cols = 24
                            val rows = 6
                            for (c in 0..cols) {
                                val rx = netLeftX + (netRightX - netLeftX) * c / cols
                                canvas.drawLine(rx, netTopY, rx, netCenterY, paintNetMesh)
                            }
                            for (r in 0..rows) {
                                val ry = netTopY + (netCenterY - netTopY) * r / rows
                                canvas.drawLine(netLeftX, ry, netRightX, ry, paintNetMesh)
                            }

                            // Banda superiore bianca della rete
                            paintFill.color = colorNetBand
                            canvas.drawRect(netLeftX - 10f, netTopY - 8f, netRightX + 10f, netTopY + 4f, paintFill)

                            // Antenne bianche e rosse ai lati della rete
                            val antennaLeftX = centerLeftX + (centerRightX - centerLeftX) * 0.08f
                            val antennaRightX = centerRightX - (centerRightX - centerLeftX) * 0.08f
                            canvas.drawLine(antennaLeftX, netTopY - 70f, antennaLeftX, netCenterY, paintAntenna)
                            canvas.drawLine(antennaRightX, netTopY - 70f, antennaRightX, netCenterY, paintAntenna)

                            // ── 6. ANIMAZIONE PALLONE DINAMICA TRA I DUE CAMPI ──
                            val strokeIndex = frameIndex / strokeFrames
                            val t = (frameIndex % strokeFrames).toFloat() / strokeFrames.toFloat() // 0..1

                            val wpCurrent = waypoints[strokeIndex % waypoints.size]
                            val wpNext = waypoints[(strokeIndex + 1) % waypoints.size]

                            // Calcolo posizione X e Y tra il punto d'origine e il punto di destinazione
                            val fromX = netLeftX + (netRightX - netLeftX) * wpCurrent.second
                            val toX = netLeftX + (netRightX - netLeftX) * wpNext.second
                            val ballX = fromX + (toX - fromX) * t

                            val fromY = if (wpCurrent.first == 0) floorBottomY - 120f else floorTopY + 40f
                            val toY = if (wpNext.first == 0) floorBottomY - 120f else floorTopY + 40f
                            val baseFloorY = fromY + (toY - fromY) * t

                            // Parabola del volo sopra la rete
                            val arcHeight = 160f
                            val ballY = baseFloorY - arcHeight * Math.sin(Math.PI * t).toFloat()

                            // Prospettiva dimensione pallone (più grande vicino, più piccolo lontano)
                            val depthRatio = (baseFloorY - floorTopY) / (floorBottomY - floorTopY) // 0 (lontano) .. 1 (vicino)
                            val ballRadius = 18f + 20f * depthRatio.coerceIn(0f, 1f)

                            // Ombra del pallone sul pavimento del campo
                            canvas.drawOval(
                                ballX - ballRadius * 1.2f, baseFloorY - 6f,
                                ballX + ballRadius * 1.2f, baseFloorY + 6f,
                                paintShadow
                            )

                            // Disegno Pallone da Pallavolo a colori (Giallo/Blu/Bianco)
                            canvas.drawCircle(ballX, ballY, ballRadius, paintBallYellow)
                            // Pannello blu curvo
                            val bluePath = Path().apply {
                                addArc(ballX - ballRadius, ballY - ballRadius, ballX + ballRadius, ballY + ballRadius, (frameIndex * 5) % 360f, 120f)
                                lineTo(ballX, ballY)
                                close()
                            }
                            canvas.drawPath(bluePath, paintBallBlue)
                            // Cuciture
                            canvas.drawCircle(ballX, ballY, ballRadius, paintBallSeam)
                            canvas.drawLine(ballX - ballRadius * 0.7f, ballY, ballX + ballRadius * 0.7f, ballY, paintBallSeam)
                            canvas.drawLine(ballX, ballY - ballRadius * 0.7f, ballX, ballY + ballRadius * 0.7f, paintBallSeam)

                            // ── 7. TESTI E TIMESTAMP (IN ALTO - ZONA LIBERA) ──
                            val simTime = System.currentTimeMillis() - (durationSec - seconds) * 1000L
                            val timeStr = sdf.format(Date(simTime))
                            canvas.drawText(timeStr, width / 2f, 55f, paintTime)

                            canvas.drawText("● DEMO", width - 110f, 50f, paintDemo)

                            // Contatore dei secondi enorme al centro in alto
                            canvas.drawText("${seconds}s", width / 2f, 145f, paintCounter)

                            // Titolo applicazione
                            canvas.drawText("VideoDelay • Pallavolo Demo", width / 2f, 185f, paintLabel)

                            // ── BARRA PROGRESSO IN BASSO ──
                            val progressWidth = (width.toFloat() * seconds / durationSec)
                            val progressPaint = Paint().apply {
                                color = Color.parseColor("#38BDF8")
                                alpha = 180
                            }
                            canvas.drawRect(0f, height - 10f, progressWidth, height.toFloat(), progressPaint)

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
            Log.d(TAG, "Video demo 180s (pallavolo dinamica vista da dietro) generato: ${outputFile.length()} bytes")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Errore generazione video demo", e)
            tempFile.delete()
            return false
        }
    }

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
