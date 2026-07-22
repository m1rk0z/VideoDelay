package it.videodelay.app.ui.player

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
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.videodelay.app.R
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
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_CLIP = 1
        private const val GRID_SPAN_COUNT = 3

        // Cartella "Movies/VideoDelay/Marks/<CODICE>/..." usata dal popup MARK.
        private val MARK_FOLDER_REGEX = Regex("Movies/VideoDelay/Marks/([^/]+)/?")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DELETE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Operazione completata con successo", Toast.LENGTH_SHORT).show()
            }
            exitSelectionMode()
            loadClips()
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: ClipAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private val items = ArrayList<GalleryItem>()
    private var cameraName: String = ""

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
        toolbar.title = "Clip · $cameraName"
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

        setupSelectionListeners()

        adapter = ClipAdapter()
        gridLayoutManager = GridLayoutManager(this, GRID_SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (items.getOrNull(position) is GalleryItem.Header) GRID_SPAN_COUNT else 1
            }
        }
        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter

        loadClips()
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

    /**
     * Legge camera + cartella (RELATIVE_PATH) di ogni clip e le raggruppa per tipo di attacco,
     * nello stesso ordine di zona/codice mostrato nel popup MARK. Le clip salvate col pulsante
     * "CLIP" generico (non da un MARK) finiscono in una sezione "Altre clip" in fondo.
     */
    private fun loadClips() {
        data class Entry(val uri: Uri, val code: String?, val dateAdded: Long)

        val entries = ArrayList<Entry>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("Movies/VideoDelay%", "%${cameraName.sanitizedForFilename()}%")

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
        findViewById<Toolbar>(R.id.toolbar_clip_gallery).title = "Clip · $cameraName"
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
                type = "video/mp4"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, urisList)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi ${urisList.size} clip"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore durante la condivisione: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmAndDeleteSelected() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elimina selezionati")
            .setMessage("Sei sicuro di voler eliminare definitivamente le ${selectedUris.size} clip selezionate?")
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
                    Toast.makeText(this, "$deleteCount clip eliminate", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadClips()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun playClip(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nessuna app disponibile per riprodurre il video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (isSelectionMode) exitSelectionMode() else super.onBackPressed()
    }

    inner class ClipAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                is GalleryItem.Header -> (holder as HeaderViewHolder).bind(item)
                is GalleryItem.Clip -> (holder as ClipViewHolder).bind(item.uri, selectedUris.contains(item.uri))
            }
        }

        override fun getItemCount(): Int = items.size

        inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tv_section_title)
            private val viewColor: View = itemView.findViewById(R.id.view_section_color)

            fun bind(header: GalleryItem.Header) {
                tvTitle.text = header.title
                viewColor.setBackgroundColor(ContextCompat.getColor(itemView.context, header.colorRes))
            }
        }

        inner class ClipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ImageView = itemView.findViewById(R.id.iv_clip_thumbnail)
            private val selectedOverlay: View = itemView.findViewById(R.id.view_selected_overlay)
            private val selectedCheck: ImageView = itemView.findViewById(R.id.iv_selected_check)

            fun bind(uri: Uri, isSelected: Boolean) {
                selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                selectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                try {
                    val thumbnail: Bitmap = contentResolver.loadThumbnail(uri, Size(250, 250), null)
                    imageView.setImageBitmap(thumbnail)
                } catch (e: Exception) {
                    imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }

                itemView.setOnClickListener {
                    if (isSelectionMode) toggleSelection(uri) else playClip(uri)
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
