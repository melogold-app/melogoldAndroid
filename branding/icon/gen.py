"""Melogold icon generator: builds SVG variants from one geometry and rasterizes them via Quick Look."""
import math, os, shutil, subprocess, tempfile
import numpy as np
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
CX, CY = 512, 518          # centre of the artwork in base coordinates
FRUIT = (512, 572, 250)     # cx, cy, r
LEAF_L = 'M500,324 C470,252 390,214 296,236 C346,302 428,338 500,324 Z'
LEAF_L_TOP = 'M500,324 C470,252 390,214 296,236 Z'
LEAF_R = 'M524,324 C554,252 634,214 728,236 C678,302 596,338 524,324 Z'
LEAF_R_TOP = 'M524,324 C554,252 634,214 728,236 Z'

DEFS = '''<defs>
<radialGradient id="fruit" cx="0.38" cy="0.32" r="0.78">
<stop offset="0" stop-color="#FFA04D"/><stop offset="0.42" stop-color="#FE6B08"/>
<stop offset="0.82" stop-color="#EE5A00"/><stop offset="1" stop-color="#D94A00"/></radialGradient>
<linearGradient id="leafL" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#27D680"/><stop offset="1" stop-color="#12B866"/></linearGradient>
<linearGradient id="leafD" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0A9A55"/><stop offset="1" stop-color="#04703C"/></linearGradient>
<linearGradient id="stem" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#16B064"/><stop offset="1" stop-color="#05683A"/></linearGradient>
<clipPath id="fc"><circle cx="512" cy="572" r="250"/></clipPath>
<filter id="b6" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="6"/></filter>
<filter id="b16" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="16"/></filter>
</defs>'''

def leaves_svg():
    return (f'<rect x="500" y="276" width="24" height="66" rx="12" fill="url(#stem)"/>'
            f'<path d="{LEAF_L}" fill="url(#leafD)"/><path d="{LEAF_L_TOP}" fill="url(#leafL)"/>'
            f'<path d="{LEAF_R}" fill="url(#leafD)"/><path d="{LEAF_R_TOP}" fill="url(#leafL)"/>')

def fruit_svg(effects=True):
    s = '<circle cx="512" cy="572" r="250" fill="url(#fruit)"/>'
    if effects:
        s += ('<g clip-path="url(#fc)"><ellipse cx="512" cy="336" rx="160" ry="36" fill="#9E3300" opacity="0.38" filter="url(#b16)"/></g>'
              '<ellipse cx="410" cy="458" rx="66" ry="38" transform="rotate(-38 410 458)" fill="#FFFFFF" opacity="0.6" filter="url(#b6)"/>'
              '<ellipse cx="452" cy="408" rx="13" ry="10" fill="#FFFFFF" opacity="0.9"/>')
    return s

def svg(body, scale, bg=None, size=1024, defs=True):
    t = f'translate({size/2} {size/2}) scale({scale * size / 1024}) translate({-CX} {-CY})'
    bgr = f'<rect width="{size}" height="{size}" fill="{bg}"/>' if bg else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}">'
            f'{DEFS if defs else ""}{bgr}<g transform="{t}">{body}</g></svg>')

def glyph_paths(dy=-30):
    """Single-colour silhouette: fruit with highlight hole (even-odd) + detached leaves and stem."""
    cx, cy, r = FRUIT
    a = math.radians(-38); rx, ry, ex, ey = 58, 30, 410, 458
    p1 = (ex - rx * math.cos(a), ey - rx * math.sin(a)); p2 = (ex + rx * math.cos(a), ey + rx * math.sin(a))
    fruit = (f'M{cx-r},{cy} A{r},{r} 0 1,0 {cx+r},{cy} A{r},{r} 0 1,0 {cx-r},{cy} Z '
             f'M{p1[0]:.1f},{p1[1]:.1f} A{rx},{ry} -38 1,0 {p2[0]:.1f},{p2[1]:.1f} A{rx},{ry} -38 1,0 {p1[0]:.1f},{p1[1]:.1f} Z')
    def shift(d):
        # shift y of every coordinate pair
        res, nums = [], d.replace('M', ' M ').replace('C', ' C ').replace('Z', ' Z ').split()
        for n in nums:
            if ',' in n:
                x, y = n.split(','); res.append(f'{x},{float(y) + dy:g}')
            else:
                res.append(n)
        return ' '.join(res)
    stem = f'M500,{258+dy} A12,12 0 0,1 524,{258+dy} L524,{288+dy} A12,12 0 0,1 500,{288+dy} Z'
    leaves = shift(LEAF_L) + ' ' + shift(LEAF_R)
    return fruit, leaves + ' ' + stem

def glyph_svg(scale, fg='#000', bg=None, size=1024):
    fruit, rest = glyph_paths()
    body = f'<path d="{fruit}" fill="{fg}" fill-rule="evenodd"/><path d="{rest}" fill="{fg}"/>'
    return svg(body, scale, bg=bg, size=size, defs=False)

def render(svg_text, size=1024):
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, 'x.svg'); open(p, 'w').write(svg_text)
        subprocess.run(['qlmanage', '-t', '-s', str(size), '-o', d, p], capture_output=True, check=True)
        return Image.open(p + '.png').convert('RGB').copy()

def render_transparent(body_fn, scale, size=1024, **kw):
    """Render on black and on white, recover alpha (difference matting)."""
    b = np.asarray(render(body_fn(scale, bg='#000000', size=size, **kw), size), dtype=np.float64)
    w = np.asarray(render(body_fn(scale, bg='#FFFFFF', size=size, **kw), size), dtype=np.float64)
    alpha = 1.0 - (w - b).mean(axis=2) / 255.0
    alpha = np.clip(alpha, 0, 1)
    rgb = np.where(alpha[..., None] > 1e-3, b / np.maximum(alpha[..., None], 1e-3), 0)
    rgba = np.dstack([np.clip(rgb, 0, 255), alpha * 255]).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')

def art_body():
    return fruit_svg() + leaves_svg()

def art_svg(scale, bg=None, size=1024):
    return svg(art_body(), scale, bg=bg, size=size)

def squircle(img, size, radius=0.2237):
    img = img.resize((size, size), Image.LANCZOS)
    m = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * radius), fill=255)
    m = m.resize((size, size), Image.LANCZOS)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0)); out.paste(img, (0, 0), m); return out

def circle_crop(img, size):
    img = img.resize((size, size), Image.LANCZOS)
    m = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    m = m.resize((size, size), Image.LANCZOS)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0)); out.paste(img, (0, 0), m); return out
