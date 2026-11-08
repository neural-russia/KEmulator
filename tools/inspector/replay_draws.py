#!/usr/bin/env python3
"""Replay KEmulator Game Data Inspector draw calls as lossless PNG frame images.

This is a diagnostic renderer, not an animation-format decoder.
Requires: python -m pip install pillow

Usage:
  python tools/inspector/replay_draws.py game-inspector-output --list-targets
  python tools/inspector/replay_draws.py game-inspector-output --target native@abc123 --size 240x320
"""
import argparse
import collections
import json
from pathlib import Path

from PIL import Image


def events(path: Path):
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as error:
                print(f"Skipping truncated line {line_number}: {error}")


def transformed(image: Image.Image, code: int) -> Image.Image:
    # MIDP Sprite transformation constants:
    # 0 NONE, 1 MIRROR_ROT180, 2 MIRROR, 3 ROT180,
    # 4 MIRROR_ROT270, 5 ROT90, 6 ROT270, 7 MIRROR_ROT90.
    mirror = {
        0: None,
        1: Image.Transpose.FLIP_LEFT_RIGHT,
        2: Image.Transpose.FLIP_LEFT_RIGHT,
        3: None,
        4: Image.Transpose.FLIP_LEFT_RIGHT,
        5: None,
        6: None,
        7: Image.Transpose.FLIP_LEFT_RIGHT,
    }
    if code in (1, 2, 4, 7):
        image = image.transpose(mirror[code])
    if code in (1, 3):
        image = image.transpose(Image.Transpose.ROTATE_180)
    elif code in (5, 7):
        image = image.transpose(Image.Transpose.ROTATE_270)
    elif code in (4, 6):
        image = image.transpose(Image.Transpose.ROTATE_90)
    return image


def draw(canvas: Image.Image, command: dict, images: Path, cache: dict) -> bool:
    path = images / command.get("file", "")
    if not path.is_file():
        return False
    if path not in cache:
        cache[path] = Image.open(path).convert("RGBA")
    source = cache[path]
    op = command.get("op")
    if op == "drawImage":
        item = source
        x, y = int(command["x"]), int(command["y"])
    elif op == "drawRGB":
        item = source
        x, y = map(int, command["dst"][:2])
    elif op == "drawRegion":
        sx, sy, w, h = command["src"]
        item = source.crop((sx, sy, sx + w, sy + h))
        item = transformed(item, command.get("transform", 0))
        x, y, dw, dh = command["dst"]
        # Scaled MIDP _drawRegion is an emulator extension.
        if (dw, dh) != (w, h):
            if command.get("transform", 0) in (4, 5, 6, 7):
                dw, dh = dh, dw
            if dw > 0 and dh > 0:
                item = item.resize((dw, dh), Image.Resampling.NEAREST)
        a = int(command.get("anchor", 20))
        if a & 8:      # RIGHT
            x -= item.width
        elif a & 1:    # HCENTER
            x -= item.width // 2
        if a & 32:     # BOTTOM
            y -= item.height
        elif a & 2:    # VCENTER
            y -= item.height // 2
    else:
        return False

    x += int(command.get("tx", 0))
    y += int(command.get("ty", 0))
    # Clip destination; source pixels outside it must not leak.
    clip = command.get("clip")
    if clip:
        cx, cy, cw, ch = clip
        cx, cy, cw, ch = map(int, (cx, cy, cw, ch))
        if cw <= 0 or ch <= 0:
            return True
        x0, y0 = max(x, cx), max(y, cy)
        x1, y1 = min(x + item.width, cx + cw), min(y + item.height, cy + ch)
        if x1 <= x0 or y1 <= y0:
            return True
        item = item.crop((x0 - x, y0 - y, x1 - x, y1 - y))
        x, y = x0, y0

    # alpha_composite correctly blends semi-transparent sprite pixels.
    if x >= canvas.width or y >= canvas.height or x + item.width <= 0 or y + item.height <= 0:
        return True
    l, t, r, b = max(0, x), max(0, y), min(canvas.width, x + item.width), min(canvas.height, y + item.height)
    item = item.crop((l - x, t - y, r - x, b - y))
    canvas.alpha_composite(item, (l, t))
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, help="Inspector output directory")
    parser.add_argument("--list-targets", action="store_true", help="List targets without rendering")
    parser.add_argument("--target", help="Target bitmap, e.g. native@1a2b3c or img_42")
    parser.add_argument("--size", default="240x320", help="Output size in pixels, e.g. 240x320")
    parser.add_argument("--max-frames", type=int, default=300)
    parser.add_argument("--out", type=Path, default=Path("replayed-frames"))
    args = parser.parse_args()
    trace = args.capture / "draws.jsonl"
    if not trace.exists():
        parser.error("draws.jsonl not found. Enable Game trace and run the game first.")
    counts = collections.Counter(
        e.get("target") for e in events(trace) if e.get("op") in ("drawImage", "drawRegion", "drawRGB")
    )
    print("Drawing targets (number of commands):")
    for key, number in counts.most_common(25):
        print(f"  {key}: {number}")
    if args.list_targets:
        return
    if not counts:
        parser.error("No captured drawing events found.")
    target = args.target or counts.most_common(1)[0][0]
    width, height = map(int, args.size.lower().split("x"))
    args.out.mkdir(parents=True, exist_ok=True)
    cache = {}
    frame = None
    frame_image = None
    saved = 0
    drawn = 0
    skipped = 0

    def save_frame():
        nonlocal saved
        if frame_image is not None and saved < args.max_frames:
            frame_image.save(args.out / f"frame_{frame:06d}.png")
            saved += 1

    for e in events(trace):
        if e.get("target") != target or e.get("op") not in ("drawImage", "drawRegion", "drawRGB"):
            continue
        n = e.get("frame", 0)
        if frame != n:
            save_frame()
            if saved >= args.max_frames:
                break
            frame = n
            frame_image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        if draw(frame_image, e, args.capture / "images", cache):
            drawn += 1
        else:
            skipped += 1
    if saved < args.max_frames:
        save_frame()
    print(f"Rendered {saved} frames for {target}; draws={drawn}, missing/unsupported={skipped}")
    print(f"Output directory: {args.out.resolve()}")
    print("Note: fillRect, direct graphics primitives and offscreen dependencies")
    print("are not replayed by this minimal previewer; output may differ from the game.")


if __name__ == "__main__":
    main()
