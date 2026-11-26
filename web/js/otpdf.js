// PDF de la «Orden de trabajo» NCR Voyix, con las mismas medidas que la app de Android.
const ANCHO = 595.32;
const ALTO = 841.92;

const LINEAS = [
  [214.32, 70.68, 216.24, 94.8], [214.8, 94.8, 215.76, 261.72], [214.8, 287.76, 215.76, 309.96], [279.24, 287.76, 280.2, 309.96],
  [355.8, 287.76, 356.76, 309.96], [432.36, 287.76, 433.32, 309.96], [214.8, 311.88, 215.76, 527.04], [49.92, 68.76, 51.84, 743.28],
  [214.8, 528.96, 215.76, 644.88], [537.48, 70.68, 539.4, 743.28], [279.24, 311.88, 280.2, 527.04], [355.8, 311.88, 356.76, 527.04],
  [432.36, 311.88, 433.32, 527.04], [51.84, 68.76, 539.4, 70.68], [51.84, 92.88, 539.4, 94.8], [51.84, 117.48, 537.48, 118.44],
  [51.84, 141.6, 537.48, 142.56], [51.84, 165.72, 537.48, 166.68], [51.84, 189.84, 537.48, 190.8], [51.84, 213.96, 537.48, 214.92],
  [51.84, 238.08, 537.48, 239.04], [51.84, 261.72, 539.4, 263.64], [51.84, 285.84, 539.4, 287.76], [51.84, 309.96, 539.4, 311.88],
  [51.84, 334.56, 537.48, 335.52], [51.84, 358.68, 537.48, 359.64], [51.84, 382.8, 537.48, 383.76], [51.84, 406.92, 537.48, 407.88],
  [51.84, 431.04, 537.48, 432.0], [51.84, 455.16, 537.48, 456.12], [51.84, 479.28, 537.48, 480.24], [51.84, 503.4, 537.48, 504.36],
  [51.84, 527.04, 539.4, 528.96], [51.84, 597.12, 537.48, 598.08], [51.84, 621.24, 537.48, 622.2], [51.84, 644.88, 539.4, 646.8],
  [51.84, 741.36, 539.4, 743.28],
];
const FILAS_DATOS = [117.48, 141.6, 165.72, 189.84, 213.96, 238.08, 261.72];
const ETIQUETAS = ['Numero de orden', 'Fecha', 'Cliente', 'Local', 'Direccion', 'Nombre tecnico', 'Estado actividad'];
const FILAS_DETALLE = [334.56, 358.68, 382.8, 406.92, 431.04, 455.16, 479.28, 503.4, 527.04];
const COLUMNAS = [[51.84, 214.8], [215.76, 279.24], [280.2, 355.8], [356.76, 432.36], [433.32, 537.48]];
const TITULOS = ['Tipo Actividad', '# Equipos', 'Modelo', 'Tipo equipo', 'Serie'];
const X_DATOS = 219.5;
const X_FIN = 534.5;
export const MAX_LINEAS = 9;

/** Deja solo caracteres que la fuente estándar del PDF puede escribir. */
const limpiar = (s) => (s || '').replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/[–—]/g, '-')
  .replace(/[^\x20-\x7E -ÿ…]/g, '');

/**
 * ot: { numero, fecha, cliente, local, direccion, tecnico, estado, lineas:[{tipoActividad,cantidad,modelo,tipoEquipo,serie}],
 *       observaciones, recibeNombre, recibeCargo }
 * firmaPng: bytes PNG (fondo blanco) o null. logoJpg: bytes del logo NCR Voyix.
 */
export async function crearPdfOt(ot, firmaPng, logoJpg, imagenes = []) {
  const { PDFDocument, StandardFonts, rgb } = window.PDFLib;
  const doc = await PDFDocument.create();
  doc.setTitle(`Orden de trabajo ${ot.numero || ''}`.trim());
  doc.setProducer('Infomaxis');
  const p = doc.addPage([ANCHO, ALTO]);
  const normal = await doc.embedFont(StandardFonts.Helvetica);
  const negrita = await doc.embedFont(StandardFonts.HelveticaBold);
  const VERDE = rgb(36 / 255, 148 / 255, 95 / 255);
  const AZUL = rgb(15 / 255, 30 / 255, 90 / 255);
  const NEGRO = rgb(0, 0, 0);
  const y = (top) => ALTO - top;

  // Logo y título
  if (logoJpg) {
    const img = await doc.embedJpg(logoJpg);
    p.drawImage(img, { x: 50.4, y: y(47.0), width: 161.2, height: 25.4 });
  }
  const texto = (t, x, base, { centro = false, max = 300, fuente = normal, color = NEGRO, tam = 11 } = {}) => {
    t = limpiar(t);
    let s = tam;
    while (fuente.widthOfTextAtSize(t, s) > max && s > 7) s -= 0.25;
    const w = fuente.widthOfTextAtSize(t, s);
    p.drawText(t, { x: centro ? x - w / 2 : x, y: y(base), size: s, font: fuente, color });
  };
  const dato = (v, x, base, max, centro = false) => {
    let t = limpiar(String(v || '').replace(/\n/g, ' ').trim());
    if (!t) return;
    let s = 10;
    while (normal.widthOfTextAtSize(t, s) > max && s > 6.5) s -= 0.25;
    if (normal.widthOfTextAtSize(t, s) > max) {
      while (t.length > 1 && normal.widthOfTextAtSize(t + '…', s) > max) t = t.slice(0, -1);
      t += '…';
    }
    const w = normal.widthOfTextAtSize(t, s);
    p.drawText(t, { x: centro ? x - w / 2 : x, y: y(base), size: s, font: normal, color: AZUL });
  };

  texto('ORDEN DE TRABAJO', 297.6, 61.5, { centro: true, max: 595 });
  for (const [x0, t0, x1, t1] of LINEAS) p.drawRectangle({ x: x0, y: y(t1), width: x1 - x0, height: t1 - t0, color: NEGRO });

  texto('SEGUIMIENTO NCR EXTERNAL', 52.8, 89.6, { max: 160, fuente: negrita, color: VERDE });
  texto('DATOS A COMPLETAR', 376.6, 89.6, { centro: true, fuente: negrita, color: VERDE });
  ETIQUETAS.forEach((e, i) => texto(e, 52.8, FILAS_DATOS[i] - 2.9, { max: 160 }));
  texto('Detalle de actividad', 294.7, 282.6, { centro: true, max: 480, fuente: negrita, color: VERDE });
  COLUMNAS.forEach(([a, b], i) => texto(TITULOS[i], (a + b) / 2, 306.6, { centro: true, max: b - a - 4, fuente: negrita, color: VERDE }));
  texto('Observaciones', 52.8, 566.8, { max: 160 });
  texto('Nombre quien recibe', 52.8, 618.3, { max: 160 });
  texto('Cargo quien recibe', 52.8, 641.5, { max: 160 });
  texto('Firma cliente', 294.7, 739.0, { centro: true });
  texto('Preparado por Infomaxis', 51.4, 815.7);

  [ot.numero, ot.fecha, ot.cliente, ot.local, ot.direccion, ot.tecnico, ot.estado]
    .forEach((v, i) => dato(v, X_DATOS, FILAS_DATOS[i] - 2.9, X_FIN - X_DATOS));

  (ot.lineas || []).filter((l) => Object.values(l).some((v) => String(v || '').trim())).slice(0, MAX_LINEAS).forEach((l, i) => {
    const base = FILAS_DETALLE[i] - 7.2;
    const actividad = /^otros$/i.test((l.tipoActividad || '').trim()) && (l.actividadOtra || '').trim() ? l.actividadOtra : l.tipoActividad;
    [actividad, l.cantidad, l.modelo, l.tipoEquipo, l.serie].forEach((v, j) => {
      const [a, b] = COLUMNAS[j];
      if (j === 1) dato(v, (a + b) / 2, base, b - a - 6, true);
      else dato(v, a + 3.5, base, b - a - 7);
    });
  });

  // Observaciones en varias líneas, achicando hasta que quepan.
  const obs = limpiar((ot.observaciones || '').trim());
  if (obs) {
    const ancho = X_FIN - X_DATOS;
    const alto = 595.5 - 531.5;
    let s = 10;
    let lineas;
    for (;;) {
      lineas = [];
      for (const parrafo of obs.split('\n')) {
        let actual = '';
        for (const palabra of parrafo.split(/\s+/)) {
          const prueba = actual ? `${actual} ${palabra}` : palabra;
          if (normal.widthOfTextAtSize(prueba, s) <= ancho || !actual) actual = prueba;
          else { lineas.push(actual); actual = palabra; }
        }
        lineas.push(actual);
      }
      if (lineas.length * s * 1.15 <= alto || s <= 6.5) break;
      s -= 0.5;
    }
    lineas.forEach((l, i) => {
      const base = 531.5 + s + i * s * 1.15;
      if (base <= 596) p.drawText(l, { x: X_DATOS, y: y(base), size: s, font: normal, color: AZUL });
    });
  }
  dato(ot.recibeNombre, X_DATOS, 618.3, X_FIN - X_DATOS);
  dato(ot.recibeCargo, X_DATOS, 641.5, X_FIN - X_DATOS);

  if (firmaPng) {
    const f = await doc.embedPng(firmaPng);
    const [cx0, ct0, cx1, ct1] = [130, 650, 460, 727];
    const e = Math.min((cx1 - cx0) / f.width, (ct1 - ct0) / f.height);
    const w = f.width * e;
    const h = f.height * e;
    p.drawImage(f, { x: (cx0 + cx1) / 2 - w / 2, y: y(ct1), width: w, height: h });
  }
  // Imágenes: páginas adicionales, dos por hoja.
  const logoImg = logoJpg ? await doc.embedJpg(logoJpg) : null;
  const cajas = [[51.84, 78, 539.4, 438], [51.84, 452, 539.4, 800]];
  for (let i = 0; i < imagenes.length; i += 2) {
    const pg = doc.addPage([ANCHO, ALTO]);
    if (logoImg) pg.drawImage(logoImg, { x: 50.4, y: y(47.0), width: 161.2, height: 25.4 });
    const titulo = limpiar(`ORDEN DE TRABAJO${ot.numero ? ` N° ${ot.numero}` : ''} · IMÁGENES`);
    let s = 11;
    while (normal.widthOfTextAtSize(titulo, s) > 490 && s > 7) s -= 0.25;
    pg.drawText(titulo, { x: 297.6 - normal.widthOfTextAtSize(titulo, s) / 2, y: y(61.5), size: s, font: normal, color: NEGRO });
    for (let j = 0; j < 2 && i + j < imagenes.length; j++) {
      const [x0, t0, x1, t1] = cajas[j];
      pg.drawText(`Imagen ${i + j + 1} de ${imagenes.length}`, { x: x0, y: y(t0 + 11), size: 11, font: negrita, color: VERDE });
      const top = t0 + 18;
      pg.drawRectangle({ x: x0, y: y(t1), width: x1 - x0, height: t1 - top, borderColor: rgb(214 / 255, 217 / 255, 226 / 255), borderWidth: 0.8 });
      try {
        const img = await doc.embedJpg(imagenes[i + j]);
        const e = Math.min((x1 - x0 - 8) / img.width, (t1 - top - 8) / img.height);
        const w = img.width * e;
        const h = img.height * e;
        pg.drawImage(img, { x: (x0 + x1) / 2 - w / 2, y: y((top + t1) / 2 + h / 2), width: w, height: h });
      } catch (_) { /* imagen dañada: se deja el recuadro */ }
    }
    pg.drawText('Preparado por Infomaxis', { x: 51.4, y: y(815.7), size: 11, font: normal, color: NEGRO });
  }
  return doc.save();
}

// ---------------------------------------------------------------------------
// «Orden de trabajo IMX» (diseño minimalista, mismas medidas que OtPdfImx.kt).
// ---------------------------------------------------------------------------
const ANCHOS_IMX = [0.3, 0.12, 0.18, 0.19, 0.21];
const TITULOS_IMX = ['Tipo actividad', '# Equipos', 'Modelo', 'Tipo equipo', 'Serie'];

/** estados: lista de casillas (la misma de la app). logoPng: bytes del logo IMX. */
export async function crearPdfOtImx(ot, firmaPng, logoPng, imagenes = [], estados = []) {
  const { PDFDocument, StandardFonts, rgb } = window.PDFLib;
  const doc = await PDFDocument.create();
  doc.setTitle(`Orden de trabajo IMX ${ot.numero || ''}`.trim());
  doc.setProducer('Infomaxis');
  const normal = await doc.embedFont(StandardFonts.Helvetica);
  const negrita = await doc.embedFont(StandardFonts.HelveticaBold);
  const c255 = (r, g, b) => rgb(r / 255, g / 255, b / 255);
  const TINTA = c255(20, 28, 60), GRIS = c255(107, 112, 128), LINEA_C = c255(209, 214, 224);
  const NAR = c255(240, 147, 0), NAR2 = c255(240, 98, 20), BLANCO = rgb(1, 1, 1);
  const X0 = 50, X1 = 545, FILA = 22, TOP = 336;
  const y = (t) => ALTO - t;
  const logo = logoPng ? await doc.embedPng(logoPng) : null;
  const paginas = 1 + Math.ceil(imagenes.length / 2);

  const texto = (p, v, x, base, tam, color, { bold = false, centro = false, derecha = false, max = 0, cortar = false } = {}) => {
    let t = limpiar(String(v ?? ''));
    if (!t) return;
    const f = bold ? negrita : normal;
    let s = tam;
    if (max > 0) {
      while (f.widthOfTextAtSize(t, s) > max && s > 6.5) s -= 0.25;
      if (cortar && f.widthOfTextAtSize(t, s) > max) {
        while (t.length > 1 && f.widthOfTextAtSize(t + '…', s) > max) t = t.slice(0, -1);
        t += '…';
      }
    }
    const w = f.widthOfTextAtSize(t, s);
    p.drawText(t, { x: centro ? x - w / 2 : derecha ? x - w : x, y: y(base), size: s, font: f, color });
  };
  const linea = (p, xa, ta, xb, tb, color, grosor) => p.drawLine({ start: { x: xa, y: y(ta) }, end: { x: xb, y: y(tb) }, thickness: grosor, color });
  const caja = (p, xa, ta, xb, tb, opts) => p.drawRectangle({ x: xa, y: y(tb), width: xb - xa, height: tb - ta, ...opts });

  const encabezado = (p, titulo) => {
    if (logo) {
      const h = 52;
      p.drawImage(logo, { x: X0, y: y(34 + h), width: h * logo.width / logo.height, height: h });
    }
    linea(p, 126, 36, 126, 84, LINEA_C, 0.7);
    texto(p, titulo, 140, 58, 19, TINTA, { bold: true, max: 260 });
    texto(p, 'IMX · Servicio técnico', 140, 76, 10, GRIS);
    if ((ot.numero || '').trim()) {
      texto(p, 'N°', X1, 50, 9, GRIS, { derecha: true });
      texto(p, ot.numero.trim(), X1, 72, 17, NAR2, { bold: true, derecha: true, max: 140 });
    }
    caja(p, X0, 99, X1, 101, { color: NAR });
  };
  const pie = (p, n) => {
    linea(p, X0, 805, X1, 805, LINEA_C, 0.7);
    texto(p, 'Preparado por Infomaxis', X0, 820, 8.5, GRIS);
    texto(p, `Página ${n} de ${paginas}`, X1, 820, 8.5, GRIS, { derecha: true });
  };
  const seccion = (p, n, titulo, top) => {
    p.drawCircle({ x: 60, y: y(top - 4), size: 9, color: NAR });
    texto(p, String(n), 60, top, 10, BLANCO, { bold: true, centro: true });
    texto(p, titulo, 76, top, 12, TINTA, { bold: true });
  };
  const campoImx = (p, etiqueta, valor, xa, xb, top) => {
    texto(p, etiqueta, xa, top, 8.5, GRIS);
    texto(p, String(valor || '').replace(/\n/g, ' ').trim(), xa, top + 15, 11, TINTA, { max: xb - xa, cortar: true });
    linea(p, xa, top + 21, xb, top + 21, LINEA_C, 0.7);
  };
  const partir = (t, s) => {
    const out = [];
    for (const parrafo of t.split('\n')) {
      let actual = '';
      for (const palabra of parrafo.split(/\s+/)) {
        const prueba = actual ? `${actual} ${palabra}` : palabra;
        if (normal.widthOfTextAtSize(prueba, s) <= X1 - X0 || !actual) actual = prueba;
        else { out.push(actual); actual = palabra; }
      }
      out.push(actual);
    }
    return out;
  };

  // ---------- Página 1 ----------
  const p = doc.addPage([ANCHO, ALTO]);
  encabezado(p, 'Orden de trabajo');
  seccion(p, 1, 'Datos de la visita', 132);
  campoImx(p, 'Cliente', ot.cliente, X0, 285, 158);
  campoImx(p, 'Fecha', ot.fecha, 310, X1, 158);
  campoImx(p, 'Local', ot.local, X0, 285, 196);
  campoImx(p, 'Técnico', ot.tecnico, 310, X1, 196);
  campoImx(p, 'Dirección', ot.direccion, X0, X1, 234);

  texto(p, 'Estado actividad', X0, 272, 8.5, GRIS);
  const est = (ot.estado || '').trim();
  const marcado = estados.find((e) => e.toLowerCase() === est.toLowerCase());
  estados.forEach((e, i) => {
    const x = X0 + i * 99;
    caja(p, x, 279, x + 11, 290, { borderColor: TINTA, borderWidth: 0.9 });
    if (e === marcado) caja(p, x + 2.5, 281.5, x + 8.5, 287.5, { color: NAR2 });
    texto(p, e, x + 17, 288.5, 10.5, TINTA);
  });
  if (!marcado && est) texto(p, `Otro: ${est}`, X1, 272, 8.5, NAR2, { derecha: true, max: 200 });

  seccion(p, 2, 'Detalle de actividad', 322);
  const xs = [X0];
  ANCHOS_IMX.forEach((a) => xs.push(xs[xs.length - 1] + a * (X1 - X0)));
  TITULOS_IMX.forEach((t, i) => texto(p, t, (xs[i] + xs[i + 1]) / 2, TOP + FILA - 7, 9, TINTA, { bold: true, centro: true, max: xs[i + 1] - xs[i] - 4 }));
  const fin = TOP + FILA * (MAX_LINEAS + 1);
  for (let k = 1; k <= MAX_LINEAS; k++) linea(p, X0, TOP + FILA * (k + 1), X1, TOP + FILA * (k + 1), LINEA_C, 0.7);
  for (let i = 1; i < 5; i++) linea(p, xs[i], TOP + FILA, xs[i], fin, LINEA_C, 0.7);
  caja(p, X0, TOP, X1, fin, { borderColor: LINEA_C, borderWidth: 0.7 });
  linea(p, X0, TOP + FILA, X1, TOP + FILA, TINTA, 1.2);
  (ot.lineas || []).filter((l) => Object.values(l).some((v) => String(v || '').trim())).slice(0, MAX_LINEAS).forEach((l, k) => {
    const base = TOP + FILA * (k + 2) - 7;
    const actividad = /^otros$/i.test((l.tipoActividad || '').trim()) && (l.actividadOtra || '').trim() ? l.actividadOtra : l.tipoActividad;
    [actividad, l.cantidad, l.modelo, l.tipoEquipo, l.serie].forEach((v, i) => {
      const ancho = xs[i + 1] - xs[i] - 8;
      if (i === 1) texto(p, v, (xs[i] + xs[i + 1]) / 2, base, 9.5, TINTA, { centro: true, max: ancho, cortar: true });
      else texto(p, v, xs[i] + 5, base, 9.5, TINTA, { max: ancho, cortar: true });
    });
  });

  const tObs = fin + 30;
  seccion(p, 3, 'Observaciones', tObs);
  for (let k = 0; k < 3; k++) linea(p, X0, tObs + 30 + k * 20, X1, tObs + 30 + k * 20, LINEA_C, 0.7);
  const obs = limpiar((ot.observaciones || '').trim());
  if (obs) {
    let s = 10.5;
    let ls = partir(obs, s);
    if (ls.length <= 3) ls.forEach((l, i) => texto(p, l, X0, tObs + 26 + i * 20, s, TINTA));
    else {
      while (s > 6.5) { s -= 0.5; ls = partir(obs, s); if (ls.length * s * 1.2 <= 64) break; }
      ls.forEach((l, i) => { const b = tObs + 10 + s + i * s * 1.2; if (b <= tObs + 76) texto(p, l, X0, b, s, TINTA); });
    }
  }

  const tCli = fin + 132;
  seccion(p, 4, 'Conformidad del cliente', tCli);
  campoImx(p, 'Nombre', ot.recibeNombre, X0, 230, tCli + 26);
  campoImx(p, 'Cargo', ot.recibeCargo, X0, 230, tCli + 66);
  caja(p, 260, tCli + 16, X1, tCli + 100, { borderColor: LINEA_C, borderWidth: 0.7 });
  texto(p, 'Firma cliente', 402.5, tCli + 94, 8.5, GRIS, { centro: true });
  if (firmaPng) {
    const f = await doc.embedPng(firmaPng);
    const [cx0, ct0, cx1, ct1] = [270, tCli + 20, 535, tCli + 84];
    const e = Math.min((cx1 - cx0) / f.width, (ct1 - ct0) / f.height);
    const w = f.width * e, h = f.height * e;
    p.drawImage(f, { x: (cx0 + cx1) / 2 - w / 2, y: y(ct1), width: w, height: h });
  }
  pie(p, 1);

  // ---------- Imágenes, dos por hoja ----------
  const cajas = [[X0, 120, X1, 455], [X0, 470, X1, 795]];
  for (let i = 0; i < imagenes.length; i += 2) {
    const pg = doc.addPage([ANCHO, ALTO]);
    encabezado(pg, 'Imágenes');
    for (let j = 0; j < 2 && i + j < imagenes.length; j++) {
      const [xa, ta, xb, tb] = cajas[j];
      texto(pg, `Imagen ${i + j + 1} de ${imagenes.length}`, xa, ta + 2, 10.5, NAR2, { bold: true });
      const top = ta + 10;
      caja(pg, xa, top, xb, tb, { borderColor: LINEA_C, borderWidth: 0.8 });
      try {
        const img = await doc.embedJpg(imagenes[i + j]);
        const e = Math.min((xb - xa - 8) / img.width, (tb - top - 8) / img.height);
        const w = img.width * e, h = img.height * e;
        pg.drawImage(img, { x: (xa + xb) / 2 - w / 2, y: y((top + tb) / 2 + h / 2), width: w, height: h });
      } catch (_) { /* imagen dañada: queda el recuadro */ }
    }
    pie(pg, i / 2 + 2);
  }
  return doc.save();
}
