package it.videodelay.app.ui.cameras

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import kotlinx.coroutines.*
import it.videodelay.app.R
import it.videodelay.app.data.model.Camera
import it.videodelay.app.databinding.ItemCameraBinding
import it.videodelay.app.util.DemoVideoGenerator
import java.text.SimpleDateFormat
import java.util.*

class CameraListAdapter(
    private val scope: CoroutineScope,
    private val onCameraClick: (Camera) -> Unit,
    private val onEditClick: (Camera) -> Unit,
    private val onDeleteClick: (Camera) -> Unit
) : ListAdapter<Camera, CameraListAdapter.CameraViewHolder>(DiffCallback) {

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Camera>() {
            override fun areItemsTheSame(old: Camera, new: Camera) = old.id == new.id
            override fun areContentsTheSame(old: Camera, new: Camera) = old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CameraViewHolder {
        val binding = ItemCameraBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CameraViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CameraViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: CameraViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelActiveJobs()
    }

    inner class CameraViewHolder(
        private val binding: ItemCameraBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var pingJob: Job? = null

        fun bind(camera: Camera) {
            pingJob?.cancel() // Cancella eventuali ping in corso per il riciclo della vista

            binding.tvCameraName.text = camera.name
            binding.tvCameraUrl.text = camera.rtspUrl
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            binding.tvCameraDate.text = "Aggiunta: ${sdf.format(Date(camera.createdAt))}"

            binding.root.setOnClickListener { onCameraClick(camera) }
            binding.btnConnect.setOnClickListener { onCameraClick(camera) }
            binding.btnEdit.setOnClickListener { onEditClick(camera) }
            binding.btnDelete.setOnClickListener { onDeleteClick(camera) }

            // Controllo PING asincrono per lo stato ONLINE/OFFLINE
            if (camera.rtspUrl.startsWith("demo://")) {
                if (DemoVideoGenerator.isReady.value == true) {
                    binding.badgeStatus.text = "ONLINE"
                    binding.badgeStatus.setBackgroundResource(R.drawable.badge_online_bg)
                    binding.badgeStatus.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    binding.badgeStatus.text = "PREPARAZIONE..."
                    binding.badgeStatus.setBackgroundResource(R.drawable.badge_pinging_bg)
                    binding.badgeStatus.setTextColor(Color.parseColor("#FF9800"))
                }
            } else {
                binding.badgeStatus.text = "PINGING..."
                binding.badgeStatus.setBackgroundResource(R.drawable.badge_pinging_bg)
                binding.badgeStatus.setTextColor(Color.parseColor("#FF9800"))

                pingJob = scope.launch {
                    while (isActive) {
                        val isOnline = withContext(Dispatchers.IO) {
                            try {
                                val uri = java.net.URI(camera.rtspUrl)
                                val host = uri.host ?: return@withContext false
                                val port = if (uri.port > 0) uri.port else 554
                                java.net.Socket().use { socket ->
                                    socket.connect(java.net.InetSocketAddress(host, port), 1500)
                                    true
                                }
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (isActive) {
                            if (isOnline) {
                                binding.badgeStatus.text = "ONLINE"
                                binding.badgeStatus.setBackgroundResource(R.drawable.badge_online_bg)
                                binding.badgeStatus.setTextColor(Color.parseColor("#4CAF50"))
                            } else {
                                binding.badgeStatus.text = "OFFLINE"
                                binding.badgeStatus.setBackgroundResource(R.drawable.badge_offline_bg)
                                binding.badgeStatus.setTextColor(Color.parseColor("#F44336"))
                            }
                        }
                        delay(5000) // Controlla di nuovo dopo 5 secondi
                    }
                }
            }
        }

        fun cancelActiveJobs() {
            pingJob?.cancel()
            pingJob = null
        }
    }
}
