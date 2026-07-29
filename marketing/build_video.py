import math
import pathlib
import subprocess

from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920
FPS = 30
BRAND = (233, 230, 74)
INK = (18, 18, 18)
OUT = pathlib.Path(r"C:\Users\lrumk\AppData\Local\Temp\claude\C--Users-lrumk\b199a11a-d0e4-41ea-9103-1fbe10172412\scratchpad")
FRAMES = OUT / "frames2"
FRAMES.mkdir(exist_ok=True)
for old in FRAMES.glob("*.png"):
    old.unlink()

ARTIST = "ZAYLO"
TITLE = "MONTAGEM URANIUM"


def font(size, bold=True):
    for name in (("segoeuib.ttf", "arialbd.ttf") if bold else ("segoeui.ttf", "arial.ttf")):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def fit(draw, text, size, bold=True, limit=W - 200):
    while size > 30:
        f = font(size, bold)
        if draw.textlength(text, font=f) <= limit:
            return f
        size -= 4
    return font(size, bold)


def clip_stub(draw, note):
    draw.rectangle([0, 0, W, H], fill=(22, 22, 24))
    f = font(40, bold=False)
    for i, line in enumerate(note):
        draw.text(((W - draw.textlength(line, font=f)) / 2, H / 2 - 40 + i * 56),
                  line, font=f, fill=(105, 105, 112))


def sub(draw, text, y, size=64):
    f = font(size)
    w = draw.textlength(text, font=f)
    x = (W - w) / 2
    draw.rectangle([x - 26, y - 16, x + w + 26, y + size + 18], fill=(0, 0, 0))
    draw.text((x, y), text, font=f, fill=(255, 255, 255))


def cassette(draw, cx, cy, width, spin, wobble=0.0):
    """The mark that makes the channel recognisable, drawn as a flat cassette."""
    height = width * 0.62
    left, top = cx - width / 2, cy - height / 2
    draw.rounded_rectangle([left, top, left + width, top + height],
                           radius=width * 0.05, fill=INK)
    hub_r = width * 0.085
    window_w, window_h = width * 0.52, height * 0.34
    draw.rounded_rectangle([cx - window_w / 2, cy - window_h / 2, cx + window_w / 2, cy + window_h / 2],
                           radius=window_h / 2, fill=BRAND)
    for side in (-1, 1):
        hx = cx + side * window_w * 0.27
        draw.ellipse([hx - hub_r, cy - hub_r, hx + hub_r, cy + hub_r], fill=INK)
        for spoke in range(6):
            angle = spin + spoke * math.pi / 3 + (0 if side < 0 else 0.4)
            draw.line([hx, cy,
                       hx + math.cos(angle) * hub_r * 0.78,
                       cy + math.sin(angle) * hub_r * 0.78], fill=BRAND, width=4)
    draw.rectangle([cx - width * 0.035, cy - window_h * 0.22, cx + width * 0.035, cy + window_h * 0.22],
                   fill=INK)
    for corner_x in (left + width * 0.07, left + width * 0.86):
        for corner_y in (top + height * 0.11, top + height * 0.72):
            draw.rectangle([corner_x, corner_y, corner_x + width * 0.045, corner_y + height * 0.07],
                           fill=BRAND)


def wave(draw, y, phase, amp=1.0):
    bars = 46
    gap = (W - 200) / bars
    for i in range(bars):
        level = abs(math.sin(phase + i * 0.42)) * 0.7 + abs(math.sin(phase * 1.7 + i * 0.19)) * 0.3
        h = 12 + level * 120 * amp
        x = 100 + i * gap
        draw.rounded_rectangle([x, y - h / 2, x + gap * 0.55, y + h / 2], radius=4, fill=INK)


index = 0

for step in range(3 * FPS):
    frame = Image.new("RGB", (W, H), (22, 22, 24))
    draw = ImageDraw.Draw(frame)
    clip_stub(draw, ["[ stitched clip, sound loud ]"])
    sub(draw, "everyone knows this sound", 300)
    frame.save(FRAMES / f"f{index:04d}.png"); index += 1

for step in range(3 * FPS):
    frame = Image.new("RGB", (W, H), (22, 22, 24))
    draw = ImageDraw.Draw(frame)
    clip_stub(draw, ["[ same clip, no cut ]"])
    sub(draw, "nobody knows the name", 300)
    frame.save(FRAMES / f"f{index:04d}.png"); index += 1

reveal = int(1.2 * FPS)
for step in range(reveal):
    t = step / reveal
    ease = 1 - pow(1 - t, 3)
    frame = Image.new("RGB", (W, H), (22, 22, 24))
    draw = ImageDraw.Draw(frame)
    clip_stub(draw, [])
    band_h = H * 0.52 * ease
    draw.rectangle([0, H / 2 - band_h / 2, W, H / 2 + band_h / 2], fill=BRAND)
    if ease > 0.45:
        cassette(draw, W / 2, H / 2, W * 0.5 * min(1, (ease - 0.45) / 0.55), spin=step * 0.25)
    frame.save(FRAMES / f"f{index:04d}.png"); index += 1

hold = int(4.2 * FPS)
for step in range(hold):
    frame = Image.new("RGB", (W, H), (22, 22, 24))
    draw = ImageDraw.Draw(frame)
    clip_stub(draw, [])
    draw.rectangle([0, H * 0.24, W, H * 0.76], fill=BRAND)
    cassette(draw, W / 2, H * 0.375, W * 0.5, spin=step * 0.22)
    wave(draw, H * 0.53, phase=step * 0.3, amp=0.55)
    f_artist = fit(draw, ARTIST, 58)
    draw.text(((W - draw.textlength(ARTIST, font=f_artist)) / 2, H * 0.585), ARTIST,
              font=f_artist, fill=INK)
    f_title = fit(draw, TITLE, 96)
    draw.text(((W - draw.textlength(TITLE, font=f_title)) / 2, H * 0.645), TITLE,
              font=f_title, fill=INK)
    frame.save(FRAMES / f"f{index:04d}.png"); index += 1

for step in range(int(3.6 * FPS)):
    frame = Image.new("RGB", (W, H), (22, 22, 24))
    draw = ImageDraw.Draw(frame)
    clip_stub(draw, [])
    draw.rectangle([0, H * 0.24, W, H * 0.76], fill=BRAND)
    cassette(draw, W / 2, H * 0.375, W * 0.5, spin=(hold + step) * 0.22)
    wave(draw, H * 0.53, phase=(hold + step) * 0.3, amp=0.55)
    f_artist = fit(draw, ARTIST, 58)
    draw.text(((W - draw.textlength(ARTIST, font=f_artist)) / 2, H * 0.585), ARTIST,
              font=f_artist, fill=INK)
    f_title = fit(draw, TITLE, 96)
    draw.text(((W - draw.textlength(TITLE, font=f_title)) / 2, H * 0.645), TITLE,
              font=f_title, fill=INK)
    tag = "more songs nobody could name"
    f_tag = font(44, bold=False)
    draw.text(((W - draw.textlength(tag, font=f_tag)) / 2, H * 0.82), tag,
              font=f_tag, fill=(150, 150, 158))
    frame.save(FRAMES / f"f{index:04d}.png"); index += 1

print("frames:", index, "=", round(index / FPS, 1), "seconds")

target = OUT / "syntracks-test-v2.mp4"
subprocess.run([
    r"C:\ffmpeg\bin\ffmpeg.exe", "-y", "-loglevel", "error",
    "-framerate", str(FPS), "-i", str(FRAMES / "f%04d.png"),
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "20",
    str(target),
], check=True)
print("written:", target.stat().st_size // 1024, "KB")
