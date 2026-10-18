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
