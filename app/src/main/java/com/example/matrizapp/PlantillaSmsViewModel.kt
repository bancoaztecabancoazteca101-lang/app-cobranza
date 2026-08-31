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
        dao.actualizarTexto(id, texto.trim())
    }

    /** Vuelve las 6 variantes de un tipo+semana a su texto original de fábrica. */
    fun restaurarSemana(tipo: String, semana: Int) = viewModelScope.launch {
        for (slot in 1..6) {
            val textoDefault = PlantillasSemillaSms.textoDeFabrica(tipo, semana, slot) ?: continue
            val id = dao.idDe(tipo, semana, slot) ?: continue
            dao.actualizarTexto(id, textoDefault)
        }
    }
}
