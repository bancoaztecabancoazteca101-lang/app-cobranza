package com.example.matrizapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlantillaSmsViewModel(private val dao: PlantillaSmsDao) : ViewModel() {

    val plantillas: StateFlow<List<PlantillaSmsEntity>> = dao.observarTodas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun guardarTexto(id: Long, texto: String) = viewModelScope.launch {
        dao.actualizarTexto(id, texto)
    }
}
