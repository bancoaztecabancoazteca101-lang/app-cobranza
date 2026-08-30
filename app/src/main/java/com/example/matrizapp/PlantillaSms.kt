package com.example.matrizapp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Una plantilla de SMS editable desde la app. `tipo` = "TT" (titular, admite {nombre} y {monto})
 * o "REF" (referencias, solo {nombre}). `semana` = 1..5 (semana de atraso). `slot` = 1..6, es la
 * posición fija del mensaje dentro de esa semana/tipo — la rotación va por slot, no por id, así
 * editar el texto de un slot no cambia el orden de rotación.
 */
@Entity(tableName = "plantilla_sms_table", indices = [Index(value = ["tipo", "semana", "slot"], unique = true)])
data class PlantillaSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tipo: String,
    val semana: Int,
    val slot: Int,
    val texto: String
)

@Dao
interface PlantillaSmsDao {
    @Query("SELECT * FROM plantilla_sms_table ORDER BY tipo, semana, slot")
    fun observarTodas(): Flow<List<PlantillaSmsEntity>>

    /** Solo las plantillas con texto no vacío participan en la rotación — así se puede "apagar"
     * una variante en la app dejándola en blanco sin tener que borrar la fila. */
    @Query("SELECT * FROM plantilla_sms_table WHERE tipo = :tipo AND semana = :semana AND texto != '' ORDER BY slot")
    suspend fun obtenerActivasPara(tipo: String, semana: Int): List<PlantillaSmsEntity>

    @Query("UPDATE plantilla_sms_table SET texto = :texto WHERE id = :id")
    suspend fun actualizarTexto(id: Long, texto: String)

    @Insert
    suspend fun insertarTodas(plantillas: List<PlantillaSmsEntity>)

    @Query("SELECT COUNT(*) FROM plantilla_sms_table")
    suspend fun contar(): Int
}

/** Semillas por defecto — se insertan una sola vez (AppContainer revisa `contar() == 0` al
 * arrancar). A partir de ahí todo se edita desde la pantalla "Plantillas de SMS"; este objeto ya
 * no lo lee nadie en tiempo de envío. */
object PlantillasSemillaSms {

    fun defaults(): List<PlantillaSmsEntity> {
        val lista = mutableListOf<PlantillaSmsEntity>()
        textosTT.forEach { (sem, textos) ->
            textos.forEachIndexed { i, texto -> lista.add(PlantillaSmsEntity(tipo = "TT", semana = sem, slot = i + 1, texto = texto)) }
        }
        textosRef.forEach { (sem, textos) ->
            textos.forEachIndexed { i, texto -> lista.add(PlantillaSmsEntity(tipo = "REF", semana = sem, slot = i + 1, texto = texto)) }
        }
        return lista
    }

    private val textosTT: Map<Int, List<String>> = mapOf(
        1 to listOf(
            "Hola {nombre}, Banco Azteca le recuerda su pago pendiente{monto}. Le invitamos a regularizar su situación a la brevedad.",
            "Banco Azteca: {nombre}, tiene un pago pendiente{monto} con nosotros. Le pedimos ponerse al corriente lo antes posible.",
            "Estimado(a) {nombre}, le recordamos que su pago con Banco Azteca sigue pendiente{monto}. Quedamos atentos a su pronto pago.",
            "Hola {nombre}, notamos un pago pendiente{monto} en su cuenta con Banco Azteca. Le invitamos a ponerse al corriente cuando pueda.",
            "Banco Azteca le saluda, {nombre}. Tiene un pago pendiente{monto}; agradecemos pueda regularizarlo pronto.",
            "{nombre}, este es un recordatorio de Banco Azteca sobre su pago pendiente{monto}. Estamos para apoyarle si tiene dudas."
        ),
        2 to listOf(
            "Hola {nombre}, su pago con Banco Azteca sigue pendiente{monto}. Evite recargos, comuníquese hoy mismo.",
            "Banco Azteca: {nombre}, aún no recibimos su pago{monto}. Le sugerimos contactarnos hoy para evitar cargos adicionales.",
            "{nombre}, su cuenta con Banco Azteca continúa sin regularizar{monto}. Por favor comuníquese con nosotros a la brevedad.",
            "Banco Azteca le recuerda, {nombre}, que su pago{monto} sigue pendiente. Contáctenos hoy para evitar cargos extra.",
            "{nombre}, aún tiene un pago pendiente{monto} con Banco Azteca. Le pedimos comunicarse pronto para regularizarlo.",
            "Hola {nombre}, su cuenta requiere atención{monto}. Comuníquese con Banco Azteca hoy mismo para evitar recargos."
        ),
        3 to listOf(
            "Banco Azteca: {nombre}, su cuenta presenta atraso{monto}. Regularice su pago para evitar afectaciones en su historial crediticio.",
            "{nombre}, su adeudo con Banco Azteca sigue sin regularizarse{monto}. Le recordamos que esto puede afectar su historial crediticio.",
            "Banco Azteca le informa que {nombre} mantiene un atraso{monto}. Es importante que se comunique para evitar mayores afectaciones.",
            "{nombre}, su cuenta con Banco Azteca lleva ya un tiempo en atraso{monto}. Regularícela pronto para proteger su historial.",
            "Banco Azteca: el atraso de {nombre}{monto} sigue vigente. Le pedimos comunicarse para evitar consecuencias en su historial.",
            "{nombre}, es momento de regularizar su cuenta{monto} con Banco Azteca. Contáctenos para evitar afectaciones mayores."
        ),
        4 to listOf(
            "Banco Azteca: {nombre}, su adeudo{monto} continúa sin regularizar. De no atenderlo, podría afectarse su historial y generarse gestiones adicionales de cobro.",
            "{nombre}, no hemos recibido respuesta sobre su adeudo{monto}. Le pedimos comunicarse pronto para evitar gestiones de cobro adicionales.",
            "Banco Azteca: su cuenta, {nombre}, sigue vencida{monto} y sin respuesta de su parte. Regularícela para evitar consecuencias mayores.",
            "{nombre}, su adeudo{monto} con Banco Azteca sigue vencido. Necesitamos que se comunique cuanto antes para evitar más gestiones.",
            "Banco Azteca le busca, {nombre}, por su adeudo vencido{monto}. Le pedimos comunicarse hoy para evitar acciones adicionales.",
            "{nombre}, su situación con Banco Azteca{monto} requiere atención inmediata. Comuníquese para evitar gestiones de cobro mayores."
        ),
        5 to listOf(
            "Banco Azteca: {nombre}, su cuenta presenta atraso grave{monto}. Es indispensable que se comunique hoy mismo para evitar medidas de cobranza y visitas en su domicilio.",
            "{nombre}, su adeudo{monto} sigue sin resolverse y es urgente atenderlo. Contáctenos hoy mismo para evitar visitas de cobranza en su domicilio.",
            "Banco Azteca: es indispensable que {nombre} se comunique hoy{monto}. De lo contrario, procederemos con gestiones de cobranza, incluida visita domiciliaria.",
            "{nombre}, su cuenta con Banco Azteca está en atraso grave{monto}. Comuníquese de inmediato para evitar una visita de cobranza.",
            "Banco Azteca: el caso de {nombre}{monto} es urgente. Le pedimos contactarnos hoy mismo antes de proceder con visita domiciliaria.",
            "{nombre}, es su última oportunidad de regularizar{monto} antes de que se programe una visita de cobranza a su domicilio. Comuníquese hoy."
        )
    )

    private val textosRef: Map<Int, List<String>> = mapOf(
        1 to listOf(
            "Banco Azteca le informa que {nombre} mantiene un pago pendiente. Le solicitamos, por favor, comunicarle que se ponga en contacto con nosotros.",
            "Le hablamos de Banco Azteca: {nombre} tiene un pago pendiente con nosotros. Le pedimos su apoyo para que se comunique a la brevedad.",
            "Banco Azteca: le pedimos comunicarle a {nombre} que tiene un pago pendiente y que se ponga en contacto con nosotros.",
            "Banco Azteca le saluda. {nombre} tiene un pago pendiente con nosotros; agradecemos su apoyo para avisarle.",
            "Le escribimos de Banco Azteca respecto a {nombre}, quien tiene un pago pendiente. Le pedimos comunicarle que nos contacte.",
            "Banco Azteca: agradecemos su apoyo para comunicarle a {nombre} que tiene un pago pendiente con nosotros."
        ),
        2 to listOf(
            "Banco Azteca le informa que {nombre} mantiene un pago pendiente sin regularizar. Le pedimos comunicarle que se contacte con nosotros a la brevedad.",
            "Le escribimos de Banco Azteca: {nombre} aún no regulariza su pago. Agradecemos su apoyo para pedirle que nos contacte pronto.",
            "Banco Azteca: {nombre} sigue sin regularizar su pago pendiente. Le solicitamos su apoyo para que se comunique con nosotros.",
            "Banco Azteca le informa que el pago de {nombre} sigue pendiente. Le pedimos su apoyo para que se comunique con nosotros pronto.",
            "Le hablamos de Banco Azteca sobre {nombre}, cuyo pago sigue pendiente. Agradecemos comunicarle que nos contacte.",
            "Banco Azteca: por favor comunique a {nombre} que su pago sigue pendiente y que se ponga en contacto con nosotros."
        ),
        3 to listOf(
            "Banco Azteca: {nombre} mantiene un adeudo pendiente de regularización. Le solicitamos comunicarle la importancia de contactarnos pronto.",
            "Le hablamos de Banco Azteca: el adeudo de {nombre} sigue sin regularizarse. Le pedimos comunicarle que nos contacte cuanto antes.",
            "Banco Azteca: es importante que {nombre} regularice su adeudo. Le pedimos su apoyo para comunicarle que se ponga en contacto con nosotros.",
            "Banco Azteca le informa que {nombre} mantiene un adeudo en atraso. Le solicitamos comunicarle que se comunique pronto.",
            "Le escribimos de Banco Azteca respecto al adeudo de {nombre}. Le pedimos comunicarle la importancia de contactarnos.",
            "Banco Azteca: por favor comunique a {nombre} que su adeudo sigue pendiente de regularización."
        ),
        4 to listOf(
            "Banco Azteca: {nombre} mantiene un adeudo vencido y no ha respondido a nuestras gestiones de cobro. Le solicitamos comunicarle la urgencia de regularizar su situación.",
            "Le escribimos de Banco Azteca: no hemos tenido respuesta de {nombre} sobre su adeudo vencido. Le pedimos comunicarle que es urgente que nos contacte.",
            "Banco Azteca: el adeudo de {nombre} sigue vencido y sin respuesta. Le solicitamos su apoyo para comunicarle la urgencia de regularizarlo.",
            "Banco Azteca le informa que {nombre} tiene un adeudo vencido sin atender. Le pedimos comunicarle la urgencia de contactarnos.",
            "Le hablamos de Banco Azteca: el caso de {nombre} sigue sin resolverse. Le pedimos comunicarle que se comunique urgentemente.",
            "Banco Azteca: por favor comunique a {nombre} la urgencia de regularizar su adeudo vencido."
        ),
        5 to listOf(
            "Banco Azteca: {nombre} mantiene un adeudo grave sin resolver. Le solicitamos, por favor, comunicarle con urgencia que se contacte con nosotros para evitar visitas en su domicilio.",
            "Le hablamos de Banco Azteca: el caso de {nombre} es urgente. Le pedimos comunicarle que se contacte con nosotros hoy mismo para evitar visitas de cobranza.",
            "Banco Azteca: es indispensable que {nombre} se comunique hoy. Le solicitamos su apoyo para evitarle una visita domiciliaria de cobranza.",
            "Banco Azteca le informa que el adeudo de {nombre} es grave. Le pedimos comunicarle que se contacte hoy para evitar una visita de cobranza.",
            "Le escribimos de Banco Azteca: el caso de {nombre} requiere atención inmediata. Le pedimos comunicarle que nos contacte hoy mismo.",
            "Banco Azteca: por favor comunique a {nombre} con urgencia que se ponga en contacto con nosotros para evitar una visita domiciliaria."
        )
    )
}
