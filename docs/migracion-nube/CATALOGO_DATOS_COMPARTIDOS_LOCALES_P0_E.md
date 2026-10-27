# Catálogo de datos compartidos, locales y derivados — P0-E

**Rama auditada:** `chatgpt/sincronizacion-integridad-nube`  
**Alcance:** auditoría de código; solo documentación.  
**Estado:** clasificación preliminar basada en entidades/DAO/ViewModels/SheetsRepository. No se modificó código de producción ni se ejecutó build.

## 1. Regla de clasificación

- **Compartido candidato:** representa operación de la cartera y hoy tiene escritura/lectura en Google Sheets o está pensado para usarse en más de un teléfono. Antes de migrar, definir autoridad, permisos, identidad global y conflictos.
- **Local confirmado:** el código o comentario declara que es local o su propósito depende del teléfono/dispositivo.
- **Derivado/caché:** se reconstruye desde otra fuente o es una copia auxiliar; no debe convertirse en una segunda fuente de verdad.
- **Decisión pendiente:** el código no permite concluir con seguridad si debe compartirse entre todos los teléfonos o mantenerse por dispositivo.

La clasificación no equivale a autorización para sincronizar. No mover datos al cloud hasta cerrar decisiones y reglas de acceso.

## 2. Catálogo por entidad/flujo

| Entidad o flujo | Clasificación provisional | Evidencia y riesgo |
|---|---|---|
| `MatrizEntity` / `matriz_table` | Compartido candidato | Se descarga desde Sheets y `SyncWorker` sube filas `isDirty`. Incluye cambios de estado, gestión, fotos y renombrado. El borrado local es físico; no hay tombstone global. |
| Campos de Matriz que no están en Sheets: `descuentoPago`, `descuentoAhorro`, `ref3`, `ref4`, `diaPago`, `domicilioLaboral`, `diasAtraso`, `diasApertura`, `montoCobrado` | Decisión pendiente por campo | El código los documenta como locales/no presentes en Sheet. No deben perderse al migrar ni asumirse compartidos sin decidir. `montoCobrado` se captura desde ticket y no se sube en la sincronización actual de Matriz. |
| `FiltrarEntity` | Compartido candidato / derivado en revisión | Tiene DAO dirty, pero el ViewModel actual de Filtrar usa datos de Matriz para parte de la pantalla. Confirmar qué datos propios siguen siendo operativos antes de crear colección cloud independiente. |
| `FiltroFechaEntity` | Caché/compatibilidad en revisión | El comentario del ViewModel indica que Filtro Fecha ahora lee Matriz directamente; aún existen DAO/repository y sincronización de la tabla. Evitar duplicar la fuente. |
| `PaseEntity` | Compartido candidato, independiente | La entidad declara que es copia independiente y que sus cambios no regresan a Matriz. No borrar ni actualizar en cascada desde Matriz automáticamente. |
| `PaseSyncOperationEntity` | Cola local crítica | Registra operaciones pendientes DELETE/RENAME. Preservar las operaciones pendientes en cualquier migración Room; no tratarla como dato de negocio cloud sin diseño específico. |
| `SolicitudEntity` | Compartido candidato | Tiene estado dirty, altas/ediciones, audio e imágenes que se suben a Drive. El borrado físico y la reimportación actual pueden resucitar filas. Definir metadatos de adjuntos y borrado lógico. |
| `RutaIAEntity` | Compartido candidato con alto riesgo de conflicto | La sincronización actual puede reemplazar la hoja completa con datos de un teléfono. Requiere versión/propietario de edición o transacciones por elemento; no replicar el reemplazo total como estrategia cloud. |
| `Sem6RegistroEntity` | Derivado/caché con ediciones locales que requieren decisión | Se genera desde Matriz y también permite cambios/notas y borrado. Clave compuesta `(hoja,id)`; los marcadores de borrado están en SharedPreferences del dispositivo. Decidir qué campos de Semana 6 son operaciones compartidas y cuáles se recalculan. |
| `VisitaMapaEntity` | Historial/evento; compartición pendiente | El comentario lo declara 100% local/no sincronizado y conserva coordenadas/eventos por fecha, con retención aproximada de cuatro meses. Confirmar si se necesita historial global; no subir ubicación histórica por defecto. |
| `CartuchoDiaEntity` | Decisión pendiente: global de equipo vs. por dispositivo | Guarda cartucho activo por fecha en Room y usa cartucho 1 como valor por defecto si no hay fila. Compartirlo sin definir quién controla el cartucho podría hacer que un teléfono cambie el de todos. |
| `TicketPagoEntity` | **Local confirmado** | El comentario de la entidad dice “100% local”; almacena lectura OCR del ticket. `FiltroFechaViewModel.borrarTicket` solo lo elimina de Room. No migrarlo como registro compartido por accidente. |
| `ControlEntity` | Caché/compartido por confirmar | Se refresca desde Sheets, pero se debe confirmar si es catálogo/estado global o solo datos de apoyo antes de diseñar escritura cloud. |
| `CanalPagoEntity` | Caché/catálogo local actual | El código lo describe como catálogo local de lugares de pago y referencia una fuente externa/hoja para poblarlo. Definir si cloud lo publicará como catálogo común o si cada dispositivo lo descargará como caché. No depender de Apps Script como parte de esta migración sin petición explícita. |
| `VelocidadEntity`, `ComisionEntity`, `BolsaGerenciaEntity` | Local confirmado según comentarios actuales | Las entidades/comentarios las describen como datos calculados o configuraciones locales, no extraídas de Sheets. No compartir hasta que exista requerimiento explícito y definición de propietario. |
| `RutaIAFiltroEntity` / criterios de orden | Preferencia/configuración por confirmar | Se guardan localmente y afectan el orden de la ruta. Confirmar si son preferencias personales del teléfono/usuario o reglas de equipo. |
| URI `content://` y rutas locales de archivos | Local/cache de dispositivo | No se pueden usar como referencias cloud globales. Subir archivo de forma controlada, guardar URL/ID remoto y mantener URI local solo como caché. |
| Preferencias de borrado Semana 6 (`Sem6CacheStore`, `deleted_ids_<hoja>`) | Local confirmado, insuficiente para borrado global | SharedPreferences del dispositivo. No impiden que otro teléfono vuelva a importar el registro. Reemplazar por tombstone compartido solo cuando la política de borrado esté definida. |

## 3. Operaciones de escritura que requieren tratamiento especial

1. **Renombrado de Matriz:** hay dos entradas identificadas: `MatrizViewModel.cambiarIdYGuardar()` y `FiltrarViewModel.guardarRegistroCompleto()`. `MatrizDao.renameId()` cambia la columna `id`; no actualiza automáticamente referencias relacionadas. El renombrado cloud debe ser una operación idempotente que preserve referencias y no cree un duplicado.
2. **Renombrado de Pase:** `PaseCarteraViewModel.cambiarIdYGuardar()` mantiene un flujo independiente. Preservar la cola `PaseSyncOperationEntity` y definir cómo se resuelve una colisión de ID.
3. **Borrados de Matriz y Solicitud:** hoy se intenta borrar en Sheets y luego se elimina físicamente en Room cuando se confirma. Sin tombstone global, una descarga posterior puede reinsertar el ID borrado.
4. **Semana 6:** el borrado depende de una lista local de IDs en SharedPreferences; otro teléfono no conoce el marcador.
5. **Ruta IA:** `SheetsRepository.refreshRutaIA()` puede subir una lista local completa si hay dirty items. No llevar este patrón al cloud; una edición de un teléfono puede reemplazar cambios de otro.
6. **Pago OCR:** `TicketPagoEntity` es local, pero el procesamiento puede cambiar el estado de un registro Matriz y marcarlo dirty. Separar el comprobante local del evento de negocio compartido (p. ej. cambio a Pagado).
7. **Imágenes/audio:** las URIs locales no son globales; el registro de metadatos y la carga de archivo deben confirmarse juntos o recuperarse de forma idempotente.

## 4. Reglas que sí pueden fijarse desde el código

- Mantener el ID de cliente existente durante la transición Sheets/cloud; no retirarlo mientras siga siendo clave de búsqueda/compatibilidad.
- Mantener `PaseEntity` independiente de Matriz; sin cascada automática.
- Mantener `TicketPagoEntity` local.
- No subir coordenadas/historial de `VisitaMapaEntity` hasta confirmar necesidad y política.
- No usar `isDirty` como sustituto de una outbox durable con identidad de operación, reintentos e idempotencia.
- No marcar una operación como sincronizada hasta obtener confirmación verificable del servidor.
- No convertir un borrado físico en borrado global sin tombstone y reconciliación de descargas.
- No habilitar lectura/escritura Firestore mientras no exista autenticación y autorización verificadas.

## 5. Decisiones abiertas antes del esquema cloud

1. ¿`CartuchoDiaEntity` representa el cartucho del equipo completo o la selección de cada teléfono?
2. ¿Qué campos locales de Matriz deben compartirse entre teléfonos y cuáles deben seguir privados/locales?
3. ¿Semana 6 es una vista derivada de Matriz o contiene cambios que deben sobrevivir como datos compartidos independientes?
4. ¿El historial de visitas del mapa debe ser global, local por teléfono o desactivado para cloud?
5. ¿Control y criterios de Ruta IA son reglas compartidas o preferencias individuales?
6. ¿Qué roles/usuarios pueden crear, editar, renombrar, borrar y ver adjuntos?
7. ¿Cómo se resuelve una edición concurrente: rechazo por versión, resolución manual o reglas campo a campo?
8. ¿Qué política de retención y acceso se aplicará a fotos, audio y ubicaciones?

## 6. Siguiente paso técnico

Preparar un diseño de migración Room **38 → 39** que añada una outbox durable y metadatos de sincronización sin perder la cola Pase pendiente. Antes de implementarlo, revisar la declaración exacta de `AppDatabase`, migraciones existentes y pruebas disponibles. La migración no debe eliminar ni reconstruir tablas con datos sin una migración preservadora.

## 7. Evidencia y límites

Archivos inspeccionados en la rama indicada:
- `Entities.kt` — blob `ccd9097479375611993b196fd106736357faf1f9`
- `DAOs.kt` — blob `78574574296743fe92a0e51d64170e3f51c628f7`
- `SheetsRepository.kt` — blob `9a625c6a68d64febc5aa24d75243027389ee25bf`
- `MatrizViewModel.kt` — blob `38fa7428fa92329a7164b5ec4cfec10dd3ef290d`
- `FiltrarViewModel.kt` — blob `08491420265ede1f13985c262767f2cc179a5b1b`
- `RutaIAViewModel.kt` — blob `a67f8ed613747697028dd1d627e12f61509ff585`
- `PaseCarteraViewModel.kt` — blob `c153ecffb9eabbb8ff053a38076443ac215d5d69`
- `FiltroFechaViewModel.kt` — blob `5ece22add07e87257ed845a02b306872cfa636c1`
- `SolicitudViewModel.kt` — blob `8c10d3281d47bbe1ccd0c58e343dac80ffaa161d`
- `AppDatabase.kt` — blob `6a641b399e8ae6071a6e46b4629abc115c5a99f1`

**No se modificó código de producción. No se ejecutó compilación, pruebas ni prueba en teléfonos.** Este documento es un catálogo de auditoría; las clasificaciones marcadas como pendientes necesitan decisión antes de implementar.
