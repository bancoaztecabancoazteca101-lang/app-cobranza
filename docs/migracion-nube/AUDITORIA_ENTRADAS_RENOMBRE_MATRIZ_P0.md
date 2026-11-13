# Auditoría de entradas de renombre de Matriz — P0

Fecha: 2026-10-26  
Rama inspeccionada: `chatgpt/sincronizacion-integridad-nube`  
Tipo de trabajo: lectura estática de archivos Kotlin de la rama. No se modificó código de producción ni se ejecutaron builds/pruebas. `main` intacta.

## Hallazgo nuevo: el renombre no pasa por una única ruta

Se confirmaron dos implementaciones de renombre/guardado de un registro de Matriz:

1. `MatrizViewModel.cambiarIdYGuardar()` en `app/src/main/java/com/example/matrizapp/MatrizViewModel.kt`.
2. `FiltrarViewModel.guardarRegistroCompleto()` en `app/src/main/java/com/example/matrizapp/FiltrarViewModel.kt`.

Ambas llaman a `repository.renameRowId(...)` antes de `matrizDao.renameId(...)`, pero la implementación no es equivalente.

### Diferencias comprobadas

- En `MatrizViewModel.cambiarIdYGuardar()`, el código revisado consulta si el ID nuevo ya existe localmente y consulta los IDs anterior/nuevo en Sheets antes de renombrar. Si el ID anterior no existe remotamente, trata el registro como alta local pendiente.
- En `FiltrarViewModel.guardarRegistroCompleto()`, el fragmento revisado intenta renombrar directamente la fila remota y después cambia el ID en Room. No se observa en esa función la misma validación explícita de colisión local/remota que sí aparece en la ruta de Matriz.
- La ruta de Filtrar llama luego a `matrizDao.updateRegistroCompleto(...)` y dispara sincronización. Por tanto, el comportamiento ante ID duplicado, fila remota ausente o interrupción entre las dos escrituras puede diferir según la pantalla desde la que se edite.
- `MatrizDao.renameId()` cambia únicamente la columna `id` en `matriz_table` y marca la fila dirty. No actualiza en esa misma operación `VisitaMapaEntity`, `ContactoExtraEntity`, `ContactoLogEntity`, `RutaIAEntity.cuMatrizMatch`, `PaseEntity.origenMatrizId` ni la clave compuesta de Semana 6. Esto no demuestra que todas deban cambiar; demuestra que no hay una transacción única que las coordine.

## Implicación para el diseño

No conviene implementar sincronización en la nube como una llamada añadida a una de estas funciones. Primero se debe consolidar el contrato de renombre para todas las entradas, con una operación idempotente de dominio y referencias explícitas según su semántica.

Contrato propuesto para evaluación (no implementado):

1. Resolver la identidad interna inmutable del cliente; el ID visible/externo se maneja como alias editable.
2. Validar colisión de alias con respuesta consistente, tanto en local como en el servidor.
3. Registrar una operación durable de renombre en outbox local antes de depender de conectividad.
4. Reintentar de forma idempotente si el servidor ya tiene el alias nuevo asociado a la misma identidad.
5. Actualizar en transacción las referencias locales que deban seguir al cliente; conservar eventos históricos que deban retener el ID con el que se registraron.
6. No marcar operación completada hasta confirmación remota y reconciliación local.
7. Reutilizar el mismo caso de uso desde Matriz, Filtrar y formularios abiertos desde Ruta IA.

## Pruebas de aceptación mínimas

| Escenario | Resultado requerido |
|---|---|
| Renombre desde Matriz con red | Un solo cliente, alias actualizado y referencias vigentes coherentes. |
| Renombre desde Filtrar con alias ocupado local/remoto | Rechazo claro; ningún cambio parcial en Room o Sheets. |
| Renombre offline | La operación queda en cola durable; la interfaz no afirma que ya se sincronizó. |
| Caída después de cambiar alias remoto y antes de Room | El reintento reconoce que el alias nuevo ya pertenece a la misma identidad y reconcilia sin duplicar. |
| Alta local aún no subida, luego renombrada | Se sube un solo registro con el alias final. |
| Renombre desde formulario de Ruta IA | El vínculo de la parada sigue apuntando al mismo cliente estable. |
| Dos teléfonos renombrando simultáneamente | Conflicto explícito; nunca fusionar dos clientes solo porque el alias coincida. |
| Historial de contacto y visitas del mapa | Se preserva el evento histórico; solo se actualizan enlaces activos cuando la regla de negocio lo autorice. |
| Semana 6 y Pase | Se respetan la clave por hoja y la naturaleza independiente de Pase; no se aplican cascadas indiscriminadas. |

## Siguiente paso

Completar el recorrido de los métodos de eliminación y las referencias de Ruta IA/Semana 6; después consolidar un único contrato de operaciones CRUD, identidad, outbox, tombstones y concurrencia. No añadir todavía dependencias de Firestore ni cambiar Room hasta cerrar este contrato y definir migraciones y pruebas.

## Archivos de rama consultados

- `MatrizViewModel.kt` — SHA `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`
- `FiltrarViewModel.kt` — SHA `08491420265ede1f13985c262767f2cc179a5b1b`
- `DAOs.kt` — SHA `78574574296743fe92a0e51d64170e3f51c628f7`
- `RutaIA.kt` — SHA `e6c815650b5b6f07539b3a4d41648972b6551989`
- `ContactoLog.kt` — SHA `58320f484f991a2845f6b60c8269e100546b1060`

Las búsquedas de código de GitHub pueden devolver fragmentos de la rama predeterminada; los hashes anteriores corresponden a los archivos recuperados explícitamente desde la rama indicada.
