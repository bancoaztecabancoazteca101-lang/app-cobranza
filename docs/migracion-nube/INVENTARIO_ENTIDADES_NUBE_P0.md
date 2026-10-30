# Inventario P0 de entidades Room para migración a nube

Fecha de revisión: 2026-10-26  
Repositorio: `bancoaztecabancoazteca101-lang/app-cobranza`  
Rama: `chatgpt/sincronizacion-integridad-nube`  
Estado: inventario inicial basado en código real; clasificación funcional pendiente de validación por flujos completos.  
Reglas: no tocar `main`, no fusionar PR #122 sin autorización, no habilitar escrituras de Firestore mientras sus reglas sigan cerradas.

## Evidencia de código revisada

- `Entities.kt`, blob `ccd9097479375611993b196fd106736357faf1f9`.
- `AppDatabase.kt`, blob `6a641b399e8ae6071a6e46b4629abc115c5a99f1`: `DB_VERSION = 38`; anotación `@Database` declara 23 entidades.
- `DAOs.kt`, blob `78574574296743fe92a0e51d64170e3f51c628f7`.
- `SheetsRepository.kt`, blob `9a625c6a68d64febc5aa24d75243027389ee25bf`.
- `SyncWorker.kt`, blob `cb87f911072c0bfdd228468a28f35f3902aa9b2c`.
- `LoginScreen.kt`, blob `5d2b59b95e27a1dc8e1a2d2954d982afd8e1bb2c`.

Este documento no afirma que las 23 entidades deban copiarse literalmente a Firestore. Registra alcance y preguntas para impedir pérdida de datos o sincronización accidental de configuraciones privadas del dispositivo.

## Inventario inicial (23 entidades Room)

| Entidad / tabla | Función observada en código | Hipótesis inicial para nube | Pendiente antes de implementar |
|---|---|---|---|
| `MatrizEntity` / `matriz_table` | Registro principal del cliente; estado, observaciones, referencias, ubicación, pagos y campos auxiliares. Fuente actual de varias pantallas. | **Compartida, prioridad piloto candidata.** | Mapear todos los CRUD, ID cliente, campos solo locales, fotos, renombres/borrados, conflictos concurrentes y exportación. |
| `TicketPagoEntity` / `ticket_pago_table` | Tickets OCR/pago, monto, CU/nombre y fecha; suma de pagos y control de duplicados. | **Operativa; probablemente compartida**, pendiente definir relación con el pago en Matriz. | Ver si ticket es comprobante independiente o dato derivado; ID global/idempotencia y política de borrado. |
| `VisitaMapaEntity` / `visita_mapa_table` | Historial de visita por clave y día con coordenadas, nombre, cartucho y timestamp; comentario indica local/no sincronizada. | **Compartida**, si representa visita operativa del equipo. | Confirmar quién puede registrar/editar, política de conservación actual (~120 días), clave estable y datos de ubicación. |
| `PaseEntity` / `pase_cartera_table` | Copia independiente de registro que pasa a cartera Pase; edición separada de Matriz. | **Compartida**, prioridad alta. | Confirmar relación con Matriz, renombres, borrados y cola de operaciones; preservar semántica de copia independiente. |
| `PaseSyncOperationEntity` / `pase_sync_operation_table` | Cola durable local para renombrar/borrar Pase en Sheets. | **No replicar como dato de negocio**; reemplazar por outbox genérica de nube o adaptador temporal. | Diseñar operación idempotente y transición segura desde cola actual sin perder pendientes. |
| `SolicitudEntity` / `solicitud_table` | Solicitudes con contacto, imágenes, audio, observaciones, estado y gestor asignado. | **Compartida**, prioridad alta. | Mapear adjuntos/Storage, permisos, cambios concurrentes y estado de sincronización. |
| `FiltroFechaEntity` / `filtro_fecha_table` | Registro de Filtro Fecha con estado, fecha/hora, observaciones y datos del cliente. | **Posible vista/registro operativo compartido; verificar si es fuente independiente o proyección de Matriz.** | Leer importación y CRUD completos; evitar duplicar la misma operación en dos colecciones. |
| `FiltrarEntity` / `filtrar_table` | Datos de pantalla Filtrar con referencias y campos similares a Matriz. | **Probable proyección/caché**, no copiar a nube sin verificar origen y escrituras. | Identificar si tiene CRUD propio o si solo se rellena desde Sheets/Matriz. |
| `ControlEntity` / `control_table` | Requerido por semana y valores de control; algunas vistas calculan cifras localmente desde Matriz. | **Probablemente derivada o catálogo de control**, no asumir que es fuente operativa. | Ver consumidores, origen de datos y si existe edición manual. |
| `BloqueHorarioEntity` / `bloques_horario_table` | Bloques horarios de llamadas automatizadas. | **Configuración a clasificar por alcance**: común del equipo vs específica del dispositivo/gestor. | No mezclar sincronización de configuración con ejecución local de llamadas; confirmar si todos los teléfonos deben compartir horarios. |
| `ContactoLogEntity` / `contacto_log_table` | Historial de intentos/contactos por cliente y día, usado para catchup/meta de contacto. | **Operativa potencialmente compartida**, con datos personales. | Definir visibilidad, retención, ID global y deduplicación para que varios teléfonos no dupliquen contactos. |
| `PlantillaSmsEntity` / `plantilla_sms_table` | Plantillas por tipo/semana/slot, con unicidad compuesta. | **Configuración posiblemente compartida**. | Confirmar quién edita y si rotación/slot deben sincronizarse entre dispositivos. |
| `ConfiguracionAutomatizacionEntity` / `config_automatizacion_table` | Preferencias de automatización; incluye selección de SIM y ocultar número. | **Mixta: separar preferencias globales de hardware local.** | No sincronizar número de SIM/ajustes ligados al teléfono como si fueran globales; clasificar campo por campo. |
| `ReglaSemanaEntity` / `regla_semana_table` | Reglas por semana para catchup/offsets. | **Configuración posiblemente compartida**. | Confirmar autoridad de edición y consistencia entre dispositivos. |
| `RutaIAEntity` / `ruta_ia_table` | Registros de ruta IA; el flujo actual puede reemplazar la hoja completa al sincronizar cambios. | **Compartida**, con control de concurrencia obligatorio. | Definir dueño, versión, orden, estado visitado y evitar reemplazo completo que borre cambios de otro teléfono. |
| `RutaIAFiltroEntity` / `ruta_ia_filtro_table` | Criterios de orden de Ruta IA. | **Configuración por usuario/dispositivo o global pendiente**. | Confirmar si filtros son preferencia personal o regla compartida del equipo. |
| `CanalPagoEntity` / `canal_pago_table` | Catálogo de lugares/canales de pago con latitud/longitud; se describe como descargado de hoja catálogo. | **Catálogo compartido de solo lectura**, si se confirma origen vigente. | Identificar fuente autoritativa y frecuencia de actualización; no migrar el mecanismo de Apps Script fuera de alcance sin petición. |
| `ContactoExtraEntity` / `contacto_extra_table` | Contactos extra asociados a cliente para ciclos de llamadas. | **Compartida**, por afectar contactos y acciones operativas. | Definir permisos, normalización de teléfono, IDs estables y deduplicación. |
| `VelocidadEntity` / `velocidad_table` | Entradas manuales para reporte Penalización/Velocidades, fila única local según comentario. | **Dato operativo manual; no dejar aislado si lo necesita el equipo.** | Determinar si corresponde al usuario/gestor o al equipo; no subir cálculos derivados, solo entradas fuente. |
| `ComisionEntity` / `comision_table` | Capturas manuales de montos por segmentos y meta, fila única local según comentario. | **Dato operativo manual; alcance por gestor/equipo pendiente.** | Definir propietario lógico, periodo/semana e ID estable; revisar riesgo de que una fila única mezcle gestores. |
| `BolsaGerenciaEntity` / `bolsa_gerencia_table` | Entradas manuales para reporte Bolsa Gerencia, fila única local según comentario. | **Dato operativo manual posiblemente compartido**. | Definir periodo, propietario, permisos y datos fuente vs cálculos. |
| `CartuchoDiaEntity` / `cartucho_dia_table` | Cartucho activo por fecha, guardado localmente. | **Estado operativo compartido pendiente**; probablemente necesita estado global si varios teléfonos trabajan el mismo día. | Definir quién puede cambiarlo, resolución de cambios simultáneos y zona horaria/día de negocio. |
| `Sem6RegistroEntity` / `sem6_registro_table` | Registros de Semana 6 por hoja/semana y cliente, con campos editables; el código indica reemplazo de una hoja antigua. | **Compartida**, si es registro operativo que varios teléfonos deben ver y editar. | Confirmar identidad por semana+ID, edición/borrado, restauración de borrados y exportación. |

## Hallazgos transversales verificados

1. Room usa `DB_VERSION = 38` en esta rama. No incrementar ni modificar migraciones sin un plan probado.
2. Las entidades con `isDirty`/`lastSync` no constituyen por sí mismas una cola de sincronización global, ni prueban confirmación del servidor.
3. El repositorio actual `SheetsRepository` está acoplado a Google Sheets. `updateSheetCell()` escribe una celda y retorna `Unit`; `appendRow()` agrega filas. No hay transacción atómica de negocio entre varios campos.
4. `SyncWorker` actual sigue sincronizando a Sheets, no a Firestore. Su aislamiento de errores entre módulos no equivale a sincronización nube bidireccional ni resolución de conflictos.
5. `LoginScreen` solicita email y scopes de Google Sheets/Drive, pero no solicita ID token ni autentica con Firebase Auth. Preservar los accesos actuales al diseñar autenticación.
6. La cola `PaseSyncOperationEntity` es específica de Sheets; se necesita diseñar una outbox que guarde cambios locales, ID de operación idempotente, entidad, versión/base conocida, estado y error/reintento, sin borrar pendientes hasta confirmación real del servidor.
7. Para todas las entidades, las bajas requieren tombstones o mecanismo equivalente para que un dispositivo antiguo no resucite registros eliminados.
8. Los datos sensibles de contacto, ubicación, pagos y solicitudes requieren reglas de autorización antes de cualquier prueba con datos reales.

## Orden de trabajo propuesto (sin cambios de aplicación todavía)

1. **P0-A — Mapeo de flujos:** para cada entidad prioritaria, encontrar los consumidores de DAO, CRUD y mecanismos actuales de descarga/subida; clasificar cada campo y operación con referencias a archivo/función.
2. **P0-B — Contrato de datos:** definir IDs globales, propietario/alcance, timestamps/versión, borrado, adjuntos, idempotencia y conflictos; no diseñar Firestore a partir de nombres de entidades solamente.
3. **P0-C — Piloto:** elegir el primer módulo tras el mapeo; construir un adaptador de nube aislado detrás de interfaz, mantener el camino actual intacto hasta pruebas.
4. **P0-D — Seguridad y validación:** autenticación, reglas mínimas por usuario/rol, emulador/datos sintéticos, dos teléfonos y pruebas offline/reintento/duplicado/conflicto.
5. **P0-E — Exportación:** solo después de validar nube, crear el flujo de exportación desde la fuente central a un archivo nuevo de Sheets. El archivo antiguo se conserva durante transición.

## Límites de esta revisión

- Es inventario y clasificación provisional; no implementa nube.
- No se ejecutó Gradle ni pruebas de dispositivo.
- No se cambió `main`, no se fusionó PR #122.
- Firestore debe conservar reglas `deny-all`.
- Las huellas Firebase/OAuth quedan fuera de este inventario; no modificar certificados para continuar el diseño.
- Los scripts de llamadas/SMS siguen fuera del alcance por instrucción del usuario.
