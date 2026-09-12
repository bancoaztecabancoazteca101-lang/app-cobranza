/**
 * Normaliza a MAYÚSCULAS los nombres ya capturados en la hoja "Matriz " (columna A),
 * de una sola vez. La app ya guarda en mayúsculas de aquí en adelante (registros nuevos
 * y ediciones) -- este script es solo para limpiar lo que ya estaba en la hoja con
 * mayúsculas/minúsculas mezcladas.
 *
 * INSTALACIÓN (una sola vez, manual):
 *   1. Abrir el Spreadsheet -> Extensiones -> Apps Script.
 *   2. Pegar este archivo completo como un archivo nuevo.
 *   3. Correr manualmente la función `normalizarNombresMatriz` (botón "Ejecutar").
 *   4. Revisar el log (Ver -> Registros) para ver cuántas filas se actualizaron.
 *
 * No toca ninguna otra columna, ni agrega/quita filas -- solo reescribe la columna A
 * (Nombre) de cada fila con datos, dejando igual las filas que ya estaban en mayúsculas.
 */

var SPREADSHEET_ID_MATRIZ = '1iMFndEHeEOs95egkOkhhc-2yfhwfFSY3YNNuwR_NsMA';
var NOMBRE_HOJA_MATRIZ = 'Matriz ';
var COLUMNA_NOMBRE = 1; // Columna A

function normalizarNombresMatriz() {
  var ss = SpreadsheetApp.openById(SPREADSHEET_ID_MATRIZ);
  var hoja = ss.getSheetByName(NOMBRE_HOJA_MATRIZ);
  if (!hoja) {
    Logger.log('No se encontró la hoja "' + NOMBRE_HOJA_MATRIZ + '"');
    return;
  }

  var ultimaFila = hoja.getLastRow();
  if (ultimaFila < 2) {
    Logger.log('La hoja no tiene filas de datos.');
    return;
  }

  var rango = hoja.getRange(2, COLUMNA_NOMBRE, ultimaFila - 1, 1);
  var valores = rango.getValues();
  var actualizados = 0;

  for (var i = 0; i < valores.length; i++) {
    var original = valores[i][0];
    if (typeof original !== 'string' || original.trim() === '') continue;
    var normalizado = original.trim().toUpperCase();
    if (normalizado !== original) {
      valores[i][0] = normalizado;
      actualizados++;
    }
  }

  rango.setValues(valores);
  Logger.log('Nombres normalizados a mayúsculas: ' + actualizados + ' de ' + valores.length + ' filas revisadas.');
}
