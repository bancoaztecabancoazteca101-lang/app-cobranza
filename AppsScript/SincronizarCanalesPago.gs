/**
 * Catálogo de sucursales/lugares de pago cercanos (Elektra, Banco Azteca, OXXO, etc.)
 *
 * PROBLEMA que resuelve: la app intentaba consultar Overpass (OpenStreetMap) EN VIVO desde el
 * teléfono de Diego en campo, y truena por timeout siempre -- probamos hasta 3 espejos distintos
 * (Alemania/Austria/Rusia) y los 3 fallan igual, lo que apunta a que la red del teléfono en
 * campo no deja salir a esos dominios (no es que los servidores estén caídos).
 *
 * SOLUCIÓN: este script corre del lado de Google (Apps Script), no tiene ese problema de red, y
 * deja el resultado guardado en la hoja "Catálogo Canales Pago". La app solo LEE esa hoja por
 * Sheets API (igual que Matriz/Pase de Cartera/etc, que ya sabemos que sí funciona en ese
 * teléfono) y guarda una copia local en Room -- la búsqueda de "lugares cercanos" queda
 * instantánea y sin red en el momento de imprimir el ticket.
 *
 * ZONA cubierta: bounding box alrededor del polígono "Cafetales Nuevo" que Diego compartió
 * (San Andrés Tetepilco / El Retoño / Popular Escuadrón 201 / Modelo / Prado Churubusco /
 * Granjas Esmeralda / Valle del Sur / Paseos de Taxqueña / Educación / San Francisco Culhuacán /
 * Culhuacán CTM V), con margen extra. Si algún día trabaja en otra zona, ajustar BBOX abajo.
 *
 * INSTALACIÓN (una sola vez, manual):
 *   1. Abrir el Spreadsheet -> Extensiones -> Apps Script.
 *   2. Pegar este archivo completo en el proyecto (archivo nuevo "SincronizarCanalesPago.gs").
 *   3. En el editor, correr manualmente la función `sincronizarCanalesPago` UNA vez (botón
 *      "Ejecutar" con esa función seleccionada) -- va a pedir autorización la primera vez.
 *      Esto crea la hoja "Catálogo Canales Pago" y la llena.
 *   4. Después, correr `crearTriggerSemanal` UNA sola vez (mismo botón "Ejecutar", cambiando la
 *      función seleccionada) para que se repita solo cada semana sin tener que acordarse.
 *
 * SIEMPRE usar SpreadsheetApp.openById(...), nunca getActiveSpreadsheet() -- estos triggers de
 * tiempo corren sin una hoja "activa" asociada.
 */

var SPREADSHEET_ID_CANALES = '1iMFndEHeEOs95egkOkhhc-2yfhwfFSY3YNNuwR_NsMA';
var NOMBRE_HOJA_CANALES = 'Catálogo Canales Pago';

// Bounding box: [sur, oeste, norte, este] -- cubre la zona "Cafetales Nuevo" con margen.
var BBOX_CANALES = [19.315, -99.165, 19.385, -99.100];

// Mismas cadenas que reconoce classifyChannel() en PaymentChannels.kt -- si se agrega o quita
// una marca ahí, hay que reflejarlo aquí también para que el catálogo la incluya/excluya.
var MARCAS_REGEX = 'Elektra|Banco Azteca|Italika|Neto|OXXO|7-Eleven|Seven Eleven|Soriana|Chedraui';

// Semilla manual que Diego ya había investigado a mano (fuentes: mxfirmas.com, listado de
// sucursales OXXO de Afirme) -- se conserva siempre, independientemente de lo que traiga
// Overpass, porque son datos ya verificados contra fuentes oficiales/públicas.
var SEMILLA_MANUAL = [
  ['CAF-0001', 'Banco Azteca - Calzada Taxqueña 2050', 'Banco Azteca', 'Calzada Taxqueña 2050, Coyoacán, CDMX', 19.338109, -99.11911],
  ['CAF-0002', 'OXXO La Viga MEX', 'OXXO', 'Atanacio G. Saravia 1102, Héroes de Churubusco, Iztapalapa, CDMX', 19.36196707, -99.12203605],
  ['CAF-0003', 'OXXO Cerro de la Estrella 277', 'OXXO', 'Cerro de la Estrella 277, CDMX', 19.34473, -99.133247],
  ['CAF-0004', '7-Eleven Avenida Cerro de la Venta 12', '7-Eleven', 'Av. Cerro de la Venta 12, Coyoacán, CDMX', 19.336879, -99.13211],
  ['CAF-0005', '7 Eleven 801 Ciclistas', '7-Eleven', 'Calle Ciclistas 1768, Coyoacán, CDMX', 19.35148, -99.144653]
];

function sincronizarCanalesPago() {
  var ss = SpreadsheetApp.openById(SPREADSHEET_ID_CANALES);
  var hoja = ss.getSheetByName(NOMBRE_HOJA_CANALES);
  if (!hoja) {
    hoja = ss.insertSheet(NOMBRE_HOJA_CANALES);
  }

  var filas = SEMILLA_MANUAL.map(function (r) { return r.concat(['manual']); });

  var overpassRows = consultarOverpassCanales_();
  var vistos = {};
  filas.forEach(function (r) { vistos[llaveDedup_(r[1], r[4], r[5])] = true; });
  overpassRows.forEach(function (r) {
    var k = llaveDedup_(r[1], r[4], r[5]);
    if (!vistos[k]) { vistos[k] = true; filas.push(r); }
  });

  hoja.clearContents();
  hoja.getRange(1, 1, 1, 6).setValues([['Id', 'Nombre', 'Empresa', 'Direccion', 'Lat', 'Lng']]);
  if (filas.length > 0) {
    var soloDatos = filas.map(function (r) { return r.slice(0, 6); });
    hoja.getRange(2, 1, soloDatos.length, 6).setValues(soloDatos);
  }
  Logger.log('Catálogo de canales de pago sincronizado: ' + filas.length + ' lugares (' + SEMILLA_MANUAL.length + ' manuales + ' + (filas.length - SEMILLA_MANUAL.length) + ' de Overpass).');
}

function llaveDedup_(nombre, lat, lng) {
  return (nombre || '').toLowerCase().trim() + '|' + Number(lat).toFixed(4) + '|' + Number(lng).toFixed(4);
}

function consultarOverpassCanales_() {
  var sur = BBOX_CANALES[0], oeste = BBOX_CANALES[1], norte = BBOX_CANALES[2], este = BBOX_CANALES[3];
  var query = '[out:json][timeout:50];' +
    '(' +
    '  nwr[' + '"name"~"' + MARCAS_REGEX + '",i](' + sur + ',' + oeste + ',' + norte + ',' + este + ');' +
    '  nwr[' + '"brand"~"' + MARCAS_REGEX + '",i](' + sur + ',' + oeste + ',' + norte + ',' + este + ');' +
    ');' +
    'out center tags;';

  var respuesta;
  try {
    respuesta = UrlFetchApp.fetch('https://overpass-api.de/api/interpreter', {
      method: 'post',
      payload: { data: query },
      muteHttpExceptions: true
    });
  } catch (e) {
    Logger.log('Overpass falló, se usa solo la semilla manual: ' + e.message);
    return [];
  }
  if (respuesta.getResponseCode() !== 200) {
    Logger.log('Overpass respondió ' + respuesta.getResponseCode() + ', se usa solo la semilla manual.');
    return [];
  }

  var data = JSON.parse(respuesta.getContentText());
  var filas = [];
  var idx = 1;
  (data.elements || []).forEach(function (el) {
    var tags = el.tags || {};
    var nombre = (tags.name || tags.brand || '').trim();
    if (!nombre) return;
    var lat = el.lat != null ? el.lat : (el.center ? el.center.lat : null);
    var lng = el.lon != null ? el.lon : (el.center ? el.center.lon : null);
    if (lat == null || lng == null) return;
    var direccion = [tags['addr:street'], tags['addr:housenumber'], tags['addr:suburb'], tags['addr:postcode']]
      .filter(function (v) { return v; }).join(' ');
    filas.push(['OSM-' + (idx++), nombre, tags.brand || tags.operator || '', direccion, lat, lng, 'overpass']);
  });
  return filas;
}

/** Correr UNA sola vez manualmente para instalar el trigger semanal (los datos de sucursales no
 * cambian tan seguido como para necesitar algo más frecuente que esto). */
function crearTriggerSemanal() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'sincronizarCanalesPago') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('sincronizarCanalesPago')
    .timeBased()
    .onWeekDay(ScriptApp.WeekDay.MONDAY)
    .atHour(3)
    .create();
}
