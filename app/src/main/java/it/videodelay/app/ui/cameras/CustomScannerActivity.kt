package it.videodelay.app.ui.cameras

import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import it.videodelay.app.R

class CustomScannerActivity : CaptureActivity() {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private lateinit var btnTorch: ImageButton
    private var isTorchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_scanner)

        barcodeScannerView = findViewById(R.id.zxing_barcode_scanner)
        btnTorch = findViewById(R.id.btn_scanner_flash)
        val btnClose = findViewById<ImageButton>(R.id.btn_scanner_close)

        capture = CaptureManager(this, barcodeScannerView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.setShowMissingCameraPermissionDialog(false)
        capture.decode()

        btnClose.setOnClickListener {
            finish()
        }

        btnTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            if (isTorchOn) {
                barcodeScannerView.setTorchOn()
                btnTorch.setImageResource(android.R.drawable.btn_star_big_on)
                btnTorch.setColorFilter(android.graphics.Color.YELLOW)
            } else {
                barcodeScannerView.setTorchOff()
                btnTorch.setImageResource(android.R.drawable.btn_star_big_off)
                btnTorch.clearColorFilter()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeScannerView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
}
