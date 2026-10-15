#!/usr/bin/env python3
"""Turns raw emulator captures (raw/<theme>-<screen>.png) into captioned 1080x1920 store screenshots.
usage: make_screenshots.py RAW_DIR OUT_DIR"""
import sys, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RAW, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
R = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

def hexc(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

# background top, background bottom, accent, headline colour, sub colour
THEMES = {
    "neon":   ("#0E2A1D", "#120D1F", "#3BE37F", "#F4F6F8", "#B3BBC6"),
    "ocean":  ("#0B2A47", "#071523", "#38D6F5", "#F1F6FC", "#B0C0D4"),
    "royal":  ("#2A1850", "#1C0C1B", "#C6A6FF", "#F6F2FF", "#C2B8D8"),
    "amoled": ("#0A0A0A", "#000000", "#3BE37F", "#FFFFFF", "#BDBDC4"),
    "light":  ("#DDF2E6", "#E8E3F8", "#0E8A4A", "#0F172A", "#475569"),
    "sand":   ("#F4E3CB", "#EFE7DA", "#B4532A", "#231C12", "#5A4E3E"),
}

def wrap(d, text, font, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if d.textlength(t, font=font) <= width: cur = t
        else: lines.append(cur); cur = w
    return lines + [cur]

def make(n, theme, raw, head, sub):
    top, bot, acc, hc, sc = [hexc(x) if x.startswith("#") else x for x in THEMES[theme]]
    W, H = 1080, 1920
    img = Image.new("RGB", (W, H))
    px = ImageDraw.Draw(img)
    for y in range(H):
        t = y / H
        px.line([(0, y), (W, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)))
    # soft accent glow behind the phone
    glow = Image.new("RGB", (W, H), (0, 0, 0)); gd = ImageDraw.Draw(glow)
    gd.ellipse([140, 700, 940, 1500], fill=tuple(int(c * 0.35) for c in acc))
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    from PIL import ImageChops
    img = ImageChops.add(img, glow) if theme not in ("light", "sand") else ImageChops.screen(img, glow)
    d = ImageDraw.Draw(img)
    hf, sf = ImageFont.truetype(B, 84), ImageFont.truetype(R, 40)
    y = 120
    for line in wrap(d, head, hf, 920):
        d.text((80, y), line, font=hf, fill=hc); y += 100
    y += 14
    for line in wrap(d, sub, sf, 880):
        d.text((80, y), line, font=sf, fill=sc); y += 54
    # phone
    shot = Image.open(os.path.join(RAW, raw + ".png")).convert("RGB")
    pw = 880; ph = int(shot.height * pw / shot.width)
    shot = shot.resize((pw, ph), Image.LANCZOS)
    m = Image.new("L", shot.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, pw, ph], 64, fill=255)
    px0, py0 = (W - pw) // 2, max(y + 50, 470)
    bez = 14
    frame = Image.new("RGBA", (pw + bez * 2, ph + bez * 2), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, pw + bez * 2, ph + bez * 2], 76, fill=(18, 18, 20, 255), outline=(*acc, 255), width=3)
    shadow = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([0, 0, frame.width, frame.height], 76, fill=(0, 0, 0, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(30))
    img.paste(shadow, (px0 - bez, py0 - bez + 24), shadow)
    img.paste(frame, (px0 - bez, py0 - bez), frame)
    img.paste(shot, (px0, py0), m)
    out = os.path.join(OUT, f"{n:02d}-{theme}-{raw.split('-', 1)[1]}.png")
    img.save(out, optimize=True)
    return out

if __name__ == "__main__":
    import json
    spec = json.load(open(sys.argv[3]))
    for i, s in enumerate(spec, 1):
        print(make(i, s["theme"], s["raw"], s["head"], s["sub"]))
