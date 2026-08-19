package com.example.matrizapp
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Una fila de la hoja "Cont-Sem-NN": solo lectura, viene de Apps Script (guardarRegistroSemana6). */
data class Sem6Item(
    val nombre: String,
    val sem: String,
    val req: String,
    val id: String,
    val cu: String,
    val imagenUrl: String?,
    val colonia: String,
    val visitas: Int,
    val ultimaFechaVisita: String,
    val numTT: String = "",
    val ubicacion: String = "",
    val seContiene: String = "",
    val susceptible: String = "",
    val observaciones: String = ""
)

/** Calcula el nombre de la hoja Sem6 de la semana actual, ej: "Cont-Sem-34".
 * Debe coincidir EXACTO con la lógica del script de Apps Script (obtenerNumeroSemanaISO). */
fun currentSem6SheetName(): String {
    val cal = Calendar.getInstance()
    cal.firstDayOfWeek = Calendar.MONDAY
    cal.minimalDaysInFirstWeek = 4 // hace que getWeekOfYear se comporte como ISO 8601
    val numeroSemana = cal.get(Calendar.WEEK_OF_YEAR)
    return "Cont-Sem-$numeroSemana"
}

/** Guarda/recupera la última respuesta exitosa de Sem6, para poder mostrar algo
 * si no hay señal en campo. Usa SharedPreferences: no necesita Room ni WorkManager. */
class Sem6CacheStore(context: Context) {
    private val prefs = context.getSharedPreferences("sem6_cache", Context.MODE_PRIVATE)

    fun save(items: List<Sem6Item>) {
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("nombre", item.nombre)
            o.put("sem", item.sem)
            o.put("req", item.req)
            o.put("id", item.id)
            o.put("cu", item.cu)
            o.put("imagenUrl", item.imagenUrl)
            o.put("colonia", item.colonia)
            o.put("visitas", item.visitas)
            o.put("ultimaFechaVisita", item.ultimaFechaVisita)
            o.put("numTT", item.numTT)
            o.put("ubicacion", item.ubicacion)
            o.put("seContiene", item.seContiene)
            o.put("susceptible", item.susceptible)
            o.put("observaciones", item.observaciones)
            arr.put(o)
        }
        prefs.edit()
            .putString("data", arr.toString())
            .putLong("timestamp", System.currentTimeMillis())
            .apply()
    }

    /** Devuelve (items, momentoDeLaUltimaActualizacion) o null si nunca se guardó nada. */
    fun load(): Pair<List<Sem6Item>, Long>? {
        val json = prefs.getString("data", null) ?: return null
        val ts = prefs.getLong("timestamp", 0L)
        val arr = JSONArray(json)
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Sem6Item(
                nombre = o.optString("nombre"),
                sem = o.optString("sem"),
                req = o.optString("req"),
                id = o.optString("id"),
                cu = o.optString("cu"),
                imagenUrl = if (o.isNull("imagenUrl")) null else o.optString("imagenUrl"),
                colonia = o.optString("colonia"),
                visitas = o.optInt("visitas"),
                ultimaFechaVisita = o.optString("ultimaFechaVisita"),
                numTT = o.optString("numTT"),
                ubicacion = o.optString("ubicacion"),
                seContiene = o.optString("seContiene"),
                susceptible = o.optString("susceptible"),
                observaciones = o.optString("observaciones")
            )
        }
        return items to ts
    }
}
