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


# ---- the test miner (dev, creative only): a stand-in for the Build Gun miners of work package 5, for the node
# selftests. 3 by 3, 2 high: a deck of andesite casing on four corner legs, standing over a node. The node's cell (the
# middle of the bottom row) and the four cells beside it are left out of the template, so the node stays put.
def test_miner():
    CASING = "create:andesite_casing"
    c = {}
    for x in (0, 2):
        for z in (0, 2):
            c[(x, 0, z)] = CASING
    for x in range(3):
        for z in range(3):
            c[(x, 1, z)] = CASING
    c[(1, 1, 2)] = "siftec:building_controller[facing=south]"
    return structure(c)


# ---- work package 5: the first buildings. Satisfactory's shapes in Create's casings: orange (copper casing) and dark grey
# (railway casing) with framed glass to see the machines, industrial iron for trim. Belts attach at ground level: every
# port is in the bottom row, on the front (outputs) or the back (inputs), as Satisfactory's machines have them.
CASE, DARK, GLASS, TRIM, BRASS = "create:copper_casing", "create:railway_casing", "create:framed_glass", "create:industrial_iron_block", "create:brass_casing"
AIR = "minecraft:air"
CONTROLLER = "siftec:building_controller[facing=south]"


class Frame:
    """A template drawn in the Build Gun frame: x to the right of the middle column, y up from the bottom row, z from the
    front row (where the player stands) away. Directions in block states are as built facing north: south is the front."""

    def __init__(self, width, depth):
        assert width % 2 == 1, "a building is an odd number of blocks wide, so it has a middle column"
        self.w, self.d = width, depth
        self.cells, self.nbt = {}, {}

    def file(self, x, y, z):
        return (x + self.w // 2, y, self.d - 1 - z)

    def set(self, x, y, z, spec):
        self.cells[self.file(x, y, z)] = spec

    def box(self, x0, y0, z0, x1, y1, z1, spec):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, spec)

    def walls(self, y0, y1, corner, side, x0=None, x1=None, z0=0, z1=None):
        """The outer walls from y0 to y1: corner pillars and side panels, the inside left as air."""
        x0 = -(self.w // 2) if x0 is None else x0
        x1 = self.w // 2 if x1 is None else x1
        z1 = self.d - 1 if z1 is None else z1
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                edge_x, edge_z = x in (x0, x1), z in (z0, z1)
                for y in range(y0, y1 + 1):
                    self.set(x, y, z, corner if edge_x and edge_z else side if edge_x or edge_z else AIR)

    def port(self, x, y, z, facing, kind, target):
        """A port facing out of the building, feeding or draining the cell at target (Build Gun frame)."""
        self.set(x, y, z, f"siftec:building_port[facing={facing},kind={kind}]")
        self.nbt[self.file(x, y, z)] = {"Target": IntArray(self.file(*target))}

    def void(self, x, y, z):
        """A cell that is not part of the building (a node it stands over)."""
        self.set(x, y, z, "minecraft:structure_void")

    def done(self):
        return structure(self.cells, self.nbt)


def constructor():
    # 5 wide, 5 deep, 6 high: a Mechanical Press over a Basin in the middle, turned by the motor beside it; input at the
    # back, output at the front, both on the Basin. The controller's panel is on the front, right of the output.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.walls(1, 4, CASE, GLASS)
    f.box(-2, 5, 0, 2, 5, 4, CASE)
    f.set(0, 1, 2, "create:basin")
    f.set(0, 3, 2, "create:mechanical_press[facing=west]")
    f.set(1, 3, 2, "siftec:building_motor[facing=west]")
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 2))
    f.port(0, 0, 4, "north", "item_in", (0, 1, 2))
    return f.done()


def smelter():
    # 5 wide, 5 deep, 6 high: the motor in the left wall turns an Encased Fan that blows through a lava cell onto a Depot
    # (Create's bulk blasting). The controller feeds the Depot and takes the ingots off; the ports work on the controller.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.walls(1, 4, CASE, GLASS)
    f.box(-2, 5, 0, 2, 5, 4, CASE)
    f.set(-2, 1, 2, "siftec:building_motor[facing=east]")
    f.set(-1, 1, 2, "create:encased_fan[facing=east]")
    f.set(0, 1, 2, "minecraft:lava")
    f.set(1, 1, 2, "create:depot")
    # the lava is closed in on every side but the fan's and the Depot's
    for at in ((0, 2, 2), (0, 1, 1), (0, 1, 3)):
        f.set(*at, TRIM)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 0))
    f.port(0, 0, 4, "north", "item_in", (1, 1, 0))
    return f.done()


def miner(height, corner, deck, side):
    # 5 wide, 5 deep: four legs round the node, a deck over it with the Drill pointing down at the node, the motor on the
    # Drill and a tower round them; a chute down to the output port at the front. The node's own cell is left out.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, corner)
    f.void(0, 0, 2)
    f.box(-2, 1, 0, 2, 1, 4, deck)
    f.set(0, 1, 2, "create:mechanical_drill[facing=down]")
    f.set(1, 1, 0, CONTROLLER)
    f.walls(2, height - 2, corner, side, -1, 1, 1, 3)
    f.set(0, 2, 2, "siftec:building_motor[facing=down]")
    f.set(0, 2, 1, "create:chute")
    f.box(-1, height - 1, 1, 1, height - 1, 3, corner)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 0))
    return f.done()


def portable_miner():
    # 3 by 3, 3 high: a small drill rig with no power on four legs over the node; the ore drops on the ground in front
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, AIR)
    for x in (-1, 1):
        for z in (0, 2):
            f.set(x, 0, z, "create:andesite_casing")
    f.void(0, 0, 1)
    f.box(-1, 1, 0, 1, 1, 2, "create:andesite_casing")
    f.set(0, 1, 1, "create:mechanical_drill[facing=down]")
    f.box(-1, 2, 0, 1, 2, 2, AIR)
    f.set(0, 2, 1, CONTROLLER)
    return f.done()


def storage(depth, vaults):
    # 3 wide: Item Vaults along the middle, closed in; output at the front and input at the back, both on the vault
    f = Frame(3, depth)
    f.box(-1, 0, 0, 1, 0, depth - 1, DARK)
    f.walls(1, 1, CASE, CASE)
    f.box(-1, 2, 0, 1, 2, depth - 1, CASE)
    for z in range(2, 2 + vaults):
        f.set(0, 1, z, "create:item_vault[axis=z,large=false]")
    f.set(0, 1, 0, GLASS)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 2))
    f.port(0, 0, depth - 1, "north", "item_in", (0, 1, 2))
    return f.done()


BUILT = {"test_building": test_building, "test_miner": test_miner,
         "constructor": constructor, "smelter": smelter,
         "miner_mk1": lambda: miner(8, CASE, DARK, GLASS), "miner_mk2": lambda: miner(9, TRIM, DARK, GLASS),
         "miner_mk3": lambda: miner(10, BRASS, TRIM, GLASS), "portable_miner": portable_miner,
         "storage_container": lambda: storage(5, 1), "industrial_storage_container": lambda: storage(6, 2)}

if __name__ == "__main__":
    for bid, make in BUILT.items():
        write_nbt(os.path.join(ROOT, bid + ".nbt"), make())
        print("wrote", bid)
