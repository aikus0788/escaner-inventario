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
1. Elige el tipo de equipo.
2. Toca la cámara junto a **Modelo** y fotografía la etiqueta. Repite para **N° de serie**.
3. Revisa el valor leído (si hay varias opciones, eliges la correcta) y toca **Guardar fila**.
4. **Exportar Excel** → compartir a Drive / WhatsApp / correo, o guardar en Descargas.

Cada teléfono guarda su propia planilla.

## Compilar
GitHub Actions compila la APK en cada cambio a `main` (ver `.github/workflows/build.yml`).
