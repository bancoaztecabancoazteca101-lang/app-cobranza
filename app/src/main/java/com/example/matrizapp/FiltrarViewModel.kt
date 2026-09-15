package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Un registro de Matriz encontrado cerca (<= 10 m) del titular que se está Filtrando, con sus
 * datos completos de contacto (no solo nombre/distancia) para poder llamar/mandar SMS desde
 * la vista rápida. `yaAgregado` indica si Diego ya confirmó sumar los Ref1/Ref2 de este cercano
 * como contacto extra del titular (ver ContactoExtraEntity) -- si es true, ya entran al ciclo
 * automático de Bloques y no hace falta mostrar el botón de agregar de nuevo. */
data class CercanoDetalle(
    val nombre: String,
    val numTT: String,
    val ref1: String,
    val ref2: String,
    val ubicacion: String?,
    val distanciaM: Int,
    val yaAgregado: Boolean = false
)

/** El titular con Status = "Filtrar", solo con lo que de verdad se ocupa de él (nombre, foto,
 * dirección) más la lista de registros encontrados cerca con sus datos completos. Guarda también
 * el MatrizEntity original completo (para el diálogo de "Actualizar Gestión", que sí necesita
 * observaciones/estado tal como están en Matriz). */
data class FiltrarItem(
    val id: String,
    val nombre: String,
    val estado: String,
    val imagen: String?,
    val ubicacion: String?,
    val cercanos: List<CercanoDetalle>,
    val original: MatrizEntity
)

/**
 * "Filtrar" ya no depende de la hoja de Google Sheet "Filtrar" ni del script de Apps Script que
 * la alimentaba: se calcula directo de los datos de Matriz ya sincronizados en el teléfono
 * (matriz_table), así que aparece al instante sin esperar a que el script corra y funciona
 * incluso sin conexión.
 *
 * Reglas (confirmadas con el usuario, actualizadas 15/09/2026):
 * - Detección automática por coordenadas: fuera de una zona "unidad" (ver más abajo), si un
 *   registro tiene entre 1 y MAX_VECINOS_AUTOMATICO vecinos a 10 metros o menos (grupo de hasta
 *   5 registros contando al titular), aparece automático, sin necesitar marcarlo a mano. La
 *   relación es mutua: CADA miembro del grupo sale con su propia tarjeta (no solo el de id
 *   menor), listando a los demás como cercanos -- así, si Jesús tiene cerca a María, también
 *   sale la tarjeta de María con Jesús como cercano (antes solo salía una de las dos).
 * - Si un registro tiene más de MAX_VECINOS_AUTOMATICO vecinos a 10 metros o menos, se trata como
 *   "vecindario" (edificio con muchos domicilios pegados) y NO aparece automático -- solo si se
 *   marca su Status como "Filtrar" a mano, igual que antes.
 * - Dentro de una zona "unidad" conocida (ver ZONAS_UNIDAD más abajo), nunca aparece automático
 *   sin importar cuántos vecinos tenga -- siempre requiere Status="Filtrar" manual.
 * - La idea de Filtrar es traer los datos de contacto completos (Num TT, Ref1, Ref2, dirección)
 *   de los registros de Matriz encontrados a 10 metros o menos del titular, ordenados del más
 *   cercano al más lejano, máximo 7 -- del titular mismo solo se necesita nombre/foto/dirección.
 * - Solo considera registros del día de hoy (misma fecha que usa por defecto Filtro Fecha) --
 *   ni como titular ni como vecino entra un registro de otro día, para no comparar contra
 *   historial viejo que ya no es relevante hoy.
 */
private const val RADIO_CERCANOS_METROS = 10.0
private const val MAX_VECINOS_AUTOMATICO = 4

/** Zonas conocidas como "unidad habitacional" (varios domicilios pegados) donde, aunque el
 * algoritmo detecte exactamente 1 vecino a <= 10m, no se debe tratar como par aislado automático
 * -- la coordenada del titular puede caer cerca de un vecino real sin que ambos formen parte del
 * mismo edificio/unidad. Radio fijo de 50m por zona (ajustado de 100m a 50m el 13/09/2026,
 * porque a 100m tapaba pares reales como Cira Balderas Alarcón / Marisela Salvador González). */
private data class ZonaUnidad(val nombre: String, val lat: Double, val lng: Double, val radioMetros: Double = 50.0)

private val ZONAS_UNIDAD = listOf(
    ZonaUnidad("Av. Canal Nacional 110", 19.347889, -99.119333),
    ZonaUnidad("San Francisco Culhuacán", 19.344294, -99.119858),
    ZonaUnidad("Av. H. Escuela Naval Militar 180", 19.342833, -99.120278),
    ZonaUnidad("La Viga 1416", 19.372255, -99.121199),
    ZonaUnidad("Porto Alegre 305", 19.373484, -99.127671)
)

private fun estaEnZonaUnidad(coord: Pair<Double, Double>?): Boolean {
    if (coord == null) return false
    return ZONAS_UNIDAD.any { zona -> distanciaKm(coord, zona.lat to zona.lng) * 1000.0 <= zona.radioMetros }
}

class FiltrarViewModel(
    private val matrizDao: MatrizDao,
    private val workManager: WorkManager,
    val driveHelper: DriveHelper,
    private val repository: SheetsRepository,
    private val contactoExtraDao: ContactoExtraDao
) : ViewModel() {

    private fun inicioDeHoy(): Long = java.time.LocalDate.now()
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun finDeHoy(): Long = java.time.LocalDate.now().plusDays(1)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

    val items: StateFlow<List<FiltrarItem>> = combine(matrizDao.getAllMatriz(), contactoExtraDao.observarTodos()) { todos, extras ->
        val agregados = extras.map { it.clienteId to it.nombreOrigen }.toSet()
        calcularFiltrar(todos.filter { val f = it.fecha; f != null && f in inicioDeHoy()..finDeHoy() }, agregados)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun calcularFiltrar(todos: List<MatrizEntity>, agregados: Set<Pair<String, String>>): List<FiltrarItem> {
        // Coordenadas ya parseadas una sola vez para no repetir el parseo por cada comparación.
        val coords = todos.associateWith { parseLatLngOrden(it.ubicacion) }

        fun cercanosDe(item: MatrizEntity, limite: Int): List<Pair<MatrizEntity, Double>> {
            val miCoord = coords[item] ?: return emptyList()
            return todos.asSequence()
                .filter { it.id != item.id }
                .mapNotNull { otro -> coords[otro]?.let { otro to distanciaKm(miCoord, it) * 1000.0 } }
                .filter { (_, metros) -> metros <= RADIO_CERCANOS_METROS }
                .sortedBy { (_, metros) -> metros }
                .take(limite)
                .toList()
        }

        val candidatos = todos.filter { item ->
            val manual = item.estado.trim().equals("Filtrar", ignoreCase = true)
            if (manual) return@filter true
            // Antes solo calificaba el miembro de id menor del grupo (para no repetir tarjeta
            // por grupo); ahora, a petición de Diego, la relación es mutua: cualquier miembro
            // con 1 a MAX_VECINOS_AUTOMATICO vecinos a <=10m califica y sale con su propia
            // tarjeta, así el cercano aparece "de ida y vuelta" (ej. Jesús <-> María).
            val vecinos = cercanosDe(item, MAX_VECINOS_AUTOMATICO + 1)
            vecinos.isNotEmpty() && vecinos.size <= MAX_VECINOS_AUTOMATICO && !estaEnZonaUnidad(coords[item])
        }
        if (candidatos.isEmpty()) return emptyList()

        return candidatos.map { item ->
            val cercanos = cercanosDe(item, 7).map { (otro, metros) ->
                CercanoDetalle(
                    nombre = otro.nombre, numTT = otro.numTT, ref1 = otro.ref1, ref2 = otro.ref2,
                    ubicacion = otro.ubicacion, distanciaM = metros.toInt(),
                    yaAgregado = (item.id to otro.nombre) in agregados
                )
            }
            FiltrarItem(
                id = item.id, nombre = item.nombre, estado = item.estado,
                imagen = item.imagenUrl, ubicacion = item.ubicacion, cercanos = cercanos,
                original = item
            )
        }
    }

    /** Confirma sumar los Ref1/Ref2 de un cercano (encontrado por Filtrar) como contacto extra
     * del cliente titular -- desde ese momento entran a la misma ronda de SMS de referencia del
     * flujo automático de Bloques, mencionando siempre el nombre del titular. */
    fun agregarContactoExtra(clienteId: String, cercano: CercanoDetalle) {
        viewModelScope.launch {
            listOfNotNull(cercano.ref1.takeIf { it.isNotBlank() }, cercano.ref2.takeIf { it.isNotBlank() }).forEach { tel ->
                contactoExtraDao.insertar(ContactoExtraEntity(clienteId = clienteId, telefono = tel, nombreOrigen = cercano.nombre))
            }
        }
    }

    /** Guarda el cambio directo sobre el registro de Matriz (misma fila que ya existe ahí,
     * no hay una tabla "Filtrar" aparte) y dispara el push normal hacia el Sheet de Matriz. */
    fun guardarGestion(id: String, nuevoEstado: String, obs: String) {
        viewModelScope.launch {
            matrizDao.updateGestionLocal(id, nuevoEstado, obs)
            triggerSync()
        }
    }

    /** Igual que MatrizViewModel.cambiarIdYGuardar: guarda el registro completo de Matriz desde
     * el formulario completo (MatrizFullFormDialog) abierto desde Filtrar. */
    fun guardarRegistroCompleto(
        idAnterior: String, idNuevo: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long?, hora: String?, ruta: String?, folioP: String?,
        onResult: (exito: Boolean, error: String?) -> Unit
    ) {
        viewModelScope.launch {
            val idFinal = idNuevo.trim().ifBlank { idAnterior }
            if (idFinal != idAnterior) {
                try {
                    repository.renameRowId(Constants.SHEET_MATRIZ, idAnterior, idFinal, Constants.MatrizCols.COL_ID)
                    matrizDao.renameId(idAnterior, idFinal)
                } catch (e: Exception) {
                    onResult(false, "No se pudo cambiar el ID en el Sheet (revisa tu conexión): ${e.message}")
                    return@launch
                }
            }
            matrizDao.updateRegistroCompleto(
                idFinal, nombre.trim().uppercase(), semana, requisito, numTT, ref1, ref2,
                observaciones, estado, ubicacion, fecha, hora, ruta, folioP
            )
            triggerSync()
            onResult(true, null)
        }
    }

    private fun triggerSync() {
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork("sync_app_data", ExistingWorkPolicy.REPLACE, syncRequest)
    }
}
