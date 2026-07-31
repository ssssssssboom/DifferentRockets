# -*- coding: utf-8 -*-
"""Draw new part sprites in the SimpleRockets/DifferentRockets atlas style:
gray metal body + vertical gradient + white highlight streak, dark red accents,
dark outlines, rivets. ~30 px per game unit, side/planform view.
Outputs to game/core/assets/sprites/."""
import math, os
from PIL import Image, ImageDraw, ImageFilter

OUT = r"D:\DifferentRockets\game\core\assets\sprites"
os.makedirs(OUT, exist_ok=True)

# palette
METAL_HI   = (225, 229, 233)
METAL_MID  = (176, 181, 187)
METAL_DARK = (110, 115, 122)
METAL_EDGE = (70, 74, 80)
RED_HI     = (176, 44, 44)
RED_MID    = (140, 26, 26)
RED_DARK   = (96, 18, 18)
OUTLINE    = (26, 27, 30)
WHITE      = (245, 248, 250)

def vgrad(w, h, stops):
    """vertical gradient image from list of (t0..1, color)"""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = stops[-1][1]
        for i in range(len(stops) - 1):
            t0, c0 = stops[i]; t1, c1 = stops[i + 1]
            if t0 <= t <= t1:
                f = (t - t0) / max(1e-6, t1 - t0)
                c = tuple(int(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                break
        for x in range(w):
            px[x, y] = c + (255,)
    return img

def hgrad(w, h, stops):
    g = vgrad(h, w, stops).rotate(-90, expand=True)
    return g.resize((w, h))

def fill_poly(base, poly, grad_stops, vertical=True, outline=OUTLINE, ow=3):
    """fill polygon with gradient clipped to it, then outline"""
    xs = [p[0] for p in poly]; ys = [p[1] for p in poly]
    x0, y0 = int(min(xs)) - 2, int(min(ys)) - 2
    x1, y1 = int(max(xs)) + 2, int(max(ys)) + 2
    w, h = x1 - x0, y1 - y0
    g = vgrad(w, h, grad_stops) if vertical else hgrad(w, h, grad_stops)
    mask = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(mask)
    md.polygon([(x - x0, y - y0) for x, y in poly], fill=255)
    base.paste(g, (x0, y0), mask)
    d = ImageDraw.Draw(base)
    d.polygon(poly, outline=outline, width=ow)

def rivets(d, pts, r=2):
    for x, y in pts:
        d.ellipse([x - r, y - r, x + r, y + r], fill=(60, 63, 68))
        d.ellipse([x - r + 1, y - r + 1, x + r - 1, y + r - 1], fill=(200, 205, 210))

def highlight(d, box, alpha=90):
    """soft white gloss streak inside box (x0,y0,x1,y1)"""
    x0, y0, x1, y1 = box
    ov = Image.new("RGBA", base_size, (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    od.rounded_rectangle([x0, y0, x1, y1], radius=(x1 - x0) // 2, fill=(255, 255, 255, alpha))
    return ov

def save(img, name):
    img.save(os.path.join(OUT, name))
    print("saved", name, img.size)

METAL_V = [(0.0, METAL_HI), (0.25, METAL_MID), (0.75, METAL_MID), (1.0, METAL_DARK)]
METAL_V_DARK = [(0.0, METAL_MID), (0.5, METAL_DARK), (1.0, METAL_EDGE)]
RED_V = [(0.0, RED_HI), (0.5, RED_MID), (1.0, RED_DARK)]

# ---------------------------------------------------------------- wing-1 (plain wing, planform, 8x4 units = 240x120)
W, H = 240, 120
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
# swept wing: root chord at left (attaches to fuselage), swept leading edge to the right tip
wing = [(4, 30), (150, 30), (236, 74), (236, 92), (4, 92)]
fill_poly(img, wing, [(0.0, METAL_HI), (0.35, METAL_MID), (0.8, METAL_MID), (1.0, METAL_DARK)], vertical=True)
d = ImageDraw.Draw(img)
# gloss streak along span
ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
od = ImageDraw.Draw(ov)
od.polygon([(14, 40), (150, 40), (226, 76), (226, 82), (14, 82)], fill=(255, 255, 255, 55))
od.polygon([(14, 44), (150, 44), (220, 76), (220, 78), (14, 78)], fill=(255, 255, 255, 60))
img = Image.alpha_composite(img, ov)
d = ImageDraw.Draw(img)
# panel lines along chord
for fx in (70, 150, 210):
    # interpolate y at leading/trailing edge
    if fx <= 150:
        yT = 30; yB = 92
    else:
        t = (fx - 150) / 86.0
        yT = 30 + 44 * t; yB = 92
    d.line([(fx, yT + 2), (fx, yB - 2)], fill=(90, 94, 100), width=2)
# root attachment band (red, like atlas accents)
root = [(4, 30), (26, 30), (26, 92), (4, 92)]
fill_poly(img, root, RED_V, vertical=True)
d = ImageDraw.Draw(img)
rivets(d, [(15, y) for y in range(40, 90, 12)])
# red tip cap
tip = [(226, 68), (236, 74), (236, 92), (222, 92)]
fill_poly(img, tip, RED_V, vertical=True)
save(img, "wing-1.png")

# ---------------------------------------------------------------- wing-2 (wing with aileron)
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
wing = [(4, 30), (150, 30), (236, 74), (236, 82), (4, 82)]   # main surface, shorter chord
fill_poly(img, wing, [(0.0, METAL_HI), (0.35, METAL_MID), (0.8, METAL_MID), (1.0, METAL_DARK)], vertical=True)
d = ImageDraw.Draw(img)
# aileron: separate panel behind trailing edge, slightly deflected (darker shade + hinge line)
ail = [(60, 84), (236, 84), (236, 100), (60, 100)]
fill_poly(img, ail, METAL_V_DARK, vertical=True)
d = ImageDraw.Draw(img)
# hinge line
d.line([(60, 83), (236, 83)], fill=OUTLINE, width=2)
# hinge knuckles
for hx in range(70, 236, 40):
    d.rectangle([hx, 81, hx + 10, 86], fill=METAL_EDGE, outline=OUTLINE)
# red actuator horn at aileron mid-span
d.polygon([(140, 84), (152, 84), (148, 74), (144, 74)], fill=RED_MID, outline=OUTLINE)
# gloss
ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
od = ImageDraw.Draw(ov)
od.polygon([(14, 38), (150, 38), (226, 74), (226, 78), (14, 78)], fill=(255, 255, 255, 55))
img = Image.alpha_composite(img, ov)
d = ImageDraw.Draw(img)
for fx in (70, 150):
    d.line([(fx, 32), (fx, 80)], fill=(90, 94, 100), width=2)
# root band
root = [(4, 30), (26, 30), (26, 82), (4, 82)]
fill_poly(img, root, RED_V, vertical=True)
d = ImageDraw.Draw(img)
rivets(d, [(15, y) for y in range(40, 80, 12)])
tip = [(226, 70), (236, 74), (236, 82), (222, 82)]
fill_poly(img, tip, RED_V, vertical=True)
save(img, "wing-2.png")

# ---------------------------------------------------------------- missile-1 (side view, 2x8 units = 60x240)
W, H = 60, 240
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
# body tube
body = [(14, 40), (46, 40), (46, 216), (14, 216)]
fill_poly(img, body, METAL_V, vertical=False)  # horizontal gradient across tube (highlight across width)
d = ImageDraw.Draw(img)
# nose ogive (red tip like nosecone accents)
nose = [(14, 40), (46, 40), (30, 6)]
fill_poly(img, nose, RED_V, vertical=True)
d = ImageDraw.Draw(img)
# seeker window
d.ellipse([24, 34, 36, 46], fill=(40, 44, 50), outline=OUTLINE, width=2)
d.ellipse([27, 37, 32, 42], fill=(120, 170, 200))
# canard fins (front, small delta)
finL = [(14, 70), (2, 92), (14, 96)]
finR = [(46, 70), (58, 92), (46, 96)]
fill_poly(img, finL, METAL_V_DARK, vertical=True)
fill_poly(img, finR, METAL_V_DARK, vertical=True)
d = ImageDraw.Draw(img)
# tail fins (larger)
tfL = [(14, 180), (0, 214), (14, 214)]
tfR = [(46, 180), (60, 214), (46, 214)]
fill_poly(img, tfL, METAL_V_DARK, vertical=True)
fill_poly(img, tfR, METAL_V_DARK, vertical=True)
d = ImageDraw.Draw(img)
# red band
band = [(14, 120), (46, 120), (46, 136), (14, 136)]
fill_poly(img, band, RED_V, vertical=True)
d = ImageDraw.Draw(img)
rivets(d, [(30, y) for y in (150, 165)], r=2)
# nozzle
noz = [(20, 216), (40, 216), (44, 234), (16, 234)]
fill_poly(img, noz, METAL_V_DARK, vertical=True)
d = ImageDraw.Draw(img)
d.ellipse([22, 228, 38, 238], fill=(30, 32, 36), outline=OUTLINE)
save(img, "missile-1.png")

# ---------------------------------------------------------------- missile-2 (rear 3/4 view detail: nozzle + fin cross, 60x60)
W, H = 60, 60
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
d = ImageDraw.Draw(img)
# fin cross behind body
for ang in (0, 90):
    a = math.radians(ang)
    dx, dy = math.cos(a), math.sin(a)
    px, py = -dy, dx
    tipx, tipy = 30 + dx * 28, 30 + dy * 28
    bx1, by1 = 30 + px * 6, 30 + py * 6
    bx2, by2 = 30 - px * 6, 30 - py * 6
    fill_poly(img, [(bx1, by1), (tipx + px * 4, tipy + py * 4), (tipx - px * 4, tipy - py * 4), (bx2, by2)],
              METAL_V_DARK, vertical=True)
d = ImageDraw.Draw(img)
# body circle (rear)
d.ellipse([10, 10, 50, 50], fill=METAL_MID, outline=OUTLINE, width=3)
d.ellipse([14, 14, 46, 46], fill=METAL_HI)
d.ellipse([14, 14, 46, 46], outline=OUTLINE, width=2)
# exhaust nozzle
d.ellipse([20, 20, 40, 40], fill=METAL_DARK, outline=OUTLINE, width=2)
d.ellipse([24, 24, 36, 36], fill=(28, 30, 34), outline=OUTLINE)
# gloss
d.arc([12, 12, 48, 48], 200, 300, fill=(255, 255, 255), width=3)
save(img, "missile-2.png")

# ---------------------------------------------------------------- turbofan-1 (side view, 4x6 units = 120x180)
W, H = 120, 180
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
# nacelle: intake top, nozzle bottom (engine points down like other engines)
nac = [(16, 8), (104, 8), (104, 150), (16, 150)]
fill_poly(img, nac, METAL_V, vertical=False)
d = ImageDraw.Draw(img)
# intake lip (rounded, darker ring at top)
d.rounded_rectangle([10, 4, 110, 30], radius=12, fill=METAL_DARK, outline=OUTLINE, width=3)
d.rounded_rectangle([18, 10, 102, 24], radius=7, fill=(30, 32, 36), outline=OUTLINE, width=2)
# fan blades hint inside intake
for i in range(7):
    x = 26 + i * 11
    d.line([(x, 12), (x + 6, 22)], fill=(90, 96, 104), width=3)
# spinner cone
d.polygon([(52, 6), (68, 6), (60, -2)], fill=METAL_HI, outline=OUTLINE)
# bypass panel lines
for y in (60, 100, 138):
    d.line([(16, y), (104, y)], fill=(90, 94, 100), width=2)
# red band accent
band = [(16, 108), (104, 108), (104, 124), (16, 124)]
fill_poly(img, band, RED_V, vertical=True)
d = ImageDraw.Draw(img)
rivets(d, [(24, 116), (96, 116)])
# pylon mount stub at top
d.rectangle([46, 0, 74, 6], fill=METAL_EDGE, outline=OUTLINE)
# exhaust nozzle cone
noz = [(28, 150), (92, 150), (76, 176), (44, 176)]
fill_poly(img, noz, METAL_V_DARK, vertical=True)
d = ImageDraw.Draw(img)
d.ellipse([46, 168, 74, 180], fill=(28, 30, 34), outline=OUTLINE)
# gloss streak
ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
od = ImageDraw.Draw(ov)
od.rounded_rectangle([26, 34, 40, 146], radius=7, fill=(255, 255, 255, 60))
img = Image.alpha_composite(img, ov)
save(img, "turbofan-1.png")

# ---------------------------------------------------------------- turbofan-2 (front view fan disc, 120x120)
W, H = 120, 120
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
d = ImageDraw.Draw(img)
d.ellipse([4, 4, 116, 116], fill=METAL_DARK, outline=OUTLINE, width=3)
d.ellipse([12, 12, 108, 108], fill=(34, 37, 42), outline=OUTLINE, width=2)
# fan blades
for i in range(12):
    a = math.radians(i * 30 + 8)
    cx, cy = 60, 60
    x1 = cx + math.cos(a) * 14; y1 = cy + math.sin(a) * 14
    x2 = cx + math.cos(a + 0.35) * 46; y2 = cy + math.sin(a + 0.35) * 46
    d.line([(x1, y1), (x2, y2)], fill=(150, 156, 163), width=6)
    d.line([(x1, y1), (x2, y2)], fill=METAL_EDGE, width=1)
# hub
d.ellipse([42, 42, 78, 78], fill=METAL_MID, outline=OUTLINE, width=2)
d.ellipse([52, 52, 68, 68], fill=METAL_HI, outline=OUTLINE)
d.arc([16, 16, 104, 104], 190, 290, fill=(255, 255, 255), width=4)
save(img, "turbofan-2.png")

# ---------------------------------------------------------------- prop-1 (side view, nacelle + spinner + 2 blades, 6x6 units = 180x180)
W, H = 180, 180
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
d = ImageDraw.Draw(img)
cx = 54  # hub center x
# far blade (drawn first, darker)
blade_far = [(cx - 2, 88), (cx - 14, 30), (cx - 20, 14), (cx - 10, 12), (cx + 6, 84)]
fill_poly(img, blade_far, METAL_V_DARK, vertical=True)
d = ImageDraw.Draw(img)
# near blade (down)
blade_near = [(cx - 2, 92), (cx - 16, 150), (cx - 22, 168), (cx - 10, 170), (cx + 6, 96)]
fill_poly(img, blade_near, METAL_V, vertical=True)
d = ImageDraw.Draw(img)
# red blade tips
d.polygon([(cx - 20, 14), (cx - 10, 12), (cx - 12, 26), (cx - 18, 28)], fill=RED_MID, outline=OUTLINE)
d.polygon([(cx - 22, 168), (cx - 10, 170), (cx - 13, 156), (cx - 19, 154)], fill=RED_MID, outline=OUTLINE)
# nacelle body to the right of hub
nac = [(cx + 10, 62), (168, 62), (168, 118), (cx + 10, 118)]
fill_poly(img, nac, METAL_V, vertical=True)
d = ImageDraw.Draw(img)
d.line([(cx + 10, 90), (168, 90)], fill=(90, 94, 100), width=2)
# spinner
d.ellipse([cx - 16, 74, cx + 24, 106], fill=METAL_HI, outline=OUTLINE, width=3)
d.ellipse([cx - 8, 82, cx + 4, 94], fill=(255, 255, 255))
# red band on nacelle
band = [(150, 62), (168, 62), (168, 118), (150, 118)]
fill_poly(img, band, RED_V, vertical=True)
d = ImageDraw.Draw(img)
rivets(d, [(cx + 30, 70), (cx + 60, 70), (cx + 90, 70)])
save(img, "prop-1.png")

# ---------------------------------------------------------------- prop-2 (single blade for rotation animation, 30x90)
W, H = 30, 90
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
base_size = (W, H)
blade = [(15, 88), (5, 40), (3, 12), (10, 4), (20, 6), (25, 30), (21, 88)]
fill_poly(img, blade, METAL_V, vertical=True)
d = ImageDraw.Draw(img)
d.polygon([(3, 12), (10, 4), (20, 6), (19, 14), (8, 16)], fill=RED_MID, outline=OUTLINE)
ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
od = ImageDraw.Draw(ov)
od.line([(12, 20), (16, 80)], fill=(255, 255, 255, 70), width=3)
img = Image.alpha_composite(img, ov)
save(img, "prop-2.png")

print("done")
