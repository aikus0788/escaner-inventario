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
2. **Caja:** elige **POS** o **SSCO** y escribe la ubicación (ej. Caja 3).
   - POS: pantalla, segunda pantalla, impresora, gaveta, teclado, scanner de mano y scanner de mesón.
   - SSCO: pantalla, impresora, scanner de mesón y scanner de mano.
3. En cada componente, toca la cámara junto a **Modelo** y **N° de serie** (o escríbelos). Deja vacío lo que la caja no tenga.
4. Toca **Guardar caja**: toda la caja queda en **una fila** del Excel.
5. **Exportar Excel** → compartir a Drive / WhatsApp / correo, o guardar en Descargas.
6. **Nuevo Excel** empieza una planilla vacía. **Cargar Excel** abre una planilla ya empezada para seguir llenándola.
7. **Actualizar** (arriba a la derecha) busca una versión nueva, la descarga y la instala si confirmas.

Cada teléfono guarda su propia planilla.

## Compilar
GitHub Actions compila la APK en cada cambio a `main` (ver `.github/workflows/build.yml`).
