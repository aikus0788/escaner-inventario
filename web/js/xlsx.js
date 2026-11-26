// Excel (.xlsx) del inventario, con el mismo formato que la app de Android:
// pestañas «Retiro» e «Instalación», una fila por caja y las 5 fotos dentro de sus celdas.
import { COMPONENTES, FOTOS_POS, POS, SSCO, RETIRO, INSTALACION, etiqueta } from './base.js';

const ENC = new TextEncoder();
const EMU = 9525;
const FOTO_ANCHO = 200;
const FOTO_ALTO = 150;
const MARGEN = 5;

export const ENCABEZADO = (() => {
  const e = ['N°', 'Fecha', 'Trabajo', 'Cliente', 'N° Tienda', 'Dirección', 'Región', 'Tipo', 'Ubicación POS', 'N° POS'];
  for (const c of COMPONENTES) e.push(`${c} - Modelo`, `${c} - Serie`);
  e.push('Notas', 'Estado POS');
  for (const [, n] of FOTOS_POS) e.push(`Foto: ${n}`);
  return e;
})();
const ANCHOS = [6, 12, 12, 16, 11, 34, 26, 8, 14, 9, ...COMPONENTES.flatMap(() => [20, 20]), 30, 34, ...FOTOS_POS.map(() => 30)];
export const PRIMERA_COLUMNA_FOTO = ENCABEZADO.length - FOTOS_POS.length;

export function comoFila(c, n) {
  const f = [String(n), c.fecha, c.trabajo, c.cliente, c.tienda, c.direccion, c.region, c.tipo, c.ubicacion, c.numeroPos];
  for (const comp of COMPONENTES) {
    const p = c.piezas?.[comp] || {};
    f.push(p.modelo || '', p.serie || '');
  }
  f.push(c.notas, c.estado, ...FOTOS_POS.map(() => ''));
  return f.map((x) => x ?? '');
}

export function textoCaja(c) {
  return [
    c.tipo + (c.numeroPos ? ` N° ${c.numeroPos}` : ''),
    c.ubicacion,
    [c.cliente, c.tienda].filter(Boolean).join(' '),
  ].filter((x) => x && x.trim()).join(' · ');
}

// ---------------- Escribir ----------------

const esc = (s) => String(s).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function columna(i) {
  let n = i + 1, s = '';
  while (n > 0) { const r = (n - 1) % 26; s = String.fromCharCode(65 + r) + s; n = Math.floor((n - 1) / 26); }
  return s;
}

/** Ancho y alto de un JPEG sin decodificarlo. */
export function medidasJpeg(b) {
  if (b[0] !== 0xff || b[1] !== 0xd8) return null;
  let i = 2;
  while (i < b.length) {
    while (b[i] !== 0xff && i < b.length) i++;
    while (b[i] === 0xff) i++;
    const m = b[i++];
    if (m === 0xd8 || (m >= 0xd0 && m <= 0xd7) || m === 0x01) continue;
    const largo = (b[i] << 8) | b[i + 1];
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
      return [(b[i + 5] << 8) | b[i + 6], (b[i + 3] << 8) | b[i + 4]];
    }
    i += largo;
  }
  return null;
}

function tamano(bytes) {
  const [w, h] = medidasJpeg(bytes) || [4, 3];
  const f = Math.min(FOTO_ANCHO / w, FOTO_ALTO / h);
  return [Math.max(1, Math.floor(w * f)), Math.max(1, Math.floor(h * f))];
}

function fila(n, valores, estilo, alto) {
  let s = alto ? `<row r="${n}" ht="${alto.toFixed(1)}" customHeight="1">` : `<row r="${n}">`;
  valores.forEach((v, i) => {
    if (v === '' && estilo !== 1) return;
    s += `<c r="${columna(i)}${n}" t="inlineStr" s="${estilo}"><is><t xml:space="preserve">${esc(v)}</t></is></c>`;
  });
  return s + '</row>';
}

const ESTILOS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF0960A"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`;

/**
 * hojas: [{ nombre, filas: [[…]], fotos: [{ fila, columna, bytes, descripcion }] }]
 * Devuelve los bytes del .xlsx.
 */
export function escribirXlsx(hojas) {
  const z = {};
  const poner = (ruta, texto) => { z[ruta] = ENC.encode(texto); };
  let tipos = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="jpg" ContentType="image/jpeg"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>`;
  let lista = '';
  let rels = '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>';
  let numImg = 0;

  hojas.forEach((h, i) => {
    const n = i + 1;
    const fotos = h.fotos || [];
    tipos += `<Override PartName="/xl/worksheets/sheet${n}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`;
    lista += `<sheet name="${esc(h.nombre.replace(/[\\/?*[\]:]/g, ' ').slice(0, 31))}" sheetId="${n}" r:id="rId${n + 1}"/>`;
    rels += `<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${n}.xml"/>`;

    const conFoto = new Set(fotos.map((f) => f.fila));
    let s = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">';
    s += `<sheetViews><sheetView${i === 0 ? ' tabSelected="1"' : ''} workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>`;
    s += '<cols>' + ANCHOS.map((w, j) => `<col min="${j + 1}" max="${j + 1}" width="${w}" customWidth="1"/>`).join('') + '</cols><sheetData>';
    s += fila(1, ENCABEZADO, 1);
    h.filas.forEach((f, j) => { s += conFoto.has(j) ? fila(j + 2, f, 3, (FOTO_ALTO + 2 * MARGEN) * 0.75) : fila(j + 2, f, 2); });
    s += `</sheetData><autoFilter ref="A1:${columna(ENCABEZADO.length - 1)}${h.filas.length + 1}"/>`;
    if (fotos.length) s += '<drawing r:id="rId1"/>';
    s += '</worksheet>';
    poner(`xl/worksheets/sheet${n}.xml`, s);

    if (fotos.length) {
      tipos += `<Override PartName="/xl/drawings/drawing${n}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>`;
      poner(`xl/worksheets/_rels/sheet${n}.xml.rels`, `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing${n}.xml"/></Relationships>`);
      let d = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">';
      let dr = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">';
      fotos.forEach((f, j) => {
        numImg++;
        const [w, hh] = tamano(f.bytes);
        const cx = w * EMU, cy = hh * EMU, m = MARGEN * EMU;
        d += `<xdr:oneCellAnchor><xdr:from><xdr:col>${f.columna}</xdr:col><xdr:colOff>${m}</xdr:colOff><xdr:row>${f.fila + 1}</xdr:row><xdr:rowOff>${m}</xdr:rowOff></xdr:from><xdr:ext cx="${cx}" cy="${cy}"/>`
          + `<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${j + 2}" name="Foto ${j + 1}" descr="${esc(f.descripcion || '')}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>`
          + `<xdr:blipFill><a:blip r:embed="rId${j + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>`
          + `<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>`;
        dr += `<Relationship Id="rId${j + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${numImg}.jpg"/>`;
        z[`xl/media/image${numImg}.jpg`] = [f.bytes, { level: 0 }];
      });
      poner(`xl/drawings/drawing${n}.xml`, d + '</xdr:wsDr>');
      poner(`xl/drawings/_rels/drawing${n}.xml.rels`, dr + '</Relationships>');
    }
  });

  poner('[Content_Types].xml', tipos + '</Types>');
  poner('_rels/.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>');
  poner('xl/workbook.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${lista}</sheets></workbook>`);
  poner('xl/_rels/workbook.xml.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${rels}</Relationships>`);
  poner('xl/styles.xml', ESTILOS);
  return window.fflate.zipSync(z, { level: 6 });
}

/** Pestañas Retiro e Instalación (y «Sin trabajo» si hay cajas antiguas). fotoBytes(id) → Uint8Array|null */
export async function hojasInventario(cajas, fotoBytes) {
  const grupos = [[RETIRO, cajas.filter((c) => c.trabajo === RETIRO)], [INSTALACION, cajas.filter((c) => c.trabajo === INSTALACION)]];
  const otras = cajas.filter((c) => c.trabajo !== RETIRO && c.trabajo !== INSTALACION);
  if (otras.length) grupos.push(['Sin trabajo', otras]);
  const hojas = [];
  for (const [nombre, lista] of grupos) {
    const fotos = [];
    for (const [i, c] of lista.entries()) {
      for (const [j, [k, titulo]] of FOTOS_POS.entries()) {
        const id = c.fotos?.[k];
        const bytes = id ? await fotoBytes(id) : null;
        if (bytes?.length) fotos.push({ fila: i, columna: PRIMERA_COLUMNA_FOTO + j, bytes, descripcion: `${titulo} · ${textoCaja(c)}` });
      }
    }
    hojas.push({ nombre, filas: lista.map((c, i) => comoFila(c, i + 1)), fotos });
  }
  return hojas;
}

// ---------------- Leer ----------------

const DEC = new TextDecoder();
const local = (n) => (n.includes(':') ? n.split(':').pop() : n);
const hijos = (nodo, nombre) => [...nodo.getElementsByTagName('*')].filter((e) => local(e.nodeName) === nombre);

function normalizar(s) {
  return (s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[°º]/g, '').replace(/[^a-z0-9]/g, '');
}

function resolver(base, destino) {
  if (destino.startsWith('/')) return destino.slice(1);
  const partes = base.split('/').filter(Boolean);
  for (const p of destino.split('/')) {
    if (p === '..') partes.pop();
    else if (p && p !== '.') partes.push(p);
  }
  return partes.join('/');
}
const carpeta = (r) => r.split('/').slice(0, -1).join('/');

function relaciones(archivos, ruta) {
  const b = archivos[ruta];
  if (!b) return {};
  const doc = new DOMParser().parseFromString(DEC.decode(b), 'application/xml');
  const m = {};
  for (const r of hijos(doc, 'Relationship')) m[r.getAttribute('Id')] = { tipo: r.getAttribute('Type') || '', destino: r.getAttribute('Target') || '' };
  return m;
}

function indiceColumna(ref) {
  let n = 0;
  for (const ch of ref) { if (!/[A-Za-z]/.test(ch)) break; n = n * 26 + (ch.toUpperCase().charCodeAt(0) - 64); }
  return n - 1;
}

function leerFilas(xml, compartidos) {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  const filas = new Map();
  for (const r of hijos(doc, 'row')) {
    const n = parseInt(r.getAttribute('r'), 10);
    const celdas = [];
    for (const c of hijos(r, 'c')) {
      const col = indiceColumna(c.getAttribute('r') || '');
      const t = c.getAttribute('t');
      let v;
      if (t === 'inlineStr') v = hijos(c, 't').map((x) => x.textContent).join('');
      else {
        const crudo = hijos(c, 'v')[0]?.textContent ?? '';
        if (t === 's') v = compartidos[parseInt(crudo, 10)] ?? '';
        else if (t === 'str') v = crudo;
        else if (t === 'b') v = crudo.trim() === '1' ? 'VERDADERO' : 'FALSO';
        else { const d = parseFloat(crudo); v = Number.isFinite(d) && Number.isInteger(d) && Math.abs(d) < 1e15 ? String(d) : crudo; }
      }
      if (v !== '') celdas[col] = v;
    }
    filas.set(n, Array.from(celdas, (x) => x ?? ''));
  }
  return filas;
}

function imagenesDeHoja(archivos, hoja) {
  const relsHoja = relaciones(archivos, `${carpeta(hoja)}/_rels/${hoja.split('/').pop()}.rels`);
  const dib = Object.values(relsHoja).find((r) => r.tipo.endsWith('/drawing'));
  if (!dib) return new Map();
  const rutaDib = resolver(carpeta(hoja), dib.destino);
  if (!archivos[rutaDib]) return new Map();
  const relsDib = relaciones(archivos, `${carpeta(rutaDib)}/_rels/${rutaDib.split('/').pop()}.rels`);
  const doc = new DOMParser().parseFromString(DEC.decode(archivos[rutaDib]), 'application/xml');
  const res = new Map();
  for (const ancla of [...hijos(doc, 'oneCellAnchor'), ...hijos(doc, 'twoCellAnchor')]) {
    const desde = hijos(ancla, 'from')[0];
    const blip = hijos(ancla, 'blip')[0];
    if (!desde || !blip) continue;
    const fila = parseInt(hijos(desde, 'row')[0]?.textContent, 10);
    const col = parseInt(hijos(desde, 'col')[0]?.textContent, 10);
    let rid = null;
    for (const a of blip.attributes) if (a.name.endsWith('embed')) rid = a.value;
    const destino = rid && relsDib[rid]?.destino;
    const bytes = destino && archivos[resolver(carpeta(rutaDib), destino)];
    if (bytes) res.set(`${fila},${col}`, bytes);
  }
  return res;
}

/** Lee un .xlsx del inventario. Devuelve [{ caja, fotos: { clave: Uint8Array } }]. */
export function leerXlsx(bytes) {
  let archivos;
  try { archivos = window.fflate.unzipSync(new Uint8Array(bytes)); } catch (_) {
    throw new Error('El archivo no es un Excel .xlsx. Si es .xls antiguo, ábrelo en Excel y guárdalo como .xlsx.');
  }
  const compartidos = archivos['xl/sharedStrings.xml']
    ? hijos(new DOMParser().parseFromString(DEC.decode(archivos['xl/sharedStrings.xml']), 'application/xml'), 'si')
      .map((si) => hijos(si, 't').filter((t) => !t.closest?.('rPh')).map((t) => t.textContent).join(''))
    : [];
  const relsLibro = relaciones(archivos, 'xl/_rels/workbook.xml.rels');
  const libro = new DOMParser().parseFromString(DEC.decode(archivos['xl/workbook.xml'] || ENC.encode('<x/>')), 'application/xml');
  const hojas = hijos(libro, 'sheet').map((s) => {
    let rid = '';
    for (const a of s.attributes) if (a.name.endsWith(':id')) rid = a.value;
    return { nombre: s.getAttribute('name') || '', ruta: relsLibro[rid] ? resolver('xl', relsLibro[rid].destino) : '' };
  }).filter((h) => archivos[h.ruta]);

  const resultado = [];
  let conColumnas = false;
  const base = Date.now();
  for (const h of hojas) {
    const filas = leerFilas(DEC.decode(archivos[h.ruta]), compartidos);
    const numeros = [...filas.keys()].sort((a, b) => a - b);
    const iEnc = numeros.find((n) => filas.get(n).map(normalizar).some((x) => x === 'modelo' || x.endsWith('serie') || x.startsWith('tipo')));
    if (iEnc === undefined) continue;
    conColumnas = true;
    const enc = filas.get(iEnc).map(normalizar);
    const col = (...ns) => { let i = enc.findIndex((x) => ns.includes(x)); if (i < 0) i = enc.findIndex((x) => ns.some((n) => x.startsWith(n))); return i; };
    const C = {
      fecha: col('fecha'), trabajo: col('trabajo'), cliente: col('cliente'), tienda: col('ntienda', 'notienda', 'tienda'),
      direccion: col('direccion'), region: col('region'), tipo: col('tipo'), ubic: col('ubicacionpos', 'ubicacion'),
      npos: col('npos', 'numeropos', 'nropos'), notas: col('notas', 'observaciones'), estado: col('estadopos', 'estadodelpos', 'estado'),
    };
    const piezasCols = COMPONENTES.map((c) => [c, enc.indexOf(normalizar(c) + 'modelo'), enc.indexOf(normalizar(c) + 'serie')]);
    const fotosCols = FOTOS_POS.map(([k, n]) => [k, enc.indexOf(normalizar(`Foto: ${n}`))]).filter(([, i]) => i >= 0);
    const imagenes = imagenesDeHoja(archivos, h.ruta);
    const nh = normalizar(h.nombre);
    const trabajoHoja = nh.startsWith('ret') ? RETIRO : nh.startsWith('ins') ? INSTALACION : '';

    for (const n of numeros.filter((x) => x > iEnc)) {
      const f = filas.get(n);
      const v = (i) => (i >= 0 ? (f[i] || '').trim() : '');
      const piezas = {};
      for (const [c, im, is] of piezasCols) if (v(im) || v(is)) piezas[c] = { modelo: v(im), serie: v(is) };
      const tr = v(C.trabajo);
      const caja = {
        id: base + resultado.length, fecha: v(C.fecha),
        trabajo: /^ret/i.test(tr) ? RETIRO : /^ins/i.test(tr) ? INSTALACION : (tr || trabajoHoja),
        cliente: v(C.cliente), tienda: v(C.tienda), direccion: v(C.direccion), region: v(C.region),
        tipo: /^ssco/i.test(v(C.tipo)) ? SSCO : POS, ubicacion: v(C.ubic), numeroPos: v(C.npos),
        piezas, notas: v(C.notas), estado: v(C.estado), fotos: {},
      };
      const fotos = {};
      for (const [k, ci] of fotosCols) { const b = imagenes.get(`${n - 1},${ci}`); if (b) fotos[k] = b; }
      const tiene = Object.keys(piezas).length || Object.keys(fotos).length || [caja.cliente, caja.tienda, caja.ubicacion, caja.numeroPos, caja.notas, caja.estado].some(Boolean);
      if (tiene) resultado.push({ caja, fotos });
    }
  }
  if (!conColumnas) throw new Error('No encontré las columnas (Tipo, Modelo, Serie…) en el Excel.');
  return resultado;
}

export { etiqueta };
