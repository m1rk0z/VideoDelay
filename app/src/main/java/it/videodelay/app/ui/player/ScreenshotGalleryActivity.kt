package it.videodelay.app.ui.player

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.videodelay.app.R
import it.videodelay.app.util.ScreenshotUtil
import java.io.File

class ScreenshotGalleryActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_DELETE = 1001
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DELETE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Operazione completata con successo", Toast.LENGTH_SHORT).show()
            }
            exitSelectionMode()
            loadScreenshots()
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: GalleryAdapter
    private val screenshotUris = ArrayList<Uri>()

    // Variabili per la gestione della selezione multipla
    private var isSelectionMode = false
    private val selectedUris = HashSet<Uri>()
    
    private lateinit var layoutSelectionActions: View
    private lateinit var btnSelectAll: Button
    private lateinit var btnShareSelected: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnCancelSelection: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot_gallery)

        // Forza portrait per la galleria principale
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        val toolbar = findViewById<Toolbar>(R.id.toolbar_gallery)
        toolbar.title = "Analisi Screenshot"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (isSelectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        recyclerView = findViewById(R.id.recycler_screenshots)
        tvEmpty = findViewById(R.id.tv_gallery_empty)

        // Elementi della barra di selezione
        layoutSelectionActions = findViewById(R.id.layout_selection_actions)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnShareSelected = findViewById(R.id.btn_share_selected)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
        btnCancelSelection = findViewById(R.id.btn_cancel_selection)

        setupSelectionListeners()

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = GalleryAdapter()
        recyclerView.adapter = adapter

        loadScreenshots()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_gallery, menu)
        // Nasconde il tasto di selezione manuale se siamo già in selezione multipla
        menu.findItem(R.id.action_select)?.isVisible = !isSelectionMode
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_select) {
            enterSelectionMode()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupSelectionListeners() {
        btnSelectAll.setOnClickListener {
            selectedUris.clear()
            selectedUris.addAll(screenshotUris)
            updateSelectionTitle()
            adapter.notifyDataSetChanged()
        }

        btnShareSelected.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Nessun elemento selezionato", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareMultipleScreenshots()
        }

        btnDeleteSelected.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Nessun elemento selezionato", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmAndDeleteSelected()
        }

        btnCancelSelection.setOnClickListener {
            exitSelectionMode()
        }
    }

    private fun loadScreenshots() {
        screenshotUris.clear()
        val uris = ScreenshotUtil.getSavedScreenshots(this)
        screenshotUris.addAll(uris)

        if (screenshotUris.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            exitSelectionMode()
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedUris.clear()
        layoutSelectionActions.visibility = View.VISIBLE
        updateSelectionTitle()
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedUris.clear()
        layoutSelectionActions.visibility = View.GONE
        findViewById<Toolbar>(R.id.toolbar_gallery).title = "Analisi Screenshot"
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun toggleSelection(uri: Uri) {
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri)
        } else {
            selectedUris.add(uri)
        }
        updateSelectionTitle()
        adapter.notifyDataSetChanged()
        
        if (selectedUris.isEmpty()) {
            exitSelectionMode()
        }
    }

    private fun updateSelectionTitle() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar_gallery)
        toolbar.title = "${selectedUris.size} Selezionati"
    }

    private fun shareMultipleScreenshots() {
        try {
            val urisList = ArrayList(selectedUris)
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, urisList)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi ${urisList.size} screenshot"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore durante la condivisione: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmAndDeleteSelected() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elimina selezionati")
            .setMessage("Sei sicuro di voler eliminare definitivamente i ${selectedUris.size} screenshot selezionati?")
            .setPositiveButton("Elimina") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val pendingIntent = MediaStore.createDeleteRequest(contentResolver, selectedUris.toList())
                        startIntentSenderForResult(pendingIntent.intentSender, REQUEST_CODE_DELETE, null, 0, 0, 0)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this, "Errore durante l'eliminazione", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    var deleteCount = 0
                    for (uri in selectedUris) {
                        try {
                            contentResolver.delete(uri, null, null)
                            deleteCount++
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    Toast.makeText(this, "$deleteCount screenshot eliminati", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadScreenshots()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showFullscreenImage(uri: Uri, position: Int) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_fullscreen_image)

        val imageView = dialog.findViewById<ImageView>(R.id.iv_fullscreen)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btn_dialog_close)
        val btnShare = dialog.findViewById<Button>(R.id.btn_dialog_share)
        val btnDelete = dialog.findViewById<Button>(R.id.btn_dialog_delete)

        // Carica immagine a pieno schermo
        imageView.setImageURI(uri)

        btnClose.setOnClickListener { dialog.dismiss() }

        btnShare.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi screenshot"))
        }

        btnDelete.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Elimina immagine")
                .setMessage("Sei sicuro di voler eliminare definitivamente questo screenshot?")
                .setPositiveButton("Elimina") { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                            startIntentSenderForResult(pendingIntent.intentSender, REQUEST_CODE_DELETE, null, 0, 0, 0)
                            dialog.dismiss()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this, "Impossibile eliminare l'immagine", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        try {
                            contentResolver.delete(uri, null, null)
                            Toast.makeText(this, "Immagine eliminata", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            loadScreenshots()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this, "Impossibile eliminare l'immagine", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        dialog.show()
    }

    override fun onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    // ──────────────────────────── Adapter / ViewHolder ──────────────────────

    inner class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_screenshot, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uri = screenshotUris[position]
            val isSelected = selectedUris.contains(uri)
            holder.bind(uri, position, isSelected)
        }

        override fun getItemCount(): Int = screenshotUris.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ImageView = itemView.findViewById(R.id.iv_screenshot_thumbnail)
            private val selectedOverlay: View = itemView.findViewById(R.id.view_selected_overlay)
            private val selectedCheck: ImageView = itemView.findViewById(R.id.iv_selected_check)

            fun bind(uri: Uri, position: Int, isSelected: Boolean) {
                // Imposta visualizzazione dello stato selezionato
                selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                selectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                // Carica il thumbnail nativo per risparmiare memoria (Android 10+)
                try {
                    val thumbnail: Bitmap = contentResolver.loadThumbnail(uri, Size(250, 250), null)
                    imageView.setImageBitmap(thumbnail)
                } catch (e: Exception) {
                    imageView.setImageURI(uri) // Fallback diretto
                }

                itemView.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(uri)
                    } else {
                        showFullscreenImage(uri, position)
                    }
                }

                itemView.setOnLongClickListener {
                    if (!isSelectionMode) {
                        enterSelectionMode()
                        toggleSelection(uri)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}
