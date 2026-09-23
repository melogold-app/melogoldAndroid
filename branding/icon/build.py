import json, os, shutil
from PIL import Image
from gen import *

MASTER = 1.235   # iOS / macOS / Play Store / previews (5% smaller than the 1.3 draft)
FG = 0.88        # Android adaptive foreground: fits the 66dp safe circle
MONO = 0.82      # Android monochrome (glyph is taller because leaves are detached)
FREE = 1.5       # shape-free icons (Windows, Linux)
NOTIF = 1.5      # notification small icon

shutil.rmtree(OUT, ignore_errors=True)
def p(*a):
    path = os.path.join(OUT, *a); os.makedirs(os.path.dirname(path), exist_ok=True); return path

# --- master
open(p('master', 'melogold.svg'), 'w').write(art_svg(MASTER, bg='#FFFFFF'))
open(p('master', 'melogold-transparent.svg'), 'w').write(art_svg(FREE))
open(p('master', 'melogold-glyph.svg'), 'w').write(glyph_svg(NOTIF))
white = render(art_svg(MASTER, bg='#FFFFFF')); white.save(p('master', 'melogold-1024.png'))
free = render_transparent(art_svg, FREE); free.save(p('master', 'melogold-transparent-1024.png'))

# --- android
fg = render_transparent(art_svg, FG)
for dpi, s in {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}.items():
    fg.resize((s, s), Image.LANCZOS).save(p('android', f'drawable-{dpi}', 'ic_launcher_foreground.png'), optimize=True)
for dpi, s in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    inner = int(s * 0.92); off = (s - inner) // 2
    sq = Image.new('RGBA', (s, s), (0, 0, 0, 0)); sq.paste(squircle(white, inner, 0.18), (off, off), squircle(white, inner, 0.18))
    sq.save(p('android', f'mipmap-{dpi}', 'ic_launcher.webp'), lossless=True)
    rd = Image.new('RGBA', (s, s), (0, 0, 0, 0)); c = circle_crop(white, inner); rd.paste(c, (off, off), c)
    rd.save(p('android', f'mipmap-{dpi}', 'ic_launcher_round.webp'), lossless=True)
white.resize((512, 512), Image.LANCZOS).save(p('android', 'ic_launcher-playstore.png'), optimize=True)

def vector(scale, dp):
    fruit, rest = glyph_paths()
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{dp}dp"
    android:height="{dp}dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024">
    <group
        android:pivotX="{CX}"
        android:pivotY="{CY}"
        android:scaleX="{scale}"
        android:scaleY="{scale}"
        android:translateX="{512 - CX}"
        android:translateY="{512 - CY}">
        <path
            android:fillColor="#FF000000"
            android:fillType="evenOdd"
            android:pathData="{fruit}" />
        <path
            android:fillColor="#FF000000"
            android:pathData="{rest}" />
    </group>
</vector>
'''
open(p('android', 'drawable', 'ic_launcher_monochrome.xml'), 'w').write(vector(MONO, 108))
open(p('android', 'drawable', 'app_icon.xml'), 'w').write(vector(NOTIF, 24))

# --- apple (Icon Composer bundle for Liquid Glass + fallback PNG)
os.makedirs(p('apple', 'Melogold.icon', 'Assets', 'x'), exist_ok=True); os.rmdir(p('apple', 'Melogold.icon', 'Assets', 'x'))
open(p('apple', 'Melogold.icon', 'Assets', 'fruit.svg'), 'w').write(svg(fruit_svg(effects=False), MASTER))
open(p('apple', 'Melogold.icon', 'Assets', 'leaves.svg'), 'w').write(svg(leaves_svg(), MASTER))
group = lambda name: {"layers": [{"image-name": f"{name}.svg", "name": name}],
                      "shadow": {"kind": "neutral", "opacity": 0.5},
                      "translucency": {"enabled": True, "value": 0.4}}
json.dump({"fill": "automatic", "groups": [group('leaves'), group('fruit')],
           "supported-platforms": {"circles": ["watchOS"], "squares": "shared"}},
          open(p('apple', 'Melogold.icon', 'icon.json'), 'w'), indent=2)
white.save(p('apple', 'AppIcon-1024.png'))

# --- windows
free.save(p('windows', 'melogold.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
free.resize((256, 256), Image.LANCZOS).save(p('windows', 'melogold-256.png'))

# --- linux (hicolor theme layout)
shutil.copy(p('master', 'melogold-transparent.svg'), p('linux', 'hicolor', 'scalable', 'apps', 'melogold.svg'))
for s in (16, 24, 32, 48, 64, 128, 256, 512):
    free.resize((s, s), Image.LANCZOS).save(p('linux', 'hicolor', f'{s}x{s}', 'apps', 'melogold.png'), optimize=True)

# --- preview sheet
from PIL import ImageDraw, ImageFont
dark = render(art_svg(MASTER, bg='#1C1C1E'))
shape = render(glyph_svg(MASTER * MONO / FG)).convert('L').point(lambda v: 255 - v)
tint = Image.new('RGBA', (1024, 1024), (211, 227, 253, 255)); tint.paste(Image.new('RGBA', (1024, 1024), (29, 63, 114, 255)), (0, 0), shape)
sheet = Image.new('RGBA', (1640, 520), (242, 242, 247, 255)); d = ImageDraw.Draw(sheet)
f = ImageFont.truetype('/System/Library/Fonts/SFNS.ttf', 26); x = 40
for img, label in [(white, 'Светлая'), (dark, 'Тёмная'), (tint, 'Android: тонированная')]:
    t = squircle(img.convert('RGBA'), 360); sheet.paste(t, (x, 50), t); d.text((x + 180, 450), label, fill=(60, 60, 67, 255), font=f, anchor='mm'); x += 400
for s, y in [(120, 50), (72, 200), (48, 300)]:
    t = squircle(white.convert('RGBA'), s); sheet.paste(t, (1440 - s // 2, y), t)
d.text((1440, 450), 'Мелкие размеры', fill=(60, 60, 67, 255), font=f, anchor='mm')
sheet.convert('RGB').save(p('preview.png'))
print('done')
