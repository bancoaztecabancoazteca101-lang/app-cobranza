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

/** Antes había un solo mensaje fijo por semana (idéntico al script GAS). Como un cliente puede
 * recibir hasta 10 contactos en la misma semana (Sem 5), mandar siempre el mismo texto se ve
 * repetitivo/robótico — ahora hay varias variantes por semana y se rota entre ellas según
 * `variante` (normalmente el total de contactos previos al cliente, % tamaño de la lista). */
object MensajesCobranza {

    fun paraTT(nombre: String, monto: String, sem: Int, variante: Int = 0): String {
        val montoParte = if (monto.isNotBlank()) " por \$$monto" else ""
        val plantillas = plantillasTT[sem] ?: plantillasTT.getValue(1)
        val idx = ((variante % plantillas.size) + plantillas.size) % plantillas.size
        return plantillas[idx](nombre, montoParte)
    }

    fun paraReferencia(nombre: String, sem: Int, variante: Int = 0): String {
        val plantillas = plantillasRef[sem] ?: plantillasRef.getValue(1)
        val idx = ((variante % plantillas.size) + plantillas.size) % plantillas.size
        return plantillas[idx](nombre)
    }

    private val plantillasTT: Map<Int, List<(String, String) -> String>> = mapOf(
        1 to listOf(
            { n, m -> "Hola $n, Banco Azteca le recuerda su pago pendiente$m. Le invitamos a regularizar su situación a la brevedad." },
            { n, m -> "Banco Azteca: $n, tiene un pago pendiente$m con nosotros. Le pedimos ponerse al corriente lo antes posible." },
            { n, m -> "Estimado(a) $n, le recordamos que su pago con Banco Azteca sigue pendiente$m. Quedamos atentos a su pronto pago." }
        ),
        2 to listOf(
            { n, m -> "Hola $n, su pago con Banco Azteca sigue pendiente$m. Evite recargos, comuníquese hoy mismo." },
            { n, m -> "Banco Azteca: $n, aún no recibimos su pago$m. Le sugerimos contactarnos hoy para evitar cargos adicionales." },
            { n, m -> "$n, su cuenta con Banco Azteca continúa sin regularizar$m. Por favor comuníquese con nosotros a la brevedad." }
        ),
        3 to listOf(
            { n, m -> "Banco Azteca: $n, su cuenta presenta atraso$m. Regularice su pago para evitar afectaciones en su historial crediticio." },
            { n, m -> "$n, su adeudo con Banco Azteca sigue sin regularizarse$m. Le recordamos que esto puede afectar su historial crediticio." },
            { n, m -> "Banco Azteca le informa que $n mantiene un atraso$m. Es importante que se comunique para evitar mayores afectaciones." }
        ),
        4 to listOf(
            { n, m -> "Banco Azteca: $n, su adeudo$m continúa sin regularizar. De no atenderlo, podría afectarse su historial y generarse gestiones adicionales de cobro." },
            { n, m -> "$n, no hemos recibido respuesta sobre su adeudo$m. Le pedimos comunicarse pronto para evitar gestiones de cobro adicionales." },
            { n, m -> "Banco Azteca: su cuenta, $n, sigue vencida$m y sin respuesta de su parte. Regularícela para evitar consecuencias mayores." }
        ),
        5 to listOf(
            { n, m -> "Banco Azteca: $n, su cuenta presenta atraso grave$m. Es indispensable que se comunique hoy mismo para evitar medidas de cobranza y visitas en su domicilio." },
            { n, m -> "$n, su adeudo$m sigue sin resolverse y es urgente atenderlo. Contáctenos hoy mismo para evitar visitas de cobranza en su domicilio." },
            { n, m -> "Banco Azteca: es indispensable que $n se comunique hoy$m. De lo contrario, procederemos con gestiones de cobranza, incluida visita domiciliaria." }
        )
    )

    private val plantillasRef: Map<Int, List<(String) -> String>> = mapOf(
        1 to listOf(
            { n -> "Banco Azteca le informa que $n mantiene un pago pendiente. Le solicitamos, por favor, comunicarle que se ponga en contacto con nosotros." },
            { n -> "Le hablamos de Banco Azteca: $n tiene un pago pendiente con nosotros. Le pedimos su apoyo para que se comunique a la brevedad." },
            { n -> "Banco Azteca: le pedimos comunicarle a $n que tiene un pago pendiente y que se ponga en contacto con nosotros." }
        ),
        2 to listOf(
            { n -> "Banco Azteca le informa que $n mantiene un pago pendiente sin regularizar. Le pedimos comunicarle que se contacte con nosotros a la brevedad." },
            { n -> "Le escribimos de Banco Azteca: $n aún no regulariza su pago. Agradecemos su apoyo para pedirle que nos contacte pronto." },
            { n -> "Banco Azteca: $n sigue sin regularizar su pago pendiente. Le solicitamos su apoyo para que se comunique con nosotros." }
        ),
        3 to listOf(
            { n -> "Banco Azteca: $n mantiene un adeudo pendiente de regularización. Le solicitamos comunicarle la importancia de contactarnos pronto." },
            { n -> "Le hablamos de Banco Azteca: el adeudo de $n sigue sin regularizarse. Le pedimos comunicarle que nos contacte cuanto antes." },
            { n -> "Banco Azteca: es importante que $n regularice su adeudo. Le pedimos su apoyo para comunicarle que se ponga en contacto con nosotros." }
        ),
        4 to listOf(
            { n -> "Banco Azteca: $n mantiene un adeudo vencido y no ha respondido a nuestras gestiones de cobro. Le solicitamos comunicarle la urgencia de regularizar su situación." },
            { n -> "Le escribimos de Banco Azteca: no hemos tenido respuesta de $n sobre su adeudo vencido. Le pedimos comunicarle que es urgente que nos contacte." },
            { n -> "Banco Azteca: el adeudo de $n sigue vencido y sin respuesta. Le solicitamos su apoyo para comunicarle la urgencia de regularizarlo." }
        ),
        5 to listOf(
            { n -> "Banco Azteca: $n mantiene un adeudo grave sin resolver. Le solicitamos, por favor, comunicarle con urgencia que se contacte con nosotros para evitar visitas en su domicilio." },
            { n -> "Le hablamos de Banco Azteca: el caso de $n es urgente. Le pedimos comunicarle que se contacte con nosotros hoy mismo para evitar visitas de cobranza." },
            { n -> "Banco Azteca: es indispensable que $n se comunique hoy. Le solicitamos su apoyo para evitarle una visita domiciliaria de cobranza." }
        )
    )
}
