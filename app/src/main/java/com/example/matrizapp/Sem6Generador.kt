package com.example.matrizapp

import android.content.Context
import java.util.Calendar

/** Reemplazo, dentro de la app, del script de Apps Script `guardarRegistroSemana6`. Reglas del script que se conservan:
 *  - Solo registros de Matriz con Sem = 6, con Id, y con Fecha dentro de la SEMANA ISO ACTUAL (lunes a domingo).
 *  - Van a la hoja "Cont-Sem-NN" (NN = semana ISO). Un cliente aparece UNA vez por semana (por Id).
 *  - Registro nuevo: nombre, sem, req, id, CU, ubicación, imagen, teléfono; visitas = 1.
 *  - Registro ya existente: se rellenan teléfono/ubicación/imagen si estaban vacíos.
 *  - Colonia: por geocodificación inversa de la ubicación (el script usaba Maps; aquí el caché de direcciones).
 *  Cambio a propósito: el script sumaba +1 "visita" cada DÍA a TODOS los registros de la semana (por eso todos tenían el mismo
 *  número). Aquí "Visitas" = días distintos en que el registro tuvo fecha/edición (visita real): +1 cuando la fecha del registro
 *  es un día posterior a la última fecha de visita guardada. */
object Sem6Generador {
    private fun inicioSemanaMillis(): Long {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val atrasLunes = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lunes = 0 ... domingo = 6
        cal.add(Calendar.DAY_OF_YEAR, -atrasLunes)
        return cal.timeInMillis
    }

    private fun diaStr(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Pasa a la tabla de Semana 6 los registros de Matriz que correspondan (ver reglas arriba). Idempotente. */
    suspend fun actualizar(sem6Dao: Sem6Dao, registros: List<MatrizEntity>, eliminado: (hoja: String, id: String) -> Boolean) {
        val hoja = currentSem6SheetName()
        val lunes = inicioSemanaMillis()
        val finSemana = lunes + 7L * 24 * 60 * 60 * 1000
        val existentes = sem6Dao.obtenerHoja(hoja).associateBy { it.id }
        val nuevos = ArrayList<Sem6RegistroEntity>()
        for (r in registros) {
            if (r.semana.trim().toIntOrNull() != 6) continue
            val id = r.id.trim()
            if (id.isEmpty() || eliminado(hoja, id)) continue
            val f = r.fecha ?: continue
            if (f < lunes || f >= finSemana) continue
            val dia = diaStr(f)
            val ex = existentes[id]
            if (ex == null) {
                nuevos.add(Sem6RegistroEntity(
                    hoja = hoja, id = id, nombre = r.nombre, sem = "6", req = r.requisito, cu = r.folioP.orEmpty(),
                    ubicacion = r.ubicacion.orEmpty().let { if (it == "N/A") "" else it }, imagenUrl = r.imagenUrl,
                    visitas = 1, ultimaFechaVisita = dia, numTT = r.numTT
                ))
            } else {
                var actualizado = ex
                if (actualizado.numTT.isBlank() && r.numTT.isNotBlank()) actualizado = actualizado.copy(numTT = r.numTT)
                if (actualizado.ubicacion.isBlank() && !r.ubicacion.isNullOrBlank() && r.ubicacion != "N/A") actualizado = actualizado.copy(ubicacion = r.ubicacion!!)
                if (actualizado.imagenUrl.isNullOrBlank() && !r.imagenUrl.isNullOrBlank()) actualizado = actualizado.copy(imagenUrl = r.imagenUrl)
                if (actualizado.cu.isBlank() && !r.folioP.isNullOrBlank()) actualizado = actualizado.copy(cu = r.folioP!!)
                val ultimaIso = Regex("""^\d{4}-\d{2}-\d{2}""").find(actualizado.ultimaFechaVisita)?.value ?: ""
                if (dia > ultimaIso) actualizado = actualizado.copy(visitas = actualizado.visitas + 1, ultimaFechaVisita = dia)
                if (actualizado != ex) sem6Dao.guardar(actualizado)
            }
        }
        if (nuevos.isNotEmpty()) sem6Dao.insertarSiFalta(nuevos)
    }

    /** Rellena la colonia de los registros que aún no la tienen (máx. [limite] por pasada; usa el caché de direcciones). */
    suspend fun completarColonias(context: Context, sem6Dao: Sem6Dao, hoja: String, limite: Int = 8) {
        var hechos = 0
        for (r in sem6Dao.obtenerHoja(hoja)) {
            if (hechos >= limite) break
            if (r.colonia.isNotBlank() || r.ubicacion.isBlank()) continue
            val colonia = try { DireccionCache.obtener(context, r.ubicacion).first } catch (_: Exception) { null }
            if (!colonia.isNullOrBlank()) { sem6Dao.actualizarColonia(hoja, r.id, colonia); hechos++ }
        }
    }

    /** Importa UNA vez lo que ya existe en las hojas "Cont-Sem-NN" (de todas las semanas) para no perder lo capturado.
     *  Fusiona: lo ya capturado en la app no se pisa; solo se completa lo que esté vacío. Devuelve true si terminó sin error. */
    suspend fun importarDesdeHojas(repository: SheetsRepository, sem6Dao: Sem6Dao, eliminado: (hoja: String, id: String) -> Boolean): Boolean {
        return try {
            for (hoja in repository.listSem6SheetNames()) {
                for (item in repository.fetchSem6Data(hoja)) {
                    if (eliminado(hoja, item.id)) continue
                    val local = sem6Dao.obtener(hoja, item.id)
                    if (local == null) { sem6Dao.guardar(item.toEntity(hoja)); continue }
                    val fusion = local.copy(
                        nombre = local.nombre.ifBlank { item.nombre }, req = local.req.ifBlank { item.req }, cu = local.cu.ifBlank { item.cu },
                        ubicacion = local.ubicacion.ifBlank { item.ubicacion }, imagenUrl = local.imagenUrl?.takeIf { it.isNotBlank() } ?: item.imagenUrl,
                        colonia = local.colonia.ifBlank { item.colonia }, numTT = local.numTT.ifBlank { item.numTT },
                        visitas = maxOf(local.visitas, item.visitas), ultimaFechaVisita = maxOf(local.ultimaFechaVisita, item.ultimaFechaVisita),
                        seContiene = local.seContiene.ifBlank { item.seContiene }, susceptible = local.susceptible.ifBlank { item.susceptible },
                        observaciones = local.observaciones.ifBlank { item.observaciones }, capital = local.capital.ifBlank { item.capital },
                        abono = local.abono.ifBlank { item.abono }
                    )
                    if (fusion != local) sem6Dao.guardar(fusion)
                }
            }
            true
        } catch (_: Exception) { false }
    }
}
