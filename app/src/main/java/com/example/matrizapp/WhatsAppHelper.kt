package com.example.matrizapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** WhatsApp: el titular (TT) se escribe por WhatsApp BUSINESS y las referencias por WhatsApp normal; el SMS es un botón
 *  aparte para todos. Toque normal = la app que corresponde; toque largo = la otra app (por si ese contacto está en la otra). */
object WhatsAppHelper {
    const val PKG_NORMAL = "com.whatsapp"
    const val PKG_BUSINESS = "com.whatsapp.w4b"

    /** Número en formato internacional para WhatsApp (52 + 10 dígitos), o null si no es un número válido.
     *  Limpia los prefijos de México: 044/045, 01, +52, 521 y ceros iniciales. Un número que empieza en 0 o 1 no existe
     *  en México: ahí WhatsApp responde "no está registrado" aunque la persona sí tenga WhatsApp. */
    fun normalizar(raw: String?): String? {
        val texto = raw.orEmpty().trim()
        var d = texto.filter { it.isDigit() }
        if (d.isEmpty()) return null
        val conMas = texto.startsWith("+")
        if (d.startsWith("00")) d = d.drop(2)
        if (d.startsWith("044") || d.startsWith("045")) d = d.drop(3)
        else if (d.startsWith("01") && d.length == 12) d = d.drop(2)
        if (d.length == 13 && d.startsWith("521")) d = d.drop(3)
        else if (d.length == 12 && d.startsWith("52")) d = d.drop(2)
        // Número extranjero escrito con +código (que no sea México): se respeta tal cual.
        if (conMas && !(raw.orEmpty().filter { it.isDigit() }.startsWith("52")) && d.length in 8..15) return raw.orEmpty().filter { it.isDigit() }
        if (d.length != 10 || d[0] !in '2'..'9') return null
        return "52$d"
    }

    private fun urlWhatsApp(numero: String, texto: String? = null) =
        Uri.parse("https://api.whatsapp.com/send?phone=$numero" + (texto?.takeIf { it.isNotBlank() }?.let { "&text=" + Uri.encode(it) } ?: ""))

    /** Intent para abrir el chat en la app [paquete] (para botones de notificación). */
    fun intentPara(numeroRaw: String?, paquete: String): Intent? {
        val num = normalizar(numeroRaw) ?: return null
        return Intent(Intent.ACTION_VIEW, urlWhatsApp(num)).setPackage(paquete).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Abre el chat. [negocio] = app preferida; [alterno] = true usa la otra. Si la app preferida no está instalada
     *  prueba la otra. Si el número no es válido, lo avisa en vez de abrir WhatsApp con un número imposible. */
    fun abrir(context: Context, numeroRaw: String?, negocio: Boolean, alterno: Boolean = false, texto: String? = null) {
        val num = normalizar(numeroRaw)
        if (num == null) {
            Toast.makeText(context, "Número no válido para WhatsApp: ${numeroRaw.orEmpty().ifBlank { "vacío" }}. Debe tener 10 dígitos (no empezar en 0 ni 1).", Toast.LENGTH_LONG).show()
            return
        }
        val usarNegocio = negocio != alterno
        val orden = if (usarNegocio) listOf(PKG_BUSINESS, PKG_NORMAL) else listOf(PKG_NORMAL, PKG_BUSINESS)
        for (pkg in orden) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, urlWhatsApp(num, texto)).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) { /* prueba la otra */ }
        }
        try { context.startActivity(Intent(Intent.ACTION_VIEW, urlWhatsApp(num, texto))) }
        catch (_: Exception) { Toast.makeText(context, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show() }
    }
}

/** Botón de WhatsApp: verde con una "B" para Business (titular), verde normal para referencias. Toque largo = la otra app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotonWhatsApp(numero: String?, negocio: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val context = LocalContext.current
    Box(
        modifier = modifier.size(34.dp).clip(CircleShape).combinedClickable(
            onClick = { WhatsAppHelper.abrir(context, numero, negocio) },
            onLongClick = { WhatsAppHelper.abrir(context, numero, negocio, alterno = true) }
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Chat,
            contentDescription = if (negocio) "WhatsApp Business" else "WhatsApp",
            tint = if (negocio) Color(0xFF0B8F6A) else Color(0xFF25D366),
            modifier = Modifier.alpha(alpha)
        )
        if (negocio) {
            Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(Color(0xFF075E54)), contentAlignment = Alignment.Center) {
                Text("B", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
