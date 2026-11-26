// Lee el modelo o la serie desde la foto de una etiqueta: códigos de barras (ZXing) y texto impreso (Tesseract).
// Las mismas reglas que la app de Android.
import { cargarImagen } from './base.js';

const ETIQUETA_SERIE = /(?:^|[^A-Z0-9])(S\s?\/\s?N|SN|SERIAL(?:\s*(?:NO\.?|NUMBER|N[°º]))?|SER\.?\s*NO\.?|N[°º]?\s*DE\s*SERIE|NO\.?\s*DE\s*SERIE|SERIE)(?![A-Z0-9])\s*[:#.\-]?\s*(.*)$/i;
const ETIQUETA_MODELO = /(?:^|[^A-Z0-9])(MODEL(?:O)?(?:\s*(?:NO\.?|NUMBER|N[°º]|NAME))?|M\s?\/\s?N|MTM|MT|MOD\.?|P\s?\/\s?N|PRODUCT(?:\s*(?:NO\.?|NUMBER|NAME))?|PRODUCTO|TYPE|TIPO)(?![A-Z0-9])\s*[:#.\-]?\s*(.*)$/i;
const TOKEN = /[A-Za-z0-9][A-Za-z0-9\-/.]{3,}/;

export function buscarConEtiqueta(lineas, esSerie) {
  const re = esSerie ? ETIQUETA_SERIE : ETIQUETA_MODELO;
  const res = [];
  lineas.forEach((linea, i) => {
    const m = re.exec(linea);
    if (!m) return;
    let valor = m[2].trim().replace(/^[:#.\- ]+/, '');
    if (!valor && i + 1 < lineas.length) valor = lineas[i + 1].trim();
    if (!valor) return;
    const limpio = esSerie ? (TOKEN.exec(valor)?.[0] || '') : valor.slice(0, 60);
    if (limpio.length >= 2 && !res.includes(limpio)) res.push(limpio);
  });
  return res;
}

export const pareceCodigo = (s) =>
  s.length >= 5 && s.length <= 30 && !s.includes(' ') && /\d/.test(s) && /^[A-Za-z0-9\-/.]+$/.test(s);

// ---------------- Códigos de barras ----------------

function lienzo(img, ladoMax) {
  const f = Math.min(1, ladoMax / Math.max(img.width, img.height));
  const c = document.createElement('canvas');
  c.width = Math.round(img.width * f);
  c.height = Math.round(img.height * f);
  c.getContext('2d', { willReadFrequently: true }).drawImage(img, 0, 0, c.width, c.height);
  return c;
}

function girar(c) {
  const g = document.createElement('canvas');
  g.width = c.height;
  g.height = c.width;
  const x = g.getContext('2d', { willReadFrequently: true });
  x.translate(g.width / 2, g.height / 2);
  x.rotate(Math.PI / 2);
  x.drawImage(c, -c.width / 2, -c.height / 2);
  return g;
}

function leerCodigos(canvas) {
  const Z = window.ZXing;
  if (!Z) return [];
  const pistas = new Map([[Z.DecodeHintType.TRY_HARDER, true]]);
  const lector = new Z.MultiFormatReader();
  lector.setHints(pistas);
  const encontrados = [];
  for (const c of [canvas, girar(canvas)]) {
    try {
      const bmp = new Z.BinaryBitmap(new Z.HybridBinarizer(new Z.HTMLCanvasElementLuminanceSource(c)));
      try {
        const varios = new Z.GenericMultipleBarcodeReader(lector).decodeMultiple(bmp, pistas);
        varios.forEach((r) => encontrados.push(r.getText().trim()));
      } catch (_) {
        try { encontrados.push(lector.decode(bmp, pistas).getText().trim()); } catch (_) { /* nada */ }
      }
    } catch (_) { /* nada */ }
    if (encontrados.length) break;
  }
  return [...new Set(encontrados.filter(Boolean))];
}

// ---------------- Texto (Tesseract) ----------------

let trabajador = null;
async function lectorTexto() {
  if (!trabajador) {
    const base = new URL('vendor/tesseract/', document.baseURI).href;
    trabajador = window.Tesseract.createWorker('eng', 1, {
      workerPath: base + 'worker.min.js',
      corePath: base + 'core/',
      langPath: base + 'lang',
      gzip: true,
      workerBlobURL: false,
    }).catch((e) => { trabajador = null; throw e; });
  }
  return trabajador;
}

async function leerTexto(canvas) {
  try {
    const w = await lectorTexto();
    const r = await w.recognize(canvas);
    return (r.data.text || '').split('\n').map((l) => l.trim()).filter((l) => l.length >= 2);
  } catch (e) {
    console.warn('Lectura de texto no disponible', e);
    return [];
  }
}

/** Lee una imagen. Devuelve { mejor, opciones } igual que en Android. */
export async function leerEtiqueta(file, esSerie) {
  const img = await cargarImagen(file);
  const grande = lienzo(img, 1600);
  const codigos = leerCodigos(grande);
  const lineas = await leerTexto(lienzo(img, 2000));
  const sugeridos = buscarConEtiqueta(lineas, esSerie);
  const todos = [...new Set([...sugeridos, ...codigos, ...lineas.filter(pareceCodigo), ...lineas])].slice(0, 30);
  return { mejor: sugeridos[0] || (codigos.length === 1 ? codigos[0] : null), opciones: todos };
}
