package it.videodelay.app.ui.player

import android.app.AlertDialog
import android.content.ContentUris
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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.videodelay.app.R
import it.videodelay.app.util.MatchManager
import it.videodelay.app.util.sanitizedForFilename

/** Voce della galleria: intestazione di sezione (per tipo di attacco) o singola clip. */
sealed class GalleryItem {
    data class Header(val title: String, val colorRes: Int) : GalleryItem()
    data class Clip(val uri: Uri) : GalleryItem()
}

class ClipGalleryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CAMERA_NAME = "camera_name"
        private const val REQUEST_CODE_DELETE = 1001
        private const val REQUEST_CODE_DELETE_MATCH = 1002
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_CLIP = 1
        private const val GRID_SPAN_COUNT = 3

        // Cartella "Movies/VideoDelay/[Match]/Marks/<CODICE>/..." usata dal popup MARK.
        private val MARK_FOLDER_REGEX = Regex("Movies/VideoDelay/(?:[^/]+/)?Marks/([^/]+)/?")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DELETE || requestCode == REQUEST_CODE_DELETE_MATCH) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Operazione completata con successo", Toast.LENGTH_SHORT).show()
            }
            exitSelectionMode()
            setupMatchBar()
            loadClips()
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: ClipAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private val items = ArrayList<GalleryItem>()
    private var cameraName: String = ""

    // Elementi Match / Partite
    private lateinit var spinnerMatchFolders: Spinner
    private lateinit var btnNewMatch: Button
    private lateinit var btnDeleteMatch: ImageButton
    private var selectedMatchFolder: String = "Tutte le partite"
    private var matchFoldersList = ArrayList<String>()

    private var isSelectionMode = false
    private val selectedUris = HashSet<Uri>()

    private lateinit var layoutSelectionActions: View
    private lateinit var btnSelectAll: Button
    private lateinit var btnShareSelected: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnCancelSelection: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clip_gallery)

        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME) ?: ""

        val toolbar = findViewById<Toolbar>(R.id.toolbar_clip_gallery)
        toolbar.title = if (cameraName.isNotEmpty()) "Clip · $cameraName" else "Galleria Clip Video"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (isSelectionMode) exitSelectionMode() else finish()
        }

        recyclerView = findViewById(R.id.recycler_clips)
        tvEmpty = findViewById(R.id.tv_clip_gallery_empty)

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

        adapter = ClipAdapter()
        gridLayoutManager = GridLayoutManager(this, GRID_SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.getItemViewType(position) == VIEW_TYPE_HEADER) GRID_SPAN_COUNT else 1
            }
        }
        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter

        loadClips()
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

        val active = MatchManager.getActiveMatch(this)
        val activeIndex = matchFoldersList.indexOf(active).coerceAtLeast(0)
        spinnerMatchFolders.setSelection(activeIndex)
        selectedMatchFolder = matchFoldersList[activeIndex]

        spinnerMatchFolders.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedMatchFolder = matchFoldersList[position]
                if (selectedMatchFolder != "Tutte le partite") {
                    MatchManager.setActiveMatch(this@ClipGalleryActivity, selectedMatchFolder)
                }
                loadClips()
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
            .setMessage("Inserisci il nome per la nuova cartella partita. Tutte le nuove clip e mark verranno salvati qui.")
            .setView(input)
            .setPositiveButton("Crea e Attiva") { _, _ ->
                val name = input.text.toString()
                val created = MatchManager.createNewMatch(this, name)
                Toast.makeText(this, "Nuova partita creata: $created", Toast.LENGTH_SHORT).show()
                setupMatchBar()
                loadClips()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmDeleteMatchFolder() {
        if (selectedMatchFolder == "Tutte le partite") {
            Toast.makeText(this, "Seleziona una specifica cartella partita da eliminare", Toast.LENGTH_SHORT).show()
            return
        }
        val allClips = allClipUris()
        if (allClips.isEmpty()) {
            Toast.makeText(this, "Nessuna clip da eliminare in $selectedMatchFolder", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("🗑️ Elimina Partita")
            .setMessage("Vuoi eliminare definitivamente tutte le clip della cartella \"$selectedMatchFolder\" (${allClips.size} video)?")
            .setPositiveButton("Elimina Tutto") { _, _ ->
                selectedUris.clear()
                selectedUris.addAll(allClips)
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
            selectedUris.addAll(allClipUris())
            updateSelectionTitle()
            adapter.notifyDataSetChanged()
        }

        btnShareSelected.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Nessun elemento selezionato", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareSelectedClips()
        }

        btnDeleteSelected.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Nessun elemento selezionato", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmAndDeleteSelected()
        }

        btnCancelSelection.setOnClickListener { exitSelectionMode() }
    }

    private fun allClipUris(): List<Uri> = items.filterIsInstance<GalleryItem.Clip>().map { it.uri }

    private fun loadClips() {
        data class Entry(val uri: Uri, val code: String?, val dateAdded: Long)

        val entries = ArrayList<Entry>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED
        )

        val pathPattern = if (selectedMatchFolder == "Tutte le partite") {
            "Movies/VideoDelay%"
        } else {
            "Movies/VideoDelay/$selectedMatchFolder%"
        }

        val hasCameraFilter = cameraName.isNotBlank()
        val selection = if (hasCameraFilter) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        }

        val selectionArgs = if (hasCameraFilter) {
            arrayOf(pathPattern, "%${cameraName.sanitizedForFilename()}%")
        } else {
            arrayOf(pathPattern)
        }

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val relativePath = cursor.getString(pathColumn) ?: ""
                    val code = MARK_FOLDER_REGEX.find(relativePath)?.groupValues?.get(1)
                    entries.add(
                        Entry(
                            uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                            code = code,
                            dateAdded = cursor.getLong(dateColumn)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val byCode = entries.groupBy { it.code }
        items.clear()

        AttackTypes.ALL.forEach { attackType ->
            val clips = byCode[attackType.code]?.sortedByDescending { it.dateAdded } ?: return@forEach
            if (clips.isEmpty()) return@forEach
            items.add(GalleryItem.Header("${attackType.code} · ${attackType.label}", attackType.zone.colorRes()))
            clips.forEach { items.add(GalleryItem.Clip(it.uri)) }
        }
        val others = byCode[null]?.sortedByDescending { it.dateAdded } ?: emptyList()
        if (others.isNotEmpty()) {
            items.add(GalleryItem.Header("Altre clip", R.color.text_secondary))
            others.forEach { items.add(GalleryItem.Clip(it.uri)) }
        }

        if (allClipUris().isEmpty()) {
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
        findViewById<Toolbar>(R.id.toolbar_clip_gallery).title = if (cameraName.isNotEmpty()) "Clip · $cameraName" else "Galleria Clip Video"
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun toggleSelection(uri: Uri) {
        if (selectedUris.contains(uri)) selectedUris.remove(uri) else selectedUris.add(uri)
        updateSelectionTitle()
        adapter.notifyDataSetChanged()
        if (selectedUris.isEmpty()) exitSelectionMode()
    }

    private fun updateSelectionTitle() {
        findViewById<Toolbar>(R.id.toolbar_clip_gallery).title = "${selectedUris.size} Selezionati"
    }

    private fun shareSelectedClips() {
        try {
            val urisList = ArrayList(selectedUris)
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, urisList)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi ${selectedUris.size} clip"))
        } catch (e: Exception) {
            Toast.makeText(this, "Impossibile condividere le clip: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmAndDeleteSelected() {
        val count = selectedUris.size
        AlertDialog.Builder(this)
            .setTitle("Elimina clip")
            .setMessage("Sei sicuro di voler eliminare definitivamente $count clip?")
            .setPositiveButton("Elimina") { _, _ -> performBatchDelete() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun performBatchDelete() {
        val urisToDelete = selectedUris.toList()
        if (urisToDelete.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, urisToDelete)
                startIntentSenderForResult(pendingIntent.intentSender, REQUEST_CODE_DELETE, null, 0, 0, 0)
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
        Toast.makeText(this, "Eliminate $deletedCount clip", Toast.LENGTH_SHORT).show()
        exitSelectionMode()
        setupMatchBar()
        loadClips()
    }

    private fun openClipPlayer(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossibile riprodurre la clip: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────── Adapter ──────────────────────────────

    inner class ClipAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_section_title)
        }

        inner class ClipViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumbnail: ImageView = view.findViewById(R.id.iv_clip_thumbnail)
            val overlaySelected: View = view.findViewById(R.id.view_selected_overlay)
            val icCheck: ImageView = view.findViewById(R.id.iv_selected_check)
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is GalleryItem.Header) VIEW_TYPE_HEADER else VIEW_TYPE_CLIP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.item_clip_section_header, parent, false))
            } else {
                ClipViewHolder(inflater.inflate(R.layout.item_clip, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is GalleryItem.Header -> {
                    val h = holder as HeaderViewHolder
                    h.tvTitle.text = item.title
                    h.tvTitle.setTextColor(ContextCompat.getColor(this@ClipGalleryActivity, item.colorRes))
                }
                is GalleryItem.Clip -> {
                    val h = holder as ClipViewHolder
                    val uri = item.uri
                    val isSelected = selectedUris.contains(uri)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val thumbnail = contentResolver.loadThumbnail(uri, Size(300, 300), null)
                            h.ivThumbnail.setImageBitmap(thumbnail)
                        } catch (e: Exception) {
                            h.ivThumbnail.setImageResource(R.drawable.ic_videocam)
                        }
                    } else {
                        h.ivThumbnail.setImageResource(R.drawable.ic_videocam)
                    }

                    h.overlaySelected.visibility = if (isSelected) View.VISIBLE else View.GONE
                    h.icCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                    h.itemView.setOnClickListener {
                        if (isSelectionMode) toggleSelection(uri) else openClipPlayer(uri)
                    }
                    h.itemView.setOnLongClickListener {
                        if (!isSelectionMode) {
                            enterSelectionMode()
                            toggleSelection(uri)
                        }
                        true
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
