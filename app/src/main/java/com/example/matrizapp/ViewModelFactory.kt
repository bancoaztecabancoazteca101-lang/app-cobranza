package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MatrizViewModel::class.java) ->
                MatrizViewModel(container.repository, container.database.matrizDao(), container.workManager, container.driveHelper, container.notificacionesHelper) as T

            modelClass.isAssignableFrom(PaseCarteraViewModel::class.java) ->
                PaseCarteraViewModel(container.repository, container.database.paseDao(), container.workManager) as T

            modelClass.isAssignableFrom(SolicitudViewModel::class.java) ->
                SolicitudViewModel(container.repository, container.database.solicitudDao(), container.audioHelper, container.workManager, container.driveHelper) as T

            modelClass.isAssignableFrom(FiltroFechaViewModel::class.java) ->
                FiltroFechaViewModel(container.repository, container.database.filtroDao(), container.driveHelper, container.notificacionesHelper) as T

            modelClass.isAssignableFrom(FiltrarViewModel::class.java) ->
                FiltrarViewModel(container.database.filtrarDao()) as T

            modelClass.isAssignableFrom(ControlViewModel::class.java) ->
                ControlViewModel(container.database.controlDao()) as T

            modelClass.isAssignableFrom(Sem6ViewModel::class.java) ->
                Sem6ViewModel(container.repository, container.sem6CacheStore, container.driveHelper) as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}