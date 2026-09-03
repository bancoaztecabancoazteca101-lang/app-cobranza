/**
 * Ruta IA -- limpieza automática de madrugada.
 *
 * La hoja "Ruta IA" es 100% de trabajo diario: la app la reescribe por completo cada vez que
 * Diego procesa un lote nuevo de fotos, pero si un día no abre la app para tomar fotos nuevas,
 * la ruta del día anterior se quedaría ahí. Este trigger la vacía de madrugada sin importar si
 * la app se usó ese día, para que SIEMPRE empiece limpia.
 *
 * INSTALACIÓN (una sola vez, manual):
 *   1. Abrir el Spreadsheet -> Extensiones -> Apps Script.
 *   2. Pegar esta función en el proyecto (o en un archivo nuevo "RutaIA.gs").
 *   3. Editor de Apps Script -> reloj (Activadores) -> Añadir activador:
 *        Función: limpiarRutaIA
 *        Origen del evento: Basado en tiempo
 *        Tipo: Temporizador diario
 *        Franja horaria: 4:00 - 5:00 (antes de que Diego empiece su día)
 *
 * SIEMPRE usar SpreadsheetApp.openById(...), nunca getActiveSpreadsheet() -- este script corre
 * por trigger de tiempo, sin una hoja "activa" asociada.
 */
function limpiarRutaIA() {
  var SPREADSHEET_ID = '1iMFndEHeEOs95egkOkhhc-2yfhwfFSY3YNNuwR_NsMA';
  var NOMBRE_HOJA = 'Ruta IA';

  var ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  var hoja = ss.getSheetByName(NOMBRE_HOJA);
  if (!hoja) return; // La app crea la hoja sola en el primer uso; si aún no existe, no hay nada que limpiar.

  var ultimaFila = hoja.getLastRow();
  if (ultimaFila > 1) {
    // Conserva la fila 1 (encabezados: Id, Nombre, CU, Direccion, ColoniaCP, DiasAtraso,
    // PagoRequerido, Lat, Lng, Orden, EsNuevo, CuMatrizMatch, Fecha, Estado), borra el resto.
    hoja.getRange(2, 1, ultimaFila - 1, hoja.getLastColumn()).clearContent();
  }
}
