// Módulo «OT trabajos» (versión web): orden de trabajo NCR Voyix o IMX, con firma y PDF.
import {
  el, db, aviso, elegir, confirmar, conEspera, pedirTexto, pedirImagen, pedirImagenes, compartirArchivo, descargarArchivo, campoConLista, cabecera,
  hoy, aIso, deIso, comprimir, guardarFoto, leerFoto, CLIENTES, POR_TIPO, COMPONENTES, etiqueta,
} from './base.js';
import { leerEtiqueta } from './lector.js';
import { crearPdfOt, crearPdfOtImx, MAX_LINEAS } from './otpdf.js';
import { cargarCajas } from './inventario.js';
import { marcarImagen } from './marcar.js';

const ESTADOS = ['Finalizada', 'Pendiente', 'Parcial', 'Reprogramada', 'Cancelada'];
const OTROS = 'Otros';
const TIPOS_ACTIVIDAD = ['Instalación', 'Retiro', 'Reposicionamiento', 'Mantención', 'Reparación', 'Revisión', 'Cambio de equipo', OTROS];
const TIPOS_EQUIPO = ['POS', 'SSCO', 'Pantalla', 'Segunda pantalla', 'Impresora', 'Gaveta', 'Teclado', 'Scanner de mano', 'Scanner de mesón'];

/**
 * Detalle de la OT en modo conteo desde el Inventario: solo el equipo principal de cada caja (POS o SSCO),
 * una línea por trabajo, tipo y modelo, con la cantidad. La serie solo se escribe cuando es 1 equipo (igual que en Android).
 */
export function lineasDesdeInventario(cajas) {
  const grupos = new Map();
  for (const c of cajas) {
    for (const comp of [COMPONENTES[0]]) {
      const p = c.piezas?.[comp];
      if (!p || !(p.modelo || p.serie)) continue;
      const equipo = etiqueta(comp, c.tipo);
      const modelo = (p.modelo || '').trim();
      const k = [c.trabajo || '', equipo, modelo].join('|').toLowerCase();
      if (!grupos.has(k)) { const o = COMPONENTES.indexOf(comp); grupos.set(k, { trabajo: c.trabajo || '', equipo, modelo, orden: o < 0 ? 99 : o, series: [] }); }
      grupos.get(k).series.push((p.serie || '').trim());
    }
  }
  const ot = (t) => (t === 'Retiro' ? 0 : t === 'Instalación' ? 1 : 2);
  return [...grupos.values()].sort((a, b) => ot(a.trabajo) - ot(b.trabajo) || a.orden - b.orden)
    .map((g) => ({ tipoActividad: g.trabajo, cantidad: String(g.series.length), modelo: g.modelo, tipoEquipo: g.equipo, serie: g.series.length === 1 ? g.series[0] : '' }));
}

export async function resumenOt() {
  const ot = await db.get('kv', 'ot');
  return ot?.numero ? `OT en curso: N° ${ot.numero}` : '';
}

export async function pantallaOt(app) {
  document.title = 'OT trabajos · Infomaxis';
  const guardada = (await db.get('kv', 'ot')) || {};
  const ultimoTecnico = (await db.get('kv', 'tecnico')) || '';
  // Lista de clientes: los fijos, los agregados con «Otros» y al final «Otros».
  const clientesExtra = (await db.get('kv', 'clientesExtra')) || [];
  const listaClientes = [...CLIENTES, ...clientesExtra.filter((c) => !CLIENTES.some((x) => x.toLowerCase() === c.toLowerCase())), OTROS];

  const input = (attrs = {}) => el('input', { type: 'text', ...attrs });
  const f = {
    numero: input({ class: 'mono', autocapitalize: 'characters' }),
    fecha: el('input', { type: 'date' }),
    cliente: input({ autocapitalize: 'words' }),
    local: input(),
    direccion: input({ autocapitalize: 'words' }),
    tecnico: input({ autocapitalize: 'words' }),
    estado: input(),
    observaciones: el('textarea', { rows: 3 }),
    recibeNombre: input({ autocapitalize: 'words' }),
    recibeCargo: input(),
  };
  const campo = (rotulo, i, lista) => {
    const c = el('div', { class: 'campo' }, el('label', { class: 'rotulo' }, rotulo), i);
    if (lista) campoConLista(i, lista);
    return c;
  };

  // ---------- Líneas ----------
  const lineas = [];
  const contenedor = el('div');
  const cuenta = el('small');
  const btnAgregar = el('button', { class: 'boton borde ancho', type: 'button', style: 'margin-top:10px', onclick: () => agregarLinea({ cantidad: '1' }, true) }, '+ Agregar línea');

  function lineaCampo(rotulo, attrs, lista) {
    const i = input(attrs);
    return { i, nodo: campo(rotulo, i, lista) };
  }

  function agregarLinea(l = {}, enfocar = false) {
    if (lineas.length >= MAX_LINEAS) { aviso(`La OT tiene espacio para ${MAX_LINEAS} líneas.`); return; }
    const ui = {
      tipoActividad: lineaCampo('Tipo actividad', {}, TIPOS_ACTIVIDAD),
      cantidad: lineaCampo('# Equipos', { inputmode: 'numeric' }),
      tipoEquipo: lineaCampo('Tipo equipo', {}, TIPOS_EQUIPO),
      modelo: lineaCampo('Modelo', { class: 'mono', autocapitalize: 'characters' }),
      serie: lineaCampo('Serie', { class: 'mono', autocapitalize: 'characters' }),
      actividadOtra: lineaCampo('Escribe la actividad', {}),
    };
    for (const k of Object.keys(ui)) ui[k].i.value = l[k] || '';
    // «Otros»: aparece un campo para escribir la actividad.
    const revisarOtros = () => {
      const otros = ui.tipoActividad.i.value.trim().toLowerCase() === OTROS.toLowerCase();
      const antes = !ui.actividadOtra.nodo.hidden;
      ui.actividadOtra.nodo.hidden = !otros;
      if (otros && !antes) ui.actividadOtra.i.focus();
    };
    ui.actividadOtra.nodo.hidden = true;
    ui.tipoActividad.i.addEventListener('input', revisarOtros);
    ui.tipoActividad.i.addEventListener('change', revisarOtros);
    const estado = el('div', { class: 'estado-lectura', hidden: true });
    const camara = (esSerie) => el('button', { class: 'camara', type: 'button', 'aria-label': esSerie ? 'Foto de la serie' : 'Foto del modelo', onclick: () => leer(ui, esSerie, estado) }, '📷');
    const titulo = el('b');
    const nodo = el('div', { class: 'linea-ot' },
      el('header', {}, titulo, el('button', { class: 'boton-texto rojo', type: 'button', onclick: () => quitar(nodoRef) }, 'Quitar')),
      el('div', { class: 'fila' }, ui.tipoActividad.nodo, el('div', { style: 'width:96px;flex:none' }, ui.cantidad.nodo)),
      ui.actividadOtra.nodo,
      ui.tipoEquipo.nodo,
      el('div', { class: 'fila' }, ui.modelo.nodo, camara(false)),
      el('div', { class: 'fila' }, ui.serie.nodo, camara(true)),
      estado);
    const nodoRef = { nodo, ui, titulo };
    lineas.push(nodoRef);
    contenedor.append(nodo);
    if (ui.tipoActividad.i.value.trim().toLowerCase() === OTROS.toLowerCase()) ui.actividadOtra.nodo.hidden = false;
    numerar();
    if (enfocar) { ui.tipoActividad.i.focus(); nodo.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
  }

  function quitar(ref) {
    lineas.splice(lineas.indexOf(ref), 1);
    ref.nodo.remove();
    numerar();
  }

  function numerar() {
    lineas.forEach((l, i) => { l.titulo.textContent = `Línea ${i + 1}`; });
    cuenta.textContent = `${lineas.length} de ${MAX_LINEAS}`;
    btnAgregar.disabled = lineas.length >= MAX_LINEAS;
  }

  async function leer(ui, esSerie, estado) {
    const file = await pedirImagen();
    if (!file) return;
    const destino = esSerie ? ui.serie.i : ui.modelo.i;
    estado.hidden = false;
    estado.className = 'estado-lectura';
    estado.textContent = 'Leyendo la imagen… (la primera vez puede tardar unos segundos)';
    try {
      const { mejor, opciones } = await leerEtiqueta(file, esSerie);
      if (mejor) {
        destino.value = mejor;
        estado.className = 'estado-lectura ok';
        estado.textContent = '✓ Leído. Revisa que esté correcto.';
      } else if (opciones.length) {
        estado.textContent = 'Elige el valor correcto.';
        const i = await elegir(esSerie ? '¿Cuál es la serie?' : '¿Cuál es el modelo?', opciones);
        if (i !== null) { destino.value = opciones[i]; estado.className = 'estado-lectura ok'; estado.textContent = '✓ Valor elegido.'; }
      } else {
        estado.className = 'estado-lectura mal';
        estado.textContent = 'No se encontró texto ni código. Acerca la cámara o escríbelo a mano.';
      }
    } catch (_) {
      estado.className = 'estado-lectura mal';
      estado.textContent = 'No se pudo leer la imagen. Escríbelo a mano.';
    }
  }

  async function copiarDesdeInventario() {
    const cajas = await cargarCajas();
    const nuevas = lineasDesdeInventario(cajas);
    if (!nuevas.length) { aviso('El inventario no tiene equipos registrados.'); return; }
    const extra = nuevas.length > MAX_LINEAS ? `\n\nSon ${nuevas.length} líneas y la OT tiene ${MAX_LINEAS}: se copian las primeras ${MAX_LINEAS}.` : '';
    if (!(await confirmar('Copiar desde Inventario', `Se reemplazará el detalle actual por ${Math.min(nuevas.length, MAX_LINEAS)} líneas con la cantidad de equipos por modelo.${extra}`, 'Copiar'))) return;
    ponerLineasInventario(cajas, nuevas);
  }

  function ponerLineasInventario(cajas, nuevas) {
    lineas.splice(0).forEach((l) => l.nodo.remove());
    nuevas.slice(0, MAX_LINEAS).forEach((l) => agregarLinea(l));
    const c = cajas[0];
    if (c) {
      if (!f.cliente.value) f.cliente.value = c.cliente || '';
      if (!f.local.value) f.local.value = c.tienda || '';
      if (!f.direccion.value) f.direccion.value = c.direccion || '';
    }
    guardarBorrador();
    const n = nuevas.reduce((a, l) => a + (parseInt(l.cantidad, 10) || 0), 0);
    aviso(`Detalle llenado desde el Inventario: ${n} equipos en ${Math.min(nuevas.length, MAX_LINEAS)} líneas. Revisa y completa lo que falte.`);
  }

  /** Al entrar: si el detalle está vacío y hay equipos en el Inventario, se llena solo (modo conteo). */
  async function llenarDesdeInventarioSiVacio() {
    const conDatos = leerOt().lineas.some((l) => [l.tipoActividad, l.modelo, l.tipoEquipo, l.serie, l.actividadOtra].some(Boolean));
    if (conDatos) return;
    const cajas = await cargarCajas();
    const nuevas = lineasDesdeInventario(cajas);
    if (nuevas.length) ponerLineasInventario(cajas, nuevas);
  }

  // ---------- Firma ----------
  const lienzo = el('canvas');
  const aqui = el('div', { class: 'aqui' }, 'El cliente firma aquí con el dedo');
  const cajaFirma = el('div', { class: 'firma-caja' }, lienzo, el('div', { class: 'guia' }), aqui);
  let trazos = [];
  let actual = null;
  let firmaGuardada = guardada.firma || null; // data URL de una firma anterior

  function ajustarLienzo() {
    const r = cajaFirma.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    lienzo.width = Math.max(1, Math.round(r.width * dpr));
    lienzo.height = Math.max(1, Math.round(r.height * dpr));
    dibujarFirma();
  }
  function dibujarFirma() {
    const ctx = lienzo.getContext('2d');
    const dpr = window.devicePixelRatio || 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, lienzo.width, lienzo.height);
    if (firmaGuardada && !trazos.length) {
      const img = new Image();
      img.onload = () => {
        const e = Math.min(lienzo.width / img.width, lienzo.height / img.height) * 0.9;
        ctx.drawImage(img, (lienzo.width - img.width * e) / 2, (lienzo.height - img.height * e) / 2, img.width * e, img.height * e);
      };
      img.src = firmaGuardada;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.strokeStyle = '#0F1E5A';
    ctx.lineWidth = 3;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const t of [...trazos, actual].filter(Boolean)) {
      ctx.beginPath();
      t.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
      if (t.length === 1) ctx.lineTo(t[0][0] + 0.1, t[0][1]);
      ctx.stroke();
    }
    aqui.hidden = Boolean(trazos.length || actual || firmaGuardada);
  }
  const punto = (e) => { const r = lienzo.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
  lienzo.addEventListener('pointerdown', (e) => {
    if (firmaGuardada && !trazos.length) { aviso('Ya hay una firma guardada. Toca «Borrar firma» para firmar de nuevo.'); return; }
    lienzo.setPointerCapture(e.pointerId);
    actual = [punto(e)];
    dibujarFirma();
    e.preventDefault();
  });
  lienzo.addEventListener('pointermove', (e) => {
    if (!actual) return;
    const eventos = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    for (const ev of eventos) actual.push(punto(ev));
    dibujarFirma();
    e.preventDefault();
  });
  const terminarTrazo = () => {
    if (!actual) return;
    trazos.push(actual);
    actual = null;
    dibujarFirma();
    guardarBorrador();
  };
  lienzo.addEventListener('pointerup', terminarTrazo);
  lienzo.addEventListener('pointercancel', terminarTrazo);

  /** Firma recortada, sobre fondo blanco, como PNG (data URL). */
  function firmaComoImagen() {
    if (!trazos.length) return firmaGuardada;
    const pts = trazos.flat();
    const m = 8;
    const x0 = Math.max(0, Math.min(...pts.map((p) => p[0])) - m);
    const y0 = Math.max(0, Math.min(...pts.map((p) => p[1])) - m);
    const x1 = Math.max(...pts.map((p) => p[0])) + m;
    const y1 = Math.max(...pts.map((p) => p[1])) + m;
    const esc = 3;
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round((x1 - x0) * esc));
    c.height = Math.max(1, Math.round((y1 - y0) * esc));
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.setTransform(esc, 0, 0, esc, -x0 * esc, -y0 * esc);
    ctx.strokeStyle = '#0F1E5A';
    ctx.lineWidth = 3;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const t of trazos) {
      ctx.beginPath();
      t.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
      if (t.length === 1) ctx.lineTo(t[0][0] + 0.1, t[0][1]);
      ctx.stroke();
    }
    return c.toDataURL('image/png');
  }

  function borrarFirma() {
    trazos = [];
    actual = null;
    firmaGuardada = null;
    dibujarFirma();
    guardarBorrador();
  }

  // «Otros» en Cliente: se escribe un cliente nuevo y queda en la lista.
  f.cliente.addEventListener('change', async () => {
    if (f.cliente.value.trim().toLowerCase() !== OTROS.toLowerCase()) return;
    f.cliente.value = '';
    const nombre = await pedirTexto('Nuevo cliente', 'Escribe el nombre. Quedará en la lista para las próximas OT.', 'Nombre del cliente');
    if (!nombre) return;
    if (!listaClientes.some((c) => c.toLowerCase() === nombre.toLowerCase())) {
      listaClientes.splice(listaClientes.length - 1, 0, nombre);
      clientesExtra.push(nombre);
      await db.set('kv', 'clientesExtra', clientesExtra);
    }
    f.cliente.value = listaClientes.find((c) => c.toLowerCase() === nombre.toLowerCase());
    guardarBorrador();
  });

  // ---------- Imágenes ----------
  let imagenes = [];
  let comentarios = {};
  const urlsImg = [];
  const cuentaImg = el('small');
  const tiraImg = el('div', { class: 'tira-imagenes' });

  async function pintarImagenes() {
    urlsImg.splice(0).forEach((u) => URL.revokeObjectURL(u));
    const nodos = [];
    for (const [i, id] of imagenes.entries()) {
      const b = await leerFoto(id);
      if (!b) continue;
      const u = URL.createObjectURL(b);
      urlsImg.push(u);
      nodos.push(el('div', { class: 'imagen-ot', style: `background-image:url("${u}")` },
        el('button', { class: 'ver', type: 'button', 'aria-label': `Imagen ${i + 1}`, onclick: () => opcionesImagen(id, i, u) }),
        el('span', { class: 'n' }, comentarios[id] ? `${i + 1} 💬` : String(i + 1)),
        el('button', { class: 'quitar', type: 'button', 'aria-label': `Quitar imagen ${i + 1}`, onclick: () => quitarImagen(id) }, '✕')));
    }
    tiraImg.replaceChildren(...nodos);
    tiraImg.hidden = !nodos.length;
    cuentaImg.textContent = imagenes.length === 0 ? 'Sin imágenes' : imagenes.length === 1 ? '1 imagen' : `${imagenes.length} imágenes`;
  }

  async function agregarImagenes() {
    const files = await pedirImagenes();
    if (!files.length) return;
    const nuevas = [];
    await conEspera(files.length === 1 ? 'Agregando imagen…' : `Agregando ${files.length} imágenes…`, async () => {
      for (const f of files) {
        try { nuevas.push(await guardarFoto(await comprimir(f), 'ot')); } catch (_) { aviso('Una imagen no se pudo agregar.'); }
      }
    });
    imagenes.push(...nuevas);
    await pintarImagenes();
    guardarBorrador();
    // Cada imagen nueva pasa por el editor para marcarla y comentarla (se puede dejar tal cual con «Listo»).
    for (const id of nuevas) {
      if (!(await editarImagen(id))) break;
    }
  }

  /** Abre el editor de marcas; devuelve false si se canceló. */
  async function editarImagen(id) {
    const blob = await leerFoto(id);
    if (!blob) return true;
    const i = imagenes.indexOf(id);
    const r = await marcarImagen(blob, comentarios[id] || '', `Imagen ${i + 1} de ${imagenes.length}`);
    if (!r) return false;
    if (r.blob) await db.set('fotos', id, r.blob);
    if (r.comentario) comentarios[id] = r.comentario; else delete comentarios[id];
    await pintarImagenes();
    guardarBorrador();
    return true;
  }

  async function opcionesImagen(id, i, url) {
    const c = comentarios[id];
    const j = await elegir(`Imagen ${i + 1}`, ['✏️  Marcar y comentar', '🔍  Ver imagen'], { mensaje: c ? `Comentario: ${c}` : 'Sin comentario.', cancelar: 'Cerrar' });
    if (j === 0) await editarImagen(id);
    else if (j === 1) window.open(url, '_blank');
  }

  async function quitarImagen(id) {
    imagenes = imagenes.filter((x) => x !== id);
    delete comentarios[id];
    await db.del('fotos', id);
    await pintarImagenes();
    guardarBorrador();
  }

  // ---------- Leer / guardar ----------
  function leerOt() {
    return {
      numero: f.numero.value.trim(), fecha: deIso(f.fecha.value) || hoy(), cliente: f.cliente.value.trim(), local: f.local.value.trim(),
      direccion: f.direccion.value.trim(), tecnico: f.tecnico.value.trim(), estado: f.estado.value.trim(),
      lineas: lineas.map(({ ui }) => {
        const l = Object.fromEntries(Object.entries(ui).map(([k, v]) => [k, v.i.value.trim()]));
        if (l.tipoActividad.toLowerCase() !== OTROS.toLowerCase()) l.actividadOtra = '';
        return l;
      }).filter((l) => Object.values(l).some(Boolean)),
      observaciones: f.observaciones.value.trim(), recibeNombre: f.recibeNombre.value.trim(), recibeCargo: f.recibeCargo.value.trim(),
      firma: firmaComoImagen(),
      imagenes: [...imagenes],
      comentarios: Object.fromEntries(imagenes.filter((id) => comentarios[id]).map((id) => [id, comentarios[id]])),
    };
  }
  let temporizador;
  function guardarBorrador() {
    clearTimeout(temporizador);
    temporizador = setTimeout(async () => {
      const ot = leerOt();
      await db.set('kv', 'ot', ot);
      if (ot.tecnico) await db.set('kv', 'tecnico', ot.tecnico);
    }, 400);
  }

  function mostrar(ot) {
    f.numero.value = ot.numero || '';
    f.fecha.value = aIso(ot.fecha || hoy());
    f.cliente.value = ot.cliente || '';
    f.local.value = ot.local || '';
    f.direccion.value = ot.direccion || '';
    f.tecnico.value = ot.tecnico || ultimoTecnico;
    f.estado.value = ot.estado || '';
    f.observaciones.value = ot.observaciones || '';
    f.recibeNombre.value = ot.recibeNombre || '';
    f.recibeCargo.value = ot.recibeCargo || '';
    lineas.splice(0).forEach((l) => l.nodo.remove());
    const ls = ot.lineas?.length ? ot.lineas : [{ cantidad: '1' }];
    ls.slice(0, MAX_LINEAS).forEach((l) => agregarLinea(l));
    trazos = [];
    firmaGuardada = ot.firma || null;
    dibujarFirma();
    imagenes = [...(ot.imagenes || [])];
    comentarios = { ...(ot.comentarios || {}) };
    pintarImagenes();
  }

  async function nueva() {
    if (!(await confirmar('¿Empezar una OT nueva?', 'Se borran los datos de la OT actual. Se mantienen la fecha de hoy y el nombre del técnico.', 'Nueva OT'))) return;
    const tecnico = f.tecnico.value.trim();
    for (const id of imagenes) await db.del('fotos', id);
    mostrar({ fecha: hoy(), tecnico });
    await db.set('kv', 'ot', leerOt());
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // ---------- PDF ----------
  async function generar() {
    const ot = leerOt();
    const faltan = [];
    if (!ot.numero) faltan.push('Número de orden');
    if (!ot.cliente) faltan.push('Cliente');
    if (!ot.tecnico) faltan.push('Nombre técnico');
    if (!ot.lineas.length) faltan.push('Detalle de actividad');
    if (!ot.recibeNombre) faltan.push('Nombre quien recibe');
    if (!ot.firma) faltan.push('Firma del cliente');
    if (faltan.length && !(await confirmar('Faltan datos', `No has completado:\n\n• ${faltan.join('\n• ')}\n\n¿Generar la OT igual?`, 'Generar igual', 'Completar'))) return;
    await db.set('kv', 'ot', ot);

    let bytes;
    try {
      bytes = await conEspera('Creando la OT en PDF…', async () => {
        const logo = new Uint8Array(await (await fetch(tipoOt === 'IMX' ? 'img/logo_imx.png' : 'img/logo_ncr_voyix.jpg')).arrayBuffer());
        const firma = ot.firma ? new Uint8Array(await (await fetch(ot.firma)).arrayBuffer()) : null;
        const imgs = [];
        const textos = [];
        for (const id of ot.imagenes) {
          const b = await leerFoto(id);
          if (b) { imgs.push(new Uint8Array(await b.arrayBuffer())); textos.push(ot.comentarios?.[id] || ''); }
        }
        return tipoOt === 'IMX' ? crearPdfOtImx(ot, firma, logo, imgs, ESTADOS, textos) : crearPdfOt(ot, firma, logo, imgs, textos);
      });
    } catch (e) {
      aviso(`No se pudo crear el PDF: ${e.message || e}`);
      return;
    }
    const nombre = [tipoOt === 'IMX' ? 'OT-IMX' : 'OT', ot.numero, ot.local, ot.fecha].filter(Boolean).join('_').replace(/[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ_-]+/g, '-') + '.pdf';
    const i = await elegir('OT lista', ['Compartir (WhatsApp, correo…)', 'Ver', 'Descargar al teléfono'], { mensaje: `Se creó ${nombre}.` });
    if (i === 0) await compartirArchivo(bytes, nombre, 'application/pdf', nombre.replace(/_/g, ' '));
    else if (i === 1) window.open(URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' })), '_blank');
    else if (i === 2) descargarArchivo(bytes, nombre, 'application/pdf');
  }

  // ---------- Armar pantalla ----------
  const contenido = el('div', { class: 'contenido' },
      el('div', { class: 'seccion' }, 'Seguimiento'),
      el('div', { class: 'tarjeta' },
        campo('Número de orden', f.numero), campo('Fecha', f.fecha), campo('Cliente', f.cliente, listaClientes),
        campo('Local', f.local), campo('Dirección', f.direccion), campo('Nombre técnico', f.tecnico), campo('Estado actividad', f.estado, ESTADOS)),
      el('div', { class: 'seccion' }, 'Detalle de actividad', cuenta),
      el('button', { class: 'boton-texto', type: 'button', onclick: copiarDesdeInventario }, 'Copiar equipos desde Inventario'),
      contenedor, btnAgregar,
      el('div', { class: 'seccion' }, 'Cierre'),
      el('div', { class: 'tarjeta' },
        campo('Observaciones', f.observaciones),
        el('div', { class: 'etiqueta', style: 'margin-top:6px;display:flex;justify-content:space-between' }, 'Imágenes', cuentaImg),
        el('p', { class: 'ayuda' }, 'Se agregan al final del PDF de la OT, dos por página.'),
        tiraImg,
        el('button', { class: 'boton borde ancho', type: 'button', onclick: agregarImagenes }, '+ Agregar imágenes'),
        campo('Nombre quien recibe', f.recibeNombre), campo('Cargo quien recibe', f.recibeCargo),
        el('div', { class: 'etiqueta', style: 'margin-top:6px' }, 'Firma cliente'),
        cajaFirma,
        el('button', { class: 'boton-texto rojo', type: 'button', style: 'align-self:flex-end', onclick: borrarFirma }, 'Borrar firma')));
  // ---------- Tipo de OT ----------
  let tipoOt = 'NCR';
  const chipTipo = el('button', { class: 'chip-trabajo', type: 'button', onclick: () => elegirTipo(false) }, 'OT NCR Voyix ▾');
  async function elegirTipo(obligatorio) {
    const i = await elegir('¿Qué orden de trabajo vas a llenar?', ['OT NCR Voyix', 'OT IMX'],
      { cancelar: obligatorio ? 'Volver al inicio' : 'Cancelar', obligatorio });
    if (i === null) { if (obligatorio) location.hash = '#inicio'; return; }
    tipoOt = i === 1 ? 'IMX' : 'NCR';
    chipTipo.textContent = tipoOt === 'IMX' ? 'OT IMX ▾' : 'OT NCR Voyix ▾';
    if (obligatorio) await llenarDesdeInventarioSiVacio();
  }

  app.replaceChildren(
    cabecera('OT trabajos', { debajo: chipTipo, derecha: el('button', { class: 'boton-cab', type: 'button', onclick: nueva }, 'Nueva OT') }),
    contenido,
    el('div', { class: 'barra-inferior' }, el('div', {}, el('button', { class: 'boton acento', type: 'button', onclick: generar }, 'GENERAR OT EN PDF'))));

  mostrar(guardada);
  requestAnimationFrame(ajustarLienzo);
  elegirTipo(true);
  window.addEventListener('resize', () => { if (lienzo.isConnected) ajustarLienzo(); });
  // Guarda el avance mientras se escribe (solo los campos de esta pantalla).
  contenido.addEventListener('input', guardarBorrador);
  contenido.addEventListener('change', guardarBorrador);
}
