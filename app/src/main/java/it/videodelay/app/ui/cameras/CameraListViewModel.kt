package it.videodelay.app.ui.cameras

import androidx.lifecycle.*
import it.videodelay.app.data.model.Camera
import it.videodelay.app.data.repository.CameraRepository
import kotlinx.coroutines.launch

class CameraListViewModel(private val repository: CameraRepository) : ViewModel() {

    val cameras: LiveData<List<Camera>> = repository.allCameras.asLiveData()

    fun addCamera(camera: Camera) = viewModelScope.launch {
        repository.insert(camera)
    }

    fun updateCamera(camera: Camera) = viewModelScope.launch {
        repository.update(camera)
    }

    fun deleteCamera(camera: Camera) = viewModelScope.launch {
        repository.delete(camera)
    }

    fun deleteAllCameras() = viewModelScope.launch {
        repository.deleteAll()
    }
}

class CameraListViewModelFactory(
    private val repository: CameraRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CameraListViewModel(repository) as T
    }
}
