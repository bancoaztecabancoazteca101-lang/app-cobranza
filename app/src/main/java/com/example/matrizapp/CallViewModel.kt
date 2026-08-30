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
    val id: String, val nombre: String, val tipo: TipoLlamada, val telefono: String,
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

    // Submenú por tipo (Titular / Ref1 / Ref2), igual que SmsViewModel con FuenteSms: cada tipo
    // tiene su propia cola, configuración de bloque y plantilla — antes era un solo checkbox por
    // tipo mezclando los tres en una sola cola con un solo bloque de configuración compartido.
    private val _tipo = MutableStateFlow(TipoLlamada.TT)
    val tipo: StateFlow<TipoLlamada> = _tipo
    fun setTipo(t: TipoLlamada) { _tipo.value = t }

    private val _subscriptionIdSeleccionado = MutableStateFlow<Int?>(null)
    val subscriptionIdSeleccionado: StateFlow<Int?> = _subscriptionIdSeleccionado
    fun setSim(subscriptionId: Int?) { _subscriptionIdSeleccionado.value = subscriptionId }

    /** Config de bloque propia por tipo — antes era un único valor compartido para los tres,
     * por eso ajustar la pausa/duración/horario de uno desconfiguraba a los otros dos. */
    data class ConfigLlamada(
        val segundosEntreLlamadas: Int = 5,
        val duracionMaximaSegundos: Int = 45,
        val horaInicioBloque: Int = 9,
        val minutoInicioBloque: Int = 0,
        val horasEntreBloques: Int = 1,
        val repeticionesBloque: Int = 9
    )

    private val _configPorTipo: Map<TipoLlamada, MutableStateFlow<ConfigLlamada>> =
        TipoLlamada.values().associateWith { MutableStateFlow(ConfigLlamada()) }

    fun configFlow(t: TipoLlamada): StateFlow<ConfigLlamada> = _configPorTipo.getValue(t)

    fun setSegundosEntreLlamadas(t: TipoLlamada, v: Int) {
        val flujo = _configPorTipo.getValue(t); flujo.value = flujo.value.copy(segundosEntreLlamadas = v.coerceIn(1, 300))
    }
    fun setDuracionMaximaSegundos(t: TipoLlamada, v: Int) {
        val flujo = _configPorTipo.getValue(t); flujo.value = flujo.value.copy(duracionMaximaSegundos = v.coerceIn(5, 600))
    }
    fun setHoraInicioBloque(t: TipoLlamada, h: Int, m: Int) {
        val flujo = _configPorTipo.getValue(t); flujo.value = flujo.value.copy(horaInicioBloque = h.coerceIn(0, 23), minutoInicioBloque = m.coerceIn(0, 59))
    }
    fun setHorasEntreBloques(t: TipoLlamada, v: Int) {
        val flujo = _configPorTipo.getValue(t); flujo.value = flujo.value.copy(horasEntreBloques = v.coerceIn(1, 12))
    }
    fun setRepeticionesBloque(t: TipoLlamada, v: Int) {
        val flujo = _configPorTipo.getValue(t); flujo.value = flujo.value.copy(repeticionesBloque = v.coerceIn(1, 20))
    }

    // Flujo tipo Tasker: al colgar, mandar SMS a ese mismo número. Toggle y plantilla propios
    // por tipo (Titular arranca con la plantilla de cobranza directa; Ref1/Ref2 con la de
    // referencia, igual que antes, pero ahora cada uno se edita sin afectar a los otros).
    private val _enviarSmsAlColgarPorTipo: Map<TipoLlamada, MutableStateFlow<Boolean>> =
        TipoLlamada.values().associateWith { MutableStateFlow(false) }
    fun enviarSmsAlColgarFlow(t: TipoLlamada): StateFlow<Boolean> = _enviarSmsAlColgarPorTipo.getValue(t)
    fun setEnviarSmsAlColgar(t: TipoLlamada, v: Boolean) { _enviarSmsAlColgarPorTipo.getValue(t).value = v }

    private val _plantillaSmsPorTipo: Map<TipoLlamada, MutableStateFlow<String>> = mapOf(
        TipoLlamada.TT to MutableStateFlow(PLANTILLA_SMS_TT_DEFAULT),
        TipoLlamada.REF1 to MutableStateFlow(PLANTILLA_SMS_REF_DEFAULT),
        TipoLlamada.REF2 to MutableStateFlow(PLANTILLA_SMS_REF_DEFAULT)
    )
    fun plantillaSmsFlow(t: TipoLlamada): StateFlow<String> = _plantillaSmsPorTipo.getValue(t)
    fun setPlantillaSms(t: TipoLlamada, texto: String) { _plantillaSmsPorTipo.getValue(t).value = texto }

    private val _agenteSms = MutableStateFlow("")
    val agenteSms: StateFlow<String> = _agenteSms
    private val _contactoSms = MutableStateFlow("")
    val contactoSms: StateFlow<String> = _contactoSms
    fun setAgenteSms(texto: String) { _agenteSms.value = texto }
    fun setContactoSms(texto: String) { _contactoSms.value = texto }

    // Selección/estado por id de registro directo (ya no hace falta el sufijo _TT/_REF1/_REF2:
    // cada submenú solo ve su propio tipo, igual que SmsViewModel).
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

    // Candidatos según fecha + tipo + selección, antes del filtro de colonia.
    private val candidatos: StateFlow<List<LlamadaItem>> = combine(
        registrosTodos, _fechaInicio, _fechaFin, _tipo, _excluidos, _estados
    ) { valores ->
        @Suppress("UNCHECKED_CAST") val registros = valores[0] as List<MatrizEntity>
        val ini = valores[1] as Long
        val fin = valores[2] as Long
        val tipoActual = valores[3] as TipoLlamada
        val excluidos = valores[4] as Set<String>
        @Suppress("UNCHECKED_CAST") val estados = valores[5] as Map<String, EstadoLlamada>

        registros.filter { it.fecha in ini..fin }.mapNotNull { r ->
            val telefono = when (tipoActual) {
                TipoLlamada.TT -> r.numTT
                TipoLlamada.REF1 -> r.ref1
                TipoLlamada.REF2 -> r.ref2
            }
            if (telefono.isNullOrBlank()) return@mapNotNull null
            LlamadaItem(r.id, r.nombre, tipoActual, telefono, r.requisito, r.ubicacion, r.id !in excluidos, estados[r.id] ?: EstadoLlamada.PENDIENTE)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cola: StateFlow<List<LlamadaItem>> = combine(candidatos, _coloniaIdsPermitidos) { base, permitidos ->
        if (permitidos == null) base else base.filter { it.id in permitidos }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _llamando = MutableStateFlow(false)
    val llamando: StateFlow<Boolean> = _llamando
    private val _progreso = MutableStateFlow(0 to 0)
    val progreso: StateFlow<Pair<Int, Int>> = _progreso
    private val _itemActual = MutableStateFlow<LlamadaItem?>(null)
    val itemActual: StateFlow<LlamadaItem?> = _itemActual

    fun setFechaInicio(millis: Long) { _fechaInicio.value = inicioDeDiaC(millis); if (_fechaFin.value < _fechaInicio.value) _fechaFin.value = finDeDiaC(millis) }
    fun setFechaFin(millis: Long) { _fechaFin.value = finDeDiaC(millis); if (_fechaInicio.value > _fechaFin.value) _fechaInicio.value = inicioDeDiaC(millis) }

    private fun enviarSmsPostLlamada(context: Context, item: LlamadaItem, subId: Int?) {
        val plantilla = plantillaSmsFlow(item.tipo).value
        if (plantilla.isBlank()) return
        val mensaje = SmsHelper.armarMensaje(plantilla, item.nombre, item.monto, _agenteSms.value, _contactoSms.value)
        SmsHelper.enviarSms(context, subId, item.telefono, mensaje)
    }

    fun setColoniaTexto(texto: String) { _coloniaTexto.value = texto }

    fun aplicarFiltroColonia(context: Context) {
        val texto = _coloniaTexto.value.trim()
        if (texto.isBlank()) { _coloniaIdsPermitidos.value = null; return }
        viewModelScope.launch {
            _coloniaCargando.value = true
            val base = candidatos.value
            for (c in base) {
                val ub = c.ubicacion ?: continue
                if (!coloniaCache.containsKey(ub)) coloniaCache[ub] = resolverColonia(context, ub)
            }
            val ids = base.filter { c -> c.ubicacion?.let { coloniaCache[it] }?.contains(texto, ignoreCase = true) == true }
                .map { it.id }.toSet()
            _coloniaIdsPermitidos.value = ids
            _coloniaCargando.value = false
        }
    }

    fun cargarColoniasDisponibles(context: Context) {
        viewModelScope.launch {
            _coloniasCargando.value = true
            val base = candidatos.value
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

    /** Llama ahora mismo a la cola seleccionada del tipo activo, en primer plano (mientras la
     * pantalla esté abierta), esperando a que cada llamada termine antes de marcar la siguiente. */
    fun llamarAhora(context: Context) {
        if (_llamando.value) return
        val lista = cola.value.filter { it.seleccionado }
        if (lista.isEmpty()) return
        val tipoActual = _tipo.value
        val config = configFlow(tipoActual).value
        val enviarSms = enviarSmsAlColgarFlow(tipoActual).value
        _llamando.value = true
        _progreso.value = 0 to lista.size
        viewModelScope.launch {
            val subId = _subscriptionIdSeleccionado.value
            val esperaExtraMs = config.segundosEntreLlamadas * 1000L
            val estados = _estados.value.toMutableMap()
            for ((i, item) in lista.withIndex()) {
                if (!_llamando.value) break // se canceló desde la pantalla
                _itemActual.value = item
                estados[item.id] = EstadoLlamada.LLAMANDO
                _estados.value = estados.toMap()
                CallHelper.realizarLlamada(context, subId, item.telefono)
                delay(2000) // margen para que el sistema conecte la llamada antes de escuchar el estado
                CallHelper.esperarFinOForzarColgar(context, duracionMaximaMs = config.duracionMaximaSegundos * 1000L)
                if (enviarSms) enviarSmsPostLlamada(context, item, subId)
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

    /** Cuelga la llamada en curso, usando el respaldo de accesibilidad si TelecomManager es
     * bloqueado por el fabricante (ver CallHelper.colgarLlamadaConFallback). */
    fun colgarActual(context: Context, onResultado: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResultado(CallHelper.colgarLlamadaConFallback(context))
        }
    }
    fun silenciar(context: Context, mute: Boolean) = CallHelper.silenciarMicrofono(context, mute)
    fun microfonoSilenciado(context: Context): Boolean = CallHelper.microfonoSilenciado(context)

    /** Programa el primer bloque del tipo activo para la próxima ocurrencia de la hora elegida
     * (hoy si no ha pasado, mañana si ya pasó), repitiéndose cada N horas, M veces — vía
     * WorkManager. Un único bloque programado a la vez entre los tres tipos (un teléfono no
     * puede marcar dos números a la vez), igual que SmsRepeatWorker comparte una sola ronda
     * programada entre las tres fuentes de SMS. */
    fun programarBloques(context: Context) {
        val lista = cola.value.filter { it.seleccionado }
        if (lista.isEmpty()) return
        val tipoActual = _tipo.value
        val config = configFlow(tipoActual).value
        val inicioMillis = proximaOcurrencia(config.horaInicioBloque, config.minutoInicioBloque)
        CallRepeatWorker.programar(
            workManager = workManager, tipo = tipoActual, idsSeleccionados = lista.map { it.id },
            subscriptionId = _subscriptionIdSeleccionado.value, segundosEntreLlamadas = config.segundosEntreLlamadas,
            duracionMaximaSegundos = config.duracionMaximaSegundos,
            iniciarEnMillis = inicioMillis, horasEntreBloques = config.horasEntreBloques, repeticionesRestantes = config.repeticionesBloque,
            enviarSmsAlColgar = enviarSmsAlColgarFlow(tipoActual).value, plantillaSms = plantillaSmsFlow(tipoActual).value,
            agenteSms = _agenteSms.value, contactoSms = _contactoSms.value
        )
    }

    fun cancelarBloquesProgramados() { CallRepeatWorker.cancelar(workManager) }

    /** true mientras haya bloques programados encolados o corriendo (sobrevive a cerrar la app;
     * se actualiza solo vía WorkManager). Se usa para mostrar el botón "Detener bloques" en los
     * 3 submenús (Titular/Ref1/Ref2) — el bloque programado es uno solo compartido entre los
     * tres tipos, así que este botón detiene el que esté activo sin importar desde qué pestaña
     * se abra, igual que el botón equivalente de SmsScreen. */
    val bloquesProgramadosActivos: StateFlow<Boolean> = CallRepeatWorker.estaProgramado(workManager)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}
