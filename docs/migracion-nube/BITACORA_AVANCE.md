# Bitácora de avance — Migración a nube de App Matriz

## Estado actual
- [x] Confirmar objetivo: todos los datos operativos compartidos entre teléfonos.
- [x] Registrar hallazgos iniciales de sincronización parcial y riesgos de pérdida/conflicto.
- [x] Crear plan maestro versionado en el repositorio.
- [ ] Completar inventario de todas las entidades, preferencias, archivos y consumidores externos.
- [ ] Comparar proveedores cloud y seleccionar uno con criterios documentados.
- [ ] Diseñar esquema, permisos, versiones, tombstones, outbox y reglas de conflicto.
- [ ] Implementar piloto en una rama independiente.
- [ ] Ejecutar pruebas offline, multi-dispositivo, multimedia, pagos y automatización.
- [ ] Crear APK/artifact verificable cuando exista una implementación compilable.

## Cambios registrados
### 2026-10-18 — Documentación inicial
- Rama: `chatgpt/documentar-migracion-nube`
- Base de la rama: commit de referencia auditado `c766bea1ef231e02aa0d091d6cbb1f94b7dd8aa1`.
- Archivo añadido: `docs/migracion-nube/PLAN_MAESTRO_SINCRONIZACION.md`.
- Commit de documentación: `4bcb6636ee96cdde92f6a8970f382cc44e36f4ee`.
- Tipo de cambio: solo documentación; no modifica lógica de la app.
- `main`: no modificado.
- Build/APK: no ejecutado/no generado.
- Base cloud: todavía no seleccionada ni configurada.

## Reglas para cada sesión futura
1. Leer este plan y esta bitácora antes de continuar.
2. Verificar la rama y el commit actual antes de editar.
3. No tocar `main`; hacer implementación en rama separada.
4. Inspeccionar el código real antes de diseñar cambios; no inventar código genérico.
5. Registrar archivos y commits modificados, resultados reales de compilación/pruebas y fallas pendientes.
6. No marcar tareas como completadas sin evidencia.
7. Mantener el inventario y el plan actualizados con cualquier decisión nueva del usuario.


### 2026-10-18 — Comparación inicial de nube e inventario ampliado
- Se volvió a leer el plan, la bitácora y el código de referencia de entidades, base Room, repositorio y SyncWorker.kt.
- Se añadió comparación inicial entre Firestore, Realtime Database y Supabase con documentación oficial de los proveedores.
- Recomendación actual: prototipo aislado de Firestore por el soporte offline Android; comparar con Supabase si el modelo relacional y reportes requieren SQL. **Proveedor de producción aún no seleccionado.**
- Se amplió el inventario por grupos y se registraron bloqueos antes de producción.
- No se modificó código de la app ni configuración cloud. No se ejecutó build ni se generó APK.
- Pendiente: inventario fila por fila de todas las entidades/SharedPreferences/scripts y validación de consumidores externos, triggers y datos de producción.
- main: no modificado.


### 2026-10-18 — Inventario Room por tabla
- Se inspeccionó AppDatabase.kt: Room está en DB_VERSION 37 y declara 22 entidades.
- Se enumeraron tablas, claves y campos principales en el plan maestro; se identificaron además preferencias operativas/técnicas que deben separarse y auditarse.
- Se verificaron riesgos adicionales para automatización: logs de contacto y guardas anti-repetición locales pueden permitir duplicación entre teléfonos si se trasladan sin una clave global/idempotente.
- El inventario sigue abierto: falta seguir cada entidad hasta sus pantallas, ViewModels, DAOs, escrituras y lectores externos; también falta validar datos de producción y scripts/triggers instalados.
- Solo documentación modificada; no se tocó código, Firebase/Cloud ni main. Sin build ni APK.


### 2026-10-19 — Trazabilidad de datos hasta el worker
- Se rastrearon DAOs y ViewModels principales y se compararon con SyncWorker y SheetsRepository.
- Confirmado: el worker envía actualmente Matriz, Filtro Fecha, Pase, Ruta IA y Solicitud. `refreshAll()` también refresca Filtrar y Control, pero esto no prueba que los cambios locales de esos conjuntos se envíen.
- Se documentó que tickets, visitas, cartucho diario, Semana 6, contactos extra y ajustes de automatización carecen de envío global verificado en SyncWorker.
- Riesgos de prioridad: Ruta IA reemplaza lista completa; filtro fecha puede limpiar dirty sin acuse fiable; borrados locales y guards de SMS/llamada no protegen entre dispositivos.
- La rama documental se actualizó; código y configuración de producción sin cambios. No se ejecutó build ni se generó APK.


### 2026-10-19 — Preferencias y automatización
- Se revisaron LlamadaAutomatica.kt, SmsStatusWorker.kt, FiltrarScreen.kt, NumeroContactadoGuard.kt, NotificacionesHelper.kt, Sem6.kt y CartuchoAviso.kt.
- Se añadieron al plan los almacenes de preferencias detectados y la separación entre estado técnico local (SIM, alarmas, cachés, token del dispositivo) y estado operativo compartido.
- Los bloqueos locales de llamadas/SMS no coordinan dos teléfonos; la nube necesita reservar cada acción de forma atómica y registrar su resultado.
- La búsqueda de Contac_Subir y CONTACTOS no halló un consumidor claro en Kotlin dentro del repositorio. Siguen sin verificarse los scripts, hojas y triggers externos.
- Solo documentación en la rama chatgpt/documentar-migracion-nube; no se modificó código ni main. No se ejecutó build ni se generó APK.
- Pendiente: rastrear pase_import_gcr y preferencias restantes; verificar consumidores externos y datos reales antes del piloto.


### 2026-10-19 — Tercera pasada: preferencias y scripts
- La búsqueda global de preferencias añadió al plan el inventario de BackupManager, guardas SMS/llamada, cache/tombstones Sem6, retornos, cartucho, automatización, contactos, GCR, imágenes y registro FCM.
- Se inspeccionaron los scripts versionados RutaIA.gs, SincronizarCanalesPago.gs y firebase-apps-script/Code.gs.
- Riesgos confirmados: RutaIA.gs puede vaciar la hoja por trigger diario; SincronizarCanalesPago.gs limpia y reconstruye el catálogo; el backend FCM guarda sentEvents temporalmente en ScriptProperties y solo atiende notificaciones, no sincronización operativa completa.
- El código incluye una API key en el script FCM; documentado como credencial que debe rotarse y guardarse de forma segura antes de ampliar su uso.
- No se encontró Contac_Subir ni un lector explícito de CONTACTOS en la búsqueda de código. Sigue pendiente verificar los otros proyectos Apps Script, fórmulas, AppSheet y triggers instalados en la cuenta real.
- Solo documentación en chatgpt/documentar-migracion-nube; sin cambios de app, sin cambios en main, sin build ni APK. La comparación de rama confirma que los archivos añadidos son únicamente los dos documentos de migración.


### 2026-10-19 — Escritores concurrentes y confirmación de nube
- Se confirmó en RutaIAViewModel.kt que la importación borra y recrea toda la ruta local, y luego reemplaza la hoja completa; también existe reemplazo en el worker y limpieza externa. Se documentó que una ruta compartida necesita control de versión/propietario y no solo último escritor.
- Se revisó SheetsRepository.updateSheetCell(): no devuelve un resultado de negocio; los callers deben esperar todas las escrituras y manejar excepciones antes de marcar limpio.
- Se documentó el alcance del script de normalización (columna A) y que el backend FCM reconstruye la pestaña de dispositivos durante limpieza de duplicados.
- Se señaló que sentEvents de siete días solo deduplica notificaciones y no debe usarse como ledger de llamadas/SMS.
- Se mantiene la regla de no tocar main ni datos vivos. Solo documentos en la rama de auditoría; sin compilación ni APK.
