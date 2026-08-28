package com.example.matrizapp

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Respaldo para colgar llamadas cuando TelecomManager.endCall() es bloqueado silenciosamente
 * por el fabricante (MIUI en Redmi y similares ignoran endCall() para apps que no son el
 * marcador predeterminado, aunque el permiso ANSWER_PHONE_CALLS esté concedido).
 *
 * Funciona igual que la acción "AutoInput Action" de Tasker: busca en la pantalla actual el
 * botón de colgar/rechazar por su texto o descripción, y le hace clic mediante la Accessibility
 * API. Esto no puede ser bloqueado por MIUI porque, desde el punto de vista del sistema, es un
 * toque real como el que haría el usuario.
 *
 * Requiere que el usuario active el servicio una sola vez en:
 * Ajustes del sistema -> Accesibilidad -> Apps instaladas -> (nombre de la app) -> Activar.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallAccessibilityService"

        // Etiquetas conocidas del botón de colgar/rechazar en distintos marcadores/idiomas.
        private val ETIQUETAS_COLGAR = listOf(
            "colgar", "finalizar llamada", "rechazar", "end call", "decline",
            "hang up", "reject", "finalizar", "terminar llamada"
        )

        @Volatile
        private var instancia: CallAccessibilityService? = null

        fun servicioActivo(): Boolean = instancia != null

        /**
         * Intenta encontrar y tocar el botón de colgar en la pantalla actual.
         * Devuelve true si encontró un nodo candidato y ejecutó el clic (no garantiza que la
         * llamada haya terminado, solo que se intentó el toque).
         */
        fun intentarColgar(): Boolean {
            val servicio = instancia ?: run {
                Log.w(TAG, "Servicio de accesibilidad no activo, no se puede colgar por este medio")
                return false
            }
            return servicio.buscarYClickearBotonColgar()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
        Log.i(TAG, "CallAccessibilityService conectado")
    }

    override fun onInterrupt() {
        Log.w(TAG, "CallAccessibilityService interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instancia === this) instancia = null
    }

    // No necesitamos reaccionar a cada evento; intentarColgar() consulta la ventana activa
    // bajo demanda cuando esperarFinOForzarColgar() detecta que la llamada sigue viva.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    private fun buscarYClickearBotonColgar(): Boolean {
        val raiz = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow es null, no hay ventana activa que inspeccionar")
            return false
        }
        val nodo = buscarNodoColgar(raiz)
        if (nodo == null) {
            Log.w(TAG, "No se encontró ningún nodo candidato a botón de colgar")
            raiz.recycle()
            return false
        }
        val clickeable = encontrarAncestroClickeable(nodo) ?: nodo
        val resultado = clickeable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Clic en botón de colgar ejecutado, resultado=$resultado")
        raiz.recycle()
        return resultado
    }

    /** Recorre el árbol de nodos buscando texto/descripción que coincida con ETIQUETAS_COLGAR. */
    private fun buscarNodoColgar(nodo: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val texto = (nodo.text?.toString() ?: nodo.contentDescription?.toString())?.lowercase()
        if (texto != null && ETIQUETAS_COLGAR.any { etiqueta -> texto.contains(etiqueta) }) {
            return nodo
        }
        for (i in 0 until nodo.childCount) {
            val hijo = nodo.getChild(i) ?: continue
            val encontrado = buscarNodoColgar(hijo)
            if (encontrado != null) return encontrado
        }
        return null
    }

    /** Muchos botones tienen el texto en un TextView/ImageView hijo; sube hasta encontrar el
     * ancestro marcado como clickeable, que es el que realmente responde a ACTION_CLICK. */
    private fun encontrarAncestroClickeable(nodo: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var actual: AccessibilityNodeInfo? = nodo
        var saltos = 0
        while (actual != null && saltos < 6) {
            if (actual.isClickable) return actual
            actual = actual.parent
            saltos++
        }
        return null
    }
}
