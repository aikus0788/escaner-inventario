"""Revisa con openpyxl el Excel de prueba que genera ExcelFotosTest."""
import sys
import zipfile
import xml.dom.minidom

import openpyxl

ruta = sys.argv[1]

with zipfile.ZipFile(ruta) as z:
    assert z.testzip() is None
    for n in z.namelist():
        if n.endswith(".xml") or n.endswith(".rels"):
            xml.dom.minidom.parseString(z.read(n))
    medios = [n for n in z.namelist() if n.startswith("xl/media/")]

wb = openpyxl.load_workbook(ruta)
assert wb.sheetnames == ["Retiro", "Instalación"], wb.sheetnames
fotos = ["Foto: Serie del POS", "Foto: Cómo quedó el equipo", "Foto: Cableado del POS",
         "Foto: Boleta POS certificado", "Foto: Adicional"]

ret, ins = wb["Retiro"], wb["Instalación"]
for h in (ret, ins):
    enc = [c.value for c in h[1]]
    assert enc[-5:] == fotos and enc[2] == "Trabajo", enc
primera = len([c for c in ret[1]]) - 5

# Retiro: 2 cajas (N° 1 y 2), la primera con 3 fotos
assert ret.max_row == 3, ret.max_row
assert [ret.cell(r, 1).value for r in (2, 3)] == ["1", "2"]
assert all(ret.cell(r, 3).value == "Retiro" for r in (2, 3))
assert sorted((i.anchor._from.row, i.anchor._from.col) for i in ret._images) == [(1, primera), (1, primera + 1), (1, primera + 2)]
# Instalación: 1 caja (N° 1) con la foto adicional
assert ins.max_row == 2 and ins.cell(2, 1).value == "1" and ins.cell(2, 3).value == "Instalación"
assert [(i.anchor._from.row, i.anchor._from.col) for i in ins._images] == [(1, primera + 4)]
assert len(medios) == 4

print("Excel de prueba OK: pestañas", wb.sheetnames, "·", len(ret._images) + len(ins._images), "fotos")
