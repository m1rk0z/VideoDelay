package it.videodelay.app.ui.cameras

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import it.videodelay.app.ui.player.ScreenshotGalleryActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import it.videodelay.app.R
import it.videodelay.app.VideoDelayApp
import it.videodelay.app.data.model.Camera
import it.videodelay.app.databinding.FragmentCameraListBinding
import it.videodelay.app.ui.main.MainActivity
import it.videodelay.app.util.DemoVideoGenerator
import it.videodelay.app.util.JsonImportExport
import java.io.File

class CameraListFragment : Fragment() {

    companion object {
        const val TAG = "CameraListFragment"
    }

    private var _binding: FragmentCameraListBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: CameraListViewModel
    private lateinit var adapter: CameraListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo = (requireActivity().application as VideoDelayApp).cameraRepository
        viewModel = ViewModelProvider(
            this,
            CameraListViewModelFactory(repo)
        )[CameraListViewModel::class.java]

        setupRecyclerView()
        setupFab()
        setupToolbar()
        observeViewModel()

        // Genera il video demo già in questa schermata, così è pronto (o quasi) quando
        // l'utente entra nella Demo Camera invece di trovarla nera/ferma in fase di generazione.
        DemoVideoGenerator.ensureGenerated(requireContext().applicationContext)
        DemoVideoGenerator.isReady.observe(viewLifecycleOwner) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun setupRecyclerView() {
        adapter = CameraListAdapter(
            viewLifecycleOwner.lifecycleScope,
            onCameraClick = { camera ->
                if (camera.rtspUrl.startsWith("demo://") && DemoVideoGenerator.isReady.value != true) {
                    Toast.makeText(
                        requireContext(),
                        "Video demo in preparazione, attendi qualche secondo...",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    (requireActivity() as MainActivity).openPlayer(camera)
                }
            },
            onEditClick = { camera ->
                showEditDialog(camera)
            },
            onDeleteClick = { camera ->
                confirmDelete(camera)
            }
        )
        binding.recyclerCameras.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCameras.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddCamera.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.menu_add_camera, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_scan_network -> { showNetworkScanDialog(); true }
                    R.id.action_add_manual -> { showAddDialog(); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = "VideoDelay"
        binding.toolbar.inflateMenu(R.menu.menu_camera_list)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> { exportCameras(); true }
                R.id.action_import -> { importCameras(); true }
                R.id.action_clear_all -> { confirmClearAll(); true }
                R.id.action_screenshots -> { openScreenshotsGallery(); true }
                else -> false
            }
        }
    }

    private fun openScreenshotsGallery() {
        val intent = android.content.Intent(requireContext(), ScreenshotGalleryActivity::class.java)
        startActivity(intent)
    }

    private fun observeViewModel() {
        viewModel.cameras.observe(viewLifecycleOwner) { cameras ->
            adapter.submitList(cameras)
            binding.tvEmpty.visibility =
                if (cameras.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerCameras.visibility =
                if (cameras.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ──────────────────────────── Dialogs ──────────────────────────

    private fun showAddDialog() {
        CameraEditDialog.newInstance(null) { name, url ->
            viewModel.addCamera(Camera(name = name, rtspUrl = url))
        }.show(childFragmentManager, CameraEditDialog.TAG)
    }

    private fun showNetworkScanDialog() {
        NetworkScanDialog.newInstance { name, url ->
            viewModel.addCamera(Camera(name = name, rtspUrl = url))
            Toast.makeText(requireContext(), "Telecamera \"$name\" aggiunta", Toast.LENGTH_SHORT).show()
        }.show(childFragmentManager, NetworkScanDialog.TAG)
    }

    private fun showEditDialog(camera: Camera) {
        CameraEditDialog.newInstance(camera) { name, url ->
            viewModel.updateCamera(camera.copy(name = name, rtspUrl = url))
        }.show(childFragmentManager, CameraEditDialog.TAG)
    }

    private fun confirmDelete(camera: Camera) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Elimina telecamera")
            .setMessage("Eliminare \"${camera.name}\"?")
            .setPositiveButton("Elimina") { _, _ ->
                viewModel.deleteCamera(camera)
                Snackbar.make(binding.root, "\"${camera.name}\" eliminata", Snackbar.LENGTH_LONG)
                    .setAction("Annulla") { viewModel.addCamera(camera) }
                    .show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Svuota rubrica")
            .setMessage("Sei sicuro di voler eliminare tutte le videocamere? Questa operazione non può essere annullata.")
            .setPositiveButton("Elimina tutto") { _, _ ->
                viewModel.deleteAllCameras()
                Toast.makeText(requireContext(), "Rubrica svuotata", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ──────────────────────────── Import/Export ─────────────────────

    private fun exportCameras() {
        val cameras = viewModel.cameras.value ?: emptyList()
        if (cameras.isEmpty()) {
            Toast.makeText(requireContext(), "Nessuna telecamera da esportare", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(requireContext().cacheDir, "videodelay_cameras.json")
        JsonImportExport.exportToFile(cameras, file)

        // Condividi il file JSON
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.provider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Esporta telecamere"))
    }

    @Suppress("DEPRECATION")
    private fun importCameras() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(android.content.Intent.createChooser(intent, "Importa telecamere"), 200)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val json = requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText() ?: return
                    val cameras = JsonImportExport.importFromJson(json)
                    cameras.forEach { viewModel.addCamera(it) }
                    Toast.makeText(
                        requireContext(),
                        "${cameras.size} telecamere importate",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Errore importazione: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
