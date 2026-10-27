# Contrato del piloto de integridad Matriz + Pase — P0-L

**Rama:** `chatgpt/sincronizacion-integridad-nube`  
**Fecha:** 27 de octubre de 2026  
**Estado:** contrato de análisis y pruebas; no es implementación ni autorización para migrar datos reales.

## Propósito y límites

Cerrar primero las reglas de identidad, confirmación, renombre y borrado para dos módulos existentes antes de introducir Firestore o una nueva migración Room. Matriz y Pase no son la misma entidad: Pase es una copia independiente con su propio ciclo de vida.

No se modifica código de producción, `DB_VERSION`, dependencias, OAuth ni reglas cloud en este paso. `main` permanece intacta; PR #122 no se fusiona.

## Evidencia del código inspeccionado

- `AppDatabase.kt` (blob `6a641b399e8ae6071a6e46b4629abc115c5a99f1`): `DB_VERSION = 38`; 23 entidades. La migración 37→38 crea `pase_sync_operation_table`. La tabla y sus pendientes deben preservarse.
- `SyncWorker.kt` (blob `cb87f911072c0bfdd228468a28f35f3902aa9b2c`): procesa primero operaciones Pase DELETE/RENAME; el RENAME se completa si no se encuentran ni el ID anterior ni el nuevo y deja la creación para el upsert posterior. En cambio, si el ID anterior falta y el nuevo existe, conserva la operación como ambigua. Después hace upsert de Pase y, en la rama de append, llama a `markPaseAsClean` tras la respuesta de `appendRow`.
- `SheetsRepository.kt` (blob `9a625c6a68d64febc5aa24d75243027389ee25bf`): `appendRow` retorna `Unit` después de que la llamada API termina; un timeout con resultado remoto incierto puede provocar un reintento sin clave idempotente remota. `renameRowId` devuelve `false` si no encuentra la fila anterior.
- `FiltrarViewModel.kt` (blob `08491420265ede1f13985c262767f2cc179a5b1b`): `guardarRegistroCompleto` llama a `renameRowId` para Matriz, pero no comprueba el Boolean devuelto antes de ejecutar `matrizDao.renameId`. Es una ruta distinta de `MatrizViewModel.cambiarIdYGuardar` y puede dejar Room y Sheets con IDs distintos si no hay excepción y el método devuelve `false`.
- `PaseCarteraViewModel.kt` (blob `c153ecffb9eabbb8ff053a38076443ac215d5d69`): crea el ID de Pase con UUID aleatorio de 12 caracteres; la importación evita duplicar localmente comparando `origenMatrizId`. Esto no es una identidad global compartida garantizada entre teléfonos.
- `SheetsRepository.refreshMatriz` y los flujos de borrado observados trabajan con filas/IDs y borrado físico; no hay tombstone global que impida reimportar una fila antigua. No convertir la ausencia de fila en prueba suficiente de un borrado global.

## Contrato funcional propuesto

### 1. Matriz: identidad y renombre

1. Mantener el ID visible de cliente mientras Sheets siga siendo puente.
2. Un renombre debe validar que el ID destino no esté ocupado, tanto local como remotamente, y confirmar el resultado remoto antes de cambiar la clave local cuando el registro ya existe remotamente.
3. Si el registro aún no se ha subido, permitir renombre local solamente si la operación pendiente conserva identidad estable y el ID final.
4. Todas las pantallas que guardan Matriz deben usar una única política común: el flujo desde Filtrar no puede ignorar un resultado `false`.
5. No reasignar automáticamente referencias de Pase, visitas históricas ni contactos extra al renombrar Matriz hasta definir por entidad si siguen al cliente. No usar el ID visible como identidad inmutable a largo plazo sin evaluar referencias.

### 2. Pase: identidad y ciclo de vida independiente

1. Pase conserva sus campos/ID y sus decisiones de negocio independientes de Matriz; actualizar o borrar Matriz no debe actualizar/borrar Pase automáticamente.
2. `origenMatrizId` es una relación de origen útil, pero no basta como identidad global del registro de Pase si el ID actual se genera al azar por teléfono.
3. Antes de replicar Pase entre teléfonos, elegir una clave estable que se genere una sola vez para el registro lógico (o una clave cloud asignada por servidor) y una restricción de unicidad verificable. No cambiar los IDs existentes sin plan de reconciliación.
4. El RENAME/DELETE en la cola actual debe permanecer durable hasta confirmar el estado remoto. Una respuesta ambigua no se marca como éxito.
5. La finalización de RENAME debe comprobar el resultado final: no se puede completar solo porque no se encontraron ambas filas; si se crea el registro mediante upsert posterior, esa creación debe confirmarse antes de cerrar la operación o debe existir un estado intermedio explícito.

### 3. Borrados y reimportación

1. El borrado compartido debe ser una operación durable/tombstone con entidad, identidad canónica, alcance, versión y operación idempotente.
2. Antes de insertar o refrescar desde Sheets, comprobar el estado canónico y los tombstones; una fila vieja no debe revivir una entidad borrada.
3. Para Pase, el borrado se aplica a Pase, no al origen Matriz; la política de borrar Pase no implica borrar Matriz.
4. No crear tombstones históricos a partir de filas ausentes actuales: no hay evidencia suficiente para saber si fueron borradas, filtradas o si falló una descarga.

### 4. Confirmación, reintentos y conflictos

- La operación local y su registro pendiente deben guardarse atómicamente en Room.
- Reintentar con el mismo identificador de operación; no generar otro ID por cada intento.
- No marcar limpio/completado hasta recibir confirmación remota verificable.
- Si la respuesta se pierde después de que el servidor pudo aceptar el cambio, consultar por identidad/operación antes de volver a insertar.
- Si dos teléfonos editan simultáneamente el mismo campo o intentan el mismo renombre, registrar conflicto; no aplicar “última escritura gana” indiscriminadamente.

## Casos mínimos de prueba antes de implementar

| Caso | Resultado exigido |
|---|---|
| Renombre Matriz desde la pantalla Matriz | Room y remoto terminan con el ID acordado; sin duplicados |
| Renombre Matriz desde Filtrar y remoto devuelve `false` | No cambiar el ID local; mostrar error verificable |
| ID destino ya existe en Room o remoto | Rechazar el renombre sin mutación parcial |
| Timeout al crear/actualizar | Reintento no duplica la fila ni pierde cambios |
| Borrado Matriz en teléfono A, refresh en B | El registro no reaparece tras confirmación global del borrado |
| Borrado de Pase | Solo Pase se borra; Matriz permanece |
| Renombre de Pase con fila anterior, nueva, ambas o ninguna | Cada estado se resuelve explícitamente; no cerrar operación ambigua |
| Reinicio con DELETE/RENAME pendiente | La cola se conserva y reintenta de forma idempotente |
| Dos teléfonos importan el mismo origen Matriz a Pase | Se crea un solo registro lógico compartido |
| Migración Room de origen real a nueva versión | Se conservan todas las filas y las operaciones Pase pendientes |

## Puerta de entrada a la implementación

Antes de añadir una outbox común o incrementar Room:

1. Obtener un esquema Room de origen auténtico compatible con la versión distribuida; no inferir la versión instalada por la etiqueta de release.
2. Añadir/confirmar infraestructura de pruebas que pueda ejecutar la migración y validar esquema/datos.
3. Definir el formato final de la operación (identidad, payload, versión base, actor, estado, reintentos, conflicto y ACK).
4. Decidir si la outbox común coexiste temporalmente con `pase_sync_operation_table`; no borrar ni migrar la cola especializada sin pruebas.
5. Ejecutar pruebas de migración, fallo de red, respuesta ambigua y dos dispositivos con datos sintéticos.
6. Solo después, implementar el primer cambio de código en esta rama y comprobar CI.

## Estado de este documento

Contrato inicial derivado de inspección estática de los blobs indicados. No se ha ejecutado una prueba automatizada de estos casos ni se ha corregido aún el flujo de renombre desde Filtrar. No hay integración Firestore implementada ni prueba real de dos teléfonos.
