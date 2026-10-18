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
