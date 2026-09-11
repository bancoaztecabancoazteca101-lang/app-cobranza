# Plan: vista previa del ticket de sucursales cercanas

Rama: `claude/ticket-vista-previa` (NO mezclar a main sin que Diego confirme que lo probó en su teléfono).

## Contexto
`PaymentChannelsDialog` (en `PaymentChannels.kt`) ya busca sucursales/lugares de pago
cercanos al domicilio del cliente (Overpass API sobre OpenStreetMap) y permite imprimir un
ticket térmico por Bluetooth con `imprimirTicketCanalesPago` / `ThermalPrinterManager`.

Problema reportado por Diego: nunca ha probado la función porque no hay forma de ver cómo
queda el ticket sin tener la impresora conectada -- no hay vista previa.

## Hecho en esta rama
- `buildTicketPreviewText(customerName, channels)`: genera el mismo contenido que
  `ThermalPrinterManager.sendTicket()` pero como texto plano (sin comandos ESC/POS, sin QR
  real -- se muestra como placeholder `[código QR: ...]`).
- Botón "Ver vista previa del ticket" dentro del diálogo (debajo de la lista de sucursales
  encontradas), que muestra el texto en fuente monoespaciada dentro de un bloque con scroll.
- No requiere permisos de Bluetooth ni impresora conectada -- se puede ver la vista previa
  aunque no haya ninguna impresora emparejada.

## Pendiente / por probar
- [ ] Compilar y confirmar build verde en CI.
- [ ] Diego probar en su teléfono: que la vista previa se vea bien, que el texto no se corte,
      que el botón aparezca solo cuando ya hay resultados (`result.channels` no vacío).
- [ ] Si el ancho de 32 caracteres (`TICKET_WIDTH`) no coincide visualmente con lo que imprime
      la impresora real, ajustar ambos lados igual (preview y `sendTicket`) -- están
      intencionalmente duplicados por ahora.
- [ ] Decidir si vale la pena mostrar la vista previa automáticamente (sin botón) la primera
      vez que hay resultados, en vez de requerir un tap.

## IMPORTANTE si ChatGPT continúa este trabajo
- No hacer commit directo a `main`. Trabajar sobre esta misma rama `claude/ticket-vista-previa`
  o una rama `chatgpt/ticket-vista-previa-<algo>` si prefieres separarlo.
- Si tocas `sendTicket()` (el formato real que se imprime), refleja el mismo cambio en
  `buildTicketPreviewText()` para que la vista previa no mienta.
- Avisar a Diego cuando esté listo para que lo pruebe en campo antes de integrar a main.
