package it.videodelay.app.ui.player

import android.app.AlertDialog
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.videodelay.app.R
import it.videodelay.app.util.MatchManager
import it.videodelay.app.util.ScreenshotUtil
import java.io.File

class ScreenshotGalleryActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_DELETE = 1001
        private const val REQUEST_CODE_DELETE_MATCH = 1002
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DELETE || requestCode == REQUEST_CODE_DELETE_MATCH) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Operazione completata con successo", Toast.LENGTH_SHORT).show()
            }
            exitSelectionMode()
            setupMatchBar()
            loadScreenshots()
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: GalleryAdapter
    private val screenshotUris = ArrayList<Uri>()

    // Elementi Match / Partite
    private lateinit var spinnerMatchFolders: Spinner
    private lateinit var btnNewMatch: Button
    private lateinit var btnDeleteMatch: ImageButton
    private var selectedMatchFolder: String = "Tutte le partite"
    private var matchFoldersList = ArrayList<String>()

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

        layoutSelectionActions = findViewById(R.id.layout_selection_actions)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnShareSelected = findViewById(R.id.btn_share_selected)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
        btnCancelSelection = findViewById(R.id.btn_cancel_selection)

        spinnerMatchFolders = findViewById(R.id.spinner_match_folders)
        btnNewMatch = findViewById(R.id.btn_new_match)
        btnDeleteMatch = findViewById(R.id.btn_delete_match)

        setupSelectionListeners()
        setupMatchBar()

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = GalleryAdapter()
        recyclerView.adapter = adapter

        loadScreenshots()
    }

    private fun setupMatchBar() {
        val matches = MatchManager.getAllMatchFolders(this).toMutableList()
        matchFoldersList.clear()
        matchFoldersList.add("Tutte le partite")
        matchFoldersList.addAll(matches.filter { it != "Tutte le partite" })

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            matchFoldersList
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerMatchFolders.adapter = spinnerAdapter

        // Seleziona la partita attiva di default se presente
        val active = MatchManager.getActiveMatch(this)
        val activeIndex = matchFoldersList.indexOf(active).coerceAtLeast(0)
        spinnerMatchFolders.setSelection(activeIndex)
        selectedMatchFolder = matchFoldersList[activeIndex]

        spinnerMatchFolders.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedMatchFolder = matchFoldersList[position]
                if (selectedMatchFolder != "Tutte le partite") {
                    MatchManager.setActiveMatch(this@ScreenshotGalleryActivity, selectedMatchFolder)
                }
                loadScreenshots()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnNewMatch.setOnClickListener { showNewMatchDialog() }
        btnDeleteMatch.setOnClickListener { confirmDeleteMatchFolder() }
    }

    private fun showNewMatchDialog() {
        val input = EditText(this).apply {
            hint = "Es. Partita vs Roma"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("➕ Nuova Partita / Sessione")
            .setMessage("Inserisci il nome per la nuova cartella partita. Tutti i nuovi screenshot e clip verranno organizzati qui.")
            .setView(input)
            .setPositiveButton("Crea e Attiva") { _, _ ->
                val name = input.text.toString()
                val created = MatchManager.createNewMatch(this, name)
                Toast.makeText(this, "Nuova partita creata: $created", Toast.LENGTH_SHORT).show()
                setupMatchBar()
                loadScreenshots()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmDeleteMatchFolder() {
        if (selectedMatchFolder == "Tutte le partite") {
            Toast.makeText(this, "Seleziona una specifica cartella partita da eliminare", Toast.LENGTH_SHORT).show()
            return
        }
        if (screenshotUris.isEmpty()) {
            Toast.makeText(this, "Nessun elemento da eliminare in $selectedMatchFolder", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("🗑️ Elimina Partita")
            .setMessage("Vuoi eliminare definitivamente tutti gli screenshot della cartella \"$selectedMatchFolder\" (${screenshotUris.size} file)?")
            .setPositiveButton("Elimina Tutto") { _, _ ->
                selectedUris.clear()
                selectedUris.addAll(screenshotUris)
                confirmAndDeleteSelected()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_gallery, menu)
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
        val uris = ScreenshotUtil.getSavedScreenshots(this, selectedMatchFolder)
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
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedUris.clear()
        layoutSelectionActions.visibility = View.GONE
        supportActionBar?.title = "Analisi Screenshot"
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionTitle() {
        supportActionBar?.title = "${selectedUris.size} selezionati"
    }

    private fun toggleItemSelection(uri: Uri) {
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri)
            if (selectedUris.isEmpty()) {
                exitSelectionMode()
                return
            }
        } else {
            selectedUris.add(uri)
        }
        updateSelectionTitle()
        adapter.notifyDataSetChanged()
    }

    private fun shareMultipleScreenshots() {
        val uriList = ArrayList(selectedUris)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Condividi ${selectedUris.size} screenshot"))
    }

    private fun confirmAndDeleteSelected() {
        val count = selectedUris.size
        AlertDialog.Builder(this)
            .setTitle("Elimina screenshot")
            .setMessage("Sei sicuro di voler eliminare definitivamente $count screenshot?")
            .setPositiveButton("Elimina") { _, _ ->
                performBatchDelete()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun performBatchDelete() {
        val urisToDelete = selectedUris.toList()
        if (urisToDelete.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, urisToDelete)
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    REQUEST_CODE_DELETE,
                    null,
                    0,
                    0,
                    0
                )
            } catch (e: Exception) {
                e.printStackTrace()
                fallbackDelete(urisToDelete)
            }
        } else {
            fallbackDelete(urisToDelete)
        }
    }

    private fun fallbackDelete(uris: List<Uri>) {
        var deletedCount = 0
        for (uri in uris) {
            try {
                val rows = contentResolver.delete(uri, null, null)
                if (rows > 0) deletedCount++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        Toast.makeText(this, "Eliminati $deletedCount screenshot", Toast.LENGTH_SHORT).show()
        exitSelectionMode()
        setupMatchBar()
        loadScreenshots()
    }

    private fun showFullscreenImage(uri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_fullscreen_image)

        val imageView = dialog.findViewById<ImageView>(R.id.iv_fullscreen)
        val btnClose = dialog.findViewById<View>(R.id.btn_dialog_close)
        val btnShare = dialog.findViewById<View>(R.id.btn_dialog_share)
        val btnDelete = dialog.findViewById<View>(R.id.btn_dialog_delete)

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
            dialog.dismiss()
            selectedUris.clear()
            selectedUris.add(uri)
            confirmAndDeleteSelected()
        }

        dialog.show()
    }

    // ──────────────────────────── Adapter ──────────────────────────────

    inner class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumbnail: ImageView = view.findViewById(R.id.iv_screenshot_thumbnail)
            val overlaySelected: View = view.findViewById(R.id.view_selected_overlay)
            val icCheck: ImageView = view.findViewById(R.id.iv_selected_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_screenshot, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uri = screenshotUris[position]

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val thumbnail: Bitmap = contentResolver.loadThumbnail(uri, Size(300, 300), null)
                    holder.ivThumbnail.setImageBitmap(thumbnail)
                } catch (e: Exception) {
                    holder.ivThumbnail.setImageURI(uri)
                }
            } else {
                holder.ivThumbnail.setImageURI(uri)
            }

            val isSelected = selectedUris.contains(uri)
            holder.overlaySelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.icCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleItemSelection(uri)
                } else {
                    showFullscreenImage(uri)
                }
            }

            holder.itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode()
                    toggleItemSelection(uri)
                }
                true
            }
        }

        override fun getItemCount(): Int = screenshotUris.size
    }
}
