# Puerta de calidad antes de implementar Room 38→39 — P0-G

**Rama:** `chatgpt/sincronizacion-integridad-nube`  
**Estado:** revisión de infraestructura de pruebas; no se modificó código.

## Evidencia inspeccionada

- `app/src/main/java/com/example/matrizapp/AppDatabase.kt`, SHA `6a641b399e8ae6071a6e46b4629abc115c5a99f1`: `DB_VERSION = 38`, 23 entidades, cadena de migraciones hasta `MIGRATION_37_38`; esta última crea `pase_sync_operation_table`.
- `app/build.gradle`, SHA `9a5f44092bf87b94b4a3418839002fa4ed9da55e`: Room runtime/ktx/compiler 2.6.1, coroutines Android 1.7.3 y Firebase BoM/Messaging; el bloque de dependencias visible no declara `room-testing`, JUnit, AndroidX test runner ni dependencias de instrumentación.
- Las búsquedas de `MigrationTest`, `AppDatabaseTest`, `MIGRATION_37_38` y `androidTest` no devolvieron pruebas. Esto no demuestra por sí solo que no existan pruebas en todo el repositorio; significa que no pude verificar una prueba de migración con las herramientas de búsqueda disponibles.
- Intentar leer las rutas de carpetas `app/src/androidTest/...` y `app/src/test/...` como si fueran archivos no es evidencia de que las carpetas no existan; la API no devolvió un listado válido.

## Decisión de calidad

**No es responsable cambiar `DB_VERSION` ni agregar una migración 38→39 todavía.** No hay evidencia de una prueba ejecutable para demostrar que:
1. Room abre una base real de versión 38 después de migrar.
2. Las filas existentes permanecen intactas.
3. `pase_sync_operation_table` conserva sus operaciones pendientes.
4. El esquema SQL manual coincide con el esquema que Room espera para la entidad nueva.

## Prueba mínima requerida antes del cambio de versión

Crear/confirmar una prueba instrumentada con Room 2.6.1 y una base SQLite de versión 38:
- preparar el esquema 38 mediante exportación/esquema histórico fiable o fixture generado desde una instalación real de versión 38;
- insertar filas representativas en tablas existentes y al menos una operación DELETE y una RENAME pendiente en `pase_sync_operation_table`;
- ejecutar exactamente la migración 38→39;
- verificar recuentos, valores, claves y operaciones pendientes antes/después;
- abrir la base con el `AppDatabase` de versión 39 y validar el esquema;
- probar también la creación limpia de una base nueva versión 39.

No basta con probar una base vacía, porque no detecta pérdida de datos ni pérdida de operaciones pendientes.

## Dependencias de prueba

El `app/build.gradle` inspeccionado no declara dependencias de test en el bloque visible. Antes de escribir la prueba, confirmar la configuración Android test existente y agregar únicamente las dependencias que falten, por ejemplo Room testing compatible con 2.6.1, AndroidX test runner y JUnit. No introducir versiones sin verificar la compatibilidad con el proyecto.

## Condiciones para autorizar la implementación

- El contrato final de la outbox define la serialización, claves, estados y política de conflictos; la propuesta conceptual P0-F todavía no basta para congelar la entidad.
- El esquema de la tabla y su SQL se derivan del contrato final, no de nombres provisionales.
- La migración preserva `pase_sync_operation_table` sin recrearla ni borrarla.
- Se puede ejecutar una compilación y las pruebas con evidencia real.
- El cambio queda aislado en esta rama y no se fusiona el PR.

## Estado

No se cambió `DB_VERSION`, no se añadió entidad outbox, no se añadieron dependencias, no se modificó código de producción y no se afirma que exista un build/test exitoso. El próximo paso es establecer la infraestructura de prueba y cerrar el contrato de la outbox; luego implementar y verificar la migración.
