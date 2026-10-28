# BITÁCORA DE CONTINUIDAD — FUENTE ÚNICA DE VERDAD Y SINCRONIZACIÓN GLOBAL

Actualizada: 27 de octubre de 2026 — continuidad técnica y estado de CI  
Repositorio: `bancoaztecabancoazteca101-lang/app-cobranza`  
Rama de trabajo: `chatgpt/sincronizacion-integridad-nube`  
PR: https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/pull/122  
Regla: no modificar `main`, no fusionar el PR sin revisión y pruebas.

## 1. Objetivo definitivo confirmado por el usuario

Toda la información operativa de Matriz debe ser compartida y sincronizada para todos los dispositivos autorizados. No debe existir información operativa aislada por teléfono como fuente independiente. La nube será la única fuente de verdad; los dispositivos recibirán de ella los datos y enviarán sus cambios. Room podrá conservar caché y cambios pendientes para trabajar sin conexión, pero deberá reconciliarse con la nube.

La estrategia se actualiza por decisión del usuario: dejar de adaptar indefinidamente las hojas antiguas y construir una base central nueva para la operación de Matriz. La nube será la fuente única de verdad. Google Sheets dejará de ser una fuente operativa; se creará un archivo NUEVO de Sheets como destino de exportación/reportes y compatibilidad para los scripts que se confirme que siguen siendo necesarios. La app exportará desde la base central hacia ese archivo nuevo. El archivo antiguo no se borrará al inicio: se conservará como respaldo hasta identificar dependencias, migrar datos necesarios y probar la nueva arquitectura. No se debe confundir exportar a una hoja nueva con tener sincronización global.

Este objetivo aplica a TODAS las pantallas, módulos, hojas, tablas, campos y operaciones de alta, edición, renombre y borrado. La auditoría no debe limitarse a los módulos que hoy ya sincronizan.

## 2. Estado de rama verificado durante esta sesión

- Rama encontrada: `chatgpt/sincronizacion-integridad-nube`.
- Comparación GitHub consultada: 22 commits por delante de `main`, 0 detrás, antes del nuevo cambio de esta sesión.
- PR #122 continúa siendo la ruta de revisión; no se fusionó ni se tocó `main`.
- Commit de esta sesión: `9537eb953155c918b0d9b63e7338c0a2032eeb36` — “Aislar errores entre módulos de sincronización”.
- Archivo cambiado: `app/src/main/java/com/example/matrizapp/SyncWorker.kt`.
- Estado de compilación del nuevo commit: PENDIENTE. La consulta de ejecuciones de GitHub Actions para este SHA no devolvió una ejecución; no declarar compilado hasta comprobarlo.
- Pruebas funcionales en dos teléfonos: PENDIENTES.

## 3. Cambio aplicado en esta sesión

En `SyncWorker.doWork()`, cada módulo (Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha) ahora se intenta dentro de su propio manejo de excepciones. Un error de un módulo queda registrado y no impide intentar los módulos siguientes en ese ciclo. Si uno o más módulos fallan, el Worker devuelve `Result.retry()` para conservar reintentos; si todos terminan, devuelve `Result.success()`. También se intenta programar la sincronización de imágenes aunque un módulo haya fallado.

Esto corrige el bloqueo en cascada entre módulos, pero NO resuelve conflictos de datos ni convierte Sheets en la fuente única de verdad. Debe compilarse y probarse antes de marcarlo como verificado. Los errores individuales dentro de un módulo aún pueden detener el procesamiento restante de ese módulo; la cola y cada operación requieren su propia estrategia de reintento/diagnóstico.

## 4. Hechos de código confirmados y riesgos

### Sincronización actual observada

En `SyncWorker.kt`:
- El orden actual de módulos es Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha.
- Matriz escribe campos en Sheets columna por columna usando una copia local completa y, si no encuentra el ID remoto, agrega una fila. Esto puede sobrescribir cambios concurrentes de otros teléfonos o recrear una fila borrada si el estado local quedó obsoleto.
- Pase procesa operaciones pendientes de renombre/borrado y sube filas sucias; el flujo observado no contiene descarga completa de Pase en `syncPase()`. Confirmar si existe otro mecanismo de descarga antes de cerrar el hallazgo.
- Filtro Fecha conserva el cambio como pendiente si no encuentra su ID remoto.
- Ruta IA reemplaza el contenido de la hoja de ruta completa cuando detecta registros sucios; requiere control de versión/propietario/conflicto antes de considerarlo seguro para edición concurrente.
- `SheetsRepository.updateSheetCell()` escribe una celda individual; el flujo actual no proporciona una transacción global de varios campos de un registro.

### Entidades/datos que requieren auditoría de alcance global

En `Entities.kt` y `AppDatabase.kt` se observan, entre otras, entidades de Matriz, tickets de pago, visitas de mapa, Pase, Solicitud, Filtro Fecha, Filtrar, Control, horarios, logs de contacto, plantillas SMS, configuración de automatización, reglas semanales, Ruta IA, filtros de Ruta IA, canales de pago, contactos extra, Velocidad/Penalización, Comisión, Bolsa Gerencia, cartucho del día y Semana 6.

Algunos comentarios de código describen expresamente como locales las tablas de Velocidad/Penalización, Comisión, Bolsa Gerencia, visitas de mapa, cartucho del día y Semana 6. Bajo el objetivo nuevo del usuario, esto no se acepta automáticamente como diseño final: cada una debe clasificarse como compartida o calculada/local de interfaz, y si contiene datos operativos introducidos por el usuario debe diseñarse cómo guardarla en la nube y distribuirla a los demás dispositivos. No migrar a ciegas campos derivados, cachés, permisos o datos temporales; documentar la decisión por entidad/campo y confirmar que no se pierde información.

Canal de pago actualmente se describe como nutrido desde una hoja de catálogo; hay que confirmar cómo se actualiza esa hoja y su fuente real. Los scripts de llamadas/SMS quedan fuera de alcance porque el usuario confirmó que la app ya realiza esas funciones; no reabrir esa auditoría salvo petición expresa.

## 5. Prioridades técnicas

### P0 — Inventario integral y mapa de datos
1. Enumerar todas las pantallas/módulos, entidades Room, DAOs, hojas Google Sheets, columnas, repositorios, Workers, importaciones/exportaciones y operaciones CRUD.
2. Para cada dato/campo, documentar: dónde se crea, dónde se edita, ID estable, fuente actual, si sube, si baja, si es compartido, comportamiento offline, conflicto, borrado, confirmación remota y pantallas consumidoras.
3. Identificar datos que hoy viven solo en Room, se crean independientemente en cada teléfono, o no tienen descarga desde la fuente compartida.
4. No dar por sincronizado un módulo porque tenga un botón “Descargar”, un Worker o una hoja asociada: verificar el flujo real de subida y bajada.

### P1 — Integridad de sincronización actual
1. Compilar y probar el aislamiento de errores agregado a SyncWorker.
2. Revisar cómo informar y recuperar operaciones pendientes que fallan repetidamente; evitar que una operación ambigua permanezca indefinidamente sin visibilidad.
3. Resolver la identidad compartida de Pase y la posible duplicación al copiar Matriz en más de un teléfono; usar ID determinista/estable basado en la identidad funcional, solo después de confirmar la regla de negocio.
4. Completar y verificar renombres de Matriz y Filtro Fecha, incluida operación sin conexión y colisiones.
5. Evitar que borrados confirmados reaparezcan en otro teléfono; diseñar tombstones/versiones o mecanismo equivalente.
6. Evitar sobrescrituras concurrentes por escrituras de fila completa; definir actualización por campo/versionado/conflicto.
7. Añadir pruebas de migración Room 37→38 y las nuevas migraciones futuras, sin pérdida de datos.

### P2 — Base central nueva y archivo nuevo de exportación
1. Comparar Firestore y Supabase contra el inventario real de consultas, relaciones, permisos, modo offline, costos, auditoría y exportación. La viabilidad técnica es alta, pero la elección del proveedor queda abierta hasta cerrar ese análisis.
2. Prototipo aislado con datos sintéticos; no conectar todavía datos productivos ni credenciales de producción.
3. Diseñar esquema central a partir de entidades/reglas reales de Matriz, no copiando automáticamente la estructura antigua de Sheets.
4. Definir IDs globales, control de versiones, outbox, idempotencia, tombstones, confirmación del servidor, permisos y resolución de conflictos.
5. Crear un archivo NUEVO de Google Sheets como salida controlada de la base central. Definir pestañas, columnas y frecuencia de exportación desde los datos reales y las necesidades verificadas de reportes/scripts.
6. Migrar un módulo piloto y validar alta/edición/borrado online y offline en al menos dos dispositivos; comprobar que la exportación no escribe de vuelta a la base central.
7. Migrar progresivamente todas las entidades operativas compartidas; Room será caché/outbox, y el archivo nuevo de Sheets será una proyección/exportación, no una segunda fuente de verdad.
8. Confirmar qué scripts siguen siendo necesarios y adaptarlos al nuevo archivo solo después de verificar su uso real. Los scripts de llamadas/SMS continúan fuera de alcance porque la app ya realiza esas funciones.
9. Conservar las hojas antiguas como respaldo durante la validación. Retirar sus dependencias del código solo cuando el backend, la migración y las exportaciones hayan pasado las pruebas de aceptación.

## 6. Pruebas de aceptación obligatorias

- Teléfono A crea/edita/renombra/borra; teléfono B recibe el resultado sin duplicados.
- Teléfono B realiza el cambio equivalente y A lo recibe.
- Dos teléfonos editan campos distintos y el sistema no pierde cambios silenciosamente.
- Dos teléfonos editan el mismo campo: conflicto detectado y resuelto mediante una regla explícita.
- Edición offline → pendiente persistente → reconexión → confirmación de nube → recepción en el otro teléfono.
- Borrado offline/online no reaparece por sincronización de datos obsoletos.
- Un error de Pase o Filtro Fecha no impide intentar Matriz, Ruta IA y Solicitud.
- Operaciones repetidas son idempotentes.
- Instalación/actualización con datos previos conserva los datos de Room.
- Pruebas de cada hoja y de cada entidad clasificada como compartida.

Un build verde solo demuestra compilación/empaquetado, no sincronización funcional. No declarar completado sin evidencias registradas.

## 7. Evaluación de viabilidad y decisión de estrategia (23-10-2026)

**Conclusión:** la estrategia es viable técnicamente y resulta más coherente con la meta de independizar Matriz de las hojas antiguas. Requiere migración por etapas; no es seguro ni realista borrar las hojas actuales y sustituirlas de golpe sin inventario y pruebas.

### Decisión de proveedor (23-10-2026): Firebase Cloud Firestore

Se selecciona **Firebase Cloud Firestore como primera opción para el backend central**, porque el proyecto es Android/Kotlin, el usuario ya dispone de cuenta Firebase y Firestore ofrece SDK Android, sincronización en tiempo real, caché y escrituras offline, consultas, transacciones/escrituras por lotes e integración con Firebase Authentication y Security Rules. Documentación oficial: [Firestore](https://firebase.google.com/docs/firestore), [persistencia offline](https://firebase.google.com/docs/firestore/manage-data/enable-offline), [transacciones y lotes](https://firebase.google.com/docs/firestore/manage-data/transactions), [reglas de seguridad](https://firebase.google.com/docs/rules) y [precios](https://firebase.google.com/docs/firestore/pricing).

La selección es una decisión de arquitectura inicial, no prueba de que Firebase ya esté conectado ni configurado en este repositorio. Antes de datos productivos se debe verificar el proyecto Firebase real, región, autenticación, permisos, reglas cerradas por defecto, App Check cuando corresponda, cuotas/costos y estrategia de respaldo. No incluir credenciales ni secretos en el repositorio.

Firestore tiene un matiz crítico: su comportamiento offline resuelve caché y sincronización básica, pero los cambios concurrentes sobre el mismo documento pueden aplicar last-write-wins. Por tanto, **no se delegará la integridad de Matriz solo al SDK**: se definirán IDs globales, campos de auditoría/versionado, reglas de conflicto, borrados/tombstones, outbox persistente para operaciones de negocio y confirmación remota. Las transacciones requieren conexión y no deben ser el único mecanismo para operaciones que se originan offline.

Supabase no se elige como opción inicial: su modelo SQL puede convenir si el inventario demuestra necesidad fuerte de relaciones/consultas analíticas complejas, pero agregaría otra plataforma y no ofrece una ventaja suficiente demostrada frente a aprovechar la cuenta y ecosistema Firebase existentes. Reabrir la decisión solo si el inventario, prototipo o costos reales muestran una limitación importante de Firestore.

### Por qué es viable
- El código ya separa parte del acceso a datos mediante entidades Room, DAOs, `SheetsRepository` y `SyncWorker`; esto proporciona puntos de intervención para introducir una capa de repositorio/backend, aunque no demuestra que la arquitectura actual ya esté preparada para nube.
- Existe un conjunto identificable de entidades operativas en Room. Algunas ya se sincronizan parcialmente, mientras otras (por ejemplo Semana 6, tickets, visitas de mapa/cartucho y varios módulos de captura) muestran persistencia local en el código revisado. Esas brechas pueden migrarse de forma gradual.
- Sheets puede tratarse como salida de datos desde el backend nuevo. Esto reduce el acoplamiento futuro entre reglas de negocio y la estructura heredada de hojas.

### Condiciones y riesgos que impiden prometer una migración inmediata
- Aún falta completar el inventario de todas las pantallas, entidades, columnas, flujos de lectura/escritura, catálogos, informes y scripts realmente activos.
- No se ha elegido proveedor de nube ni se han configurado proyecto, autenticación, permisos, reglas de seguridad, copias de seguridad ni costos.
- No está demostrada la migración completa de los datos actuales de Sheets y Room al esquema nuevo, ni la deduplicación entre teléfonos.
- No se han probado aún de extremo a extremo dos teléfonos, edición concurrente, borrados, reintentos, modo sin conexión, recuperación ni exportación a un Sheets nuevo.
- La exportación a Sheets debe ser unidireccional desde la base central para no crear una segunda fuente de verdad. Si un script modifica datos operativos, deberá identificarse explícitamente como integración de entrada controlada; no se asumirá que es solo exportación.

### Orden de ejecución acordado
1. Terminar mapa de dependencias y datos reales.
2. Comparar proveedor y arquitectura usando ese mapa; elegir con evidencia, no por preferencia previa.
3. Definir modelo de datos, identidad, permisos, sincronización offline, conflictos y estrategia de migración.
4. Crear backend y archivo nuevo de Sheets en entorno de prueba.
5. Migrar un módulo piloto con prueba de dos teléfonos y exportación.
6. Expandir por módulos, conservar respaldo y comprobar integridad.
7. Retirar del código las dependencias de hojas antiguas y solo después considerar archivar/eliminar archivos antiguos.

## 8. Próximo paso exacto

Continuar el inventario P0 desde la rama real, recorriendo todas las entidades/DAOs, ViewModels, pantallas, `SheetsRepository`, Workers y flujos de importación/exportación. Para cada entidad, documentar si es operativa compartida, configuración por dispositivo, caché, dato derivado o catálogo; dónde se crea/edita/borra; ID; lectura/escritura; subida/bajada; y pantallas consumidoras. Luego seleccionar el módulo piloto con el menor riesgo y una regla de negocio verificable.

Ruta objetivo actualizada: **base central nueva en la nube (única fuente de verdad) → todos los teléfonos autorizados → exportación unidireccional a un archivo nuevo de Google Sheets para reportes/integraciones verificadas → retirada controlada de dependencias antiguas.**

## 9. Verificación directa de Firebase en la rama `chatgpt/sincronizacion-integridad-nube` — 2026-10-23

Inspección directa de `app/build.gradle`, `build.gradle`, `settings.gradle`, `app/google-services.json`, `app/src/main/AndroidManifest.xml`, `AppDatabase.kt`, `SyncWorker.kt` y `SheetsRepository.kt`.

### Hallazgos confirmados

- **Sí existe configuración Firebase para Android en el repositorio:** `app/google-services.json` contiene un proyecto Firebase identificado como `app-matriz` y el paquete Android `com.example.matrizapp`. Esto confirma que el archivo de configuración está presente en esta rama; no confirma por sí solo que los servicios estén habilitados correctamente en la consola, que las reglas sean seguras o que la app pueda autenticar y leer/escribir datos.
- **El plugin de Google Services ya está aplicado** en `app/build.gradle` y declarado en `build.gradle`; no hace falta agregarlo de nuevo sin una razón técnica.
- **Firebase BoM y Firebase Cloud Messaging ya están en dependencias** (`firebase-bom:34.18.0`, `firebase-messaging`). El manifiesto registra `.MatrizFirebaseMessagingService`. Esto es uso de mensajería, no una implementación de base de datos Firestore.
- **No se encontró una dependencia `firebase-firestore` ni uso de `FirebaseFirestore` en la búsqueda de código realizada.** Por tanto, Firestore todavía no está integrado como base operativa en la rama examinada.
- Room sigue en versión 38 y contiene las entidades enumeradas en la sección de inventario. No se debe subir la versión ni modificar migraciones hasta diseñar y probar los cambios necesarios.
- `SyncWorker.kt` sigue enviando cambios operativos a Google Sheets. En `syncMatriz()`, si no encuentra el ID remoto, llama a `appendRow()`; eso puede recrear en Sheets un registro que fue eliminado remotamente o cuyo resultado de consulta fue incorrecto. También actualiza múltiples celdas individualmente, por lo que una escritura parcial o concurrente puede dejar campos mezclados.
- `syncFiltroFecha()` ahora conserva pendiente un registro cuyo ID no encuentra, evitando marcarlo limpio sin confirmación.
- `SheetsRepository.updateSheetCell()` ejecuta una actualización de una celda y devuelve `Unit`; no proporciona por sí mismo una confirmación de integridad de toda una operación de negocio.
- El manifiesto declara permiso de Internet y servicio FCM. Esto no demuestra conectividad con Firestore, autenticación de usuarios ni controles de acceso.
- El archivo `google-services.json` contiene identificadores de configuración y una API key de cliente. No copiar esos valores a bitácoras, respuestas o informes; validar restricciones de API y configuración del proyecto en Firebase Console. No se detectó aquí ninguna credencial privada de servidor, pero esta inspección no sustituye una revisión de secretos completa.

### Decisión operativa derivada

Se mantiene **Firestore como backend elegido**, aprovechando el proyecto configurado existente solo después de validar su estado en Firebase Console. Antes de conectar datos reales, comprobar:
1. Firestore Database creado y región elegida con criterio de latencia/cumplimiento.
2. Authentication: método de acceso real para los teléfonos autorizados y ciclo de alta/baja.
3. Security Rules cerradas por defecto, probadas con usuarios autorizados y no autorizados.
4. App Check cuando sea viable, límites/cuotas/costos y respaldo/recuperación.
5. Separación entre mensajería FCM existente y persistencia Firestore; FCM no sustituye a la base de datos.

No se ha escrito ni leído ningún documento Firestore desde la app, no se han migrado datos, no se han creado reglas ni se ha probado sincronización entre dos teléfonos. No afirmar que Firebase ya funciona como fuente de verdad.

### Próximo paso técnico

Continuar el inventario P0 de entidades, DAOs, ViewModels, pantallas y rutas de lectura/escritura; usar la configuración existente como punto de partida, sin tocar todavía datos productivos. Elegir un módulo piloto solo cuando su identidad, CRUD, consumidores y regla de conflicto estén trazados. No cambiar `main`, no fusionar PR #122 y no retirar las hojas antiguas en esta fase.

## 10. Continuación de auditoría: entidades operativas y riesgos — 2026-10-23

**Rama de trabajo:** `chatgpt/sincronizacion-integridad-nube`. No modificar `main` ni fusionar el PR #122 sin autorización. Esta actualización documenta hallazgos; no implica implementación ni pruebas de compilación.

### Archivos inspeccionados directamente
- `app/src/main/java/com/example/matrizapp/Entities.kt` — blob SHA `ccd9097479375611993b196fd106736357faf1f9`.
- `app/src/main/java/com/example/matrizapp/DAOs.kt` — blob SHA `78574574296743fe92a0e51d64170e3f51c628f7`.
- `app/src/main/java/com/example/matrizapp/AppDatabase.kt` — blob SHA `6a641b399e8ae6071a6e46b4629abc115c5a99f1`; Room `DB_VERSION = 38`.
- `app/src/main/java/com/example/matrizapp/SyncWorker.kt` — blob SHA `cb87f911072c0bfdd228468a28f35f3902aa9b2c`.
- También se revisaron previamente las rutas de Semana 6 en `Sem6ViewModel.kt` y `Sem6Generador.kt`.

### Hallazgos confirmados
1. **Matriz / Sheets:** `SyncWorker.syncMatriz()` recorre registros sucios y busca el ID remoto; cuando no lo encuentra puede usar `appendRow()`. Antes de migrar se debe distinguir registro nuevo de borrado remoto, fallo de consulta o conflicto, para evitar resurrecciones/duplicados. La escritura de celdas individuales en Sheets no es una transacción de negocio entre teléfonos.
2. **Tickets de pago:** `TicketPagoEntity` está descrito en código como registro OCR local de Filtro Fecha. `TicketPagoDao` usa `OnConflictStrategy.IGNORE`, consulta totales por rango y permite operaciones locales; en los flujos `SyncWorker` revisados no aparece sincronización de tickets. Los datos y totales pueden diferir entre dispositivos.
3. **Visitas del mapa:** `VisitaMapaEntity` usa clave primaria compuesta `clave + fechaDia`; el DAO inserta evitando duplicados locales. Esto no garantiza unicidad entre instalaciones. Debe definirse ID global/idempotente y política para correcciones de visita, fecha y cartucho.
4. **Cartucho por día:** `CartuchoDiaEntity` usa `fechaDia` como clave y guarda `cartuchoActivo` en Room. Falta trazar consumidores y confirmar que el cartucho diario debe ser idéntico para todos los teléfonos; la meta general del proyecto sí es compartir los datos operativos.
5. **Semana 6:** `Sem6RegistroEntity` usa clave compuesta `hoja + id`. La ruta revisada importa hojas `Cont-Sem-NN` una sola vez por instalación mediante preferencia local y después opera en Room; `Sem6Generador` crea registros faltantes desde Matriz. La marca de eliminación revisada en `Sem6CacheStore` es local, no una lápida compartida. Otra instalación o regeneración puede no respetar el borrado; no se ha demostrado que sea la única causa de toda reaparición.
6. **Esquema:** la base actual registra 23 entidades y está en Room versión 38. No incrementar versión ni cambiar entidades/migraciones hasta diseñar la sincronización y las pruebas de compatibilidad.

### Alcance de estos resultados
Estos son hallazgos del código inspeccionado, no un mapa CRUD exhaustivo. Falta rastrear pantallas/ViewModels y todos los consumidores de DAO para confirmar cada flujo de alta, lectura, edición y borrado, además de privacidad y reglas de conflicto.

### Decisión de arquitectura y objetivo
Se mantiene Firestore como candidato backend central inicial porque el proyecto ya contiene configuración Firebase y usa Android/Kotlin; no hay integración Firestore confirmada todavía. Firebase Messaging/FCM no equivale a una base de datos Firestore operativa. Room seguirá siendo caché/outbox local, no una fuente independiente de verdad. Sheets queda como puente de exportación/reportes durante la transición y no como segunda autoridad operacional. Apps Script y los scripts de llamadas/SMS quedan fuera del alcance actual, según la decisión del usuario.

### Próxima ruta de trabajo
1. Completar el mapa CRUD real de Matriz, TicketPago, VisitaMapa, CartuchoDia y Semana 6: pantalla/ViewModel → DAO → fuente/lector remoto → borrado.
2. Especificar por entidad ID estable global, idempotencia, versiones/auditoría, tombstones, conflictos, autorización y campos sensibles.
3. Verificar en Firebase Console existencia/ubicación de Firestore, Authentication y reglas seguras; no asumir que la configuración local demuestra que el backend está listo.
4. Elegir un piloto pequeño solo después del mapa CRUD; probar dos teléfonos, ediciones concurrentes, reintentos, borrados y modo sin conexión.
5. Compilar y probar antes de declarar éxito. Migrar progresivamente; mantener Sheets antiguo como respaldo hasta validar integridad y exportaciones.

**Estado al cierre de esta entrada:** auditoría/diseño; sin integración Firestore, sin migración de datos productivos, sin cambios de Room y sin pruebas de dos teléfonos documentadas.

## 11. Seguimiento del mapa CRUD: Matriz, tickets, mapa y Semana 6 — 2026-10-23

**Continuación realizada en la rama de trabajo** `chatgpt/sincronizacion-integridad-nube`. Se leyeron directamente los siguientes archivos de esa rama:

- `MapaScreen.kt` — blob SHA `05e71746d5d9c57b446f59fea4eccefc54599e91`.
- `MatrizViewModel.kt` — blob SHA `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`.
- `FiltroFechaViewModel.kt` — blob SHA `5ece22add07e87257ed845a02b306872cfa636c1`.
- `Sem6ViewModel.kt` — blob SHA `5110af8d9ed70834d67296992662d37bfa775905`.
- `Sem6Generador.kt` — blob SHA `38a9ae9ef7b8126157cc1a65b0372551034aea13`.
- `SyncWorker.kt` — blob SHA `cb87f911072c0bfdd228468a28f35f3902aa9b2c`.
- `DAOs.kt` — blob SHA `78574574296743fe92a0e51d64170e3f51c628f7`.
- `Entities.kt` — blob SHA `ccd9097479375611993b196fd106736357faf1f9`.

### Flujos y riesgos confirmados en esta revisión

1. **Mapa:** `MapaScreen` combina historial guardado con registros actuales de Matriz que caen dentro de la semana y tienen coordenadas. El comentario de código confirma que, si un registro no aparece en el historial, se presenta con cartucho 1. La pantalla actualiza el cartucho diario llamando directamente a `VisitaMapaDao.asegurarCartuchoDia()` y `setCartuchoActivo()`; no se encontró en esta ruta una confirmación remota. Por tanto, el cambio de cartucho no es aún una operación global compartida.
2. **Registro de visitas:** `MatrizViewModel` llama a `registrarRegistrosDeHoy()` al observar Matriz y también a `registrarVisitaHoy()` al guardar cambios relevantes. El DAO ignora duplicados con la clave local `clave + fechaDia` y conserva alrededor de cuatro meses. Esto define parte de la conducta actual que la migración debe preservar; no es suficiente para deduplicar entre teléfonos.
3. **Pago por ticket:** `FiltroFechaViewModel.registrarPagoDesdeTicket()` inserta el ticket OCR localmente, busca duplicados por cliente/monto desde el inicio del día, marca como Pagado el registro Matriz encontrado y registra la visita del mapa. El ticket usa un ID basado en la hora actual y hash del texto OCR; la comprobación de duplicados es una consulta local previa al insert, por lo que dos teléfonos pueden pasar la comprobación al mismo tiempo. En el piloto se necesitará una clave idempotente/operación atómica en servidor, no copiar simplemente esta comprobación local.
4. **Semana 6:** `Sem6ViewModel` elimina la fila en Room y escribe la marca de eliminación en `Sem6CacheStore`; al arrancar, importa las hojas antiguas una sola vez según preferencia local, y observa Matriz para regenerar/completar Semana 6. `Sem6Generador` inserta registros que faltan y actualiza ciertos campos. El borrado y la protección contra regeneración no están coordinados entre teléfonos; la marca local no puede ser la lápida global.
5. **Fuente remota vigente:** el `SyncWorker` revisado sigue utilizando `SheetsRepository` para Matriz/Filtro Fecha/Pase/Solicitud; la ruta actual no contiene persistencia Firestore. No confundir los eventos/avisos locales con sincronización de datos.

### Reglas que deben conservarse al diseñar la migración

- Mantener la regla actual del mapa: un cliente cuenta una vez por día; registrar la visita no debe duplicar el registro de Matriz.
- Conservar el cartucho asignado a visitas históricas; definir explícitamente cómo se resuelve una edición de cartucho concurrente.
- No volver a crear en Semana 6 un registro cuya eliminación global haya sido confirmada.
- Un pago/ticket y su actualización relacionada deben tener IDs idempotentes y resultado verificable; evitar que el mismo ticket sumado en dos teléfonos duplique totales.
- No borrar ni migrar las hojas antiguas hasta tener comparación de datos, reglas de reconciliación y pruebas.

### Siguiente paso inmediato

Terminar de rastrear la ruta de CRUD y persistencia de las entidades restantes, y convertir el mapa completo en un contrato por entidad: identidad global, lectores/escritores, autorización, outbox local, confirmación del servidor, conflictos y tombstones. Después de esa trazabilidad, definir un piloto pequeño de Firestore. Todavía no se ha hecho cambio de código funcional, compilación ni prueba entre dos teléfonos en esta etapa.

## 12. Trazabilidad ampliada: configuración, Ruta IA, reportes y catálogos — 2026-10-23

Se continuó el inventario sobre la rama de trabajo, leyendo directamente los archivos siguientes:

- `ConfiguracionAutomatizacion.kt` — blob SHA `19ee1af3528884aad71998fb4fd6701a89d1872e`.
- `BloqueHorarioViewModel.kt` — blob SHA `e6480a909ddcc12ebd5f6f7fdd8bde94609e78c1`.
- `LlamadaAutomatica.kt` — blob SHA `80355eb837bb266107cb6b4b2387df953f5cf55f`.
- `RutaIAViewModel.kt` — blob SHA `a67f8ed613747697028dd1d627e12f61509ff585`.
- `RutaIA.kt` — blob SHA `e6c815650b5b6f07539b3a4d41648972b6551989`.
- `PenalizacionViewModel.kt` — blob SHA `ccd7c908d94b7f5d8cfc116de76a799eca496499`.
- `ComisionViewModel.kt` — blob SHA `fc55b1619aab6c1d3d2474ba05f36421dcea138e`.
- `BolsaGerenciaViewModel.kt` — blob SHA `f11998f09178f25f8a2ac7742a5695b8952bbe88`.
- `SheetsRepository.kt` — blob SHA `9a625c6a68d64febc5aa24d75243027389ee25bf`.
- Se volvieron a revisar las rutas pertinentes de `DAOs.kt` y `SyncWorker.kt`.

### Hallazgos adicionales

1. **Configuración de automatización:** `ConfiguracionAutomatizacionEntity` mezcla opciones del dispositivo (por ejemplo, `simSeleccionada` y `simSms`, IDs de SIM del teléfono) con ajustes de comportamiento (`ocultarNumero`, pausa entre llamadas, duración máxima). No se debe sincronizar ciegamente la entidad completa entre teléfonos: las SIM pertenecen al dispositivo y los parámetros de comportamiento deben definirse como configuración compartida o individual explícitamente. La automatización es ejecutada por la app; Apps Script de llamadas/SMS sigue fuera de alcance.
2. **Reglas semanales y bloques de horario:** `ReglaSemanaEntity` guarda offsets de contacto y `catchupActivo`; `BloqueHorarioViewModel` edita reglas, horarios y opciones de ejecución. Los horarios/reglas de negocio podrían ser compartidos por el equipo, pero el disparo real, permisos, disponibilidad de SIM y alarmas siguen siendo propios de cada teléfono. Antes de migrar, se requiere distinguir configuración compartida de ejecución local para evitar llamadas/SMS duplicados desde varios teléfonos.
3. **Contacto extra, plantillas y logs:** `LlamadaAutomatica.kt` consulta `ContactoExtraDao`, `PlantillaSmsDao` y `ContactoLogDao` al procesar clientes. Son datos usados por el flujo operativo real, no deben quedar como configuración invisible distinta en cada teléfono. La migración necesita una política compartida de plantillas/contactos y un registro global de intentos/resultados o una asignación exclusiva por tarea que evite que dos teléfonos contacten al mismo cliente a la vez. No cambiar el flujo actual de llamadas/SMS en esta etapa.
4. **Ruta IA:** `RutaIAViewModel` importa una ruta, la guarda en Room, ordena las paradas y usa `reemplazarRutaIAEnSheet()`. Este método limpia el rango `A2:O` y después escribe filas; el reemplazo completo puede perder cambios concurrentes si dos teléfonos editan la ruta. `refreshRutaIA()` descarga desde Sheets cuando no hay cambios locales sucios; si hay cambios locales, la copia local reemplaza la hoja y no descarga. Es una política local-gana que no es suficiente para una fuente global con múltiples escritores. La ruta y sus filtros deben migrarse como conjunto/versionado o documentos con control de concurrencia.
5. **Reportes capturados manualmente:** los ViewModels de Penalización (`VelocidadEntity`), Comisión (`ComisionEntity`) y Bolsa Gerencia (`BolsaGerenciaEntity`) guardan los datos en Room con un registro singleton local. Para que los mismos datos estén disponibles en todos los teléfonos, estas entidades requieren sincronización compartida; no deben asumirse como preferencias personales solo porque actualmente son locales.
6. **Filtrar / Control / catálogos:** los DAOs muestran escrituras locales y `SheetsRepository` refresca desde Sheets datos de Filtrar y Control. El catálogo `CanalPagoEntity` se reemplaza localmente al actualizarse desde la fuente actual. Estas rutas necesitan definir cuál es el origen temporal durante la transición y pasar a Firestore como lectura operativa central; las hojas antiguas no deben quedar como autoridad paralela después de la migración.
7. **Confirmación de sincronización:** los campos `isDirty` y `lastSync` sirven para el flujo actual, pero no son suficientes por sí solos para concurrencia entre teléfonos, borrados o confirmación atómica de cambios relacionados. La nueva capa requiere outbox persistente, clave idempotente, versión/autor de modificación, tombstone y marcar la operación confirmada solo después de la aceptación del servidor.

### Clasificación preliminar para el contrato de sincronización

- **Compartidos, por defecto según la misión:** Matriz, Pase, Solicitud, Filtro Fecha, Filtrar, Control, Ruta IA y filtros de ruta, tickets de pago, visitas/cartucho por día, Semana 6, contactos extra, plantillas, logs de contacto necesarios para evitar repetición, reglas y bloques horarios, y reportes manuales de Penalización/Comisión/Bolsa Gerencia.
- **Por separar entre compartido y específico del dispositivo:** configuración de automatización (SIM, permisos y capacidades del teléfono frente a reglas generales), alarmas/disparadores locales, estado de conectividad, selección de ubicación actual y preferencias puramente visuales.
- **Catálogo externo:** canales de pago. Debe fijarse una fuente de publicación/actualización central y no permitir que cada teléfono mantenga una versión divergente.

Esta clasificación es preliminar: el contrato final debe verificar todos los consumidores y usos de cada DAO, así como los datos sensibles, antes de diseñar las colecciones y reglas.

### Próximo paso

Consolidar este inventario en una matriz entidad por entidad y definir el modelo de datos de Firestore: documentos, IDs estables, relaciones, permisos por usuario/equipo, estrategia de cambios offline, deduplicación, borrados y conflictos. Luego revisar la configuración de Firebase Console. No empezar a escribir datos productivos hasta cerrar esos puntos.

**Estado:** auditoría de flujos ampliada; Firestore sigue sin integración operativa en el código revisado. Esta etapa no cambia código funcional, no actualiza Room, no contiene prueba de compilación ni prueba multi-dispositivo.

## 13. Consolidación del contrato por DAO y operación — 2026-10-23

Se revisaron las definiciones de DAO y las rutas restantes de `SyncWorker.kt`, `AppDatabase.kt`, `DAOs.kt` y `Entities.kt` en la rama. La base sigue en `DB_VERSION = 38`; no se modificó el esquema ni el código funcional.

### Lo que la capa actual permite afirmar

- La cola `PaseSyncOperationEntity` es un precedente útil: `AppDatabase` guarda de forma transaccional la operación pendiente junto con el renombre/borrado local de Pase. Para Firestore habrá que conservar el patrón de operación durable, pero con confirmación remota real y protección contra repetición.
- En Matriz, Pase, Solicitud, Filtro Fecha, Filtrar y Ruta IA existen banderas `isDirty`/métodos de limpieza local. La bandera no representa por sí misma una confirmación global, una versión ni un borrado remoto.
- `SyncWorker` actualmente solo ejecuta sincronización para Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha. No procesa como módulos de subida los tickets, visitas/cartuchos, Semana 6, reportes manuales, reglas/horarios, contactos extra, plantillas SMS ni logs de contacto.
- `ControlDao` y `CanalPagoDao` tienen operaciones locales de reemplazo/borrado; `SheetsRepository.refreshAll()` actualiza algunos datos desde Sheets. Durante la transición hay que definir precedencia explícita para no mezclar silenciosamente una versión remota con cambios locales.
- Ruta IA tiene operaciones locales de eliminar toda la lista y reordenar/actualizar elementos, además de reemplazo completo en Sheets. Una migración segura debe representar la ruta como conjunto/versionado o cambios por documento coordinados; no replicar el borrado global de la hoja como estrategia de concurrencia.
- Los registros de reporte singleton (ID fijo 1) pueden colisionar entre teléfonos porque no llevan propietario, versión ni fecha de modificación comparable. El servidor necesita reglas de actualización y auditoría.
- `VisitaMapaEntity` y `CartuchoDiaEntity` guardan datos que deben reconciliarse entre sí: el cartucho asignado a una visita histórica no debe cambiar solo porque se cambie el cartucho activo para nuevas visitas.
- Semana 6 requiere tombstones compartidos para borrados; borrar únicamente la fila local no basta para impedir su recreación por otro cliente o por el generador.

### Contrato mínimo requerido antes de implementar

Para cada entidad/colección se debe especificar:
1. ID global estable e inmutable, con estrategia de migración desde los IDs actuales.
2. Campos de creación/actualización, versión o mecanismo de concurrencia, autor y marca de tiempo del servidor.
3. Política por tipo de dato: último cambio gana solo si es seguro; en otros casos, transacción, rechazo explícito o resolución manual.
4. Outbox durable en Room para cambios offline; clave idempotente por operación y reintentos seguros.
5. Tombstone para eliminaciones; no interpretar fallo de lectura como confirmación de inexistencia.
6. Confirmación del servidor antes de limpiar la operación pendiente, manteniendo cambios locales si falla la red.
7. Autorización de lectura/escritura por usuario/equipo y clasificación de datos sensibles.
8. Pruebas de dos teléfonos: mismo registro, edición simultánea, duplicado de pago, cambio de cartucho, borrado Semana 6, reintento tras caída y uso sin conexión.

### Secuencia propuesta para la siguiente fase

**No empezar con la migración de todas las entidades a la vez.** Primero cerrar la matriz entidad → pantalla/ViewModel → DAO → escritor/lector remoto; verificar Firebase Console y modelo de acceso; después preparar el piloto en Firestore con un módulo cuya identidad y política de conflicto estén cerradas. La selección del piloto debe considerar riesgo operacional y capacidad de validar los datos, no solo facilidad de programación.

No hay evidencia de que Firestore esté ya habilitado/configurado en consola, ni se ejecutó compilación o prueba entre teléfonos en esta continuación. No afirmar esos resultados. No modificar `main`, no fusionar PR #122 y mantener hojas actuales como puente/respaldo hasta que la validación de integridad sea satisfactoria.


## 14. Continuación tras confirmar Firebase como proveedor — 2026-10-23

El usuario confirma que la duda sobre Drive era exploratoria y que el proyecto continúa con Firebase. Los dispositivos actuales son personales y usan la misma cuenta de Google; el diseño debe cubrir primero este escenario, sin impedir que en el futuro se incorporen usuarios autorizados adicionales. Este dato simplifica el alcance inicial de acceso, pero no reemplaza autenticación/autorización ni las pruebas de sincronización.

### Revisión directa adicional de acceso y contenedor

Se volvieron a leer desde la rama `chatgpt/sincronizacion-integridad-nube`:

- `app/src/main/java/com/example/matrizapp/AppContainer.kt` — blob SHA `5013020b97b687e80fa9835e7de52ecdd7f7c264`.
- `app/src/main/java/com/example/matrizapp/LoginScreen.kt` — blob SHA `5d2b59b95e27a1dc8e1a2d2954d982afd8e1bb2c`.
- `app/src/main/java/com/example/matrizapp/MainActivity.kt` — blob SHA `f4a9608a0ba90f49ef6aa604e0102a680c4cd4d7`.
- `app/build.gradle` — blob SHA `9a5f44092bf87b94b4a3418839002fa4ed9da55e`.

Hallazgos confirmados:
1. `LoginScreen.kt` usa Google Sign-In y solicita scopes de Google Sheets y Drive (`SPREADSHEETS` y `DRIVE`). `hasSignedInAccount()` comprueba esa cuenta y esos scopes. Esto autentica el acceso actual a las APIs de Google usadas por la app, pero **no equivale a iniciar sesión con Firebase Authentication** ni prueba una identidad Firebase.
2. `AppContainer.kt` construye `SheetsRepository` con DAOs Room y un servicio Google Sheets; también expone servicios Drive/backup. En el código inspeccionado, el repositorio operativo remoto continúa siendo Sheets.
3. `app/build.gradle` aplica Google Services y tiene la configuración Android de Firebase existente, pero la inspección anterior no encontró dependencia `firebase-firestore` ni llamadas a `FirebaseFirestore`. Firestore sigue sin estar integrado como base operativa.
4. El usuario aún no ha informado el estado actual de Firestore Database ni del proveedor Google en Firebase Authentication. No asumir que la consola está configurada ni pedirle cambios de seguridad a ciegas.

### Decisión para el siguiente trabajo

No cambiar de proveedor y no comenzar aún una migración de datos reales. Continuar el inventario de CRUD y cerrar el piloto usando datos sintéticos. En paralelo, preparar el diseño de identidad de forma que pueda aprovechar la cuenta Google existente, pero validar expresamente cómo vincular Google Sign-In con Firebase Authentication y cómo autorizar la cuenta antes de cualquier lectura/escritura productiva.

Antes de conectar datos reales:
- comprobar en Firebase Console si Firestore Database existe y su región;
- verificar Google como proveedor de Firebase Authentication;
- definir reglas cerradas por defecto y probar acceso permitido/denegado;
- elegir un identificador de usuario/equipo estable sin codificar que la app solo tendrá un usuario para siempre;
- no modificar las hojas actuales ni introducir cambios de esquema Room hasta cerrar la prueba piloto.

**Estado:** decisión de proveedor confirmada por el usuario; Firebase local configurado parcialmente; Firestore aún no integrado; configuración de consola no verificada por el usuario; no hay compilación ni pruebas entre dispositivos confirmadas en esta continuación.


## 15. Siguiente corte de inventario: datos de Matriz que hoy no viven en Sheets — 2026-10-23

Se releyó `Entities.kt` en la rama de trabajo (blob SHA `ccd9097479375611993b196fd106736357faf1f9`) y se verificó el inicio de `SyncWorker.kt` (blob SHA `cb87f911072c0bfdd228468a28f35f3902aa9b2c`).

### Hallazgo confirmado que afecta el diseño

`MatrizEntity` incluye campos anotados en el propio código como locales y ausentes de la hoja actual: `ref3`, `ref4`, `diaPago`, `domicilioLaboral`, `diasAtraso`, `diasApertura` y `montoCobrado`. El comentario de `montoCobrado` lo relaciona con el ticket de cobranza y `FiltroFechaViewModel.registrarPagoDesdeTicket`. Esto significa que copiar solo las columnas actuales de Sheets a Firestore perdería parte de la información operativa que Matriz ya guarda localmente. El modelo central debe incluir esos campos y cualquier otro campo real identificado en la auditoría, con una migración que preserve los valores locales existentes.

También se confirma que `TicketPagoEntity` está descrita como local y no se puede considerar migrada por el hecho de que el registro Matriz relacionado tenga un estado de pago. El ticket, el monto, la fecha y la actualización del registro relacionado deben conservar su relación y tener tratamiento idempotente.

### Próximo paso de implementación, con barreras de seguridad

1. Seguir el recorrido completo de campos locales de Matriz y tickets desde ViewModel → DAO → pantallas/consultas, para identificar cuáles son editables, calculados o derivados.
2. Cerrar la tabla de contrato del piloto antes de escribir el código Firestore: campos incluidos, ID actual, relaciones, borrado, reintento y confirmación remota.
3. Preferir un piloto con datos sintéticos y una copia aislada de prueba; no conectar producción ni importar datos reales hasta verificar Authentication y reglas.
4. Para desbloquear esa validación de consola, queda pendiente la respuesta del usuario sobre: existencia de Firestore Database y región, Google Authentication habilitado y uso de la misma cuenta Google en los dispositivos. No pedir ni compartir contraseñas, claves privadas ni datos de clientes.
5. No subir la versión de Room ni modificar entidades/migraciones en este paso; todavía no hay prueba de compilación ni de sincronización entre teléfonos documentada.

**Estado:** inventario continúa; este hallazgo se incorpora como requisito de preservación de datos. No hay implementación Firestore ni pruebas nuevas confirmadas.

---

## 8. Continuidad de configuración Firebase desde la consola (25-10-2026)

Esta sección registra el avance posterior a la revisión del 23-10-2026. Si alguna descripción anterior de esta bitácora contradice este estado, prevalece esta sección para el estado de la consola Firebase; las conclusiones de arquitectura y los hallazgos de código anteriores siguen vigentes salvo que se indique lo contrario.

### Proyecto Firebase y app Android — datos descritos por el usuario

- Proyecto visible: `App Matriz`.
- ID de proyecto: `app-matriz`.
- Número de proyecto: `542876060274`.
- Aplicación Android registrada: `Matriz App`.
- Paquete Android registrado: `com.example.matrizapp`.
- App ID: `1:542876060274:android:d354e9f160e0eb2ce390eb`.
- Plan: Spark.
- Base de datos Cloud Firestore `(default)`: creada; aparece vacía/lista para añadir datos.
- Región elegida por el usuario: región de Norteamérica/México, descrita durante la configuración como `northamerica-south1`. Confirmar el identificador exacto en la consola antes de documentar una decisión de residencia definitiva.
- SHA-1/SHA-256 en la configuración de la app Android: lista vacía. No inventar huellas ni asumir que esto por sí solo explica un fallo; primero identificar el certificado de firma real del APK que se distribuye a los teléfonos.
- Reglas de Firestore: el usuario indicó que completó el paso 1 pegando reglas que deniegan toda lectura y escritura (`allow read, write: if false;`). No se ha confirmado expresamente en el chat que se haya pulsado **Publicar**; verificarlo en consola. Mantener el acceso cerrado hasta implementar autenticación y reglas específicas.

### Situación del paso 2 — Authentication

- El trabajo actual es revisar **Firebase Authentication**, sin modificar aún el código Android.
- URL directa de trabajo: https://console.firebase.google.com/project/app-matriz/authentication
- Próxima comprobación: determinar qué pantalla aparece (Comenzar, Método de acceso/proveedores, usuarios u otra) y si el proveedor Google está habilitado.
- En una revisión anterior se reportó Google deshabilitado, pero el estado actual debe confirmarse con el usuario en la consola; no darlo por verificado.
- No deshabilitar el inicio de sesión de Google existente de Matriz, no sustituir `google-services.json` y no registrar huellas SHA inventadas.
- El inicio de sesión actual de la app utiliza Google Sign-In para acceder a Google Sheets/Drive y sus ámbitos; eso no equivale a Firebase Authentication. La integración futura deberá enlazar de forma deliberada la identidad de Google con Firebase Auth y preservar el acceso actual a Sheets/Drive.
- En el código previamente inspeccionado no se encontraron referencias a `FirebaseAuth`, `FirebaseFirestore` ni dependencia `firebase-firestore`. Sí se observó Firebase Messaging/FCM, lo cual no significa que Firestore o Auth ya estén integrados.
- No crear colecciones manualmente ni cargar datos reales todavía. No abrir reglas públicas ni usar modo de prueba. App Check se revisará después y no sustituye autenticación ni reglas.

### Secuencia inmediata acordada

1. Confirmar el estado real de Authentication y del proveedor Google en consola, guiando al usuario paso a paso según el texto que vea; no depender de capturas.
2. Verificar que las reglas cerradas de Firestore quedaron publicadas.
3. Revisar la configuración de autenticación necesaria y la huella SHA solo después de identificar el certificado real del APK instalado/distribuido.
4. Antes de tocar el código, volver a leer el estado actual de la rama y los archivos Android reales. Preparar integración de Firebase Auth/Firestore en una rama de trabajo; no tocar `main` ni fusionar PR #122.
5. Diseñar y probar primero un piloto aislado con datos sintéticos. No conectar datos productivos hasta tener identidad, reglas, permisos, estructura, costos/cuotas y recuperación respaldados por evidencia.
6. Continuar con inventario de todas las entidades/pantallas y luego migración progresiva; Firestore debe ser la fuente única de verdad, Room caché/outbox y Google Sheets una salida/exportación compatible, no una fuente paralela.

### Estado verificado y pendiente al cierre de esta actualización

**Confirmado por el usuario:** proyecto/app registrados, Firestore creado y reglas de denegación total copiadas en la consola.  
**Pendiente de confirmación:** publicación efectiva de las reglas; estado actual de Authentication y proveedor Google; identificación de la firma real del APK.  
**No realizado todavía:** integración de Firebase Auth/Firestore en el código, carga de datos operativos, migración productiva, pruebas de sincronización entre dos teléfonos, compilación de una versión con esta integración y validación de exportación a un nuevo Google Sheets.

### Reglas de continuidad para un chat nuevo

- Leer primero esta bitácora y la rama `chatgpt/sincronizacion-integridad-nube`; no reiniciar la auditoría ni repetir hallazgos ya documentados.
- No asumir acceso a la consola Firebase ni al APK si no está disponible en la sesión; pedir al usuario que describa el texto visible cuando sea necesario.
- Trabajar con código real del repositorio, cambios pequeños y comprobables; documentar archivos, commits, pruebas y resultados reales.
- No modificar `main`, no fusionar PR #122 sin autorización expresa, no afirmar compilación/pruebas sin evidencia.
- Los scripts de llamadas/SMS quedan fuera de alcance: el usuario confirmó que esas funciones ya las realiza la app.

## 9. Verificación de Firebase Authentication y App ID — actualización 2026-10-25

### Aclaración definitiva del App ID

El usuario confirmó que en Firebase Console → Configuración del proyecto → General → Tus aplicaciones aparece **una sola aplicación Android**:

- Nombre: `Matriz App`
- Paquete: `com.example.matrizapp`
- Proyecto: `app-matriz`
- Número de proyecto: `542876060274`
- App ID real: `1:542876060274:android:d334e9f168a8ab2ce890eb`

El `google-services.json` compartido por el usuario se descargó desde esa única ficha y contiene el App ID anterior. El texto alternativo terminado en `d354e9f160e0eb2ce390eb` fue una lectura visual equivocada de una captura de baja resolución; **no es una segunda aplicación ni un ID de Firebase confirmado**. Se cierra esa investigación: no volver a pedir que busque otra app ni seguir tratando el caso como una discrepancia de App ID.

### Comparación con el archivo versionado

Se volvió a leer directamente `app/google-services.json` en esta rama. Blob SHA: `e499626e662e0219996201df46ff451cc7ca188a`.

El archivo versionado coincide con el JSON proporcionado en los campos de proyecto, paquete y App ID. Sin embargo, el JSON versionado actualmente contiene `oauth_client: []` y `other_platform_oauth_client: []`, mientras que el JSON que pegó el usuario incluye un cliente OAuth de `client_type: 3`. Este detalle sí debe revisarse comparando el contenido íntegro antes de reemplazar el archivo; no es evidencia de que exista otra app Android. No copiar IDs manualmente ni modificar el JSON hasta comprobar el diff y el origen exacto del cliente OAuth.

### Estado real del código Android inspeccionado

Archivos releídos en `chatgpt/sincronizacion-integridad-nube`:

- [`app/build.gradle`](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/build.gradle), blob SHA `9a5f44092bf87b94b4a3418839002fa4ed9da55e`.
- [`LoginScreen.kt`](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/LoginScreen.kt), blob SHA `5d2b59b95e27a1dc8e1a2d2954d982afd8e1bb2c`.
- [`app/google-services.json`](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/google-services.json), blob SHA `e499626e662e0219996201df46ff451cc7ca188a`.

Hallazgos:
1. Google Services ya está aplicado; Firebase BoM `34.18.0` y Firebase Messaging están presentes.
2. En las dependencias revisadas no están `firebase-auth` ni `firebase-firestore`; Firestore aún no es la base operativa de esta rama.
3. `LoginScreen.kt` usa Google Sign-In y solicita ámbitos de Google Sheets/Drive. No solicita todavía un ID token ni inicia una sesión de Firebase Authentication. La integración debe preservar los permisos y el flujo actual de Sheets/Drive.
4. Las variantes debug/release/optimizado de esta rama apuntan a `../debug.keystore`. Eso no demuestra por sí solo qué certificado firmó el APK que se distribuye; antes de configurar SHA-1/SHA-256 se debe verificar la firma del APK real.
5. El cliente OAuth `client_type: 3` es un cliente OAuth de tipo web; no sustituye la configuración Android vinculada al certificado de firma.

### Siguiente trabajo acordado

1. Comparar de forma controlada el JSON descargado y el versionado, preservando los valores originales y revisando el diff, en una rama de trabajo; no inventar ni editar IDs a mano.
2. Identificar la firma real del APK instalado/distribuido antes de registrar huellas SHA en Firebase.
3. Preparar integración de Firebase Authentication con el Google Sign-In existente: solicitar el ID token usando el cliente web configurado, crear la credencial de Google y autenticar en Firebase, con estados de carga/errores y sin romper Sheets/Drive.
4. Mantener Firestore sin lecturas ni escrituras operativas hasta que Auth esté integrado y probado. Mantener las reglas `deny-all` publicadas; no usar reglas públicas ni modo de prueba.
5. Ejecutar compilación y pruebas verificables, documentando commit y resultado real. No declarar la integración terminada solo por añadir dependencias.

### Estado al cierre

- App Android única e identidad del proyecto: **confirmadas por el usuario**.
- App ID: **aclarado; no existe una segunda app fantasma**.
- JSON versionado y código de login: **releídos directamente**.
- Integración Firebase Auth/Firestore: **pendiente**.
- Firma del APK real y huellas SHA: **pendientes de verificar**.
- Reglas Firestore: deben permanecer cerradas hasta contar con autenticación y reglas específicas probadas.
- No se ha hecho una nueva compilación ni prueba de login Firebase en esta actualización.

### Reglas de continuidad

Leer esta bitácora y el código actual de la rama antes de continuar. No reiniciar la investigación de una supuesta segunda app. No modificar `main`, no fusionar PR #122 sin autorización, no cambiar reglas a abiertas, no afirmar compilaciones/pruebas sin evidencia y mantener los scripts de llamadas/SMS fuera de alcance salvo petición expresa del usuario.

## 2026-10-25 — Huellas de certificado verificadas en APK publicados

**Evidencia proporcionada y comprobada por el usuario:** las huellas no se dedujeron solo de la configuración Gradle. El usuario obtuvo las huellas de `debug.keystore` en la raíz del repositorio (contraseña `android`) y comprobó las firmas de dos APK publicados con `apksigner`.

- Package: `com.example.matrizapp`
- Certificado: `CN=Diego Cobranza Debug, O=Cobranza, C=MX`
- Alias: `androiddebugkey`
- Vigencia reportada: hasta 2053-12-24
- SHA-1: `BC:F4:55:B9:3B:B3:34:4B:EE:91:BA:CB:69:59:88:F9:36:1D:FB:64`
- SHA-256: `DD:0B:72:C4:FD:53:56:E8:B8:C6:D4:BD:81:CA:7C:23:83:EB:05:C2:6B:CB:3A:62:6D:29:3C:E1:ED:4C:E4:17`
- APK comprobados según el usuario: `app-debug.apk` de la publicación `latest-debug` (main) y `app-optimizado.apk` de la rama `claude/seguimiento-cartuchos`. Ambos presentan las mismas huellas.
- **Límite de la comprobación:** `app-release.apk` no fue revisado; no afirmar que usa este certificado.
- **Uso previsto:** registrar estas huellas en la ficha Firebase Android existente de `com.example.matrizapp`, proyecto `app-matriz`, para preparar Google Sign-In/Firebase Authentication.
- **Estado pendiente:** registrar las huellas en Firebase y verificar el flujo de autenticación real. La rama revisada aún requiere revisar la implementación: `LoginScreen.kt` usa Google Sign-In con scopes de Sheets/Drive, pero no solicita ID token ni autentica con Firebase; en `app/build.gradle` se deben incorporar las dependencias Firebase Auth/Firestore cuando corresponda. No habilitar lecturas/escrituras operativas de Firestore: las reglas deben permanecer `deny-all` durante el prototipo y la migración.
- **Seguridad:** estas son huellas públicas de un certificado de depuración, no una llave de publicación de tienda. Si se publica con otra llave, habrá que registrar las huellas de ese certificado por separado.

Esta entrada registra evidencia comunicada por el usuario; no significa que el asistente haya vuelto a ejecutar `apksigner` sobre esos APK ni que ya se hayan guardado cambios en Firebase.



## 2026-10-26 — Continuidad después de revisar huellas Firebase; reinicio del inventario P0 con código real

### Estado de Firebase comunicado por el usuario
- El usuario confirma que en Firebase quedó guardada **solo una huella SHA-256**; no guardó la SHA-1 y la alerta roja desapareció.
- El valor que el usuario transcribió en esta sesión fue `DD:9B:77:C4:FD:53:66:A6:CB:04:BD:81:60:7C:20:80:E5:C2:6B:0B:9A:62:6D:29:0C:E1:ED:4C:E4:17`. **No está verificado contra el certificado del APK** y difiere de la SHA-256 previamente comprobada por el usuario en los APK (`DD:0B:72:C4:FD:53:56:E8:B8:C6:D4:BD:81:CA:7C:23:83:EB:05:C2:6B:CB:3A:62:6D:29:3C:E1:ED:4C:E4:17`). No reemplazar ni añadir huellas basándose solo en esta transcripción; comparar visualmente con el certificado real cuando se retome la configuración de Google Sign-In.
- La SHA-1 comprobada para los APK `app-debug.apk` y `app-optimizado.apk` sigue siendo `BC:F4:55:B9:3B:B3:34:4B:EE:91:BA:CB:69:59:88:F9:36:1D:FB:64`, pero el usuario no la guardó en Firebase.
- No se modificaron credenciales OAuth ni el archivo `google-services.json` por esta revisión.

### Nueva lectura directa del código en la rama
Rama inspeccionada: `chatgpt/sincronizacion-integridad-nube`.

- `Entities.kt`, blob SHA `ccd9097479375611993b196fd106736357faf1f9`: se confirma que `MatrizEntity` contiene campos locales que no existen en Sheets (`ref3`, `ref4`, `diaPago`, `domicilioLaboral`, `diasAtraso`, `diasApertura`, `montoCobrado`). También existen entidades explícitamente locales para tickets OCR, visitas de mapa y reportes manuales de Velocidad/Penalización, Comisión y Bolsa Gerencia. Estas anotaciones describen el comportamiento actual, no una decisión de mantener esos datos aislados: deben clasificarse para la futura fuente global y evitar pérdida de información.
- `AppDatabase.kt`, blob SHA `6a641b399e8ae6071a6e46b4629abc115c5a99f1`: versión Room confirmada en `38`; incluye 23 entidades en la anotación `@Database`. La migración 37→38 ya está representada en el trabajo anterior de la cola de operaciones de Pase. No cambiar versión ni migraciones hasta diseñar y probar la siguiente modificación.
- `SyncWorker.kt`, blob SHA `cb87f911072c0bfdd228468a28f35f3902aa9b2c`: el aislamiento de errores por módulo está en la rama. La sincronización actual todavía escribe en Google Sheets; esto no equivale a Firestore ni a fuente única de verdad. En Matriz persiste el patrón de actualizar celdas individualmente o añadir una fila cuando no se encuentra el ID remoto; siguen vigentes los riesgos de sobrescritura/recreación documentados arriba.
- `SheetsRepository.kt`, blob SHA `9a625c6a68d64febc5aa24d75243027389ee25bf`: `updateSheetCell()` ejecuta una actualización de una celda y retorna `Unit`; `appendRow()` agrega filas. El repositorio depende directamente de Google Sheets, así que no debe reemplazarse sin mapear primero todos sus consumidores y flujos.
- `LoginScreen.kt`, blob SHA `5d2b59b95e27a1dc8e1a2d2954d982afd8e1bb2c`: el inicio de sesión actual solicita email y scopes de Sheets/Drive mediante Google Sign-In; no solicita ID token ni autentica con Firebase Auth. La integración futura debe preservar esos scopes y el acceso existente.
- `app/build.gradle`, blob SHA `9a5f44092bf87b94b4a3418839002fa4ed9da55e`: Firebase BoM `34.18.0` y Firebase Messaging están presentes; no están declaradas `firebase-auth` ni `firebase-firestore`. El plugin Google Services ya está aplicado. No agregar código de login a ciegas antes de resolver la configuración OAuth y revisar el JSON oficial completo.

### Siguiente ejecución
1. Continuar P0 desde archivos reales: identificar DAOs, repositorios, ViewModels, pantallas y operaciones CRUD por entidad; clasificar cada entidad como operativa compartida, configuración por dispositivo, caché, derivada o catálogo, con evidencia de subida/bajada.
2. Elegir el primer piloto solo después de verificar las dependencias y las operaciones de lectura/escritura reales.
3. Mantener Firestore con reglas `deny-all`; no añadir datos reales ni declarar completada la autenticación, integración, compilación o prueba entre teléfonos sin evidencia.
4. Mantener `main` intacta y PR #122 sin fusionar; continuar únicamente en esta rama. Los scripts de llamadas/SMS siguen fuera de alcance.

**Estado al cierre de esta actualización:** inspección de archivos realizada; no se modificó código de aplicación, no se añadieron dependencias, no se ejecutó compilación y no se probaron dispositivos. Esta entrada documenta la revisión y el siguiente paso, no una implementación terminada.


## 2026-10-26 — Inventario P0 de entidades Room publicado

Se creó `docs/migracion-nube/INVENTARIO_ENTIDADES_NUBE_P0.md` en la rama `chatgpt/sincronizacion-integridad-nube`.

- Commit: `6715298f830200f1a5463031b553f164ed8dc2ec`.
- El inventario registra las 23 entidades declaradas por `AppDatabase.kt` (DB_VERSION 38 en esta rama), la función observada en el código y una clasificación provisional: compartida, configuración, catálogo, proyección o cola local.
- Se dejaron explícitamente pendientes las decisiones que necesitan seguirse hasta los CRUD/consumidores reales; no se supone que toda tabla local se replique sin análisis.
- Riesgos que se preservan: cambios concurrentes, borrados resucitados, acoplamiento de `SheetsRepository`, falta de Firebase Auth/Firestore en el código actual, adjuntos, datos sensibles y necesidad de outbox idempotente.
- Próximo paso: P0-A, mapear por módulo los puntos exactos de alta/edición/borrado/lectura y los caminos de subida/descarga; después redactar contrato de datos y elegir el piloto. No agregar dependencias ni código de Firestore hasta cerrar el mapa del piloto.
- Sin compilación ni pruebas de dispositivos en esta etapa. `main` permanece intacta; PR #122 no se fusionó; Firestore conserva `deny-all`; scripts de llamadas/SMS fuera de alcance.


## 8. Continuación P0 — CRUD, borrados y estado local (26-10-2026)

Inspección adicional de `MatrizViewModel.kt`, `Entities.kt`, `DAOs.kt` y `Sem6ViewModel.kt` en la rama actual:

- **Borrado de Matriz:** `eliminarRegistro()` llama primero al borrado en Sheets y elimina Room solo si el repositorio confirma éxito. Si falla, conserva el registro local. El propio código indica que requiere conexión y no usa una cola offline. La nube necesitará una operación durable de borrado y tombstone o mecanismo equivalente.
- **Visitas del mapa:** `VisitaMapaEntity` declara clave primaria compuesta por `clave` y `fechaDia`; las claves se forman como `M:<id>` o `S6:<id>`. El comentario del modelo dice que este historial es local y no se sincroniza con Sheets. Existe limpieza de registros antiguos (aproximadamente cuatro meses). Si el historial debe compartirse, hay que definir idempotencia y retención global.
- **Cartucho diario:** `CartuchoDiaEntity` se guarda localmente por fecha. Los teléfonos pueden discrepar para el mismo día; confirmar si el valor es global para el equipo o particular de un usuario antes de definir la política de sincronización.
- **Semana 6:** `Sem6RegistroEntity` usa clave compuesta `hoja + id`. `Sem6ViewModel` importa inicialmente registros desde hojas y conserva eliminaciones en `Sem6CacheStore` local. Esa marca no comunica por sí sola el borrado a otros dispositivos. Definir si Semana 6 es proyección de Matriz, entidad editable compartida o combinación con campos de origen explícito.
- **Protección de sincronización de Matriz:** el DAO `markAsClean` compara los campos enviados con los valores actuales antes de limpiar `isDirty`, reduciendo el riesgo de perder una edición local concurrente durante la subida. Sin embargo, las escrituras a Sheets siguen siendo múltiples actualizaciones de celda y no una transacción única del registro.
- **Ruta IA:** el worker reemplaza el contenido completo de la hoja al detectar cambios locales; no se debe trasladar ese patrón a Firestore sin versionado y resolución de conflictos.

### Próxima tarea concreta

Completar el mapa de operaciones por entidad y campo, empezando por:
1. rastrear cambios de ID de Matriz y confirmar todas sus rutas de escritura;
2. definir contrato de borrado offline (Matriz, Pase, Semana 6 y demás entidades compartidas);
3. mapear qué campos de Semana 6 proceden de Matriz y cuáles se editan de forma independiente;
4. decidir alcance del cartucho diario y la retención del historial de visitas;
5. diseñar pruebas de dos teléfonos con datos sintéticos antes de habilitar cualquier lectura/escritura productiva en Firestore.

### Estado de verificación

Lectura estática de código. No se ejecutó Gradle ni pruebas de dispositivo en esta continuación. No afirmar compilación ni sincronización global verificada. Mantener Firestore con reglas `deny-all`, no cambiar migraciones Room sin plan de pruebas y no fusionar PR #122 sin autorización.


## 11. Verificación adicional P0 — versiones publicadas y bloqueo de CI (27 de octubre de 2026)

### Ejecución CI inspeccionada
- Ejecución GitHub Actions: [40511465262](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/actions/runs/40511465262), asociada al PR #122 y al commit `e28a32cc27018f09b4b7961d8f1ddaa5608bcd64`.
- Resultado: `failure`. El log de Gradle muestra respuestas HTTP 403 al descargar dependencias de `repo.maven.apache.org`, entre ellas `jsr305:3.0.2`, `guava:31.1-jre` y `kotlin-stdlib-jdk8:1.8.20-RC2`; termina en `BUILD FAILED` tras 14 segundos.
- Interpretación: el fallo observado es de resolución/descarga de dependencias desde Maven Central en esa ejecución. No es evidencia de que el código Kotlin haya fallado al compilar por un error de fuente. Tampoco permite declarar la rama compilada; el pipeline no quedó verde.
- No se cambió configuración de repositorios ni dependencias automáticamente: primero se debe confirmar si el 403 es transitorio, del entorno/rate limit o una política de red y revisar el bloque de repositorios real. No cambiar versiones de dependencias sin evidencia.

### Referencia publicada con APK
- Release `latest-debug-claude/seguimiento-cartuchos`: [página de release](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/releases/tag/latest-debug-claude/seguimiento-cartuchos).
- El cuerpo de la release indica que fue generada desde el commit `6d05d8b484c3d7d450f1d100e06fa2a441fe40e2`; publica `app-debug.apk`, `app-release.apk` y `app-optimizado.apk`.
- El registro de la release no demuestra qué APK está instalado en cada teléfono. Los binarios no se han inspeccionado para extraer el esquema Room; por tanto, aún no se conoce la versión instalada en los dispositivos.
- El siguiente paso para establecer compatibilidad es obtener y examinar el APK publicado (manifest/clases Room o metadatos equivalentes) y comparar sus migraciones con la rama actual. No inferir la versión instalada a partir del nombre de la etiqueta ni del número de descargas.

### Estado y siguiente acción
- No se añadió código de producción ni una migración Room nueva en este paso.
- No se ejecutaron pruebas de migración ni una compilación exitosa.
- Mantener el PR #122 sin fusionar. Antes de tocar `AppDatabase.kt`, resolver/aislar el bloqueo de CI y establecer evidencia sobre las versiones realmente distribuidas; después crear el fixture válido de esquema 38 y el arnés de pruebas.


## 12. Seguimiento del bloqueo CI y repositorios Gradle (27 de octubre de 2026)

- Inspeccionados los archivos reales `settings.gradle` (blob `128e606e872e86e5a8e67300a1ab4dbe55ea0507`) y `build.gradle` (blob `b5580a14fcd59cf1e69f17150cfbcfe34dee15f1`) de la rama.
- Los repositorios configurados son los helpers oficiales de Gradle `google()`, `mavenCentral()` y `gradlePluginPortal()`; no se encontró una URL Maven Central escrita manualmente ni un mirror personalizado en esos archivos. Por ello no se cambió el endpoint a ciegas.
- La documentación oficial de Sonatype indica que un HTTP 403 puede deberse a bloqueo de tráfico/egress, política de uso o incidencia del CDN; debe investigarse con el error exacto y estado del servicio, no sustituir repositorios sin diagnóstico. Referencia: https://central.sonatype.org/faq/403-error-central/
- Se solicitó volver a ejecutar los jobs fallidos de la ejecución CI `40511465262`. La consulta posterior mostró la ejecución en estado `queued`; el resultado de este reintento queda PENDIENTE y no se afirma que compiló.
- Se intentó acceder al APK de la release publicada para inspección, pero la descarga del binario no se completó en esta sesión. No se declara verificada la versión Room del APK.
- Estado: sin cambios de código de producción, sin cambios en repositorios Gradle, sin migración Room nueva, sin build exitoso confirmado. Mantener PR #122 abierto y `main` intacta.


## 13. Cierre de verificación CI y vigencia de esta bitácora (27 de octubre de 2026)

- Se volvió a consultar la ejecución `40511465262` después de solicitar reintento. GitHub muestra la ejecución como `completed` con conclusión `cancelled`; en la consulta de jobs, el job `build` aún figuraba `queued`. Los estados devueltos son inconsistentes entre ejecución y job, así que el reintento NO se considera una compilación exitosa ni una validación fallida del código fuente. La compilación continúa **SIN CONFIRMAR**.
- Se actualiza la fecha del encabezado de esta bitácora para que no quede desfasada respecto a las anotaciones de octubre 27.
- Esta bitácora y los documentos P0 enumerados en `docs/migracion-nube/` son el respaldo versionado del avance, decisiones, riesgos y siguientes pasos. No equivalen a una copia descargable independiente ni a una prueba de build; GitHub conserva el historial por commits en la rama `chatgpt/sincronizacion-integridad-nube`.
- Estado general: objetivo y estrategia documentados; inventario/auditorías P0 registrados; implementación de nube todavía no completada; binario de la release todavía no inspeccionado; pruebas de migración y pruebas reales entre teléfonos pendientes. No modificar `main` ni fusionar PR #122 sin revisión y autorización explícita.

## 14. Corrección del estado de compilación con CI posterior (27 de octubre de 2026)

Esta sección actualiza la conclusión de la sección 13 a la luz de ejecuciones posteriores. Se conserva la anotación histórica anterior, pero ya no debe leerse como el estado actual de compilación.

- **Workflow de push, ejecución [40517906699](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/actions/runs/40517906699):** job `build` completado con conclusión `success`. En el detalle de la ejecución se confirmó que el paso de compilación de Debug APK terminó correctamente.
- **Workflow de PR, ejecución [40517916861](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/actions/runs/40517916861):** job `build` completado con conclusión `success`.
- Estas ejecuciones corresponden al avance de la rama en torno al commit `9766f42cbfa05babc03e45a840497757d96bd94a`. La ejecución anterior `40511465262`, que quedó cancelada/inconsistente, fue superada por estas ejecuciones verdes; no se borra su antecedente histórico.
- **Qué sí queda confirmado:** GitHub Actions terminó satisfactoriamente el job de compilación en las dos ejecuciones citadas, y la ejecución de push confirma el paso de compilación de Debug APK.
- **Qué NO queda confirmado:** no se acredita con esto la seguridad de la migración Room 37→38, pruebas automatizadas de migración, sincronización funcional entre dos teléfonos, resolución de conflictos, propagación global de borrados, ni qué versión de APK está instalada en cada dispositivo. El pipeline observado no ejecutó pruebas funcionales de esos casos.
- No se añade una dependencia Firebase ni se habilita Firestore en esta actualización documental. No se cambia código de producción ni el esquema Room.

### Estado actualizado

**Compilación de artefactos confirmada por CI para el avance documentado; pruebas de migración Room, pruebas funcionales y validación entre teléfonos siguen pendientes.** El siguiente paso técnico debe ser definir y verificar el contrato del piloto Matriz + Pase (identidad compartida, renombres, borrados/tombstones y confirmación de operaciones remotas) y establecer un arnés de pruebas con un esquema Room de origen auténtico antes de diseñar otra migración. Mantener Firestore con reglas `deny-all`, `main` intacta y PR #122 sin fusionar sin autorización explícita.

## 15. Contrato del piloto de integridad Matriz + Pase — P0-L (27 de octubre de 2026)

Se creó `docs/migracion-nube/CONTRATO_PILOTO_INTEGRIDAD_MATRIZ_PASE_P0_L.md` en esta rama, commit `a296a3663a211e9c86490d186fb94975672cd66e`.

### Hallazgos concretos contrastados con el código actual

- `SyncWorker.syncPase()` resuelve primero la cola `PaseSyncOperationEntity`. En una operación RENAME, si no encuentra el ID anterior ni el nuevo, completa la operación antes de que el upsert posterior cree la fila nueva. El código indica que el upsert la creará después, pero no exige que la creación esté confirmada antes de completar la operación; debe cerrarse esa ventana de fallo.
- El upsert de Pase usa un ID aleatorio de 12 caracteres creado en `PaseCarteraViewModel.aplicarImportacion()`. La prevención de duplicados compara `origenMatrizId` dentro de la base local; no demuestra una identidad única compartida entre teléfonos.
- `SheetsRepository.appendRow()` espera la respuesta de la API, pero no usa una clave idempotente remota. Si el servidor acepta una fila y la respuesta se pierde, un reintento puede volver a anexarla.
- `FiltrarViewModel.guardarRegistroCompleto()` invoca `renameRowId()` y no comprueba su resultado booleano antes de cambiar el ID en Room. El contrato exige corregir esta ruta para que un resultado `false` no produzca una mutación local parcial. **El código todavía no se ha corregido en este paso.**
- Matriz usa borrado físico en las rutas inspeccionadas y la descarga desde Sheets puede volver a insertar una fila que ya no existe localmente. Hace falta un tombstone/estado canónico para que un borrado confirmado no resucite en otro teléfono.
- Pase debe conservar su ciclo de vida independiente: borrar/editar Matriz no debe propagar automáticamente esas acciones a Pase.

### Decisión de trabajo

Se documentaron las reglas propuestas y los casos mínimos de prueba en P0-L. No se modificó código de producción, no se cambió `DB_VERSION = 38`, no se añadió dependencia, no se habilitó Firestore ni se modificó la cola existente.

### Próximo paso

Preparar la puerta de pruebas con esquema Room de origen auténtico y revisar las versiones realmente distribuidas; después, diseñar pruebas que cubran el renombre desde Filtrar, operaciones Pase pendientes, duplicados tras timeout, tombstones y migración preservando filas. No comenzar Room 38→39 hasta que estas pruebas sean reproducibles. Mantener `main` intacta y PR #122 sin fusionar.


## 16. Corrección acotada del renombre desde Filtrar — 27 de octubre de 2026

Se corrigió en la rama de trabajo la ruta `FiltrarViewModel.guardarRegistroCompleto()` para alinearla con la política que ya usa `MatrizViewModel.cambiarIdYGuardar()`.

- Archivo: `app/src/main/java/com/example/matrizapp/FiltrarViewModel.kt`.
- Commit: `f7b021314b75bfbc8f624e15b0c167e4e07af659`.
- Cambio: antes de renombrar, comprueba si el ID destino ya existe en Room o en Sheets. Si el ID anterior existe remotamente, exige que `renameRowId()` devuelva `true` antes de cambiar la clave local. Si el registro anterior todavía no existe en Sheets, conserva la ruta de alta local pendiente para el siguiente push.
- Si hay colisión, si Sheets devuelve `false` o si ocurre una excepción, informa el error por `onResult` y no continúa con el cambio local de ID.
- No se modificaron `AppDatabase.kt`, `DB_VERSION`, dependencias ni reglas Firebase. No se tocaron `main` ni la configuración de Firestore.

### Verificación pendiente

- La lectura posterior de `FiltrarViewModel.kt` confirmó el nuevo blob `4e8fc544e5d2f9e3b0d2d176086f33799c949ea8`.
- GitHub Actions creó la ejecución `40527045342` para el commit; en la última consulta estaba `pending`. Por tanto, la compilación de este cambio todavía no está confirmada.
- No existe evidencia de pruebas automatizadas para este flujo ni de prueba entre dos teléfonos. El cambio es una corrección estática acotada, no una garantía de consistencia global.
- La puerta de pruebas Room y el esquema de origen auténtico siguen pendientes. La versión Room instalada en cada teléfono no se ha establecido.

### Siguiente paso

Esperar el resultado de CI para este commit. Después, seguir con el arnés de pruebas de integridad sin subir aún `DB_VERSION`: obtener un APK distribuido que pueda inspeccionarse o una exportación de esquema Room auténtica, confirmar cómo probar migración y preservación de `pase_sync_operation_table`, y añadir pruebas para colisión/renombre, fallo remoto y reintento. Mantener PR #122 abierto y Firestore con reglas `deny-all`.


## 17. Puerta de pruebas reproducibles Room y sincronización P0 — 27 de octubre de 2026

Se creó `docs/migracion-nube/PUERTA_PRUEBAS_ROOM_Y_SYNC_P0.md` (commit inicial `c7eb4faee824d713d0c41c4649163f19e313071b`, luego precisión de evidencia CI en `88198436d440861349ad020301b736b5eb58a9fc`). El documento convierte el siguiente paso en una puerta verificable, sin elevar la versión de Room.

Evidencia inspeccionada en la rama:

- `AppDatabase.kt`, blob `6a641b399e8ae6071a6e46b4629abc115c5a99f1`: `DB_VERSION=38`, `exportSchema=false`; `MIGRATION_37_38` crea `pase_sync_operation_table` con clave primaria `operationKey` y campos `type`, `oldId`, `newId`.
- `app/build.gradle`, blob `9a5f44092bf87b94b4a3418839002fa4ed9da55e`: Room 2.6.1; no se observan dependencias de pruebas Room/JUnit/AndroidX Test en el archivo inspeccionado.
- No se encontró `app/schemas`, `gradle/libs.versions.toml` ni wrapper `gradlew` en las rutas raíz inspeccionadas. El workflow `.github/workflows/build.yml` usa Gradle 8.4 y no ejecuta pruebas automatizadas; compilar variantes no equivale a validar migración ni sincronización.
- El run de PR `40527045342` fue cancelado; el run `40527186356` vinculado a una actualización documental se observó pendiente. No se declara CI verde para el cambio de código `f7b021314b75bfbc8f624e15b0c167e4e07af659`.

La puerta define pruebas para: migración 37→38 y preservación de datos; colisiones/renombre de Matriz desde Filtrar; fallos remotos/reintentos; cola de operaciones de Pase; y, después, prueba controlada de dos dispositivos. También deja explícito que las llamadas Room/Sheets no son una transacción distribuida, que una respuesta remota ambigua puede requerir reconciliación, y que no se debe asumir versión instalada de los teléfonos sin evidencia.

**Estado:** puerta documentada; todavía no se implementaron pruebas automatizadas ni se confirmó compilación verde del cambio. No se cambió `DB_VERSION`, no se alteraron reglas Firebase, `main` sigue intacta y PR #122 sigue abierto sin merge.


## 18. Infraestructura inicial para exportar esquema Room — 27 de octubre de 2026

Se abrió la rama aislada `chatgpt/room-test-infrastructure` desde el commit `bfe290630ef9038c9ca4ee91d048de7820760b82`. El propósito de esta rama es habilitar evidencia de esquema/pruebas, sin cambiar el modelo de datos ni fusionar PR #122.

Cambios realizados en esta rama:

- `app/build.gradle`: se añadió `testInstrumentationRunner`, configuración KAPT `room.schemaLocation` apuntando a `app/schemas`, y dependencias AndroidX Test/JUnit y `androidx.room:room-testing:2.6.1`. Se conservaron las dependencias existentes y los campos `BUILD_SHA`/`BUILD_NUMBER`.
- `app/src/main/java/com/example/matrizapp/AppDatabase.kt`: `exportSchema` cambia de `false` a `true`; `DB_VERSION) sigue en 38 y no se añadió migración.
- `.github/workflows/build.yml`: después de la compilación, intenta publicar `app/schemas/` como artefacto por 14 días. Si el compilador no genera el directorio, advierte sin bloquear la entrega del APK; por ello la presencia del artefacto se debe verificar en el run.
- Commits de la rama: `0fd759c79c1d009c4a66cb352d001195c8d96469` (preparación inicial), `f23c0eb83128728f2111a3a2a4a80624923fa04b` (conservar interpolación de build), `b630f62c2b13aa1f8a8b69ca2024f2317a8c88d4` (exportación Room), `083e111bac677548fdf9a5ebebc111e8d451b695` (artefacto de esquema), `b0d3482752500e561ba695b6a0571cfa66113719` (no bloquear APK si falta esquema).

Verificación y límites:

- La edición de archivos se confirmó por GitHub; todavía no hay evidencia de compilación de esta rama ni de que el artefacto `app/schemas` se haya generado. Debe revisarse el CI del HEAD `b0d3482752500e561ba695b6a0571cfa66113719`.
- Esta infraestructura no sustituye una base de versión 37 auténtica. No crear a mano un snapshot 37 ni afirmar que existe una prueba de migración hasta obtener esquema/base de origen real.
- Aún no se ha añadido una clase de prueba de migración; las dependencias permiten preparar ese paso, pero por sí solas no validan nada.
- `main) permanece intacta; PR #122 sigue sin fusionar; Firebase/Firestore no se habilita para datos productivos; Apps Script permanece fuera de alcance.

### Siguiente paso

Verificar el CI y confirmar si se generó el artefacto Room. Si compila, recuperar el esquema 38 generado y versionarlo como salida auténtica. Después buscar una base/esquema 37 de un APK distribuido, y solo entonces implementar la prueba 37→38 con `MigrationTestHelper`. No subir a DB_VERSION 39 hasta que esta puerta esté respaldada por evidencia.


## 19. Verificación CI de infraestructura Room — 27 de octubre de 2026

Se verificó el HEAD real de `chatgpt/room-test-infrastructure`: commit `55f13f8dfc462d8ee2e99cf3eb862802b45503c6`.

Evidencia comprobada en GitHub Actions:

- Ejecución `40533800777`: conclusión `success`; el job `build` terminó correctamente.
- Los pasos `Build Debug APK`, `Guardar esquema Room generado` y `Publish stable release with APK` finalizaron correctamente.
- Se publicó y descargó para inspección el artefacto `room-schemas-55f13f8dfc462d8ee2e99cf3eb862802b45503c6`, ID `12747461992`, tamaño 3560 bytes, con vencimiento el 10 de noviembre de 2026. El ZIP contiene `com.example.matrizapp.AppDatabase/38.json` (47,774 bytes): `formatVersion=1`, `database.version=38`, `identityHash=c19a207e422bacd6bdf0258bca1c8ae2`. Es un esquema Room generado por la compilación, no uno escrito a mano.
- El archivo `app/build.gradle` confirma que `room.schemaLocation` apunta a `app/schemas`, que están declaradas las dependencias `room-testing:2.6.1` y AndroidX Test, y que los campos `BUILD_SHA` y `BUILD_NUMBER` conservan interpolación de Gradle.
- `AppDatabase.kt` confirma `DB_VERSION=38` y `exportSchema=true`. No se añadió una migración ni una prueba instrumentada en este cambio.

**Qué significa y qué no:** CI demuestra compilación correcta de las variantes configuradas y publicación del artefacto de esquema. No demuestra que la migración 37→38 preserve datos ni que la sincronización global sea correcta. El workflow no ejecuta pruebas instrumentadas de Room. El artefacto debe inspeccionarse y guardarse como evidencia versionada si contiene el esquema 38 válido.

**Bloqueo de la prueba de migración:** el esquema 38 ya se verificó dentro del artefacto; todavía no se ha versionado como archivo en el repositorio. No se debe fabricar un esquema 37. Sigue pendiente localizar un esquema o una base de datos de versión 37 generado por una compilación real/distribuida, y verificar que corresponde al código de esa versión. No aumentar `DB_VERSION` ni habilitar Firestore productivo hasta completar esta puerta.

Estado de seguridad y alcance: `main` no se modificó; PR #122 permanece sin merge; Firestore no se habilita para datos productivos; Apps Script sigue fuera del alcance.


## 20. Captura del esquema Room 37 y primera prueba SQL — 27 de octubre de 2026

Se creó una rama temporal aislada desde el commit histórico real `c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1`: `chatgpt/room-schema-v37-capture`. En esa rama solo se habilitó `exportSchema=true`, se configuró `room.schemaLocation` y se publicó el esquema generado por Room como artefacto de CI.

Evidencia del esquema 37:
- CI run `40543083998`, conclusión `success`: https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/actions/runs/40543083998
- Artefacto ID `12751194955`, nombre `room-schema-v37-b19c7fec1fac364b129d439c83cedeade2e0cac2`, expira el 10 de noviembre de 2026.
- El ZIP contiene `com.example.matrizapp.AppDatabase/37.json`; Room declara `database.version=37`, 22 entidades/tablas y hash `6f8d3d12b03c4f57838d8920358aa3a5`.
- El artefacto 38 previamente inspeccionado contiene `38.json`, versión 38, 23 entidades/tablas y hash `c19a207e422bacd6bdf0258bca1c8ae2`. El nuevo esquema incorpora `PaseSyncOperationEntity`.

Primer paso de prueba añadido a `chatgpt/room-test-infrastructure`:
- `AppDatabase.kt`: la visibilidad de `MIGRATION_37_38` cambia de privada a interna para permitir prueba.
- Se agregó `app/src/androidTest/java/com/example/matrizapp/PaseSyncMigrationSqlTest.kt`. La prueba ejecuta el objeto de migración real contra SQLite, comprueba que se crea `pase_sync_operation_table`, que un registro de una tabla preexistente sigue intacto y que la cola inicia vacía.
- Se añadió al workflow el paso de emulador Android para ejecutar `gradle connectedDebugAndroidTest`.
- Commits de estos cambios: `7bad05ac7c77a2d1bb1d396de5d3d6cf285ec6e2` (visibilidad de migración), `bf241257e59c227cc9b47262649f302020002cc7` (prueba), `7659ee8da894e024d3154200bab23c041681217e` (workflow de emulador).

Estado al escribir esta nota: la ejecución CI del HEAD `7659ee8da894e024d3154200bab23c041681217e` está pendiente; no se afirma que la prueba compile ni que haya pasado. Esta prueba es deliberadamente acotada: usa una base de prueba mínima y no sustituye todavía `MigrationTestHelper` con el esquema 37 completo. El siguiente paso es comprobar el resultado CI; después incorporar los snapshots auténticos 37/38 al repositorio y construir la prueba integral de migración con validación del esquema completo y conservación de datos.

Sin cambios en `main`; no se fusionó PR #122; no se activó Firestore productivo; Apps Script sigue fuera del alcance.


## 21. Revisión independiente de Claude contrastada contra el código actual — 2026-10-27/28

Esta sección registra el contraste realizado sobre la rama aislada `chatgpt/room-test-infrastructure`, HEAD `eab7f39a4407fbd81472ae81cdf5c0c104858455`. No sustituye la revisión del commit `9766f42` de `chatgpt/sincronizacion-integridad-nube`: son ramas/estados distintos. No se modificó `main`, no se fusionó PR #122 y no se afirma que la app esté lista para datos productivos.

### Confirmado en el código actual

- `SheetsRepository.refreshMatriz()` obtiene las filas remotas, actualiza/inserta registros y no elimina registros limpios que ya no están en la hoja. La reconciliación de borrados no está resuelta para Matriz.
- `copiarPaseDesdeMatriz()` filtra por estado PASE y semana actual, usa `getOrigenesYaCopiados()` y genera ID aleatorio para cada copia. No se observa en este método un identificador determinista por origen ni una reconciliación que evite la resurrección después de borrar Pase. La tabla `pase_sync_operation_table` no demuestra que este flujo ya esté conectado a una cola funcional.
- `SyncWorker.doWork()` empuja cambios locales hacia Sheets y captura `Exception` por módulo; no implementa una descarga global desde la nube. La descarga observada está en los flujos de refresco del repositorio/UI. Debe relanzarse `CancellationException` antes de tratar errores recuperables.
- `MatrizViewModel.cambiarIdYGuardar()` verifica colisiones local/remota y comprueba el resultado de `renameRowId()`, pero realiza primero el cambio remoto y después el local; no hay transacción distribuida ni evidencia de cola offline para renombres.
- `app/build.gradle` usa `../debug.keystore` como almacén de firma para las variantes debug, release y optimizada. Debe tratarse como hallazgo de seguridad confirmado y corregirse en un cambio separado, revisando el impacto en la instalación/actualización de teléfonos.
- La rama de infraestructura tiene `DB_VERSION = 38`, `exportSchema = true`, una migración `MIGRATION_37_38` y la prueba `PaseSyncMigrationRoomTest`. La compilación de Android tests y la descarga/instalación del snapshot auténtico v37 pasaron en el intento consultado; el paso del emulador seguía en curso al registrar esta sección. No declarar la migración probada hasta que el job termine en verde y el log confirme el test.

### Parcial o pendiente de corroborar

- La protección de renombre en `FiltrarViewModel` es una corrección puntual, no resuelve el flujo de renombre de Matriz ni una cola durable/atómica.
- Las afirmaciones de Claude sobre que el repositorio es público, los valores/configuración de Firebase, las huellas SHA, `oauth_client`, `google-services.json` y la presencia exacta de secretos deben comprobarse con un escaneo seguro de los archivos actuales y consola Firebase. No copiar valores de claves a logs, bitácora ni respuestas. La configuración de firma en Gradle sí está confirmada; no se debe publicar ningún secreto en este documento.
- Las contradicciones de P0-E sobre tickets, visitas de mapa y otros datos compartidos siguen siendo una decisión de producto abierta. No convertir la frase “todo dato operativo se comparte” en una implementación hasta resolver excepciones explícitas.
- No hay evidencia actual de pruebas Firebase Rules/Auth con usuarios autorizados/no autorizados ni de sincronización real entre dos teléfonos.
- No está definido un único exportador de Sheets desde la base central.

### Decisiones técnicas a incorporar al plan

1. Mantener una lista única de decisiones de producto sobre qué datos son globales, locales o derivados; resolver los conflictos entre P0-E, inventario y objetivo general antes del esquema definitivo.
2. Diseñar borrados con tombstones/versionado y una reconciliación idempotente; no borrar filas locales por ausencia remota sin distinguir borrados reales, errores de lectura y cambios pendientes.
3. Pase: usar una clave determinista por origen Matriz y definir el ciclo de vida de borrado/edición; agregar pruebas de dos dispositivos y de reintentos.
4. Sincronización: separar claramente pull, push y outbox; la nube será fuente de verdad y Room caché/outbox. Definir qué capa manda y qué confirma cada operación antes de marcarla limpia.
5. Renombres: definir un protocolo recuperable ante fallo entre cambio remoto y local; probar colisión, desconexión y reintento.
6. Seguridad: auditar y restringir API keys, retirar credenciales/keystore de firma comprometidos del repositorio con rotación planificada, y validar Firebase Auth/Rules. No hacer rotación de firma sin plan de actualización/exportación de datos locales.
7. CI: completar la prueba de migración Room 37→38 y mantener la rama aislada; después añadir pruebas de borrado, Pase y renombre. No iniciar migración productiva ni activar escritura de nube hasta validar reglas y pruebas sintéticas.
8. Mantener Google Apps Script fuera del alcance actual, según la instrucción del usuario; el destino Sheets futuro es solo exportación/compatibilidad si se confirma necesario.

### Estado

- Contraste de código para borrados de Matriz, copia de Pase, ausencia de pull en Worker, manejo de cancelación y renombre: confirmado como hallazgos vigentes en la rama examinada.
- Prueba Room 37→38: CI en curso al momento de la consulta; resultado pendiente.
- Auditoría Firebase/secretos completa: pendiente; se debe hacer sin revelar valores.
- Implementación de sincronización global, tombstones, IDs deterministas y pruebas con dos teléfonos: pendiente.

## 22. Origen real del fallo de prueba Room 37→38 y corrección de CI — 28 de octubre de 2026

Se inspeccionó el log completo del job de GitHub Actions de la rama `chatgpt/room-test-infrastructure`, ejecución `40614187071`, job `122573096850`, commit probado `880ac4c7891d95a350f9c3684aec7266b3c06376`.

### Diagnóstico confirmado por el log

- La compilación de las variantes de la app terminó con `BUILD SUCCESSFUL`; el fallo no era de compilación Kotlin ni de la migración SQL en sí.
- La descarga del artefacto con el esquema Room 37 auténtico funcionó, y el paso de CI verificó que existían los JSON de esquema 37 y 38.
- El emulador sí terminó de arrancar. Los mensajes iniciales `adb: device offline` y `Failed to start Emulator console` fueron transitorios en esta ejecución; no fueron la causa final del job fallido.
- La prueba `PaseSyncMigrationRoomTest.migration37To38PreservesPaseAndValidatesEntireSchema` sí llegó a ejecutarse y falló con el mensaje exacto: `FileNotFoundException: Cannot find the schema file in the assets folder`, faltaba `com.example.matrizapp.AppDatabase/37.json`.
- Causa raíz: el workflow copiaba el JSON a `app/schemas/` y comprobaba que existía, pero `MigrationTestHelper` busca los snapshots en los assets empaquetados del APK de pruebas instrumentadas. La presencia del archivo en `app/schemas` no garantiza que ese archivo esté en los assets de `androidTest`.

### Corrección aplicada

- Commit: `956417306655bf47d630fd747b60e56c0d794bab` — `fix(ci): incluir esquemas Room en assets de androidTest`.
- Archivo cambiado: `.github/workflows/build.yml`.
- El paso ahora copia los esquemas auténticos 37 y 38 a `app/src/androidTest/assets/com.example.matrizapp.AppDatabase/`, conserva los originales bajo `app/schemas/` y verifica que ambos assets no estén vacíos antes de iniciar las pruebas.
- La corrección está en la rama aislada `chatgpt/room-test-infrastructure`; no se modificó `main` ni se fusionó PR #122.

### Estado de validación

La corrección está registrada en GitHub. La nueva ejecución de CI debe confirmar que ambos JSON se empaquetan como assets, que `MigrationTestHelper` encuentra el esquema 37 y que la migración conserva los datos y valida el esquema 38 completo. Hasta ver ese resultado, la prueba 37→38 continúa **pendiente de confirmación**; no declarar el problema cerrado solo por el commit.
