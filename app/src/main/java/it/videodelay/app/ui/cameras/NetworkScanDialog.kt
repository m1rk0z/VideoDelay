package it.videodelay.app.ui.cameras

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import it.videodelay.app.databinding.DialogNetworkScanBinding
import it.videodelay.app.discovery.CameraDiscoveryManager
import it.videodelay.app.discovery.DiscoveredCamera

/**
 * Mostra in tempo reale le telecamere IPCAM trovate sulla rete locale via NSD,
 * permettendo di aggiungerle con un tap invece di scansionare un QR code o
 * digitare manualmente l'URL RTSP.
 */
class NetworkScanDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "NetworkScanDialog"
        private const val EMPTY_HINT_DELAY_MS = 4000L

        fun newInstance(onCameraChosen: (name: String, url: String) -> Unit): NetworkScanDialog {
            return NetworkScanDialog().apply { this.onCameraChosen = onCameraChosen }
        }
    }

    private var _binding: DialogNetworkScanBinding? = null
    private val binding get() = _binding!!

    private var onCameraChosen: ((String, String) -> Unit)? = null
    private lateinit var discoveryManager: CameraDiscoveryManager
    private lateinit var adapter: FoundCameraAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val foundCameras = mutableListOf<DiscoveredCamera>()

    private val emptyHintRunnable = Runnable {
        if (_binding != null && foundCameras.isEmpty()) {
            binding.tvEmptyHint.visibility = View.VISIBLE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogNetworkScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        discoveryManager = CameraDiscoveryManager(requireContext())

        adapter = FoundCameraAdapter { camera ->
            onCameraChosen?.invoke(camera.displayName, camera.rtspUrl)
            dismiss()
        }
        binding.recyclerFound.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFound.adapter = adapter

        binding.btnClose.setOnClickListener { dismiss() }

        mainHandler.postDelayed(emptyHintRunnable, EMPTY_HINT_DELAY_MS)

        discoveryManager.startDiscovery(
            onFound = { camera ->
                mainHandler.post {
                    if (_binding == null) return@post
                    if (foundCameras.none { it.serviceName == camera.serviceName }) {
                        foundCameras.add(camera)
                        adapter.submitList(foundCameras.toList())
                        binding.tvEmptyHint.visibility = View.GONE
                    }
                }
            },
            onLost = { serviceName ->
                mainHandler.post {
                    if (_binding == null) return@post
                    foundCameras.removeAll { it.serviceName == serviceName }
                    adapter.submitList(foundCameras.toList())
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(emptyHintRunnable)
        discoveryManager.stopDiscovery()
        _binding = null
    }
}
