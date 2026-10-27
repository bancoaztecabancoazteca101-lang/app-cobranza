# Puerta de pruebas para Room 38→39 y outbox — P0-H

**Rama:** `chatgpt/sincronizacion-integridad-nube`  
**Estado:** auditoría de infraestructura y plan; no es implementación.  
**Alcance:** preparar pruebas reproducibles antes de modificar la versión Room o las tablas de producción.

## 1. Evidencia revisada

- `app/build.gradle`: Room runtime/ktx/compiler 2.6.1. En el bloque `dependencies` revisado no aparecen JUnit, AndroidX Test ni `room-testing`.
- `.github/workflows/build.yml`: compila variantes APK; no se observó un paso que ejecute pruebas unitarias o instrumentadas.
- GitHub devolvió 404 para `app/src/test` y `app/src/androidTest` en esta rama. Esto confirma que esas rutas no están presentes; no demuestra que no exista alguna prueba en una ruta no convencional.
- `AppDatabase.kt` previamente inspeccionado: `DB_VERSION = 38`, 23 entidades registradas y migración 37→38 que crea `pase_sync_operation_table`.
- `PaseSyncOperationEntity` en `Entities.kt`: clave primaria `operationKey`, campos `type`, `oldId`, `newId`.

No se ejecutó Gradle ni se ejecutó una prueba real durante esta revisión. Esta puerta registra la inspección de archivos remotos, no un resultado de compilación.

## 2. Bloqueo actual

No es seguro cambiar `DB_VERSION` a 39 hasta que haya un mecanismo reproducible para comprobar que:
1. una base con esquema 38 abre tras migrar a 39;
2. las filas de negocio y las tablas existentes permanecen;
3. cada operación pendiente de Pase se conserva exactamente;
4. el esquema de la tabla outbox coincide con el esquema exportado/generado por Room;
5. una instalación nueva puede crear la base 39;
6. el camino de migración desde las versiones realmente soportadas no queda roto.

## 3. Trabajo previo requerido

1. Inspeccionar el árbol completo de `app/src` y cualquier configuración de pruebas no convencional antes de afirmar que no hay pruebas en el proyecto.
2. Definir dependencias de pruebas compatibles con la configuración Android/Gradle del repositorio. No añadirlas hasta comprobar versiones y estructura actuales.
3. Crear prueba de migración con una base de versión 38 que contenga registros de varias tablas y al menos operaciones Pase pendientes de tipos distintos.
4. Comparar antes/después el contenido de `pase_sync_operation_table`, no solo contar filas.
5. Validar el esquema Room generado para la entidad outbox frente al SQL de migración.
6. Conectar la ejecución de pruebas a GitHub Actions y exigir que falle el job si una prueba falla.
7. Solo después, implementar 38→39 en esta rama y ejecutar build y pruebas. Registrar IDs de ejecuciones, commit y resultados reales.

## 4. Contrato de outbox: decisiones que aún no se deben inventar

- **Identidad:** conservar el ID visible de negocio por compatibilidad con Sheets; no asumir que es una identidad inmutable para todas las entidades. Las claves compuestas necesitan codificación inequívoca.
- **Payload:** no fijar todavía JSON ni serializador. Primero comparar entidades, tipos, valores nulos, tamaños, campos locales y cambios por campo; decidir si cada operación guarda snapshot o delta.
- **Reintentos:** el ID de operación debe ser estable entre reintentos; el ACK debe depender de confirmación del servidor, no del envío.
- **Conflictos:** no aplicar “última escritura gana” globalmente. La política debe definirse por entidad/campo y versión base.
- **Borrados:** una outbox local no sustituye el tombstone durable necesario en el servidor; no inferir borrados históricos de filas ausentes.
- **Adjuntos:** no almacenar URI local como si fuera URL remota. Archivo y registro requieren estados de carga/confirmación diferenciados.
- **Pase:** la cola especializada existente se conserva. Su eventual adaptación a la outbox común requiere una fase separada y pruebas contra duplicados.

## 5. Criterio para autorizar la migración

La migración 38→39 se considera lista para intentar únicamente cuando existan: esquema final revisado, pruebas reproducibles, protección de datos de Pase, validación de esquema Room y un build que ejecute esas pruebas. Hasta entonces, no cambiar la versión de la base, no añadir escrituras cloud y no alterar reglas Firebase.

## 6. Estado

- Rama de trabajo: `chatgpt/sincronizacion-integridad-nube`.
- `main`: sin cambios en este paso.
- Código de producción: sin cambios en este paso.
- Build y pruebas: no ejecutados.
- Próximo paso: inspección completa del árbol de fuentes y confirmación de la ruta de pruebas; después preparar la infraestructura de pruebas, sin activar sincronización cloud.
