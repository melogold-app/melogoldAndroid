"""Melogold icon set from one raster source (illustrated grapefruit on white).

Usage: python3 build_from_png.py source.png   -> writes everything into ./out
Requires Pillow, NumPy, SciPy.
"""
import json, os, shutil, sys
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

SRC = sys.argv[1] if len(sys.argv) > 1 else 'source.png'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
MASTER_FILL = 0.74   # subject height share on square white icons (iOS/macOS/Play/README/avatar)
FG_RADIUS = 0.32     # Android adaptive foreground: farthest opaque pixel from centre, share of canvas
FREE_FILL = 0.94     # shape-free icons (Windows, Linux): subject bbox share

def extract(path):
    im = np.asarray(Image.open(path).convert('RGB')).astype(np.float64)
    h, w, _ = im.shape
    mn, mx = im.min(axis=2), im.max(axis=2)
    bright = (mn > 225) & ((mx - mn) < 25)
    lab, _ = ndimage.label(bright)
    bg = np.isin(lab, list({lab[0, 0], lab[0, w - 1], lab[h - 1, 0], lab[h - 1, w - 1]} - {0}))
    band = ndimage.binary_dilation(bg, iterations=4) & ~bg
    alpha = np.ones((h, w)); alpha[bg] = 0
    alpha[band] = np.clip((255 - mn[band]) / 195, 0, 1)
    rgb = im.copy()
    a = np.maximum(alpha[band], 1e-3)[:, None]
    rgb[band] = np.clip((im[band] - (1 - alpha[band])[:, None] * 255) / a, 0, 255)
    lum = 0.299 * im[..., 0] + 0.587 * im[..., 1] + 0.114 * im[..., 2]
    lines = ((lum < 60) & (alpha > 0.5)).astype(np.float64)
    ys, xs = np.where(alpha > 0.05)
    box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    subject = Image.fromarray(np.dstack([rgb, alpha * 255]).astype(np.uint8), 'RGBA').crop(box)
    line_img = Image.fromarray((lines * 255).astype(np.uint8), 'L').crop(box)
    return subject, line_img

def radius(img):
    a = np.asarray(img.getchannel('A')) > 12
    ys, xs = np.where(a); h, w = a.shape
    return np.sqrt((ys - h / 2) ** 2 + (xs - w / 2) ** 2).max()

def place(img, canvas, scale, bg=None):
    w, h = img.size
    im = img.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    out = Image.new('RGBA', (canvas, canvas), bg or (0, 0, 0, 0))
    out.alpha_composite(im, ((canvas - im.width) // 2, (canvas - im.height) // 2))
    return out

def mask_rounded(img, radius_share, circle=False):
    s = img.width; m = Image.new('L', (s * 4, s * 4), 0); d = ImageDraw.Draw(m)
    (d.ellipse if circle else lambda b, fill: d.rounded_rectangle(b, radius=int(s * 4 * radius_share), fill=fill))([0, 0, s * 4 - 1, s * 4 - 1], fill=255)
    m = m.resize((s, s), Image.LANCZOS); out = Image.new('RGBA', (s, s), (0, 0, 0, 0)); out.paste(img, (0, 0), m); return out

def glyph(img, lines=None):
    """White glyph with alpha: filled silhouette, or line art when `lines` is given."""
    a = (lines if lines is not None else img.getchannel('A'))
    g = Image.new('RGBA', img.size, (255, 255, 255, 0)); g.putalpha(a); return g

def build(src):
    shutil.rmtree(OUT, ignore_errors=True)
    def p(*a):
        path = os.path.join(OUT, *a); os.makedirs(os.path.dirname(path), exist_ok=True); return path
    subject, lines = extract(src)
    subject.save(p('master', 'melogold-subject.png'))
    line_art = glyph(subject, lines)
    H = subject.height
    white = place(subject, 1024, MASTER_FILL * 1024 / H, bg=(255, 255, 255, 255)); white.save(p('master', 'melogold-1024.png'))
    free = place(subject, 1024, FREE_FILL * 1024 / max(subject.size)); free.save(p('master', 'melogold-transparent-1024.png'))
    fg_scale = FG_RADIUS * 1024 / radius(subject)
    # --- Android
    fg = place(subject, 1024, fg_scale); mono = place(line_art, 1024, fg_scale)
    for dpi, s in {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}.items():
        fg.resize((s, s), Image.LANCZOS).save(p('android', f'drawable-{dpi}', 'ic_launcher_foreground.png'), optimize=True)
        mono.resize((s, s), Image.LANCZOS).save(p('android', f'drawable-{dpi}', 'ic_launcher_monochrome.png'), optimize=True)
    notif = place(glyph(subject), 1024, 0.92 * 1024 / max(subject.size))
    for dpi, s in {'mdpi': 24, 'hdpi': 36, 'xhdpi': 48, 'xxhdpi': 72, 'xxxhdpi': 96}.items():
        notif.resize((s, s), Image.LANCZOS).save(p('android', f'drawable-{dpi}', 'app_icon.png'), optimize=True)
    for dpi, s in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
        inner = int(s * 0.92); off = (s - inner) // 2
        for name, circle in (('ic_launcher.webp', False), ('ic_launcher_round.webp', True)):
            t = mask_rounded(white.resize((inner, inner), Image.LANCZOS), 0.18, circle)
            c = Image.new('RGBA', (s, s), (0, 0, 0, 0)); c.alpha_composite(t, (off, off)); c.save(p('android', f'mipmap-{dpi}', name), lossless=True)
    white.resize((512, 512), Image.LANCZOS).save(p('android', 'ic_launcher-playstore.png'), optimize=True)
    # --- Apple (Icon Composer bundle, single layer like Clementine.icon)
    place(subject, 1024, MASTER_FILL * 1024 / H).save(p('apple', 'Melogold.icon', 'Assets', 'melogold.png'))
    json.dump({"fill": "automatic",
               "groups": [{"layers": [{"image-name": "melogold.png", "name": "melogold"}],
                           "shadow": {"kind": "neutral", "opacity": 0.5},
                           "translucency": {"enabled": False, "value": 0.2}}],
               "supported-platforms": {"circles": ["watchOS"], "squares": "shared"}},
              open(p('apple', 'Melogold.icon', 'icon.json'), 'w'), indent=2)
    white.convert('RGB').save(p('apple', 'AppIcon-1024.png'))
    # --- Windows / Linux
    free.save(p('windows', 'melogold.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    free.resize((256, 256), Image.LANCZOS).save(p('windows', 'melogold-256.png'))
    for s in (16, 24, 32, 48, 64, 128, 256, 512):
        free.resize((s, s), Image.LANCZOS).save(p('linux', 'hicolor', f'{s}x{s}', 'apps', 'melogold.png'), optimize=True)
    # --- README / avatar
    mask_rounded(white.resize((512, 512), Image.LANCZOS), 0.2237).save(p('readme', 'melogold-icon.png'), optimize=True)
    white.convert('RGB').save(p('avatar', 'melogold-avatar.png'))
    return white, fg, mono

if __name__ == '__main__':
    build(SRC); print('done ->', OUT)
