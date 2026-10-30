# Inspección completa del árbol de pruebas y rutas Room — P0-I

**Rama inspeccionada:** `chatgpt/sincronizacion-integridad-nube`  
**Commit de referencia:** `eac53cfb591818c21770543dc0c75082a3477556`  
**Estado:** hallazgos de inspección; no se cambió código de producción ni se ejecutaron pruebas.

## 1. Árbol del repositorio

La consulta recursiva de GitHub devolvió 165 entradas y `truncated=false`.

En el árbol consultado:
- No hay `app/src/test`.
- No hay `app/src/androidTest`.
- No aparece `gradlew` ni `gradlew.bat`.
- No aparece un directorio `gradle/wrapper`.
- Las fuentes de la app están bajo `app/src/main/java/com/example/matrizapp`.
- El workflow `.github/workflows/build.yml` instala Gradle 8.4 mediante `gradle/actions/setup-gradle@v3` y compila variantes APK. No ejecuta tareas de pruebas.

Esto describe el árbol exacto consultado, no otros branches o clones locales.

## 2. Infraestructura de pruebas pendiente

El archivo `app/build.gradle` usa Room 2.6.1. En las dependencias revisadas no aparecen JUnit, AndroidX Test ni `room-testing`.

Antes de añadirlas se debe decidir el enfoque de pruebas:
- **Prueba instrumentada con Room MigrationTestHelper:** comprueba la migración SQLite real y el esquema, pero necesita dependencias AndroidX Test y un entorno Android disponible en CI.
- **Prueba unitaria pura:** útil para lógica aislada, pero por sí sola no acredita que Room abra una base histórica y valide el esquema SQLite real.

Para la migración de Room se necesita una prueba que ejercite SQLite/Room real, no solo una prueba que inspeccione el texto SQL.

## 3. Rutas de migración

`AppDatabase.kt` registra `MIGRATION_1_2` y las migraciones de `4_5` en adelante hasta `37_38`. No se observa una migración `2_3` ni `3_4` en el archivo inspeccionado.

No debe concluirse automáticamente que esas rutas estén rotas: antes hay que verificar el historial de versiones distribuidas, si existen instalaciones con esas versiones y qué esquema tenían. Pero sí es una cuestión que requiere resolución antes de afirmar que cualquier base antigua puede actualizarse sin riesgo.

La migración 38→39 debe añadirse a la lista de migraciones explícitas y probarse junto con las rutas de actualización que el proyecto realmente deba soportar.

## 4. Secuencia segura propuesta

1. Determinar las versiones mínimas instaladas en dispositivos reales y la política de actualización soportada.
2. Crear pruebas instrumentadas de migración compatibles con Gradle 8.4 y el workflow actual.
3. Preparar una base versión 38 con registros representativos en tablas existentes y varias operaciones pendientes en `pase_sync_operation_table`.
4. Migrar y comparar el contenido antes/después, incluido cada campo de las operaciones Pase.
5. Validar la creación de una base nueva y el esquema Room exportado.
6. Añadir la tarea de pruebas al workflow y comprobar que un fallo haga fallar el job.
7. Solo entonces implementar la migración 38→39 en una modificación separada, manteniendo la outbox Pase existente y sin generar operaciones automáticamente desde `isDirty`.

## 5. Estado verificable

- Base Room: versión 38; 23 entidades registradas.
- Cola Pase: `pase_sync_operation_table`, con clave `operationKey`.
- Infraestructura de pruebas: no aparece en el árbol de esta rama.
- Gradle Wrapper: no aparece en el árbol de esta rama.
- Build y pruebas: no ejecutados en esta revisión.
- Código de producción: sin cambios en esta revisión.
- `main`: sin cambios.
- Firebase: sin nuevas dependencias, reglas ni escrituras habilitadas.

## 6. Criterio de salida

No se declara lista la migración hasta contar con pruebas reproducibles, protección demostrada de los datos existentes y de la cola Pase, revisión de las rutas antiguas que deban soportarse y resultado real de compilación/pruebas registrado.
