# Resultado verificable: prueba de migración Room 37→38 en CI (28–29 de octubre de 2026)

Rama: `claude/room-ci-emulador` (base: `chatgpt/room-test-infrastructure` @ `d3ce1d7`). No se tocó `main`, ni el PR #122, ni la versión de Room, ni los esquemas históricos, ni la prueba `PaseSyncMigrationRoomTest`.

## Hechos comprobados (con evidencia en GitHub)
1. En un runner `ubuntu-24.04` (4 vCPU, 16 GB), `/dev/kvm` es `crw-rw---- root:kvm` y el usuario `runner` NO pertenece al grupo `kvm` (Issue #127). Sin la regla udev de la acción `android-emulator-runner`, el emulador no puede usar aceleración por hardware. `build.yml` no tenía ese paso.
2. `build.yml` ejecutaba el emulador dentro del job que compila y publica el APK (también en `main`). Un fallo del emulador saltaba los pasos siguientes (guardar esquemas, publicar release).
3. La línea `set -eu` dentro de `script:` de `android-emulator-runner` no se propaga: la acción ejecuta cada línea en un shell aparte.
4. `app/schemas/` no está versionado: `38.json` se genera al compilar (`exportSchema = true`); el job nuevo debe compilar antes de instalar los snapshots (el primer intento falló por eso, Issue #127).
5. Con KVM habilitado, API 30 x86_64, 3 GB de RAM y la prueba aislada en su propio workflow: **PASS** en dos ejecuciones independientes — run 40813392932 (commit `106e60b`, Issue #128) y run 40814048456 (commit `b8838ae`, Issue #129). Caso ejecutado: `PaseSyncMigrationRoomTest.migration37To38PreservesPaseAndValidatesEntireSchema`, tests=1, failures=0, errors=0.
6. Control negativo (rama desechable, NO fusionar): con `newId TEXT NOT NULL` en la migración (distinto de `38.json`), la misma prueba **FALLA** (run 40814048887, commit `88e806e`, Issue #130; tests=1, failures=1). La prueba detecta divergencias de esquema.

## Hipótesis no confirmadas
- Que `ubuntu-latest` ya apuntara a Ubuntu 26 en los runs fallidos originales (hay un aviso de migración desde el 19-oct-2026; los logs originales no se pudieron descargar desde este entorno). La causa demostrada del emulador sin aceleración es el hecho 1.
- Estabilidad a largo plazo: hay 2 ejecuciones en verde, no un historial.

## Qué NO queda demostrado
- La prueba cubre la migración 37→38 de `pase_sync_operation_table` y la conservación de una fila de `pase_cartera_table`; no cubre otras migraciones ni un dispositivo físico.
- El mensaje exacto del fallo del control negativo no se capturó en el resumen (solo failures=1).

## Cambios
- `.github/workflows/room-migration-test.yml` (nuevo): `ubuntu-24.04` fijado, KVM, compilación para generar `38.json`, snapshot 37 auténtico, emulador, diagnóstico automático en un Issue.
- `ci-scripts/run-room-test.sh` y `ci-scripts/publicar-diagnostico.sh` (nuevos).
- `.github/workflows/build.yml`: se quitan los 3 pasos del emulador/snapshot; el build y la publicación del APK ya no dependen del emulador.
