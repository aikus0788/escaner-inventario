// Infomaxis web · datos comunes, guardado en el teléfono y utilidades de pantalla.

export const VERSION = '__VERSION__';

export const CLIENTES = ['Paris', 'Easy', 'Jumbo', 'Santa Isabel', 'Spid', 'Lider', 'Acuenta', 'Mayorista', 'Falabella', 'Tottus', 'Sodimac'];
export const OTROS = 'Otros';
export const REGIONES = [
  'Arica y Parinacota', 'Tarapacá', 'Antofagasta', 'Atacama', 'Coquimbo', 'Valparaíso',
  'Metropolitana de Santiago', "Lib. Gral. Bernardo O'Higgins", 'Maule', 'Ñuble', 'Biobío',
  'La Araucanía', 'Los Ríos', 'Los Lagos', 'Aysén', 'Magallanes y la Antártica Chilena',
];

export const POS = 'POS';
export const SSCO = 'SSCO';
export const RETIRO = 'Retiro';
export const INSTALACION = 'Instalación';
export const PRINCIPAL = 'POS/SSCO';
export const COMPONENTES = [PRINCIPAL, 'Pantalla', 'Segunda pantalla', 'Impresora', 'Gaveta', 'Teclado', 'Scanner de mano', 'Scanner de mesón'];
export const POR_TIPO = {
  [POS]: COMPONENTES,
  [SSCO]: [PRINCIPAL, 'Pantalla', 'Impresora', 'Scanner de mesón', 'Scanner de mano'],
};
export const FOTOS_POS = [
  ['foto_serie', 'Serie del POS'],
  ['foto_equipo', 'Cómo quedó el equipo'],
  ['foto_cableado', 'Cableado del POS'],
  ['foto_boleta', 'Boleta POS certificado'],
  ['foto_adicional', 'Adicional'],
];
export const etiqueta = (nombre, tipo) => (nombre === PRINCIPAL ? (tipo || PRINCIPAL) : nombre);

// ---------------- Guardado (IndexedDB) ----------------

let dbPromesa = null;
function abrirDb() {
  if (!dbPromesa) {
    dbPromesa = new Promise((ok, mal) => {
      const r = indexedDB.open('infomaxis', 1);
      r.onupgradeneeded = () => {
        r.result.createObjectStore('kv');
        r.result.createObjectStore('fotos');
      };
      r.onsuccess = () => ok(r.result);
      r.onerror = () => mal(r.error);
    });
  }
  return dbPromesa;
}
async function tx(store, modo, fn) {
  const db = await abrirDb();
  return new Promise((ok, mal) => {
    const t = db.transaction(store, modo);
    const s = t.objectStore(store);
    const r = fn(s);
    t.oncomplete = () => ok(r && 'result' in r ? r.result : undefined);
    t.onerror = () => mal(t.error);
    t.onabort = () => mal(t.error);
  });
}
export const db = {
  get: (store, k) => tx(store, 'readonly', (s) => s.get(k)),
  set: (store, k, v) => tx(store, 'readwrite', (s) => s.put(v, k)),
  del: (store, k) => tx(store, 'readwrite', (s) => s.delete(k)),
  keys: (store) => tx(store, 'readonly', (s) => s.getAllKeys()),
};

/** Pide al navegador que no borre los datos guardados. */
export function pedirGuardadoPersistente() {
  try { navigator.storage?.persist?.(); } catch (_) { /* sin soporte */ }
}

// ---------------- Fotos ----------------

/** Achica y endereza una foto (máx. 1280 px, JPEG) para guardarla. */
export async function comprimir(file, lado = 1280, calidad = 0.72) {
  const bmp = await cargarImagen(file);
  const f = Math.min(1, lado / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * f);
  c.height = Math.round(bmp.height * f);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  return new Promise((ok) => c.toBlob((b) => ok(b), 'image/jpeg', calidad));
}

/** Carga una imagen respetando la orientación de la cámara. */
export async function cargarImagen(file) {
  if (window.createImageBitmap) {
    try { return await createImageBitmap(file, { imageOrientation: 'from-image' }); } catch (_) { /* respaldo abajo */ }
  }
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return img;
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}

let idFoto = 0;
/** Guarda una foto. Las imágenes de la OT usan el prefijo «ot» para que el inventario no las borre. */
export async function guardarFoto(blob, prefijo = 'f') {
  const id = `${prefijo}${Date.now()}_${++idFoto}_${Math.random().toString(36).slice(2, 8)}`;
  await db.set('fotos', id, blob);
  return id;
}
export const leerFoto = (id) => db.get('fotos', id);

/** Borra las fotos del inventario que ya no usa ninguna caja (no toca las imágenes de la OT). */
export async function limpiarFotos(enUso) {
  const usadas = new Set(enUso);
  for (const k of await db.keys('fotos')) if (!String(k).startsWith('ot') && !usadas.has(k)) await db.del('fotos', k);
}

// ---------------- Utilidades ----------------

export const $ = (sel, raiz = document) => raiz.querySelector(sel);
export const $$ = (sel, raiz = document) => [...raiz.querySelectorAll(sel)];

/** Crea un elemento: el('div', {class:'x', onclick:fn}, hijos…). */
export function el(tag, attrs = {}, ...hijos) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else if (k === 'html') e.innerHTML = v;
    else if (k in e && typeof v !== 'string') e[k] = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const h of hijos.flat()) if (h !== null && h !== undefined && h !== false) e.append(h.nodeType ? h : String(h));
  return e;
}

export function hoy() {
  const d = new Date();
  return `${String(d.getDate()).padStart(2, '0')}-${String(d.getMonth() + 1).padStart(2, '0')}-${d.getFullYear()}`;
}
/** dd-mm-aaaa ↔ aaaa-mm-dd (para el selector de fecha). */
export const aIso = (f) => { const m = /^(\d{2})-(\d{2})-(\d{4})$/.exec(f || ''); return m ? `${m[3]}-${m[2]}-${m[1]}` : ''; };
export const deIso = (f) => { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(f || ''); return m ? `${m[3]}-${m[2]}-${m[1]}` : ''; };

/** Minúsculas, sin tildes ni signos: para buscar. */
export function simple(s) {
  return (s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9 ]/g, ' ').replace(/ +/g, ' ').trim();
}

let toastTimer;
export function aviso(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3200);
}

/** Ventana con opciones; devuelve el índice elegido o null. */
export function elegir(titulo, opciones, { mensaje = '', cancelar = 'Cancelar', obligatorio = false } = {}) {
  return new Promise((ok) => {
    const fondo = el('div', { class: 'modal-fondo' });
    const cerrar = (v) => { fondo.remove(); ok(v); };
    const caja = el('div', { class: 'modal', role: 'dialog', 'aria-label': titulo },
      el('h3', {}, titulo),
      mensaje ? el('p', { class: 'modal-msg' }, mensaje) : null,
      el('div', { class: 'modal-opciones' }, opciones.map((o, i) => el('button', { class: 'opcion', type: 'button', onclick: () => cerrar(i) }, o))),
      cancelar ? el('button', { class: 'boton-texto', type: 'button', onclick: () => cerrar(null) }, cancelar) : null);
    if (!obligatorio) fondo.addEventListener('click', (e) => { if (e.target === fondo) cerrar(null); });
    fondo.append(caja);
    document.body.append(fondo);
  });
}

/** Confirmación: devuelve true/false. */
export async function confirmar(titulo, mensaje, si = 'Aceptar', no = 'Cancelar') {
  return (await elegir(titulo, [si], { mensaje, cancelar: no })) === 0;
}

/** Ventana «Preparando…» mientras corre una tarea larga. */
export async function conEspera(texto, tarea) {
  const fondo = el('div', { class: 'modal-fondo' }, el('div', { class: 'modal' }, el('div', { class: 'girando' }), el('p', { class: 'modal-msg' }, texto)));
  document.body.append(fondo);
  try { return await tarea(); } finally { fondo.remove(); }
}

/** Pide una imagen: cámara o galería. Devuelve un File o null. */
export async function pedirImagen() {
  const i = await elegir('Agregar imagen', ['📷  Tomar foto', '🖼  Elegir imagen del teléfono']);
  if (i === null) return null;
  return new Promise((ok) => {
    const input = el('input', { type: 'file', accept: 'image/*' });
    if (i === 0) input.setAttribute('capture', 'environment');
    input.addEventListener('change', () => ok(input.files?.[0] || null));
    input.click();
  });
}

/** Pide una o varias imágenes: una foto con la cámara o varias de la galería. */
export async function pedirImagenes() {
  const i = await elegir('Agregar imágenes', ['📷  Tomar foto', '🖼  Elegir imágenes del teléfono']);
  if (i === null) return [];
  return new Promise((ok) => {
    const input = el('input', { type: 'file', accept: 'image/*' });
    if (i === 0) input.setAttribute('capture', 'environment');
    else input.multiple = true;
    input.addEventListener('change', () => ok([...(input.files || [])]));
    input.click();
  });
}

/** Comparte un archivo (WhatsApp, correo…) o, si el teléfono no puede, lo descarga. */
export async function compartirArchivo(bytes, nombre, tipo, titulo) {
  const file = new File([bytes], nombre, { type: tipo });
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: titulo });
      return;
    } catch (e) {
      if (e?.name === 'AbortError') return;
    }
  }
  descargarArchivo(bytes, nombre, tipo);
}

export function descargarArchivo(bytes, nombre, tipo) {
  const url = URL.createObjectURL(new Blob([bytes], { type: tipo }));
  const a = el('a', { href: url, download: nombre });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

/** Campo con lista de sugerencias que filtra al escribir (sin importar tildes). */
export function campoConLista(input, opciones, alElegir) {
  const lista = el('div', { class: 'sugerencias', hidden: true });
  input.after(lista);
  input.setAttribute('autocomplete', 'off');
  const pintar = () => {
    const q = simple(input.value);
    const empiezan = opciones.filter((o) => !q || simple(o).split(' ').some((w) => w.startsWith(q)) || simple(o).startsWith(q));
    const contienen = q ? opciones.filter((o) => simple(o).includes(q) && !empiezan.includes(o)) : [];
    const res = [...empiezan, ...contienen];
    lista.replaceChildren(...res.map((o) => el('button', {
      type: 'button',
      class: 'sugerencia',
      onmousedown: (e) => e.preventDefault(),
      onclick: () => { input.value = o; lista.hidden = true; alElegir?.(o); input.dispatchEvent(new Event('change')); },
    }, o)));
    lista.hidden = res.length === 0;
  };
  input.addEventListener('focus', pintar);
  input.addEventListener('input', pintar);
  input.addEventListener('blur', () => {
    setTimeout(() => { lista.hidden = true; }, 150);
    // Si lo escrito calza con una sola opción, se completa.
    const q = simple(input.value);
    if (!q) return;
    const exacta = opciones.find((o) => simple(o) === q);
    const una = opciones.filter((o) => simple(o).includes(q));
    const v = exacta || (una.length === 1 ? una[0] : null);
    if (v && v !== input.value) { input.value = v; input.dispatchEvent(new Event('change')); }
  });
}

/** Barra azul de arriba, con flecha para volver al inicio. */
export function cabecera(titulo, { volver = true, derecha = null, debajo = null } = {}) {
  return el('header', { class: 'cabecera' },
    volver ? el('button', { class: 'volver', type: 'button', 'aria-label': 'Volver al inicio', onclick: () => { location.hash = '#inicio'; } }, '‹') : null,
    el('div', { class: 'titulos' }, el('div', { class: 'marca' }, 'INFOMAXIS'), el('h1', {}, titulo), debajo),
    derecha);
}

