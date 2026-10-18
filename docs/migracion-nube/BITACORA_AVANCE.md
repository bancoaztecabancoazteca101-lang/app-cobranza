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
