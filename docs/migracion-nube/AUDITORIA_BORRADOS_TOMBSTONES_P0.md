# Auditoría P0 — borrados, tombstones y riesgo de reaparición

Fecha de revisión: 26 de octubre de 2026  
Repositorio: `bancoaztecabancoazteca101-lang/app-cobranza`  
Rama: `chatgpt/sincronizacion-integridad-nube`  
Alcance: lectura estática del código real; no se modificó código de producción.  
Estado: hallazgos documentados; no hay build, pruebas automatizadas ni prueba en dispositivos asociadas a esta revisión.

## 1. Archivos exactos inspeccionados

- `app/src/main/java/com/example/matrizapp/MatrizViewModel.kt` — blob SHA `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`
- `app/src/main/java/com/example/matrizapp/Sem6ViewModel.kt` — blob SHA `5110af8d9ed70834d67296992662d37bfa775905`
- `app/src/main/java/com/example/matrizapp/DAOs.kt` — blob SHA `78574574296743fe92a0e51d64170e3f51c628f7`
- `app/src/main/java/com/example/matrizapp/Sem6.kt` — blob SHA `97b13a0c0c43e6c14229c90d48b9dc59c80e3ee0`
- `app/src/main/java/com/example/matrizapp/SheetsRepository.kt` — blob SHA `9a625c6a68d64febc5aa24d75243027389ee25bf`
- `app/src/main/java/com/example/matrizapp/Entities.kt` — blob SHA `ccd9097479375611993b196fd106736357faf1f9`

## 2. Matriz: borrado remoto inmediato y borrado físico local

En `MatrizViewModel.eliminarRegistro()`, la app llama primero a `repository.deleteRowById(Constants.SHEET_MATRIZ, id, Constants.MatrizCols.COL_ID)`. Si la operación no confirma éxito, conserva la fila de Room y devuelve error. Si confirma, ejecuta `matrizDao.deleteById(id)`.

En `SheetsRepository.deleteRowById()`, si la fila ya no existe en la hoja, devuelve `true` (estado remoto deseado ya cumplido); los errores de API lanzan excepción. El borrado de la hoja no forma parte de una transacción común con Room.

**Riesgo para la futura nube:** no existe un tombstone global documentado por este flujo. Si el registro ya se eliminó remotamente pero el dispositivo falla antes de eliminarlo localmente, puede quedar una copia local; si después un proceso de sincronización interpreta esa copia como válida, el registro podría reinsertarse. Es un escenario de riesgo que debe probarse; no afirmamos que siempre ocurra.

## 3. Semana 6: marca de borrado solo en el dispositivo

`Sem6ViewModel.eliminarRegistro()` elimina primero de Room con `sem6Dao.eliminar(hoja, id)`. Si se eliminó una fila, llama a `cacheStore.marcarEliminado(id, hoja)`.

`Sem6CacheStore` vive dentro de `Sem6.kt` y guarda los IDs en SharedPreferences `sem6_cache`, con claves `deleted_ids_<hoja>`. `filtrarEliminados()` oculta las filas cuyos IDs aparecen en esa lista.

Consecuencia confirmada por el diseño del código: la marca no se comparte con otros teléfonos ni constituye un borrado remoto autoritativo. Otro dispositivo sin esa marca puede mostrar o volver a importar la fila. La marca tampoco equivale a una operación pendiente duradera en una cola común en la nube.

## 4. Pase y otros datos: distinguir borrado de fila, copia e historial

- `DAOs.kt` contiene `PaseCarteraDao.eliminar(id)`, que hace un borrado físico local. También existe `PaseSyncOperationEntity` y un DAO con `encolar()`, `pendientes()` y `completar()`; hay una cola local para operaciones de Pase. Antes de migrar, se debe preservar y verificar su comportamiento completo, no sustituirla por un borrado genérico.
- `PaseEntity.origenMatrizId` identifica el registro de Matriz de origen, pero Pase es una copia independiente según la auditoría de referencias previa. Borrar Matriz no debe borrar automáticamente Pase sin una regla de negocio aprobada.
- `VisitaMapaEntity` representa visitas/eventos locales; borrar un cliente no debe borrar a ciegas su historial.
- `ContactoLogEntity` conserva historial de contacto; no debe reescribirse ni eliminarse por cascada sin una política explícita.
- Tickets y Solicitudes tienen consultas de borrado físico en sus DAO. Esta revisión no demuestra por sí sola qué pantallas/acciones las invocan ni si el borrado se propaga a Sheets o a otros dispositivos; queda pendiente rastrear cada llamador antes de decidir su contrato.

## 5. Contrato recomendado antes de implementar nube

1. Toda eliminación operativa compartida debe crear una operación durable local con ID único, identidad estable del registro, tipo de entidad, versión esperada, actor/fecha y estado pendiente.
2. La nube debe conservar un tombstone/versionado que impida que sincronizaciones atrasadas recreen el registro eliminado.
3. Reintentar la misma operación debe ser idempotente. La app solo marca la operación completada tras confirmación remota inequívoca.
4. Una fila borrada no debe reaparecer al importar desde Sheets, refrescar una pantalla, restaurar caché ni sincronizar desde otro teléfono.
5. Definir por entidad si borrar significa: eliminar dato operativo, archivar, cancelar, o conservar historial. No aplicar cascadas globales por conveniencia.
6. Para Semana 6, la eliminación debe incluir la clave compuesta `(hoja, id)`, no solo el ID visible, y propagarse a todos los dispositivos autorizados.
7. Mantener compatibilidad temporal con Sheets mediante una política explícita de reconciliación; una fila que reaparece en Sheets no debe anular automáticamente un tombstone vigente.
8. Las operaciones locales pendientes y la cola existente de Pase deben migrarse/consumirse sin perder ni duplicar cambios.

## 6. Pruebas de aceptación obligatorias

- Borrar Matriz con conexión y verificar el resultado en Room, fuente remota y segundo teléfono.
- Borrar Matriz y cerrar la app inmediatamente después de la confirmación remota; al reiniciar no debe recrearse.
- Borrar sin conexión, reiniciar y reconectar; el borrado debe quedar en cola y aplicarse una sola vez.
- Importar una fila antigua desde Sheets después de borrarla; no debe reaparecer.
- Borrar una fila de Semana 6 y comprobar otro dispositivo, cambio de semana, refresco y reinicio.
- Verificar que un cliente con el mismo ID en otra hoja/semana no se suprima por error.
- Borrar/renombrar Matriz y comprobar la regla acordada para Pase, Ruta IA, ContactoExtra, ContactoLog y VisitaMapa.
- Simular fallo/timeout tras ejecutar el borrado remoto, antes de confirmar el estado local.
- Confirmar que los cambios pendientes de Pase no se pierden ni se duplican.
- Probar dos teléfonos que editan/borran el mismo registro y verificar la resolución de conflicto.

## 7. Decisión de implementación

No implementar todavía borrados en Firestore ni abrir reglas de acceso. Primero cerrar el contrato de identidad, outbox, tombstones y conflicto por entidad; después diseñar migraciones y pruebas. Mantener las reglas de Firestore en deny-all hasta implementar y validar autenticación/autorización.

## 8. Límites de esta revisión

Esta es una auditoría estática parcial de los puntos de borrado local/remoto identificados. No afirma que todos los flujos de borrado de la aplicación estén cubiertos; falta rastrear todos los llamadores de DAO y las rutas UI, y ejecutar pruebas. No se cambió `main`, Room, dependencias, reglas de Firebase ni código productivo.
