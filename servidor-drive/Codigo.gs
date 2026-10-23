/**
 * INFOMAXIS · Servidor Drive
 *
 * Puente entre la app Infomaxis y el Drive de la cuenta de la empresa.
 * Solo responde a teléfonos que el administrador aprobó.
 *
 * Instalación (una sola vez, con la cuenta de la empresa):
 *   1. Entra a https://script.google.com → «Nuevo proyecto».
 *   2. Borra lo que aparece y pega TODO este archivo. Guarda.
 *   3. «Implementar» → «Nueva implementación» → tipo «Aplicación web».
 *        Ejecutar como: Yo        Quién tiene acceso: Cualquier usuario
 *   4. Autoriza los permisos que pide Google.
 *   5. Copia la «URL de la aplicación web» (termina en /exec) y envíasela a quien mantiene la app.
 *
 * Si cambias este código después: «Implementar» → «Gestionar implementaciones» → lápiz →
 * Versión: «Nueva versión» → «Implementar». Así la dirección (URL) no cambia.
 */

const MAX_BYTES = 20 * 1024 * 1024;   // archivos más grandes se abren desde el link de Drive
const MAX_ARCHIVOS = 60;
const MAX_CARPETAS = 20;

function doGet(e) {
  const p = (e && e.parameter) || {};
  try {
    switch (p.accion) {
      case 'estado':    return json_(estado_(p.id));
      case 'solicitar': return json_(solicitar_(p));
      case 'buscar':    exigirAprobado_(p.id); return json_(buscar_(p.q));
      case 'carpeta':   exigirAprobado_(p.id); return json_(carpeta_(p.carpeta));
      case 'archivo':   exigirAprobado_(p.id); return json_(archivo_(p.archivo));
      case 'panel':     exigirAdmin_(p.token); return panel_('');
      case 'aprobar':   exigirAdmin_(p.token); return panel_(cambiarEstado_(p.dispositivo, 'aprobado'));
      case 'rechazar':  exigirAdmin_(p.token); return panel_(cambiarEstado_(p.dispositivo, 'rechazado'));
      default:          return json_({ ok: true, servicio: 'Infomaxis Drive' });
    }
  } catch (err) {
    const codigo = String((err && err.message) || err);
    if (p.accion === 'panel' || p.accion === 'aprobar' || p.accion === 'rechazar') {
      return HtmlService.createHtmlOutput('<p style="font-family:sans-serif">Enlace no válido: ' + escapar_(codigo) + '</p>');
    }
    return json_({ ok: false, error: codigo });
  }
}

/* ---------------- Dispositivos y aprobación ---------------- */

function props_() { return PropertiesService.getScriptProperties(); }

function validarId_(id) {
  if (!id || !/^[A-Za-z0-9-]{16,64}$/.test(id)) throw new Error('ID_INVALIDO');
  return id;
}

function leerDisp_(id) {
  const t = props_().getProperty('disp_' + id);
  return t ? JSON.parse(t) : null;
}

function guardarDisp_(id, d) { props_().setProperty('disp_' + id, JSON.stringify(d)); }

function tokenAdmin_() {
  let t = props_().getProperty('ADMIN_TOKEN');
  if (!t) {
    t = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
    props_().setProperty('ADMIN_TOKEN', t);
  }
  return t;
}

function exigirAdmin_(token) {
  if (!token || token !== tokenAdmin_()) throw new Error('NO_AUTORIZADO');
}

function exigirAprobado_(id) {
  validarId_(id);
  const d = leerDisp_(id);
  if (!d || d.estado !== 'aprobado') throw new Error('NO_AUTORIZADO');
  d.ultimoUso = new Date().toISOString();
  guardarDisp_(id, d);
}

function estado_(id) {
  validarId_(id);
  const d = leerDisp_(id);
  return { ok: true, estado: d ? d.estado : 'nuevo', nombre: d ? d.nombre : '' };
}

function solicitar_(p) {
  const id = validarId_(p.id);
  const nombre = String(p.nombre || '').trim().slice(0, 60);
  const modelo = String(p.modelo || '').trim().slice(0, 60);
  if (!nombre) throw new Error('FALTA_NOMBRE');

  const ahora = new Date();
  let d = leerDisp_(id);
  if (d && d.estado === 'aprobado') return { ok: true, estado: 'aprobado', nombre: d.nombre };

  // Evita mandar muchos correos si la persona toca el botón varias veces.
  const reciente = d && d.estado === 'pendiente' && d.solicitado &&
    (ahora - new Date(d.solicitado)) < 10 * 60 * 1000;

  d = { nombre: nombre, modelo: modelo, estado: 'pendiente', solicitado: ahora.toISOString() };
  guardarDisp_(id, d);
  if (!reciente) avisarAdmin_(id, d);
  return { ok: true, estado: 'pendiente', nombre: nombre };
}

function enlaceAdmin_(accion, id) {
  let u = ScriptApp.getService().getUrl() + '?accion=' + accion + '&token=' + tokenAdmin_();
  if (id) u += '&dispositivo=' + encodeURIComponent(id);
  return u;
}

function avisarAdmin_(id, d) {
  const boton = function (texto, url, color) {
    return '<a href="' + url + '" style="display:inline-block;padding:12px 22px;margin:6px 8px 6px 0;' +
      'background:' + color + ';color:#fff;text-decoration:none;border-radius:8px;font-weight:bold">' + texto + '</a>';
  };
  const html =
    '<div style="font-family:Arial,sans-serif;font-size:15px;color:#1B1F2A">' +
    '<p><b>' + escapar_(d.nombre) + '</b> pide acceso al módulo <b>Drive</b> de Infomaxis.</p>' +
    '<p style="color:#5C6170">Teléfono: ' + escapar_(d.modelo || 'sin dato') + '<br>Fecha: ' +
    Utilities.formatDate(new Date(d.solicitado), Session.getScriptTimeZone(), 'dd-MM-yyyy HH:mm') + '</p>' +
    '<p>Si lo apruebas, podrá buscar, ver y descargar archivos de este Drive.</p>' +
    boton('Aprobar', enlaceAdmin_('aprobar', id), '#0832D6') +
    boton('Rechazar', enlaceAdmin_('rechazar', id), '#B3261E') +
    '<p style="margin-top:18px"><a href="' + enlaceAdmin_('panel') + '">Ver todos los teléfonos con acceso</a></p>' +
    '</div>';
  MailApp.sendEmail({
    to: Session.getEffectiveUser().getEmail(),
    subject: 'Infomaxis: ' + d.nombre + ' pide acceso al Drive',
    htmlBody: html,
  });
}

function cambiarEstado_(id, estado) {
  validarId_(id);
  const d = leerDisp_(id);
  if (!d) return 'Ese teléfono ya no está registrado.';
  d.estado = estado;
  d.actualizado = new Date().toISOString();
  guardarDisp_(id, d);
  return estado === 'aprobado'
    ? '✓ ' + d.nombre + ' ya tiene acceso.'
    : d.nombre + ' quedó sin acceso.';
}

function panel_(mensaje) {
  const todos = props_().getProperties();
  const filas = [];
  Object.keys(todos).forEach(function (k) {
    if (k.indexOf('disp_') !== 0) return;
    const id = k.slice(5);
    const d = JSON.parse(todos[k]);
    filas.push({ id: id, d: d });
  });
  const orden = { pendiente: 0, aprobado: 1, rechazado: 2 };
  filas.sort(function (a, b) { return (orden[a.d.estado] - orden[b.d.estado]) || a.d.nombre.localeCompare(b.d.nombre); });

  const color = { pendiente: '#F0960A', aprobado: '#0832D6', rechazado: '#B3261E' };
  const texto = { pendiente: 'Pendiente', aprobado: 'Con acceso', rechazado: 'Sin acceso' };
  let html = '<div style="font-family:Arial,sans-serif;max-width:640px;margin:0 auto;padding:16px;color:#1B1F2A">' +
    '<h2 style="color:#0832D6;margin:0 0 4px">Infomaxis · Accesos al Drive</h2>' +
    '<p style="color:#5C6170;margin:0 0 16px">Teléfonos que pidieron usar el módulo Drive.</p>';
  if (mensaje) html += '<p style="background:#E6EAFB;padding:12px;border-radius:8px">' + escapar_(mensaje) + '</p>';
  if (!filas.length) html += '<p>Todavía nadie ha pedido acceso.</p>';
  filas.forEach(function (f) {
    const d = f.d;
    html += '<div style="border:1px solid #D6D9E2;border-radius:10px;padding:12px;margin-bottom:10px">' +
      '<b>' + escapar_(d.nombre) + '</b> <span style="color:#fff;background:' + color[d.estado] +
      ';padding:2px 8px;border-radius:10px;font-size:12px">' + texto[d.estado] + '</span>' +
      '<div style="color:#5C6170;font-size:13px;margin:4px 0 8px">' + escapar_(d.modelo || '') +
      (d.ultimoUso ? ' · último uso ' + Utilities.formatDate(new Date(d.ultimoUso), Session.getScriptTimeZone(), 'dd-MM-yyyy HH:mm') : '') +
      '</div>';
    if (d.estado !== 'aprobado') html += '<a href="' + enlaceAdmin_('aprobar', f.id) + '" target="_top" style="margin-right:14px;color:#0832D6;font-weight:bold">Aprobar</a>';
    if (d.estado !== 'rechazado') html += '<a href="' + enlaceAdmin_('rechazar', f.id) + '" target="_top" style="color:#B3261E;font-weight:bold">Quitar acceso</a>';
    html += '</div>';
  });
  html += '</div>';
  return HtmlService.createHtmlOutput(html)
    .setTitle('Infomaxis · Accesos')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/* ---------------- Drive ---------------- */

function escaparConsulta_(s) { return String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'"); }

function infoArchivo_(f, conCarpeta) {
  let carpeta = '';
  if (conCarpeta) {
    try { const it = f.getParents(); if (it.hasNext()) carpeta = it.next().getName(); } catch (e) {}
  }
  return {
    id: f.getId(),
    nombre: f.getName(),
    tipo: f.getMimeType(),
    tamano: f.getSize(),
    modificado: f.getLastUpdated().toISOString(),
    url: f.getUrl(),
    carpeta: carpeta,
  };
}

function infoCarpeta_(c) {
  return { id: c.getId(), nombre: c.getName(), tipo: 'carpeta', modificado: c.getLastUpdated().toISOString(), url: c.getUrl() };
}

function buscar_(q) {
  q = String(q || '').trim();
  if (q.length < 2) throw new Error('BUSQUEDA_CORTA');
  const s = escaparConsulta_(q);

  const carpetas = [];
  const itC = DriveApp.searchFolders("title contains '" + s + "' and trashed = false");
  while (itC.hasNext() && carpetas.length < MAX_CARPETAS) carpetas.push(infoCarpeta_(itC.next()));

  const archivos = [];
  const itA = DriveApp.searchFiles("(title contains '" + s + "' or fullText contains '" + s + "') and trashed = false");
  while (itA.hasNext() && archivos.length < MAX_ARCHIVOS) archivos.push(infoArchivo_(itA.next(), true));

  // Primero los que tienen el texto en el nombre, después los más recientes.
  const ql = q.toLowerCase();
  const orden = function (a, b) {
    const an = a.nombre.toLowerCase().indexOf(ql) >= 0 ? 0 : 1;
    const bn = b.nombre.toLowerCase().indexOf(ql) >= 0 ? 0 : 1;
    return (an - bn) || (b.modificado > a.modificado ? 1 : -1);
  };
  carpetas.sort(orden);
  archivos.sort(orden);
  return { ok: true, carpetas: carpetas, archivos: archivos, hayMas: itA.hasNext() };
}

function carpeta_(id) {
  if (!id) throw new Error('FALTA_CARPETA');
  const c = DriveApp.getFolderById(id);
  const carpetas = [];
  const itC = c.getFolders();
  while (itC.hasNext() && carpetas.length < 200) carpetas.push(infoCarpeta_(itC.next()));
  const archivos = [];
  const itA = c.getFiles();
  while (itA.hasNext() && archivos.length < 300) {
    const f = itA.next();
    if (!f.isTrashed()) archivos.push(infoArchivo_(f, false));
  }
  const porNombre = function (a, b) { return a.nombre.localeCompare(b.nombre); };
  carpetas.sort(porNombre);
  archivos.sort(porNombre);
  return { ok: true, nombre: c.getName(), carpetas: carpetas, archivos: archivos };
}

function archivo_(id) {
  if (!id) throw new Error('FALTA_ARCHIVO');
  let f = DriveApp.getFileById(id);
  if (f.getMimeType() === 'application/vnd.google-apps.shortcut') f = DriveApp.getFileById(f.getTargetId());

  const mime = f.getMimeType();
  let blob, nombre = f.getName();
  if (mime.indexOf('application/vnd.google-apps.') === 0) {
    // Documentos, hojas y presentaciones de Google se entregan como PDF para poder verlos en el teléfono.
    try { blob = f.getAs('application/pdf'); } catch (e) {
      return { ok: false, error: 'NO_CONVERTIBLE', url: f.getUrl() };
    }
    if (!/\.pdf$/i.test(nombre)) nombre += '.pdf';
  } else {
    if (f.getSize() > MAX_BYTES) return { ok: false, error: 'MUY_GRANDE', url: f.getUrl(), tamano: f.getSize() };
    blob = f.getBlob();
  }
  const bytes = blob.getBytes();
  if (bytes.length > MAX_BYTES) return { ok: false, error: 'MUY_GRANDE', url: f.getUrl(), tamano: bytes.length };
  return { ok: true, nombre: nombre, tipo: blob.getContentType(), base64: Utilities.base64Encode(bytes) };
}

/* ---------------- Utilidades ---------------- */

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function escapar_(s) {
  return String(s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

/** Ejecútala una vez desde el editor si quieres recibir por correo el enlace al panel de accesos. */
function enviarmeEnlaceDelPanel() {
  MailApp.sendEmail(Session.getEffectiveUser().getEmail(), 'Infomaxis: panel de accesos al Drive',
    'Panel para aprobar o quitar acceso a los teléfonos:\n\n' + enlaceAdmin_('panel'));
}
