# ASEM Mods banner: app screenshots + the green ASEM MODS icon.  python make_banner.py <dir> <out.png>
# <dir> holds shots/*.png, asem_wordmark.png and Tajawal-*.ttf
import sys, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
D = sys.argv[1]; OUT = sys.argv[2]
f = lambda w, s: ImageFont.truetype(os.path.join(D, "Tajawal-%s.ttf" % w), s)
W, H = 3000, 1100

def shot(name):
    return Image.open(os.path.join(D, "shots", name)).convert("RGB")

def phone(im, h):
    w = round(h * 1080 / 2436)
    scr = im.resize((w, h), Image.LANCZOS)
    b = 12; r = 54
    ph = Image.new("RGBA", (w + 2 * b, h + 2 * b), (0, 0, 0, 0))
    ImageDraw.Draw(ph).rounded_rectangle((0, 0, ph.width - 1, ph.height - 1), r + b, fill=(30, 36, 44, 255), outline=(62, 72, 84, 255), width=2)
    m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    ph.paste(scr, (b, b), m)
    return ph

def place(canvas, ph, cx, cy, angle):
    p = ph.rotate(angle, resample=Image.BICUBIC, expand=True)
    sh = Image.new("RGBA", (p.width + 80, p.height + 80), (0, 0, 0, 0))
    sh.paste((0, 0, 0, 150), (40, 55), p.split()[3])
    sh = sh.filter(ImageFilter.GaussianBlur(26))
    canvas.alpha_composite(sh, (cx - sh.width // 2, cy - sh.height // 2 + 10))
    canvas.alpha_composite(p, (cx - p.width // 2, cy - p.height // 2))

def icon(size):
    """the app icon: logo-green plate + ASEM letters from the logo + MODS"""
    S = size
    t = np.linspace(0, 1, S)[:, None, None]
    plate = (np.array([77, 201, 111]) * (1 - t) + np.array([1, 127, 114]) * t) * np.ones((S, S, 3))
    im = Image.fromarray(plate.astype(np.uint8)).convert("RGBA")
    wm = Image.open(os.path.join(D, "asem_wordmark.png"))
    tw = int(S * 0.70); th = int(wm.height * tw / wm.width)
    wm = wm.resize((tw, th), Image.LANCZOS)
    ft = f("Medium", int(S * 0.085)); lab = "M O D S"
    d = ImageDraw.Draw(im); lb = d.textbbox((0, 0), lab, font=ft)
    gap = int(S * 0.05); total = th + gap + (lb[3] - lb[1]); y0 = (S - total) // 2
    im.alpha_composite(wm, ((S - tw) // 2, y0))
    d.text(((S - (lb[2] - lb[0])) // 2 - lb[0], y0 + th + gap - lb[1]), lab, font=ft, fill=(245, 250, 232, 235))
    m = Image.new("L", (S, S), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, S - 1, S - 1), int(S * 0.23), fill=255)
    im.putalpha(m)
    return im

# background: dark with a soft green glow in the middle
bg = Image.new("RGBA", (W, H), (11, 15, 20, 255))
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0)); g = ImageDraw.Draw(glow)
g.ellipse((W/2 - 520, H/2 - 420, W/2 + 520, H/2 + 420), fill=(18, 160, 120, 95))
g.ellipse((W/2 - 900, H/2 - 260, W/2 + 900, H/2 + 600), fill=(10, 70, 60, 60))
bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(170)))

shots_l = [shot("dark_icons.png"), shot("dark_splash.png"), shot("dark_main.png")]
shots_r = [shot("light_volume.png"), shot("light_splash.png"), shot("light_main.png")]
# outer, middle, inner on each side (inner = biggest, closest to the logo)
spec = [(330, 740, 9), (620, 830, 5), (900, 920, 0)]
for (x, h, a), im in zip(spec, shots_l):
    place(bg, phone(im, h), x, H // 2 + (40 if h < 900 else 0), a)
for (x, h, a), im in zip(spec, shots_r):
    place(bg, phone(im, h), W - x, H // 2 + (40 if h < 900 else 0), -a)

# centre: icon + title
logo = icon(300)
lg = Image.new("RGBA", (W, H), (0, 0, 0, 0)); lgd = ImageDraw.Draw(lg)
lgd.ellipse((W/2 - 230, 250 - 230 + 140, W/2 + 230, 250 + 230 + 140), fill=(40, 200, 140, 120))
bg.alpha_composite(lg.filter(ImageFilter.GaussianBlur(70)))
lx, ly = W // 2 - 150, 220
sh = Image.new("RGBA", (W, H), (0, 0, 0, 0)); sh.paste((0, 0, 0, 170), (lx + 8, ly + 18), logo.split()[3])
bg.alpha_composite(sh.filter(ImageFilter.GaussianBlur(18)))
bg.alpha_composite(logo, (lx, ly))
d = ImageDraw.Draw(bg)
def ctext(y, t, ft, col):
    d.text((W / 2 - d.textlength(t, font=ft) / 2, y), t, font=ft, fill=col)
ctext(560, "ASEM Mods", f("Bold", 96), (240, 246, 244))
ctext(675, "LSPosed mods for XOS 16  ·  by Asem", f("Medium", 34), (150, 215, 195))
ctext(727, "Infinix NOTE 50 Pro 4G (X6855)", f("Regular", 30), (150, 158, 170))
rows = [["Louder lock sound", "3D haptics", "Glass icons"], ["Volume panel buttons", "Power menu Recovery"]]
ft = f("Medium", 27); y = 790
for chips in rows:
    widths = [d.textlength(c, font=ft) + 56 for c in chips]
    x = W / 2 - (sum(widths) + 18 * (len(chips) - 1)) / 2
    for c, w in zip(chips, widths):
        d.rounded_rectangle((x, y, x + w, y + 52), 26, fill=(22, 56, 50), outline=(40, 120, 100), width=2)
        d.text((x + 28, y + 11), c, font=ft, fill=(170, 235, 210)); x += w + 18
    y += 66
bg.convert("RGB").save(OUT, quality=95)
print("saved", OUT)
