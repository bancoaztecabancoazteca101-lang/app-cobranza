# BITÁCORA DE CONTINUIDAD — FUENTE ÚNICA DE VERDAD Y SINCRONIZACIÓN GLOBAL

Actualizada: 23 de octubre de 2026  
Repositorio: `bancoaztecabancoazteca101-lang/app-cobranza`  
Rama de trabajo: `chatgpt/sincronizacion-integridad-nube`  
PR: https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/pull/122  
Regla: no modificar `main`, no fusionar el PR sin revisión y pruebas.

## 1. Objetivo definitivo confirmado por el usuario

Toda la información operativa de Matriz debe ser compartida y sincronizada para todos los dispositivos autorizados. No debe existir información operativa aislada por teléfono como fuente independiente. La nube será la única fuente de verdad; los dispositivos recibirán de ella los datos y enviarán sus cambios. Room podrá conservar caché y cambios pendientes para trabajar sin conexión, pero deberá reconciliarse con la nube.

Google Sheets será una fuente/puente temporal durante la migración y recibirá los datos mientras se mantenga esa compatibilidad. Cuando el backend central esté implementado y validado, Sheets se retirará progresivamente de la operación de la app. No retirar nada antes de probar el reemplazo, migración de datos y recuperación.

Este objetivo aplica a TODAS las pantallas, módulos, hojas, tablas, campos y operaciones de alta, edición, renombre y borrado. La auditoría no debe limitarse a los módulos que hoy ya sincronizan.

## 2. Estado de rama verificado durante esta sesión

- Rama encontrada: `chatgpt/sincronizacion-integridad-nube`.
- Comparación GitHub consultada: 22 commits por delante de `main`, 0 detrás, antes del nuevo cambio de esta sesión.
- PR #122 continúa siendo la ruta de revisión; no se fusionó ni se tocó `main`.
- Commit de esta sesión: `9537eb953155c918b0d9b63e7338c0a2032eeb36` — “Aislar errores entre módulos de sincronización”.
- Archivo cambiado: `app/src/main/java/com/example/matrizapp/SyncWorker.kt`.
- Estado de compilación del nuevo commit: PENDIENTE. La consulta de ejecuciones de GitHub Actions para este SHA no devolvió una ejecución; no declarar compilado hasta comprobarlo.
- Pruebas funcionales en dos teléfonos: PENDIENTES.

## 3. Cambio aplicado en esta sesión

En `SyncWorker.doWork()`, cada módulo (Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha) ahora se intenta dentro de su propio manejo de excepciones. Un error de un módulo queda registrado y no impide intentar los módulos siguientes en ese ciclo. Si uno o más módulos fallan, el Worker devuelve `Result.retry()` para conservar reintentos; si todos terminan, devuelve `Result.success()`. También se intenta programar la sincronización de imágenes aunque un módulo haya fallado.

Esto corrige el bloqueo en cascada entre módulos, pero NO resuelve conflictos de datos ni convierte Sheets en la fuente única de verdad. Debe compilarse y probarse antes de marcarlo como verificado. Los errores individuales dentro de un módulo aún pueden detener el procesamiento restante de ese módulo; la cola y cada operación requieren su propia estrategia de reintento/diagnóstico.

## 4. Hechos de código confirmados y riesgos

### Sincronización actual observada

En `SyncWorker.kt`:
- El orden actual de módulos es Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha.
- Matriz escribe campos en Sheets columna por columna usando una copia local completa y, si no encuentra el ID remoto, agrega una fila. Esto puede sobrescribir cambios concurrentes de otros teléfonos o recrear una fila borrada si el estado local quedó obsoleto.
- Pase procesa operaciones pendientes de renombre/borrado y sube filas sucias; el flujo observado no contiene descarga completa de Pase en `syncPase()`. Confirmar si existe otro mecanismo de descarga antes de cerrar el hallazgo.
- Filtro Fecha conserva el cambio como pendiente si no encuentra su ID remoto.
- Ruta IA reemplaza el contenido de la hoja de ruta completa cuando detecta registros sucios; requiere control de versión/propietario/conflicto antes de considerarlo seguro para edición concurrente.
- `SheetsRepository.updateSheetCell()` escribe una celda individual; el flujo actual no proporciona una transacción global de varios campos de un registro.

### Entidades/datos que requieren auditoría de alcance global

En `Entities.kt` y `AppDatabase.kt` se observan, entre otras, entidades de Matriz, tickets de pago, visitas de mapa, Pase, Solicitud, Filtro Fecha, Filtrar, Control, horarios, logs de contacto, plantillas SMS, configuración de automatización, reglas semanales, Ruta IA, filtros de Ruta IA, canales de pago, contactos extra, Velocidad/Penalización, Comisión, Bolsa Gerencia, cartucho del día y Semana 6.

Algunos comentarios de código describen expresamente como locales las tablas de Velocidad/Penalización, Comisión, Bolsa Gerencia, visitas de mapa, cartucho del día y Semana 6. Bajo el objetivo nuevo del usuario, esto no se acepta automáticamente como diseño final: cada una debe clasificarse como compartida o calculada/local de interfaz, y si contiene datos operativos introducidos por el usuario debe diseñarse cómo guardarla en la nube y distribuirla a los demás dispositivos. No migrar a ciegas campos derivados, cachés, permisos o datos temporales; documentar la decisión por entidad/campo y confirmar que no se pierde información.

Canal de pago actualmente se describe como nutrido desde una hoja de catálogo; hay que confirmar cómo se actualiza esa hoja y su fuente real. Los scripts de llamadas/SMS quedan fuera de alcance porque el usuario confirmó que la app ya realiza esas funciones; no reabrir esa auditoría salvo petición expresa.

## 5. Prioridades técnicas

### P0 — Inventario integral y mapa de datos
1. Enumerar todas las pantallas/módulos, entidades Room, DAOs, hojas Google Sheets, columnas, repositorios, Workers, importaciones/exportaciones y operaciones CRUD.
2. Para cada dato/campo, documentar: dónde se crea, dónde se edita, ID estable, fuente actual, si sube, si baja, si es compartido, comportamiento offline, conflicto, borrado, confirmación remota y pantallas consumidoras.
3. Identificar datos que hoy viven solo en Room, se crean independientemente en cada teléfono, o no tienen descarga desde la fuente compartida.
4. No dar por sincronizado un módulo porque tenga un botón “Descargar”, un Worker o una hoja asociada: verificar el flujo real de subida y bajada.

### P1 — Integridad de sincronización actual
1. Compilar y probar el aislamiento de errores agregado a SyncWorker.
2. Revisar cómo informar y recuperar operaciones pendientes que fallan repetidamente; evitar que una operación ambigua permanezca indefinidamente sin visibilidad.
3. Resolver la identidad compartida de Pase y la posible duplicación al copiar Matriz en más de un teléfono; usar ID determinista/estable basado en la identidad funcional, solo después de confirmar la regla de negocio.
4. Completar y verificar renombres de Matriz y Filtro Fecha, incluida operación sin conexión y colisiones.
5. Evitar que borrados confirmados reaparezcan en otro teléfono; diseñar tombstones/versiones o mecanismo equivalente.
6. Evitar sobrescrituras concurrentes por escrituras de fila completa; definir actualización por campo/versionado/conflicto.
7. Añadir pruebas de migración Room 37→38 y las nuevas migraciones futuras, sin pérdida de datos.

### P2 — Backend central y retirada progresiva de Sheets
1. Comparar Firestore y Supabase según consultas, estructura relacional, operación offline, costos, reglas de acceso, auditoría y migración.
2. Hacer prototipo aislado con datos de prueba; no migrar datos productivos todavía.
3. Definir IDs globales, control de versiones, outbox, idempotencia, tombstones, confirmación del servidor, permisos y estrategia de conflictos.
4. Migrar un módulo piloto y validar creación/edición/borrado online y offline en al menos dos dispositivos.
5. Migrar todas las entidades/hojas compartidas en etapas; durante la transición, definir cuál sistema escribe y cuál es solo espejo para impedir dos fuentes de verdad simultáneas.
6. Validar reconciliación, respaldo, recuperación y exportación; retirar Sheets de la app solo cuando el backend sea el origen operativo probado.

## 6. Pruebas de aceptación obligatorias

- Teléfono A crea/edita/renombra/borra; teléfono B recibe el resultado sin duplicados.
- Teléfono B realiza el cambio equivalente y A lo recibe.
- Dos teléfonos editan campos distintos y el sistema no pierde cambios silenciosamente.
- Dos teléfonos editan el mismo campo: conflicto detectado y resuelto mediante una regla explícita.
- Edición offline → pendiente persistente → reconexión → confirmación de nube → recepción en el otro teléfono.
- Borrado offline/online no reaparece por sincronización de datos obsoletos.
- Un error de Pase o Filtro Fecha no impide intentar Matriz, Ruta IA y Solicitud.
- Operaciones repetidas son idempotentes.
- Instalación/actualización con datos previos conserva los datos de Room.
- Pruebas de cada hoja y de cada entidad clasificada como compartida.

Un build verde solo demuestra compilación/empaquetado, no sincronización funcional. No declarar completado sin evidencias registradas.

## 7. Próximo paso exacto

Continuar el inventario P0 desde el código real de la rama actual, recorriendo DAOs, Entities, SheetsRepository, todos los métodos de refresh/download, ViewModels y pantallas. Elaborar matriz de cobertura subida/bajada por entidad y hoja. Priorizar primero las entidades que el código documenta como locales pero que, según el objetivo confirmado, contienen información operativa que debe ser compartida. Después corregir cada flujo en commits pequeños, ejecutar build y pruebas, y registrar los resultados aquí.

Mantener siempre la ruta: fuente única de verdad en la nube → todos los dispositivos autorizados sincronizados → Google Sheets como puente temporal → retirada gradual de Sheets tras validación.