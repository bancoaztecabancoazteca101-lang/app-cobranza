# Auditoría de sincronización actual — P0-B

Fecha: 2026-10-26  
Rama: `chatgpt/sincronizacion-integridad-nube`  
Estado: inspección estática del código real; sin cambios de código de producción, sin compilación ni pruebas de dispositivo.

## Archivos revisados

- `SyncWorker.kt` — blob `cb87f911072c0bfdd228468a28f35f3902aa9b2c`
- `SheetsRepository.kt` — blob `9a625c6a68d64febc5aa24d75243027389ee25bf`
- `DAOs.kt` — blob `78574574296743fe92a0e51d64170e3f51c628f7`
- `AppDatabase.kt` — blob `6a641b399e8ae6071a6e46b4629abc115c5a99f1`

## Flujos verificados

### 1. Matriz

1. Toma registros de `getDirtyMatrizItems()`.
2. Si la URI de una imagen empieza por `content://`, intenta subirla a Drive.
3. Busca la fila remota por ID en la hoja Matriz.
4. Si existe, actualiza individualmente varias celdas A–L y N–P; si no existe, agrega una fila.
5. Adopta las imágenes en el almacenamiento local de imágenes y llama `markMatrizAsClean`.

**Protección ya existente:** el DAO `MatrizDao.markAsClean` limpia `isDirty` solo si el registro conserva los valores que se intentaron sincronizar (comparación de campos), lo que ayuda a no borrar un cambio local concurrente durante la subida.

**Riesgos/pendientes:**
- Muchas llamadas de actualización de celda por fila; no es una escritura atómica de registro. Una falla a mitad puede dejar campos parcialmente escritos.
- La creación de fila se hace con `appendRow`; hay que comprobar el orden de columnas y cómo se recupera si la respuesta se pierde después de que Sheets sí agregó la fila, para evitar duplicados en reintentos.
- `MatrizDao.deleteById` elimina la fila local. En el fragmento DAO no existe tombstone de Matriz. Falta rastrear los llamadores para determinar si el borrado remoto se hace antes o se puede perder cuando el dispositivo está offline.
- El ID funciona como clave de búsqueda remota; un cambio de ID necesita una estrategia explícita y reconciliación de colisiones.

### 2. Filtro Fecha

1. Busca la fila por ID en la columna M.
2. Si no existe, lanza excepción y conserva el cambio pendiente.
3. Actualiza estado y hora en dos celdas.
4. Marca limpio solo después de que ambas llamadas terminen sin excepción.

**Conclusión:** el código actual incluye protección para no limpiar el dirty cuando no encuentra la fila. Aun así, las dos escrituras no son una sola transacción; si falla la segunda, la primera pudo haberse aplicado y el reintento repetirá la primera.

### 3. Pase

1. Procesa primero operaciones durables de `PaseSyncOperationEntity`: `DELETE` y `RENAME`.
2. En `DELETE`, exige que `deleteRowById` confirme la eliminación antes de completar la operación.
3. En `RENAME`, detecta colisión si existen ID anterior y nuevo; si solo existe el nuevo y falta el anterior, deja el renombre pendiente por ambigüedad.
4. Después actualiza filas dirty existentes o agrega filas nuevas y las marca limpias.

**Protección ya existente:** es el flujo con cola explícita de operaciones y manejo de renombres/borrados más cuidadoso.

**Riesgos/pendientes:** hay que confirmar el comportamiento ante timeout después de aplicar la operación remota; verificar que cada cambio local de identidad siempre encole la operación en la misma transacción Room y que los borrados no puedan escapar de la cola.

### 4. Ruta IA

Si existe al menos un elemento dirty, carga toda la ruta local, reemplaza el rango de datos de la hoja completa y luego marca limpios todos los elementos cargados.

**Riesgo alto para multi-teléfono:** dos dispositivos pueden editar rutas concurrentemente y el último reemplazo puede borrar cambios del otro. Además, hay manejo de excepciones que ignora errores al limpiar rango/actualizar encabezado en `reemplazarRutaIAEnSheet`; debe verificarse el resultado real antes de marcar la ruta como sincronizada. No migrar este flujo como si fuera un simple upsert por documento.

### 5. Solicitud

Para cada registro dirty, sube audio/imágenes locales a Drive, busca la fila por ID y actualiza campos individualmente o agrega una fila nueva. Después marca limpio y guarda las URLs remotas en Room.

**Riesgos/pendientes:** subida de adjuntos y escritura de fila no forman una transacción; una falla intermedia puede dejar archivos huérfanos o filas parcialmente actualizadas. Para nube se requiere decidir Storage, metadatos, reintentos idempotentes y confirmación de todas las partes antes de marcar sincronizado.

## Datos operativos sin módulo explícito en SyncWorker

El listado de `doWork()` incluye Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha, además de programar la sincronización de imágenes. No incluye explícitamente tickets OCR, visitas del mapa, cartucho diario o Semana 6. La ausencia en este trabajador no descarta otro camino, por lo que falta rastrear sus consumidores y refrescos antes de cerrar el inventario.

## Decisiones de diseño que se desprenden

1. No reemplazar el worker actual de una vez: primero establecer una capa de sincronización con operaciones idempotentes y confirmación remota.
2. Toda entidad compartida necesita ID estable global, control de versiones/conflictos y borrado representado por tombstone o mecanismo equivalente.
3. Marcar sincronizado solo después de confirmar escritura en nube; el marcado debe ser condicional a que el dato local no haya cambiado desde que se preparó el envío.
4. No usar el reemplazo completo de Ruta IA como patrón general.
5. Mantener Firestore cerrado (`deny-all`) hasta que autenticación, autorización y reglas se prueben con usuarios autorizados y no autorizados.
6. Preservar la base Room existente (`DB_VERSION = 38`) y la cola de Pase; no añadir migraciones sin plan y pruebas de actualización desde instalaciones existentes.

## Próximo paso

Rastrear los llamadores reales de `MatrizDao.deleteById`, los flujos de cambio de ID, y los puntos de creación/edición de visitas, cartucho diario y Semana 6. Con eso se puede completar la matriz de CRUD y definir un piloto pequeño sin asumir que todas las tablas son compartidas.

## Verificación

- Código consultado en la rama indicada.
- Sin cambios de producción.
- Sin ejecución de Gradle, pruebas unitarias o pruebas multi-dispositivo.
- `main` no se modificó; PR #122 sigue sin fusionarse.
