# Verificación de versiones Room en referencias publicadas — P0-K

Fecha: 2026-10-27  
Rama documental: chatgpt/sincronizacion-integridad-nube  
Objetivo: separar evidencia de código en referencias etiquetadas de lo que todavía no puede afirmarse sobre APK instalados en los teléfonos.

## Método y límites

Se consultó el contenido de AppDatabase.kt en las referencias del repositorio que el workflow de publicación usa como tags/release. Esto verifica el esquema que contiene el código etiquetado, pero no inspecciona el APK binario instalado ni prueba qué versión de base de datos tiene un teléfono concreto.

## Referencias inspeccionadas

1. Referencia `latest-debug`: `AppDatabase.kt`, blob SHA `50b69886292939ae9496e0b10cf8f8ded545e93e`. La anotación `@Database` declara `version = 10`. Las migraciones observadas al final de la cadena llegan a 9→10. Esta referencia es una versión etiquetada del repositorio, no evidencia suficiente por sí sola de que un usuario instaló el APK o de cuál versión de Room tiene hoy.
2. Referencia `latest-debug-claude/rendimiento-2`: `AppDatabase.kt`, blob SHA `a5339c4c3f93f3858736dda44f18f63320dc2169`. Declara `version = 34`; la cadena observada llega a 33→34.
3. Referencia `latest-debug-claude/seguimiento-cartuchos`: `AppDatabase.kt`, blob SHA `62830f5c063964144c658718b6a6583e561c0c6b`. Usa `DB_VERSION` y la cadena de migraciones observada llega a 36→37. El valor exacto de `DB_VERSION` debe leerse desde esa misma referencia antes de usarlo como prueba; la referencia se identifica aquí como código de la línea 37 por su última migración, no por una lectura del APK binario.
4. Rama de trabajo `chatgpt/sincronizacion-integridad-nube`: `AppDatabase.kt`, blob SHA `6a641b399e8ae6071a6e46b4629abc115c5a99f1`. Declara `DB_VERSION = 38` y registra `MIGRATION_37_38`.

## Hallazgo importante sobre versiones antiguas

La referencia `latest-debug` declara Room versión 10, y su archivo no muestra migraciones 2→3 ni 3→4. Eso demuestra que esas rutas no están en ese archivo de código etiquetado; no demuestra que existan teléfonos activos con una base 2 o 3, ni permite afirmar que la ruta antigua sea segura o insegura sin conocer el historial de publicación y actualización.

Antes de cambiar la cadena de migraciones, hay que decidir la política soportada: ¿la app debe actualizar cualquier base histórica que haya existido o solo las versiones que todavía están instaladas/soportadas? No inventar una respuesta. La fuente más fuerte sería el historial de releases/APK y, si está disponible, telemetría no sensible de versiones de app/base; no acceder a datos personales de usuarios.

## Decisión

- No crear migraciones ficticias 2→3 o 3→4 sin conocer los esquemas de origen y los cambios reales de esas versiones.
- No borrar ni alterar migraciones existentes.
- Antes de ejecutar la migración 38→39, conseguir un fixture de esquema 38 real y configurar pruebas instrumentadas de Room/SQLite.
- La prueba de 38→39 debe preservar los datos de Matriz y Pase, así como todas las operaciones pendientes de `pase_sync_operation_table`.
- La CI debe ejecutar las pruebas de migración y fallar si alguna falla; el build APK por sí solo no basta.

## Estado

Lectura estática de archivos en referencias de GitHub; no se descargaron ni inspeccionaron APK binarios, no se ejecutó Gradle y no se ejecutaron pruebas. No se modificó código de producción, Room, CI ni Firebase. `main` sigue intacta y el PR #122 no se fusiona sin autorización expresa.

## Siguiente paso exacto

Construir un fixture válido del esquema 38 a partir del modelo Room real y preparar el harness instrumentado. Antes de agregar casos de versiones 2/3, confirmar si esas versiones fueron distribuidas y siguen dentro de la política de actualización. Después implementar 38→39 y añadir la tarea de prueba al workflow, con ejecución real registrada.