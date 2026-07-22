package it.videodelay.app.ui.main

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import it.videodelay.app.R
import it.videodelay.app.data.model.Camera
import it.videodelay.app.databinding.ActivityMainBinding
import it.videodelay.app.ui.cameras.CameraListFragment
import it.videodelay.app.ui.player.PlayerFragment

import androidx.lifecycle.lifecycleScope
import it.videodelay.app.VideoDelayApp
import it.videodelay.app.util.isEmulator
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val imagesGranted = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        val videoGranted = permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        val readStorageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        val writeStorageGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false

        if (!imagesGranted && !videoGranted && !readStorageGranted && !writeStorageGranted) {
            Toast.makeText(
                this,
                "I permessi di archiviazione sono consigliati per salvare screenshot e clip.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cleanTempCache()
        checkAndRequestPermissions()

        if (savedInstanceState == null) {
            val app = application as VideoDelayApp
            val repo = app.cameraRepository
            lifecycleScope.launch {
                val list = repo.getAll()
                if (list.isEmpty()) {
                    // Alla prima installazione, inseriamo le configurazioni SJCAM e la Demo Camera
                    val sjcam = Camera(name = "Sjcam C400", rtspUrl = "rtsp://192.168.1.254/sjcam.mov")
                    repo.insert(sjcam)
                    
                    val demoCam = Camera(name = "Demo Replay Camera", rtspUrl = "demo://replay")
                    val demoId = repo.insert(demoCam)
                    
                    if (isEmulator()) {
                        openPlayer(Camera(id = demoId, name = demoCam.name, rtspUrl = demoCam.rtspUrl))
                    } else {
                        showCameraList()
                    }
                } else {
                    showCameraList()
                }
            }
        }
    }

    fun showCameraList() {
        // Torna al portrait quando si mostra la rubrica
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, CameraListFragment(), CameraListFragment.TAG)
        }
    }

    fun openPlayer(camera: Camera) {
        // Forzare landscape per il player sportivo
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(
                R.id.fragment_container,
                PlayerFragment.newInstance(camera.id, camera.name, camera.rtspUrl),
                PlayerFragment.TAG
            )
            addToBackStack(PlayerFragment.TAG)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (supportFragmentManager.backStackEntryCount > 0) {
            // Torna alla rubrica e ripristina portrait
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            super.onBackPressed()
        } else {
            super.onBackPressed()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun cleanTempCache() {
        try {
            val cacheDir = cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (
                            file.name.startsWith("concat_temp_") ||
                            file.name.startsWith("highlights_temp_") ||
                            file.name.startsWith("temp_part_") ||
                            file.name.startsWith("temp_screenshot_") ||
                            file.name.startsWith("edited_screenshot_")
                        )) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
