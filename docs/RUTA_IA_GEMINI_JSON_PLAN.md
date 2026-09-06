# Ruta IA — Nueva arquitectura Gemini → JSON

Esta rama es de trabajo de ChatGPT.

## Objetivo
Reemplazar la captura/OCR de fotografías dentro de la app Ruta IA por importación de un archivo JSON generado externamente por Gemini.

Flujo objetivo:
Gemini → JSON → Ruta IA → validación → cruce con Matriz → geocodificación → GPS → generación de ruta.

Principio: Gemini extrae; Ruta IA valida y decide.

## Reglas de ruta
- GPS actual se usa únicamente para seleccionar el primer cliente más cercano.
- Después del primer cliente, cada siguiente cliente se selecciona por cercanía al cliente anterior, no nuevamente desde el GPS.
- Días de atraso: prioridad de mayor a menor.
- Requerido/saldo: prioridad económica de mayor a menor.
- Deben existir estrategias separadas para ruta inteligente, mayor atraso, mayor requerido y prioridad de cobranza.
- Los datos inválidos no deben entrar silenciosamente a la ruta.

## Contrato JSON inicial
Cada cliente puede contener:
- nombre
- cu
- direccion
- colonia
- cp
- diasAtraso
- saldo
- requerido

La app debe validar campos, normalizar datos, detectar duplicados y advertir inconsistencias.

## Rama
`chatgpt/ruta-ia-gemini-json`

## Regla de seguridad
No modificar `main` desde este trabajo. La integración a `main` se hará después de probar y revisar los cambios.
