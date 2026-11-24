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
assert wb.sheetnames == ["Inventario", "Fotos"], wb.sheetnames
inv, fot = wb["Inventario"], wb["Fotos"]

enc = [c.value for c in inv[1]]
assert enc[-2:] == ["Estado POS", "Fotos"], enc[-3:]
assert inv.cell(2, len(enc)).value == "Ver 3 fotos"
assert inv.cell(2, len(enc)).hyperlink.location == "'Fotos'!A2"
assert inv.cell(3, len(enc)).value is None
assert inv.cell(2, len(enc) - 1).value == "Operativo, limpio"

assert [c.value for c in fot[1]] == ["N° caja", "Caja", "Foto de", "Valor", "Foto", "clave"]
assert fot.column_dimensions["F"].hidden
assert len(fot._images) == 4 == len(medios), (len(fot._images), medios)
filas = sorted(img.anchor._from.row for img in fot._images)
assert filas == [1, 2, 3, 4], filas
assert fot["C2"].value == "POS - Serie" and fot["D2"].value == "41A2B3C", (fot["C2"].value, fot["D2"].value)
assert fot["A2"].hyperlink.location == "'Inventario'!A2"
assert fot["A5"].value == "3" and fot["F5"].value == "Impresora|modelo"

print("Excel de prueba OK:", len(fot._images), "fotos,", inv.max_row - 1, "cajas")
