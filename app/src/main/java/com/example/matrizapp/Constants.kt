package com.example.matrizapp
object Constants {
    const val SPREADSHEET_ID = "1iMFndEHeEOs95egkOkhhc-2yfhwfFSY3YNNuwR_NsMA"
    const val SHEET_MATRIZ = "Matriz "
    const val SHEET_PASE = "Pase de Cartera"
    const val SHEET_SOLICITUD = "Solicitud"
    const val SHEET_FILTRO = "Filtro Fecha"
    const val SHEET_FILTRAR = "Filtrar"
    const val SHEET_CONTROL = "GraficaSuma"
    const val SHEET_RUTA_IA = "Ruta IA"
    const val FOLDER_IMAGES = "Matriz_Images/"
    const val FOLDER_AUDIOS = "Matriz_Audios/"

    object MatrizCols {
        const val ID = 12; const val NOMBRE = 0; const val SEMANA = 1; const val REQUISITO = 2
        const val NUMTT = 3; const val REF1 = 4; const val REF2 = 5; const val OBSERVACIONES = 6
        const val ESTADO = 7; const val UBICACION = 8; const val IMAGEN = 9; const val IMAGEN2 = 10; const val FECHA = 11
        const val HORA = 13; const val RUTA = 14; const val FOLIOP = 15; const val COL_ID = "M"
    }
    /**
     * Columnas conocidas de la hoja Pase de Cartera.
     * CONTIENE y CAPITALES se agregan al final para no desplazar ninguna columna existente.
     * Por tanto quedan T y U respectivamente.
     */
    object PaseCols {
        const val ID = 0; const val CU = 1; const val NOMBRE = 2; const val NUMTT = 3
        const val NOMBRE_REF1 = 4; const val REF1 = 5; const val NOMBRE_REF2 = 6; const val REF2 = 7
        const val IMAGEN1 = 12; const val IMAGEN2 = 13; const val UBICACION = 16
        const val ESTADO = 18; const val CONTIENE = 19; const val CAPITALES = 20
        const val COL_ID = "A"; const val COL_CONTIENE = "T"; const val COL_CAPITALES = "U"
    }
    object SolicitudCols {
        const val ID = 0; const val NOMBRE = 1; const val NUMERO = 2; const val SUCURSAL = 3
        const val UBICACION = 4; const val IMAGEN = 5; const val IMAGEN2 = 6
        const val NOMBRE_REF1 = 7; const val REF1 = 8; const val NOMBRE_REF2 = 9; const val REF2 = 10
        const val OBSERVACIONES = 11; const val AUDIO = 12; const val ESTADO = 13
        const val IMAGEN3 = 14; const val IMAGEN4 = 15; const val COL_ID = "A"
        const val GESTOR = 16; const val FECHA_HORA = 17
    }
    object FiltroCols {
        const val ID = 12; const val NOMBRE = 0; const val REQ = 2; const val NUMTT = 3; const val OBSERVACIONES = 6
        const val ESTADO = 7; const val REF1 = 4; const val REF2 = 5; const val UBICACION = 8
        const val IMAGEN = 9; const val FECHA = 11; const val HORA = 13
    }
    object FiltrarCols {
        const val NOMBRE = 0; const val SEMANA = 1; const val REQUERIDO = 2; const val NUMTT = 3
        val REF_PAIRS = listOf(4 to 5, 6 to 7, 8 to 9, 10 to 11, 12 to 13, 14 to 15, 16 to 17)
        const val OBSERVACIONES = 18; const val ESTADO = 19; const val UBICACION = 20
        const val IMAGEN = 21; const val FECHA = 25; const val ID = 26; const val HORA = 27
    }
    object ControlCols {
        const val SEMANA = 0; const val REQUERIDO = 1
    }
    object RutaIACols {
        const val ID = 0; const val NOMBRE = 1; const val CU = 2; const val DIRECCION = 3
        const val COLONIA_CP = 4; const val DIAS_ATRASO = 5; const val PAGO_REQUERIDO = 6
        const val LAT = 7; const val LNG = 8; const val ORDEN = 9; const val ES_NUEVO = 10
        const val CU_MATRIZ_MATCH = 11; const val FECHA = 12; const val ESTADO = 13
        const val COL_ID = "A"
    }
}

enum class OrdenLista(val etiqueta: String) {
    ORIGINAL("Como llegó"),
    FECHA_HORA_RECIENTE("Fecha y hora: más reciente primero"),
    FECHA_HORA_ANTIGUA("Fecha y hora: más antiguo primero"),
    UBICACION_CERCA("Ubicación: más cercano primero"),
    UBICACION_LEJOS("Ubicación: más lejano primero"),
    ALFABETICO_AZ("Alfabético: A-Z"),
    ALFABETICO_ZA("Alfabético: Z-A")
}

fun OrdenLista.necesitaUbicacionActual() = this == OrdenLista.UBICACION_CERCA || this == OrdenLista.UBICACION_LEJOS

fun parseLatLngOrden(raw: String?): Pair<Double, Double>? {
    if (raw.isNullOrBlank()) return null
    val partes = raw.split(",").map { it.trim() }
    if (partes.size != 2) return null
    val lat = partes[0].toDoubleOrNull() ?: return null
    val lng = partes[1].toDoubleOrNull() ?: return null
    return lat to lng
}

fun distanciaKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.first - a.first)
    val dLon = Math.toRadians(b.second - a.second)
    val la1 = Math.toRadians(a.first); val la2 = Math.toRadians(b.first)
    val h = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(la1) * kotlin.math.cos(la2) * kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
}

fun quitarAcentos(texto: String): String {
    val normalizado = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
    return normalizado.replace(Regex("\\p{Mn}+"), "")
}

fun coincideBusqueda(texto: String?, query: String): Boolean {
    if (texto.isNullOrBlank()) return false
    return quitarAcentos(texto).contains(quitarAcentos(query), ignoreCase = true)
}
