package com.example.matrizapp

import android.content.Context
import kotlinx.coroutines.flow.first

/** Corrige la confusión típica del OCR entre la "I" (i mayúscula) y la "L" (ej. "Iván" leído como "Lván").
 *  Para cada palabra del nombre: si NO es un nombre/apellido conocido, se prueban los cambios I↔L y, si alguno SÍ es
 *  conocido, se usa ese. Los conocidos salen de una lista de nombres comunes y de los nombres que ya están en Matriz.
 *  Una palabra que ya es conocida (ej. "LUIS") nunca se toca. */
object NombreOcr {
    private val COMUNES = (
        "IVAN IVON IVONNE IRMA IRENE IRIS IRMA ISABEL ISABELA ISAAC ISAIAS ISAURA ISIDRO ISMAEL ISRAEL ITZEL ITZAYANA IGNACIO IGNACIA " +
        "IMELDA INES INGRID IRAIS IRASEMA IRVING ISELA ISMENIA IVETTE IVAN IVANNA IXCHEL IKER IAN ILSE ILIANA ILDA ILARIO IGOR " +
        "LUIS LUISA LUCIA LUCIANO LUCERO LUCILA LUCINA LUCIO LUPE LUPITA LUZ LAURA LAURO LEONOR LEONARDO LEON LEONEL LETICIA LIDIA LILIA LILIANA LINA LIDIA " +
        "LORENA LORENZO LOURDES LOLA LOLITA LUCAS LUNA LIZETH LIZBETH LISSETH LILI LIBRADO LIBORIO LINO LEOPOLDO LEANDRO LEO LAZARO LEYLA LEIDY " +
        "JULIO JULIA JULIAN JULIANA JULIETA EMILIO EMILIA EMILY ELIAS ELIZABETH ELISA ELISEO ELVIA ELVIRA ELOISA ELOY ELENA ELIDA ELIAN ELBA ELDA " +
        "ALICIA ALICE ALIDA ALINA ALEJANDRA ALEJANDRO ALBERTO ALBERTA ALFREDO ALFONSO ALMA ALONDRA ALVARO ALVARADO AGUILAR AGUILERA " +
        "CLAUDIA CLAUDIO CLARA CLARISA CLEMENTE CLEMENTINA CLAUDETTE CLARIBEL CELIA CECILIA CESAR CELESTINO " +
        "GLORIA GILBERTO GUILLERMO GUILLERMINA GISELA GISELLE GABRIELA GABRIEL GRACIELA GRISELDA GREGORIO " +
        "MARIA MARIO MARIANA MARICELA MARISOL MARISELA MARTHA MARTIN MARTINA MARCELA MARCELO MARCO MARCOS MARGARITA MARICRUZ MIGUEL MILAGROS MIRIAM MIREYA MIRELLA " +
        "VIRGINIA VICTOR VICTORIA VIOLETA VIVIANA VICENTE VIRIDIANA ABIGAIL ADRIAN ADRIANA ADELA ADELINA ADOLFO ADRIANO " +
        "FELIPE FELIX FELICIANO FELICITAS FIDEL FILIBERTO FLORENCIA FLORENTINO FLORES FLOR FLORA FERNANDO FERNANDA FRANCISCO FRANCISCA " +
        "NICOLAS NIDIA NILDA NOELIA NOEMI OLIVIA OLIVER OLIVERIO OLGA OLIVARES OLVERA PABLO PAULA PAULINA PAULINO PILAR PLACIDO " +
        "SALVADOR SALOMON SALAZAR SALINAS SOLEDAD SILVIA SILVIO SILVESTRE SIMON SOFIA SOLIS SOTO TOLEDO VILLA VILLALOBOS VILLANUEVA VILLEGAS VILLAREAL " +
        "LOPEZ LOZANO LOZA LOPEZ LARA LARIOS LEAL LEDEZMA LEGORRETA LEMUS LEON LERMA LEYVA LIMON LIMA LIRA LIZARRAGA LLAMAS LOBATO LOERA LOMELI LONGORIA LOREDO LUGO LUJAN LUNA LUNAR " +
        "IBARRA IBANEZ IGLESIAS IGLESIA IRIARTE IRIBE IRIGOYEN ISLAS ITURBE ITURBIDE IZQUIERDO IZAGUIRRE ILLESCAS " +
        "MOLINA MOLINAR VILLEDA PALACIOS PALOMINO PALMA PADILLA PELAEZ PICAZO PIÑA PINEDA PINEDO PLASCENCIA POLANCO SOLANO SOLORZANO " +
        "CARRILLO CASTILLO CASTILLEJOS CEBALLOS CUELLAR GALLEGOS GALVAN GALINDO GALLARDO GALICIA CALDERON CAMPILLO CASILLAS COLIN COLLADO " +
        "DE LA DEL LOS LAS Y"
    ).split(" ").filter { it.isNotBlank() }.map { quitarAcentos(it).uppercase() }.toSet()

    private fun clave(t: String) = quitarAcentos(t).uppercase()

    /** Corrige [nombre] con el vocabulario [conocidos] (claves sin acento y en mayúsculas) + los nombres comunes. */
    fun corregir(nombre: String, conocidos: Set<String>): String {
        return nombre.split(" ").joinToString(" ") { palabra ->
            val up = palabra.uppercase()
            val k = clave(up)
            if (k.isEmpty() || k in COMUNES || k in conocidos) return@joinToString up
            val posiciones = up.indices.filter { up[it] == 'I' || up[it] == 'L' }
            if (posiciones.isEmpty() || posiciones.size > 4) return@joinToString up
            var mejor: String? = null
            var menosCambios = Int.MAX_VALUE
            for (mascara in 1 until (1 shl posiciones.size)) {
                val chars = up.toCharArray()
                var cambios = 0
                for ((b, pos) in posiciones.withIndex()) if ((mascara shr b) and 1 == 1) {
                    chars[pos] = if (chars[pos] == 'I') 'L' else 'I'; cambios++
                }
                val candidata = String(chars)
                val kc = clave(candidata)
                if ((kc in COMUNES || kc in conocidos) && cambios < menosCambios) { mejor = candidata; menosCambios = cambios }
            }
            mejor ?: up
        }
    }

    /** Igual que [corregir] pero sumando al vocabulario los nombres que ya están en Matriz. */
    suspend fun corregir(context: Context, nombre: String): String {
        return try {
            val matriz = (context.applicationContext as MainApplication).container.database.matrizDao().getAllMatriz().first()
            val conocidos = HashSet<String>()
            for (m in matriz) m.nombre.split(" ").forEach { t -> clave(t).takeIf { it.length >= 2 }?.let { conocidos.add(it) } }
            corregir(nombre, conocidos)
        } catch (_: Exception) { corregir(nombre, emptySet()) }
    }
}
