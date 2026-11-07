# Matriz de referencias a ID y estado local — P0

Fecha de revisión: 2026-10-26  
Rama revisada: `chatgpt/sincronizacion-integridad-nube`  
Alcance: revisión estática de código fuente real. No se modificó código de producción, Room, dependencias ni configuración Firebase. No se ejecutaron compilación, pruebas ni pruebas en dispositivo. `main` permanece intacta.

## Corrección de ubicación de Sem6CacheStore

La clase sí existe; no está en un archivo independiente. Está declarada en:

- `app/src/main/java/com/example/matrizapp/Sem6.kt`
- SHA del blob revisado: `97b13a0c0c43e6c14229c90d48b9dc59c80e3ee0`

Usa `SharedPreferences("sem6_cache")`. La marca de borrado se guarda en claves `deleted_ids_<hoja>`, donde la hoja es, por ejemplo, `Cont-Sem-NN`. La marca es local al dispositivo y específica de la semana/hoja. No es una baja global ni una cola durable compartida. Por tanto, otro teléfono puede no conocerla y volver a mostrar/generar el registro.

## Referencias a identificadores confirmadas

| Área | Referencia actual | Qué se verificó | Implicación para nube |
|---|---|---|---|
| Matriz | `MatrizEntity.id` | Es la clave primaria de `matriz_table`; el ID visible también se usa para localizar/actualizar la fila en Sheets. | No usar el ID editable como identidad interna permanente. Mantenerlo como identificador externo/visible durante la transición. |
| Semana 6 | clave compuesta `(hoja, id)` | `Sem6Dao` consulta y modifica registros por hoja e ID. Los campos manuales incluyen `seContiene`, `susceptible`, `observaciones`, `capital`, `abono`. | El ID visible participa en la clave compuesta; renombrar requiere migración idempotente y resolver colisiones sin perder campos manuales. |
| Borrados Semana 6 | `Sem6CacheStore` SharedPreferences | La marca se guarda por hoja y solo en el dispositivo. | Sustituir a futuro por tombstone/operación de borrado sincronizable; no borrar el marcador local hasta confirmar la política de retención y migración. |
| Contactos extra | `ContactoExtraEntity.clienteId` | Es una referencia textual al cliente; el PK del contacto extra es autogenerado. | Debe definirse si se re-vincula a identidad inmutable o se conserva un alias de ID visible. No asumir cascada automática. |
| Historial de contacto | `ContactoLogEntity.clienteId` | Registra el ID usado al reclamar/registrar contacto por bloque y día. | Es historial operativo; un cambio de ID no debe reescribirlo a ciegas. Preferir añadir identidad estable para nuevas operaciones y conservar el ID histórico. |
| Visitas de mapa | `VisitaMapaEntity.clave` y `matrizId` | La clave usa prefijos `M:<id>` para Matriz o `S6:<id>` para Semana 6; también guarda nombre, ubicación, fecha y cartucho. La entidad se documenta como 100% local, no sincronizada con Sheets. | Cambiar ID puede afectar la clave y el enlace “Abrir en Matriz”. No cambiar claves históricas sin decidir si se preservan las visitas históricas y cómo se resuelven los borrados. |
| Pase | `PaseEntity.origenMatrizId` | La documentación de la entidad dice que es una copia independiente; el campo identifica el origen para evitar duplicar, no sincroniza cambios de vuelta. | No aplicar cascada de renombre/borrado sin una decisión de negocio. Preservar la cola local `PaseSyncOperationEntity`. |
| Ruta IA | `RutaIAEntity.matrizId` | Se usa para vincular una parada a un registro Matriz. | Debe incluirse en el inventario de referencias y pruebas de renombre. Confirmar en el código de rama la semántica exacta antes de modificarlo. |
| Cartucho diario | `CartuchoDiaEntity.fechaDia` | Guarda `cartuchoActivo` por día en Room; la lógica usa 1 como valor por defecto si no hay registro. | No es una referencia de identidad de cliente. Falta decisión explícita de si el cartucho es global de equipo, por usuario o por dispositivo antes de sincronizarlo. |

## Riesgos concretos para la migración

1. **ID editable ≠ identidad estable.** El ID que viene de Sheets/AppSheet puede cambiar. La nube necesita un identificador interno inmutable y mantener el ID actual como alias/identificador externo mientras exista el puente de Sheets.
2. **Renombre parcial.** El flujo actual puede actualizar Sheets antes de actualizar Room. Si la app se interrumpe en medio, el reintento debe reconciliar el caso “el ID nuevo ya existe remotamente” sin duplicar ni declarar fallo definitivo.
3. **Borrado Semana 6 no global.** El marcador local en SharedPreferences explica por qué el borrado no es una instrucción compartida entre teléfonos.
4. **Referencias con significado distinto.** ContactoLog es historial; Pase es copia independiente; VisitaMapa es un evento histórico con datos congelados; Semana 6 combina proyección y campos editables. No deben tratarse todas como cascadas relacionales idénticas.
5. **Datos locales aún sin contrato de sincronización.** Visitas del mapa, cartucho del día y algunos campos locales de Matriz no están probados como parte del ciclo principal de sincronización. Debe decidirse si son globales, personales o específicos del dispositivo antes de moverlos a la nube.

## Decisiones pendientes antes de implementar

- Definir identidad interna inmutable por cliente y política de compatibilidad del ID visible.
- Definir si contactos extra siguen al cliente tras renombrar; conservar ContactoLog como historial.
- Definir propiedad de Semana 6: campos calculados/proyectados frente a campos manuales y borrado explícito por semana.
- Definir si visitas del mapa son eventos compartidos y si se conservan aunque se renombre o borre el cliente.
- Definir si cartucho activo por día es global para el equipo, por gestor o por dispositivo.
- Diseñar outbox durable para crear/editar/renombrar/borrar, tombstones versionados, operaciones idempotentes, control de concurrencia y confirmación de servidor antes de marcar sincronizado.
- Añadir pruebas de caída entre escritura remota/local, dos teléfonos concurrentes, modo offline, colisión de ID y borrado/renombre de Semana 6.

## Evidencia y límites

Fuentes de rama consultadas: `Sem6.kt`, `Sem6ViewModel.kt`, `Entities.kt`, `DAOs.kt`, `MatrizViewModel.kt`. Las búsquedas globales de GitHub pueden mostrar el commit por defecto antiguo; los archivos usados para esta revisión se recuperaron explícitamente de `chatgpt/sincronizacion-integridad-nube`.

No se afirma que toda la matriz de referencias esté cerrada: quedan por recorrer las referencias a ID en todas las entidades y flujos de edición, importación, recordatorios y sincronización. Este documento es una actualización de hallazgos P0, no autorización para cambiar código de producción.
