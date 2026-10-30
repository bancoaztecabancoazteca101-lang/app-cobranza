# Auditoría P0 — rutas de borrado y reimportación desde Sheets

Fecha: 26 de octubre de 2026  
Rama: `chatgpt/sincronizacion-integridad-nube`  
Alcance: rastreo estático de puntos concretos en los archivos enumerados. No se cambió código productivo ni se ejecutaron builds o pruebas.

## Fuentes revisadas

- `MatrizViewModel.kt` — `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`
- `SolicitudViewModel.kt` — `8c10d3281d47bbe1ccd0c58e343dac80ffaa161d`
- `SyncWorker.kt` — `cb87f911072c0bfdd228468a28f35f3902aa9b2c`
- `SheetsRepository.kt` — `9a625c6a68d64febc5aa24d75243027389ee25bf`
- `DAOs.kt` — `78574574296743fe92a0e51d64170e3f51c628f7`
- `Entities.kt` — `ccd9097479375611993b196fd106736357faf1f9`

## 1. Solicitud: mismo patrón de borrado remoto inmediato que Matriz

`SolicitudViewModel.eliminarRegistro()` llama a `repository.deleteRowById(Constants.SHEET_SOLICITUD, ...)`; si no confirma, conserva la copia de Room. Si confirma, llama a `solicitudDao.deleteById(id)`. El flujo no usa la cola de operaciones de Pase ni un tombstone común.

Esto implica el mismo requisito de diseño que Matriz: tras confirmar un borrado remoto, un fallo local o una importación atrasada no debe resucitar el registro.

## 2. Pase: existe una cola durable, pero es específica de Sheets

En `SyncWorker.syncPase()`, se procesan primero las operaciones pendientes de `PaseSyncOperationDao`. Para `DELETE`, llama a `deleteRowById(Constants.SHEET_PASE, op.oldId, ...)` y elimina la operación de la cola mediante `completar(operationKey)` después de confirmar el resultado. Para `RENAME`, inspecciona presencia de fila antigua y nueva antes de continuar.

La cola actual es una referencia útil para el diseño de outbox, pero no debe asumirse que ya resuelve sincronización global: su contrato actual es para Google Sheets y no equivale a un tombstone compartido con control de versión.

## 3. Refrescos pueden volver a introducir filas remotas

En `SheetsRepository.refreshSolicitud()`, el código obtiene las filas de Sheets, excluye IDs que están dirty en Room y hace `insertAll(items)` para las filas remotas restantes. No aparece una comprobación de tombstones compartidos en este flujo.

En `refreshFiltroFecha()`, lee la hoja, excluye los IDs dirty, ejecuta `filtroDao.deleteAllClean()` y después inserta las filas remotas. Por diseño, el borrado de todas las filas limpias se usa como estrategia de refresco; en la migración no debe confundirse con una eliminación de negocio.

En el fragmento revisado del refresco de Matriz, las filas existentes se actualizan con `actualizarDesdeSheet` y las desconocidas se agregan con `insertAll(nuevos)`. No se identificó un filtro por tombstones en el código revisado. Debe verificarse el método completo y todos los llamadores antes de implementar, pero el contrato de nube debe proteger también este punto de entrada.

**Conclusión:** filtrar solo por `isDirty` no protege contra reaparición después de un borrado, porque una fila borrada físicamente ya no está en la tabla local para aparecer como dirty. Se necesita un registro de borrado durable que sobreviva a la eliminación física y sea consultado por los procesos de importación/reconciliación.

## 4. Operaciones que no deben confundirse con borrado de negocio

- `FiltroFechaDao.deleteAllClean()`: limpia copias locales sincronizadas durante el refresco; no representa por sí mismo que el usuario haya eliminado clientes.
- `RutaIADao.deleteAll()`: existe para reemplazar la lista local de Ruta IA cuando el resultado remoto está vacío. No es un borrado individual global de cliente.
- `CanalPagoDao.deleteAll()` y `ControlDao.deleteAll()`: reemplazan catálogos/datos descargados, no son tombstones de registros operativos.
- `VisitaMapaDao.borrarAnteriores(limite)`: elimina eventos locales antiguos por retención. Su política de retención debe distinguirse de la eliminación operativa compartida.
- `TicketPagoDao.borrar(id)`: existe un borrado físico local en DAO. En esta revisión no se localizó un ViewModel de tickets con esa ruta mediante la búsqueda por nombre de archivo; no se concluye quién lo llama ni si se ofrece como acción en UI. Queda pendiente localizar todos sus llamadores y confirmar si los tickets deben ser locales, compartidos o inmutables/históricos.

## 5. Contrato mínimo para el outbox/tombstones

1. Registrar el borrado en una transacción local antes de retirar la fila visible.
2. Usar ID de operación estable e idempotente, identidad estable de entidad y versión esperada.
3. Sincronizar tombstone a la nube y confirmar antes de marcar la operación como completada.
4. Todo refresco/importación debe consultar el tombstone vigente y rechazar filas borradas aunque reaparezcan en Sheets.
5. Mantener la operación pendiente después de timeout, cierre de app o error; reintentar no debe duplicar ni recrear el registro.
6. Separar los borrados de negocio de la limpieza de caché, retención de historial y reemplazo de catálogos.
7. Definir por separado el contrato de Matriz, Solicitud, Pase, Semana 6, tickets, visitas, Ruta IA y contactos históricos.
8. Durante la transición, reconciliar Sheets contra la fuente de verdad en nube; no permitir que una hoja antigua invalide silenciosamente un tombstone.

## 6. Pruebas de aceptación añadidas

- Borrar Solicitud y forzar un refresco de Sheets: el registro no debe volver.
- Borrar Matriz y ejecutar sincronización completa/refresh: no debe volver por importación.
- Borrar Pase sin conexión, reiniciar, sincronizar y verificar que la cola se completa solo tras confirmación.
- Simular timeout ambiguo en borrado remoto y volver a intentar; el estado final debe ser estable.
- Ejecutar refresco de Filtro Fecha con filas limpias y cambios pendientes; no debe perder cambios locales.
- Verificar que las limpiezas de caché/catálogos no se registran como borrados de negocio.
- Localizar y probar el flujo real que llama a `TicketPagoDao.borrar()`.

## 7. Estado y límites

Hallazgos estáticos, no prueba dinámica. No se cambió `main`, Firebase, reglas, dependencias, Room ni APK. El inventario de todos los puntos de borrado aún no se considera cerrado hasta localizar los llamadores de todos los DAO de borrado y revisar los refrescos completos y rutas UI.
