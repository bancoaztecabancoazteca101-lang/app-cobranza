# Matriz en la nube — Fase 1 (Firestore como única fuente de verdad)

Estado: código escrito, **sin compilar ni probar en teléfonos**. Apagado por defecto (`MatrizNube.habilitado = false`): con la bandera apagada la app se comporta como antes (Sheets).

## Qué hace
- Colección `matriz`, un documento por cliente (ID del cliente = ID del documento). Incluye los campos que eran 100 % locales (oferta, ref3/ref4, día de pago, domicilio laboral, días de atraso/apertura, monto cobrado).
- Room = caché + cambios pendientes (`isDirty`). La nube solo se aplica a registros sin cambios pendientes (UPDATE atómico `WHERE isDirty = 0`).
- Subir = transacción con fusión de 3 vías por campo (`MatrizNubeMerge`): campos distintos se combinan; mismo campo distinto → gana el local y se registra el conflicto.
- Borrar = lápida (`eliminado = true`), nunca borrado físico; renombrar = documento nuevo + lápida del viejo en UNA transacción.
- Bajar = incremental por `updatedAt` (ahorra lecturas de la cuota Spark) + escucha en tiempo real con la app abierta + `SyncWorker`.
- Room 38 → 39: tabla nueva `matriz_nube_meta_table` (base de la fusión). No toca datos existentes.

## Pasos manuales (consola Firebase) antes de activar
1. Registrar en la app Android las huellas SHA-1 y SHA-256 del certificado (copiar y pegar), y **volver a descargar `google-services.json`**: debe traer `oauth_client` con un cliente tipo 3 (web). Sin eso la app no pide token de Firebase y no arranca la nube.
2. Authentication → habilitar el proveedor Google.
3. Firestore → pegar `firestore.rules` (cambiando `TU_CORREO@gmail.com` por las cuentas autorizadas) y **Publicar**.
4. Con datos reales, primero probar con una copia / dos teléfonos de prueba.

## Activación
Diagnóstico → tarjeta "Matriz en la nube" → "Activar Matriz en la nube" en el teléfono con los datos completos; después en el resto (la nube manda, lo local solo rellena vacíos).

## Límites conocidos (no resueltos en esta fase)
- Borrar y renombrar requieren conexión (igual que hoy con Sheets).
- Pase, Solicitud, Filtro Fecha, Ruta IA y Semana 6 siguen con Sheets/local; el hallazgo de Pase (copias con UUID aleatorio por teléfono) sigue abierto.
- Exportación a un Sheets nuevo: pendiente (Fase 2).
- Cuota Spark: 50 000 lecturas / 20 000 escrituras al día; la activación inicial usa 1 lectura por documento y 1 escritura por registro nuevo.
- Verificar el reloj: la marca incremental usa hora del servidor con margen de 10 s.
