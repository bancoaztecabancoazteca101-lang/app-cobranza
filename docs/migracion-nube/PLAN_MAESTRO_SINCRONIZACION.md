# Plan maestro: sincronización global y migración a nube — App Matriz

**Estado:** auditoría/diseño inicial. No implementar la migración hasta completar inventario, reglas y piloto.  
**Referencia del código auditado:** `c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1`.  
**Rama documental:** `chatgpt/documentar-migracion-nube`.  
**Regla de seguridad:** no modificar `main`; la implementación futura debe ir en una rama independiente.

## 1. Objetivo y reglas no negociables

1. Todos los datos operativos de Matriz son datos compartidos/globales: deben estar disponibles en todos los teléfonos autorizados. Que un dato esté hoy en Room o SharedPreferences no significa que pertenezca a un solo dispositivo.
2. La base de datos en la nube será la fuente principal de verdad cuando se haya migrado y validado el módulo correspondiente.
3. La app debe funcionar sin conexión: guardar localmente los cambios pendientes y sincronizarlos automáticamente cuando vuelva la conectividad. No prometer sincronización instantánea mientras el dispositivo está desconectado.
4. No perder datos existentes durante la importación, migraciones de Room, cambios de esquema ni reintentos.
5. No marcar una operación como sincronizada hasta recibir confirmación de que fue aceptada por el destino canónico.
6. Las altas, modificaciones y eliminaciones deben propagarse a todos los dispositivos. Las bajas requieren tombstones o un mecanismo equivalente para evitar que un registro borrado reaparezca.
7. Los reintentos deben ser idempotentes: no duplicar clientes, pagos, visitas ni llamadas/SMS.
8. Google Sheets será un puente de compatibilidad/salida durante la transición, no una segunda fuente de verdad que compita con la nube.
9. Conservar Google Drive para multimedia y los respaldos actuales hasta probar y aprobar una alternativa segura.
10. Auditar el código real y las dependencias antes de implementar; no introducir código genérico ni afirmar que una compilación pasó sin ejecutarla.

## 2. Alcance técnico y límites

La revisión se hizo sobre el código del commit indicado, no sobre una base de producción ni sobre el contenido vivo de Sheets/Drive. No se verificó la configuración actual de consolas cloud ni los triggers instalados en Apps Script. No se ha elegido proveedor, implementado la migración ni compilado APK.

## 3. Hallazgos iniciales verificados en código

- `SyncWorker.doWork()` llama a sincronizaciones de Matriz, Pase, Ruta IA, Solicitud y Filtro Fecha; después encola la sincronización de imágenes. No constituye una sincronización global de todas las tablas.
- `SheetsRepository.updateSheetCell()` devuelve `Unit`; en `syncFiltroFecha()` se marca limpio el elemento incluso cuando no se encuentra una fila remota. Es un riesgo de pérdida silenciosa.
- La sincronización de Ruta IA reemplaza la hoja completa si hay cambios pendientes. Dos teléfonos con estados diferentes pueden sobrescribirse.
- Hay operaciones de borrado que no dejan un registro de eliminación común a todos los dispositivos.
- Se encontraron tablas locales de tickets de pago, visitas del mapa, cartucho por día, Semana 6 y contactos extra sin una ruta global demostrada en el trabajador de sincronización.
- Existen campos operativos de Matriz descritos como locales/no exportados, entre ellos `montoCobrado` y otros datos auxiliares. Deben evaluarse para inclusión en el esquema compartido y no excluirse automáticamente.
- Fotos y audios utilizan Google Drive y referencias locales/remotas; su migración requiere preservar uso sin conexión y reintentos seguros.
- En la búsqueda del código revisado no se encontró integración de Firestore ni Realtime Database. Sí se encontraron Google Sign-In/Sheets/Drive y FCM para notificaciones. Esto no demuestra cómo están configuradas las consolas externas.

## 4. Inventario inicial de datos compartidos

| Módulo | Ubicación identificada | Situación inicial | Requisito de migración |
|---|---|---|---|
| Matriz/clientes | `matriz_table`, hoja Matriz | Sincronización parcial | Compartir todos los campos operativos, conservar IDs y multimedia. |
| Pase de Cartera | tabla/hoja Pase | Sincronización parcial | Replicar altas, cambios y bajas; mantener relaciones con el registro original. |
| Solicitud | tabla/hoja Solicitud | Sincronización parcial | Replicar campos, imágenes y audio con confirmación. |
| Filtro Fecha | tabla/hoja Filtro Fecha | Parcial; riesgo de marcar limpio sin acuse | Cola de cambios y confirmación del destino canónico. |
| Filtrar | tabla/hoja Filtrar | Hay refresco; no se invoca desde SyncWorker periódico | Auditar campos editables y sincronizar cambios. |
| Control/reportes | tabla/hoja Control | Mixto | Compartir valores y definir claves por periodo. |
| Ruta IA y filtros | tablas/hoja Ruta IA | Reemplazo completo | Operaciones por registro, versiones y resolución de conflictos. |
| Semana 6 | `sem6_registro_table`, preferencias y hojas históricas | Principalmente local en el flujo actual | Compartir registros, notas, capital, abono, estados, altas y bajas. |
| Visitas del mapa | `visita_mapa_table` | Local | Replicar visitas idempotentes, sin duplicar el mismo evento. |
| Cartucho por día | `cartucho_dia_table` | Local | Compartir la selección por fecha y definir conflictos si dos dispositivos la cambian. |
| Tickets de pago | `ticket_pago_table` y `montoCobrado` | Local | Identidad idempotente, auditoría y prevención de doble conteo. |
| Contactos extra | `contacto_extra_table` | Local | Compartir altas/bajas; deduplicar por cliente y teléfono. |
| Automatización | horarios, logs, plantillas SMS, configuración y reglas semanales | Sincronización global no demostrada | Compartir configuración; evitar ejecución duplicada de SMS/llamadas entre teléfonos. |
| Ajustes y reportes | tablas de velocidad, comisión y bolsa de gerencia | Local/mixto | Compartir valores y establecer ámbito/periodo. |
| Catálogo de canales de pago | tabla local, hoja y Apps Script | Se descarga desde hoja/script | Definir fuente canónica y versión. |
| Fotos y audios | Drive y rutas guardadas en tablas/hojas | Mixto | Mantener archivos, referencias durables y copias offline hasta confirmar migración. |
| Configuración local | SharedPreferences y otras preferencias | Debe revisarse clave por clave | Compartir toda configuración funcional; mantener local solo cachés/estado técnico sin significado de negocio, documentando cada excepción. |

Este inventario es inicial, no una afirmación de exhaustividad. La fase 0 debe revisar todas las entidades, DAOs, ViewModels, Workers, preferencias, scripts y consumidores externos de cada hoja/carpeta.

## 5. Arquitectura objetivo

### 5.1 Datos y cambios
- ID global estable por entidad; nunca usar posición de fila en Sheets como identidad.
- Base cloud canónica con versiones/metadata de actualización.
- Outbox local en Room: guardar el cambio de negocio y la operación pendiente en una transacción.
- Worker de sincronización con reintentos y backoff; estado visible de pendiente/error/sincronizado.
- Confirmación explícita del servidor antes de limpiar una operación.
- Tombstones versionados para las eliminaciones, retenidos hasta que los dispositivos/consumidores relevantes las hayan procesado o hasta que exista una política segura de compactación.
- Idempotency keys para pagos, visitas y efectos secundarios.

### 5.2 Conflictos
No adoptar automáticamente “última escritura gana” para todo. Definir por entidad/campo: fusión por campos independientes, prioridad para operaciones monotónicas, y conflicto explícito para cambios incompatibles. Ruta IA no debe reemplazarse a ciegas como una lista completa compartida.

### 5.3 Multimedia
Subir archivo y verificar resultado; después guardar/confirmar la referencia en la nube. Si falla la segunda parte, reintentar sin perder la copia local. No eliminar archivos ni cachés de fotos hasta comprobar referencias y recuperación offline.

### 5.4 Google Sheets
Un puente separado proyecta cambios confirmados de la base cloud a las hojas que deban seguir existiendo. La caída de Sheets no debe revertir el dato canónico. Comparar conteos y campos durante la transición. No retirar scripts/triggers hasta rastrear sus consumidores y reemplazos.

### 5.5 Identidad y seguridad
Antes del piloto, definir quién puede leer/escribir los datos compartidos, autenticación por usuario/dispositivo, permisos, auditoría y protección de credenciales. No asumir que una hoja compartida equivale a un sistema de autorización adecuado.

## 6. Fases

0. **Inventario completo:** entidades, DAOs, ViewModels, Workers, SharedPreferences, archivos, scripts y procesos externos. Entregable: mapa de cada dato desde pantalla/origen hasta almacenamiento, consumidores y sincronización.
1. **Decisión tecnológica:** comparar Firestore y una opción relacional como Supabase con los requisitos reales de modo offline, consultas, conflictos, seguridad, coste y mantenimiento.
2. **Modelo cloud y seguridad:** esquema, IDs, reglas, versiones, tombstones, auditoría, migración y restauración.
3. **Piloto aislado:** una rama nueva; un módulo representativo; dos dispositivos y pruebas offline/conflictos. No tocar `main`.
4. **Migración por módulos:** Matriz, Solicitud, Pase, Filtro Fecha, Filtrar, Control, Ruta IA, Semana 6, mapa, cartuchos, tickets, contactos, automatización y demás ajustes/reportes.
5. **Puente de Sheets:** replicar desde nube, reconciliar conteos/campos, conservar respaldos.
6. **Validación/corte:** solo retirar escrituras directas a Sheets cuando pruebas, reconciliación y recuperación estén aprobadas.

## 7. Pruebas de aceptación obligatorias

- Crear y editar sin internet; al recuperar conexión, sincronizar automáticamente sin intervención manual.
- Editar el mismo registro en dos teléfonos y aplicar la regla de conflicto documentada.
- Borrar en un teléfono y confirmar que no reaparece en los demás.
- Capturar el mismo ticket desde más de un dispositivo sin duplicar monto ni pago.
- Registrar visita/cartucho sin duplicados y con resultado común.
- Ver fotos/audios sin internet y conservarlos durante fallos/reintentos.
- Simular nube confirmada pero Sheets fallido: conservar dato y reintentar el puente.
- Probar reinicio, cierre forzado, pérdida de red durante una escritura y restauración de respaldo.
- Migrar Room conservando datos existentes; no usar borrado destructivo como atajo.
- Verificar automatización para que dos teléfonos no disparen la misma llamada/SMS.
- Probar con los tres teléfonos y comparar conteos, IDs y campos críticos.

## 8. Referencias de código

- [SyncWorker.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1/app/src/main/java/com/example/matrizapp/SyncWorker.kt)
- [SheetsRepository.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1/app/src/main/java/com/example/matrizapp/SheetsRepository.kt)
- [Entities.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1/app/src/main/java/com/example/matrizapp/Entities.kt)
- [DAOs.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1/app/src/main/java/com/example/matrizapp/DAOs.kt)
- [AppDatabase.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1/app/src/main/java/com/example/matrizapp/AppDatabase.kt)
- [ContactoExtra.kt](https://github.com/bancoaztecabancoazteca101-lang/app-cobranza/blob/c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1/app/src/main/java/com/example/matrizapp/ContactoExtra.kt)

## 9. Registro de decisiones
- **Confirmado por el usuario:** todos los datos operativos deben ser globales/compartidos; no hay datos operativos exclusivos de un teléfono.
- **Confirmado como objetivo:** sincronización automática al volver la conexión; base cloud como fuente principal y Sheets secundario durante transición.
- **Pendiente:** proveedor cloud, esquema definitivo, política de conflictos por entidad, autenticación/permisos y plan de corte.


## 10. Comparación técnica de candidatos (evaluación inicial, 2026-10-18)

Esta comparación no es una contratación ni una elección irreversible. Debe validarse con un piloto, medición del volumen real y revisión de reglas de seguridad y costes.

| Criterio | Cloud Firestore | Firebase Realtime Database | Supabase (PostgreSQL) |
|---|---|---|---|
| Datos offline en Android | SDK mantiene caché y sincroniza al volver la red; persistencia habilitada por defecto | Cola de escrituras y persistencia en disco disponibles; hay que habilitar persistencia | No asumir sincronización offline automática equivalente; conservar Room + outbox y programar sincronizador propio |
| Modelo de datos | Documentos/colecciones; requiere modelar relaciones y consultas | Árbol JSON; relaciones y consultas complejas requieren diseño adicional | Relacional SQL; se adapta mejor a relaciones, reportes y consultas de cruce |
| Conflictos | Para varias escrituras al mismo documento, la documentación describe “última escritura gana”; no usarlo como política universal | La cola/reconexión ayuda, pero las operaciones/estructura necesitan reglas de conflicto propias | Reglas de conflicto y transacciones se diseñan en SQL/API; la app necesita cola idempotente |
| Tiempo real | Escucha cambios en documentos/consultas | Escucha cambios en rutas de datos | Realtime disponible, sujeto al diseño y límites del plan |
| Trabajo de integración | Potencialmente menor para el primer piloto Android offline | Potencialmente bajo para sincronización sencilla, mayor al modelar relaciones | Mayor trabajo inicial para outbox/sync offline; ventaja de SQL y restricciones relacionales |
| Coste y límites | Depende de lecturas, escrituras, almacenamiento, tráfico y región; calcular con telemetría del piloto | Depende de almacenamiento y tráfico; calcular con volumen y frecuencia reales | Plan Free limitado y puede pausar proyectos inactivos; Pro aparece desde USD 25/mes en la página consultada. Revisar condiciones vigentes antes de decidir |
| Riesgo principal para Matriz | Conflictos si varios teléfonos editan el mismo documento; coste por patrón de lecturas; reglas de seguridad mal configuradas | Estructura menos cómoda para reportes relacionales y cambios parciales entre módulos | La sincronización offline no se obtiene solo por añadir el SDK; hay que construirla y probarla |

### Referencias oficiales consultadas
- Firestore offline en Android y política de última escritura gana: https://firebase.google.com/docs/firestore/manage-data/enable-offline
- Realtime Database offline, persistencia y cola de escrituras: https://firebase.google.com/docs/database/android/offline-capabilities
- Supabase precios y cuotas: https://supabase.com/pricing
- Supabase facturación/uso: https://supabase.com/docs/guides/platform/billing-on-supabase

### Recomendación provisional
1. **No seleccionar proveedor de producción todavía.**
2. Hacer una prueba de concepto aislada con Firestore porque ofrece persistencia offline integrada en Android y sincronización al recuperar red; el SDK por sí solo no resuelve los conflictos de negocio de Matriz.
3. Comparar esa prueba contra un pequeño prototipo de Supabase + Room/outbox si los cruces relacionales, reportes y consistencia de datos resultan prioritarios. Realtime Database queda como alternativa si domina la sincronización simple en tiempo real, pero no es el primer candidato para todo el modelo de Matriz.
4. No considerar el piloto aprobado hasta simular edición simultánea, borrado, desconexión prolongada, reinicio, fallo parcial y reconciliación con Sheets. Revisar coste real antes del corte.

## 11. Inventario técnico ampliado y pendientes de verificación

La revisión del código confirma que el inventario debe contemplar, como mínimo, estos grupos. Todos los datos operativos se consideran compartidos por instrucción del usuario; la etiqueta “local” describe la implementación actual, no una excepción autorizada.

| Grupo de datos | Evidencia en el código revisado | Trabajo requerido |
|---|---|---|
| Entidades Room principales | Entities.kt, AppDatabase.kt, DAOs.kt | Enumerar cada entidad, claves, campos, índices, relaciones y DAO; documentar tabla por tabla |
| Migraciones locales | AppDatabase.kt, versión 37 | Conservar historial y datos de todas las versiones soportadas; ensayar migración sobre copias de bases reales |
| Operaciones pendientes | flags isDirty, workers y métodos markAsClean | Inventariar cada mutación y exigir acuse verificable antes de limpiar |
| Preferencias de negocio | SharedPreferences, incluidos sem6_cache y configuración de automatización | Listar claves y consumidores; migrar valores funcionales y diferenciar solo cachés técnicas documentadas |
| Bajas | métodos eliminarRegistro, DAO deletes, deleted_ids_* | Rastrear cada borrado y crear tombstone global con ID/versión; evitar resurrección al refrescar |
| Efectos externos | SMS, llamadas, notificaciones, People API, Apps Script | Registrar operaciones enviadas, evitar ejecución duplicada por varios teléfonos y separar “dato sincronizado” de “acción ejecutada” |
| Multimedia | Drive, ClientImageSyncWorker, fotos y audios | Inventariar origen, ruta, MIME, hash/tamaño, propietario lógico y consumidores; migrar referencia y archivo sin romper offline |
| Hojas y scripts externos | Constants.kt, AppsScript/, firebase-apps-script/ y scripts externos descritos por el usuario | Identificar todos los escritores/lectores, triggers instalados, cuentas y hojas de destino; confirmar los consumidores de CONTACTOS y Contac_Subir antes de retirar procesos |
| Reportes/catálogos | Control, canales de pago, velocidad, comisión, bolsa de gerencia | Definir claves por periodo/zona y si son datos editables o proyecciones calculadas |
| Dispositivos | registro de dispositivos/FCM | Separar identidad del dispositivo, identidad de usuario y autorización; no usar un Android ID como única identidad de usuario |
| Respaldo/restauración | BackupManager, Room + WAL/SHM, Drive | Ensayar restauración completa y reconciliación con nube; mantener respaldo anterior hasta aceptación firmada |
| Cachés y derivados | resultados calculados y cachés locales | Documentar origen de verdad y regla de reconstrucción; si contienen un valor operativo, compartirlo o justificar explícitamente su derivación |

### Riesgos que bloquean la migración de producción
- Las escrituras a Sheets en varios flujos se realizan campo por campo y el método updateSheetCell no expone resultado; hace falta distinguir escritura parcial, fila no encontrada y éxito.
- Ruta IA se reemplaza como lista completa; dos dispositivos pueden pisarse.
- El borrado local de Pase y las marcas locales de Semana 6 no prueban una baja global.
- montoCobrado, tickets y el cambio de estado asociado necesitan una operación idempotente para no contar dos veces un pago.
- Contactos extra se usan en flujos de llamadas/SMS, pero no se demostró sincronización global.
- Los scripts externos pueden seguir escribiendo a Sheets aun después de que la app cambie; no retirar el puente sin identificar y reemplazar esos consumidores.
- No se ha inspeccionado una copia de producción de Room, el contenido actual de Sheets/Drive, los triggers instalados ni los costes/volúmenes reales. No afirmar que el inventario está cerrado hasta verificarlo.

## 12. Siguiente entregable de auditoría

Antes de implementar, producir un inventario fila por fila con: entidad/clave, campos, pantalla y flujo que la modifica, DAO/Repository/Worker, ubicación actual, escritor externo, consumidor, regla de conflicto, tratamiento de bajas, plan de importación, validación y recuperación. Después documentar el resultado del piloto de proveedor y pedir revisión técnica de Claude/Gemini antes de abrir una rama de implementación.


## 13. Inventario por tabla Room verificada en AppDatabase.kt (DB_VERSION 37)

Se identificaron 22 entidades declaradas en la base de Room de la revisión. La lista siguiente refleja el código actual, no una decisión de dejar datos locales. Todos los campos operativos deberán estar disponibles de forma global. Los campos técnicos de sincronización se conservarán como metadatos o se sustituirán por un mecanismo equivalente validado.

| Entidad / tabla | Clave actual | Campos o datos principales identificados | Uso y tratamiento para nube |
|---|---|---|---|
| MatrizEntity / matriz_table | id (String) | nombre, semana, requisito, numTT, referencias 1–4, observaciones, estado, ubicación, imágenes, fecha/hora, ruta, folio, descuentos, día de pago, domicilio laboral, días de atraso/apertura, montoCobrado, isDirty/lastSync | Registro principal. Incluir campos hoy ausentes de Sheets; versión y conflicto por campo. |
| TicketPagoEntity / ticket_pago_table | id (String) | fecha, nombre, CU, monto | Evento financiero idempotente; no sumar el mismo ticket dos veces en dispositivos distintos. |
| PaseEntity / pase_cartera_table | id (String) | campos de Matriz, origenMatrizId, contiene, capitales, estado | Copia de cartera que actualmente se edita aparte; conservar relación y documentar si el origen se sincroniza o no. |
| SolicitudEntity / solicitud_table | id (String) | nombre, número, sucursal, ubicación raw, hasta cuatro imágenes, referencias, observaciones, audio, estado, gestor, fecha/hora | Compartir registro y referencias multimedia durables. |
| FiltroFechaEntity / filtro_fecha_table | id (String) | nombre, estado, observaciones, numTT, fecha/hora, imagen, referencias, ubicación, requisito | Compartir estados y cambios; eliminar limpieza sin acuse. |
| FiltrarEntity / filtrar_table | id (String) | nombre, semana, requerido, numTT, referencias, observaciones, estado, ubicación, imagen, fecha/hora | Auditar si es proyección de Matriz o registro independiente; evitar dos fuentes editables. |
| ControlEntity / control_table | semana (String) | requerido, lastSync | Compartir por semana; definir si es dato introducido o cálculo. |
| BloqueHorarioEntity / bloques_horario_table | id autogenerado | hora, minuto, activo | Configuración de horarios de llamadas; un único calendario global y cambios auditables. |
| ContactoLogEntity / contacto_log_table | id autogenerado | clienteId, fechaDia, bloqueIndex, timestamp | Registro de acciones para no repetir llamadas/SMS/catch-up entre teléfonos; retención y deduplicación global. |
| PlantillaSmsEntity / plantilla_sms_table | id autogenerado; índice único tipo+semana+slot | tipo, semana, slot, texto | Plantillas funcionales compartidas; evitar que cada teléfono rote/edite con estado distinto. |
| ConfiguracionAutomatizacionEntity / config_automatizacion_table | id (por defecto 1) | simSeleccionada, ocultarNumero, pausa entre llamadas, duración máxima, simSms | Ajustes funcionales de automatización compartidos según instrucción; revisar si las SIM son específicas del hardware y modelar esa excepción como configuración de dispositivo, no dato de cliente. |
| ReglaSemanaEntity / regla_semana_table | semana (Int) | offsets, catchupActivo | Reglas semanales compartidas; controlar concurrencia de ejecución. |
| RutaIAEntity / ruta_ia_table | id (String) | nombre, CU, dirección, colonia/CP, días atraso, pago requerido, saldo, lat/lng, orden, esNuevo, match Matriz, fechaDia, estado, fotoOrigenUrl, isDirty/lastSync | Compartir cambios por registro, no reemplazar toda la ruta desde un teléfono. |
| RutaIAFiltroEntity / ruta_ia_filtro_table | id (Int, normalmente 1) | criteriosOrden | Preferencia funcional compartida; resolver cambios concurrentes. |
| CanalPagoEntity / canal_pago_table | id (String) | nombre, empresa, dirección, lat/lng, lastSync | Catálogo común con versión de origen y estrategia de actualización. |
| ContactoExtraEntity / contacto_extra_table | id autogenerado; índice clienteId | clienteId, teléfono, nombreOrigen, fechaAgregado | Compartir referencias extra; clave lógica clienteId+teléfono para no duplicar. |
| VelocidadEntity / velocidad_table | id (String) | rk, plan100, total, reque36, monto36, cuPase, capital, diasOp, lastUpdate | Compartir o recalcular de forma consistente; documentar periodo y fuente de cada valor. |
| ComisionEntity / comision_table | id (String) | momento12, indirecta12, momento3, indirecta3, momento46, indirecta46, momento79, indirecta79, meta, lastUpdate | Compartir valores de cálculo/configuración con periodo y versión. |
| BolsaGerenciaEntity / bolsa_gerencia_table | id (String) | cobranzaGerencia, cumplimientoPlan, montoBase, numeroGestores, cobranza1a9Gestor, cobranza1a9Gerencia, lastUpdate | Compartir o recalcular con regla común; no permitir cifras divergentes por dispositivo. |
| VisitaMapaEntity / visita_mapa_table | clave + fechaDia | nombre, ubicación, matrizId, cartucho, timestamp | Evento compartido idempotente; definir la identidad de una visita y cómo corregir una visita equivocada. |
| CartuchoDiaEntity / cartucho_dia_table | fechaDia | cartuchoActivo | Selección por fecha compartida; regla de conflicto si dos dispositivos la cambian. |
| Sem6RegistroEntity / sem6_registro_table | hoja + id | nombre, semana, requisito, CU, ubicación, imagen, colonia, visitas, última visita, numTT, contiene, susceptible, observaciones, capital, abono | Compartir cambios, altas y bajas; reemplazar deleted_ids locales por tombstones globales. |

### Preferencias y estado fuera de Room identificados

Estos datos no son entidades Room y requieren inventario separado. La presencia de un valor en SharedPreferences no basta para decidir que deba compartirse: debe determinarse si representa negocio compartido o un estado estrictamente técnico del dispositivo. La instrucción del usuario exige compartir los valores operativos.

| Almacén/clave identificada | Función actual | Tratamiento objetivo |
|---|---|---|
| SharedPreferences sem6_cache: data, timestamp, deleted_ids por hoja | Caché Sem6 y registro local de IDs borrados | Sincronizar registros y bajas globales; mantener caché local reconstruible. |
| sms_status_config: enabled_statuses, sender_enabled, subscriptionId, sent_events, baseline_* | Selección de estados SMS y control de eventos | Separar configuración de negocio compartida de subscriptionId/estado técnico de SIM; deduplicar envíos por ID global de acción. |
| numero_contactado_guard y numero_sms_guard | Ventanas anti-repetición de llamadas/SMS | Cambiar a guard global/idempotencia para impedir que varios teléfonos contacten al mismo cliente en la misma ventana. |
| Preferencias de NotificacionesHelper para retornos: ids, historial, ids_matriz | Evitar duplicar alarmas locales | Mantener alarmas locales derivadas, pero la fuente del evento/estado de retorno debe ser global. |
| Preferencias de BackupManager: versión, último respaldo local/Drive, aviso de downgrade | Operación técnica del respaldo | Permanecen locales si solo describen ese dispositivo; los archivos de respaldo deben seguir siendo recuperables y coordinados. |
| Preferencias adicionales no enumeradas todavía | No se ha terminado la búsqueda de todos los consumidores | Completar búsqueda de SharedPreferences, DataStore, archivos privados, alarmas, servicios y claves de configuración. |

### Estado del inventario
- **Verificado:** 22 entidades en la anotación @Database de AppDatabase.kt, con DB_VERSION 37.
- **Verificado:** ContactoExtraEntity se declara en ContactoExtra.kt y aparece en la lista de entidades de Room; su tabla no debe omitirse.
- **Pendiente:** inventario campo por campo de las entidades adicionales y sus DAO/ViewModels, todas las claves de preferencias, preferencias de SIM por dispositivo, configuración de seguimiento por día, scripts externos y consumidores vivos de Sheets/Drive.
- **Pendiente:** comprobar si el esquema de producción coincide con el commit revisado; no se dispone desde esta auditoría de una copia de la base local de cada teléfono ni acceso confirmado al contenido vivo de Sheets/Drive.


## 14. Trazabilidad de sincronización: pantalla/DAO → Sheets (revisión del código)

La presencia de un DAO o de un método `getDirtyItems()` no demuestra que el proceso de sincronización lo ejecute. La tabla distingue la carga desde Sheets, el envío a Sheets y la persistencia solamente local.

| Datos / flujo | Lectura o edición observada | Envío remoto verificado en SyncWorker | Riesgo/decisión pendiente |
|---|---|---|---|
| Matriz | MatrizViewModel → MatrizDao; `isDirty`; `refreshMatriz()` evita reemplazar filas sucias | Sí: `syncMatriz()` actualiza o agrega, sube imágenes y limpia si el registro no cambió durante el envío | Buen patrón parcial de control de edición concurrente; ampliar a campos omitidos y validar resultado de escritura remota. |
| Solicitud | SolicitudViewModel → SolicitudDao; estado, fotos/audio y `isDirty` | Sí: `syncSolicitud()` actualiza/agrega y procesa multimedia | Probar fallo entre subida multimedia y escritura de fila; bajas no tienen tombstone global confirmado. |
| Pase de cartera | PaseCarteraViewModel → PaseCarteraDao | Sí: `syncPase()` procesa filas sucias | El borrado descrito solo elimina localmente; el registro puede reaparecer en refresh. |
| Filtro Fecha | FiltroFechaViewModel y FiltroFechaDao | Sí: `syncFiltroFecha()` | Se observó que la rutina puede marcar limpio aunque no encuentre fila remota y no verifica acuses de `updateSheetCell`; riesgo de perder cambios. |
| Ruta IA | RutaIAViewModel → RutaIADao | Sí, pero como reemplazo completo de la lista cuando hay cambios | Conflicto crítico: un teléfono puede borrar cambios de otro; `RutaIALimpieza` también puede reemplazar por lista vacía. Cambiar a operaciones por ID/versionado. |
| Filtrar | FiltrarViewModel usa `MatrizDao.updateGestionLocal()` para guardar gestión y dispara sincronización general; SheetsRepository tiene `getDirtyFiltrarItems()` y `markFiltrarAsClean()` | No se encontró una llamada a `syncFiltrar()` dentro de SyncWorker; `refreshAll()` sí ejecuta `refreshFiltrar()` | Confirmar si Filtrar es proyección de Matriz o datos propios; no asumir que tener métodos dirty significa que se publican. |
| Control | ControlViewModel calcula requeridos a partir de Matriz; ControlDao tiene filas/flags | No se encontró envío de Control en SyncWorker; `refreshAll()` hace `refreshControl()` | Decidir fuente canónica: cálculo compartido determinista o datos editables remotos. |
| Tickets de pago | FiltroFechaViewModel → TicketPagoDao; inserción local para evitar doble conteo | No | Sincronizar evento con ID idempotente, referencia al cliente y monto/fecha; deduplicar globalmente y conciliar con estado Pagado. |
| Visitas / mapa | MatrizViewModel y Flujos de visita → VisitaMapaDao | No | Compartir evento de visita con clave única y política de corrección; hoy la semana del mapa puede variar por teléfono. |
| Cartucho por día | CartuchoAviso/flujo Matriz → CartuchoDiaDao | No | `fechaDia → cartuchoActivo` necesita fuente global y resolución si dos teléfonos seleccionan distinto. |
| Semana 6 | Sem6ViewModel/Sem6Generador → Sem6Dao; edición y bajas locales; cache `sem6_cache` | No; métodos antiguos de Sheets existen pero el flujo actual recalcula localmente | Las bajas locales no son bajas globales. Compartir cambios de notas/capital/visitas/estado y tombstones por hoja+ID. |
| Contactos extra | FiltrarViewModel → ContactoExtraDao; clave lógica clienteId+teléfono | No | El dato alimenta mensajes/llamadas; no sincronizarlo puede generar diferentes destinatarios por teléfono. |
| Bloques, plantillas y configuración de automatización | BloqueHorarioViewModel, PlantillaSmsViewModel y ConfiguracionAutomatizacionDao | No | Compartir reglas funcionales; separar parámetros que son físicamente propios de cada SIM/teléfono. Evitar que todos los teléfonos ejecuten la misma acción automática. |
| Reglas semanales y filtro Ruta IA | DAOs `ReglaSemanaDao` y `RutaIAFiltroDao` | No | Sincronizar reglas de negocio y versiones; definir quién puede modificarlas. |
| Catálogo de canales de pago | CanalPagoDao y método de sincronización de catálogo dedicado | No forma parte de los cinco `sync*` de SyncWorker; tiene un flujo separado de catálogo | Confirmar el programador/activador real, autoría y cómo resolver cambios de usuario frente a reconstrucción del catálogo. |
| Velocidad, Comisión y Bolsa Gerencia | DAOs de esas entidades; consumidos por vistas/cálculos | No se encontró envío en SyncWorker | Determinar si cada campo es fuente manual o derivado; si es derivado, centralizar fórmula o asegurar cálculo idéntico con mismos datos globales. |
| Registro de dispositivos/FCM | MultiDeviceNotificationManager y Apps Script | Flujo aparte basado en Google Sheets; no equivale a sincronización de datos de negocio | Mantener separado de la fuente de verdad operativa y revisar autorización de dispositivos. |

### Observaciones transversales verificadas
- `SheetsRepository.refreshAll()` intenta refrescar Matriz, Pase derivado de Matriz, Solicitud, Filtro Fecha, Filtrar, Control y Ruta IA. Que un conjunto se descargue no significa que sus cambios se publiquen correctamente.
- `SyncWorker.doWork()` ejecuta envíos para Matriz, Filtro Fecha, Pase, Ruta IA y Solicitud, además de encolar `ClientImageSyncWorker`. No se encontró envío en ese worker para tickets, visitas, cartucho del día, Semana 6, contactos extra, plantillas, bloques, configuración de automatización, reglas semanales, velocidades, comisiones o bolsa de gerencia.
- `SheetsRepository.updateSheetCell()` no devuelve un resultado de negocio comprobable al llamador; los flujos deben distinguir éxito, fila no encontrada, error de red y escritura parcial antes de borrar el estado pendiente.
- El guard de llamadas/SMS (`NumeroContactadoGuard`) y los logs de bloque (`ContactoLogDao`) se guardan localmente. Un `Mutex` en un proceso y SharedPreferences por teléfono no impiden que dos teléfonos contacten al mismo cliente simultáneamente. La futura nube necesita reclamar la acción de forma atómica y con expiración/idempotencia antes de iniciar llamada/SMS.
- Los identificadores autogenerados localmente (ticket, visita, contactos extra, logs) no son suficientes como claves globales: definir UUID/clave de negocio estable antes de importar datos de múltiples teléfonos.

### Prioridad revisada
1. P0: proteger datos existentes, inventariar todos los escritores externos y crear copia restaurable antes de cualquier importación.
2. P0: acordar identidad global, esquema de cambios, tombstones, acuse real, idempotencia y reclamación de acciones automáticas.
3. P1: prototipo con Matriz, pago/ticket, visita, contactos extra y Semana 6, porque son flujos con impacto operativo y hoy tienen cobertura parcial/local.
4. P1: piloto en dos teléfonos con edición concurrente y desconexión/reconexión; reconciliar con Sheets sin borrar filas ni duplicar acciones.
5. P2: migrar configuraciones/catálogos/reportes y scripts externos; retirar puente de Sheets solo tras pruebas de equivalencia y recuperación.


## 15. Segunda pasada de preferencias y automatización (2026-10-19)

La lectura directa de SmsStatusWorker.kt, FiltrarScreen.kt, NumeroContactadoGuard.kt, NotificacionesHelper.kt, Sem6.kt, CartuchoAviso.kt y LlamadaAutomatica.kt confirmó estos almacenes adicionales:

| Preferencia | Uso actual | Tratamiento propuesto |
|---|---|---|
| automatizacion_prefs: activa, catchup_activa | Interruptores generales de llamadas automáticas y catchup | Estado funcional compartido/auditable; la ejecución debe coordinarse globalmente para que no todos los teléfonos llamen. |
| filtrar_contactos: agregar_automaticamente | Activa altas automáticas de contactos extra desde Filtrar | Definir alcance global o por operador; los contactos extra resultantes sí deben sincronizarse. |
| sms_status_config: enabled_statuses, sender_enabled, subscriptionId, sent_events, baseline_*, message_template | Estados elegibles, activación, SIM, plantilla y deduplicación local de SMS | Estados/plantilla: configuración funcional a compartir según política. subscriptionId: local a cada teléfono. sent_events y baseline: sustituir por registro global idempotente. |
| numero_contactado_guard / numero_sms_guard | Bloqueos por número durante 15/30 minutos | No son barrera global: sustituir/complementar con reserva atómica en nube, expiración y resultado de la acción. |
| retornos_programados: ids, historial, ids_matriz | Alarmas locales ya programadas | Estado técnico reconstruible, no dato de negocio compartido. |
| retornos_confirmados: ids fechados | Confirmación de retorno hoy en el dispositivo | El evento de negocio debe quedar en el registro compartido; la alarma/notificación sigue local. |
| cartucho_aviso | Evita notificación repetida de cambio de cartucho | Preferencia de notificación local; CartuchoDiaEntity sigue requiriendo sincronización global. |
| sem6_interna: importado_v1 | Evita repetir importación inicial Sem6 | Marcador técnico local; no prueba que los datos Sem6 estén sincronizados. |
| client_image_store | Índice local de archivos de imagen | Mantener caché/rutas locales; sincronizar contenido y metadatos con ID remoto estable. |
| multi_device_notifications | Token FCM e identificador de instalación | Identidad técnica por dispositivo, separada de la base operativa. |
| pase_import_gcr | Estado usado durante importación de Pase de Cartera | Pendiente rastrear cada clave y consumidor antes de clasificar. |

### Riesgos confirmados en llamadas y SMS

- AutomatizacionPrefs controla scheduler, receivers y workers fuera de Room. Migrar solo bloques/reglas/configuración Room no reproduce todo el comportamiento.
- ContactoLogDao, Mutex y NumeroContactadoGuard protegen dentro del teléfono/proceso, no entre teléfonos. La nube debe reclamar cada acción atómicamente antes de marcar/enviar, con clave idempotente, vencimiento y estados reservado/en curso/completado/fallido.
- subscriptionId es propio del hardware. Nunca debe copiarse como ID válido de SIM entre teléfonos.
- Reservar un número no equivale a que la llamada o SMS haya terminado correctamente; deben distinguirse reserva, ejecución y resultado para permitir reintentos seguros.

### Consumidores externos CONTACTOS: búsqueda y límites

- La búsqueda del literal Contac_Subir en el código indexado del repositorio no devolvió coincidencias.
- La búsqueda de CONTACTOS no identificó un consumidor claro en el código Kotlin indexado.
- Esto no demuestra que la hoja externa esté sin uso: otros proyectos Apps Script, fórmulas, AppSheet, hojas y triggers instalados pueden no estar versionados en este repositorio.
- Antes de retirar o cambiar el script “Actualizar contactos”, rastrear en las cuentas externas el ID de la hoja destino, la pestaña CONTACTOS, Contac_Subir y todos los triggers instalados. Los comentarios del script no prueban que un trigger esté activo.
- No se puede confirmar desde GitHub el contenido vivo de Sheets/Drive ni el estado real de triggers.

### Criterios de aceptación añadidos

1. Dos teléfonos no pueden reclamar y ejecutar la misma acción automática a la vez, incluso si ambos iniciaron un worker.
2. La SIM seleccionada se valida localmente contra las SIM disponibles.
3. Bajas y eventos confirmados se comparten como cambios versionados; alarmas, cachés, tokens e índices locales son reconstruibles.
4. No retirar CONTACTOS ni scripts hasta verificar consumidores y triggers y realizar una prueba reversible con copia.
5. El inventario de preferencias sigue abierto: falta rastrear todas las claves de pase_import_gcr y los demás usos de preferencias/almacenamiento no cubiertos por esta pasada.


## 16. Seguimiento GCR e imágenes offline

- En PaseCarteraViewModel.kt, `pase_import_gcr` guarda valores pendientes de Contiene/Capitales por `matrizId`. `procesarPendientes()` los aplica al registro de Pase cuando existe y luego elimina la preferencia. Es un cambio operativo temporal fuera de Room; debe pasar a una outbox durable y no borrarse antes de confirmar persistencia compartida.
- ClientImageStore.kt conserva imágenes en `filesDir/matriz_clientes`; `client_image_store` registra `ultima_limpieza`. La clave local de imagen deriva del origen (URL/ruta/URI), así que no sirve como identidad remota permanente. El piloto debe cubrir foto offline, subida fallida/parcial, URL nueva después de subir a Drive, restauración y limpieza de huérfanos sin borrar el único original pendiente.
- MultiDeviceNotificationManager.kt usa `multi_device_notifications` para `installation_id_fallback`, `device_name` y `enabled`, además del token FCM. Son identidad y preferencias del dispositivo, separadas de los datos de cobranza compartidos.
- Estos tres archivos quedan revisados para los usos descritos; la búsqueda global de todas las preferencias y de sistemas externos sigue abierta.


## 17. Tercera pasada: inventario completo de preferencias encontradas y scripts versionados

### Preferencias y almacenes confirmados por búsqueda global

La búsqueda del literal `getSharedPreferences` en el código indexado encontró estos almacenes principales. Es una lista de los resultados disponibles del índice, no una garantía de que el buscador haya devuelto todos los usos:

- `matriz_backup`: `last_local`, `last_drive`, `last_app_version`, `aviso_bajada`. Metadatos técnicos de respaldo por teléfono; conservar el sistema de backup mientras no exista recuperación probada desde la nube.
- `sms_status_config`: configuración de estados, plantilla, activación, SIM y registro local de eventos. Separar configuración de negocio, hardware local y deduplicación global.
- `numero_contactado_guard` / `numero_sms_guard`: bloqueo local por número; insuficiente para coordinar tres teléfonos.
- `sem6_cache`: datos cacheados, fecha de última respuesta y IDs borrados por hoja. La caché es local, pero el borrado debe convertirse en tombstone compartido; de lo contrario otro teléfono/Apps Script puede recrear el registro.
- `retornos_programados` y `retornos_confirmados`: alarmas por dispositivo frente al evento de negocio. La alarma se reconstruye localmente; la confirmación que cambia el flujo debe registrarse globalmente.
- `cartucho_aviso`: evita repetir una notificación local; no es la fuente de verdad del cartucho.
- `automatizacion_prefs`: activación de llamadas/catchup, fuera de Room.
- `filtrar_contactos`: preferencia de alta automática de contactos.
- `sem6_interna`: marcador de importación inicial, no confirma sincronización completa.
- `client_image_store`: marca de limpieza; los archivos están en el almacenamiento privado del teléfono.
- `pase_import_gcr`: contiene/capitales pendientes, indexados por ID Matriz. Se eliminan al aplicar a Pase; convertir a outbox durable antes de migrar.
- `multi_device_notifications`: ID fallback, nombre, habilitación y token FCM del dispositivo.
- `getDefaultSharedPreferences` y DataStore: no se encontraron coincidencias en la búsqueda ejecutada.
- `BackupManager` mantiene además la base Room y sus archivos WAL/SHM en ZIP. No retirar ni acortar la política de respaldo durante la transición.

### Scripts de Apps Script versionados: comportamiento que puede alterar datos

1. `AppsScript/RutaIA.gs`: `limpiarRutaIA()` vacía el contenido de la hoja `Ruta IA` desde la fila 2. El archivo describe instalación manual de un trigger diario de 4–5 AM, pero el repositorio no confirma que ese trigger esté instalado actualmente. Es un escritor destructivo concurrente que debe pausarse/retirarse solo cuando se compruebe su estado real y la app/nube ya tenga reemplazo.
2. `AppsScript/SincronizarCanalesPago.gs`: `sincronizarCanalesPago()` ejecuta `hoja.clearContents()` y vuelve a escribir encabezados y catálogo. Su función de configuración borra triggers anteriores del mismo handler y crea uno semanal los lunes a las 3 AM. No equivale a un trigger instalado verificado; la semilla manual debe conservarse como fuente de recuperación.
3. `firebase-apps-script/Code.gs`: gestiona registros de dispositivos FCM en la pestaña `NotificacionesDispositivos` del mismo spreadsheet. `pollRetornos_` consulta la hoja `Matriz ` y guarda deduplicación temporal `sentEvents` en ScriptProperties por siete días. Este backend es para notificaciones y no sincroniza la base operativa completa. El secreto/API key que aparece en el código debe tratarse como credencial expuesta: rotarlo y moverlo a configuración segura antes de ampliar permisos o migrar el backend; no reutilizarlo como credencial de la nueva base.
4. `AppsScript/NormalizarNombresMatriz.gs`: herramienta de normalización de nombres; antes de ejecutarla en producción, registrar copia previa y verificar exactamente qué columnas modifica.
5. En todos los casos, el repositorio describe el código, no el estado actual de los proyectos Apps Script, permisos, despliegues, triggers, propiedades ni contenido real de Sheets.

### Estado de la búsqueda CONTACTOS

- No se encontró `Contac_Subir` ni un lector explícito de la pestaña `CONTACTOS` en los resultados de búsqueda de código del repositorio.
- El script externo “Actualizar contactos” compartido anteriormente escribe una hoja `CONTACTOS` en otro spreadsheet. No puede darse por obsoleto solo porque el consumidor no aparezca en este repositorio.
- Acción necesaria fuera del repositorio: revisar en la cuenta propietaria todos los proyectos Apps Script, implementaciones web, activadores instalados, fórmulas, AppSheet y hojas que referencien el ID del spreadsheet destino, la pestaña `CONTACTOS` o `Contac_Subir`. Si no hay acceso a esas cuentas, este punto debe quedar marcado como no verificable, no como resuelto.

### Bloqueo para avanzar a implementación

No iniciar migración real ni desactivar escritores hasta completar: (a) inventario de todos los proyectos y triggers activos; (b) copia/validación de datos en producción; (c) diseño de IDs globales, versiones, tombstones y acuses; (d) prueba paralela de dos o tres teléfonos, incluyendo offline, eliminación, fallo de red y doble envío; (e) restauración desde respaldo. Los hallazgos actuales son del commit `c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1` y los archivos versionados; no prueban el estado vivo de la cuenta Google.
