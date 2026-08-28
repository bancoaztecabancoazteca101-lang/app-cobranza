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

enum class EstadoLlamada { PENDIENTE, LLAMANDO, HECHA }
enum class TipoLlamada { TT, REF1, REF2 }

data class LlamadaItem(
    val id: String, val contactoId: String, val nombre: String, val tipo: TipoLlamada, val telefono: String,
    val monto: String, val ubicacion: String?, val seleccionado: Boolean = true, val estado: EstadoLlamada = EstadoLlamada.PENDIENTE
)

private fun inicioDeDiaC(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun finDeDiaC(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

/** Próxima ocurrencia de la hora:minuto indicada — hoy si aún no pasa, mañana si ya pasó. */
private fun proximaOcurrencia(hora: Int, minuto: Int): Long {
    val ahora = Calendar.getInstance()
    val objetivo = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (objetivo.timeInMillis <= ahora.timeInMillis) objetivo.add(Calendar.DAY_OF_YEAR, 1)
    return objetivo.timeInMillis
}

private const val PLANTILLA_SMS_TT_DEFAULT = "Buen día Señor(a) %nombre%.\n\nDe Banco Azteca referente a su línea de crédito que presenta un atraso de \$%monto%.\n\nRecupere los beneficios de pagar PUNTUAL y por APP paga menos.\n\nPor cuál cantidad esperamos su pago el día de hoy.\n\nContamos con más de 50,000 sucursales, pago por app y línea azteca.\nhttps://www.bancoazteca.com.mx/"

private const val PLANTILLA_SMS_REF_DEFAULT = "Buen día.\n\nLe saluda %agente% del área de atención de Banco Azteca.\n\nNos estamos tratando de comunicar con el(la) Sr.(a) %nombre% respecto a un asunto relacionado con su cuenta. Su número fue proporcionado como referencia, por lo que agradeceríamos su apoyo para informarle que se comunique a la brevedad con nosotros al %contacto%.\n\nAgradecemos mucho su atención y apoyo.\n\nQue tenga excelente día."

class CallViewModel(private val matrizDao: MatrizDao, private val workManager: WorkManager) : ViewModel() {

    private val registrosTodos: StateFlow<List<MatrizEntity>> = matrizDao.getAllMatriz()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val hoy = System.currentTimeMillis()
    private val _fechaInicio = MutableStateFlow(inicioDeDiaC(hoy))
    val fechaInicio: StateFlow<Long> = _fechaInicio
    private val _fechaFin = MutableStateFlow(finDeDiaC(hoy))
    val fechaFin: StateFlow<Long> = _fechaFin

    private val _incluirTT = MutableStateFlow(true)
    val incluirTT: StateFlow<Boolean> = _incluirTT
    private val _incluirRef1 = MutableStateFlow(true)
    val incluirRef1: StateFlow<Boolean> = _incluirRef1
    private val _incluirRef2 = MutableStateFlow(true)
    val incluirRef2: StateFlow<Boolean> = _incluirRef2

    private val _subscriptionIdSeleccionado = MutableStateFlow<Int?>(null)
    val subscriptionIdSeleccionado: StateFlow<Int?> = _subscriptionIdSeleccionado

    private val _segundosEntreLlamadas = MutableStateFlow(5)
    val segundosEntreLlamadas: StateFlow<Int> = _segundosEntreLlamadas
    private val _horaInicioBloque = MutableStateFlow(9)
    val horaInicioBloque: StateFlow<Int> = _horaInicioBloque
    private val _minutoInicioBloque = MutableStateFlow(0)
    val minutoInicioBloque: StateFlow<Int> = _minutoInicioBloque
    private val _horasEntreBloques = MutableStateFlow(1)
    val horasEntreBloques: StateFlow<Int> = _horasEntreBloques
    private val _repeticionesBloque = MutableStateFlow(9)
    val repeticionesBloque: StateFlow<Int> = _repeticionesBloque

    // Flujo tipo Tasker: al colgar cada llamada, mandar un SMS a ese mismo número.
    private val _enviarSmsAlColgar = MutableStateFlow(false)
    val enviarSmsAlColgar: StateFlow<Boolean> = _enviarSmsAlColgar
    private val _plantillaSmsTT = MutableStateFlow(PLANTILLA_SMS_TT_DEFAULT)
    val plantillaSmsTT: StateFlow<String> = _plantillaSmsTT
    private val _plantillaSmsRef = MutableStateFlow(PLANTILLA_SMS_REF_DEFAULT)
    val plantillaSmsRef: StateFlow<String> = _plantillaSmsRef
    private val _agenteSms = MutableStateFlow("")
    val agenteSms: StateFlow<String> = _agenteSms
    private val _contactoSms = MutableStateFlow("")
    val contactoSms: StateFlow<String> = _contactoSms

    private val _excluidos = MutableStateFlow<Set<String>>(emptySet())
    private val _estados = MutableStateFlow<Map<String, EstadoLlamada>>(emptyMap())

    private val _coloniaTexto = MutableStateFlow("")
    val coloniaTexto: StateFlow<String> = _coloniaTexto
    private val _coloniaIdsPermitidos = MutableStateFlow<Set<String>?>(null)
    private val _coloniaCargando = MutableStateFlow(false)
    val coloniaCargando: StateFlow<Boolean> = _coloniaCargando
    private val _coloniasDisponibles = MutableStateFlow<List<String>>(emptyList())
    val coloniasDisponibles: StateFlow<List<String>> = _coloniasDisponibles
    private val _coloniasCargando = MutableStateFlow(false)
    val coloniasCargando: StateFlow<Boolean> = _coloniasCargando
    private val coloniaCache = HashMap<String, String?>()

    // Cola de llamadas: por cada registro en el rango de fecha, hasta 3 items (TT, Ref1, Ref2)
    // en ese orden, solo los que tengan teléfono y estén incluidos.
    private val candidatos: StateFlow<List<LlamadaItem>> = combine(
        registrosTodos, _fechaInicio, _fechaFin, _incluirTT, _incluirRef1, _incluirRef2, _excluidos, _estados
    ) { valores ->
        @Suppress("UNCHECKED_CAST") val registros = valores[0] as List<MatrizEntity>
        val ini = valores[1] as Long
        val fin = valores[2] as Long
        val incTT = valores[3] as Boolean
        val incR1 = valores[4] as Boolean
        val incR2 = valores[5] as Boolean
        val excluidos = valores[6] as Set<String>
        @Suppress("UNCHECKED_CAST") val estados = valores[7] as Map<String, EstadoLlamada>

        val lista = mutableListOf<LlamadaItem>()
        registros.filter { it.fecha in ini..fin }.forEach { r ->
            if (incTT && r.numTT.isNotBlank()) {
                val id = "${r.id}_TT"
                lista += LlamadaItem(id, r.id, r.nombre, TipoLlamada.TT, r.numTT, r.requisito, r.ubicacion, id !in excluidos, estados[id] ?: EstadoLlamada.PENDIENTE)
            }
            if (incR1 && !r.ref1.isNullOrBlank()) {
                val id = "${r.id}_REF1"
                lista += LlamadaItem(id, r.id, r.nombre, TipoLlamada.REF1, r.ref1, r.requisito, r.ubicacion, id !in excluidos, estados[id] ?: EstadoLlamada.PENDIENTE)
            }
            if (incR2 && !r.ref2.isNullOrBlank()) {
                val id = "${r.id}_REF2"
                lista += LlamadaItem(id, r.id, r.nombre, TipoLlamada.REF2, r.ref2, r.requisito, r.ubicacion, id !in excluidos, estados[id] ?: EstadoLlamada.PENDIENTE)
            }
        }
        lista
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cola: StateFlow<List<LlamadaItem>> = combine(candidatos, _coloniaIdsPermitidos) { base, permitidos ->
        if (permitidos == null) base else base.filter { it.contactoId in permitidos }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _llamando = MutableStateFlow(false)
    val llamando: StateFlow<Boolean> = _llamando
    private val _progreso = MutableStateFlow(0 to 0)
    val progreso: StateFlow<Pair<Int, Int>> = _progreso
    private val _itemActual = MutableStateFlow<LlamadaItem?>(null)
    val itemActual: StateFlow<LlamadaItem?> = _itemActual

    fun setFechaInicio(millis: Long) { _fechaInicio.value = inicioDeDiaC(millis); if (_fechaFin.value < _fechaInicio.value) _fechaFin.value = finDeDiaC(millis) }
    fun setFechaFin(millis: Long) { _fechaFin.value = finDeDiaC(millis); if (_fechaInicio.value > _fechaFin.value) _fechaInicio.value = inicioDeDiaC(millis) }
    fun setIncluirTT(v: Boolean) { _incluirTT.value = v }
    fun setIncluirRef1(v: Boolean) { _incluirRef1.value = v }
    fun setIncluirRef2(v: Boolean) { _incluirRef2.value = v }
    fun setSim(subscriptionId: Int?) { _subscriptionIdSeleccionado.value = subscriptionId }
    fun setSegundosEntreLlamadas(v: Int) { _segundosEntreLlamadas.value = v.coerceIn(1, 300) }
    fun setHoraInicioBloque(h: Int, m: Int) { _horaInicioBloque.value = h.coerceIn(0, 23); _minutoInicioBloque.value = m.coerceIn(0, 59) }
    fun setHorasEntreBloques(v: Int) { _horasEntreBloques.value = v.coerceIn(1, 12) }
    fun setRepeticionesBloque(v: Int) { _repeticionesBloque.value = v.coerceIn(1, 20) }

    fun setEnviarSmsAlColgar(v: Boolean) { _enviarSmsAlColgar.value = v }
    fun setPlantillaSmsTT(texto: String) { _plantillaSmsTT.value = texto }
    fun setPlantillaSmsRef(texto: String) { _plantillaSmsRef.value = texto }
    fun setAgenteSms(texto: String) { _agenteSms.value = texto }
    fun setContactoSms(texto: String) { _contactoSms.value = texto }

    private fun enviarSmsPostLlamada(context: Context, item: LlamadaItem, subId: Int?) {
        val plantilla = if (item.tipo == TipoLlamada.TT) _plantillaSmsTT.value else _plantillaSmsRef.value
        val mensaje = SmsHelper.armarMensaje(plantilla, item.nombre, item.monto, _agenteSms.value, _contactoSms.value)
        SmsHelper.enviarSms(context, subId, item.telefono, mensaje)
    }

    fun setColoniaTexto(texto: String) { _coloniaTexto.value = texto }

    fun aplicarFiltroColonia(context: Context) {
        val texto = _coloniaTexto.value.trim()
        if (texto.isBlank()) { _coloniaIdsPermitidos.value = null; return }
        viewModelScope.launch {
            _coloniaCargando.value = true
            val base = candidatos.value.distinctBy { it.contactoId }
            for (c in base) {
                val ub = c.ubicacion ?: continue
                if (!coloniaCache.containsKey(ub)) coloniaCache[ub] = resolverColonia(context, ub)
            }
            val ids = base.filter { c -> c.ubicacion?.let { coloniaCache[it] }?.contains(texto, ignoreCase = true) == true }
                .map { it.contactoId }.toSet()
            _coloniaIdsPermitidos.value = ids
            _coloniaCargando.value = false
        }
    }

    fun cargarColoniasDisponibles(context: Context) {
        viewModelScope.launch {
            _coloniasCargando.value = true
            val base = candidatos.value.distinctBy { it.contactoId }
            for (c in base) {
                val ub = c.ubicacion ?: continue
                if (!coloniaCache.containsKey(ub)) coloniaCache[ub] = resolverColonia(context, ub)
            }
            _coloniasDisponibles.value = base.mapNotNull { it.ubicacion?.let { ub -> coloniaCache[ub] } }
                .filter { it.isNotBlank() }.distinct().sorted()
            _coloniasCargando.value = false
        }
    }

    fun seleccionarColoniaDelCatalogo(context: Context, nombre: String) {
        _coloniaTexto.value = nombre
        aplicarFiltroColonia(context)
    }

    fun limpiarFiltroColonia() { _coloniaTexto.value = ""; _coloniaIdsPermitidos.value = null }

    fun toggleSeleccionado(id: String) {
        val actuales = _excluidos.value.toMutableSet()
        if (id in actuales) actuales.remove(id) else actuales.add(id)
        _excluidos.value = actuales
    }
    fun seleccionarTodos() { _excluidos.value = emptySet() }
    fun deseleccionarTodos() { _excluidos.value = cola.value.map { it.id }.toSet() }

    /** Llama ahora mismo a la cola seleccionada, en primer plano (mientras la pantalla esté
     * abierta), esperando a que cada llamada termine antes de marcar la siguiente. */
    fun llamarAhora(context: Context) {
        if (_llamando.value) return
        val lista = cola.value.filter { it.seleccionado }
        if (lista.isEmpty()) return
        _llamando.value = true
        _progreso.value = 0 to lista.size
        viewModelScope.launch {
            val subId = _subscriptionIdSeleccionado.value
            val esperaExtraMs = _segundosEntreLlamadas.value * 1000L
            val estados = _estados.value.toMutableMap()
            for ((i, item) in lista.withIndex()) {
                if (!_llamando.value) break // se canceló desde la pantalla
                _itemActual.value = item
                estados[item.id] = EstadoLlamada.LLAMANDO
                _estados.value = estados.toMap()
                CallHelper.realizarLlamada(context, subId, item.telefono)
                delay(2000) // margen para que el sistema conecte la llamada antes de escuchar el estado
                CallHelper.esperarFinDeLlamada(context, timeoutMs = 120_000)
                if (_enviarSmsAlColgar.value) enviarSmsPostLlamada(context, item, subId)
                estados[item.id] = EstadoLlamada.HECHA
                _estados.value = estados.toMap()
                _progreso.value = (i + 1) to lista.size
                if (i < lista.lastIndex) delay(esperaExtraMs)
            }
            _itemActual.value = null
            _llamando.value = false
        }
    }

    fun detenerLlamadas() { _llamando.value = false }

    fun colgarActual(context: Context): Boolean = CallHelper.colgarLlamada(context)
    fun silenciar(context: Context, mute: Boolean) = CallHelper.silenciarMicrofono(context, mute)
    fun microfonoSilenciado(context: Context): Boolean = CallHelper.microfonoSilenciado(context)

    /** Programa el primer bloque para la próxima ocurrencia de la hora elegida (hoy si no ha
     * pasado, mañana si ya pasó), repitiéndose cada N horas, M veces — vía WorkManager. */
    fun programarBloques(context: Context) {
        val lista = cola.value.filter { it.seleccionado }
        if (lista.isEmpty()) return
        val inicioMillis = proximaOcurrencia(_horaInicioBloque.value, _minutoInicioBloque.value)
        CallRepeatWorker.programar(
            workManager = workManager, idsSeleccionados = lista.map { it.id },
            subscriptionId = _subscriptionIdSeleccionado.value, segundosEntreLlamadas = _segundosEntreLlamadas.value,
            iniciarEnMillis = inicioMillis, horasEntreBloques = _horasEntreBloques.value, repeticionesRestantes = _repeticionesBloque.value,
            enviarSmsAlColgar = _enviarSmsAlColgar.value, plantillaSmsTT = _plantillaSmsTT.value, plantillaSmsRef = _plantillaSmsRef.value,
            agenteSms = _agenteSms.value, contactoSms = _contactoSms.value
        )
    }

    fun cancelarBloquesProgramados() { CallRepeatWorker.cancelar(workManager) }
}
