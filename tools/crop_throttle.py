from PIL import Image

img = Image.open(r"D:\DifferentRockets\aasss\Runtime.png").convert("RGBA")

# throttle control track
img.crop((2, 2, 2 + 106, 2 + 407)).resize((106 * 2, 407), Image.NEAREST).save(
    r"D:\DifferentRockets\shots\crop-control.png")

# level sprites packed rects (w,h as listed in XML)
levels = {
    1: (151, 259, 27, 21), 2: (110, 123, 28, 25), 3: (148, 201, 28, 27),
    4: (148, 36, 31, 28), 5: (110, 94, 35, 27), 6: (148, 161, 28, 38),
    7: (139, 230, 41, 27), 8: (148, 115, 28, 44), 9: (110, 214, 27, 48),
    10: (151, 349, 25, 48),
}
# stack packed crops side by side with labels implied by order
pad = 6
W = sum(w + pad for _, _, w, _ in levels.values()) + pad
H = max(h for _, _, _, h in levels.values()) + 2 * pad
sheet = Image.new("RGBA", (W, H), (40, 40, 60, 255))
x = pad
for k in range(1, 11):
    sx, sy, w, h = levels[k]
    sheet.paste(img.crop((sx, sy, sx + w, sy + h)), (x, pad))
    x += w + pad
sheet = sheet.resize((W * 3, H * 3), Image.NEAREST)
sheet.save(r"D:\DifferentRockets\shots\crop-levels-packed.png")
print("ok")
