#!/usr/bin/env python3
"""SIFTEC steel's textures, in Create's industrial iron palette (the Steel Casing is Create's industrial iron block itself).

The Steel Ingot, Sheet and Nugget are drawn here pixel by pixel. The steel-encased shaft and cogwheels' trim is Create's
brass trim redrawn in the same palette, read from a Create checkout:

    python3 tools/steel_art.py <create>/src/client/resources/assets/create/textures/block

The output is committed; CI does not run this (it has no Pillow), and gen_assets.py only points models at the files."""
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import ASSETS

# Create's industrial_iron_block.png and its top, dark to light
PALETTE = [(43, 43, 46), (53, 52, 56), (63, 62, 66), (68, 68, 68), (73, 72, 72), (79, 79, 79), (89, 88, 88), (103, 97, 97),
           (117, 113, 113), (129, 126, 126), (141, 139, 139), (152, 151, 151)]

# '.' is clear; 0-9, a and b are the palette from dark to light
INGOT = """
................
................
................
................
.......00000....
.....00ab9a90...
...00ab999998a0.
..0ab99988877b0.
.0a99887766650..
0b98776655540...
06776655440.....
.0554430000.....
..00000.........
................
................
................"""
SHEET = """
................
................
..000000000000..
..0bbaaaaaaaa60.
..0b98888888760.
..0a8b766667760.
..0a87777777760.
..0a87777777760.
..0a87777777760.
..0a87777777760.
..0a87666667b60.
..0a88777777760.
..0766666666650.
..000000000000..
................
................"""
NUGGET = """
................
................
................
................
................
................
.......000......
.....00ab90.....
....0ab99870....
....0a987650....
....07665540....
.....0044300....
......0000......
................
................
................"""
ITEMS = {"steel_ingot": INGOT, "steel_sheet": SHEET, "steel_nugget": NUGGET}
# Create's brass trim -> SIFTEC's steel trim (the encased blocks' models name these, gen_assets.py)
TRIM = {"encased_brass_border_1.png": "steel_encased_border_1", "encased_brass_border_2.png": "steel_encased_border_2",
        "brass_gearbox_3.png": "steel_gearbox", "brass_encased_cogwheel_side.png": "steel_encased_cogwheel_window",
        "brass_encased_cogwheel_side_connected/3.png": "steel_encased_cogwheel_window_3",
        "brass_encased_large_cogwheel_side.png": "steel_encased_large_cogwheel_window"}


def drawn(art):
    rows = art.strip("\n").split("\n")
    if len(rows) != 16 or any(len(r) != 16 for r in rows):
        raise SystemExit("a steel sprite must be 16 rows of 16")
    out = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c != ".":
                out.putpixel((x, y), PALETTE[int(c, 16)] + (255,))
    return out


def recoloured(path):
    """The same picture, each pixel the palette colour at its brightness among the picture's own."""
    src = Image.open(path).convert("RGBA")
    lum = lambda p: 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]
    seen = [lum(p) for p in (src.getpixel((x, y)) for y in range(src.height) for x in range(src.width)) if p[3] > 0]
    lo, hi = min(seen), max(seen)
    out = Image.new("RGBA", src.size)
    for y in range(src.height):
        for x in range(src.width):
            p = src.getpixel((x, y))
            if p[3] == 0:
                continue
            t = 0 if hi == lo else (lum(p) - lo) / (hi - lo)
            out.putpixel((x, y), PALETTE[min(len(PALETTE) - 1, round(t * (len(PALETTE) - 1)))] + (p[3],))
    return out


def main():
    os.makedirs(os.path.join(ASSETS, "textures", "item"), exist_ok=True)
    for iid, art in ITEMS.items():
        drawn(art).save(os.path.join(ASSETS, "textures", "item", iid + ".png"))
    if len(sys.argv) > 1:
        for src, name in TRIM.items():
            recoloured(os.path.join(sys.argv[1], src)).save(os.path.join(ASSETS, "textures", "block", name + ".png"))
    print(f"{len(ITEMS)} steel items" + (f", {len(TRIM)} steel trim textures" if len(sys.argv) > 1 else ""))


if __name__ == "__main__":
    main()
