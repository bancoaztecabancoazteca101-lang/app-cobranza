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

/** [linea] = id de la línea que detectó el OCR (-1 si no se sabe); sirve para medir la inclinación de una foto. */
data class PalabraOcr(val texto: String, val izq: Int, val arriba: Int, val der: Int, val abajo: Int, val linea: Int = -1) {
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
    val monto36: Double? = null,     // Requerido Cobrado <- "Cobranza" (3 a 6 semanas)
    val alcance: Double? = null,     // "Alcance de plan" en % (solo para comprobar que Plan y Cobranza cuadran)
    val cuPaseDeducido: Boolean = false // true si no se leyó el número pero $ Capital = 0 obliga a que sean 0 cuentas
) {
    val vacia: Boolean get() = plan == null && cobrado == null && cuPase == null && capitalPase == null && reque36 == null && monto36 == null && alcance == null
    val camposLeidos: List<String> get() = listOfNotNull(
        "Meta Plan".takeIf { plan != null }, "Total Cobrado".takeIf { cobrado != null },
        "Cuentas de Pase".takeIf { cuPase != null }, "\$ Capital".takeIf { capitalPase != null },
        "Requerido Pisado".takeIf { reque36 != null }, "Requerido Cobrado".takeIf { monto36 != null },
        "Alcance".takeIf { alcance != null }
    )

    /** Combina dos capturas: lo que trae la segunda tiene prioridad si ambas leen el mismo campo. */
    fun fusionar(otra: LecturaVelocidad?): LecturaVelocidad = if (otra == null) this else LecturaVelocidad(
        plan = otra.plan ?: plan, cobrado = otra.cobrado ?: cobrado, cuPase = otra.cuPase ?: cuPase,
        capitalPase = otra.capitalPase ?: capitalPase, reque36 = otra.reque36 ?: reque36, monto36 = otra.monto36 ?: monto36,
        alcance = otra.alcance ?: alcance,
        cuPaseDeducido = if (otra.cuPase != null) otra.cuPaseDeducido else cuPaseDeducido
    )
}

/**
 * Comisión: 4 renglones en orden (1 a 2, 3, 4 a 6, 7 a 9); cada valor es null si no se leyó.
 * [total] es la 3.ª columna de la captura (Total = Al momento + Indirecta) y sirve para comprobar y completar.
 */
data class LecturaComision(
    val momento: List<Double?>,
    val indirecta: List<Double?>,
    val renglonesEncontrados: Int,
    val total: List<Double?> = List(4) { null },
    val momentoDeducido: List<Boolean> = List(4) { false },
    val indirectaDeducida: List<Boolean> = List(4) { false }
) {
    val vacia: Boolean get() = momento.all { it == null } && indirecta.all { it == null }
    companion object { val VACIA = LecturaComision(List(4) { null }, List(4) { null }, 0) }
}

private class Monto(val valor: Double, val caja: PalabraOcr)

// ------------------------------------------------------------------------------------------ utilidades

internal fun normaliza(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

private fun mediana(v: List<Int>): Int = if (v.isEmpty()) 20 else v.sorted()[v.size / 2]

/**
 * Una FOTO tomada con cámara a otra pantalla casi nunca sale derecha: el texto queda ligeramente girado y las
 * columnas/renglones dejan de alinearse. Se mide el giro con las líneas del propio OCR (primer vs. último
 * elemento de cada línea) y se endereza antes de interpretar. En una captura de pantalla no cambia nada.
 */
fun corregirInclinacion(palabras: List<PalabraOcr>): List<PalabraOcr> {
    if (palabras.size < 4) return palabras
    val hMed = mediana(palabras.map { it.alto })
    val angulos = palabras.filter { it.linea >= 0 }.groupBy { it.linea }.values.mapNotNull { g ->
        val o = g.sortedBy { it.cx }
        val dx = (o.last().cx - o.first().cx).toDouble()
        if (o.size >= 2 && dx >= 4 * hMed) Math.atan2((o.last().cy - o.first().cy).toDouble(), dx) else null
    }
    if (angulos.size < 2) return palabras
    val theta = angulos.sorted()[angulos.size / 2]
    if (abs(theta) < Math.toRadians(0.4) || abs(theta) > Math.toRadians(15.0)) return palabras
    val cx0 = palabras.map { it.cx }.average()
    val cy0 = palabras.map { it.cy }.average()
    val c = Math.cos(theta)
    val s = Math.sin(theta)
    return palabras.map { p ->
        val dx = p.cx - cx0
        val dy = p.cy - cy0
        val nx = cx0 + dx * c + dy * s
        val ny = cy0 - dx * s + dy * c
        val mw = (p.der - p.izq) / 2.0
        val mh = (p.abajo - p.arriba) / 2.0
        PalabraOcr(p.texto, Math.round(nx - mw).toInt(), Math.round(ny - mh).toInt(), Math.round(nx + mw).toInt(), Math.round(ny + mh).toInt(), p.linea)
    }
}

/** Texto tal como lo vio el OCR, de arriba abajo (para poder revisar qué leyó cuando un dato sale mal). */
fun textoDiagnostico(palabras: List<PalabraOcr>): String =
    agruparLineas(corregirInclinacion(palabras)).joinToString("\n") { l -> "y${l.cy}: ${l.texto}" }

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

fun leerResumenGestor(palabrasOcr: List<PalabraOcr>): LecturaVelocidad {
    if (palabrasOcr.isEmpty()) return LecturaVelocidad()
    val palabras = corregirInclinacion(palabrasOcr)
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
        // Tarjeta "Plan de cobranza": dos cajas en la MISMA fila, Cobranza a la izquierda y Plan a la derecha.
        // Se exige la etiqueta "Plan" encima de la caja derecha para no confundirla con la tabla de Efectividad
        // ni con la palabra "Plan" del título de la tarjeta ("Plan de cobranza").
        for (l in lineas.filter { it.abajo < limite }) {
            val m = montosEn(l).sortedBy { it.caja.cx }
            if (m.size != 2) continue
            val der = m[1].caja
            val etiquetaPlan = palabras.any {
                norm(it) == "plan" && it.abajo <= der.arriba && der.arriba - it.abajo <= 8 * hMed && abs(it.cx - der.cx) <= maxOf(der.der - der.izq, 4 * hMed)
            }
            val etiquetaOtra = palabras.any {
                val n = norm(it); (n == "requerido" || n == "gestionado" || n == "efectividad") && it.abajo <= der.arriba && der.arriba - it.abajo <= 8 * hMed
            }
            if (etiquetaPlan || !etiquetaOtra) { cobrado = m[0].valor; plan = m[1].valor; break }
        }
    }
    if (cobrado == null || plan == null) {
        val limite = listOfNotNull(titPase?.arriba, titEfec?.arriba).minOrNull() ?: Int.MAX_VALUE
        fun valorBajo(etiqueta: String): Double? {
            val lab = palabras.firstOrNull { norm(it) == etiqueta && it.abajo < limite && montos.any { m -> m.caja.arriba >= it.abajo - hMed / 2 && m.caja.arriba - it.abajo <= 8 * hMed && abs(m.caja.cx - it.cx) <= maxOf(it.der - it.izq, 4 * hMed) } } ?: return null
            return montos.filter {
                it.caja.arriba >= lab.abajo - hMed / 2 && it.caja.arriba - lab.abajo <= 8 * hMed &&
                    abs(it.caja.cx - lab.cx) <= maxOf(lab.der - lab.izq, 4 * hMed)
            }.minByOrNull { it.caja.arriba }?.valor
        }
        if (cobrado == null) cobrado = valorBajo("cobranza")
        if (plan == null) plan = valorBajo("plan")
    }

    // 1b) "Alcance de plan 12.1%": solo sirve para comprobar que Plan y Cobranza se leyeron bien.
    var alcance: Double? = null
    run {
        val limite = listOfNotNull(titPase?.arriba, titEfec?.arriba).minOrNull() ?: Int.MAX_VALUE
        val et = palabras.firstOrNull { norm(it) == "alcance" && it.abajo < limite }
        if (et != null) {
            val porc = palabras.filter { it.abajo < limite && it.arriba >= et.arriba - hMed && it.arriba - et.arriba <= 14 * hMed }
                .mapNotNull { w -> Regex("^(\\d{1,3}(?:[.,]\\d{1,2})?)%$").matchEntire(w.texto.trim())?.let { w to it.groupValues[1].replace(',', '.').toDouble() } }
            alcance = porc.minByOrNull { (w, _) -> abs(w.cy - et.cy) + abs(w.cx - et.cx) / 4 }?.second
        }
    }

    // 2) "Pase de clientes": Número de clientes | Suma del saldo capital (columnas por posición de sus encabezados).
    var cuPase: Int? = null
    var cuPaseDeducido = false
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
        val capMonto = if (cxDer != null) montosReg.minByOrNull { abs(it.caja.cx - cxDer) } else montosReg.maxByOrNull { it.caja.cx }
        capitalPase = capMonto?.valor
        val enteros = debajo.mapNotNull { w -> entero(w.texto)?.let { w to it } }
        val anchoCol = if (cxIzq != null && cxDer != null) abs(cxDer - cxIzq) * 0.4 else Double.MAX_VALUE
        cuPase = when {
            capMonto != null -> enteros.filter {
                abs(it.first.cy - capMonto.caja.cy) <= hMed * 1.2 && it.first.cx < capMonto.caja.cx &&
                    (cxIzq == null || abs(it.first.cx - cxIzq) <= anchoCol)
            }.minByOrNull { if (cxIzq != null) abs(it.first.cx - cxIzq) else it.first.cx }?.second
            cxIzq != null -> enteros.filter { it.first.arriba - yEnc <= 6 * hMed && abs(it.first.cx - cxIzq) <= anchoCol }
                .minByOrNull { abs(it.first.cx - cxIzq) }?.second
            else -> null
        }
        // Un "0" suelto se pierde fácil en el OCR; pero con $ Capital = 0 no puede haber cuentas de Pase.
        if (cuPase == null && capitalPase == 0.0) { cuPase = 0; cuPaseDeducido = true }
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
    return LecturaVelocidad(plan = plan, cobrado = cobrado, cuPase = cuPase, capitalPase = capitalPase, reque36 = reque36, monto36 = monto36, alcance = alcance, cuPaseDeducido = cuPaseDeducido)
}

// ------------------------------------------------------------------------------- Cobranza del gestor

private val ETIQUETAS_SEMANAS = listOf(Regex("^[1li|!]a2$"), Regex("^3$"), Regex("^4a6$"), Regex("^7a9$"))

/** "$0" mal leído por el OCR ("0)", "O", "$O", "S0", "(O).") — dentro de la tabla cuenta como 0. */
private val RE_CERO_SUCIO = Regex("^[(\\[]?[" + "$" + "S]?[0OoQD][)\\].,]*$")

fun leerComisionGestor(palabrasOcr: List<PalabraOcr>): LecturaComision {
    if (palabrasOcr.isEmpty()) return LecturaComision.VACIA
    val palabras = corregirInclinacion(palabrasOcr)
    val hMed = mediana(palabras.map { it.alto })
    val lineas = agruparLineas(palabras)
    val wMom = palabras.firstOrNull { normaliza(it.texto) == "momento" } ?: return LecturaComision.VACIA
    val wInd = palabras.firstOrNull { normaliza(it.texto) == "indirecta" && abs(it.cy - wMom.cy) <= 3 * hMed } ?: return LecturaComision.VACIA
    val wTot = palabras.firstOrNull { normaliza(it.texto) == "total" && abs(it.cy - wMom.cy) <= 3 * hMed }
    val cxInd = wInd.cx
    val cxTot = wTot?.cx
    // El encabezado "Al momento" son dos palabras: el centro de la columna es el de ambas juntas.
    val wAl = palabras.firstOrNull { normaliza(it.texto) == "al" && abs(it.cy - wMom.cy) <= hMed && it.der <= wMom.izq + hMed }
    val cxMom = if (wAl != null) (wAl.izq + wMom.der) / 2 else wMom.cx
    val brecha = (cxInd - cxMom).coerceAtLeast(10)
    val tol = (brecha * 0.4).toInt()
    val yEnc = listOfNotNull(wMom.abajo, wInd.abajo, wTot?.abajo).max()
    val columnas = listOfNotNull(cxMom, cxInd, cxTot)
    fun enColumna(x: Int) = columnas.any { abs(x - it) <= tol }

    val montos = lineas.flatMap { montosEn(it) }.filter { it.caja.arriba > yEnc && enColumna(it.caja.cx) }.toMutableList()
    // Un "$0" mal leído se pierde fácil: dentro del alto de la tabla se acepta como 0 lo que luzca a cero.
    val ultimoMonto = montos.maxOfOrNull { it.caja.cy }
    val limiteTabla = if (ultimoMonto != null) ultimoMonto + (4 * hMed).coerceAtLeast(hMed * 3) else yEnc
    for (w in palabras) {
        if (w.arriba > yEnc && w.cy <= limiteTabla && enColumna(w.cx) && RE_CERO_SUCIO.matches(w.texto.trim()) && montos.none { it.caja === w }) {
            if (montos.none { abs(it.caja.cx - w.cx) < hMed * 2 && abs(it.caja.cy - w.cy) < hMed }) montos.add(Monto(0.0, w))
        }
    }
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
    val total = MutableList<Double?>(4) { null }
    fun asignar(idx: Int, r: Renglon) {
        r.montos.filter { abs(it.caja.cx - cxMom) <= tol }.minByOrNull { abs(it.caja.cx - cxMom) }?.let { momento[idx] = it.valor }
        r.montos.filter { abs(it.caja.cx - cxInd) <= tol }.minByOrNull { abs(it.caja.cx - cxInd) }?.let { indirecta[idx] = it.valor }
        if (cxTot != null) r.montos.filter { abs(it.caja.cx - cxTot) <= tol }.minByOrNull { abs(it.caja.cx - cxTot) }?.let { total[idx] = it.valor }
    }
    if (ordenados.size == 4) ordenados.forEachIndexed { i, r -> asignar(i, r) }
    else ordenados.forEach { r -> r.etiqueta?.takeIf { it in 0..3 }?.let { asignar(it, r) } }

    // Total = Al momento + Indirecta: si falta uno de los dos, se completa con el Total de la captura.
    val momDed = MutableList(4) { false }
    val indDed = MutableList(4) { false }
    for (i in 0..3) {
        val tt = total[i] ?: continue
        if (momento[i] == null && indirecta[i] != null && tt - indirecta[i]!! >= -0.5) { momento[i] = (tt - indirecta[i]!!).coerceAtLeast(0.0); momDed[i] = true }
        else if (indirecta[i] == null && momento[i] != null && tt - momento[i]!! >= -0.5) { indirecta[i] = (tt - momento[i]!!).coerceAtLeast(0.0); indDed[i] = true }
        else if (momento[i] == null && indirecta[i] == null && tt == 0.0) { momento[i] = 0.0; indirecta[i] = 0.0; momDed[i] = true; indDed[i] = true }
    }
    return LecturaComision(momento, indirecta, ordenados.size, total, momDed, indDed)
}
