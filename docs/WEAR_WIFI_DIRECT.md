# Matriz Wear OS: consulta a través del teléfono

La app del reloj usa el puente Wear OS existente en Matriz para pedir una consulta al teléfono emparejado. El servicio del teléfono lee los datos reales de Room y la caché local de Semana 6; el reloj guarda la última respuesta para poder consultarla sin conexión.

## Requisitos

- Matriz instalada y actualizada en el teléfono con el servicio `MatrizWearBridgeService`.
- Reloj emparejado con ese teléfono mediante la aplicación de Wear OS.
- Bluetooth y conexión del vínculo habilitados.

El reloj no solicita un token de Apps Script. La consulta se realiza mediante el puente Wear OS del teléfono.

## Uso

1. Abre Matriz en el teléfono y confirma que sus datos estén sincronizados mediante el flujo habitual.
2. Abre Matriz Wear en el reloj.
3. Pulsa **Actualizar** para solicitar una lectura al teléfono.
4. Elige **Matriz**, **Solicitud**, **Filtro Fecha**, **Control** o **Semana 6**. Las listas incluyen búsqueda y permiten abrir un registro para consultar sus campos.
5. Usa el gesto de retroceso del sistema del reloj para volver del detalle a la lista y de la lista al inicio. No hay botones de retroceso ocupando espacio.

## Datos y límites

El teléfono consulta sus tablas Room y la caché local existente de Semana 6. **Filtro Fecha** se deriva de los registros de Matriz del día actual; **Control** reúne los valores que prepara el puente del teléfono. La cantidad de registros por sección y el tamaño total de la respuesta están limitados por el servicio para respetar el transporte Wear OS.

El reloj conserva la última respuesta recibida. Si el teléfono no está conectado o no responde, los datos guardados anteriormente siguen disponibles y la consulta nueva muestra un aviso.

El reloj es de consulta: no modifica registros de Matriz.
