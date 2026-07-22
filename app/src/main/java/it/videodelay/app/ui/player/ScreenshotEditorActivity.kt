package it.videodelay.app.ui.player

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import it.videodelay.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCREENSHOT_PATH = "extra_screenshot_path"
    }

    private lateinit var drawingView: DrawingView
    private var screenshotPath: String? = null
    private var loadedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot_editor)

        // Forza la visibilità della barra delle notifiche (status bar) anche in landscape
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())

        // Forza orientamento orizzontale coerente con il player
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        if (screenshotPath.isNullOrEmpty()) {
            Toast.makeText(this, "Errore: Screenshot non trovato", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Carica l'immagine catturata
        val file = File(screenshotPath!!)
        if (!file.exists()) {
            Toast.makeText(this, "Errore: File temporaneo inesistente", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadedBitmap = BitmapFactory.decodeFile(file.absolutePath)

        drawingView = findViewById(R.id.drawing_view)
        loadedBitmap?.let { drawingView.setBackgroundImage(it) }

        setupToolbar()
        setupPalette()
    }

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.btn_editor_close).setOnClickListener {
            // Elimina file temporaneo all'uscita senza salvare
            deleteTempFile()
            finish()
        }

        findViewById<ImageButton>(R.id.btn_editor_undo).setOnClickListener {
            drawingView.undo()
        }

        findViewById<ImageButton>(R.id.btn_editor_clear).setOnClickListener {
            drawingView.clear()
        }

        findViewById<Button>(R.id.btn_editor_save).setOnClickListener {
            saveToGallery()
        }

        findViewById<Button>(R.id.btn_editor_share).setOnClickListener {
            shareScreenshot()
        }
    }

    private fun setupPalette() {
        val redDot = findViewById<View>(R.id.color_red)
        val greenDot = findViewById<View>(R.id.color_green)
        val blueDot = findViewById<View>(R.id.color_blue)
        val yellowDot = findViewById<View>(R.id.color_yellow)
        val whiteDot = findViewById<View>(R.id.color_white)
        val blackDot = findViewById<View>(R.id.color_black)

        // Applica i colori ai rispettivi background programmaticamente
        redDot.background.setTint(Color.RED)
        greenDot.background.setTint(Color.GREEN)
        blueDot.background.setTint(Color.BLUE)
        yellowDot.background.setTint(Color.YELLOW)
        whiteDot.background.setTint(Color.WHITE)
        blackDot.background.setTint(Color.BLACK)

        // Seleziona il colore di default (Rosso) ed evidenzialo
        selectColor(redDot, Color.RED)

        redDot.setOnClickListener { selectColor(it, Color.RED) }
        greenDot.setOnClickListener { selectColor(it, Color.GREEN) }
        blueDot.setOnClickListener { selectColor(it, Color.BLUE) }
        yellowDot.setOnClickListener { selectColor(it, Color.YELLOW) }
        whiteDot.setOnClickListener { selectColor(it, Color.WHITE) }
        blackDot.setOnClickListener { selectColor(it, Color.BLACK) }
    }

    private var activeDot: View? = null

    private fun selectColor(dotView: View, color: Int) {
        // Ripristina la scala dell'ultimo pallino selezionato
        activeDot?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(100)?.start()
        
        // Ingrandisci leggermente il pallino selezionato per dare feedback
        dotView.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100).start()
        activeDot = dotView

        drawingView.setColor(color)
    }

    private fun saveToGallery() {
        try {
            val bitmap = drawingView.getFinalBitmap()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "videodelay_edited_$timestamp.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VideoDelay")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Impossibile inserire nel MediaStore")

            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            Toast.makeText(this, "Screenshot modificato salvato in Galleria!", Toast.LENGTH_SHORT).show()
            deleteTempFile()
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore durante il salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareScreenshot() {
        try {
            val bitmap = drawingView.getFinalBitmap()
            val tempFile = File(cacheDir, "edited_screenshot_${System.currentTimeMillis()}.jpg")
            
            contentResolver.openOutputStream(Uri.fromFile(tempFile))?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                tempFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi screenshot modificato"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore durante la condivisione: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteTempFile() {
        screenshotPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadedBitmap?.recycle()
    }
}
