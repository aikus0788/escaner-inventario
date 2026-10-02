# Escáner de Inventario

App Android para registrar equipos (impresoras, notebooks, monitores…) sacando una foto a la etiqueta.
Lee códigos de barras, QR y texto impreso **dentro del teléfono, sin internet** (Google ML Kit),
y exporta todo a una planilla **Excel (.xlsx)** que se puede compartir por Drive, WhatsApp o correo.

## Descargar
Ve a **Releases** (columna derecha de esta página) y descarga `EscanerInventario.apk`.

## Instalar en Android
1. Abre el archivo `EscanerInventario.apk` en el teléfono.
2. Si Android lo pide, permite "Instalar apps de origen desconocido" para Chrome o el gestor de archivos.
3. Toca **Instalar**.

## Uso
1. **Tienda:** elige el cliente (o «Otros» y escribe el nombre), el N° de tienda, la dirección, la región y revisa la fecha.
2. **Equipo:** elige el tipo (POS, pantalla, impresora, gaveta…), toca la cámara junto a **Modelo** y luego junto a **N° de serie**.
3. Escribe la **Ubicación POS**, revisa los valores leídos y toca **Guardar fila**. Los datos de la tienda se mantienen para el siguiente equipo.
4. **Exportar Excel** → compartir a Drive / WhatsApp / correo, o guardar en Descargas.
5. **Nuevo Excel** empieza una planilla vacía. **Cargar Excel** abre una planilla ya empezada para seguir llenándola.

Cada teléfono guarda su propia planilla.

## Compilar
GitHub Actions compila la APK en cada cambio a `main` (ver `.github/workflows/build.yml`).
