#!/usr/bin/env python3
"""Writes the structure templates of the buildings that are drawn here rather than built in game:
src/main/resources/data/siftec/structure/building/<id>.nbt, in the format a Structure Block saves.

A template is authored facing north: the player stands on its south side (largest z). It holds exactly one
siftec:building_controller, its ports (siftec:building_port, facing out of the building, kind item_in, item_out,
fluid_in or fluid_out), the Create machines inside, the shell, and air for the inside space that has to be free.
A port feeds or drains the cell just inside it, or the cell given as {"Target": [x, y, z]} in its block entity data.

Run from outside the repository: cd /tmp && python3 -I -B /path/to/SIFTEC/tools/structures.py
"""
import gzip, io, os, struct

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "data", "siftec", "structure", "building")
DATA_VERSION = 5023  # what the game's own 26.3 structures carry; the game upgrades older files when it loads them

# ---- a minimal NBT writer (big-endian, gzip with no timestamp so the output never changes between runs)
END, BYTE, INT, STRING, LIST, COMPOUND, INT_ARRAY = 0, 1, 3, 8, 9, 10, 11


class IntArray(list):
    pass


def _tag_type(v):
    if isinstance(v, IntArray): return INT_ARRAY
    if isinstance(v, bool): return BYTE
    if isinstance(v, int): return INT
    if isinstance(v, str): return STRING
    if isinstance(v, list): return LIST
    if isinstance(v, dict): return COMPOUND
    raise TypeError(v)


def _string(out, s):
    b = s.encode("utf-8")
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def _payload(out, v):
    t = _tag_type(v)
    if t == BYTE: out.write(struct.pack(">b", int(v)))
    elif t == INT: out.write(struct.pack(">i", v))
    elif t == STRING: _string(out, v)
    elif t == INT_ARRAY:
        out.write(struct.pack(">i", len(v)))
        for x in v: out.write(struct.pack(">i", x))
    elif t == LIST:
        inner = _tag_type(v[0]) if v else END
        out.write(struct.pack(">bi", inner, len(v)))
        for x in v: _payload(out, x)
    elif t == COMPOUND:
        for k, x in v.items():
            out.write(struct.pack(">b", _tag_type(x)))
            _string(out, k)
            _payload(out, x)
        out.write(struct.pack(">b", END))


def write_nbt(path, root):
    raw = io.BytesIO()
    raw.write(struct.pack(">b", COMPOUND))
    _string(raw, "")
    _payload(raw, root)
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0, filename="") as g:
        g.write(raw.getvalue())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(buf.getvalue())


def structure(cells, nbt=None):
    """cells: {(x, y, z): "namespace:block[prop=value,...]"}; nbt: {(x, y, z): block entity data}."""
    nbt = nbt or {}
    palette, index, blocks = [], {}, []
    for pos in sorted(cells, key=lambda p: (p[1], p[2], p[0])):
        spec = cells[pos]
        if spec not in index:
            name, _, props = spec.partition("[")
            entry = {"id": name}  # the 26.3 block state keys ("id", "properties"), as the game's own structures have them
            if props:
                entry["properties"] = dict(kv.split("=") for kv in props.rstrip("]").split(","))
            index[spec] = len(palette)
            palette.append(entry)
        b = {"pos": list(pos), "state": index[spec]}
        if pos in nbt: b["nbt"] = nbt[pos]
        blocks.append(b)
    size = [max(p[i] for p in cells) + 1 for i in range(3)]
    return {"DataVersion": DATA_VERSION, "size": size, "palette": palette, "blocks": blocks, "entities": []}


# ---- the test building (dev, creative only): the framework's selftests place, turn, nudge and dismantle it.
# 3 wide, 5 high, 3 deep: a Mechanical Press over a Basin in an andesite-casing frame with framed glass, an item
# input on the front and an item output on the back, both on the basin, and the controller in the front of the floor.
def test_building():
    CASING, GLASS = "create:andesite_casing", "create:framed_glass"
    c = {}
    for x in range(3):
        for z in range(3):
            c[(x, 0, z)] = CASING
            c[(x, 4, z)] = CASING
            for y in (1, 2, 3):
                corner = x != 1 and z != 1
                c[(x, y, z)] = CASING if corner else GLASS
    c[(1, 0, 2)] = "siftec:building_controller[facing=south]"
    c[(1, 1, 1)] = "create:basin"
    c[(1, 1, 2)] = "siftec:building_port[facing=south,kind=item_in]"
    c[(1, 1, 0)] = "siftec:building_port[facing=north,kind=item_out]"
    c[(1, 2, 1)] = "minecraft:air"
    c[(1, 3, 1)] = "create:mechanical_press[facing=north]"
    return structure(c)


BUILT = {"test_building": test_building}

if __name__ == "__main__":
    for bid, make in BUILT.items():
        write_nbt(os.path.join(ROOT, bid + ".nbt"), make())
        print("wrote", bid)
