# BITÁCORA DE CONTINUIDAD — FUENTE ÚNICA DE VERDAD Y SINCRONIZACIÓN GLOBAL

Actualizada: 23 de octubre de 2026 — revisión de estrategia  
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
