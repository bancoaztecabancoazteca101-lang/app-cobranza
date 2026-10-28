package com.example.matrizapp

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Date

/**
 * Matriz con la nube (Cloud Firestore) como ÚNICA fuente de verdad.
 *
 * - Colección `matriz`, un documento por cliente. Campos = los de MatrizEntity (incluidos los que antes eran
 *   100% locales: oferta, ref3/ref4, día de pago, domicilio laboral, días de atraso/apertura, monto cobrado),
 *   más `id`, `version` (+1 en cada escritura), `eliminado` (lápida), `updatedAt` (hora del servidor) y `updatedBy`.
 * - Room queda como caché + cambios pendientes (`isDirty`). Lo que llega de la nube solo se aplica a registros sin
 *   cambios locales pendientes; los pendientes se fusionan campo por campo al subir (ver MatrizNubeMerge).
 * - Los borrados son lápidas (`eliminado = true`): un teléfono atrasado no puede resucitar un registro borrado.
 * - Todo está detrás de [habilitado] (apagado por defecto): con false la app sigue exactamente como antes con Sheets.
 */
class MatrizNube(private val context: Context, private val database: AppDatabase) {

    enum class Tipo { SUBIDO, SIN_CAMBIOS, ELIMINADO_REMOTO }
    class Salida(val tipo: Tipo, val campos: Map<String, Any?>, val version: Long, val conflictos: List<String>)
    class Resumen(val subidos: Int, val adoptados: Int, val borradosLocales: Int)

    private val prefs = context.getSharedPreferences("matriz_nube", Context.MODE_PRIVATE)
    private val matrizDao get() = database.matrizDao()
    private val metaDao get() = database.matrizNubeMetaDao()
    private val firestore: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val coleccion get() = firestore.collection(COLECCION)
    private val mutexAplicar = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var escucha: ListenerRegistration? = null

    var habilitado: Boolean
        get() = prefs.getBoolean(KEY_HABILITADO, false)
        set(v) { prefs.edit().putBoolean(KEY_HABILITADO, v).apply() }

    // ------------------------------------------------------------------ Sesión

    /** Devuelve el correo con el que hay sesión de Firebase, iniciándola en silencio con la cuenta de Google actual. */
    suspend fun asegurarSesion(): String {
        FirebaseAuth.getInstance().currentUser?.let { return it.email ?: it.uid }
        val webId = webClientId(context)
            ?: throw IllegalStateException("Falta el cliente web de Firebase en google-services.json (registra las huellas SHA y vuelve a descargarlo).")
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SheetsScopes.SPREADSHEETS), Scope(DriveScopes.DRIVE))
            .requestIdToken(webId)
            .build()
        val cuenta = GoogleSignIn.getClient(context, gso).silentSignIn().await()
        val token = cuenta.idToken ?: throw IllegalStateException("Google no entregó el token de identidad.")
        val usuario = FirebaseAuth.getInstance()
            .signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user
            ?: throw IllegalStateException("No se pudo iniciar sesión en Firebase.")
        return usuario.email ?: usuario.uid
    }

    // ------------------------------------------------------------------ Bajar (nube -> Room)

    /** Trae de la nube lo que cambió desde la última vez (o todo si es la primera). Devuelve cuántos documentos procesó. */
    suspend fun traerCambios(): Int = withContext(Dispatchers.IO) {
        asegurarSesion()
        val snap = consultaDesdeMarca().get().await()
        procesar(snap.documents)
    }

    /** Escucha en tiempo real mientras la app está abierta. */
    suspend fun arrancar() {
        if (!habilitado || escucha != null) return
        asegurarSesion()
        escucha = consultaDesdeMarca().addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            val docs = snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { it.document }
            if (docs.isNotEmpty()) scope.launch { runCatching { procesar(docs) } }
        }
    }

    fun detener() { escucha?.remove(); escucha = null }

    private fun consultaDesdeMarca(): Query {
        val marca = prefs.getLong(KEY_MARCA, 0L)
        return if (marca > 0) coleccion.whereGreaterThan("updatedAt", Timestamp(Date(marca - MARGEN_MS))) else coleccion
    }

    private suspend fun procesar(docs: List<DocumentSnapshot>): Int = mutexAplicar.withLock {
        var maxima = prefs.getLong(KEY_MARCA, 0L)
        for (doc in docs) {
            runCatching { aplicarDoc(doc) }
            doc.getTimestamp("updatedAt")?.toDate()?.time?.let { if (it > maxima) maxima = it }
        }
        prefs.edit().putLong(KEY_MARCA, maxima).apply()
        docs.size
    }

    private suspend fun aplicarDoc(doc: DocumentSnapshot) {
        val id = doc.getString("id") ?: return
        val local = matrizDao.getById(id)
        if (doc.getBoolean("eliminado") == true) {
            // La lápida gana siempre: ni una edición local atrasada ni un refresco viejo lo resucitan.
            val altaLocalNueva = local != null && local.isDirty && metaDao.obtener(id) == null
            if (local != null && !altaLocalNueva) matrizDao.deleteById(id)   // un alta local nueva con ese ID resucita al subir
            if (!altaLocalNueva) metaDao.borrar(id)
            return
        }
        val remoto = camposDe(doc)
        val version = doc.getLong("version") ?: 0L
        if (local == null) {
            matrizDao.insertarSiNoExiste(entidadDe(id, remoto))
            metaDao.guardar(MatrizNubeMetaEntity(id, version, aJson(remoto)))
        } else if (!local.isDirty) {
            if (aplicarLocal(id, remoto) > 0) metaDao.guardar(MatrizNubeMetaEntity(id, version, aJson(remoto)))
        }
        // Si tiene cambios locales pendientes se fusiona al subir (la base guardada sigue siendo la anterior).
    }

    private suspend fun aplicarLocal(id: String, c: Map<String, Any?>): Int = matrizDao.aplicarDesdeNube(
        id, c.s("nombre") ?: "", c.s("semana") ?: "", c.s("requisito") ?: "", c.s("numTT") ?: "",
        c.s("ref1") ?: "", c.s("ref2") ?: "", c.s("observaciones"), c.s("estado") ?: "", c.s("ubicacion"),
        c.s("imagenUrl"), c.s("imagenUrl2"), c.l("fecha"), c.s("hora"), c.s("ruta"), c.s("folioP"),
        c.s("descuentoPago"), c.s("descuentoAhorro"), c.s("ref3"), c.s("ref4"), c.s("diaPago"),
        c.s("domicilioLaboral"), c.s("diasAtraso"), c.s("diasApertura"), c.d("montoCobrado")
    )

    // ------------------------------------------------------------------ Subir (Room -> nube)

    /**
     * Sube un registro con cambios pendientes. Las fotos ya deben ser URLs remotas (no content://).
     * Lanza excepción si no hay conexión o la nube rechaza la escritura: el registro sigue pendiente.
     */
    suspend fun subir(item: MatrizEntity): Salida = withContext(Dispatchers.IO) {
        val usuario = asegurarSesion()
        val ref = coleccion.document(docId(item.id))
        val meta = metaDao.obtener(item.id)
        val base = meta?.let { desdeJson(it.baseJson) }
        val local = camposDe(item)
        val salida = firestore.runTransaction(Transaction.Function<Salida> { tx ->
            val snap = tx.get(ref)
            val eliminado = snap.exists() && snap.getBoolean("eliminado") == true
            val versionRemota = snap.getLong("version") ?: 0L
            if (eliminado && meta != null) {
                // Estaba sincronizado y alguien lo borró: la lápida manda sobre esta edición atrasada.
                return@Function Salida(Tipo.ELIMINADO_REMOTO, emptyMap(), versionRemota, emptyList())
            }
            val remoto = if (snap.exists() && !eliminado) camposDe(snap) else null
            val fus = MatrizNubeMerge.fusionar(if (remoto == null) null else base, local, remoto)
            if (remoto != null && !MatrizNubeMerge.hayCambios(remoto, fus.campos)) {
                return@Function Salida(Tipo.SIN_CAMBIOS, remoto, versionRemota, fus.conflictos)
            }
            val nueva = versionRemota + 1
            tx.set(ref, mapaDoc(item.id, fus.campos, nueva, usuario))
            Salida(Tipo.SUBIDO, fus.campos, nueva, fus.conflictos)
        }).await()
        if (salida.tipo == Tipo.ELIMINADO_REMOTO) {
            matrizDao.deleteById(item.id); metaDao.borrar(item.id)
        } else {
            metaDao.guardar(MatrizNubeMetaEntity(item.id, salida.version, aJson(salida.campos)))
        }
        salida
    }

    /** Después de marcar limpio el registro, trae a Room lo que otros teléfonos cambiaron en otros campos. */
    suspend fun reconciliarLocal(id: String, salida: Salida) {
        if (salida.tipo == Tipo.ELIMINADO_REMOTO) return
        mutexAplicar.withLock { aplicarLocal(id, salida.campos) }
    }

    // ------------------------------------------------------------------ Borrar y renombrar

    /** Borra con lápida en la nube y luego en este teléfono. Requiere conexión (igual que hoy con Sheets). */
    suspend fun eliminar(id: String) = withContext(Dispatchers.IO) {
        val usuario = asegurarSesion()
        val ref = coleccion.document(docId(id))
        firestore.runTransaction(Transaction.Function<Boolean> { tx ->
            val snap = tx.get(ref)
            if (snap.exists() && snap.getBoolean("eliminado") != true) {
                tx.update(ref, mapOf(
                    "eliminado" to true,
                    "version" to (snap.getLong("version") ?: 0L) + 1,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "updatedBy" to usuario
                ))
            }
            true
        }).await()
        matrizDao.deleteById(id)
        metaDao.borrar(id)
    }

    /**
     * Cambia el ID en la nube de forma atómica (documento nuevo + lápida del viejo en UNA transacción) y luego en Room.
     * Devuelve false si el ID nuevo ya lo usa otro registro de la nube.
     */
    suspend fun renombrar(idAnterior: String, idNuevo: String): Boolean = withContext(Dispatchers.IO) {
        val usuario = asegurarSesion()
        val refViejo = coleccion.document(docId(idAnterior))
        val refNuevo = coleccion.document(docId(idNuevo))
        class Mov(val ocupado: Boolean, val movido: Boolean, val campos: Map<String, Any?>, val version: Long)
        val mov = firestore.runTransaction(Transaction.Function<Mov> { tx ->
            val viejo = tx.get(refViejo)
            val nuevo = tx.get(refNuevo)
            if (nuevo.exists() && nuevo.getBoolean("eliminado") != true) return@Function Mov(true, false, emptyMap(), 0L)
            if (!viejo.exists() || viejo.getBoolean("eliminado") == true) return@Function Mov(false, false, emptyMap(), 0L)
            val campos = camposDe(viejo)
            val versionNueva = (nuevo.getLong("version") ?: 0L) + 1
            tx.set(refNuevo, mapaDoc(idNuevo, campos, versionNueva, usuario))
            tx.update(refViejo, mapOf(
                "eliminado" to true,
                "renombradoA" to idNuevo,
                "version" to (viejo.getLong("version") ?: 0L) + 1,
                "updatedAt" to FieldValue.serverTimestamp(),
                "updatedBy" to usuario
            ))
            Mov(false, true, campos, versionNueva)
        }).await()
        if (mov.ocupado) return@withContext false
        matrizDao.renameId(idAnterior, idNuevo)   // deja el registro pendiente: subirá los demás cambios del formulario
        metaDao.borrar(idAnterior)
        if (mov.movido) metaDao.guardar(MatrizNubeMetaEntity(idNuevo, mov.version, aJson(mov.campos)))
        true
    }

    // ------------------------------------------------------------------ Activación

    /**
     * Vincula ESTE teléfono a la nube y la activa. Una sola vez por teléfono:
     *  - lo que existe aquí y no en la nube se sube;
     *  - lo que ya está en la nube manda; lo local solo rellena campos que la nube tiene vacíos;
     *  - lo que la nube tiene borrado se quita de aquí.
     * Con 2 teléfonos: actívalo primero en el que tiene los datos completos.
     */
    suspend fun activar(): Resumen = withContext(Dispatchers.IO) {
        val usuario = asegurarSesion()
        val remotos = coleccion.get().await().documents.associateBy { it.getString("id") ?: "" }
        val locales = matrizDao.getTodosParaNube()
        var subidos = 0; var adoptados = 0; var borrados = 0

        val nuevos = locales.filter { remotos[it.id] == null }
        nuevos.chunked(400).forEach { lote ->
            val batch = firestore.batch()
            lote.forEach { m -> batch.set(coleccion.document(docId(m.id)), mapaDoc(m.id, camposDe(m), 1L, usuario)) }
            batch.commit().await()
            lote.forEach { m -> metaDao.guardar(MatrizNubeMetaEntity(m.id, 1L, aJson(camposDe(m)))) }
            subidos += lote.size
        }
        mutexAplicar.withLock {
            for (m in locales) {
                val doc = remotos[m.id] ?: continue
                if (doc.getBoolean("eliminado") == true) { matrizDao.deleteById(m.id); metaDao.borrar(m.id); borrados++; continue }
                val remoto = camposDe(doc)
                val unido = MatrizNubeMerge.vincular(camposDe(m), remoto)
                if (!m.isDirty) {
                    aplicarLocal(m.id, unido)
                    if (MatrizNubeMerge.hayCambios(remoto, unido)) matrizDao.marcarSucio(m.id)  // sube lo que solo existía aquí
                }
                metaDao.guardar(MatrizNubeMetaEntity(m.id, doc.getLong("version") ?: 0L, aJson(remoto)))
                adoptados++
            }
        }
        prefs.edit().putLong(KEY_MARCA, 0L).apply()   // la siguiente bajada trae todo lo demás
        habilitado = true
        traerCambios()
        Resumen(subidos, adoptados, borrados)
    }

    // ------------------------------------------------------------------ Conversión

    private fun docId(id: String): String {
        // Los IDs de documento no pueden llevar "/" ni ser "." o "..".
        val limpio = id.replace("/", "%2F")
        return if (limpio == "." || limpio == "..") "%2E$limpio" else limpio
    }

    private fun camposDe(m: MatrizEntity): Map<String, Any?> = mapOf(
        "nombre" to m.nombre, "semana" to m.semana, "requisito" to m.requisito, "numTT" to m.numTT,
        "ref1" to m.ref1, "ref2" to m.ref2, "observaciones" to m.observaciones, "estado" to m.estado,
        "ubicacion" to m.ubicacion, "imagenUrl" to m.imagenUrl, "imagenUrl2" to m.imagenUrl2, "fecha" to m.fecha,
        "hora" to m.hora, "ruta" to m.ruta, "folioP" to m.folioP, "descuentoPago" to m.descuentoPago,
        "descuentoAhorro" to m.descuentoAhorro, "ref3" to m.ref3, "ref4" to m.ref4, "diaPago" to m.diaPago,
        "domicilioLaboral" to m.domicilioLaboral, "diasAtraso" to m.diasAtraso, "diasApertura" to m.diasApertura,
        "montoCobrado" to m.montoCobrado
    )

    private fun camposDe(doc: DocumentSnapshot): Map<String, Any?> =
        MatrizNubeMerge.CAMPOS.associateWith { doc.get(it) }

    private fun entidadDe(id: String, c: Map<String, Any?>) = MatrizEntity(
        id = id, nombre = c.s("nombre") ?: "", semana = c.s("semana") ?: "", requisito = c.s("requisito") ?: "",
        numTT = c.s("numTT") ?: "", ref1 = c.s("ref1") ?: "", ref2 = c.s("ref2") ?: "",
        observaciones = c.s("observaciones"), estado = c.s("estado") ?: "", ubicacion = c.s("ubicacion"),
        imagenUrl = c.s("imagenUrl"), imagenUrl2 = c.s("imagenUrl2"), fecha = c.l("fecha"), hora = c.s("hora"),
        ruta = c.s("ruta"), folioP = c.s("folioP"), descuentoPago = c.s("descuentoPago"),
        descuentoAhorro = c.s("descuentoAhorro"), ref3 = c.s("ref3"), ref4 = c.s("ref4"), diaPago = c.s("diaPago"),
        domicilioLaboral = c.s("domicilioLaboral"), diasAtraso = c.s("diasAtraso"),
        diasApertura = c.s("diasApertura"), montoCobrado = c.d("montoCobrado")
    )

    private fun mapaDoc(id: String, campos: Map<String, Any?>, version: Long, usuario: String): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(campos)
        m["id"] = id
        m["version"] = version
        m["eliminado"] = false
        m["updatedAt"] = FieldValue.serverTimestamp()
        m["updatedBy"] = usuario
        return m
    }

    private fun aJson(m: Map<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v ?: JSONObject.NULL)
        return o.toString()
    }

    private fun desdeJson(s: String): Map<String, Any?> {
        val o = JSONObject(s)
        val m = HashMap<String, Any?>()
        for (k in o.keys()) m[k] = if (o.isNull(k)) null else o.get(k)
        return m
    }

    private fun Map<String, Any?>.s(k: String): String? = this[k] as? String
    private fun Map<String, Any?>.l(k: String): Long? = (this[k] as? Number)?.toLong()
    private fun Map<String, Any?>.d(k: String): Double? = (this[k] as? Number)?.toDouble()

    companion object {
        const val COLECCION = "matriz"
        private const val KEY_HABILITADO = "habilitado"
        private const val KEY_MARCA = "marca_updated_at"
        private const val MARGEN_MS = 10_000L

        /** Lo asigna AppContainer al arrancar; los ViewModels lo consultan para saber si Matriz va por la nube. */
        @Volatile var instance: MatrizNube? = null

        /** ID del cliente web de Firebase (lo genera google-services.json); null si aún no está en el archivo. */
        fun webClientId(context: Context): String? {
            val res = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            return if (res == 0) null else context.getString(res)
        }
    }
}
