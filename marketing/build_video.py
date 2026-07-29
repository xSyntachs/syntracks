import pathlib
import subprocess

from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920
FPS = 30
BRAND = (233, 230, 74)
INK = (18, 18, 18)
SHOTS = pathlib.Path(r"C:\Users\lrumk")
OUT = pathlib.Path(r"C:\Users\lrumk\AppData\Local\Temp\claude\C--Users-lrumk\b199a11a-d0e4-41ea-9103-1fbe10172412\scratchpad")
FRAMES = OUT / "frames"
FRAMES.mkdir(exist_ok=True)
for old in FRAMES.glob("*.png"):
    old.unlink()


def font(size, bold=True):
    for name in ("segoeuib.ttf" if bold else "segoeui.ttf", "arialbd.ttf" if bold else "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def centered(draw, text, y, f, fill=(255, 255, 255)):
    box = draw.textbbox((0, 0), text, font=f)
    draw.text(((W - box[2]) / 2, y), text, font=f, fill=fill)
    return box[3] - box[1]


def caption(draw, lines, y, size=62, pad=22):
    f = font(size)
    for line in lines:
        box = draw.textbbox((0, 0), line, font=f)
        w, h = box[2] - box[0], box[3] - box[1]
        x = (W - w) / 2
        draw.rectangle([x - pad, y - pad * 0.6, x + w + pad, y + h + pad * 0.9], fill=(0, 0, 0))
        draw.text((x, y), line, font=f, fill=(255, 255, 255))
        y += h + pad * 2.1
    return y


def placeholder_clip(draw, label):
    draw.rectangle([0, 0, W, H], fill=(24, 24, 26))
    f = font(44, bold=False)
    for i, line in enumerate(label):
        box = draw.textbbox((0, 0), line, font=f)
        draw.text(((W - box[2]) / 2, H / 2 - 60 + i * 62), line, font=f, fill=(120, 120, 128))
    draw.rectangle([60, 60, W - 60, H - 60], outline=(70, 70, 76), width=4)


def shot_frame(name):
    im = Image.open(SHOTS / name).convert("RGB")
    scale = W / im.width
    im = im.resize((W, int(im.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), (18, 18, 18))
    canvas.paste(im, (0, (H - im.height) // 2))
    return canvas


def write(frame, index):
    frame.save(FRAMES / f"f{index:04d}.png")


index = 0

for _ in range(3 * FPS):
    frame = Image.new("RGB", (W, H), (24, 24, 26))
    draw = ImageDraw.Draw(frame)
    placeholder_clip(draw, ["[ your stitched TikTok clip goes here ]", "keep the original sound loud"])
    caption(draw, ["this sound is", "everywhere"], 300)
    write(frame, index); index += 1

for _ in range(3 * FPS):
    frame = Image.new("RGB", (W, H), (24, 24, 26))
    draw = ImageDraw.Draw(frame)
    placeholder_clip(draw, ["[ same clip keeps running ]", "no cut here"])
    caption(draw, ["nobody in the", "comments knows", "the name"], 260)
    write(frame, index); index += 1

for name, seconds in (("shot-a.png", 2), ("shot-b.png", 2), ("shot-c.png", 2)):
    base = shot_frame(name)
    for _ in range(seconds * FPS):
        write(base.copy(), index); index += 1

for step in range(3 * FPS):
    frame = Image.new("RGB", (W, H), INK)
    draw = ImageDraw.Draw(frame)
    draw.rectangle([0, H * 0.30, W, H * 0.62], fill=BRAND)
    f_small = font(46)
    box = draw.textbbox((0, 0), "ZAYLO", font=f_small)
    draw.text(((W - box[2]) / 2, H * 0.345), "ZAYLO", font=f_small, fill=INK)
    f_big = font(96)
    box = draw.textbbox((0, 0), "MONTAGEM", font=f_big)
    draw.text(((W - box[2]) / 2, H * 0.405), "MONTAGEM", font=f_big, fill=INK)
    box = draw.textbbox((0, 0), "URANIUM", font=f_big)
    draw.text(((W - box[2]) / 2, H * 0.485), "URANIUM", font=f_big, fill=INK)
    f_tag = font(52)
    box = draw.textbbox((0, 0), "syntracks.app", font=f_tag)
    draw.text(((W - box[2]) / 2, H * 0.70), "syntracks.app", font=f_tag, fill=BRAND)
    write(frame, index); index += 1

print("frames:", index, "=", round(index / FPS, 1), "seconds")

target = OUT / "syntracks-test.mp4"
subprocess.run([
    r"C:\ffmpeg\bin\ffmpeg.exe", "-y", "-loglevel", "error",
    "-framerate", str(FPS), "-i", str(FRAMES / "f%04d.png"),
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "20",
    str(target),
], check=True)
print("written:", target, target.stat().st_size // 1024, "KB")
