package it.videodelay.app.ui.cameras

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import it.videodelay.app.databinding.ItemDiscoveredCameraBinding
import it.videodelay.app.discovery.DiscoveredCamera

class FoundCameraAdapter(
    private val onCameraClick: (DiscoveredCamera) -> Unit
) : ListAdapter<DiscoveredCamera, FoundCameraAdapter.ViewHolder>(DiffCallback) {

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<DiscoveredCamera>() {
            override fun areItemsTheSame(old: DiscoveredCamera, new: DiscoveredCamera) =
                old.serviceName == new.serviceName

            override fun areContentsTheSame(old: DiscoveredCamera, new: DiscoveredCamera) =
                old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDiscoveredCameraBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDiscoveredCameraBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(camera: DiscoveredCamera) {
            binding.tvDiscoveredName.text = camera.displayName
            binding.tvDiscoveredHost.text = "${camera.host}:${camera.port}"
            binding.root.setOnClickListener { onCameraClick(camera) }
        }
    }
}
