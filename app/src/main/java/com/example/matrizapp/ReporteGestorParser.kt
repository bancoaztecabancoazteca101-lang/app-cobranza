package com.example.matrizapp

import java.text.Normalizer
import kotlin.math.abs

/*
 * Lectura por OCR de las capturas de la app del banco ("Resumen del Gestor" y "Cobranza del gestor") para NO
 * teclear a mano la Tabla de Velocidades y la Comisión Individual.
 *
 * Esta parte es Kotlin puro (sin Android ni ML Kit): recibe las PALABRAS que detectó el OCR con su caja y las
 * interpreta por POSICIÓN (qué columna / qué sección), no por etiqueta exacta, porque el OCR falla en cosas
 * como "1 a 2" -> "la2" o "$0" -> "(O)". Todo lo que no se pueda leer queda en null: nunca se inventa un valor.
 */

data class PalabraOcr(val texto: String, val izq: Int, val arriba: Int, val der: Int, val abajo: Int) {
    val cx: Int get() = (izq + der) / 2
    val cy: Int get() = (arriba + abajo) / 2
    val alto: Int get() = (abajo - arriba).coerceAtLeast(1)
}

class LineaOcr(val palabras: List<PalabraOcr>) {
    val texto: String get() = palabras.joinToString(" ") { it.texto }
    val izq: Int get() = palabras.minOf { it.izq }
    val der: Int get() = palabras.maxOf { it.der }
    val arriba: Int get() = palabras.minOf { it.arriba }
    val abajo: Int get() = palabras.maxOf { it.abajo }
    val cx: Int get() = (izq + der) / 2
    val cy: Int get() = (arriba + abajo) / 2
}

/** Lo que se pudo leer de las capturas de "Resumen del Gestor" (cada campo es null si no se leyó). */
data class LecturaVelocidad(
    val plan: Double? = null,        // Meta Plan
    val cobrado: Double? = null,     // Total Cobrado
    val cuPase: Int? = null,         // Cuentas de Pase (Número de clientes, 6 a 7 semanas)
    val capitalPase: Double? = null, // $ Capital (Suma del saldo capital)
    val reque36: Double? = null,     // Requerido Pisado  <- "Requerido Gestionado" (3 a 6 semanas)
    val monto36: Double? = null      // Requerido Cobrado <- "Cobranza" (3 a 6 semanas)
) {
    val vacia: Boolean get() = plan == null && cobrado == null && cuPase == null && capitalPase == null && reque36 == null && monto36 == null
    val camposLeidos: List<String> get() = listOfNotNull(
        "Meta Plan".takeIf { plan != null }, "Total Cobrado".takeIf { cobrado != null },
        "Cuentas de Pase".takeIf { cuPase != null }, "\$ Capital".takeIf { capitalPase != null },
        "Requerido Pisado".takeIf { reque36 != null }, "Requerido Cobrado".takeIf { monto36 != null }
    )

    /** Combina dos capturas: lo que trae la segunda tiene prioridad si ambas leen el mismo campo. */
    fun fusionar(otra: LecturaVelocidad?): LecturaVelocidad = if (otra == null) this else LecturaVelocidad(
        plan = otra.plan ?: plan, cobrado = otra.cobrado ?: cobrado, cuPase = otra.cuPase ?: cuPase,
        capitalPase = otra.capitalPase ?: capitalPase, reque36 = otra.reque36 ?: reque36, monto36 = otra.monto36 ?: monto36
    )
}

/** Comisión: 4 renglones en orden (1 a 2, 3, 4 a 6, 7 a 9); cada valor es null si no se leyó. */
data class LecturaComision(val momento: List<Double?>, val indirecta: List<Double?>, val renglonesEncontrados: Int) {
    val vacia: Boolean get() = momento.all { it == null } && indirecta.all { it == null }
    companion object { val VACIA = LecturaComision(List(4) { null }, List(4) { null }, 0) }
}

private class Monto(val valor: Double, val caja: PalabraOcr)

// ------------------------------------------------------------------------------------------ utilidades

internal fun normaliza(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

private fun mediana(v: List<Int>): Int = if (v.isEmpty()) 20 else v.sorted()[v.size / 2]

internal fun agruparLineas(palabras: List<PalabraOcr>): List<LineaOcr> {
    if (palabras.isEmpty()) return emptyList()
    val tol = (mediana(palabras.map { it.alto }) * 0.6).coerceAtLeast(4.0)
    val grupos = mutableListOf<MutableList<PalabraOcr>>()
    for (p in palabras.sortedBy { it.cy }) {
        val ultimo = grupos.lastOrNull()
        if (ultimo != null && abs(p.cy - ultimo.map { it.cy }.average()) <= tol) ultimo.add(p) else grupos.add(mutableListOf(p))
    }
    return grupos.map { g -> LineaOcr(g.sortedBy { it.izq }) }.sortedBy { it.cy }
}

private val RE_MONTO = Regex("""^\$(\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?$""")

/** "$4,550" -> 4550.0. Exige el signo $ (así un "12" suelto, un porcentaje o una hora no se toman por dinero). */
internal fun parseMonto(crudo: String): Double? {
    var t = crudo.trim().replace(" ", "").trimEnd('.', ',', ')', ';', ':')
    if (!t.startsWith("$")) return null
    t = "$" + t.drop(1).map { when (it) { 'O', 'o' -> '0'; 'l', 'I' -> '1'; else -> it } }.joinToString("")
    val m = RE_MONTO.matchEntire(t) ?: return null
    return m.groupValues[1].replace(",", "").toDoubleOrNull()
}

private fun montosEn(linea: LineaOcr): List<Monto> {
    val out = mutableListOf<Monto>()
    val p = linea.palabras
    var i = 0
    while (i < p.size) {
        val w = p[i]
        var consumidas = 1
        if (w.texto.trim() == "$" && i + 1 < p.size) { // "$" separado del número
            val sig = p[i + 1]
            val v = parseMonto("$" + sig.texto)
            if (v != null) {
                out.add(Monto(v, PalabraOcr(w.texto + sig.texto, w.izq, minOf(w.arriba, sig.arriba), sig.der, maxOf(w.abajo, sig.abajo))))
                consumidas = 2
            }
        }
        if (consumidas == 1) {
            val v = parseMonto(w.texto)
            if (v != null) out.add(Monto(v, w))
        }
        i += consumidas
    }
    return out
}

private fun entero(texto: String): Int? {
    val t = texto.trim()
    if (!Regex("^[0-9OoQ]{1,4}$").matches(t)) return null
    return t.map { if (it == 'O' || it == 'o' || it == 'Q') '0' else it }.joinToString("").toIntOrNull()
}

private fun cercano(cx: Int, centros: List<Pair<String, Int>>): String? = centros.minByOrNull { abs(it.second - cx) }?.first

// ------------------------------------------------------------------------------- Resumen del Gestor

private fun List<PalabraOcr>.centroPromedio(): Int? = map { it.cx }.average().takeIf { !it.isNaN() }?.toInt()

fun leerResumenGestor(palabras: List<PalabraOcr>): LecturaVelocidad {
    if (palabras.isEmpty()) return LecturaVelocidad()
    val lineas = agruparLineas(palabras)
    val hMed = mediana(palabras.map { it.alto })
    val montos = lineas.flatMap { montosEn(it) }
    fun norm(p: PalabraOcr) = normaliza(p.texto)

    val titPase = lineas.firstOrNull { val n = normaliza(it.texto); n.contains("pase") && n.contains("cliente") }
    val titEfec = lineas.firstOrNull { val n = normaliza(it.texto); n.contains("efectividad") && n.contains("cobranza") }

    // 1) Arriba: "$4,550 de $37,518" (pantalla Cobranza del gestor) o las dos tarjetas Cobranza | Plan.
    var cobrado: Double? = null
    var plan: Double? = null
    for (l in lineas) {
        val m = montosEn(l)
        if (m.size >= 2 && normaliza(l.texto).contains(" de ")) { cobrado = m[0].valor; plan = m[1].valor; break }
    }
    if (cobrado == null || plan == null) {
        val limite = listOfNotNull(titPase?.arriba, titEfec?.arriba).minOrNull() ?: Int.MAX_VALUE
        fun valorBajo(etiqueta: String): Double? {
            val lab = palabras.firstOrNull { norm(it) == etiqueta && it.abajo < limite } ?: return null
            return montos.filter {
                it.caja.arriba >= lab.abajo - hMed / 2 && it.caja.arriba - lab.abajo <= 15 * hMed &&
                    abs(it.caja.cx - lab.cx) <= maxOf(lab.der - lab.izq, 6 * hMed)
            }.minByOrNull { it.caja.arriba }?.valor
        }
        if (cobrado == null) cobrado = valorBajo("cobranza")
        if (plan == null) plan = valorBajo("plan")
    }

    // 2) "Pase de clientes": Número de clientes | Suma del saldo capital (columnas por posición de sus encabezados).
    var cuPase: Int? = null
    var capitalPase: Double? = null
    if (titPase != null) {
        val fin = titEfec?.takeIf { it.arriba > titPase.arriba }?.arriba ?: Int.MAX_VALUE
        val region = palabras.filter { it.arriba >= titPase.abajo && it.abajo <= fin }
        val encIzq = region.filter { val n = norm(it); n == "numero" || n == "clientes" || n == "cliente" }
        val encDer = region.filter { val n = norm(it); n == "suma" || n == "saldo" || n == "capital" }
        val yEnc = (encIzq + encDer).maxOfOrNull { it.abajo } ?: (titPase.abajo + 3 * hMed)
        val cxIzq = encIzq.centroPromedio()
        val cxDer = encDer.centroPromedio()
        val debajo = region.filter { it.arriba >= yEnc - hMed / 2 }
        val montosReg = agruparLineas(debajo).flatMap { montosEn(it) }
        capitalPase = (if (cxDer != null) montosReg.minByOrNull { abs(it.caja.cx - cxDer) } else montosReg.maxByOrNull { it.caja.cx })?.valor
        val enteros = debajo.mapNotNull { w -> entero(w.texto)?.let { w to it } }
        cuPase = (if (cxIzq != null) enteros.minByOrNull { abs(it.first.cx - cxIzq) } else enteros.minByOrNull { it.first.cx })?.second
    }

    // 3) "Efectividad de cobranza 3 a 6 semanas": Cobranza | Requerido Gestionado | Efectividad.
    var monto36: Double? = null
    var reque36: Double? = null
    if (titEfec != null) {
        val region = palabras.filter { it.arriba >= titEfec.abajo }
        val encCob = region.filter { norm(it) == "cobranza" }
        val encReq = region.filter { val n = norm(it); n == "requerido" || n == "gestionado" }
        val encEfe = region.filter { norm(it) == "efectividad" }
        val yEnc = (encCob + encReq + encEfe).maxOfOrNull { it.abajo } ?: (titEfec.abajo + 4 * hMed)
        val debajo = region.filter { it.arriba >= yEnc - hMed / 2 }
        val montosReg = agruparLineas(debajo).flatMap { montosEn(it) }.sortedBy { it.caja.cx }
        val centros = buildList {
            encCob.centroPromedio()?.let { add("cob" to it) }
            encReq.centroPromedio()?.let { add("req" to it) }
            encEfe.centroPromedio()?.let { add("efe" to it) }
        }
        if (centros.size >= 2) {
            for (m in montosReg) when (cercano(m.caja.cx, centros)) {
                "cob" -> if (monto36 == null) monto36 = m.valor
                "req" -> if (reque36 == null) reque36 = m.valor
            }
        } else if (montosReg.size >= 2) { monto36 = montosReg[0].valor; reque36 = montosReg[1].valor }
    }
    return LecturaVelocidad(plan = plan, cobrado = cobrado, cuPase = cuPase, capitalPase = capitalPase, reque36 = reque36, monto36 = monto36)
}

// ------------------------------------------------------------------------------- Cobranza del gestor

private val ETIQUETAS_SEMANAS = listOf(Regex("^[1li|!]a2$"), Regex("^3$"), Regex("^4a6$"), Regex("^7a9$"))

fun leerComisionGestor(palabras: List<PalabraOcr>): LecturaComision {
    if (palabras.isEmpty()) return LecturaComision.VACIA
    val hMed = mediana(palabras.map { it.alto })
    val lineas = agruparLineas(palabras)
    val wMom = palabras.firstOrNull { normaliza(it.texto) == "momento" } ?: return LecturaComision.VACIA
    val wInd = palabras.firstOrNull { normaliza(it.texto) == "indirecta" && abs(it.cy - wMom.cy) <= 3 * hMed } ?: return LecturaComision.VACIA
    val cxMom = wMom.cx
    val cxInd = wInd.cx
    val brecha = (cxInd - cxMom).coerceAtLeast(10)
    val tol = (brecha * 0.4).toInt()
    val yEnc = maxOf(wMom.abajo, wInd.abajo)

    val montos = lineas.flatMap { montosEn(it) }.filter { it.caja.arriba > yEnc && (abs(it.caja.cx - cxMom) <= tol || abs(it.caja.cx - cxInd) <= tol) }
    fun etiquetaDe(p: PalabraOcr) = ETIQUETAS_SEMANAS.indexOfFirst { r -> r.matches(p.texto.lowercase().replace(" ", "")) }
    val etiquetas = palabras.filter { it.arriba > yEnc && it.cx < cxMom - brecha / 2 && etiquetaDe(it) >= 0 }

    // Un renglón = todo lo que está a la misma altura (montos y/o etiqueta); así no depende de que se lea la etiqueta.
    class Renglon(var cy: Double, val montos: MutableList<Monto> = mutableListOf(), var etiqueta: Int? = null)
    val renglones = mutableListOf<Renglon>()
    fun renglonPara(cy: Int): Renglon {
        val r = renglones.minByOrNull { abs(it.cy - cy) }
        if (r != null && abs(r.cy - cy) <= hMed * 0.9) return r
        return Renglon(cy.toDouble()).also { renglones.add(it) }
    }
    for (m in montos.sortedBy { it.caja.cy }) renglonPara(m.caja.cy).montos.add(m)
    for (e in etiquetas.sortedBy { it.cy }) renglonPara(e.cy).etiqueta = etiquetaDe(e)
    val ordenados = renglones.sortedBy { it.cy }
    val momento = MutableList<Double?>(4) { null }
    val indirecta = MutableList<Double?>(4) { null }
    fun asignar(idx: Int, r: Renglon) {
        r.montos.filter { abs(it.caja.cx - cxMom) <= tol }.minByOrNull { abs(it.caja.cx - cxMom) }?.let { momento[idx] = it.valor }
        r.montos.filter { abs(it.caja.cx - cxInd) <= tol }.minByOrNull { abs(it.caja.cx - cxInd) }?.let { indirecta[idx] = it.valor }
    }
    if (ordenados.size == 4) ordenados.forEachIndexed { i, r -> asignar(i, r) }
    else ordenados.forEach { r -> r.etiqueta?.takeIf { it in 0..3 }?.let { asignar(it, r) } }
    return LecturaComision(momento, indirecta, ordenados.size)
}
