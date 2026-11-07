# Mapa de operaciones P0-A — App Matriz

Fecha: 2026-10-26  
Rama: `chatgpt/sincronizacion-integridad-nube`  
Estado: lectura de código real; mapa inicial, no auditoría exhaustiva ni implementación.

## Archivos y versiones de código inspeccionados

- `DAOs.kt`, blob `78574574296743fe92a0e51d64170e3f51c628f7`
- `MatrizViewModel.kt`, blob `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`
- `SyncWorker.kt`, blob `cb87f911072c0bfdd228468a28f35f3902aa9b2c`
- `SheetsRepository.kt`, blob `9a625c6a68d64febc5aa24d75243027389ee25bf`
- `AppDatabase.kt`, blob `6a641b399e8ae6071a6e46b4629abc115c5a99f1`: `DB_VERSION = 38`, 23 entidades.

## Mapa inicial por flujo

| Flujo | Entradas / DAO encontradas | Ruta de sincronización actual | Riesgo / verificación pendiente |
|---|---|---|---|
| Matriz | `MatrizViewModel`: `guardarGestion`, `guardarRegistroCompleto`, `cambiarIdYGuardar`, `crearRegistro`, `eliminarRegistro`, `triggerSync`. DAO: `getAllMatriz`, `getById`, `insertAll`, `insertOne`, `updateGestionLocal`, `updateEstadoYHora`, `updateRegistroCompleto`, `renameId`, `getDirtyItems`, `markAsClean`, `deleteById`. | `SyncWorker.syncMatriz()` y métodos de Sheets como `getDirtyMatrizItems`, `markMatrizAsClean`, `refreshMatriz`. | Mapear el cuerpo completo para comprobar clave remota, escritura de campos, resultado de red, marcado limpio y borrado. |
| Pase | DAO con lectura, inserción, actualización, eliminación, dirty/clean; `PaseSyncOperationEntity` y DAO `encolar`, `pendientes`, `completar`. | `SyncWorker.syncPase()`; métodos de Sheets y cola durable de operaciones. | Preservar operaciones pendientes; comprobar renombres ambiguos y eliminación sin resucitar registros. |
| Solicitud | DAO `getAllSolicitud`, actualizaciones de estado/audio/imágenes, `insertOne`, `insertAll`, `updateCompleto`, `getDirtyItems`, `markAsClean`, `deleteById`. | `SyncWorker.syncSolicitud()`; `getDirtySolicitudItems`, `markSolicitudAsClean`, `refreshSolicitud`. | Revisar adjuntos, confirmación remota, errores parciales y borrado. |
| Filtro Fecha | DAO con `getAll`, `getItemsByRange`, `insertAll`, cambios locales de estado/hora, dirty/clean. | `SyncWorker.syncFiltroFecha()`; `getDirtyFiltroFechaItems`, `markFiltroFechaAsClean`, `refreshFiltroFecha`. | Verificar el caso documentado donde puede marcar limpio si no encuentra fila remota. |
| Ruta IA | DAO dedicado y dirty/clean. | `SyncWorker.syncRutaIA()`; `getDirtyRutaIAItems`, `getAllRutaIAItems`, `markRutaIAAsClean`, `reemplazarRutaIAEnSheet`, `refreshRutaIA`. | El reemplazo completo de hoja puede pisar cambios de otro teléfono; requiere versiones/propietario/conflictos. |
| Tickets OCR | `TicketPagoDao.insertar`, `borrar`, `contarSimilares`, consultas de rango/suma. | No aparece como módulo explícito en la lista de módulos de `SyncWorker.doWork()`. | Si debe ser global, necesita ID idempotente y política de deduplicación; hoy es registro local. |
| Visitas del mapa | `VisitaMapaDao.registrar`, `registrarVarias`, `reemplazar`, `getVisitasSemana`; consulta/actualización de cartucho activo. | No aparece como módulo explícito en `SyncWorker.doWork()`. | Confirmar claves, retención y si varios teléfonos deben ver la misma visita. |
| Cartucho diario | DAO contiene `getCartuchoActivo`, `asegurarCartuchoDia`, `setCartuchoActivo`, `observarCartuchosSemana`, `borrarAnteriores`. | No aparece como módulo explícito en `SyncWorker.doWork()`. | Si define el trabajo compartido del día, una copia local por teléfono puede divergir. |
| Semana 6 | DAO local `Sem6Dao`; `Sem6ViewModel`, `Sem6Generador`, `Sem6CacheStore`; el código de `Sem6.kt` describe listado de hoja/caché. | No aparece como módulo explícito en `SyncWorker.doWork()`; parte de la ruta usa Sheets/cache y parte Room. | Resolver fuente autoritativa, borrados por hoja y edición local antes de migrar. |
| Catálogo de canales de pago | DAO/repository de catálogo y métodos `sincronizarCatalogoCanalesPago`, `catalogoCanalesPagoLocal`. | Refresco desde la fuente de catálogo actual. | Probable catálogo compartido de solo lectura; confirmar autoridad y frecuencia. |

## Confirmación de alcance de SyncWorker

El cuerpo de `SyncWorker.doWork()` llama explícitamente a estos módulos: **Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha**. Además intenta el trabajo de imágenes. No se observa un módulo explícito para tickets OCR, visitas de mapa, cartucho diario o Semana 6 en esa lista. Eso no prueba que no haya otros mecanismos fuera del worker, pero sí impide afirmar que esos datos ya se sincronizan globalmente.

## Hallazgos transversales

1. `SheetsRepository.updateSheetCell()` actualiza una celda en Google Sheets; `appendRow()` agrega filas. No son una transacción atómica de negocio.
2. Los métodos `markAsClean` existen en la ruta actual. Hay que verificar que solo se ejecuten después de una confirmación remota válida.
3. La cola `PaseSyncOperationEntity` es específica de operaciones de Sheets y debe preservarse durante una transición.
4. `MatrizViewModel` usa rutas de escritura distintas; no se debe migrar solo una de ellas.
5. No agregar datos reales a Firestore ni abrir sus reglas antes de definir autenticación, autorización, IDs globales, borrados/tombstones, idempotencia y conflictos.

## Siguiente paso

Inspeccionar los cuerpos completos de `SyncWorker.syncMatriz()`, `syncPase()`, `syncSolicitud()`, `syncFiltroFecha()` y los métodos de `SheetsRepository` que invocan. Registrar por flujo: criterio dirty, clave remota, acción de escritura, manejo de errores, confirmación y marcado limpio. Después inspeccionar la UI y claves de VisitaMapa/CartuchoDia. No modificar código de producción ni migraciones Room todavía.

## Límites

No se ejecutó Gradle ni pruebas de dispositivos. `main` sigue intacta; PR #122 no se fusionó; Firestore debe permanecer con reglas `deny-all`.