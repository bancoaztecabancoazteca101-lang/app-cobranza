# Flujos reales de renombre, borrado y referencias — P0

**Rama:** `chatgpt/sincronizacion-integridad-nube`  
**Tipo:** auditoría estática del código fuente actual de la rama.  
**Resultado:** análisis documentado; no es una implementación ni una prueba de ejecución.

## Archivos revisados

- [MatrizViewModel.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/MatrizViewModel.kt), blob `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`.
- [SheetsRepository.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/SheetsRepository.kt), blob `9a625c6a68d64febc5aa24d75243027389ee25bf`.
- [ContactoExtra.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/ContactoExtra.kt), blob `c42e30456e18cfdd7a4dcc5fedb55d5fd844d256`.
- [DAOs.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/DAOs.kt), blob `78574574296743fe92a0e51d64170e3f51c628f7`.

## 1. Flujo actual de cambio de ID de Matriz

En `MatrizViewModel.cambiarIdYGuardar()` el flujo observado es:

1. Recorta el ID nuevo; si queda vacío, conserva el anterior.
2. Si el ID cambia, consulta primero si ya existe en Room.
3. Busca el ID nuevo y el anterior en la hoja Matriz.
4. Rechaza la colisión si el ID nuevo pertenece a otra fila remota.
5. Si la fila anterior existe en Sheets, ejecuta `SheetsRepository.renameRowId()`; si devuelve false, aborta.
6. Si el renombre remoto parece confirmado, ejecuta `MatrizDao.renameId()` localmente.
7. Después actualiza el resto de los campos del registro local, marca el registro dirty, registra visita si corresponde a hoy y encola la sincronización normal.

### Riesgo de operación parcial

Sheets y Room no participan en una transacción común. El renombre remoto ocurre antes del cambio local. Si Sheets cambia el ID y la app se cierra o falla antes de completar Room, las dos fuentes pueden quedar temporalmente con IDs distintos. En un reintento, el método busca el ID anterior en remoto; si ya no está, `renameRowId()` devuelve false y el flujo aborta en lugar de reconciliar explícitamente el estado “ya renombrado”.

La función remota actual vuelve a buscar la fila por ID anterior y actualiza una celda. No implementa una operación idempotente que reconozca “el ID nuevo ya es la misma fila que se intentaba renombrar”. Este comportamiento se debe corregir en el diseño de migración, no mediante una cascada improvisada.

Cuando el registro aún no existe remotamente, el código omite el renombre remoto y cambia el ID en Room, dejando el registro dirty para que la sincronización lo agregue. Eso es distinto del caso en que la fila ya existe en Sheets y debe conservarse como una misma identidad.

## 2. Flujo actual de eliminación de Matriz

`MatrizViewModel.eliminarRegistro()`:
- solicita borrar la fila en Sheets;
- si no se confirma, conserva el registro local y muestra error;
- solo después de una respuesta positiva llama a `MatrizDao.deleteById(id)`.

`SheetsRepository.deleteRowById()` considera exitoso que la fila ya no exista; si la encuentra, ejecuta un `batchUpdate` para eliminar la fila. Esto es idempotente para el estado remoto deseado, pero no crea una marca de borrado compartida.

### Consecuencia para la futura nube

No existe en este flujo un tombstone local duradero para Matriz. El borrado depende de la conexión y la confirmación de Sheets. En el modelo cloud se necesita registrar el borrado como una operación durable, compartirlo con los dispositivos autorizados y evitar que una sincronización posterior vuelva a insertar el registro eliminado.

## 3. Contactos extra y referencias

`ContactoExtraEntity` tiene una clave primaria autogenerada y un campo `clienteId`; su DAO consulta contactos por ese ID y evita duplicados por combinación de cliente y teléfono. No se encontró una consulta DAO para renombrar o reasignar `clienteId` al cambiar el ID de Matriz.

Por tanto, si se renombra el ID visible del cliente, los contactos extra pueden seguir asociados al ID anterior. En la nube conviene referenciarlos por identidad estable del cliente, no depender exclusivamente del ID visible. Antes de implementar, debe decidirse cómo reparar referencias ya existentes sin perder teléfonos ni duplicarlos.

El historial de visitas usa claves de texto como `M:<id>` y guarda también `matrizId`. El DAO de visitas inspeccionado no ofrece operación de renombre de esa clave. No se debe cambiar automáticamente el historial histórico sin definir si las visitas deben conservar el ID con el que se registraron o enlazarse a la identidad estable.

## 4. Decisiones de contrato para diseño cloud

1. **Identidad estable:** crear un identificador interno inmutable para el cliente. El ID de Sheets/AppSheet se conserva como identificador externo visible mientras exista el puente de Sheets.
2. **Renombre como operación explícita:** registrar ID anterior, ID nuevo, identidad estable, versión y estado de operación. Validar colisiones y reconciliar reintentos cuando el cambio remoto se completó pero la confirmación local se perdió.
3. **No cascada indiscriminada:** definir por entidad cómo resolver identidad:
   - Semana 6: conservar registro y notas por identidad/semana; borrar una fila de una semana no equivale a eliminar al cliente de Matriz.
   - Contactos extra: conservar relación con la identidad estable del cliente.
   - Visitas: conservar evento histórico, enlazado a identidad estable; no eliminarlo como efecto colateral del renombre.
   - Pase: seguir siendo una entidad independiente, salvo decisión explícita.
4. **Borrados durables:** usar tombstones con ámbito y versión, y no eliminar físicamente la única evidencia del borrado hasta que la política de sincronización lo permita.
5. **Outbox idempotente:** operaciones pendientes con clave estable, reintentos y estados explícitos; no marcar una operación como confirmada solo por haber iniciado una llamada remota.
6. **Conflictos:** para cambios simultáneos en teléfonos distintos, usar versión/ETag o una política de resolución explícita. No usar “última escritura gana” para todos los campos sin analizar riesgo de pérdida.

## 5. Casos de prueba necesarios antes de código productivo

- Renombrar con conexión normal y verificar Matriz, Semana 6, contactos extra y referencias de mapa.
- Renombrar un registro local aún no subido.
- Interrumpir el proceso después del renombre remoto y antes del cambio local; reintentar sin duplicar ni perder al cliente.
- Colisión con un ID ya usado localmente o remotamente.
- Borrar Matriz sin conexión: conservar operación pendiente y no permitir que una descarga resucite el cliente.
- Borrar Semana 6 en teléfono A y confirmar que el teléfono B no lo recree desde Matriz.
- Editar notas de Semana 6 en A y B simultáneamente y detectar el conflicto.
- Renombrar con contactos extra existentes y verificar que ningún teléfono quede huérfano.
- Verificar que un borrado de Semana 6 no borre Matriz ni el historial de visitas.

## 6. Pendientes y límites

- La búsqueda directa de `Sem6CacheStore.kt` por esa ruta devolvió 404; no se concluye que la clase no exista, solo que debe localizarse su archivo/ruta exacta en la rama.
- Aún falta inventariar todos los campos que referencian el ID visible, con búsquedas en la rama exacta y revisión de llamadas automáticas/consultas relacionadas.
- Esta revisión no ejecutó compilación, pruebas ni teléfono real.
- No se modificó código de producción, Room, dependencias ni reglas Firebase.
- No se modificó `main` ni se fusionó PR #122.
- Firestore debe seguir con reglas de denegación total hasta completar autenticación, autorización y pruebas.
