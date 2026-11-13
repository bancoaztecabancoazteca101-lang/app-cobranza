# Contrato propuesto de outbox, tombstones y conflictos — P0-D

**Rama revisada:** `chatgpt/sincronizacion-integridad-nube`  
**Estado:** propuesta de diseño basada en código existente; no es implementación ni autorización para alterar datos de producción.

## Código real que condiciona el diseño

Archivos y blobs inspeccionados:
- `Entities.kt`: `ccd9097479375611993b196fd106736357faf1f9`
- `AppDatabase.kt`: `6a641b399e8ae6071a6e46b4629abc115c5a99f1`
- `SyncWorker.kt`: `cb87f911072c0bfdd228468a28f35f3902aa9b2c`
- `SheetsRepository.kt`: `9a625c6a68d64febc5aa24d75243027389ee25bf`
- `Sem6Generador.kt`: `38a9ae9ef7b8126157cc1a65b0372551034aea13`
- `DAOs.kt`: `78574574296743fe92a0e51d64170e3f51c628f7`

### Consecuencias del modelo actual

1. `MatrizEntity` usa `id` como clave primaria, con `isDirty` y `lastSync`; estos campos no alcanzan para representar borrados porque el borrado físico elimina la fila.
2. `PaseSyncOperationEntity` ya es una outbox local especializada, con `operationKey`, `type`, `oldId` y `newId`. Debe migrarse/consumirse de forma segura, no perderse al introducir una outbox común.
3. `Sem6RegistroEntity` usa clave compuesta `(hoja,id)`; un tombstone de Semana 6 debe respetar el ámbito de hoja/semana, salvo que la decisión funcional sea impedir la regeneración global.
4. `MatrizEntity` contiene campos exclusivamente locales que no existen en Sheets: `descuentoPago`, `descuentoAhorro`, `ref3`, `ref4`, `diaPago`, `domicilioLaboral`, `diasAtraso`, `diasApertura` y `montoCobrado`. La sincronización cloud no debe sobrescribirlos con valores vacíos ni confundirlos con datos ya compartidos.
5. `SyncWorker` hoy sincroniza Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha con Sheets. No es una sincronización cloud ni una transacción entre teléfonos.
6. `Sem6Generador.actualizar` crea/actualiza Semana 6 a partir de registros Matriz y consulta un marcador local de eliminado. Un tombstone cloud debe ser consultado antes de regenerar.
7. La migración 37→38 creó la cola de Pase. La siguiente migración de Room debe preservar esa tabla y sus pendientes.

## Contrato propuesto

### A. Operación local pendiente

Cada mutación compartida debe escribirse en una sola transacción Room:
- aplicar el cambio local o guardar la representación pendiente;
- insertar la operación en una tabla outbox común;
- asignar un `operationId` UUID estable y conservarlo en todos los reintentos;
- almacenar `entityType`, `entityId`, `operationType`, `payload`/referencia al snapshot, `baseVersion`, `createdAt`, `actorId`, `status` y `attemptCount`;
- no marcar como sincronizado por el mero hecho de enviar la petición.

Los nombres son de diseño, no nombres de clases ya existentes. El formato final debe adecuarse a las dependencias y serialización reales del proyecto antes de programar.

### B. Identidad y versiones

- Usar ID de negocio estable cuando ya sea global y no se renumere; si puede cambiar, añadir identidad inmutable separada y mantener el ID visible como alias.
- Matriz y Solicitud no deben tratar el renombre como borrado+alta sin una política explícita de referencias.
- Pase es una copia independiente según el comentario de `PaseEntity`; no heredar automáticamente todos los cambios/borrados de Matriz.
- Para registros con ámbito, incluirlo en la clave lógica: Semana 6 requiere `hoja + id`; visitas de mapa tienen su propia identidad de evento.
- Toda versión cloud debe permitir detectar una edición basada en una versión antigua. No usar reloj local como autoridad exclusiva.

### C. Borrados/tombstones

Representar el borrado como una operación durable, no como ausencia de fila:
- `entityType`, `entityId`, `scope` opcional, `operationId`, `serverVersion` o secuencia monotónica, `deletedAt`, `actorId`, `status`.
- El pull/reconciliador consulta tombstones antes de insertar o actualizar.
- Un tombstone confirmado bloquea reimportaciones y regeneración hasta que exista una restauración explícita autorizada.
- No purgar tombstones solo por antigüedad sin conocer el horizonte de clientes offline; una app que regresa meses después podría reintroducir datos viejos.
- La limpieza local de caché, retención de visitas y borrado de una operación outbox completada no son tombstones de negocio.

### D. Confirmación, reintentos e idempotencia

- El servidor confirma la mutación y devuelve versión/resultado estable.
- Solo después se marca la outbox como completada y se actualiza la versión local.
- Si hay timeout tras una escritura posiblemente aceptada, reintentar con el mismo `operationId`; no crear una operación nueva.
- Un conflicto de versión no se resuelve automáticamente con “última escritura gana” para todos los campos. Registrar conflicto y aplicar política por entidad/campo.
- Para adjuntos, separar el estado del archivo del estado del registro y contemplar cargas incompletas/huérfanas.

### E. Resolución de conflictos inicial

| Caso | Tratamiento propuesto |
|---|---|
| Dos teléfonos editan campos diferentes de Matriz | Merge por campo solo si el mapeo y semántica de cada campo están definidos; de lo contrario conflicto visible |
| Dos teléfonos editan el mismo campo | Comparar versión base y versión servidor; no pisar silenciosamente |
| Borrado frente a edición concurrente | El tombstone no se revoca por una edición atrasada; restauración explícita |
| Renombre de ID | Operación atómica que actualiza alias/referencias definidas; no adivinar referencias |
| Pase modificado/borrado | Mantener ciclo de vida independiente, con su outbox y clave |
| Semana 6 | Aplicar alcance por hoja/semana y evitar regeneración tras borrado confirmado |
| Sheets devuelve una fila vieja | Reconciliar contra estado cloud/tombstone; Sheets no decide por sí solo resucitar un registro |

## Orden seguro de implementación (sin ejecutarlo todavía)

1. Definir catálogo de entidades: global compartida, local, derivada/caché, histórica/evento.
2. Definir identidad y política de renombre por entidad; revisar todos los sitios que cambian IDs y referencias.
3. Diseñar tablas Room de outbox/tombstones y migración desde versión 38, con prueba de preservación de la cola Pase.
4. Implementar repositorio cloud detrás de una interfaz, sin activar escrituras de producción.
5. Implementar autenticación Firebase Auth real y autorización por usuario/grupo; validar que el usuario autenticado corresponda a los teléfonos autorizados.
6. Mantener reglas Firestore deny-all hasta que autenticación y reglas estén probadas con usuarios de prueba.
7. Ejecutar pruebas unitarias/instrumentadas de migración, reintentos, conflictos, borrados, renombres, offline y reimportación.
8. Probar con dos teléfonos y datos de prueba; después planear un piloto controlado con respaldo y rollback.
9. Mantener Sheets como puente temporal explícito, con una única dirección de autoridad durante cada fase. No permitir que una importación vieja de Sheets sobrescriba el estado cloud.

## Criterios mínimos de aceptación

- Borrar Matriz en dispositivo A no permite que el registro reaparezca al refrescar dispositivo B.
- Borrar Semana 6 en A no lo regenera B para el mismo ámbito.
- Una caída de red antes/después de confirmación no duplica operaciones ni pierde el borrado.
- Un renombre no crea dos clientes ni deja referencias silenciosamente rotas.
- La migración de Room 38 conserva datos, preferencias y operaciones Pase pendientes.
- Campos locales que no existen en Sheets se preservan en el teléfono y tienen una decisión explícita de sincronización.
- Un usuario no autenticado/no autorizado no puede leer ni escribir documentos cloud.
- Cada prueba tiene resultado registrado; no se declara verificado por inspección de código solamente.

## Fuera de alcance / decisiones no asumidas

- No se elige todavía el esquema final de colecciones Firestore.
- No se cambia la política funcional de Pase, Semana 6, contactos, visitas ni tickets OCR.
- No se borra ni renombra el ID visible de cliente; se mantiene mientras Sheets sea puente.
- No se añaden dependencias ni se cambia Room 38 en este commit.
- No se modifican reglas cloud, credenciales, OAuth ni datos reales.
- No se declara compilación, pruebas ni despliegue realizados.
