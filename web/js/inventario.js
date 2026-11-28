// Módulo «Inventario equipos» (versión web): igual que en Android.
import {
  $, el, db, aviso, elegir, confirmar, conEspera, pedirImagen, compartirArchivo, descargarArchivo, campoConLista, cabecera,
  hoy, aIso, deIso, comprimir, guardarFoto, leerFoto, limpiarFotos,
  CLIENTES, OTROS, REGIONES, POS, SSCO, RETIRO, INSTALACION, POR_TIPO, COMPONENTES, FOTOS_POS, etiqueta, fotosDe,
} from './base.js';
import { leerEtiqueta } from './lector.js';
import { escribirXlsx, hojasInventario, leerXlsx } from './xlsx.js';
import * as FotosSeries from './fotosseries.js';

export async function cargarCajas() { return (await db.get('kv', 'cajas')) || []; }
const guardarCajas = (c) => db.set('kv', 'cajas', c);

export async function resumenInventario() {
  const n = (await cargarCajas()).length;
  return n === 0 ? 'Planilla vacía' : n === 1 ? '1 caja en la planilla' : `${n} cajas en la planilla`;
}

export async function pantallaInventario(app) {
  document.title = 'Inventario · Infomaxis';
  let cajas = await cargarCajas();
  const tienda = (await db.get('kv', 'tienda')) || {};
  let trabajo = '';
  let editandoId = null;
  let fotosForm = {};
  // Fotos usadas para leer modelo/serie («componente|campo» → id): se envían aparte del Excel.
  let fotosSeriesForm = {};
  const pendientesSeries = [];
  let fechaDeTrabajo = '';
  const candidatos = {};

  // ---------- Cabecera ----------
  const chipTrabajo = el('button', { class: 'chip-trabajo', type: 'button', onclick: () => elegirTrabajo(false) }, 'Elegir trabajo ▾');
  const contador = el('span', { class: 'contador' });

  // ---------- Tienda ----------
  const chipsCliente = el('div', { class: 'chips', role: 'group', 'aria-label': 'Cliente' });
  let cliente = '';
  const clienteOtro = el('input', { type: 'text', placeholder: 'Escribe el nombre del cliente', autocapitalize: 'words' });
  const cajaOtro = el('div', { class: 'campo', hidden: true }, clienteOtro);
  const edtTienda = el('input', { type: 'text', class: 'mono', placeholder: 'Ej: 512', autocapitalize: 'characters' });
  const cajaTienda = el('div', { hidden: true }, el('div', { class: 'etiqueta', style: 'margin-bottom:6px' }, 'N° de tienda'), el('div', { class: 'campo' }, edtTienda));
  const edtDireccion = el('input', { type: 'text', placeholder: 'Calle, número y comuna', autocapitalize: 'words' });
  const edtRegion = el('input', { type: 'text', placeholder: 'Escribe para buscar o elige la región', autocapitalize: 'words' });
  const campoRegion = el('div', { class: 'campo' }, edtRegion);
  campoConLista(edtRegion, REGIONES);
  const edtFecha = el('input', { type: 'date' });

  const pintarClientes = () => {
    chipsCliente.replaceChildren(...[...CLIENTES, OTROS].map((c) => el('button', {
      class: 'chip', type: 'button', 'aria-pressed': String(cliente === c),
      onclick: () => { cliente = cliente === c ? '' : c; pintarClientes(); if (cliente === OTROS) clienteOtro.focus(); },
    }, c)));
    cajaOtro.hidden = cliente !== OTROS;
    cajaTienda.hidden = !cliente && !edtTienda.value;
  };
  const clienteActual = () => (cliente === OTROS ? clienteOtro.value.trim() : cliente);
  const ponerCliente = (n) => {
    clienteOtro.value = '';
    if (!n) cliente = '';
    else if (CLIENTES.some((c) => c.toLowerCase() === n.toLowerCase())) cliente = CLIENTES.find((c) => c.toLowerCase() === n.toLowerCase());
    else { cliente = OTROS; clienteOtro.value = n; }
    pintarClientes();
  };
  const ponerTienda = (t) => {
    edtTienda.value = t.tienda || '';
    ponerCliente(t.cliente || '');
    edtDireccion.value = t.direccion || '';
    edtRegion.value = t.region || '';
  };

  // ---------- Caja ----------
  let tipo = '';
  const botonesTipo = [POS, SSCO].map((t) => el('button', { type: 'button', 'aria-pressed': 'false', onclick: () => { tipo = tipo === t ? '' : t; pintarTipo(); construirPiezas(leerPiezas()); } }, t));
  const segmento = el('div', { class: 'segmento', role: 'group', 'aria-label': 'Tipo de caja' }, botonesTipo);
  const pintarTipo = () => botonesTipo.forEach((b, i) => b.setAttribute('aria-pressed', String([POS, SSCO][i] === tipo)));
  const edtUbicacion = el('input', { type: 'text', placeholder: 'Ej: Caja 3, caja rápida' });
  const edtNumeroPos = el('input', { type: 'text', class: 'mono', placeholder: 'Ej: 05', autocapitalize: 'characters' });
  const contenedorPiezas = el('div', { style: 'display:flex;flex-direction:column;gap:12px' });
  const piezasUI = {};

  function campoLectura(nombre, campo) {
    const input = el('input', { type: 'text', class: 'mono', placeholder: campo === 'modelo' ? 'Modelo' : 'N° de serie', autocapitalize: 'characters', autocomplete: 'off' });
    const estado = el('div', { class: 'estado-lectura', hidden: true });
    const clave = `${nombre}|${campo}`;
    estado.addEventListener('click', () => mostrarOpciones(clave, input, estado));
    const boton = el('button', { class: 'camara', type: 'button', 'aria-label': `Foto de ${campo}`, onclick: () => leerFoto_(clave, input, estado) }, '📷');
    return { input, estado, nodo: el('div', {}, el('div', { class: 'fila' }, el('div', { class: 'campo' }, input), boton), estado) };
  }

  async function leerFoto_(clave, input, estado) {
    const file = await pedirImagen();
    if (!file) return;
    // La foto de la serie se guarda para enviarla después con «Enviar fotos de series» (la del modelo no).
    const destino = fotosSeriesForm;
    if (clave.endsWith('|serie')) pendientesSeries.push(comprimir(file).then((b) => guardarFoto(b)).then((id) => { destino[clave] = id; }).catch(() => {}));
    estado.hidden = false;
    estado.className = 'estado-lectura';
    estado.textContent = 'Leyendo la imagen… (la primera vez puede tardar unos segundos)';
    try {
      const { mejor, opciones } = await leerEtiqueta(file, clave.endsWith('|serie'));
      candidatos[clave] = opciones;
      if (mejor) {
        input.value = mejor;
        estado.className = 'estado-lectura ok' + (opciones.length > 1 ? ' toca' : '');
        estado.textContent = opciones.length > 1 ? '✓ Leído. ¿No es el correcto? Toca aquí para elegir otro.' : '✓ Leído. Revisa que esté correcto.';
      } else if (opciones.length) {
        estado.className = 'estado-lectura toca';
        estado.textContent = 'Elige el valor correcto. Toca aquí para ver las opciones otra vez.';
        mostrarOpciones(clave, input, estado);
      } else {
        estado.className = 'estado-lectura mal';
        estado.textContent = 'No se encontró texto ni código. Acerca la cámara, usa más luz o escríbelo a mano.';
      }
    } catch (e) {
      estado.className = 'estado-lectura mal';
      estado.textContent = 'No se pudo leer la imagen. Escríbelo a mano.';
    }
  }

  async function mostrarOpciones(clave, input, estado) {
    const op = candidatos[clave] || [];
    if (!op.length) return;
    const [nombre, campo] = clave.split('|');
    const i = await elegir(`${etiqueta(nombre, tipo)}: ¿cuál es ${campo === 'modelo' ? 'el modelo' : 'el N° de serie'}?`, op);
    if (i === null) return;
    input.value = op[i];
    estado.className = 'estado-lectura ok toca';
    estado.textContent = '✓ Valor elegido. Toca aquí para cambiarlo.';
  }

  function leerPiezas() {
    const r = {};
    for (const [n, ui] of Object.entries(piezasUI)) r[n] = { modelo: ui.modelo.input.value.trim(), serie: ui.serie.input.value.trim() };
    return r;
  }

  function construirPiezas(valores = {}) {
    for (const k of Object.keys(piezasUI)) delete piezasUI[k];
    const nombres = POR_TIPO[tipo] || [];
    if (!nombres.length) {
      contenedorPiezas.replaceChildren(el('div', { class: 'vacio' }, 'Elige POS o SSCO para ver sus componentes.'));
      return;
    }
    contenedorPiezas.replaceChildren(
      el('div', { class: 'etiqueta' }, 'Componentes · deja vacío lo que no tenga'),
      ...nombres.map((n) => {
        const ui = { modelo: campoLectura(n, 'modelo'), serie: campoLectura(n, 'serie') };
        ui.modelo.input.value = valores[n]?.modelo || '';
        ui.serie.input.value = valores[n]?.serie || '';
        piezasUI[n] = ui;
        return el('div', { class: 'componente' }, el('h4', {}, etiqueta(n, tipo)), ui.modelo.nodo, ui.serie.nodo);
      }));
  }

  const edtNotas = el('textarea', { rows: 2, placeholder: 'Opcional' });
  const edtEstado = el('textarea', { rows: 2, placeholder: 'Observación: cómo quedó el POS' });

  // ---------- Fotos del POS ----------
  const fotosUI = {};
  const contenedorFotos = el('div', { style: 'display:flex;flex-direction:column;gap:10px' });
  /** Trabajo que manda en las fotos: el de la caja que se edita o el elegido al entrar. */
  const trabajoFotos = () => (editandoId !== null && cajas.find((c) => c.id === editandoId)?.trabajo) || trabajo;
  /** Espacios de foto: los del trabajo (Retiro: cómo quedó, dónde quedó y adicional) y cualquier otra que ya tenga la caja. */
  function espaciosFoto() {
    const base = fotosDe(trabajoFotos());
    return [...base, ...FOTOS_POS.filter((f) => !base.includes(f) && fotosForm[f[0]])];
  }
  function construirFotos() {
    for (const k of Object.keys(fotosUI)) delete fotosUI[k];
    contenedorFotos.replaceChildren(...espaciosFoto().map(([k, nombre], i) => {
      const mini = el('button', { class: 'mini', type: 'button', 'aria-label': `Ver foto ${nombre}`, onclick: () => (fotosForm[k] ? verFoto(fotosForm[k]) : sacarFotoPos(k)) });
      const estado = el('span', {}, 'Sin foto');
      const quitar = el('button', { class: 'boton-texto rojo', type: 'button', hidden: true, style: 'padding:4px 0', onclick: () => { delete fotosForm[k]; pintarFotos(); } }, 'Quitar foto');
      fotosUI[k] = { mini, estado, quitar };
      return el('div', { class: 'foto-pos' }, mini,
        el('div', { class: 'info' }, el('b', {}, `${i + 1}. ${nombre}`), estado, el('div', {}, quitar)),
        el('button', { class: 'camara', type: 'button', 'aria-label': `Foto: ${nombre}`, onclick: () => sacarFotoPos(k) }, '📷'));
    }));
    pintarFotos();
  }

  async function sacarFotoPos(k) {
    const file = await pedirImagen();
    if (!file) return;
    fotosUI[k].estado.textContent = 'Guardando foto…';
    try {
      fotosForm[k] = await guardarFoto(await comprimir(file));
    } catch (_) {
      aviso('No se pudo guardar la foto. Inténtalo de nuevo.');
    }
    pintarFotos();
  }

  const urls = [];
  async function pintarFotos() {
    urls.splice(0).forEach((u) => URL.revokeObjectURL(u));
    for (const [k, ui] of Object.entries(fotosUI)) {
      const blob = fotosForm[k] ? await leerFoto(fotosForm[k]) : null;
      if (blob) {
        const u = URL.createObjectURL(blob);
        urls.push(u);
        ui.mini.style.backgroundImage = `url("${u}")`;
        ui.estado.textContent = '✓ Foto lista · tócala para verla';
        ui.estado.className = 'ok';
      } else {
        ui.mini.style.backgroundImage = '';
        ui.estado.textContent = 'Sin foto';
        ui.estado.className = '';
      }
      ui.quitar.hidden = !blob;
    }
  }

  async function verFoto(id) {
    const b = await leerFoto(id);
    if (b) window.open(URL.createObjectURL(b), '_blank');
  }

  // ---------- Guardar ----------
  const btnGuardar = el('button', { class: 'boton principal ancho', type: 'button', onclick: guardar }, 'GUARDAR CAJA');
  const btnCancelar = el('button', { class: 'boton-texto', type: 'button', onclick: terminarEdicion }, 'Cancelar edición');
  const btnBorrar = el('button', { class: 'boton-texto rojo', type: 'button', onclick: borrar }, 'Borrar caja');
  const barraEdicion = el('div', { class: 'acciones', hidden: true }, btnCancelar, btnBorrar);

  function leerFormulario(id) {
    const piezas = {};
    for (const [n, p] of Object.entries(leerPiezas())) if (p.modelo || p.serie) piezas[n] = p;
    const original = cajas.find((c) => c.id === id && editandoId !== null);
    return {
      id, fecha: deIso(edtFecha.value) || hoy(), trabajo: original?.trabajo || trabajo,
      cliente: clienteActual(), tienda: edtTienda.value.trim(), direccion: edtDireccion.value.trim(), region: edtRegion.value.trim(),
      tipo, ubicacion: edtUbicacion.value.trim(), numeroPos: edtNumeroPos.value.trim(), piezas,
      notas: edtNotas.value.trim(), estado: edtEstado.value.trim(),
      fotos: Object.fromEntries(Object.entries(fotosForm).filter(([k]) => espaciosFoto().some(([f]) => f === k))),
      fotosSeries: Object.fromEntries(Object.entries(fotosSeriesForm).filter(([k]) => (POR_TIPO[tipo] || []).includes(k.split('|')[0]))),
    };
  }

  async function guardar() {
    await Promise.all(pendientesSeries.splice(0));
    const c = leerFormulario(editandoId ?? Date.now());
    if (!c.trabajo) { await elegirTrabajo(false); return; }
    if (!c.tipo) { aviso('Elige si la caja es POS o SSCO.'); return; }
    if (!Object.keys(c.piezas).length) { aviso('Completa el modelo o la serie de al menos un componente.'); return; }
    if (cliente === OTROS && !c.cliente) { aviso('Escribe el nombre del cliente o elige otro.'); clienteOtro.focus(); return; }

    const repetidas = [];
    for (const [n, p] of Object.entries(c.piezas)) {
      if (!p.serie) continue;
      const otra = cajas.find((o) => o.id !== c.id && Object.values(o.piezas || {}).some((q) => (q.serie || '').toLowerCase() === p.serie.toLowerCase()));
      if (otra) repetidas.push(`${p.serie} (${etiqueta(n, c.tipo)}) · ya está en ${otra.tipo} ${otra.numeroPos || ''} ${otra.ubicacion || ''}`.replace(/ +/g, ' ').trim());
    }
    if (repetidas.length && !(await confirmar('Serie repetida', `Estas series ya están en la planilla:\n\n${repetidas.join('\n')}\n\n¿Guardar igual?`, 'Guardar igual', 'Revisar'))) return;

    if (editandoId !== null) {
      cajas = cajas.map((o) => (o.id === c.id ? c : o));
      await guardarCajas(cajas);
      aviso('Cambios guardados');
      await terminarEdicion();
    } else {
      cajas = [...cajas, c];
      await guardarCajas(cajas);
      await db.set('kv', 'tienda', { cliente: c.cliente, tienda: c.tienda, direccion: c.direccion, region: c.region, tipo: c.tipo });
      aviso(`Caja ${cajas.length} guardada`);
      limpiarCaja();
      $('#seccion-caja')?.scrollIntoView({ behavior: 'smooth' });
    }
  }

  function limpiarCaja() {
    edtUbicacion.value = '';
    edtNumeroPos.value = '';
    edtNotas.value = '';
    edtEstado.value = '';
    fotosForm = {};
    fotosSeriesForm = {};
    construirPiezas({});
    editandoId = null;
    construirFotos();
    btnGuardar.textContent = 'GUARDAR CAJA';
    barraEdicion.hidden = true;
    pintarLista();
    limpiarFotos(cajas.flatMap((c) => [...Object.values(c.fotos || {}), ...Object.values(c.fotosSeries || {})])).catch(() => {});
  }

  async function terminarEdicion() {
    editandoId = null;
    const t = (await db.get('kv', 'tienda')) || {};
    ponerTienda(t);
    tipo = t.tipo || '';
    pintarTipo();
    edtFecha.value = aIso(fechaDeTrabajo || hoy());
    limpiarCaja();
  }

  function editar(c) {
    if (editandoId === null) fechaDeTrabajo = deIso(edtFecha.value);
    editandoId = c.id;
    ponerTienda(c);
    edtFecha.value = aIso(c.fecha) || aIso(hoy());
    fotosForm = { ...(c.fotos || {}) };
    fotosSeriesForm = { ...(c.fotosSeries || {}) };
    tipo = c.tipo;
    pintarTipo();
    construirPiezas(c.piezas);
    edtUbicacion.value = c.ubicacion || '';
    edtNumeroPos.value = c.numeroPos || '';
    edtNotas.value = c.notas || '';
    edtEstado.value = c.estado || '';
    construirFotos();
    btnGuardar.textContent = 'GUARDAR CAMBIOS';
    barraEdicion.hidden = false;
    pintarLista();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  async function borrar() {
    if (editandoId === null) return;
    if (!(await confirmar('¿Borrar esta caja?', 'Se borra la fila completa. No se puede deshacer.', 'Borrar'))) return;
    cajas = cajas.filter((c) => c.id !== editandoId);
    await guardarCajas(cajas);
    aviso('Caja borrada');
    await terminarEdicion();
  }

  // ---------- Planilla ----------
  const lista = el('div', { class: 'lista' });
  const vacia = el('div', { class: 'vacio' }, 'Todavía no hay cajas. Completa los datos, elige POS o SSCO y toca «Guardar caja». ¿Ya tenías un Excel empezado? Toca «Cargar Excel».');

  function pintarLista() {
    contador.textContent = cajas.length === 1 ? '1 caja' : `${cajas.length} cajas`;
    vacia.hidden = cajas.length > 0;
    lista.replaceChildren(...cajas.map((c, i) => {
      const codigos = COMPONENTES.filter((n) => c.piezas?.[n] && (c.piezas[n].modelo || c.piezas[n].serie))
        .map((n) => `${etiqueta(n, c.tipo)}: ${[c.piezas[n].modelo, c.piezas[n].serie && `S/N ${c.piezas[n].serie}`].filter(Boolean).join(' · ')}`).join('\n');
      const nf = Object.keys(c.fotos || {}).length;
      return el('button', { class: 'item' + (c.id === editandoId ? ' editando' : ''), type: 'button', onclick: () => editar(c) },
        el('span', { class: 'num' }, String(i + 1)),
        el('span', { class: 'cuerpo' },
          el('b', {}, [c.trabajo, c.tipo + (c.numeroPos ? ` N° ${c.numeroPos}` : ''), c.ubicacion].filter(Boolean).join(' · ')),
          el('span', { class: 'codigos' }, codigos || 'Sin componentes'),
          el('span', { class: 'detalle', style: 'display:block' },
            [[c.cliente, c.tienda && `N° ${c.tienda}`].filter(Boolean).join(' '), c.fecha, nf ? `📷 ${nf}` : '', c.notas, c.estado && `Estado: ${c.estado}`].filter(Boolean).join(' · '))));
    }));
  }

  async function nuevoExcel() {
    if (cajas.length) {
      const i = await elegir('¿Empezar un Excel nuevo?', ['Empezar nuevo', 'Exportar primero'],
        { mensaje: `La planilla actual tiene ${cajas.length} cajas y se borrará de este teléfono.` });
      if (i === 1) { exportar(); return; }
      if (i !== 0) return;
    }
    cajas = [];
    await guardarCajas(cajas);
    await db.set('kv', 'tienda', {});
    editandoId = null;
    ponerTienda({});
    tipo = '';
    pintarTipo();
    edtFecha.value = aIso(hoy());
    limpiarCaja();
    aviso('Planilla nueva lista');
  }

  function cargarExcel() {
    const input = el('input', { type: 'file', accept: '.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    input.addEventListener('change', async () => {
      const f = input.files?.[0];
      if (!f) return;
      try {
        const leidas = await conEspera('Leyendo el Excel…', async () => {
          const r = leerXlsx(new Uint8Array(await f.arrayBuffer()));
          for (const l of r) {
            for (const [k, bytes] of Object.entries(l.fotos)) l.caja.fotos[k] = await guardarFoto(new Blob([bytes], { type: 'image/jpeg' }));
            l.caja.fotosSeries = {};
            for (const [k, bytes] of Object.entries(l.fotosSeries || {})) l.caja.fotosSeries[k] = await guardarFoto(new Blob([bytes], { type: 'image/jpeg' }));
          }
          return r.map((l) => l.caja);
        });
        if (!leidas.length) { aviso('El Excel no tiene filas con datos.'); return; }
        let reemplazar = true;
        if (cajas.length) {
          const i = await elegir(`Ya tienes ${cajas.length} cajas en la planilla`, [`Reemplazar por el Excel (${leidas.length} cajas)`, `Agregar las ${leidas.length} cajas al final`]);
          if (i === null) return;
          reemplazar = i === 0;
        }
        cajas = reemplazar ? leidas : [...cajas, ...leidas];
        await guardarCajas(cajas);
        const u = leidas[leidas.length - 1];
        ponerTienda(u);
        tipo = u.tipo;
        pintarTipo();
        limpiarCaja();
        const nf = leidas.reduce((a, c) => a + Object.keys(c.fotos).length + Object.keys(c.fotosSeries || {}).length, 0);
        aviso(`Excel cargado: ${leidas.length} cajas${nf ? ` y ${nf} fotos` : ''}.`);
      } catch (e) {
        await elegir('No se pudo cargar', [], { mensaje: e.message || 'Revisa que sea un archivo .xlsx.', cancelar: 'Entendido' });
      }
    });
    input.click();
  }

  async function exportar() {
    if (!cajas.length) { aviso('La planilla está vacía.'); return; }
    const bytes = await conEspera(`Preparando el Excel con ${cajas.length} cajas…`, async () => {
      const hojas = await hojasInventario(cajas, async (id) => {
        const b = await leerFoto(id);
        return b ? new Uint8Array(await b.arrayBuffer()) : null;
      });
      return escribirXlsx(hojas);
    });
    const u = cajas[cajas.length - 1];
    const d = new Date();
    const fecha = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}_${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}`;
    const nombre = ['inventario', u.cliente, u.tienda, fecha].filter(Boolean).join('_').replace(/[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ_-]+/g, '-') + '.xlsx';
    const tipoMime = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    const i = await elegir(`Excel listo (${cajas.length} cajas)`, ['Compartir (WhatsApp, correo, Drive…)', 'Descargar al teléfono'], { mensaje: nombre });
    if (i === 0) await compartirArchivo(bytes, nombre, tipoMime, 'Inventario de cajas');
    else if (i === 1) descargarArchivo(bytes, nombre, tipoMime);
  }

  // ---------- Fotos de series (se envían aparte del Excel) ----------
  async function enviarFotosSeries() {
    const conFotos = cajas.filter((c) => FotosSeries.cuantas(c) > 0);
    if (!conFotos.length) {
      await elegir('Fotos de series', [], { mensaje: 'Todavía no hay fotos de series guardadas.\n\nSe guardan solas cuando sacas (o eliges) la foto de la serie de un componente y luego tocas «Guardar caja».', cancelar: 'Entendido' });
      return;
    }
    const total = conFotos.reduce((a, c) => a + FotosSeries.cuantas(c), 0);
    const opciones = [`Todas las cajas (${total} fotos)`, ...conFotos.map((c) => {
      const n = FotosSeries.cuantas(c);
      return `${c.trabajo || 'Sin trabajo'} · ${[c.tipo + (c.numeroPos ? ` N° ${c.numeroPos}` : ''), c.ubicacion].filter(Boolean).join(' · ')} (${n} ${n === 1 ? 'foto' : 'fotos'})`;
    })];
    const i = await elegir('Enviar fotos de series', opciones);
    if (i === null) return;
    const elegidas = i === 0 ? conFotos : [conFotos[i - 1]];
    const archivos = await conEspera('Preparando fotos…', () => FotosSeries.preparar(elegidas));
    if (!archivos.length) { aviso('No se encontraron las fotos.'); return; }
    const c0 = elegidas[0];
    const titulo = `Fotos de series · ${[c0.cliente, c0.tienda ? `Tienda ${c0.tienda}` : ''].filter(Boolean).join(' ')} · ${archivos.length} fotos`;
    if (navigator.canShare?.({ files: archivos })) {
      try { await navigator.share({ files: archivos, title: titulo }); return; } catch (e) { if (e?.name === 'AbortError') return; }
    }
    // Sin «Compartir» (computador): se descargan juntas en un .zip.
    const zip = window.fflate.zipSync(Object.fromEntries(await Promise.all(archivos.map(async (f) => [f.name, new Uint8Array(await f.arrayBuffer())]))), { level: 0 });
    descargarArchivo(zip, 'fotos_series.zip', 'application/zip');
  }

  // ---------- Retiro / Instalación ----------
  async function elegirTrabajo(obligatorio) {
    const i = await elegir('¿Qué trabajo vas a hacer?', [RETIRO, INSTALACION],
      { cancelar: obligatorio ? 'Volver al inicio' : 'Cancelar', obligatorio });
    if (i === null) {
      if (obligatorio) location.hash = '#inicio';
      return;
    }
    trabajo = [RETIRO, INSTALACION][i];
    chipTrabajo.textContent = `${trabajo} ▾`;
    if (editandoId === null) construirFotos();
    aviso(`Trabajo: ${trabajo}`);
  }

  // ---------- Armar pantalla ----------
  const campo = (rotulo, input) => el('div', {}, el('div', { class: 'etiqueta', style: 'margin-bottom:6px' }, rotulo), el('div', { class: 'campo' }, input));
  app.replaceChildren(
    cabecera('Inventario equipos', { debajo: chipTrabajo, derecha: contador }),
    el('div', { class: 'contenido' },
      el('div', { class: 'seccion' }, 'Tienda'),
      el('div', { class: 'tarjeta' },
        el('div', { class: 'etiqueta' }, 'Cliente'), chipsCliente, cajaOtro, cajaTienda,
        campo('Dirección de la tienda', edtDireccion),
        el('div', {}, el('div', { class: 'etiqueta', style: 'margin-bottom:6px' }, 'Región'), campoRegion),
        campo('Fecha', edtFecha)),
      el('div', { class: 'seccion', id: 'seccion-caja' }, 'Caja'),
      el('div', { class: 'tarjeta' },
        el('div', { class: 'etiqueta' }, 'Tipo de caja'), segmento,
        campo('Ubicación POS', edtUbicacion),
        campo('N° de POS', edtNumeroPos),
        contenedorPiezas,
        campo('Notas', edtNotas),
        campo('Estado del POS', edtEstado),
        el('div', { class: 'etiqueta', style: 'margin-top:6px' }, 'Fotos del POS'),
        el('p', { class: 'ayuda' }, 'Saca las que correspondan. Las que no saques quedan en blanco en el Excel.'),
        contenedorFotos,
        btnGuardar, barraEdicion),
      el('div', { class: 'seccion' }, 'Planilla', el('small', {}, 'Toca una caja para editarla')),
      el('div', { class: 'acciones', style: 'margin-bottom:10px' },
        el('button', { class: 'boton borde', type: 'button', onclick: nuevoExcel }, 'Nuevo Excel'),
        el('button', { class: 'boton borde', type: 'button', onclick: cargarExcel }, 'Cargar Excel')),
      el('button', { class: 'boton borde ancho', type: 'button', style: 'margin-bottom:10px', onclick: enviarFotosSeries }, '📷  Enviar fotos de series'),
      vacia, lista),
    el('div', { class: 'barra-inferior' }, el('div', {}, el('button', { class: 'boton acento', type: 'button', onclick: exportar }, 'EXPORTAR EXCEL'))));

  ponerTienda(tienda);
  tipo = tienda.tipo || '';
  pintarTipo();
  construirPiezas({});
  edtFecha.value = aIso(hoy());
  construirFotos();
  pintarLista();
  elegirTrabajo(true);
}
