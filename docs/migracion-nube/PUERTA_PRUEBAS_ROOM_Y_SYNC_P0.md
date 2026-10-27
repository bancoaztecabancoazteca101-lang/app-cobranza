# Puerta de pruebas Room y sincronización P0

Fecha de revisión: 27 de octubre de 2026. Rama: `chatgpt/sincronizacion-integridad-nube`.

## Estado y evidencia del repositorio

- `app/src/main/java/com/example/matrizapp/AppDatabase.kt`, blob `6a641b399e8ae6071a6e46b4629abc115c5a99f1`: `DB_VERSION = 38`; `exportSchema = false`; la migración `MIGRATION_37_38` crea `pase_sync_operation_table` con columnas `operationKey TEXT NOT NULL PRIMARY KEY`, `type TEXT NOT NULL`, `oldId TEXT NOT NULL`, `newId TEXT`.
- La tabla de operaciones de Pase se utiliza para persistir operaciones de renombre/eliminación. La migración no debe borrar ni reinicializar esta tabla ni su contenido.
- `app/build.gradle`, blob `9a5f44092bf87b94b4a3418839002fa4ed9da55e`: Room `2.6.1`; no aparecen dependencias de pruebas JUnit, AndroidX Test o `room-testing` en este archivo.
- `settings.gradle` incluye `:app`; no se encontró `gradle/libs.versions.toml` ni `app/schemas` en esta rama. `exportSchema=false` impide usar una exportación de esquema versionada que no existe en el repositorio.
- `.github/workflows/build.yml`, blob `c4a817f75d93e66e5ea21a2b807e704459cb7bde`: instala Gradle 8.4 y compila variantes; el workflow no contiene un paso de pruebas unitarias/instrumentadas. El wrapper `gradlew` no se encontró en la raíz en la inspección previa.
- La compilación del cambio `f7b021314b75bfbc8f624e15b0c167e4e07af659` no se puede considerar aprobada: la ejecución de PR `40527045342` fue cancelada. Después se observó una ejecución `40527186356` pendiente asociada al commit documental `2e58976abd16eee0ca76c5b73eea9ba2cc98b1d4`; no demuestra que el código compilara. Verificar una ejecución nueva sobre el HEAD actual antes de afirmar compilación.

## Regla de seguridad

No aumentar `DB_VERSION`, no añadir migraciones destructivas y no cambiar las reglas de Firebase hasta disponer de pruebas reproducibles. No borrar datos de teléfonos reales para probar. No asumir que los teléfonos tienen Room 37 o 38: registrar el `BUILD_SHA`/versión instalada de cada dispositivo o usar una copia aislada de su base de datos.

## Bloque 0 — preparar pruebas

1. Crear un entorno reproducible de Android/Gradle con JDK 17 y Gradle 8.4; si se añade wrapper, fijar la versión y verificar sus archivos/checksums en un cambio separado.
2. Añadir dependencias de pruebas compatibles con Kotlin/AGP existentes (`junit`, AndroidX test runner, `androidx.room:room-testing:2.6.1`) en un cambio aislado. No mezclar esta habilitación con cambios de esquema.
3. Habilitar exportación Room (`exportSchema=true`) y configurar `room.schemaLocation` mediante KAPT antes de basar pruebas en snapshots; generar el snapshot real con compilación del código, no escribirlo a mano. Esto debe tratarse como infraestructura de pruebas y revisarse en diff.
4. Obtener una base SQLite de prueba de cada versión distribuida que realmente siga en uso, con datos anonimizados, o recuperar el APK exacto y extraer/inspeccionar su esquema. Registrar SHA del APK, `BUILD_SHA`, `DB_VERSION` y hash del archivo de prueba. No usar datos personales productivos en el repositorio.

## Bloque 1 — migración 37 → 38

Preparar base de versión 37 con datos de Matriz, Pase y cero/varias operaciones pendientes; migrar con `MigrationTestHelper`/Room testing a 38 y comprobar:

- todas las filas y valores anteriores siguen presentes;
- se crea `pase_sync_operation_table` con tipos, nulabilidad y clave primaria esperados;
- operaciones pendientes ya presentes en la base de prueba sobreviven a abrir/reabrir la DB (si la tabla no existía en v37, documentar que la migración crea una cola vacía; no inventar operaciones previas);
- `PRAGMA integrity_check` devuelve `ok`;
- Room abre la base sin fallback destructivo.

La migración 37→38 añade la tabla de cola; no hay evidencia de que reconstruya datos existentes. La prueba debe validar el esquema final completo generado por Room, no solo que la sentencia `CREATE TABLE` no falle.

## Bloque 2 — integridad de renombre de Matriz desde Filtrar

Usar DAO/repositorio falsos o un backend de prueba aislado y verificar:

1. ID destino libre; remoto anterior encontrado; renombre remoto confirma `true`: se cambia el ID local una sola vez.
2. ID destino ya existe en Room: se rechaza y no se modifica el registro original.
3. ID destino ya existe en Sheets para otra fila: se rechaza y no se modifica Room.
4. `renameRowId()` devuelve `false`: se informa error y el ID local anterior se conserva.
5. excepción de red: se informa error y el ID local anterior se conserva.
6. registro anterior aún no existe en Sheets: el ID local cambia y queda correctamente marcado/gestionado como alta pendiente para el siguiente push; verificar en el código de guardado y en una prueba de sincronización que realmente se sube el ID nuevo.
7. reintento tras fallo ambiguo: no crea dos registros ni deja el mismo cliente con dos IDs.

Importante: el cambio reciente hace comprobaciones previas y verifica el resultado del renombre remoto, pero las llamadas a Sheets y el cambio Room no forman una transacción distribuida. Una caída del proceso entre ambos sistemas todavía requiere prueba y estrategia de reconciliación.

## Bloque 3 — cola de Pase e idempotencia

- Encolar renombre/eliminación y verificar que la operación y el cambio local se guardan en una única transacción Room.
- Interrumpir el worker antes, durante y después de la llamada remota; reanudar y comprobar que la operación pendiente no se pierde ni duplica datos.
- Simular respuesta remota ambigua tras aceptar una escritura y comprobar que el reintento no duplica filas.
- Verificar que un renombre no se marca completo antes de confirmar que la fila remota quedó en el estado correcto.
- Probar oldId ausente/newId presente, ambos ausentes y ambos presentes; cada caso debe tener resultado explícito y observable.

## Bloque 4 — dos dispositivos

Solo después de que los bloques 1–3 pasen en pruebas controladas: usar dos teléfonos de prueba con builds identificadas y datos no productivos; ejecutar renombre, borrado y edición concurrentes, pérdida de red, reinicio y reintento. Comparar IDs, filas y operaciones pendientes. No probar con datos reales hasta acordar una copia/restauración segura y un plan de reversión.

## Criterios de salida

- CI verde para el commit exacto de código y pruebas ejecutadas en ese commit.
- Pruebas de migración y de los bloques 2–3 reproducibles, con resultados registrados.
- No se pierde ni duplica ningún registro en los escenarios cubiertos.
- Los casos no resueltos se mantienen bloqueantes; no se declara sincronización global lista por el mero hecho de compilar.

## Fuera de alcance de esta puerta

Firestore productivo, reglas permisivas, Apps Script, migración de `TicketPagoEntity`, definición de propiedad global/local de `CartuchoDiaEntity`, sincronización de Semana 6, tombstones globales de Matriz y reemplazo completo de Ruta IA. Estos asuntos requieren decisiones/pruebas propias; no deben colarse en esta prueba P0.