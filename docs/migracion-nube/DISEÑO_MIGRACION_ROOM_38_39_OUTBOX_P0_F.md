# Diseño propuesto de migración Room 38 → 39: outbox durable P0-F

**Rama auditada:** `chatgpt/sincronizacion-integridad-nube`  
**Archivo base inspeccionado:** `app/src/main/java/com/example/matrizapp/AppDatabase.kt`  
**SHA base:** `6a641b399e8ae6071a6e46b4629abc115c5a99f1`  
**Estado:** diseño, no implementación. No se modificó AppDatabase ni ninguna entidad de producción.

## 1. Hechos confirmados

- `DB_VERSION = 38`.
- La base declara 23 entidades.
- La migración 37→38 crea `pase_sync_operation_table` con `operationKey` como PK, `type`, `oldId` y `newId`.
- La instancia registra explícitamente migraciones desde 1→2 y 4→38; no se observó fallback destructivo en el fragmento inspeccionado.
- La cola de Pase representa operaciones pendientes de renombrado/borrado y debe conservarse sin reinterpretar ni vaciar.
- Las entidades operativas usan actualmente `isDirty` y `lastSync` de forma desigual; no existe una outbox general para todas las operaciones.
- No hay aún sincronización cloud de producción implementada; esta propuesta no habilita Firestore.

## 2. Objetivo limitado de 38→39

Añadir una tabla local genérica para registrar operaciones que deben sincronizarse, con identidad idempotente y estado de reintento, **sin reemplazar todavía** las rutas actuales de Sheets ni borrar columnas/tablas existentes. La migración solo prepara estructura; el productor/consumidor de la outbox sería un cambio posterior, separado y probado.

## 3. Esquema conceptual propuesto

Nombre provisional: `sync_outbox_table`. Antes de escribir SQL definitivo, comparar el esquema Room generado con cada campo y tipo para que la entidad y SQL coincidan exactamente.

Campos conceptuales:
- `operationId`: ID estable y único de operación; clave primaria.
- `entityType`: tipo de registro (p. ej. Matriz, Pase, Solicitud).
- `entityId`: ID de negocio; para claves compuestas guardar una codificación canónica no ambigua.
- `operationType`: CREATE / UPDATE / DELETE / RENAME, restringido por el código.
- `payload`: snapshot/cambios serializados, formato versionado y sin URI local como URL compartida.
- `baseVersion`: versión remota conocida cuando se generó la operación, nullable durante transición.
- `createdAt`, `updatedAt`: marcas de tiempo locales.
- `attemptCount`, `nextAttemptAt`: reintento con backoff.
- `status`: PENDING / IN_FLIGHT / ACKNOWLEDGED / CONFLICT / FAILED, con transiciones definidas en el worker.
- `lastError`: diagnóstico sanitizado, sin tokens ni datos sensibles innecesarios.
- `schemaVersion`: versión del formato de payload.

Estos nombres son una propuesta de diseño, no campos existentes. No se deben añadir tal cual hasta definir el contrato final, serialización, límites del payload y política de conflicto.

## 4. Tombstones

No se recomienda meter el borrado como simple fila ausente ni eliminar físicamente el tombstone al primer ACK. Diseño conceptual separado para registro eliminado:
- tipo de entidad + ID canónico;
- versión/fecha del borrado;
- autor autenticado;
- operación idempotente;
- política de retención que impida reimportación de filas antiguas.

El tombstone cloud es una decisión de modelo/seguridad posterior. La migración Room 38→39 no debe crear tombstones falsos para registros ya ausentes porque no hay evidencia suficiente de cuándo o por qué desaparecieron.

## 5. Reglas de preservación de datos

1. Crear la nueva tabla con `CREATE TABLE IF NOT EXISTS` y sus índices solo después de fijar el esquema final.
2. No reconstruir ni truncar tablas existentes.
3. No modificar los 23 tipos de entidad existentes en esta migración si no es necesario para el esquema outbox.
4. Mantener intacta `pase_sync_operation_table` y todos sus registros pendientes.
5. No generar operaciones outbox automáticamente a partir de todas las filas `isDirty` durante la migración: podría duplicar operaciones que ya están representadas en Pase o reejecutar acciones cuyo resultado remoto es ambiguo.
6. No importar borrados históricos como hechos confirmados.
7. No cambiar `DB_VERSION` hasta que entidad, SQL, pruebas de migración y validación de esquema estén listas.

## 6. Pruebas obligatorias antes de integrar

- Migrar una base real/esquema de versión 38 a 39 y comprobar que todas las tablas y filas previas siguen presentes.
- Comprobar que las operaciones Pase pendientes antes de migrar siguen exactamente iguales después.
- Abrir base nueva en versión 39.
- Probar caminos de actualización admitidos por las migraciones registradas; confirmar si hay bases existentes en versiones intermedias no cubiertas.
- Validar el esquema generado por Room contra la tabla creada manualmente.
- Simular reinicio durante operación pendiente y verificar que no se pierde ni se duplica.
- Probar idempotencia de inserción/reintento, ACK, conflicto y borrado.
- Probar sin red y con respuesta remota ambigua.
- No permitir que una excepción de migración provoque borrado de datos ni fallback destructivo.

## 7. Bloqueadores antes de implementación

- Acordar campos compartidos/locales del catálogo P0-E.
- Decidir si el outbox conserva snapshots completos o cambios por campo; no elegirlo sin comparar el tamaño/forma de entidades y reglas de conflicto.
- Definir identidad global y renombrado sin duplicados.
- Definir autenticación y autorización de los dispositivos.
- Definir contrato remoto, versionado y confirmación server-side.
- Definir tratamiento de adjuntos y URLs remotas.
- Tener tests de migración reproducibles y evidencia de build.

## 8. Resultado

El paso siguiente no es activar sincronización ni subir filas a Firestore. Es convertir este diseño en una especificación final basada en el catálogo de datos y después implementar una migración pequeña, con pruebas, en la misma rama. `main` y el PR permanecen sin fusionar.

**Verificación actual:** se leyó el `AppDatabase.kt` de la rama indicada y se comprobó DB_VERSION 38, entidades y cadena de migraciones. No se ejecutó build, tests ni migración real. No se modificó código de producción.
