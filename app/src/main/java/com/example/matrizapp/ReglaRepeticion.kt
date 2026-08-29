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

/** Los 5 mensajes escalonados por semana, tal cual ya redactados y en producción en el script GAS. */
object MensajesCobranza {
    fun paraTT(nombre: String, monto: String, sem: Int): String {
        val montoParte = if (monto.isNotBlank()) " por \$$monto" else ""
        return when (sem) {
            1 -> "Hola $nombre, Banco Azteca le recuerda su pago pendiente$montoParte. Le invitamos a regularizar su situación a la brevedad."
            2 -> "Hola $nombre, su pago con Banco Azteca sigue pendiente$montoParte. Evite recargos, comuníquese hoy mismo."
            3 -> "Banco Azteca: $nombre, su cuenta presenta atraso$montoParte. Regularice su pago para evitar afectaciones en su historial crediticio."
            4 -> "Banco Azteca: $nombre, su adeudo$montoParte continúa sin regularizar. De no atenderlo, podría afectarse su historial y generarse gestiones adicionales de cobro."
            5 -> "Banco Azteca: $nombre, su cuenta presenta atraso grave$montoParte. Es indispensable que se comunique hoy mismo para evitar medidas de cobranza y visitas en su domicilio."
            else -> "Banco Azteca: $nombre, su cuenta presenta atraso$montoParte. Comuníquese hoy mismo."
        }
    }

    fun paraReferencia(nombre: String, sem: Int): String = when (sem) {
        1 -> "Banco Azteca le informa que $nombre mantiene un pago pendiente. Le solicitamos, por favor, comunicarle que se ponga en contacto con nosotros."
        2 -> "Banco Azteca le informa que $nombre mantiene un pago pendiente sin regularizar. Le pedimos comunicarle que se contacte con nosotros a la brevedad."
        3 -> "Banco Azteca: $nombre mantiene un adeudo pendiente de regularización. Le solicitamos comunicarle la importancia de contactarnos pronto."
        4 -> "Banco Azteca: $nombre mantiene un adeudo vencido y no ha respondido a nuestras gestiones de cobro. Le solicitamos comunicarle la urgencia de regularizar su situación."
        5 -> "Banco Azteca: $nombre mantiene un adeudo grave sin resolver. Le solicitamos, por favor, comunicarle con urgencia que se contacte con nosotros para evitar visitas en su domicilio."
        else -> "Banco Azteca le informa que $nombre mantiene un adeudo pendiente. Le pedimos comunicarle que se contacte con nosotros."
    }
}
