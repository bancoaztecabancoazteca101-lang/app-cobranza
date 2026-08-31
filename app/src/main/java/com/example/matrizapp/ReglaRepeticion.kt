package com.example.matrizapp

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Offsets relativos al bloque de alta del cliente, por semana de atraso.
 * Idénticos a BLOQUES_POR_SEM del script GAS (proyecto "Llamadas Auto API",
 * respaldo 8/21/2026) — es la tabla que ya está en producción, no un placeholder.
 */
object ReglaRepeticion {

    val BLOQUES_POR_SEM: Map<Int, List<Int>> = mapOf(
        1 to listOf(0, 5),
        2 to listOf(0, 4, 8),
        3 to listOf(0, 2, 4, 6, 8),
        4 to listOf(0, 1, 3, 4, 6, 7, 9),
        5 to listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    )

    fun metaContactos(sem: Int): Int = BLOQUES_POR_SEM[sem]?.size ?: 0

    /**
     * Índice (0-based) del bloque de alta de un cliente, sobre la lista de bloques
     * activos ordenada por hora. Generaliza calcularBloqueDeAlta() del script GAS:
     * ya no asume espaciado fijo de 1 hora entre bloques, así que sigue funcionando
     * si Diego agrega/quita/mueve bloques.
     *
     * Regla: el cliente cae en el primer bloque cuya hora sea >= hora de alta.
     * Si se dio de alta después del último bloque del día, cae en el último bloque.
     */
    fun calcularBloqueDeAlta(fechaAlta: LocalDateTime, bloquesActivos: List<BloqueHorarioEntity>): Int {
        val ordenados = bloquesActivos.filter { it.activo }.sortedBy { it.hora * 60 + it.minuto }
        if (ordenados.isEmpty()) return 0

        val horaAlta = fechaAlta.toLocalTime()
        val idx = ordenados.indexOfFirst { it.toLocalTime() >= horaAlta }
        return if (idx == -1) ordenados.lastIndex else idx
    }

    fun debeContactarseEnBloque(sem: Int, bloqueActualIndex: Int, bloqueAltaIndex: Int): Boolean {
        val offsets = BLOQUES_POR_SEM[sem] ?: return false
        return offsets.contains(bloqueActualIndex - bloqueAltaIndex)
    }

    fun calcularDeficit(sem: Int, contactosRealizadosAyer: Int): Int =
        (metaContactos(sem) - contactosRealizadosAyer).coerceAtLeast(0)

    /**
     * Reconstruye la fecha/hora de alta de un MatrizEntity. `fecha` es el timestamp;
     * si `hora` trae un valor tipo "HH:mm" explícito, se usa para la parte de horario
     * (por si `fecha` solo guarda la fecha sin hora real). Ajusta esto si en tu caso
     * `fecha` ya incluye la hora correcta y `hora` es solo texto derivado/duplicado.
     */
    fun fechaAltaDe(registro: MatrizEntity): LocalDateTime? {
        val millis = registro.fecha ?: return null
        val base = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val horaTexto = registro.hora?.trim()
        if (!horaTexto.isNullOrBlank()) {
            val partes = horaTexto.split(":")
            val h = partes.getOrNull(0)?.toIntOrNull()
            val m = partes.getOrNull(1)?.toIntOrNull()
            if (h != null && m != null) return base.toLocalDate().atTime(h, m)
        }
        return base
    }
}

/** Antes había 5 mensajes fijos en el código (uno por semana). Ahora las plantillas viven en
 * Room (`PlantillaSmsEntity`, editables desde la pantalla "Plantillas de SMS") y aquí solo queda
 * la lógica de rotación: hasta 6 variantes por semana/tipo, se rota según `variante` (el total de
 * contactos previos al cliente, `ContactoLogDao.contarTotalContactos`) para no repetir el mismo
 * texto en clientes con varios contactos en la semana (hasta 10 en Sem 5). Los defaults de
 * `PlantillasSemillaSms` se insertan una sola vez al arrancar la app (AppContainer). */
object MensajesCobranza {

    suspend fun paraTT(dao: PlantillaSmsDao, nombre: String, monto: String, sem: Int, variante: Int = 0): String {
        val montoParte = if (monto.isNotBlank()) " por \$$monto" else ""
        val plantillas = dao.obtenerActivasPara("TT", sem)
        if (plantillas.isEmpty()) return "Banco Azteca: $nombre, su cuenta presenta atraso$montoParte. Comuníquese hoy mismo."
        val idx = ((variante % plantillas.size) + plantillas.size) % plantillas.size
        return plantillas[idx].texto.replace("%nombre%", nombre, ignoreCase = true).replace("%monto%", montoParte, ignoreCase = true)
    }

    suspend fun paraReferencia(dao: PlantillaSmsDao, nombre: String, sem: Int, variante: Int = 0): String {
        val plantillas = dao.obtenerActivasPara("REF", sem)
        if (plantillas.isEmpty()) return "Banco Azteca le informa que $nombre mantiene un adeudo pendiente. Le pedimos comunicarle que se contacte con nosotros."
        val idx = ((variante % plantillas.size) + plantillas.size) % plantillas.size
        return plantillas[idx].texto.replace("%nombre%", nombre, ignoreCase = true)
    }
}
