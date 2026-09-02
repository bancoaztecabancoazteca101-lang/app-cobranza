package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pase es una copia independiente de Matriz (ver SheetsRepository.copiarPaseDesdeMatriz):
 * todo lo que pasa aquí -- crear, editar, eliminar -- es 100% local a pase_cartera_table y
 * nunca toca matriz_table ni la hoja de Sheets de Matriz. Tampoco al revés: editar Matriz
 * después de que un registro ya se copió a Pase no altera la copia. */
class PaseCarteraViewModel(
    private val paseDao: PaseCarteraDao,
    val driveHelper: DriveHelper
) : ViewModel() {

    val paseList: StateFlow<List<PaseEntity>> = paseDao.getAllPase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _deleteInProgress = MutableStateFlow(false)
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress

    /** Misma forma que MatrizViewModel.cambiarIdYGuardar para poder reusar
     * MatrizFullFormDialog tal cual, pero el id de Pase es interno (UUID local) y no se
     * expone a ningún Sheet -- no tiene sentido dejarlo editable, así que se ignora
     * `idNuevo` y siempre se conserva `idAnterior`. Conserva imagenUrl/imagenUrl2 y
     * origenMatrizId del registro existente (este formulario no los edita). */
    fun cambiarIdYGuardar(
        idAnterior: String, idNuevo: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long?, hora: String?, ruta: String?, folioP: String?,
        onResult: (exito: Boolean, error: String?) -> Unit
    ) {
        viewModelScope.launch {
            val existente = paseList.value.find { it.id == idAnterior }
            if (existente == null) {
                onResult(false, "El registro ya no existe")
                return@launch
            }
            paseDao.actualizar(
                existente.copy(
                    nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
                    ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado,
                    ubicacion = ubicacion, fecha = fecha, hora = hora, ruta = ruta, folioP = folioP,
                    isDirty = true
                )
            )
            onResult(true, null)
        }
    }

    /** Alta manual directa en Pase (sin pasar por Matriz) -- por si se necesita agregar un
     * caso suelto. `origenMatrizId` queda vacío porque no viene de ninguna copia. */
    fun crearRegistro(
        id: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long, hora: String?, ruta: String?, folioP: String?
    ) {
        val idFinal = id.trim().ifBlank { java.util.UUID.randomUUID().toString().replace("-", "").take(12) }
        viewModelScope.launch {
            paseDao.insertar(
                PaseEntity(
                    id = idFinal, nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
                    ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado,
                    ubicacion = ubicacion, imagenUrl = null, imagenUrl2 = null, fecha = fecha,
                    hora = hora, ruta = ruta, folioP = folioP, origenMatrizId = "", isDirty = true
                )
            )
        }
    }

    fun eliminarRegistro(id: String, onResult: (exito: Boolean, error: String?) -> Unit) {
        viewModelScope.launch {
            _deleteInProgress.value = true
            paseDao.eliminar(id)
            _deleteInProgress.value = false
            onResult(true, null)
        }
    }
}
