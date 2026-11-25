"""Revisa con openpyxl el Excel de prueba que genera ExcelFotosTest."""
import sys
import zipfile
import xml.dom.minidom

import openpyxl

ruta = sys.argv[1]

# Todos los XML del archivo deben estar bien formados.
with zipfile.ZipFile(ruta) as z:
    assert z.testzip() is None
    for n in z.namelist():
        if n.endswith(".xml") or n.endswith(".rels"):
            xml.dom.minidom.parseString(z.read(n))
    medios = [n for n in z.namelist() if n.startswith("xl/media/")]

wb = openpyxl.load_workbook(ruta)
assert wb.sheetnames == ["Inventario"], wb.sheetnames
inv = wb["Inventario"]

enc = [c.value for c in inv[1]]
fotos = ["Foto: Serie del POS", "Foto: Cómo quedó el equipo", "Foto: Cableado del POS",
         "Foto: Boleta POS certificado", "Foto: Adicional"]
assert enc[-5:] == fotos, enc[-6:]
assert enc[2] == "Trabajo" and inv.cell(2, 3).value == "Retiro" and inv.cell(4, 3).value == "Instalación"
assert enc[-6] == "Estado POS" and inv.cell(2, len(enc) - 5).value == "Operativo, limpio"
primera = len(enc) - 5  # índice (desde 0) de la primera columna de fotos

assert len(inv._images) == 4 == len(medios), (len(inv._images), medios)
anclas = sorted((img.anchor._from.row, img.anchor._from.col) for img in inv._images)
esperadas = sorted([(1, primera), (1, primera + 1), (1, primera + 2), (3, primera + 4)])
assert anclas == esperadas, (anclas, esperadas)
# Celdas de fotos sin texto (en blanco).
for fila in range(2, 5):
    for col in range(primera + 1, primera + 6):
        assert inv.cell(fila, col).value is None
assert inv.row_dimensions[2].height and inv.row_dimensions[2].height > 100
assert not inv.row_dimensions[3].height  # caja sin fotos: alto normal

print("Excel de prueba OK:", len(inv._images), "fotos,", inv.max_row - 1, "cajas")
