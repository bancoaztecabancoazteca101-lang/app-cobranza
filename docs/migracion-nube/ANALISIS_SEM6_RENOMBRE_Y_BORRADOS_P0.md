# Análisis P0: Semana 6, renombre de ID y borrados

**Rama:** `chatgpt/sincronizacion-integridad-nube`  
**Tipo de revisión:** lectura estática del código de la rama; no es prueba de ejecución.  
**Alcance:** `Sem6Generador.kt`, `Sem6ViewModel.kt`, `Entities.kt`, `DAOs.kt`.  
**Estado:** documento de diseño; no se modificó código de producción, esquema Room, Firebase ni reglas.

## Fuentes inspeccionadas

- [Sem6Generador.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/Sem6Generador.kt), blob `38a9ae9ef7b8126157cc1a65b0372551034aea13`.
- [Sem6ViewModel.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/Sem6ViewModel.kt), blob `5110af8d9ed70834d67296992662d37bfa775905`.
- [Entities.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/Entities.kt), blob `ccd9097479375611993b196fd106736357faf1f9`.
- [DAOs.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/chatgpt/sincronizacion-integridad-nube/app/src/main/java/com/example/matrizapp/DAOs.kt), blob `78574574296743fe92a0e51d64170e3f51c628f7`.

## Hallazgos confirmados en el código

### 1. Semana 6 combina datos derivados y datos editados manualmente

`Sem6Generador.actualizar()` obtiene la hoja de la semana actual, consulta registros existentes y procesa Matriz con estas condiciones:
- `semana` se convierte a entero y debe ser 6;
- el ID no puede estar vacío;
- la función `eliminado(hoja, id)` debe devolver falso;
- la fecha debe estar dentro de la semana ISO actual (lunes incluido, siguiente lunes excluido).

Para un registro nuevo, genera nombre, requisito, CU/folioP, ubicación, imagen, visitas iniciales, fecha de última visita y numTT. Para uno existente **no reemplaza todos los campos**: solo completa numTT, ubicación, imagen y CU si están vacíos; aumenta visitas cuando la fecha del registro es posterior a la última fecha de visita guardada.

La entidad `Sem6RegistroEntity` también contiene `colonia`, `seContiene`, `susceptible`, `observaciones`, `capital` y `abono`. Las notas y valores financieros se guardan mediante consultas específicas de `Sem6Dao`; el generador no los asigna de nuevo al actualizar un registro existente. Esta separación debe conservarse en el diseño cloud.

### 2. El borrado de Semana 6 no es todavía un borrado global

`Sem6ViewModel.eliminarRegistro()` elimina la fila de Room y después marca el par ID/hoja en `Sem6CacheStore`. El generador consulta esa marca antes de volver a crear el registro desde Matriz.

La marca está en almacenamiento local (`SharedPreferences`, archivo lógico `sem6_interna` para la importación única y almacenamiento de eliminados en `Sem6CacheStore`). En el código inspeccionado no aparece un tombstone remoto ni una cola de borrados compartida. Por tanto, el borrado local no demuestra que otro teléfono vaya a respetarlo: el otro dispositivo puede seguir teniendo el registro y no conocer la marca de eliminación.

### 3. El renombre de ID de Matriz no tiene cascada en el DAO

`MatrizDao.renameId(idAnterior, idNuevo)` actualiza únicamente la columna `id` de `matriz_table` y marca la fila como dirty. La consulta mostrada no actualiza explícitamente:
- `sem6_registro_table`, cuya clave primaria es el par `(hoja, id)`;
- `visita_mapa_table`, que conserva `clave` (por ejemplo `M:<id>`) y `matrizId`;
- contactos extra referenciados por `clienteId`;
- otras referencias o colas que guarden el ID anterior.

Esto confirma que **no se debe diseñar una migración cloud suponiendo una cascada automática**. Aún debe seguirse el flujo completo de `MatrizViewModel.cambiarIdYGuardar()` y cualquier operación de Sheets/Pase para precisar qué pasos adicionales se ejecutan y qué pasa si falla un paso intermedio.

### 4. Pase es una entidad independiente

La definición de `PaseEntity` documenta que es una copia separada: sus cambios no se sincronizan de vuelta a Matriz. `origenMatrizId` sirve para identificar el origen y evitar duplicar la copia; no debe tratarse como una relación con cascada sin una decisión explícita del producto.

### 5. Visitas del mapa y cartucho diario son locales

`VisitaMapaEntity` usa clave compuesta `(clave, fechaDia)`, registra cartucho y conserva nombre/ubicación aunque el registro cambie después. `CartuchoDiaEntity` guarda un cartucho activo por fecha. Los comentarios y DAOs inspeccionados describen estas tablas como locales; no se encontró en este DAO una operación de renombre de clave ni sincronización remota.

Antes de llevarlas a nube hay que decidir si cartucho y visitas son datos compartidos por todo el equipo, por usuario o por dispositivo. No debe suponerse que el estado local actual representa una decisión global.

## Decisiones de diseño recomendadas (aún no implementadas)

1. Separar identidad estable del cliente de su ID visible/editable. Un cambio de ID visible no debería crear otro cliente ni perder referencias; debe tener una operación explícita y auditable.
2. Para cada entidad dependiente, definir si la relación sigue una identidad estable o el ID visible: Semana 6, visitas de mapa, contactos extra, Pase y cualquier cola pendiente.
3. Representar eliminaciones compartidas mediante tombstones versionados, con ámbito claro (por ejemplo, una semana específica de Semana 6 frente a eliminar al cliente de Matriz). No reutilizar una sola marca de borrado para significados distintos.
4. Hacer el renombre como operación lógica idempotente y recuperable: validar colisión, registrar operación pendiente, actualizar las referencias definidas y confirmar al servidor; diseñar recuperación ante corte de red o cierre de app. No ejecutar una cascada indiscriminada.
5. Conservar el comportamiento actual de Semana 6: completar campos derivados solo cuando corresponda y nunca pisar notas/capital/abono editados manualmente durante una regeneración.
6. No activar escritura cloud hasta que autenticación, autorización, versión/conflicto, borrados y pruebas multi-dispositivo estén definidos. Firestore debe seguir con reglas de denegación total mientras no exista ese control.

## Pendientes para la siguiente revisión

- Leer completo `MatrizViewModel.cambiarIdYGuardar()` y sus operaciones remotas, incluida la ruta de fallo parcial.
- Localizar y leer la implementación de `Sem6CacheStore` y de `ContactoExtraDao` en la rama exacta, y mapear todas las referencias por ID.
- Revisar migraciones Room y pruebas existentes antes de proponer entidades/outbox/tombstones.
- Definir formalmente semántica de borrado de Matriz frente a borrado de Semana 6 y retención de visitas.
- Preparar casos de prueba: renombre offline, colisión de ID, fallo después de renombre remoto, borrado en teléfono A y sincronización en B, y edición concurrente de notas de Semana 6.

## Verificación y límites

- Lectura estática de los cuatro archivos indicados; no se ejecutó la app.
- No se compiló ni se corrieron pruebas unitarias/instrumentadas.
- No se modificó código de producción ni `DB_VERSION`.
- No se habilitó Firestore ni se cambiaron reglas.
- `main` no se modificó y no se fusionó el PR #122.
