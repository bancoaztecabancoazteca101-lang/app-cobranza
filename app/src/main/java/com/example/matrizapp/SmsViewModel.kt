package com.example.matrizapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

enum class EstadoEnvio { PENDIENTE, ENVIANDO, ENVIADO, FALLIDO }
enum class FuenteSms { TT, REF1, REF2 }

data class ContactoSms(
    val id: String, val nombre: String, val telefono: String, val monto: String, val ubicacion: String?,
    val seleccionado: Boolean = true, val estado: EstadoEnvio = EstadoEnvio.PENDIENTE
)

private const val PLANTILLA_TT_DEFAULT = "Buen día Señor(a) %nombre%.\n\nDe Banco Azteca referente a su línea de crédito que presenta un atraso de \$%monto%.\n\nRecupere los beneficios de pagar PUNTUAL y por APP paga menos.\n\nPor cuál cantidad esperamos su pago el día de hoy.\n\nContamos con más de 50,000 sucursales, pago por app y línea azteca.\nhttps://www.bancoazteca.com.mx/"

private const val PLANTILLA_REF_DEFAULT = "Buen día.\n\nLe saluda %agente% del área de atención de Banco Azteca.\n\nNos estamos tratando de comunicar con el(la) Sr.(a) %nombre% respecto a un asunto relacionado con su cuenta. Su número fue proporcionado como referencia, por lo que agradeceríamos su apoyo para informarle que se comunique a la brevedad con nosotros al %contacto%.\n\nAgradecemos mucho su atención y apoyo.\n\nQue tenga excelente día."

private fun inicioDeDia(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun finDeDia(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

class SmsViewModel(private val matrizDao: MatrizDao, private val workManager: WorkManager) : ViewModel() {

    private val registrosTodos: StateFlow<List<MatrizEntity>> = matrizDao.getAllMatriz()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val hoy = System.currentTimeMillis()
    private val _fechaInicio = MutableStateFlow(inicioDeDia(hoy))
    val fechaInicio: StateFlow<Long> = _fechaInicio
    private val _fechaFin = MutableStateFlow(finDeDia(hoy))
    val fechaFin: StateFlow<Long> = _fechaFin

    private val _fuente = MutableStateFlow(FuenteSms.TT)
    val fuente: StateFlow<FuenteSms> = _fuente

    private val _plantillaTT = MutableStateFlow(PLANTILLA_TT_DEFAULT)
    val plantillaTT: StateFlow<String> = _plantillaTT
    private val _plantillaRef = MutableStateFlow(PLANTILLA_REF_DEFAULT)
    val plantillaRef: StateFlow<String> = _plantillaRef

    private val _agente = MutableStateFlow("")
    val agente: StateFlow<String> = _agente
    private val _contacto = MutableStateFlow("")
    val contactoGestor: StateFlow<String> = _contacto

    private val _subscriptionIdSeleccionado = MutableStateFlow<Int?>(null)
    val subscriptionIdSeleccionado: StateFlow<Int?> = _subscriptionIdSeleccionado

    private val _delaySegundos = MutableStateFlow(5)
    val delaySegundos: StateFlow<Int> = _delaySegundos
    private val _vecesPorDia = MutableStateFlow(1)
    val vecesPorDia: StateFlow<Int> = _vecesPorDia
    private val _horasEntreRepeticion = MutableStateFlow(3)
    val horasEntreRepeticion: StateFlow<Int> = _horasEntreRepeticion

    // Ids marcados manualmente como NO enviar, para no perder la selección al recombinar filtros.
    private val _excluidos = MutableStateFlow<Set<String>>(emptySet())
    private val _estadosEnvio = MutableStateFlow<Map<String, EstadoEnvio>>(emptyMap())

    // Filtro de Colonia: se aplica explícito (no en cada tecleo) porque cada registro nuevo
    // necesita un geocoding inverso local (Android Geocoder), y eso tarda. null = sin filtro.
    private val _coloniaTexto = MutableStateFlow("")
    val coloniaTexto: StateFlow<String> = _coloniaTexto
    private val _coloniaIdsPermitidos = MutableStateFlow<Set<String>?>(null)
    private val _coloniaCargando = MutableStateFlow(false)
    val coloniaCargando: StateFlow<Boolean> = _coloniaCargando
    private val coloniaCache = HashMap<String, String?>()

    // Candidatos según fecha + fuente + selección, ANTES de aplicar el filtro de colonia.
    private val candidatos: StateFlow<List<ContactoSms>> = combine(registrosTodos, _fechaInicio, _fechaFin, _fuente, _excluidos, _estadosEnvio) { valores ->
        @Suppress("UNCHECKED_CAST")
        val registros = valores[0] as List<MatrizEntity>
        val ini = valores[1] as Long
        val fin = valores[2] as Long
        val fte = valores[3] as FuenteSms
        val excluidos = valores[4] as Set<String>
        val estados = valores[5] as Map<String, EstadoEnvio>
        registros
            .filter { r -> r.fecha != null && r.fecha in ini..fin }
            .mapNotNull { r ->
                val telefono = when (fte) {
                    FuenteSms.TT -> r.numTT
                    FuenteSms.REF1 -> r.ref1
                    FuenteSms.REF2 -> r.ref2
                }
                if (telefono.isBlank()) return@mapNotNull null
                ContactoSms(
                    id = r.id, nombre = r.nombre, telefono = telefono, monto = r.requisito, ubicacion = r.ubicacion,
                    seleccionado = r.id !in excluidos, estado = estados[r.id] ?: EstadoEnvio.PENDIENTE
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactos: StateFlow<List<ContactoSms>> = combine(candidatos, _coloniaIdsPermitidos) { base, permitidos ->
        if (permitidos == null) base else base.filter { it.id in permitidos }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _enviando = MutableStateFlow(false)
    val enviando: StateFlow<Boolean> = _enviando
    private val _progreso = MutableStateFlow(0 to 0)
    val progreso: StateFlow<Pair<Int, Int>> = _progreso

    fun setFechaInicio(millis: Long) { _fechaInicio.value = inicioDeDia(millis); if (_fechaFin.value < _fechaInicio.value) _fechaFin.value = finDeDia(millis) }
    fun setFechaFin(millis: Long) { _fechaFin.value = finDeDia(millis); if (_fechaInicio.value > _fechaFin.value) _fechaInicio.value = inicioDeDia(millis) }
    fun setFuente(f: FuenteSms) { _fuente.value = f }
    fun setPlantillaTT(texto: String) { _plantillaTT.value = texto }
    fun setPlantillaRef(texto: String) { _plantillaRef.value = texto }
    fun setAgente(texto: String) { _agente.value = texto }
    fun setContactoGestor(texto: String) { _contacto.value = texto }
    fun setSim(subscriptionId: Int?) { _subscriptionIdSeleccionado.value = subscriptionId }
    fun setDelaySegundos(v: Int) { _delaySegundos.value = v.coerceIn(1, 300) }
    fun setVecesPorDia(v: Int) { _vecesPorDia.value = v.coerceIn(1, 9) }
    fun setHorasEntreRepeticion(v: Int) { _horasEntreRepeticion.value = v.coerceIn(1, 12) }

    fun setColoniaTexto(texto: String) { _coloniaTexto.value = texto }

    /** Geocodifica (una sola vez por ubicación, con caché) los candidatos actuales y deja
     * activo el filtro por Colonia. Se dispara con botón, no en cada tecleo. */
    fun aplicarFiltroColonia(context: Context) {
        val texto = _coloniaTexto.value.trim()
        if (texto.isBlank()) { _coloniaIdsPermitidos.value = null; return }
        viewModelScope.launch {
            _coloniaCargando.value = true
            val base = candidatos.value
            for (c in base) {
                val ub = c.ubicacion ?: continue
                if (!coloniaCache.containsKey(ub)) {
                    coloniaCache[ub] = resolverColonia(context, ub)
                }
            }
            val ids = base.filter { c ->
                val colonia = c.ubicacion?.let { coloniaCache[it] }
                colonia != null && colonia.contains(texto, ignoreCase = true)
            }.map { it.id }.toSet()
            _coloniaIdsPermitidos.value = ids
            _coloniaCargando.value = false
        }
    }

    fun limpiarFiltroColonia() { _coloniaTexto.value = ""; _coloniaIdsPermitidos.value = null }

    fun toggleSeleccionado(id: String) {
        val actuales = _excluidos.value.toMutableSet()
        if (id in actuales) actuales.remove(id) else actuales.add(id)
        _excluidos.value = actuales
    }
    fun seleccionarTodos() { _excluidos.value = emptySet() }
    fun deseleccionarTodos() { _excluidos.value = contactos.value.map { it.id }.toSet() }

    private fun plantillaActual(): String = if (_fuente.value == FuenteSms.TT) _plantillaTT.value else _plantillaRef.value

    fun enviarAhora(context: Context) {
        if (_enviando.value) return
        val lista = contactos.value.filter { it.seleccionado }
        if (lista.isEmpty()) return
        _enviando.value = true
        _progreso.value = 0 to lista.size
        viewModelScope.launch {
            val subId = _subscriptionIdSeleccionado.value
            val plantilla = plantillaActual()
            val agenteActual = _agente.value
            val contactoActual = _contacto.value
            val delayMs = _delaySegundos.value * 1000L
            val estados = _estadosEnvio.value.toMutableMap()
            lista.forEachIndexed { i, contacto ->
                estados[contacto.id] = EstadoEnvio.ENVIANDO
                _estadosEnvio.value = estados.toMap()
                val mensaje = SmsHelper.armarMensaje(plantilla, contacto.nombre, contacto.monto, agenteActual, contactoActual)
                val ok = SmsHelper.enviarSms(context, subId, contacto.telefono, mensaje)
                estados[contacto.id] = if (ok) EstadoEnvio.ENVIADO else EstadoEnvio.FALLIDO
                _estadosEnvio.value = estados.toMap()
                _progreso.value = (i + 1) to lista.size
                if (i < lista.lastIndex) delay(delayMs)
            }
            _enviando.value = false
        }
    }

    fun programarRepeticiones(context: Context) {
        val lista = contactos.value.filter { it.seleccionado }
        if (lista.isEmpty()) return
        SmsRepeatWorker.programar(
            workManager = workManager, fuente = _fuente.value, idsSeleccionados = lista.map { it.id },
            plantilla = plantillaActual(), agente = _agente.value, contacto = _contacto.value,
            subscriptionId = _subscriptionIdSeleccionado.value, delaySegundos = _delaySegundos.value,
            repeticionesRestantes = _vecesPorDia.value, horasEntreRepeticion = _horasEntreRepeticion.value
        )
    }

    fun cancelarRepeticionesProgramadas() { SmsRepeatWorker.cancelar(workManager) }
}
