"""Generate the Musiclify app icon (1024x1024 PNG): coral rounded square with a music note.

Usage: python tools/make_icon.py shared/icon.png
Then regenerate the app icon sets: cd apps/desktop && npx tauri icon ../../shared/icon.png
"""
import sys
from PIL import Image, ImageDraw

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# Background: rounded square, coral -> deeper coral vertical gradient.
grad = Image.new("RGBA", (S, S))
gd = ImageDraw.Draw(grad)
top, bottom = (255, 138, 101), (232, 84, 52)
for y in range(S):
    t = y / (S - 1)
    gd.line([(0, y), (S, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([40, 40, S - 40, S - 40], radius=220, fill=255)
img.paste(grad, (0, 0), mask)

# Beamed eighth notes in near-black.
ink = (26, 13, 8, 255)
d.ellipse([250, 600, 450, 760], fill=ink)          # left head
d.ellipse([560, 540, 760, 700], fill=ink)          # right head
d.rectangle([410, 300, 450, 690], fill=ink)        # left stem
d.rectangle([720, 240, 760, 630], fill=ink)        # right stem
d.polygon([(410, 300), (760, 240), (760, 330), (410, 390)], fill=ink)  # beam

img.save(sys.argv[1] if len(sys.argv) > 1 else "icon.png")
