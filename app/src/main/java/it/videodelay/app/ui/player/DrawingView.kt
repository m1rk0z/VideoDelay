package it.videodelay.app.ui.player

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawPath = Path()
    private var drawPaint = Paint()
    private var canvasPaint = Paint(Paint.DITHER_FLAG)
    private var drawCanvas: Canvas? = null
    private var canvasBitmap: Bitmap? = null
    private var currentPaintColor = Color.RED
    private var strokeWidth = 10f

    // Storico dei tratti disegnati (Path + Paint) per la funzione di Annulla (Undo)
    private val paths = ArrayList<Pair<Path, Paint>>()

    init {
        setupDrawing()
    }

    private fun setupDrawing() {
        drawPaint.color = currentPaintColor
        drawPaint.isAntiAlias = true
        drawPaint.strokeWidth = strokeWidth
        drawPaint.style = Paint.Style.STROKE
        drawPaint.strokeJoin = Paint.Join.ROUND
        drawPaint.strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawCanvas = Canvas(canvasBitmap!!)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Disegna l'immagine di sfondo (lo screenshot catturato)
        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, canvasPaint)
        }
        
        // Disegna tutti i tratti salvati nello storico (Undo stack)
        for (p in paths) {
            canvas.drawPath(p.first, p.second)
        }
        
        // Disegna il tratto attualmente in corso di disegno
        canvas.drawPath(drawPath, drawPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                drawPath.moveTo(touchX, touchY)
            }
            MotionEvent.ACTION_MOVE -> {
                drawPath.lineTo(touchX, touchY)
            }
            MotionEvent.ACTION_UP -> {
                drawPath.lineTo(touchX, touchY)
                val finishedPath = Path(drawPath)
                val finishedPaint = Paint(drawPaint)
                paths.add(Pair(finishedPath, finishedPaint))
                drawPath.reset()
            }
            else -> return false
        }

        invalidate()
        return true
    }

    fun setColor(newColor: Int) {
        currentPaintColor = newColor
        drawPaint.color = currentPaintColor
    }

    fun setStrokeWidth(newWidth: Float) {
        strokeWidth = newWidth
        drawPaint.strokeWidth = strokeWidth
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            paths.removeAt(paths.size - 1)
            invalidate()
        }
    }

    fun clear() {
        paths.clear()
        invalidate()
    }

    fun setBackgroundImage(bitmap: Bitmap) {
        post {
            val viewWidth = width
            val viewHeight = height
            if (viewWidth > 0 && viewHeight > 0) {
                // Scala l'immagine in proporzione per adattarla alla DrawingView con barre nere esterne
                val scaledBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(scaledBitmap)
                
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val viewAspect = viewWidth.toFloat() / viewHeight
                val bmpAspect = bitmap.width.toFloat() / bitmap.height
                val destRect = if (bmpAspect > viewAspect) {
                    val targetHeight = (viewWidth / bmpAspect).toInt()
                    val top = (viewHeight - targetHeight) / 2
                    Rect(0, top, viewWidth, top + targetHeight)
                } else {
                    val targetWidth = (viewHeight * bmpAspect).toInt()
                    val left = (viewWidth - targetWidth) / 2
                    Rect(left, 0, left + targetWidth, viewHeight)
                }
                
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, srcRect, destRect, null)
                
                canvasBitmap = scaledBitmap
                drawCanvas = Canvas(canvasBitmap!!)
                invalidate()
            }
        }
    }

    fun getFinalBitmap(): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        draw(canvas)
        return resultBitmap
    }
}
