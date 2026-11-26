// Infomaxis web · inicio, navegación y módulo Drive.
import { $, el, db, elegir, aviso, VERSION, pedirGuardadoPersistente, cabecera } from './base.js';
import { pantallaInventario, resumenInventario } from './inventario.js';
import { pantallaOt, resumenOt } from './ot.js';

const app = $('#app');
const DRIVE = 'https://accounts.google.com/ServiceLogin?service=writely&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive';

const esIphone = () => /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const instalada = () => window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

async function inicio() {
  document.title = 'Infomaxis';
  const modulos = [
    { icono: '▦', titulo: 'Inventario equipos', desc: 'Registra cajas POS y SSCO con modelo y serie de cada componente, y expórtalas a Excel.', ruta: '#inventario', resumen: resumenInventario },
    { icono: '📝', titulo: 'OT trabajos', desc: 'Llena la orden de trabajo NCR Voyix, con firma del cliente, y genérala en PDF.', ruta: '#ot', resumen: resumenOt },
    { icono: '📁', titulo: 'Drive', desc: 'Abre el Drive de la empresa en el navegador para buscar y descargar archivos.', ruta: '#drive' },
  ];
  const tarjetas = [];
  for (const m of modulos) {
    let r = '';
    try { r = m.resumen ? await m.resumen() : ''; } catch (_) { r = ''; }
    tarjetas.push(el('button', { class: 'modulo', type: 'button', onclick: () => { location.hash = m.ruta; } },
      el('div', { class: 'icono', 'aria-hidden': 'true' }, m.icono),
      el('div', { class: 'texto' }, el('b', {}, m.titulo), el('p', {}, m.desc), r ? el('span', { class: 'resumen' }, r) : null),
      el('span', { class: 'flecha', 'aria-hidden': 'true' }, '›')));
  }
  const aviso_ = esIphone() && !instalada()
    ? el('div', { class: 'instalar' },
      el('b', {}, 'Instala Infomaxis en tu iPhone'),
      'En Safari, toca el botón Compartir ', el('b', { style: 'display:inline' }, '⬆︎'), ' y luego «Agregar a pantalla de inicio». Así queda como app y tus datos se guardan de forma segura.')
    : null;
  app.replaceChildren(
    el('div', { class: 'inicio-cab' }, el('img', { src: 'img/logo_infomaxis.png', alt: 'Logo Infomaxis' }), el('h1', {}, 'Infomaxis')),
    el('div', { class: 'franja' }),
    el('div', { class: 'contenido' },
      aviso_,
      el('div', { class: 'etiqueta', style: 'margin-bottom:10px' }, 'Módulos'),
      tarjetas,
      el('div', { class: 'acciones', style: 'margin-top:8px' },
        el('button', { class: 'boton borde', type: 'button', onclick: compartirApp }, 'Descarga app')),
      el('p', { class: 'pie' }, `Versión web ${VERSION} · se actualiza sola`)));
}

async function compartirApp() {
  const enlace = new URL('descargar.html', document.baseURI).href;
  const texto = `Descarga la app Infomaxis (Android o iPhone):\n${enlace}`;
  const i = await elegir('Descarga app', ['Compartir', 'WhatsApp', 'Copiar enlace'],
    { mensaje: `Enlace para instalar Infomaxis en otro teléfono. Detecta si es Android o iPhone.\n\n${enlace}` });
  if (i === 0) {
    if (navigator.share) navigator.share({ title: 'App Infomaxis', text: texto }).catch(() => {});
    else copiar(enlace);
  } else if (i === 1) {
    window.open(`https://wa.me/?text=${encodeURIComponent(texto)}`, '_blank');
  } else if (i === 2) {
    copiar(enlace);
  }
}

async function copiar(t) {
  try { await navigator.clipboard.writeText(t); aviso('Enlace copiado'); } catch (_) { prompt('Copia el enlace:', t); }
}

function drive() {
  document.title = 'Drive · Infomaxis';
  app.replaceChildren(
    cabecera('Drive'),
    el('div', { class: 'contenido', style: 'text-align:center' },
      el('div', { style: 'font-size:64px;margin-top:24px' }, '📁'),
      el('h2', {}, 'Drive de la empresa'),
      el('p', { style: 'color:var(--gris)' }, 'Se abrirá la página de Google. Ingresa el correo y la contraseña de la empresa y quedarás dentro del Drive para buscar y descargar archivos.'),
      el('a', { class: 'boton principal ancho', href: DRIVE, target: '_blank', rel: 'noopener', style: 'display:flex;align-items:center;justify-content:center;text-decoration:none;margin-top:16px' }, 'Iniciar Google'),
      el('p', { class: 'ayuda', style: 'margin-top:14px' }, 'Si el navegador ya tiene la sesión iniciada, entrarás directo al Drive.')));
}

async function navegar() {
  const ruta = location.hash || '#inicio';
  window.scrollTo(0, 0);
  if (ruta === '#inventario') await pantallaInventario(app);
  else if (ruta === '#ot') await pantallaOt(app);
  else if (ruta === '#drive') drive();
  else await inicio();
}

window.addEventListener('hashchange', navegar);
window.addEventListener('load', () => {
  pedirGuardadoPersistente();
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
  navegar();
});

export { db };
