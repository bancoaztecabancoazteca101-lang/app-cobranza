package com.example.matrizapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estado de un contacto durante el envío masivo. */
enum class EstadoEnvio { PENDIENTE, ENVIANDO, ENVIADO, FALLIDO }

data class ContactoSms(val id: String, val nombre: String, val telefono: String, val estado: EstadoEnvio = EstadoEnvio.PENDIENTE)

class SmsViewModel(private val filtroFechaDao: FiltroFechaDao) : ViewModel() {

    // Fuente: los mismos registros de hoy que ya usa "Filtro Fecha" (Matriz filtrado por fecha
    // de hoy) — mismo par Telefono/Nombre que se usaba en el CSV de Auto Text, ya sincronizado
    // local en Room, sin pasar por Sheets ni Drive.
    private val contactosBase: StateFlow<List<ContactoSms>> = filtroFechaDao.getAll()
        .map { registros ->
            registros
                .filter { it.numTT.isNotBlank() }
                .map { ContactoSms(id = it.id, nombre = it.nombre, telefono = it.numTT) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _contactosEnvio = MutableStateFlow<List<ContactoSms>>(emptyList())
    val contactos: StateFlow<List<ContactoSms>> = _contactosEnvio

    private val _plantilla = MutableStateFlow("Hola %nombre%, te recordamos tu visita programada de hoy con Banco Azteca.")
    val plantilla: StateFlow<String> = _plantilla

    private val _subscriptionIdSeleccionado = MutableStateFlow<Int?>(null)
    val subscriptionIdSeleccionado: StateFlow<Int?> = _subscriptionIdSeleccionado

    private val _enviando = MutableStateFlow(false)
    val enviando: StateFlow<Boolean> = _enviando

    private val _progreso = MutableStateFlow(0 to 0) // enviados a total
    val progreso: StateFlow<Pair<Int, Int>> = _progreso

    init {
        viewModelScope.launch {
            contactosBase.collect { base ->
                // Solo reemplaza la lista de trabajo si no hay un envío en curso, para no
                // pisar el progreso de un envío ya iniciado.
                if (!_enviando.value) _contactosEnvio.value = base
            }
        }
    }

    fun setPlantilla(texto: String) { _plantilla.value = texto }
    fun setSim(subscriptionId: Int?) { _subscriptionIdSeleccionado.value = subscriptionId }

    fun enviarATodos(context: Context) {
        if (_enviando.value) return
        val lista = _contactosEnvio.value
        if (lista.isEmpty()) return
        _enviando.value = true
        _progreso.value = 0 to lista.size
        viewModelScope.launch {
            val subId = _subscriptionIdSeleccionado.value
            val plantillaActual = _plantilla.value
            val actualizados = lista.toMutableList()
            for (i in actualizados.indices) {
                val contacto = actualizados[i]
                actualizados[i] = contacto.copy(estado = EstadoEnvio.ENVIANDO)
                _contactosEnvio.value = actualizados.toList()
                val mensaje = SmsHelper.armarMensaje(plantillaActual, contacto.nombre)
                val ok = SmsHelper.enviarSms(context, subId, contacto.telefono, mensaje)
                actualizados[i] = contacto.copy(estado = if (ok) EstadoEnvio.ENVIADO else EstadoEnvio.FALLIDO)
                _contactosEnvio.value = actualizados.toList()
                _progreso.value = (i + 1) to lista.size
                delay(2500) // espacia los envíos para no saturar la SIM ni parecer spam
            }
            _enviando.value = false
        }
    }
}
