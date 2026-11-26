// Fotos usadas para leer modelo y serie: se envían aparte del Excel, con una franja que dice qué es cada una.
import { COMPONENTES, etiqueta, leerFoto } from './base.js';

/** Solo las fotos de la serie de cada componente, en el orden de la caja. */
export function ordenadas(c) {
  const pos = (k) => { const i = COMPONENTES.indexOf(k.split('|')[0]); return i < 0 ? 99 : i; };
  return Object.entries(c.fotosSeries || {}).filter(([k]) => k.endsWith('|serie')).sort(([a], [b]) => pos(a) - pos(b));
}

export const cuantas = (c) => ordenadas(c).length;

export function titulos(c, clave) {
  const [comp, campo] = clave.split('|');
  const p = c.piezas?.[comp] || {};
  const valor = (campo === 'modelo' ? p.modelo : p.serie) || '—';
  const principal = `${etiqueta(comp, c.tipo)} · ${campo === 'modelo' ? 'Modelo' : 'Serie'}: ${valor}`;
  const detalle = [
    c.trabajo,
    [c.cliente, c.tienda ? `Tienda ${c.tienda}` : ''].filter(Boolean).join(' '),
    c.tipo + (c.numeroPos ? ` N° ${c.numeroPos}` : ''),
    c.ubicacion, c.fecha,
  ].filter(Boolean).join(' · ');
  return [principal, detalle];
}

async function conFranja(blob, principal, detalle) {
  const img = await createImageBitmap(blob);
  const w = img.width;
  const alto = Math.max(90, Math.round(w * 0.13));
  const cv = document.createElement('canvas');
  cv.width = w; cv.height = img.height + alto;
  const g = cv.getContext('2d');
  g.fillStyle = '#08165A'; g.fillRect(0, 0, w, alto);
  g.fillStyle = '#F09300'; g.fillRect(0, alto - alto * 0.06, w, alto * 0.06);
  g.drawImage(img, 0, alto);
  const margen = w * 0.03;
  const escribir = (t, tam, peso, color, base) => {
    let s = tam;
    g.font = `${peso} ${s}px -apple-system, Roboto, Arial, sans-serif`;
    while (g.measureText(t).width > w - 2 * margen && s > 8) { s *= 0.95; g.font = `${peso} ${s}px -apple-system, Roboto, Arial, sans-serif`; }
    g.fillStyle = color; g.fillText(t, margen, base);
  };
  escribir(principal, alto * 0.36, 'bold', '#FFFFFF', alto * 0.46);
  escribir(detalle, alto * 0.22, 'normal', '#C8D2FF', alto * 0.80);
  return new Promise((ok) => cv.toBlob(ok, 'image/jpeg', 0.88));
}

/** Arma los archivos listos para enviar. */
export async function preparar(cajas) {
  const salida = [];
  for (const c of cajas) {
    for (const [clave, id] of ordenadas(c)) {
      const b = await leerFoto(id);
      if (!b) continue;
      const [p, d] = titulos(c, clave);
      const out = await conFranja(b, p, d);
      const n = String(salida.length + 1).padStart(2, '0');
      const base = [c.tipo + (c.numeroPos || ''), etiqueta(clave.split('|')[0], c.tipo), clave.split('|')[1]].join('_').replace(/[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ_-]+/g, '-');
      salida.push(new File([out], `${n}_${base}.jpg`, { type: 'image/jpeg' }));
    }
  }
  return salida;
}
