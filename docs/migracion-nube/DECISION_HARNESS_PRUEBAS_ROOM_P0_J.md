# Decisión de estrategia de pruebas Room — P0-J

Fecha: 2026-10-27  
Rama: chatgpt/sincronizacion-integridad-nube  
Alcance: preparar pruebas de migración y preservar datos locales antes de introducir una nueva tabla outbox. No modifica código de producción.

## Evidencia directa inspeccionada

Archivos leídos desde la rama actual:

- .github/workflows/build.yml
- app/build.gradle
- app/src/main/java/com/example/matrizapp/AppDatabase.kt

### CI actual

El workflow usa JDK 17 y Gradle 8.4. Ejecuta assembleDebug assembleRelease assembleOptimizado en ramas distintas de main. No ejecuta tareas test ni connectedAndroidTest. Por tanto, un build verde actual no demuestra que una migración Room funcione ni que preserve los datos.

### Configuración de pruebas actual

En las dependencias de app/build.gradle se observan Room Runtime/KTK/compiler 2.6.1, pero no se observan androidx.test, androidx.room:room-testing ni JUnit. En el árbol revisado no existen carpetas app/src/test ni app/src/androidTest; tampoco wrapper Gradle (gradlew, gradlew.bat, gradle/wrapper). El workflow instala Gradle 8.4 directamente.

### Estado Room

- DB_VERSION = 38.
- @Database registra 23 entidades.
- exportSchema = false.
- MIGRATION_37_38 crea pase_sync_operation_table, con operationKey como PK, type, oldId y newId.
- La migración 38→39 aún no existe.
- La cadena registrada incluye 1→2 y 4→5 en adelante; no incluye 2→3 ni 3→4. Esto no basta para concluir que usuarios reales se queden bloqueados: primero se deben comprobar versiones de base de datos presentes en APK distribuidos y si la app alguna vez publicó esquemas 2 o 3.

## Decisión técnica provisional

No activar todavía MIGRATION_38_39 ni cambiar DB_VERSION. Primero establecer un harness que ejecute pruebas reales y pueda verificar el estado antes/después de la migración.

Para la migración de Room, la opción preferida es una prueba instrumentada con SQLite/Room en Android, dado que reproduce mejor el motor SQLite del dispositivo. Antes de adoptar MigrationTestHelper, resolver explícitamente el problema de esquema: Room actualmente declara exportSchema = false, así que el harness no debe depender de archivos de esquema que no se generan. La implementación deberá escoger y documentar una de estas vías con evidencia de compilación:

1. Activar exportación de esquemas Room a una ruta versionada y aportar un fixture válido de esquema 38, si se puede producir a partir de la definición exacta del modelo actual.
2. Crear una base SQLite de fixture controlada con el esquema 38 real y abrirla con la migración de Room, sin fingir que una base vacía prueba preservación de datos.

No inventar un fixture con columnas aproximadas: debe validarse contra las entidades/DAOs y el esquema generado por Room.

## Casos mínimos de prueba para 38→39

1. Conservación de Matriz: crear al menos un registro con campos representativos, migrar y comparar los valores antes/después.
2. Conservación de Pase y cola existente: insertar un Pase y operaciones DELETE/RENAME representativas en pase_sync_operation_table; después de migrar, comprobar que la tabla, cada clave y sus valores continúan intactos.
3. Outbox nueva: verificar tabla, PK, restricciones y valores por defecto del diseño aprobado.
4. Idempotencia estructural: abrir la base migrada otra vez sin recrear tablas ni perder filas.
5. Validación Room: abrir la base con la clase AppDatabase real para detectar discrepancias de esquema.
6. Versiones antiguas: solo agregar casos para 2/3 u otras versiones cuando el inventario de APK/etiquetas confirme que esas versiones fueron distribuidas y todavía pueden actualizarse.

## Ajustes de CI necesarios cuando el harness esté listo

- Ejecutar la tarea de pruebas instrumentadas en un emulador compatible (por ejemplo, Android Emulator con API soportada por el proyecto).
- Hacer que el workflow falle si las pruebas de migración fallan; no ocultar fallos con continue-on-error.
- Mantener la construcción de APK separada de la afirmación de pruebas de integridad.
- Registrar URL de ejecución, commit exacto y resultado real en la bitácora.
- No publicar un APK como validado por pruebas si solo se ejecutó assemble*.

## Límites y estado al 2026-10-27

- Esta entrada es documentación basada en lectura estática de los archivos citados; no modifica Kotlin, Gradle ni CI.
- No se ejecutó compilación ni prueba automatizada durante esta revisión.
- No hay evidencia todavía de qué versiones Room contienen los APK más antiguos distribuidos.
- No se habilita Firestore ni se altera su regla de denegación total.
- main sigue intacta; PR #122 no debe fusionarse sin autorización expresa.
- Scripts de llamadas/SMS fuera de alcance, según instrucción del usuario.

## Siguiente paso exacto

Inspeccionar tags/releases y los APK disponibles para identificar la versión máxima/mínima de Room realmente distribuida; después decidir si hay rutas antiguas que deban soportarse. En paralelo, preparar el harness instrumentado y validar el fixture de esquema 38. Solo con esas dos evidencias implementar 38→39 y añadir la ejecución de pruebas a CI.
