package it.videodelay.app.ui.cameras

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import it.videodelay.app.R
import it.videodelay.app.data.model.Camera
import it.videodelay.app.databinding.DialogCameraEditBinding

class CameraEditDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "CameraEditDialog"

        fun newInstance(
            camera: Camera?,
            onSave: (name: String, url: String) -> Unit
        ): CameraEditDialog {
            return CameraEditDialog().apply {
                this.existingCamera = camera
                this.onSave = onSave
            }
        }
    }

    private var _binding: DialogCameraEditBinding? = null
    private val binding get() = _binding!!

    private var existingCamera: Camera? = null
    private var onSave: ((String, String) -> Unit)? = null

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startQrScanner()
            } else {
                Toast.makeText(requireContext(), "Permesso fotocamera necessario per scansionare il QR code", Toast.LENGTH_SHORT).show()
            }
        }

    @Suppress("DEPRECATION")
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scannedUrl = result.contents
            binding.etRtspUrl.setText(scannedUrl)

            // Feedback a vibrazione al successo della scansione
            @Suppress("DEPRECATION")
            val vibrator = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    it.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    it.vibrate(150)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogCameraEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isEditing = existingCamera != null
        binding.tvTitle.text = if (isEditing) "Modifica Telecamera" else "Aggiungi Telecamera"

        // Pre-popola i campi in caso di modifica
        existingCamera?.let { cam ->
            binding.etCameraName.setText(cam.name)
            binding.etRtspUrl.setText(cam.rtspUrl)
        }

        // Suggerimenti URL RTSP per i dispositivi comuni
        binding.tvHint.text = "Es.: rtsp://192.168.1.100:554/stream\n" +
                "SJCAM HD: rtsp://192.168.1.254/sjcam.mov o /live1\n" +
                "SJCAM Bassa: rtsp://192.168.1.254/live (480p)\n" +
                "GoPro: rtsp://10.5.5.9/live/amba.m3u8"

        binding.btnSave.text = if (isEditing) "Aggiorna" else "Salva"
        binding.btnSave.setOnClickListener { validateAndSave() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnScanQr.setOnClickListener { checkCameraPermissionAndScan() }
    }

    private fun checkCameraPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startQrScanner()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Inquadra il QR code con l'URL RTSP della videocamera")
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
            setCaptureActivity(CustomScannerActivity::class.java)
        }
        barcodeLauncher.launch(options)
    }

    private fun validateAndSave() {
        val name = binding.etCameraName.text?.toString()?.trim() ?: ""
        val url = binding.etRtspUrl.text?.toString()?.trim() ?: ""

        when {
            name.isEmpty() -> {
                binding.tilCameraName.error = "Inserisci un nome"
                return
            }
            url.isEmpty() -> {
                binding.tilRtspUrl.error = "Inserisci l'URL RTSP"
                return
            }
            !url.startsWith("rtsp://", ignoreCase = true) -> {
                binding.tilRtspUrl.error = "L'URL deve iniziare con rtsp://"
                return
            }
        }

        binding.tilCameraName.error = null
        binding.tilRtspUrl.error = null
        onSave?.invoke(name, url)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
