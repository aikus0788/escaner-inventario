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
retiro = ["Foto: Cómo quedó el equipo", "Foto: Dónde quedó el equipo", "Foto: Adicional", "Foto: Serie del POS", "Foto serie: POS/SSCO"]
instalacion = ["Foto: Serie del POS", "Foto: Cómo quedó el equipo", "Foto: Cableado del POS",
               "Foto: Boleta POS certificado", "Foto: Adicional"]

ret, ins = wb["Retiro"], wb["Instalación"]
enc_r = [c.value for c in ret[1]]
enc_i = [c.value for c in ins[1]]
assert enc_r[-5:] == retiro and enc_r[2] == "Trabajo", enc_r
assert enc_i[-5:] == instalacion and enc_i[2] == "Trabajo", enc_i
primera = len(enc_r) - 5
assert primera == len(enc_i) - 5

# Retiro: 3 cajas; la primera con sus 3 fotos y la tercera con una foto antigua de «Serie del POS»
assert ret.max_row == 4, ret.max_row
assert [ret.cell(r, 1).value for r in (2, 3, 4)] == ["1", "2", "3"]
assert all(ret.cell(r, 3).value == "Retiro" for r in (2, 3, 4))
assert sorted((i.anchor._from.row, i.anchor._from.col) for i in ret._images) == [(1, primera), (1, primera + 1), (1, primera + 2), (3, primera + 3), (3, primera + 4)]
# Instalación: 1 caja (N° 1) con la foto adicional
assert ins.max_row == 2 and ins.cell(2, 1).value == "1" and ins.cell(2, 3).value == "Instalación"
assert [(i.anchor._from.row, i.anchor._from.col) for i in ins._images] == [(1, primera + 4)]
assert len(medios) == 6

print("Excel de prueba OK: pestañas", wb.sheetnames, "·", len(ret._images) + len(ins._images), "fotos")
