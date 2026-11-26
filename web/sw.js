// Infomaxis web: guarda la app en el teléfono para que abra sin internet.
const VERSION = '__VERSION__';
const CACHE = `infomaxis-${VERSION}`;
const BASE = [
  './', 'index.html', 'app.css', 'manifest.webmanifest',
  'js/app.js', 'js/base.js', 'js/inventario.js', 'js/ot.js', 'js/lector.js', 'js/xlsx.js', 'js/otpdf.js', 'js/marcar.js',
  'vendor/zxing.min.js', 'vendor/fflate.min.js', 'vendor/pdf-lib.min.js',
  'vendor/tesseract/tesseract.min.js', 'vendor/tesseract/worker.min.js', 'vendor/tesseract/lang/eng.traineddata.gz',
  'img/logo_infomaxis.png', 'img/logo_ncr_voyix.jpg', 'img/logo_imx.png', 'img/apple-touch-icon.png', 'img/icono-192.png', 'img/icono-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(BASE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k.startsWith('infomaxis-') && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// Primero la red (para recibir versiones nuevas); sin internet, lo guardado.
// El lector de texto (archivos grandes) se guarda la primera vez que se usa.
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  const pesado = url.pathname.includes('/vendor/tesseract/core/');
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    if (pesado) {
      const guardada = await cache.match(e.request);
      if (guardada) return guardada;
    }
    try {
      const r = await fetch(e.request);
      if (r.ok) cache.put(e.request, r.clone());
      return r;
    } catch (err) {
      const guardada = await cache.match(e.request, { ignoreSearch: true });
      if (guardada) return guardada;
      if (e.request.mode === 'navigate') return cache.match('index.html');
      throw err;
    }
  })());
});
