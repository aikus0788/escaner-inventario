# Infomaxis

App Android con varios **módulos**; cada uno es una herramienta distinta y se abre desde la pantalla de inicio.

| Módulo | Para qué sirve |
|---|---|
| Inventario equipos | Registrar cajas POS y SSCO con modelo y serie de cada componente, y exportarlas a Excel. |
| Drive | Abrir el Drive de la empresa en el navegador para buscar y descargar archivos. |

Para agregar un módulo: crea su Activity, regístrala en `AndroidManifest.xml` y agrégala a `Modulos.todos` (`Modulos.kt`).

## Módulo Inventario equipos

App Android para registrar equipos (impresoras, notebooks, monitores…) sacando una foto a la etiqueta.
Lee códigos de barras, QR y texto impreso **dentro del teléfono, sin internet** (Google ML Kit),
y exporta todo a una planilla **Excel (.xlsx)** que se puede compartir por Drive, WhatsApp o correo.

## Descargar
Ve a **Releases** (columna derecha de esta página) y descarga `Infomaxis.apk`.

## Instalar en Android
1. Abre el archivo `Infomaxis.apk` en el teléfono.
2. Si Android lo pide, permite "Instalar apps de origen desconocido" para Chrome o el gestor de archivos.
3. Toca **Instalar**.

## Uso
1. **Tienda:** elige el cliente (o «Otros» y escribe el nombre), el N° de tienda, la dirección, la región y revisa la fecha.
2. **Caja:** elige **POS** o **SSCO**, escribe la ubicación (ej. Caja 3) y el **N° de POS**.
   - POS: el propio POS, pantalla, segunda pantalla, impresora, gaveta, teclado, scanner de mano y scanner de mesón.
   - SSCO: el propio SSCO, pantalla, impresora, scanner de mesón y scanner de mano.
3. En cada componente, toca la cámara junto a **Modelo** y **N° de serie** (o escríbelos). Deja vacío lo que la caja no tenga.
4. Toca **Guardar caja**: toda la caja queda en **una fila** del Excel.
5. **Exportar Excel** → compartir a Drive / WhatsApp / correo, o guardar en Descargas.
6. **Nuevo Excel** empieza una planilla vacía. **Cargar Excel** abre una planilla ya empezada para seguir llenándola.
7. **Buscar actualización** (en la pantalla de inicio) busca una versión nueva, la descarga y la instala si confirmas.

Cada teléfono guarda su propia planilla.

## Compilar
GitHub Actions compila la APK en cada cambio a `main` (ver `.github/workflows/build.yml`).

## Módulo Drive

Botón **Iniciar Google**: abre el navegador del teléfono en el inicio de sesión de Google. Al ingresar con la cuenta
de la empresa, el navegador queda en «Mi unidad» del Drive, donde se busca, se abre y se descarga.
