"""
Renders the Play Store graphics from the HTML sources in this folder.

    python render.py            # renders everything
    python render.py icon       # renders only names containing "icon"

Uses headless Chrome (or Edge) with a throw-away profile, so it never touches
your normal browser. Output PNGs go into ../icon, ../feature-graphic and ../screenshots/phone.
"""
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
GRAPHICS = os.path.dirname(HERE)

BROWSERS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

# (source html, output png relative to graphics/, width, height, keep transparency)
JOBS = [
    ("icon.html", "icon/icon_512.png", 512, 512, False),
    ("feature_graphic.html", "feature-graphic/feature_graphic_1024x500.png", 1024, 500, False),
    ("screenshot.html?s=1", "screenshots/phone/01_gallery.png", 1080, 1920, False),
    ("screenshot.html?s=2", "screenshots/phone/02_smart_tools.png", 1080, 1920, False),
    ("screenshot.html?s=3", "screenshots/phone/03_ai_cutout.png", 1080, 1920, False),
    ("screenshot.html?s=4", "screenshots/phone/04_copy_text.png", 1080, 1920, False),
    ("screenshot.html?s=5", "screenshots/phone/05_clean_up.png", 1080, 1920, False),
    ("screenshot.html?s=6", "screenshots/phone/06_video_player.png", 1080, 1920, False),
    ("screenshot.html?s=7", "screenshots/phone/07_themes.png", 1080, 1920, False),
    ("screenshot.html?s=8", "screenshots/phone/08_all_in_one.png", 1080, 1920, False),
]


def browser():
    for b in BROWSERS:
        if os.path.exists(b):
            return b
    sys.exit("Edge or Chrome not found")


def render(src, out, w, h):
    page, _, query = src.partition("?")
    url = "file:///" + os.path.join(HERE, page).replace("\\", "/") + ("?" + query if query else "")
    out_path = os.path.normpath(os.path.join(GRAPHICS, out))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    profile = tempfile.mkdtemp(prefix="playgfx_")
    try:
        subprocess.run(
            [browser(), "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
             "--force-device-scale-factor=1", f"--user-data-dir={profile}",
             "--default-background-color=00000000", "--virtual-time-budget=4000",
             f"--window-size={w},{h}", f"--screenshot={out_path}", url],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    # Play requires exact sizes and (for icon / feature graphic) no transparency.
    img = Image.open(out_path).convert("RGBA")
    if img.size != (w, h):
        img = img.crop((0, 0, w, h))
    flat = Image.new("RGB", img.size, (15, 17, 20))
    flat.paste(img, mask=img.split()[3])
    flat.save(out_path, optimize=True)
    print(f"{out:55} {flat.size[0]}x{flat.size[1]}  {os.path.getsize(out_path) // 1024} KB")


if __name__ == "__main__":
    only = sys.argv[1] if len(sys.argv) > 1 else ""
    for src, out, w, h, _ in JOBS:
        if only in src or only in out:
            render(src, out, w, h)
