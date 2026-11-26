// Editor para marcar una imagen de la OT (lápiz, círculo, flecha) y dejarle un comentario.
import { el, confirmar } from './base.js';

const COLORES = ['#E53935', '#FDD835', '#1E5BFF', '#43A047', '#FFFFFF'];

/**
 * Abre el editor a pantalla completa.
 * Devuelve null si se cancela, o { blob, comentario } (blob es null si no se dibujó nada).
 */
export function marcarImagen(blobOriginal, comentarioInicial = '', titulo = 'Marcar imagen') {
  return new Promise((resolver) => {
    const url = URL.createObjectURL(blobOriginal);
    const img = new Image();
    const lienzo = el('canvas', { class: 'marcar-lienzo' });
    const ctx = lienzo.getContext('2d');
    const zona = el('div', { class: 'marcar-zona' }, lienzo);
    const comentario = el('textarea', { rows: 2, placeholder: 'Comentario (opcional, sale bajo la imagen en el PDF)' });
    comentario.value = comentarioInicial;
    const trazos = [];
    let actual = null;
    let herramienta = 'lapiz';
    let color = COLORES[0];

    const ancho = () => Math.max(lienzo.width, lienzo.height) * 0.009;
    function dibujarTrazo(t) {
      const ruta = new Path2D();
      const a = t.puntos[0];
      const z = t.puntos[t.puntos.length - 1];
      if (t.h === 'lapiz') {
        ruta.moveTo(a[0], a[1]);
        if (t.puntos.length === 1) ruta.lineTo(a[0] + 0.1, a[1]);
        for (const p of t.puntos.slice(1)) ruta.lineTo(p[0], p[1]);
      } else if (t.h === 'circulo') {
        ruta.ellipse((a[0] + z[0]) / 2, (a[1] + z[1]) / 2, Math.abs(z[0] - a[0]) / 2, Math.abs(z[1] - a[1]) / 2, 0, 0, Math.PI * 2);
      } else {
        ruta.moveTo(a[0], a[1]); ruta.lineTo(z[0], z[1]);
        const ang = Math.atan2(z[1] - a[1], z[0] - a[0]);
        const largo = t.ancho * 4.5;
        for (const d of [-0.5, 0.5]) { ruta.moveTo(z[0], z[1]); ruta.lineTo(z[0] - largo * Math.cos(ang + d), z[1] - largo * Math.sin(ang + d)); }
      }
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      ctx.strokeStyle = 'rgba(0,0,0,.35)'; ctx.lineWidth = t.ancho * 1.6; ctx.stroke(ruta);
      ctx.strokeStyle = t.color; ctx.lineWidth = t.ancho; ctx.stroke(ruta);
    }
    function pintar() {
      ctx.drawImage(img, 0, 0, lienzo.width, lienzo.height);
      for (const t of trazos) dibujarTrazo(t);
      if (actual) dibujarTrazo(actual);
    }
    function ajustar() {
      if (!img.naturalWidth) return;
      const r = zona.getBoundingClientRect();
      const e = Math.min(r.width / lienzo.width, r.height / lienzo.height);
      lienzo.style.width = `${lienzo.width * e}px`;
      lienzo.style.height = `${lienzo.height * e}px`;
    }
    const punto = (ev) => {
      const r = lienzo.getBoundingClientRect();
      const x = ((ev.clientX - r.left) / r.width) * lienzo.width;
      const y = ((ev.clientY - r.top) / r.height) * lienzo.height;
      return [Math.min(Math.max(x, 0), lienzo.width), Math.min(Math.max(y, 0), lienzo.height)];
    };
    lienzo.addEventListener('pointerdown', (ev) => {
      ev.preventDefault();
      lienzo.setPointerCapture(ev.pointerId);
      actual = { h: herramienta, color, ancho: ancho(), puntos: [punto(ev)] };
      pintar();
    });
    lienzo.addEventListener('pointermove', (ev) => {
      if (!actual) return;
      const p = punto(ev);
      if (actual.h === 'lapiz') actual.puntos.push(p); else actual.puntos[1] = p;
      pintar();
    });
    const terminar = () => {
      if (!actual) return;
      const a = actual.puntos[0];
      const z = actual.puntos[actual.puntos.length - 1];
      if (actual.h === 'lapiz' || Math.hypot(z[0] - a[0], z[1] - a[1]) > actual.ancho * 2) trazos.push(actual);
      actual = null;
      pintar();
    };
    lienzo.addEventListener('pointerup', terminar);
    lienzo.addEventListener('pointercancel', () => { actual = null; pintar(); });

    const botonesH = [['lapiz', '✏️ Lápiz'], ['circulo', '⭕ Círculo'], ['flecha', '➚ Flecha']].map(([h, t]) =>
      el('button', { type: 'button', 'aria-pressed': String(h === herramienta), onclick: (e) => {
        herramienta = h;
        botonesH.forEach((b) => b.setAttribute('aria-pressed', String(b === e.currentTarget)));
      } }, t));
    const botonesC = COLORES.map((c) => el('button', {
      type: 'button', class: 'color', style: `background:${c}`, 'aria-label': `Color ${c}`, 'aria-pressed': String(c === color),
      onclick: (e) => { color = c; botonesC.forEach((b) => b.setAttribute('aria-pressed', String(b === e.currentTarget))); },
    }));

    const cerrar = (valor) => {
      window.removeEventListener('resize', ajustar);
      URL.revokeObjectURL(url);
      fondo.remove();
      document.body.classList.remove('sin-scroll');
      resolver(valor);
    };
    const cambio = () => trazos.length > 0 || comentario.value.trim() !== comentarioInicial.trim();
    const cancelar = async () => {
      if (!cambio() || (await confirmar('¿Salir sin guardar?', 'Se pierden las marcas y el comentario que hiciste en esta imagen.', 'Salir', 'Seguir editando'))) cerrar(null);
    };
    const listo = () => {
      const texto = comentario.value.trim();
      if (!trazos.length) { cerrar({ blob: null, comentario: texto }); return; }
      actual = null;
      pintar();
      lienzo.toBlob((b) => cerrar({ blob: b, comentario: texto }), 'image/jpeg', 0.88);
    };

    const fondo = el('div', { class: 'marcar', role: 'dialog', 'aria-label': titulo },
      el('div', { class: 'marcar-barra' },
        el('button', { type: 'button', class: 'boton-texto blanco', onclick: cancelar }, 'Cancelar'),
        el('strong', {}, titulo),
        el('button', { type: 'button', class: 'boton acento', onclick: listo }, 'Listo')),
      zona,
      el('div', { class: 'marcar-panel' },
        el('div', { class: 'marcar-fila' },
          el('div', { class: 'marcar-herramientas' }, botonesH),
          el('button', { type: 'button', class: 'boton-texto', onclick: () => { trazos.pop(); pintar(); } }, '↶ Deshacer')),
        el('div', { class: 'marcar-fila' }, el('span', { class: 'ayuda' }, 'Color:'), el('div', { class: 'marcar-colores' }, botonesC)),
        comentario));
    document.body.append(fondo);
    document.body.classList.add('sin-scroll');
    img.onload = () => {
      lienzo.width = img.naturalWidth;
      lienzo.height = img.naturalHeight;
      pintar();
      ajustar();
    };
    img.onerror = () => cerrar(null);
    img.src = url;
    window.addEventListener('resize', ajustar);
  });
}
