// Módulo «OT trabajos» (versión web): orden de trabajo NCR Voyix con firma y PDF.
import {
  el, db, aviso, elegir, confirmar, conEspera, pedirImagen, compartirArchivo, descargarArchivo, campoConLista, cabecera,
  hoy, aIso, deIso, CLIENTES, POR_TIPO, COMPONENTES, etiqueta,
} from './base.js';
import { leerEtiqueta } from './lector.js';
import { crearPdfOt, MAX_LINEAS } from './otpdf.js';
import { cargarCajas } from './inventario.js';

const ESTADOS = ['Finalizada', 'Pendiente', 'Parcial', 'Reprogramada', 'Cancelada'];
const TIPOS_ACTIVIDAD = ['Instalación', 'Retiro', 'Mantención', 'Reparación', 'Revisión', 'Cambio de equipo'];
const TIPOS_EQUIPO = ['POS', 'SSCO', 'Pantalla', 'Segunda pantalla', 'Impresora', 'Gaveta', 'Teclado', 'Scanner de mano', 'Scanner de mesón'];

export async function resumenOt() {
  const ot = await db.get('kv', 'ot');
  return ot?.numero ? `OT en curso: N° ${ot.numero}` : '';
}

export async function pantallaOt(app) {
  document.title = 'OT trabajos · Infomaxis';
  const guardada = (await db.get('kv', 'ot')) || {};
  const ultimoTecnico = (await db.get('kv', 'tecnico')) || '';

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
    };
    for (const k of Object.keys(ui)) ui[k].i.value = l[k] || '';
    const estado = el('div', { class: 'estado-lectura', hidden: true });
    const camara = (esSerie) => el('button', { class: 'camara', type: 'button', 'aria-label': esSerie ? 'Foto de la serie' : 'Foto del modelo', onclick: () => leer(ui, esSerie, estado) }, '📷');
    const titulo = el('b');
    const nodo = el('div', { class: 'linea-ot' },
      el('header', {}, titulo, el('button', { class: 'boton-texto rojo', type: 'button', onclick: () => quitar(nodoRef) }, 'Quitar')),
      el('div', { class: 'fila' }, ui.tipoActividad.nodo, el('div', { style: 'width:96px;flex:none' }, ui.cantidad.nodo)),
      ui.tipoEquipo.nodo,
      el('div', { class: 'fila' }, ui.modelo.nodo, camara(false)),
      el('div', { class: 'fila' }, ui.serie.nodo, camara(true)),
      estado);
    const nodoRef = { nodo, ui, titulo };
    lineas.push(nodoRef);
    contenedor.append(nodo);
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
    const nuevas = [];
    for (const c of cajas) {
      for (const comp of POR_TIPO[c.tipo] || COMPONENTES) {
        const p = c.piezas?.[comp];
        if (p && (p.modelo || p.serie)) nuevas.push({ tipoActividad: c.trabajo || '', cantidad: '1', modelo: p.modelo, tipoEquipo: etiqueta(comp, c.tipo), serie: p.serie });
      }
    }
    if (!nuevas.length) { aviso('El inventario no tiene equipos registrados.'); return; }
    const extra = nuevas.length > MAX_LINEAS ? `\n\nHay ${nuevas.length} equipos y la OT tiene ${MAX_LINEAS} líneas: se copian los primeros ${MAX_LINEAS}.` : '';
    if (!(await confirmar('Copiar desde Inventario', `Se reemplazará el detalle actual por ${Math.min(nuevas.length, MAX_LINEAS)} equipos del inventario.${extra}`, 'Copiar'))) return;
    lineas.splice(0).forEach((l) => l.nodo.remove());
    nuevas.slice(0, MAX_LINEAS).forEach((l) => agregarLinea(l));
    const c = cajas[0];
    if (c) {
      if (!f.cliente.value) f.cliente.value = c.cliente || '';
      if (!f.local.value) f.local.value = c.tienda || '';
      if (!f.direccion.value) f.direccion.value = c.direccion || '';
    }
    aviso('Equipos copiados. Revisa y completa lo que falte.');
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

  // ---------- Leer / guardar ----------
  function leerOt() {
    return {
      numero: f.numero.value.trim(), fecha: deIso(f.fecha.value) || hoy(), cliente: f.cliente.value.trim(), local: f.local.value.trim(),
      direccion: f.direccion.value.trim(), tecnico: f.tecnico.value.trim(), estado: f.estado.value.trim(),
      lineas: lineas.map(({ ui }) => Object.fromEntries(Object.entries(ui).map(([k, v]) => [k, v.i.value.trim()])))
        .filter((l) => Object.values(l).some(Boolean)),
      observaciones: f.observaciones.value.trim(), recibeNombre: f.recibeNombre.value.trim(), recibeCargo: f.recibeCargo.value.trim(),
      firma: firmaComoImagen(),
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
  }

  async function nueva() {
    if (!(await confirmar('¿Empezar una OT nueva?', 'Se borran los datos de la OT actual. Se mantienen la fecha de hoy y el nombre del técnico.', 'Nueva OT'))) return;
    const tecnico = f.tecnico.value.trim();
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
        const logo = new Uint8Array(await (await fetch('img/logo_ncr_voyix.jpg')).arrayBuffer());
        const firma = ot.firma ? new Uint8Array(await (await fetch(ot.firma)).arrayBuffer()) : null;
        return crearPdfOt(ot, firma, logo);
      });
    } catch (e) {
      aviso(`No se pudo crear el PDF: ${e.message || e}`);
      return;
    }
    const nombre = ['OT', ot.numero, ot.local, ot.fecha].filter(Boolean).join('_').replace(/[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ_-]+/g, '-') + '.pdf';
    const i = await elegir('OT lista', ['Compartir (WhatsApp, correo…)', 'Ver', 'Descargar al teléfono'], { mensaje: `Se creó ${nombre}.` });
    if (i === 0) await compartirArchivo(bytes, nombre, 'application/pdf', nombre.replace(/_/g, ' '));
    else if (i === 1) window.open(URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' })), '_blank');
    else if (i === 2) descargarArchivo(bytes, nombre, 'application/pdf');
  }

  // ---------- Armar pantalla ----------
  const contenido = el('div', { class: 'contenido' },
      el('div', { class: 'seccion' }, 'Seguimiento'),
      el('div', { class: 'tarjeta' },
        campo('Número de orden', f.numero), campo('Fecha', f.fecha), campo('Cliente', f.cliente, CLIENTES),
        campo('Local', f.local), campo('Dirección', f.direccion), campo('Nombre técnico', f.tecnico), campo('Estado actividad', f.estado, ESTADOS)),
      el('div', { class: 'seccion' }, 'Detalle de actividad', cuenta),
      el('button', { class: 'boton-texto', type: 'button', onclick: copiarDesdeInventario }, 'Copiar equipos desde Inventario'),
      contenedor, btnAgregar,
      el('div', { class: 'seccion' }, 'Cierre'),
      el('div', { class: 'tarjeta' },
        campo('Observaciones', f.observaciones), campo('Nombre quien recibe', f.recibeNombre), campo('Cargo quien recibe', f.recibeCargo),
        el('div', { class: 'etiqueta', style: 'margin-top:6px' }, 'Firma cliente'),
        cajaFirma,
        el('button', { class: 'boton-texto rojo', type: 'button', style: 'align-self:flex-end', onclick: borrarFirma }, 'Borrar firma')));
  app.replaceChildren(
    cabecera('OT trabajos', { derecha: el('button', { class: 'boton-cab', type: 'button', onclick: nueva }, 'Nueva OT') }),
    contenido,
    el('div', { class: 'barra-inferior' }, el('div', {}, el('button', { class: 'boton acento', type: 'button', onclick: generar }, 'GENERAR OT EN PDF'))));

  mostrar(guardada);
  requestAnimationFrame(ajustarLienzo);
  window.addEventListener('resize', () => { if (lienzo.isConnected) ajustarLienzo(); });
  // Guarda el avance mientras se escribe (solo los campos de esta pantalla).
  contenido.addEventListener('input', guardarBorrador);
  contenido.addEventListener('change', guardarBorrador);
}
