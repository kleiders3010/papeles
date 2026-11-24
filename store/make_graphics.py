"""Play icon (512x512) and feature graphic (1024x500), drawn from the launcher icon's own vector paths
(app/android/src/main/res/drawable/ic_launcher_foreground.xml: a sheet with a folded corner and three lines,
background from values/colors.xml). Run: python store/make_graphics.py [es] (es writes the Spanish feature graphic)."""
import re
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright

HERE = Path(__file__).parent
res = HERE.parent / "app/android/src/main/res"
xml = (res / "drawable/ic_launcher_foreground.xml").read_text(encoding="utf-8")
sheet, fold, lines = re.findall(r'pathData="([^"]+)"', xml)
FOLD = re.findall(r'fillColor="([^"]+)"', xml)[1]
BG = re.search(r'name="ic_launcher_background">([^<]+)<', (res / "values/colors.xml").read_text(encoding="utf-8")).group(1)
# the 108-unit adaptive canvas; the visible icon is its middle 72 units (18..90)
icon = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="{{s}}" height="{{s}}"><rect x="0" y="0" width="108" height="108" fill="{BG}"/>'
        f'<path fill="#FFFFFF" d="{sheet}"/><path fill="{FOLD}" d="{fold}"/>'
        f'<path fill="none" stroke="{BG}" stroke-width="3" stroke-linecap="round" d="{lines}"/></svg>')

es = len(sys.argv) > 1 and sys.argv[1] == "es"
line1, line2 = (("Leé, completá, firmá y escaneá", "Tus archivos quedan en tu celular") if es else
                ("Read, fill, sign and scan PDFs", "Your files stay on your phone"))
page_icon = f'<html><body style="margin:0">{icon.format(s=512)}</body></html>'
page_feature = f'''<html><body style="margin:0"><div id="f" style="width:1024px;height:500px;background:{BG};display:flex;align-items:center;gap:56px;padding-left:96px;box-sizing:border-box;font-family:'Segoe UI',Roboto,sans-serif;color:#fff">
<div style="border-radius:48px;overflow:hidden;width:260px;height:260px">{icon.format(s=260)}</div>
<div><div style="font-size:96px;font-weight:600;line-height:1">Papeles</div>
<div style="font-size:32px;margin-top:20px;opacity:.92;line-height:1.35">{line1}<br>{line2}</div></div></div></body></html>'''

with sync_playwright() as p:
    b = p.chromium.launch(headless=True, channel="msedge", args=["--disable-gpu"])
    if not es:
        pg = b.new_page(viewport={"width": 512, "height": 512})
        pg.set_content(page_icon)
        pg.screenshot(path=str(HERE / "icon-512.png"), clip={"x": 0, "y": 0, "width": 512, "height": 512})
    pg = b.new_page(viewport={"width": 1024, "height": 500})
    pg.set_content(page_feature)
    pg.screenshot(path=str(HERE / ("feature-1024x500-es.png" if es else "feature-1024x500.png")), clip={"x": 0, "y": 0, "width": 1024, "height": 500})
    b.close()
print("ok")
