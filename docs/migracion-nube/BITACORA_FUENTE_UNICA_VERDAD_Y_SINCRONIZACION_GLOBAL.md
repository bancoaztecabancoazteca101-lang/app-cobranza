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