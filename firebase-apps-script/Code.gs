const CONFIG = {
  PROJECT_ID: 'app-matriz',
  SPREADSHEET_ID: '1iMFndEHeEOs95egkOkhhc-2yfhwfFSY3YNNuwR_NsMA',
  SHEET_NAME: 'Matriz ',
  DEVICES_SHEET: 'NotificacionesDispositivos',
  API_KEY: 'MatrizFCM'
};

const COL = {
  NOMBRE: 0,
  REQUERIDO: 2,
  NUMTT: 3,
  ESTADO: 7,
  UBICACION: 8,
  FECHA: 11,
  ID: 12,
  HORA: 13
};

function doGet(e) { return json_(handle_(e && e.parameter ? e.parameter : {})); }
function doPost(e) {
  let body = {};
  try { body = JSON.parse(e.postData.contents || '{}'); } catch (_) {}
  return json_(handle_(body));
}

function handle_(p) {
  if (p.apiKey !== CONFIG.API_KEY) return { ok: false, error: 'unauthorized' };
  const action = String(p.action || '').toLowerCase();
  if (action === 'register') return withLock_(() => register_(p));
  if (action === 'list') return list_();
  if (action === 'toggle') return withLock_(() => toggle_(p));
  if (action === 'delete') return withLock_(() => delete_(p));
  if (action === 'cleanup') return withLock_(() => cleanup_());
  if (action === 'test') return test_(p);
  if (action === 'poll') return pollRetornos_();
  return { ok: false, error: 'unknown_action' };
}

// Serializa los escritores (register/toggle/delete/cleanup) para que dos llamadas casi
// simultáneas no lean la hoja antes de que la primera termine de escribir su fila —
// eso era lo que producía filas duplicadas con el mismo deviceId.
function withLock_(fn) {
  const lock = LockService.getScriptLock();
  const acquired = lock.tryLock(10000);
  if (!acquired) return { ok:false, error:'backend_ocupado_reintenta' };
  try { return fn(); } finally { lock.releaseLock(); }
}

// Header de la hoja: deviceId | name | fcmToken | enabled | lastSeen | platform | appVersion | isAdmin
function sheet_() {
  const ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  let sh = ss.getSheetByName(CONFIG.DEVICES_SHEET);
  if (!sh) {
    sh = ss.insertSheet(CONFIG.DEVICES_SHEET);
    sh.appendRow(['deviceId','name','fcmToken','enabled','lastSeen','platform','appVersion','isAdmin']);
    return sh;
  }
  // Migración: hojas creadas antes de que existiera la columna isAdmin.
  if (sh.getLastColumn() < 8) {
    sh.getRange(1,8).setValue('isAdmin');
    const values = sh.getDataRange().getValues();
    for (let i=1;i<values.length;i++) {
      const isAdmin = /kingkong/i.test(String(values[i][1] || ''));
      sh.getRange(i+1,8).setValue(isAdmin);
    }
  }
  return sh;
}

function rows_() {
  const v = sheet_().getDataRange().getValues();
  return v.length > 1 ? v.slice(1) : [];
}

function register_(p) {
  if (!p.deviceId || !p.fcmToken) return { ok:false, error:'deviceId_and_fcmToken_required' };
  const sh = sheet_(), values = sh.getDataRange().getValues(), now = new Date();
  for (let i=1;i<values.length;i++) {
    if (String(values[i][0]) === String(p.deviceId)) {
      const enabled = values[i][3] === '' ? true : values[i][3];
      const wasAdmin = values[i][7] === true || String(values[i][7]).toLowerCase() === 'true';
      // Se recalcula (en vez de solo preservar) en cada registro, así un flag de admin que se
      // haya perdido o no se haya guardado bien en el pasado se autocorrige la próxima vez que
      // ese dispositivo abra la pantalla, sin necesitar limpieza manual.
      const isAdmin = wasAdmin || /kingkong/i.test(String(p.name || ''));
      sh.getRange(i+1,2,1,6).setValues([[
        String(p.name || 'Dispositivo'), String(p.fcmToken), enabled, now, 'android', String(p.appVersion || '')
      ]]);
      sh.getRange(i+1,8).setValue(isAdmin);
      return { ok:true, deviceId:String(p.deviceId), enabled:Boolean(enabled), isAdmin:isAdmin };
    }
  }
  const isAdmin = /kingkong/i.test(String(p.name || ''));
  sh.appendRow([String(p.deviceId),String(p.name || 'Dispositivo'),String(p.fcmToken),true,now,'android',String(p.appVersion || ''),isAdmin]);
  return { ok:true, deviceId:String(p.deviceId), enabled:true, isAdmin:isAdmin };
}

function list_() {
  const seen = {};
  const devices = [];
  rows_().forEach(r => {
    const deviceId = String(r[0]);
    if (!deviceId || seen[deviceId]) return; // red de seguridad extra contra deviceId duplicado
    seen[deviceId] = true;
    devices.push({
      deviceId:deviceId, name:String(r[1]),
      enabled:r[3] === true || String(r[3]).toLowerCase() === 'true',
      lastSeen:r[4] instanceof Date ? r[4].toISOString() : String(r[4] || ''),
      platform:String(r[5] || 'android'), appVersion:String(r[6] || ''),
      isAdmin:r[7] === true || String(r[7]).toLowerCase() === 'true'
    });
  });
  return { ok:true, devices:devices };
}

function toggle_(p) {
  if (!p.deviceId) return { ok:false, error:'deviceId_required' };
  const sh=sheet_(), values=sh.getDataRange().getValues();
  for(let i=1;i<values.length;i++) {
    if(String(values[i][0])===String(p.deviceId)) {
      const enabled=String(p.enabled).toLowerCase()==='true';
      sh.getRange(i+1,4).setValue(enabled);
      return {ok:true,deviceId:String(p.deviceId),enabled};
    }
  }
  return {ok:false,error:'device_not_found'};
}

function delete_(p) {
  if (!p.deviceId) return { ok:false, error:'deviceId_required' };
  const sh=sheet_(), values=sh.getDataRange().getValues();
  for (let i=1;i<values.length;i++) {
    if (String(values[i][0]) === String(p.deviceId)) {
      sh.deleteRow(i+1);
      return { ok:true, deviceId:String(p.deviceId) };
    }
  }
  return { ok:false, error:'device_not_found' };
}

// Fusiona filas duplicadas con el mismo deviceId (dejando la de lastSeen más reciente,
// conservando enabled/isAdmin=true si cualquiera de las copias lo tenía en true).
function cleanup_() {
  const sh = sheet_(), values = sh.getDataRange().getValues();
  const bestByDeviceId = {};
  for (let i = 1; i < values.length; i++) {
    const id = String(values[i][0]);
    if (!id) continue;
    const row = values[i];
    const enabled = row[3] === true || String(row[3]).toLowerCase() === 'true';
    const isAdmin = row[7] === true || String(row[7]).toLowerCase() === 'true';
    const lastSeen = row[4] instanceof Date ? row[4].getTime() : new Date(row[4] || 0).getTime();
    const prev = bestByDeviceId[id];
    if (!prev || lastSeen >= prev.lastSeen) {
      bestByDeviceId[id] = {
        row: [id, row[1], row[2], enabled || (prev ? prev.row[3] : false), row[4], row[5], row[6], isAdmin || (prev ? prev.row[7] : false)],
        lastSeen: lastSeen
      };
    } else {
      prev.row[3] = prev.row[3] || enabled;
      prev.row[7] = prev.row[7] || isAdmin;
    }
  }
  const removed = values.length - 1 - Object.keys(bestByDeviceId).length;
  if (values.length > 1) sh.deleteRows(2, values.length - 1);
  Object.values(bestByDeviceId).forEach(v => sh.appendRow(v.row));
  return { ok:true, removedDuplicates: Math.max(removed, 0), remaining: Object.keys(bestByDeviceId).length };
}

function test_(p) {
  return sendToEnabledDevices_({
    title:String(p.title || 'Matriz App'), body:String(p.body || 'Notificación de prueba'),
    eventId:'TEST-'+Date.now(), rowId:'', nombre:String(p.nombre || 'Prueba'),
    requerido:String(p.requerido || ''), numTT:String(p.numTT || ''),
    colonia:String(p.colonia || ''), calle:String(p.calle || ''), ubicacion:String(p.ubicacion || '')
  }, p.deviceId ? [String(p.deviceId)] : null);
}

function pollRetornos_() {
  const sh=SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID).getSheetByName(CONFIG.SHEET_NAME);
  if(!sh) return {ok:false,error:'matriz_sheet_not_found'};
  const values=sh.getDataRange().getValues();
  const now=new Date();
  const props=PropertiesService.getScriptProperties();
  let sent=JSON.parse(props.getProperty('sentEvents') || '{}');
  let delivered=0;
  const cutoff=now.getTime()-7*24*60*60*1000;
  Object.keys(sent).forEach(k=>{if(Number(sent[k])<cutoff) delete sent[k];});

  for(let r=1;r<values.length;r++) {
    if(String(values[r][COL.ESTADO] || '').trim().toUpperCase() !== 'RETORNO') continue;
    const dt=combineDateTime_(values[r][COL.FECHA],values[r][COL.HORA]);
    if(!dt || Math.abs(dt.getTime()-now.getTime())>90*1000) continue;
    const rowId=String(values[r][COL.ID] || r+1);
    const eventId='RETORNO:'+rowId+':'+Utilities.formatDate(dt,Session.getScriptTimeZone(),'yyyy-MM-dd-HH-mm');
    if(sent[eventId]) continue;

    const ubicacion=String(values[r][COL.UBICACION] || '');
    const direccion=reverseGeocode_(ubicacion);
    const message={
      title:'Retorno',
      body:String(values[r][COL.NOMBRE] || 'Cliente')+' tiene retorno a las '+Utilities.formatDate(dt,Session.getScriptTimeZone(),'HH:mm'),
      eventId:eventId, rowId:rowId,
      nombre:String(values[r][COL.NOMBRE] || 'Cliente'),
      requerido:String(values[r][COL.REQUERIDO] || ''),
      numTT:String(values[r][COL.NUMTT] || ''),
      colonia:direccion.colonia, calle:direccion.calle, ubicacion:ubicacion
    };
    const result=sendToEnabledDevices_(message,null);
    if(result.ok && result.sent>0) { sent[eventId]=Date.now(); delivered+=result.sent; }
  }
  props.setProperty('sentEvents',JSON.stringify(sent));
  return {ok:true,sent:delivered};
}

function combineDateTime_(dateValue,timeValue) {
  if(!dateValue) return null;
  const d=dateValue instanceof Date ? new Date(dateValue) : new Date(String(dateValue));
  if(isNaN(d.getTime())) return null;
  if(timeValue instanceof Date) d.setHours(timeValue.getHours(),timeValue.getMinutes(),0,0);
  else { const m=String(timeValue || '').match(/(\d{1,2})[:.](\d{2})/); if(!m) return null; d.setHours(Number(m[1]),Number(m[2]),0,0); }
  return d;
}

function sendToEnabledDevices_(message,targetIds) {
  const devices=rows_().filter(r => (r[3] === true || String(r[3]).toLowerCase() === 'true') && r[2] && (!targetIds || targetIds.indexOf(String(r[0])) >= 0));
  let sent=0,failed=0;
  devices.forEach(r=>{ try { sendFcm_(String(r[2]),message); sent++; } catch(err) { failed++; Logger.log('FCM error for '+r[0]+': '+err); } });
  return {ok:true,sent,failed};
}

function sendFcm_(token,message) {
  const response=UrlFetchApp.fetch('https://fcm.googleapis.com/v1/projects/'+CONFIG.PROJECT_ID+'/messages:send',{
    method:'post', contentType:'application/json', headers:{Authorization:'Bearer '+ScriptApp.getOAuthToken()},
    payload:JSON.stringify({message:{token:token,data:{
      title:String(message.title || 'Matriz App'), body:String(message.body || ''), eventId:String(message.eventId || ''), rowId:String(message.rowId || ''),
      nombre:String(message.nombre || ''), requerido:String(message.requerido || ''), numTT:String(message.numTT || ''),
      colonia:String(message.colonia || ''), calle:String(message.calle || ''), ubicacion:String(message.ubicacion || '')
    },android:{priority:'HIGH'}}}), muteHttpExceptions:true
  });
  const code=response.getResponseCode();
  if(code<200 || code>=300) throw new Error(code+': '+response.getContentText());
}

function reverseGeocode_(raw) {
  const empty={colonia:'',calle:''};
  if(!raw) return empty;
  const parts=String(raw).split(',').map(s=>s.trim());
  if(parts.length!==2) return empty;
  const lat=Number(parts[0]), lng=Number(parts[1]);
  if(!isFinite(lat) || !isFinite(lng)) return empty;
  try {
    const response=Maps.newGeocoder().setLanguage('es').reverseGeocode(lat,lng);
    if(!response || !response.results || !response.results.length) return empty;
    const components=response.results[0].address_components || [];
    let calle='', colonia='';
    components.forEach(c=>{
      const types=c.types || [];
      if(!calle && types.indexOf('route')>=0) calle=c.long_name || '';
      if(!colonia && (types.indexOf('neighborhood')>=0 || types.indexOf('sublocality')>=0 || types.indexOf('sublocality_level_1')>=0)) colonia=c.long_name || '';
    });
    return {colonia:colonia,calle:calle};
  } catch(err) { Logger.log('Geocoder error: '+err); return empty; }
}

function installMinuteTrigger() {
  ScriptApp.getProjectTriggers().forEach(t=>{ if(t.getHandlerFunction()==='pollRetornos_') ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('pollRetornos_').timeBased().everyMinutes(1).create();
}

function configurarBackend() {
  const sh=sheet_(); installMinuteTrigger();
  Logger.log('Hoja creada: '+sh.getName()); Logger.log('Activador configurado: pollRetornos_ cada minuto');
  return {ok:true,sheetName:sh.getName(),trigger:'pollRetornos_ cada minuto',headers:sh.getRange(1,1,1,8).getValues()[0]};
}

function json_(obj) { return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON); }
