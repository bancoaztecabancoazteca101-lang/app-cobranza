# Plan de migración: de Sheets/Apps Script a 100% local

Rama de trabajo: `chatgpt/migracion-local-offline` (creada desde `main` el 13/09/2026,
commit base `be672ba`). No tocar `main` — la app estable de uso diario sigue
sincronizando con Sheets normalmente mientras esta rama avanza en paralelo.

## Objetivo final
Que la app funcione instalándose y usándose sola, sin que el usuario sepa qué es
un Spreadsheet ID, una Web App o un Apps Script. Matriz (Room/SQLite) pasa a ser
la única fuente de verdad. Todo lo demás (Sheets, Drive, Web App de
notificaciones) se vuelve opcional o desaparece de la versión que se vende.

## Diagnóstico real (leído del código, no supuesto)

`SheetsRepository.kt` funciona con patrón "dirty tracking": Room ya es local
para casi todo, y Sheets se usa como backup/sync bidireccional. Los puntos de
enganche reales son pocos y concentrados:

- **`SyncWorker.kt`** — empuja a Sheets lo que cambió local (dirty items) de:
  Matriz, Pase, Solicitud, Filtro Fecha, Ruta IA.
- **`MainActivity.kt` → `refreshAll()`** (botón "Datos: Lista" del menú) — jala
  de Sheets hacia Room: Matriz, Pase (vía `copiarPaseDesdeMatriz()`, deriva
  explícitamente de Matriz), Solicitud, Filtro Fecha, Filtrar, Control.
- **Llamadas directas fuera de esos dos puntos:**
  - `MatrizViewModel` → borra/renombra filas directo en Sheets
  - `SolicitudViewModel` → borra filas directo en Sheets
  - `RutaIAViewModel` → reemplaza toda su hoja en Sheets
  - `Sem6ViewModel` → **no tiene tabla Room propia**, vive 100% en hojas
    semanales de Sheets (leer, listar hojas, actualizar notas)
  - `DiagnosticoViewModel` → `verificarConsistenciaMatriz()` compara Room
    contra Sheets — pierde sentido sin Sheets, hay que retirarlo, no migrarlo
- **`DriveHelper.kt`** (transversal, no es Sheets sino Drive) — sube/baja
  fotos y audio, usado por Filtrar, Filtro Fecha, Matriz, Pase, Sem6,
  Solicitud
- **`MultiDeviceNotificationManager.kt`** (eje aparte) — habla con el Web App
  `Code.gs` para registrar dispositivos vía Firebase Cloud Messaging

Pantallas que YA son 100% locales sin tocar nada: Control, Ubi, Bloques de
horario, SMS, Plantillas de SMS, Llamadas.

## Orden de trabajo (de hoja a raíz, como pidió Diego)

Cada fase debe dejar el build en verde y la app estable de `main` sin tocar.

1. **Confirmar que Control/Filtrar/Filtro Fecha no necesitan el pull de
   `refreshAll()`** — quitarlos de esa función uno por uno, probar que la
   pantalla sigue funcionando solo con lo que ya hay en Room.
2. **Solicitud** — quitar `deleteRowById` remoto y `refreshSolicitud()`; el
   borrado y la data quedan solo en Room.
3. **Pase** — cambiar `copiarPaseDesdeMatriz()` para que lea de la tabla Room
   de Matriz en vez de la hoja de Sheets (Matriz sigue siendo local en este
   punto, solo se corta el intermediario de Sheets).
4. **Ruta IA** — decidir con Diego si se queda fuera de la V1 comercial (ya
   depende de una API externa de Gemini de por sí, es un caso aparte) antes
   de invertir tiempo migrándolo.
5. **Sem6** — el más grande: diseñar una tabla Room nueva (no existe hoy) que
   reemplace las hojas semanales, migrar lectura/escritura/notas a local.
6. **Diagnóstico** — retirar `verificarConsistenciaMatriz()` (deja de tener
   sentido), conservar solo lo que siga siendo útil sin Sheets.
7. **Fotos/audio (`DriveHelper`)** — mover de Google Drive a almacenamiento
   local del dispositivo (carpeta interna/externa de la app). Toca varias
   pantallas pero es un cambio mecánico bien definido (subir → guardar local,
   obtener URL → leer archivo local).
8. **Dispositivos / notificaciones multi-dispositivo** — eje aparte de
   Sheets; decidir si se queda fuera de la V1 comercial (no es indispensable
   para que un gestor use la app solo) o se simplifica más adelante.
9. **`SyncWorker` + `MainActivity.refreshAll()`** — una vez que ninguna
   pantalla individual los necesite, se vacían o se dejan como opción
   desactivada por default (útil solo para el uso personal de Diego).
10. **Matriz (la raíz, al final)** — cortar `refreshMatriz()` y el push de
    Matriz. En este punto `Constants.SPREADSHEET_ID` y
    `GoogleSheetsServiceProvider` dejan de ser necesarios para que la app
    funcione — solo quedarían como opción para quien sí quiera respaldo en
    Sheets (ej. el propio Diego).

## Regla para todo el trabajo en esta rama
Cualquier función nueva que se agregue durante esta migración debe pensarse
en local desde el inicio (Room), sin crear nuevas dependencias a cuentas de
Google de Diego.
