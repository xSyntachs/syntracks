"""Renders marketing/reveal.html into a vertical clip by stepping the page clock frame by frame."""

import argparse
import pathlib
import shutil
import subprocess
import sys
import urllib.parse

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent
FFMPEG = shutil.which("ffmpeg") or r"C:\ffmpeg\bin\ffmpeg.exe"
W, H, FPS = 1080, 1920, 30


def render(artist, title, foot, seconds, out_path, keep_frames=False):
    frames = ROOT / "_frames"
    if frames.exists():
        shutil.rmtree(frames)
    frames.mkdir()

    query = urllib.parse.urlencode({"artist": artist, "title": title, "foot": foot})
    url = (ROOT / "reveal.html").as_uri() + "?" + query
    total = int(seconds * FPS)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": W, "height": H}, device_scale_factor=1)
        page.goto(url)
        page.wait_for_timeout(1200)
        page.evaluate("document.getAnimations().forEach(a => a.pause())")

        for index in range(total):
            page.evaluate("ms => document.getAnimations().forEach(a => a.currentTime = ms)",
                          index / FPS * 1000)
            page.screenshot(path=str(frames / f"f{index:04d}.png"), omit_background=True)
            if index % 60 == 0:
                print(f"  {index}/{total}", flush=True)
        browser.close()

    subprocess.run([
        FFMPEG, "-y", "-loglevel", "error",
        "-framerate", str(FPS), "-i", str(frames / "f%04d.png"),
        "-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-b:v", "0", "-crf", "28",
        str(out_path.with_suffix(".webm")),
    ], check=True)

    subprocess.run([
        FFMPEG, "-y", "-loglevel", "error",
        "-f", "lavfi", "-i", f"color=c=0x18181A:s={W}x{H}:r={FPS}:d={seconds}",
        "-framerate", str(FPS), "-i", str(frames / "f%04d.png"),
        "-filter_complex", "[0][1]overlay=shortest=1",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
        str(out_path.with_suffix(".mp4")),
    ], check=True)

    if not keep_frames:
        shutil.rmtree(frames)
    for suffix in (".webm", ".mp4"):
        made = out_path.with_suffix(suffix)
        print(f"{made.name}: {made.stat().st_size // 1024} KB")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--artist", default="ZAYLO")
    parser.add_argument("--title", default="Montagem Uranium")
    parser.add_argument("--foot", default="more songs nobody could name")
    parser.add_argument("--seconds", type=float, default=15.0)
    parser.add_argument("--out", default=str(ROOT / "out" / "reveal"))
    parser.add_argument("--keep-frames", action="store_true")
    args = parser.parse_args()

    target = pathlib.Path(args.out)
    target.parent.mkdir(parents=True, exist_ok=True)
    render(args.artist, args.title, args.foot, args.seconds, target, args.keep_frames)
