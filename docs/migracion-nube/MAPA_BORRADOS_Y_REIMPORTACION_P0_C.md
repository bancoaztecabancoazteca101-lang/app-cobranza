# Mapa de borrados y reimportación — P0-C

**Rama inspeccionada:** `chatgpt/sincronizacion-integridad-nube`  
**Alcance:** localizar los caminos de borrado identificables en el código actual y comprobar si una lectura/importación posterior puede reintroducir registros.  
**Importante:** esto es auditoría documental; no modifica lógica de la app ni acredita compilación/pruebas.

## Archivos y revisiones inspeccionados

- `app/src/main/java/com/example/matrizapp/FiltroFechaViewModel.kt` — blob SHA `5ece22add07e87257ed845a02b306872cfa636c1`
- `app/src/main/java/com/example/matrizapp/SheetsRepository.kt` — blob SHA `9a625c6a68d64febc5aa24d75243027389ee25bf`
- `app/src/main/java/com/example/matrizapp/DAOs.kt` — blob SHA `78574574296743fe92a0e51d64170e3f51c628f7`
- `app/src/main/java/com/example/matrizapp/MatrizViewModel.kt` — blob SHA `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`
- `app/src/main/java/com/example/matrizapp/Sem6ViewModel.kt` — blob SHA `5110af8d9ed70834d67296992662d37bfa775905`
- `app/src/main/java/com/example/matrizapp/PaseCarteraViewModel.kt` — se revisó el flujo de eliminación en el archivo de la rama.

## Hallazgos confirmados

### 1. Tickets de pago OCR: borrado local

- `FiltroFechaViewModel.borrarTicket(id)` llama a `TicketPagoDao.borrar(id)`.
- `TicketPagoDao.borrar` ejecuta `DELETE FROM ticket_pago_table WHERE id = :id`.
- En el código inspeccionado, los tickets se crean y consultan en Room; no se identificó una subida/importación de tickets desde Sheets ni un outbox de tickets.
- Por ahora clasificar el ticket como **dato local** es la interpretación más compatible con este camino de código, pero debe confirmarse como decisión de producto: ¿el historial y total de tickets OCR deben compartirse entre teléfonos o solo existir en el teléfono que leyó el ticket?
- Si deben ser globales, el borrado y el registro OCR necesitan identidad global y sincronización; si son locales, no deben migrarse como entidad compartida sin una decisión explícita.

### 2. Matriz: borrado remoto inmediato y borrado físico local

- `MatrizViewModel.eliminarRegistro(id)` llama a `repository.deleteRowById(SHEET_MATRIZ, id, COL_ID)`.
- Solo cuando la operación remota devuelve confirmación ejecuta `matrizDao.deleteById(id)`.
- El DAO elimina físicamente la fila de Room. No se registra tombstone ni operación de borrado durable compartida.
- `SheetsRepository.refreshMatriz()` descarga filas del Sheet, evita IDs que actualmente están dirty, actualiza filas ya existentes e inserta IDs desconocidos con `matrizDao.insertAll(nuevos)`.
- Como un registro borrado físicamente ya no está en Room, no puede aparecer en `dirtyIds`. Si una copia antigua del Sheet, una carrera, una restauración o una escritura tardía vuelve a exponer ese ID, la ruta de importación no tiene un tombstone que lo bloquee. Es un **riesgo de reaparición**, no una prueba de que ocurra en cada sincronización normal.
- La lectura y el borrado no forman una transacción distribuida; tampoco hay confirmación global para los otros teléfonos.

### 3. Solicitud: borrado remoto confirmado y borrado físico local

- `SolicitudViewModel.eliminarRegistro(id)` solicita borrar la fila remota y, tras confirmación, ejecuta `solicitudDao.deleteById(id)`.
- `SheetsRepository.refreshSolicitud()` omite IDs dirty, pero luego inserta los elementos descargados. No se observó comprobación de tombstone en este camino.
- Por tanto, comparte el riesgo de reimportación si la fila vuelve a existir en la fuente remota o llega una respuesta atrasada.

### 4. Pase: cola local que debe conservarse

- `PaseCarteraViewModel.eliminarRegistro(id)` usa `database.eliminarPaseAtomico` con una `PaseSyncOperationEntity` de tipo `DELETE`.
- `SyncWorker.syncPase()` procesa la operación pendiente y la completa después de confirmar el borrado remoto.
- Esta es una cola durable específica de Pase y no debe eliminarse ni sustituirse sin migrar sus operaciones pendientes. Sin embargo, la cola está vinculada a Sheets; para la fuente global de verdad habrá que definir cómo se transforma en outbox cloud y cómo se reintenta de forma idempotente.

### 5. Semana 6: marcador de borrado solo en el dispositivo

- `Sem6ViewModel.eliminarRegistro(id)` elimina la fila local y llama a `Sem6CacheStore.marcarEliminado(id, hoja)`.
- `Sem6ViewModel.init` y `cargar()` llaman a `Sem6Generador.importarDesdeHojas/actualizar` con un filtro que consulta `cacheStore.estaEliminado(id, hoja)`.
- El marcador se guarda en SharedPreferences `sem6_cache`, por lo que no es global ni compartido entre teléfonos. Otro dispositivo puede regenerar/importar el registro eliminado porque no conoce el marcador del primer teléfono.
- La clave de borrado debe conservar como mínimo el ámbito de la hoja/semana y la identidad del registro; la semántica de eliminación y restauración necesita acordarse antes de convertirla a tombstone global.

### 6. Otros DELETE detectados en DAOs

El inventario de consultas `DELETE FROM` incluye:

- `Sem6Dao.eliminar(hoja, id)`: borrado de negocio local de Semana 6, descrito arriba.
- `VisitaMapaDao.borrarAnteriores(limite)`: retención de historial local por antigüedad; no convertir automáticamente en tombstones globales.
- `MatrizDao.deleteById(id)`: borrado físico de Matriz, descrito arriba.
- `PaseCarteraDao.eliminar(id)`: debe interpretarse junto con la transacción/outbox de Pase, no aisladamente.
- `PaseSyncOperationDao.completar(key)`: elimina una operación de la cola después de procesarla; no es borrado de un cliente.
- `SolicitudDao.deleteById(id)`: borrado físico de Solicitud, descrito arriba.
- `FiltroFechaDao.deleteAll/deleteAllClean`, `ControlDao.deleteAll`, `RutaIADao.deleteAll/deleteNotIn` y `CanalPagoDao.deleteAll`: operaciones de reemplazo/limpieza de tablas de caché o catálogo en rutas de refresco. No deben confundirse con una orden del usuario de borrar un cliente. Aun así, hay que verificar dirty-state y concurrencia en el diseño de sincronización.

## Contrato requerido antes de implementar cloud

1. **Identidad global estable** por entidad y ámbito; no depender solo de la existencia de una fila local.
2. **Tombstone duradero** para eliminaciones compartidas, con entidad, ID, ámbito cuando aplique, versión/fecha de servidor y actor autorizado.
3. **Outbox transaccional local**: guardar el cambio y la operación pendiente en la misma transacción Room; reintentar con clave idempotente.
4. **Pull/importación consciente de tombstones**: nunca insertar ni actualizar una entidad si existe una eliminación global vigente; definir explícitamente la restauración.
5. **Confirmación del servidor** antes de marcar la operación como sincronizada o retirar la operación pendiente.
6. **Semántica por entidad**: distinguir borrado global de cliente, retiro de Pase, borrado de Semana 6, limpieza de caché, retención de visitas y eliminación de un ticket OCR.
7. **Migración segura de la cola Pase existente**, incluyendo operaciones pendientes al actualizar la app.
8. **Pruebas de dos dispositivos**: borrar en A, refrescar en B; editar offline y volver a conectar; timeout tras escritura remota; retry duplicado; ID renombrado; y restauración explícita.

## Preguntas de decisión que el código no puede resolver

- ¿Los tickets OCR y sus totales deben compartirse entre todos los teléfonos autorizados?
- ¿Borrar de Matriz debe eliminar también referencias/copies en Pase o Semana 6? El código actual no implementa una cascada global y no se debe inventar.
- ¿Un registro eliminado puede restaurarse? Si sí, ¿quién puede hacerlo y cómo prevalece sobre el tombstone?
- ¿La eliminación de Semana 6 es solo para una hoja/semana o debe impedir que ese cliente vuelva a generarse en semanas futuras? El comportamiento actual usa `(hoja,id)`, así que la decisión debe ser explícita.

## Estado de verificación

- Auditoría de texto de los archivos listados: realizada en la rama indicada.
- Cambios de código de producción: ninguno en este trabajo.
- Compilación, pruebas automatizadas y pruebas en teléfonos: no ejecutadas/no demostradas.
- Seguridad Firestore: mantener deny-all hasta completar autenticación y autorización verificadas.
