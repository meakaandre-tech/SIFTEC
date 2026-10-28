#!/usr/bin/env python3
"""Writes the structure templates of the buildings that are drawn here rather than built in game:
src/main/resources/data/siftec/structure/building/<id>.nbt, in the format a Structure Block saves.

A template is authored facing north: the player stands on its south side (largest z). It holds exactly one
siftec:building_controller, its ports (siftec:building_port, facing out of the building, kind item_in, item_out,
fluid_in or fluid_out), the Create machines inside, the shell, and air for the inside space that has to be free.
A port feeds or drains the cell just inside it, or the cell given as {"Target": [x, y, z]} in its block entity data.

Run from outside the repository: cd /tmp && python3 -I -B /path/to/SIFTEC/tools/structures.py
"""
import gzip, io, os, struct, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import DATAPACK

ROOT = os.path.join(DATAPACK, "structure", "building")
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


# ---- the Build Gun's buildings, in the style of Polartt's Satisfactory-in-Minecraft builds (design/building-style.md has
# the style list taken from the reference shots). Dark framed panels (Create's industrial iron) are the body, light framed
# panels (iron blocks) the bands, flat orange the big masses (hoods, shoulders, pilasters), framed glass shows the Create
# machines inside; half-block edges come from dark stairs and slabs, light slabs and iron trapdoors, with metal girders,
# industrial iron windows as vents, copper ladders and iron bars as the small kit. Yellow and black hazard stripes mark
# feet, posts and bumpers, and each building stands on a light grey pad with L-shaped hazard marks at its corners. Belts
# and pipes attach at the ports, at ground level: outputs on the front, inputs on the back, as Satisfactory's machines
# have them. A building's paint (building/Paint.java) recolours its orange, its industrial iron and its iron blocks.
ORANGE, DARK, LIGHT, GLASS = "minecraft:orange_concrete", "create:industrial_iron_block", "minecraft:iron_block", "create:framed_glass"
YELLOW, BLACK, PAD = "minecraft:yellow_concrete", "minecraft:black_concrete", "minecraft:light_gray_concrete"
VENT = "create:industrial_iron_window"
BRASS = "create:brass_casing"
AIR = "minecraft:air"
CONTROLLER = "siftec:building_controller[facing=south]"
# work package 6: a powered building is turned by Create: New Age's motors on the grid, and joins the grid at the wire
# connector on its roof (one wire, as Satisfactory's machines take one power line)
MOTOR = "create_new_age:reinforced_motor[facing={}]"
EXTENSION = "create_new_age:basic_motor_extension[facing={}]"
CONNECTOR = "create_new_age:electrical_connector[facing=up,mode=inert]"
TANK = "create:fluid_tank[bottom=true,shape=window,top=true]"
BASIN, BRASS_BASIN = "create:basin[facing=down]", "siftec:brass_basin[facing=down]"
COG = "create:cogwheel[axis=y]"
MIXER, DEPOT, SPOUT, DRAIN = "create:mechanical_mixer", "create:depot", "create:spout", "create:item_drain"
BURNER = "create:blaze_burner[blaze=smouldering,facing=south]"
# a deployer facing down whose shaft runs front to back (along z), so a row of them is turned through shafts between them
DEPLOYER = "create:deployer[axis_along_first=false,facing=down]"
SHAFT_Z = "create:shaft[axis=z]"
# the half-block kit
GIRDER = "create:metal_girder[axis=y,bottom=true,top=true,waterlogged=false,x=false,z=false]"
RAIL = "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]"
# which way a stair or ladder on the ring round a box faces to lean on (or stand off) the box: in from the front ring it
# faces north (the box is behind it), in from the back ring south, from the left ring east and from the right ring west
INWARD = {"front": "north", "back": "south", "left": "east", "right": "west"}
OUTWARD = {"front": "south", "back": "north", "left": "west", "right": "east"}


def dstair(facing, half="bottom"):
    """A dark stair (polished cut deepslate), rising toward `facing`."""
    return f"create:polished_cut_deepslate_stairs[facing={facing},half={half},shape=straight,waterlogged=false]"


def dslab(kind="bottom"):
    return f"create:polished_cut_deepslate_slab[type={kind},waterlogged=false]"


def lslab(kind="bottom"):
    return f"minecraft:smooth_stone_slab[type={kind},waterlogged=false]"


def sill(facing="north"):
    """A light lip: an iron trapdoor shut in the top half of its cell."""
    return f"minecraft:iron_trapdoor[facing={facing},half=top,open=false,powered=false,waterlogged=false]"


def ladder(facing):
    return f"create:copper_ladder[facing={facing},waterlogged=false]"


class Frame:
    """A template drawn in the Build Gun frame: x to the right of the middle column, y up from the bottom row, z from the
    front row (where the player stands) away. Directions in block states are as built facing north: south is the front,
    east is to the player's right.

    The working parts (machines, ports, the controller, the connector, the inside air they need) go in first through
    m(), port() and void(); they are fixed, and the look drawn round them with set() never overwrites them. set() also
    keeps off the cell in front of the controller (so its face stays clear) and anything outside the footprint."""

    def __init__(self, width, depth, even=False):
        # a building is an odd number of blocks wide, so it has a middle column; the Foundations tab's 8-wide pieces
        # (even=True) have theirs just right of the middle
        assert width % 2 == 1 or even, "a building is an odd number of blocks wide, so it has a middle column"
        self.w, self.d = width, depth
        self.cells, self.nbt = {}, {}
        self.fixed, self.clear = set(), set()

    def file(self, x, y, z):
        return (x + self.w // 2, y, self.d - 1 - z)

    def inside(self, x, y, z):
        fx, fy, fz = self.file(x, y, z)
        return 0 <= fx < self.w and 0 <= fz < self.d and fy >= 0

    def set(self, x, y, z, spec):
        p = self.file(x, y, z)
        if p in self.fixed or p in self.clear or not self.inside(x, y, z):
            return
        self.cells[p] = spec

    def put(self, x, y, z, spec):
        """set(), but only into a cell nothing is drawn in yet."""
        if self.file(x, y, z) not in self.cells:
            self.set(x, y, z, spec)

    def get(self, x, y, z):
        return self.cells.get(self.file(x, y, z))

    def drop(self, x, y, z):
        """Takes a drawn cell out again (not a working part): the cell is no part of the building."""
        p = self.file(x, y, z)
        if p not in self.fixed:
            self.cells.pop(p, None)

    def solid(self, x, y, z):
        s = self.get(x, y, z)
        return s is not None and s != AIR and not s.startswith("minecraft:structure_void")

    def m(self, x, y, z, spec):
        """A working part (machine, inside air, connector...): fixed, the look never draws over it."""
        p = self.file(x, y, z)
        self.cells[p] = spec
        self.fixed.add(p)

    def mbox(self, x0, y0, z0, x1, y1, z1, spec):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.m(x, y, z, spec)

    def controller(self, x, y, z):
        """The controller (facing the front), with the cell in front of it kept clear."""
        self.m(x, y, z, CONTROLLER)
        self.clear.add(self.file(x, y, z - 1))

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
        self.m(x, y, z, f"siftec:building_port[facing={facing},kind={kind}]")
        self.nbt[self.file(x, y, z)] = {"Target": IntArray(self.file(*target))}

    def void(self, x, y, z):
        """A cell that is not part of the building (a node it stands over)."""
        self.m(x, y, z, "minecraft:structure_void")

    # ---- the look
    def pad(self, x0=None, x1=None, z0=0, z1=None, y=0):
        """The floor pad: light grey, with an L of yellow and black hazard stripes at each corner."""
        x0 = -(self.w // 2) if x0 is None else x0
        x1 = self.w // 2 if x1 is None else x1
        z1 = self.d - 1 if z1 is None else z1
        self.box(x0, y, z0, x1, y, z1, PAD)
        arm = 2 if min(x1 - x0, z1 - z0) >= 6 else 1
        for cx, sx in ((x0, 1), (x1, -1)):
            for cz, sz in ((z0, 1), (z1, -1)):
                self.set(cx, y, cz, YELLOW)
                for i in range(1, arm + 1):
                    stripe = BLACK if i % 2 else YELLOW
                    self.set(cx + sx * i, y, cz, stripe)
                    self.set(cx, y, cz + sz * i, stripe)

    def hazard_x(self, x0, x1, y, z):
        """Hazard stripes along a row (yellow and black blocks in turn)."""
        for x in range(x0, x1 + 1):
            self.set(x, y, z, YELLOW if (x - x0) % 2 == 0 else BLACK)

    def hazard_post(self, x, z, y0, y1, top=DARK):
        """A post with hazard stripes on its foot (yellow, black) and `top` above."""
        for y in range(y0, y1 + 1):
            self.set(x, y, z, YELLOW if y == y0 else BLACK if y == y0 + 1 else top)

    def ring(self, x0, z0, x1, z1):
        """The cells round a box (one out from it), with the side of the box each faces: (x, z, side)."""
        for x in range(x0, x1 + 1):
            yield x, z0 - 1, "front"
            yield x, z1 + 1, "back"
        for z in range(z0, z1 + 1):
            yield x0 - 1, z, "left"
            yield x1 + 1, z, "right"

    def hall(self, x0, z0, x1, z1, y0, y1, side=ORANGE, corner=DARK, sill=DARK, band=LIGHT, pilaster=None,
             front_hazard=True, shoulders=True):
        """A machine hall: a dark sill course (hazard striped along the front, and two rows high on a tall hall),
        panelled walls of `side` with dark corner pillars and a light band under the eaves, pilasters (if given) beside
        the corners, the inside air. Where the pad has room round it: two-high orange shoulders on the sides with a light slab lip,
        hazard posts at their ends, a stair skirt at the back and a slab curb at the
        front."""
        low = y0 + 1 if y1 - y0 >= 5 else y0
        self.walls(y0, low, sill, sill, x0, x1, z0, z1)
        self.walls(low + 1, y1 - 1, corner, side, x0, x1, z0, z1)
        self.walls(y1, y1, corner, band, x0, x1, z0, z1)
        if front_hazard:
            self.hazard_x(x0 + 1, x1 - 1, y0, z0)
        if pilaster:
            if z1 - z0 >= 4:
                for z in (z0 + 1, z1 - 1):
                    self.box(x0, y0 + 1, z, x0, y1, z, pilaster)
                    self.box(x1, y0 + 1, z, x1, y1, z, pilaster)
            if x1 - x0 >= 6:
                for x in (x0 + 1, x1 - 1):
                    self.box(x, y0 + 1, z0, x, y1, z0, pilaster)
                    self.box(x, y0 + 1, z1, x, y1, z1, pilaster)
        if shoulders:
            for x, z, at in self.ring(x0, z0, x1, z1):
                if at in ("left", "right"):
                    if z in (z0, z1):
                        self.hazard_post(x, z, y0, y0 + 1)
                    else:
                        self.set(x, y0, z, ORANGE)
                        self.set(x, y0 + 1, z, ORANGE)
                        self.set(x, y0 + 2, z, lslab())
                elif at == "back":
                    self.set(x, y0, z, dstair(INWARD[at]))
                else:
                    self.set(x, y0, z, dslab())

    def window_front(self, z, x0, x1, y0, y1, spec=GLASS, lip=True):
        """A window in the front or back wall at z, with a light lip under it on the outside where there is room."""
        self.box(x0, y0, z, x1, y1, z, spec)
        if lip and y0 >= 2:
            out = z - 1 if z < self.d // 2 else z + 1
            for x in range(x0, x1 + 1):
                self.set(x, y0 - 1, out, sill("north" if out < z else "south"))

    def window_side(self, x, z0, z1, y0, y1, spec=GLASS):
        self.box(x, y0, z0, x, y1, z1, spec)

    def deck(self, x0, z0, x1, z1, y, eave=True):
        """A roof deck: a dark frame round light panels split by dark ribs every fourth row, and a light slab eave on the
        ring round it where the pad has room."""
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                rim = x in (x0, x1) or z in (z0, z1)
                rib = (x - x0) % 4 == 0 or (z - z0) % 4 == 0
                self.set(x, y, z, DARK if rim or rib else LIGHT)
        if eave:
            for x, z, at in self.ring(x0, z0, x1, z1):
                self.put(x, y, z, lslab())

    def hood(self, x0, z0, x1, z1, y0, y1, spec=ORANGE, slope=True):
        """An orange hood (a box from y0 to y1), its front and back sloping down to the roof under it in dark stairs (only
        where there is roof under the stair)."""
        self.box(x0, y0, z0, x1, y1, z1, spec)
        if not slope:
            return
        for x, z, at in self.ring(x0, z0, x1, z1):
            if at in ("front", "back") and self.solid(x, y0 - 1, z):
                self.put(x, y0, z, dstair(INWARD[at]))

    def chamfer(self, x0, z0, x1, z1, y):
        """Bevels a roof's edge: dark stairs rising inward along its sides, dark slabs at its corners."""
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                ex, ez = x in (x0, x1), z in (z0, z1)
                if ex and ez:
                    self.set(x, y, z, dslab())
                elif ex:
                    self.set(x, y, z, dstair("east" if x == x0 else "west"))
                elif ez:
                    self.set(x, y, z, dstair("north" if z == z0 else "south"))

    def ladder_run(self, x, z, y0, y1, facing):
        for y in range(y0, y1 + 1):
            self.set(x, y, z, ladder(facing))

    def girders(self, x, z, y0, y1):
        for y in range(y0, y1 + 1):
            self.set(x, y, z, GIRDER)

    def drum(self, cx, cz, y0, y1, r, spec, band=None, every=3):
        """A round tank or tower: a disc of radius r (1 is a plus shape, 2 a rounded 5 by 5) from y0 to y1, with a band of
        another block every few rows."""
        for y in range(y0, y1 + 1):
            use = band if band and (y - y0) % every == every - 1 else spec
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    if dx * dx + dz * dz <= r * r + (1 if r > 1 else 0):
                        self.set(cx + dx, y, cz + dz, use)

    def done(self):
        return structure(self.cells, self.nbt)


# ------------------------------------------------------------------------------------------------ work package 5

def constructor():
    # 7 wide, 9 deep, 9 high (Satisfactory's 8 by 10). Inside, a Mechanical Press over a Basin, turned by the motor beside
    # it; input at the back, output at the front, both on the Basin. Outside: an orange hall framed by dark corner pillars and
    # pilasters, a dark lower course and a light eave band on its pad, big framed-glass windows front and back to see the press, orange shoulders down its sides, a
    # ribbed roof deck and an orange hood over the press sloping down in dark stairs; a ladder up the back corner.
    f = Frame(7, 9)
    f.m(0, 1, 4, BASIN)
    f.m(0, 2, 4, AIR)
    f.m(0, 3, 4, "create:mechanical_press[facing=west]")
    f.m(1, 3, 4, MOTOR.format("west"))
    f.controller(1, 1, 1)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 4))
    f.port(0, 0, 8, "north", "item_in", (0, 1, 4))
    f.m(0, 8, 4, CONNECTOR)
    f.pad()
    f.hall(-2, 1, 2, 7, 1, 5)
    f.window_front(1, -1, 1, 2, 4)
    f.window_front(7, -1, 1, 2, 4)
    f.window_side(-2, 3, 5, 3, 4)
    f.window_side(2, 3, 5, 3, 4)
    f.deck(-2, 1, 2, 7, 6)
    f.hood(-1, 3, 1, 5, 7, 7)
    f.set(-2, 7, 1, VENT)
    f.set(2, 7, 1, VENT)
    f.ladder_run(-2, 8, 1, 5, "north")
    return f.done()


def smelter():
    # 7 wide, 9 deep, 12 high (Satisfactory's 6 by 9 with its stack). The motor in the left wall turns an Encased Fan that
    # blows through a lava cell onto a Depot (Create's bulk blasting); the controller feeds the Depot and takes the ingots
    # off, and the ports work on the controller. Outside: a tall dark furnace hall with an orange belly band and a glass
    # front to see the lava, orange shoulders, a small orange feed hood over the front right of the roof, and a round dark
    # chimney with orange bands at the back; a ladder up the back.
    f = Frame(7, 9)
    f.m(-2, 1, 4, MOTOR.format("east"))
    f.m(-1, 1, 4, "create:encased_fan[facing=east]")
    f.m(0, 1, 4, "minecraft:lava")
    f.m(1, 1, 4, DEPOT)
    # the lava is closed in on every side but the fan's and the Depot's
    for at in ((0, 2, 4), (0, 1, 3), (0, 1, 5)):
        f.m(*at, DARK)
    f.controller(1, 1, 1)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(0, 0, 8, "north", "item_in", (1, 1, 1))
    f.m(-1, 8, 3, CONNECTOR)
    f.pad()
    f.hall(-2, 1, 2, 7, 1, 6, side=DARK, pilaster=ORANGE)
    for x, z, at in f.ring(-1, 2, 1, 6):
        if f.get(x, 4, z) is not None and f.get(x, 4, z) != GLASS:
            f.set(x, 4, z, ORANGE)
    f.window_front(1, -1, 1, 2, 3)
    f.window_side(-2, 3, 5, 2, 3)
    f.window_side(2, 3, 5, 2, 3)
    f.deck(-2, 1, 2, 7, 7)
    f.hood(1, 1, 2, 3, 8, 8)
    f.drum(0, 6, 8, 10, 1, DARK, band=ORANGE, every=2)
    f.set(0, 11, 6, VENT)
    for x, z in ((-1, 5), (1, 5), (-1, 7), (1, 7)):
        f.set(x, 8, z, dslab())
    f.ladder_run(2, 8, 1, 6, "north")
    return f.done()


def miner(height, side, cap, extension=False):
    # 5 wide, 5 deep (it has to fit over a node's patch): four legs with hazard feet round the node, a deck over it with
    # the Drill pointing down at the node, the motor on the Drill and a tower round them, orange shoulders on both sides
    # of the tower, a wide head on top carried by girders, and a ladder up its back; a chute down to the output port at
    # the front. The node's own cell is left out. The Mk shows in the tower's panels and the head.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    f.void(0, 0, 2)
    f.m(0, 1, 2, "create:mechanical_drill[facing=down,waterlogged=false]")
    f.controller(1, 1, 0)
    f.m(0, 2, 2, MOTOR.format("down"))
    if extension:
        # Miner Mk.3's 45 MW is more than one motor gives: an extension behind it doubles it
        f.m(0, 3, 2, EXTENSION.format("down"))
    f.m(0, 2, 1, "create:chute[facing=down,shape=normal,waterlogged=false]")
    f.mbox(0, 3 + extension, 2, 0, height - 2, 2, AIR)
    f.m(0, height, 2, CONNECTOR)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 0))
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, YELLOW if (x + z) % 4 == 2 else BLACK)
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.hazard_x(-2, 2, 1, 0)
    f.walls(2, height - 2, DARK, side, -1, 1, 1, 3)
    f.box(-1, height - 2, 1, 1, height - 2, 3, LIGHT)
    f.set(0, height - 2, 2, AIR)
    f.window_front(1, 0, 0, 3, height - 3, lip=False)
    for x in (-2, 2):
        f.box(x, 2, 1, x, 3, 3, ORANGE)
        for z in (1, 2, 3):
            f.set(x, 4, z, dstair("east" if x < 0 else "west"))
        f.set(x, 3, 2, VENT)
        f.hazard_post(x, 0, 2, 3)
        f.girders(x, 1, 5, height - 2)
        f.girders(x, 3, 5, height - 2)
        f.girders(x, 4, 2, height - 2)
    f.box(-2, height - 1, 1, 2, height - 1, 3, cap)
    for x in (-2, 2):
        f.set(x, height - 1, 2, dstair("east" if x < 0 else "west", "top"))
    f.set(0, height - 1, 2, cap)
    f.ladder_run(1, 4, 2, height - 2, "north")
    f.set(-1, height - 1, 4, dslab())
    return f.done()


def portable_miner():
    # 3 by 3, 3 high: a small orange drill rig with no power on four legs with hazard feet over the node, dark corners on
    # its deck; the ore drops on the ground in front
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, AIR)
    f.void(0, 0, 1)
    f.m(0, 1, 1, "create:mechanical_drill[facing=down,waterlogged=false]")
    f.box(-1, 2, 0, 1, 2, 2, AIR)
    f.m(0, 2, 1, CONTROLLER)
    for x in (-1, 1):
        for z in (0, 2):
            f.set(x, 0, z, YELLOW if z == 0 else BLACK)
    f.box(-1, 1, 0, 1, 1, 2, ORANGE)
    for x in (-1, 1):
        f.set(x, 1, 2, DARK)
    f.hazard_x(-1, 1, 1, 0)
    return f.done()


def storage(depth, vaults):
    # 3 wide: Item Vaults along the middle in a box of light panels with dark corners and a vent down each side, a lid
    # with an orange spine and dark stair slopes; output at the front and input at the back, both on the vault
    f = Frame(3, depth)
    for z in range(2, 2 + vaults):
        f.m(0, 1, z, "create:item_vault[axis=z,large=false]")
    f.controller(1, 1, 0)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 2))
    f.port(0, 0, depth - 1, "north", "item_in", (0, 1, 2))
    f.box(-1, 0, 0, 1, 0, depth - 1, DARK)
    f.hazard_x(-1, 1, 0, 0)
    f.walls(1, 1, DARK, LIGHT)
    for x in (-1, 1):
        f.set(x, 1, depth // 2, VENT)
    f.set(0, 1, 0, GLASS)
    for z in range(depth):
        f.set(-1, 2, z, dstair("east"))
        f.set(1, 2, z, dstair("west"))
        f.set(0, 2, z, ORANGE)
    f.set(-1, 2, 0, dslab())
    f.set(1, 2, 0, dslab())
    return f.done()


# ------------------------------------------------------------------------------------------------ work package 6: power
TURBINE = "siftec:turbine[facing={}]"
COIL = "create_new_age:generator_coil[axis=x]"
# New Age's own reactor casing and glass draw as missing textures in the Fabric 26.3 port (its connected-texture models),
# so the plant is built from Create's and vanilla blocks until that is fixed
R_GLASS = GLASS


def pole(height, column, band):
    # one block wide: the controller at its foot, a hazard collar over it, a girder mast with a coloured band at the
    # top, the connector on top
    f = Frame(1, 1)
    f.m(0, 0, 0, CONTROLLER)
    f.m(0, height - 1, 0, CONNECTOR)
    for y in range(1, height - 1):
        f.set(0, y, 0, band if y == height - 2 else YELLOW if y == 1 else column)
    return f.done()


def power_tower():
    # 3 by 3, 13 high (Satisfactory's lattice tower): four legs with hazard feet, braced in at the knee with dark stairs,
    # a girder mast with orange collars up to an orange platform with a dark slab rim and a hazard front, the connector
    # on top
    f = Frame(3, 3)
    f.m(0, 0, 0, CONTROLLER)
    f.m(0, 12, 1, CONNECTOR)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    for x in (-1, 1):
        for z in (0, 2):
            f.hazard_post(x, z, 1, 3)
            f.set(x, 4, z, dstair("east" if x < 0 else "west"))
    f.box(0, 1, 1, 0, 2, 1, DARK)
    f.girders(0, 1, 3, 10)
    f.set(0, 5, 1, ORANGE)
    f.set(0, 8, 1, ORANGE)
    f.set(0, 10, 1, DARK)
    f.box(-1, 11, 0, 1, 11, 2, ORANGE)
    f.hazard_x(-1, 1, 11, 0)
    for x, z in ((-1, 2), (1, 2)):
        f.set(x, 11, z, DARK)
    return f.done()


def power_storage():
    # 3 by 3, 6 high: glass round a core of New Age's overcharged iron coils, dark corner posts with hazard feet, a light
    # band and an orange lid with dark stair slopes; the connector on the roof
    f = Frame(3, 3)
    f.mbox(0, 1, 1, 0, 3, 1, "create_new_age:overcharged_iron_wire_block")
    f.controller(1, 1, 0)
    f.m(0, 5, 1, CONNECTOR)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.hazard_x(-1, 1, 0, 0)
    f.walls(1, 2, DARK, GLASS)
    f.walls(3, 3, DARK, LIGHT)
    for x in (-1, 1):
        for z in (0, 2):
            f.set(x, 1, z, YELLOW)
    f.set(-1, 4, 1, dstair("east"))
    f.set(1, 4, 1, dstair("west"))
    f.box(0, 4, 0, 0, 4, 2, ORANGE)
    for x, z in ((-1, 0), (1, 0), (-1, 2), (1, 2)):
        f.set(x, 4, z, dslab())
    return f.done()


# ---- the generators' machines (Power tab): a real Create boiler (Fluid Tank over Blaze Burners, or over Create: New
# Age's Heaters in the Nuclear Power Plant) drives Steam Engines, Diesel Generators' engine drives the Fuel Generator and
# the geyser's turbine the Geothermal Generator; the shaft turns Create: New Age generator coils, ringed with magnets, and
# carbon brushes take their energy for the grid. The magnets are what make the MW: a coil gives the SU its magnets take
# off the shaft as New Age energy, so magnets x RPM = Satisfactory's MW x 204.8.
ENGINE = "create:steam_engine[face=wall,facing={},waterlogged=false]"
POWERED_SHAFT = "create:powered_shaft[axis={}]"
SHAFT_A = "create:shaft[axis={}]"
COG_A = "create:cogwheel[axis={}]"
LARGE_COG = "create:large_cogwheel[axis={}]"
COIL_A = "create_new_age:generator_coil[axis={}]"
BRUSHES = "create_new_age:carbon_brushes[facing={}]"
NETHERITE, FLUXUATED = "create_new_age:netherite_magnet", "create_new_age:fluxuated_magnetite"
HEATER = "create_new_age:heater[blaze=none]"
VENT_R = "create_new_age:reactor_heat_vent[facing=down]"
ROD = "create_new_age:reactor_rod[axis=y,lit=false]"
ACCEPTOR = "create_new_age:reactor_fuel_acceptor[facing={}]"
DIESEL_ENGINE = "createdieselgenerators:diesel_engine[facing={},powered=false,waterlogged=false]"


def ring(axis, x, y, z):
    """The twelve cells round a generator coil (two out from it, square corners left out), cardinal ones first: where
    New Age looks for its magnets."""
    near = [(-2, 0), (2, 0), (0, -2), (0, 2), (-2, -1), (-2, 1), (2, -1), (2, 1), (-1, -2), (1, -2), (-1, 2), (1, 2)]
    out = []
    for a, b in near:
        if axis == "x":
            out.append((x, y + a, z + b))
        elif axis == "z":
            out.append((x + a, y + b, z))
        else:
            out.append((x + a, y, z + b))
    return out


def coil(f, x, y, z, axis, magnets, filler=LIGHT):
    """A generator coil with its ring: `magnets` are the magnet blocks, in the ring's order; the rest of the ring is
    `filler` (no magnet). All of it is a working part."""
    f.m(x, y, z, COIL_A.format(axis))
    for k, at in enumerate(ring(axis, x, y, z)):
        f.m(*at, magnets[k] if k < len(magnets) else filler)


def generator_box(f, y2, side=ORANGE, sill_rows=1):
    """The house over the whole footprint: a dark floor with a hazard front, a dark sill course, walls of `side` with
    dark corner pillars and a light band at the top (y2)."""
    f.box(-(f.w // 2), 0, 0, f.w // 2, 0, f.d - 1, DARK)
    f.hazard_x(-(f.w // 2), f.w // 2, 0, 0)
    f.walls(1, sill_rows, DARK, DARK)
    f.walls(sill_rows + 1, y2 - 1, DARK, side)
    f.walls(y2, y2, DARK, LIGHT)


def biomass_burner():
    # 5 wide, 6 deep, 11 high. At the back a Blaze Burner under a four-high Fluid Tank (a boiler of size 4: one heat
    # level), its Steam Engine on the tank's front driving a shaft that turns one generator coil with four netherite
    # magnets (96 SU a turn at 64 RPM: 6,144 SU, 30 MW) and the carbon brushes in the left wall. The burner is fed from the
    # fuel port at the back; the boiler is a closed loop (Satisfactory's burner takes no water). Outside: an orange house
    # on a dark plinth, glass to see the boiler, a light eave band, a roof bevelled in dark stairs round an orange field with
    # vents, and a dark flue over the burner.
    f = Frame(5, 6)
    f.m(1, 1, 5, BURNER)
    f.mbox(1, 2, 5, 1, 5, 5, TANK)
    f.m(1, 5, 4, ENGINE.format("south"))
    f.m(1, 5, 3, AIR)
    f.m(1, 5, 2, POWERED_SHAFT.format("x"))
    f.m(0, 5, 2, SHAFT_A.format("x"))
    coil(f, -1, 5, 2, "x", [NETHERITE] * 4)
    f.m(-2, 5, 2, BRUSHES.format("east"))
    f.controller(1, 1, 0)
    f.port(0, 0, 5, "north", "item_in", (1, 1, 0))
    f.m(0, 10, 2, CONNECTOR)
    generator_box(f, 8, sill_rows=2)
    f.window_side(2, 1, 3, 2, 4)
    f.window_front(0, 0, 1, 2, 3, lip=False)
    f.set(-2, 3, 3, VENT)
    f.set(-2, 4, 3, VENT)
    f.deck(-2, 0, 2, 5, 9, eave=False)
    f.chamfer(-2, 0, 2, 5, 9)
    f.box(-1, 9, 1, 1, 9, 4, ORANGE)
    f.set(0, 9, 3, LIGHT)
    f.set(1, 10, 5, dstair("north"))
    f.set(1, 10, 4, VENT)
    f.set(-1, 10, 4, VENT)
    return f.done()


def coal_generator():
    # 7 wide, 8 deep, 12 high. At the back a 2 by 2 by 2 Fluid Tank (size 8: two heat levels) over two Blaze Burners, two
    # Steam Engines on its front driving one shaft that turns a generator coil with ten netherite magnets (240 SU a turn at
    # 64 RPM: 15,360 SU, 75 MW), the carbon brushes beside it. Coal goes into the burners from the item port; water comes
    # in at the fluid port into the intake tank, from which the boiler drinks Satisfactory's 45 m3 a minute at full load.
    # Outside: an orange boiler house on a dark plinth with orange pilasters in dark walls at the front, glass down both
    # sides, a roof bevelled in dark stairs round an orange field, and two square smokestacks at the back corners with
    # orange collars.
    f = Frame(7, 8)
    f.mbox(1, 1, 5, 2, 2, 6, DARK)
    f.m(1, 3, 5, BURNER)
    f.m(2, 3, 5, BURNER)
    f.m(1, 3, 6, DARK)
    f.m(2, 3, 6, DARK)
    f.mbox(1, 4, 5, 2, 5, 6, TANK)
    for x in (1, 2):
        f.m(x, 5, 4, ENGINE.format("south"))
        f.m(x, 5, 3, AIR)
        f.m(x, 5, 2, POWERED_SHAFT.format("x"))
    f.m(0, 5, 2, SHAFT_A.format("x"))
    coil(f, -1, 5, 2, "x", [NETHERITE] * 10)
    f.m(-2, 5, 2, BRUSHES.format("east"))
    f.m(0, 1, 6, TANK)
    f.controller(1, 1, 0)
    f.port(-1, 0, 7, "north", "item_in", (1, 1, 0))
    f.port(0, 0, 7, "north", "fluid_in", (0, 1, 6))
    f.m(0, 10, 2, CONNECTOR)
    generator_box(f, 8, sill_rows=2)
    for x in (-2, 2):
        f.box(x, 3, 0, x, 8, 0, DARK)
    f.window_side(-3, 2, 5, 3, 6)
    f.window_side(3, 2, 5, 3, 6)
    f.window_front(0, -1, 1, 3, 5, lip=False)
    f.box(-1, 6, 0, 1, 7, 0, DARK)
    f.set(0, 7, 0, VENT)
    f.deck(-3, 0, 3, 7, 9, eave=False)
    f.chamfer(-3, 0, 3, 7, 9)
    f.box(-2, 9, 1, 2, 9, 4, ORANGE)
    for x0 in (-3, 2):
        f.box(x0, 10, 6, x0 + 1, 10, 7, ORANGE)
        f.box(x0, 11, 6, x0 + 1, 11, 7, DARK)
        f.set(x0, 11, 6, VENT)
    f.set(-1, 10, 6, dstair("west"))
    f.set(1, 10, 6, dstair("east"))
    return f.done()


def fuel_generator():
    # 5 wide, 6 deep, 10 high. Inside, Diesel Generators' engine turns a generator coil at 256 RPM; its eight netherite
    # magnets and one fluxuated magnetite take 200 SU a turn (51,200 SU, 250 MW), and the carbon brushes beside it give
    # that to the grid. Fuel goes straight into the engine at the fuel port. Outside: a light panelled engine house on a
    # dark plinth with an orange belly band, glass to see the engine, dark vents high on the sides and an orange lid that
    # slopes down to its edges in dark stairs.
    f = Frame(5, 6)
    f.m(1, 4, 2, DIESEL_ENGINE.format("west"))
    coil(f, 0, 4, 2, "x", [NETHERITE] * 8 + [FLUXUATED])
    f.m(-1, 4, 2, BRUSHES.format("east"))
    f.controller(1, 1, 0)
    f.port(1, 0, 5, "north", "fluid_in", (1, 4, 2))
    f.m(0, 9, 2, CONNECTOR)
    generator_box(f, 7, side=LIGHT)
    f.walls(3, 3, DARK, ORANGE)
    f.window_front(0, -1, 1, 1, 2, lip=False)
    f.window_side(-2, 1, 4, 5, 6)
    f.window_side(2, 1, 4, 5, 6)
    for x in (-2, 2):
        f.set(x, 4, 1, VENT)
        f.set(x, 4, 4, VENT)
    f.box(-1, 8, 1, 1, 8, 4, ORANGE)
    for z in range(1, 5):
        f.set(-2, 8, z, dstair("east"))
        f.set(2, 8, z, dstair("west"))
    for x in (-1, 0, 1):
        f.set(x, 8, 0, dstair("north"))
        f.set(x, 8, 5, dstair("south"))
    for x, z in ((-2, 0), (2, 0), (-2, 5), (2, 5)):
        f.set(x, 8, z, dslab())
    return f.done()


def geothermal_generator():
    # 5 wide, 5 deep, 11 high, on four legs over a sulfur geyser: the vent cell under the middle is left as air, so the
    # geyser breathes. Inside, the geyser's turbine turns two generator coils with twenty netherite magnets (480 SU a turn)
    # at the speed the geyser gives: 256 RPM at a pure geyser's peak (600 MW), so the power swings with the geyser as in
    # Satisfactory. The acid tank and its port are at the back. Outside: legs with hazard feet, a dark deck, an orange
    # turbine house with a light eave band, and steam vents on its roof, bevelled in dark stairs round an orange field.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    f.m(-1, 2, 2, TANK)
    f.m(1, 5, 2, TURBINE.format("west"))
    coil(f, 0, 5, 2, "x", [NETHERITE] * 12)
    coil(f, -1, 5, 2, "x", [NETHERITE] * 8)
    f.m(-2, 5, 2, BRUSHES.format("east"))
    f.controller(1, 1, 0)
    f.port(-1, 2, 4, "north", "fluid_out", (-1, 2, 2))
    f.m(0, 10, 2, CONNECTOR)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, YELLOW if z == 0 else BLACK)
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.hazard_x(-2, 2, 1, 0)
    f.walls(2, 2, DARK, DARK)
    f.walls(3, 7, DARK, ORANGE)
    f.walls(8, 8, DARK, LIGHT)
    f.window_side(-2, 1, 3, 2, 2)
    f.window_side(2, 1, 3, 2, 2)
    f.set(1, 3, 0, VENT)
    f.set(-1, 3, 0, VENT)
    f.deck(-2, 0, 2, 4, 9, eave=False)
    f.chamfer(-2, 0, 2, 4, 9)
    f.box(-1, 9, 1, 1, 9, 3, ORANGE)
    for x, z in ((-1, 1), (1, 1), (-1, 3), (1, 3)):
        f.set(x, 10, z, VENT)
    return f.done()


# The Nuclear Power Plant's layout (Build Gun frame), shared with building/Reactors.java through siftec_content.json
NUCLEAR_BOILERS = ((-8, 7), (6, 7))      # each boiler's lowest x and z: 3 by 3 by 8 Fluid Tanks from y 4 to 11
NUCLEAR_ENGINE_ROWS = range(4, 10)       # six rows of three Steam Engines on each boiler's front
NUCLEAR_SHAFT_Z = 4


def nuclear_power_plant():
    # 21 wide, 11 deep, 17 high (Satisfactory's reactor and its two cooling towers). The two cooling towers are Create
    # boilers: 3 by 3 by 8 Fluid Tanks (eighteen heat levels each) over nine of Create: New Age's Heaters, which take the
    # heat of the reactor below them: a layer of New Age heat vents over seven reactor rods each, fed by six fuel
    # acceptors at the sides. Eighteen Steam Engines on the front of each tower drive six shafts joined by a column of
    # cogwheels; two large-to-small cogwheel steps take it from 64 to 256 RPM, where seven generator coils with 83
    # netherite magnets and one fluxuated magnetite take 2,000 SU a turn (512,000 SU, 2,500 MW) and the carbon brushes at
    # the end give it to the grid. Rods in and waste out at the controller's ports, water into the intake tank at the back.
    # Outside: a long dark hall with orange pilasters every fourth column below and an orange upper storey between dark
    # pilasters, light bands, strip windows on the boilers and the engine rows, a ribbed roof bevelled in dark stairs
    # and an orange crown over each cooling tower.
    f = Frame(21, 11)
    for bx, bz in NUCLEAR_BOILERS:
        f.mbox(bx, 4, bz, bx + 2, 11, bz + 2, TANK)
        f.mbox(bx, 3, bz, bx + 2, 3, bz + 2, HEATER)
        f.mbox(bx, 2, bz, bx + 2, 2, bz + 2, VENT_R)
        f.mbox(bx, 1, bz, bx + 2, 1, bz + 2, DARK)
        # seven rods: two rows of three and one, each row fed along its line by a fuel acceptor outside the boiler
        west = bx < 0
        ax = bx - 1 if west else bx + 3
        for k, z in enumerate(range(bz, bz + 3)):
            f.m(ax, 1, z, ACCEPTOR.format("east" if west else "west"))
            n = 3 if k < 2 else 1
            for i in range(n):
                f.m(bx + i if west else bx + 2 - i, 1, z, ROD)
        for y in NUCLEAR_ENGINE_ROWS:
            for x in range(bx, bx + 3):
                f.m(x, y, bz - 1, ENGINE.format("south"))
                f.m(x, y, bz - 2, AIR)
                f.m(x, y, NUCLEAR_SHAFT_Z, POWERED_SHAFT.format("x"))
    for y in NUCLEAR_ENGINE_ROWS:
        for x in range(-5, 6):
            f.m(x, y, NUCLEAR_SHAFT_Z, SHAFT_A.format("x") if x != 0 else COG_A.format("x"))
    # the column of cogwheels joins the six rows; then 64 -> 128 -> 256 RPM through two large and small cogwheel steps
    f.m(0, 10, 4, COG_A.format("x"))
    f.m(1, 10, 4, LARGE_COG.format("x"))
    f.m(1, 11, 3, COG_A.format("x"))
    f.m(2, 11, 3, LARGE_COG.format("x"))
    f.m(2, 12, 2, COG_A.format("x"))
    for k, x in enumerate(range(3, 10)):
        coil(f, x, 12, 2, "x", [NETHERITE] * 12 if k < 6 else [NETHERITE] * 11 + [FLUXUATED])
    f.m(10, 12, 2, BRUSHES.format("west"))
    f.m(-2, 1, 9, TANK)
    f.controller(1, 1, 1)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(0, 0, 10, "north", "item_in", (1, 1, 1))
    f.port(-2, 0, 10, "north", "fluid_in", (-2, 1, 9))
    f.m(0, 16, 5, CONNECTOR)
    f.pad()
    f.walls(1, 2, DARK, DARK)
    f.walls(3, 14, DARK, DARK)
    f.walls(5, 5, DARK, LIGHT)
    f.walls(10, 10, DARK, LIGHT)
    f.walls(14, 14, DARK, LIGHT)
    f.walls(11, 13, DARK, ORANGE)
    for x in range(-8, 9, 4):
        f.box(x, 3, 0, x, 9, 0, ORANGE)
        f.box(x, 3, 10, x, 9, 10, ORANGE)
        f.box(x, 11, 0, x, 13, 0, DARK)
        f.box(x, 11, 10, x, 13, 10, DARK)
    for z in (3, 7):
        f.box(-10, 3, z, -10, 9, z, ORANGE)
        f.box(10, 3, z, 10, 9, z, ORANGE)
        f.box(-10, 11, z, -10, 13, z, DARK)
        f.box(10, 11, z, 10, 13, z, DARK)
    f.window_front(0, -8, -2, 2, 4)
    f.window_front(0, 2, 8, 2, 4)
    f.window_side(-10, 2, 8, 6, 9)
    f.window_side(10, 6, 8, 6, 9)
    for x in (-9, -1, 1, 9):
        f.set(x, 7, 0, VENT)
        f.set(x, 12, 0, VENT)
    f.hazard_x(-10, 10, 1, 0)
    f.deck(-10, 0, 10, 10, 15, eave=False)
    f.chamfer(-10, 0, 10, 10, 15)
    for bx, bz in NUCLEAR_BOILERS:
        for x in range(bx - 1, bx + 4):
            for z in range(bz - 1, bz + 4):
                if x in (bx - 1, bx + 3) or z in (bz - 1, bz + 3):
                    f.set(x, 16, z, DARK if x in (bx - 1, bx + 3) and z in (bz - 1, bz + 3) else ORANGE)
        f.set(bx + 1, 16, bz + 1, VENT)
    for x in (-4, 4):
        f.set(x, 16, 1, VENT)
    return f.done()


def alien_power_augmenter():
    # 5 by 5, 7 high: glass round a glowing core between dark corner posts with hazard feet, a light band and an orange lid
    # with dark stair slopes; Alien Power Matrices in at the back
    f = Frame(5, 5)
    f.m(0, 1, 2, "minecraft:sea_lantern")
    f.m(0, 2, 2, "minecraft:end_rod[facing=up]")
    f.m(0, 3, 2, "minecraft:sea_lantern")
    f.controller(1, 1, 0)
    f.port(0, 0, 4, "north", "item_in", (1, 1, 0))
    f.m(0, 6, 2, CONNECTOR)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.hazard_x(-2, 2, 0, 0)
    f.walls(1, 3, BRASS, GLASS)
    f.walls(4, 4, DARK, LIGHT)
    for x in (-2, 2):
        for z in (0, 4):
            f.hazard_post(x, z, 1, 3, top=BRASS)
    f.box(-1, 5, 1, 1, 5, 3, ORANGE)
    for x, z, at in f.ring(-1, 1, 1, 3):
        f.set(x, 5, z, dstair(INWARD[at]))
    for x, z in ((-2, 0), (2, 0), (-2, 4), (2, 4)):
        f.set(x, 5, z, dslab())
    return f.done()


# ------------------------------------------------------------------------------------------------ work package 9

def assembler():
    # 9 wide, 11 deep, 9 high (Satisfactory's 10 by 15). Inside, a Mechanical Mixer over a Basin, turned by the motor on
    # top; two inputs at the back and the output at the front, all on the Basin. Outside: a long low orange hall framed in dark
    # pillars, with orange shoulders, a raised glass lantern over the Mixer with dark stair skirts and an orange cap, and dark
    # arm housings with vents along both roof edges.
    f = Frame(9, 11)
    f.m(0, 1, 5, BASIN)
    f.m(0, 2, 5, AIR)
    mixer(f, 0, 3, 5)
    f.mbox(0, 5, 4, 0, 6, 6, AIR)
    f.controller(1, 1, 1)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 5))
    f.port(-1, 0, 10, "north", "item_in", (0, 1, 5))
    f.port(1, 0, 10, "north", "item_in", (0, 1, 5))
    f.m(0, 8, 5, CONNECTOR)
    f.pad()
    f.hall(-3, 1, 3, 9, 1, 4)
    f.window_front(1, -1, 1, 2, 3)
    f.window_side(-3, 3, 7, 3, 3)
    f.window_side(3, 3, 7, 3, 3)
    f.deck(-3, 1, 3, 9, 5)
    # the lantern over the Mixer: glass sides, an orange cap, dark stairs leaning on it from the deck
    f.walls(5, 6, DARK, GLASS, -1, 1, 3, 7)
    f.box(-1, 7, 3, 1, 7, 7, ORANGE)
    for z in range(3, 8):
        f.set(-2, 6, z, dstair("east"))
        f.set(2, 6, z, dstair("west"))
    for x in (-1, 0, 1):
        f.set(x, 6, 2, dstair("north"))
        f.set(x, 6, 8, dstair("south"))
    for side in (-3, 3):
        f.box(side, 6, 2, side, 6, 8, DARK)
        f.set(side, 6, 5, VENT)
        f.set(side, 7, 2, dslab())
        f.set(side, 7, 8, dslab())
    return f.done()


def mixer(f, x, y, z):
    """A Mechanical Mixer has no shaft of its own: it turns as a cog, so a cogwheel beside it (to its right) is turned by
    the motor on top of the cogwheel."""
    f.m(x, y, z, MIXER)
    f.m(x + 1, y, z, COG)
    f.m(x + 1, y + 1, z, MOTOR.format("down"))


def heated_mixer(f, x, z, basin=BASIN):
    """A heated Mixer: a Blaze Burner (kept lit by the building while it has power), the Basin on it, the Mixer two above
    and its cogwheel and motor beside it. Returns the Basin's cell."""
    f.m(x, 1, z, BURNER)
    f.m(x, 2, z, basin)
    f.m(x, 3, z, AIR)
    mixer(f, x, 4, z)
    return (x, 2, z)


def foundry():
    # 9 wide, 9 deep, 11 high (Satisfactory's 10 by 9): two round furnace towers either side of a glass-fronted orange
    # hall with the heated Mixer; two inputs at the back, the output at the front. The towers are dark with orange bands,
    # a light collar and a vent on top; dark stair buttresses lean on their feet.
    f = Frame(9, 9)
    basin = heated_mixer(f, 0, 4)
    f.controller(1, 1, 1)
    f.port(0, 0, 0, "south", "item_out", basin)
    f.port(-1, 0, 8, "north", "item_in", basin)
    f.port(1, 0, 8, "north", "item_in", basin)
    f.m(0, 8, 4, CONNECTOR)
    f.pad()
    f.hall(-2, 1, 2, 7, 1, 6)
    f.window_front(1, -1, 1, 2, 5)
    f.window_front(7, -1, 1, 3, 4)
    f.deck(-2, 1, 2, 7, 7)
    for cx in (-3, 3):
        f.drum(cx, 4, 1, 8, 1, DARK, band=ORANGE, every=3)
        f.set(cx, 9, 4, LIGHT)
        f.set(cx, 10, 4, VENT)
        f.set(cx, 1, 2, dstair("north"))
        f.set(cx, 1, 6, dstair("south"))
        f.drop(cx, 2, 2)
        f.drop(cx, 2, 6)
        for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            f.put(cx + dx, 9, 4 + dz, dslab())
    f.hood(-1, 3, 1, 5, 8, 8, slope=True)
    return f.done()


def refinery():
    # 9 wide, 13 deep, 16 high (Satisfactory's 10 by 20): a glass-fronted orange hall at the front with the heated Mixer, a
    # tall round distillation tower behind it, dark with orange bands, an orange crown and a copper ladder, girder pipes
    # from the hall's roof to the tower. Item and fluid in at the back, item and fluid out at the front, all on the Basin.
    f = Frame(9, 13)
    basin = heated_mixer(f, 0, 4)
    f.controller(1, 1, 1)
    f.port(-1, 0, 0, "south", "item_out", basin)
    f.port(1, 0, 0, "south", "fluid_out", basin)
    f.port(-3, 0, 12, "north", "item_in", basin)
    f.port(3, 0, 12, "north", "fluid_in", basin)
    f.m(0, 8, 4, CONNECTOR)
    f.pad()
    f.hall(-3, 1, 3, 7, 1, 6)
    f.window_front(1, -1, 1, 2, 5)
    f.window_side(-3, 3, 5, 3, 5)
    f.window_side(3, 3, 5, 3, 5)
    f.deck(-3, 1, 3, 7, 7)
    f.drum(0, 10, 1, 14, 2, DARK, band=ORANGE, every=4)
    f.drum(0, 10, 1, 1, 2, DARK)
    for y in (6, 10):
        f.drum(0, 10, y, y, 2, LIGHT)
    f.drum(0, 10, 15, 15, 1, ORANGE)
    for x in (-2, 2):
        f.set(x, 15, 10, dslab())
    f.set(0, 15, 8, dslab())
    f.set(0, 15, 12, dslab())
    for y in range(1, 15):
        f.set(3, y, 10, ladder("east"))
    for x in (-2, 2):
        f.girders(x, 8, 1, 12)
        f.set(x, 13, 8, dslab())
    f.hood(-1, 3, 1, 5, 8, 8)
    f.set(-3, 8, 1, VENT)
    f.set(3, 8, 1, VENT)
    return f.done()


def blender():
    # 11 wide, 11 deep, 11 high (Satisfactory's 18 by 16): a wide dark hall with the heated Mixer over a Brass Basin,
    # orange pilasters, shoulders and an orange eave band, and two big round light tanks with orange bands on its roof,
    # vents between them. Two fluids and two items in at the back, a fluid and an item out at the front.
    f = Frame(11, 11)
    basin = heated_mixer(f, 0, 5, BRASS_BASIN)
    f.controller(1, 1, 1)
    f.port(-1, 0, 0, "south", "item_out", basin)
    f.port(1, 0, 0, "south", "fluid_out", basin)
    f.port(-3, 0, 10, "north", "item_in", basin)
    f.port(-1, 0, 10, "north", "item_in", basin)
    f.port(1, 0, 10, "north", "fluid_in", basin)
    f.port(3, 0, 10, "north", "fluid_in", basin)
    f.m(0, 8, 3, CONNECTOR)
    f.pad()
    f.hall(-4, 1, 4, 9, 1, 6, side=DARK, pilaster=ORANGE, band=ORANGE)
    f.window_front(1, -2, 2, 2, 5)
    f.window_side(-4, 3, 7, 3, 5)
    f.window_side(4, 3, 7, 3, 5)
    f.deck(-4, 1, 4, 9, 7)
    for cx in (-2, 2):
        f.drum(cx, 6, 8, 10, 1, LIGHT, band=ORANGE, every=2)
        f.set(cx, 10, 6, DARK)
        for dx, dz in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            f.set(cx + dx, 8, 6 + dz, dslab())
    f.set(0, 8, 6, VENT)
    f.set(0, 8, 8, VENT)
    for x in (-1, 1):
        f.set(x, 8, 3, ORANGE)
    f.set(0, 8, 2, dslab())
    return f.done()


def assembly_line(f, x, z_basin, stations, motor_z, basin=BRASS_BASIN):
    """The Manufacturer's way: a Mixer over a Brass Basin at the back makes the Core, then a row of Depots toward the
    front, each under a Deployer (or a Spout, "spout"), and an Item Drain ("drain") last. The Deployers' shafts run front to
    back through shafts between them, turned by one motor at the front end. Returns the Basin's cell."""
    f.m(x, 1, z_basin, basin)
    f.m(x, 2, z_basin, AIR)
    mixer(f, x, 3, z_basin)
    z = z_basin - 2
    deployers = []
    for kind in stations:
        if kind == "drain":
            f.m(x, 1, z, DRAIN)
        else:
            f.m(x, 1, z, DEPOT)
            f.m(x, 2, z, AIR)
            f.m(x, 3, z, SPOUT if kind == "spout" else DEPLOYER)
            if kind == "deployer":
                deployers.append(z)
        z -= 2
    if deployers:
        for zz in range(min(deployers), max(deployers)):
            if zz not in deployers:
                f.m(x, 3, zz, SHAFT_Z)
        f.m(x, 3, min(deployers) - 1, MOTOR.format("north"))
    return (x, 1, z_basin)


def manufacturer():
    # 11 wide, 15 deep, 10 high (Satisfactory's 18 by 20): a long dark hall with orange pilasters along both sides, a long
    # strip of windows on the line, shoulders, and a stepped glass roof over the line: dark stair eaves, a glass slope, a
    # second step of stairs and a glass ridge between orange gables. At the back the Mixer over the Brass Basin makes the
    # Core; four Depots under Deployers carry it to the front, each Deployer adding the last of one input. Four inputs at
    # the back, the output at the front.
    f = Frame(11, 15)
    basin = assembly_line(f, 0, 11, ["deployer", "deployer", "deployer", "deployer"], 0)
    f.controller(2, 1, 1)
    f.port(0, 0, 0, "south", "item_out", (2, 1, 1))
    for x in (-3, -1, 1, 3):
        f.port(x, 0, 14, "north", "item_in", basin)
    f.m(0, 9, 7, CONNECTOR)
    f.pad()
    f.hall(-4, 1, 4, 13, 1, 6, side=DARK, pilaster=None)
    for z in range(3, 13, 3):
        f.box(-4, 2, z, -4, 6, z, ORANGE)
        f.box(4, 2, z, 4, 6, z, ORANGE)
    for x in (-3, 3):
        f.box(x, 2, 1, x, 6, 1, ORANGE)
    f.window_front(1, -2, 2, 2, 5)
    f.window_side(-4, 2, 12, 3, 4)
    f.window_side(4, 2, 12, 3, 4)
    # the stepped glass roof
    f.box(-4, 7, 1, 4, 7, 13, DARK)
    f.box(-3, 7, 2, 3, 7, 12, GLASS)
    for z in range(1, 14):
        f.set(-4, 7, z, dstair("east"))
        f.set(4, 7, z, dstair("west"))
    for z in range(2, 13):
        f.set(-2, 8, z, dstair("east"))
        f.set(2, 8, z, dstair("west"))
    f.box(-1, 8, 2, 1, 8, 12, GLASS)
    f.box(-2, 8, 1, 2, 8, 1, ORANGE)
    f.box(-2, 8, 13, 2, 8, 13, ORANGE)
    f.set(-4, 7, 1, DARK)
    f.set(4, 7, 1, DARK)
    f.set(-4, 7, 13, DARK)
    f.set(4, 7, 13, DARK)
    for z in range(1, 14):
        f.put(-5, 7, z, dslab())
        f.put(5, 7, z, dslab())
    return f.done()


def quantum_encoder():
    # 11 wide, 19 deep, 11 high (Satisfactory's long encoder with its arches): a long dark hall with glass sides under
    # three orange arches standing on hazard-striped feet, with dark stair fillets where the arches turn, shoulders
    # between them and a ribbed roof. At the back the Mixer over the Brass Basin makes the Core; the line toward the front
    # is a Spout that fills it with Excited Photonic Matter, four Deployers, and an Item Drain that takes the Dark Matter
    # Residue off. Three item inputs and the photonic matter at the back, the item and the residue out at the front.
    f = Frame(11, 19)
    basin = assembly_line(f, 0, 15, ["spout", "deployer", "deployer", "deployer", "deployer", "drain"], 0)
    f.controller(2, 1, 1)
    f.port(-1, 0, 0, "south", "item_out", (2, 1, 1))
    f.port(1, 0, 0, "south", "fluid_out", (0, 1, 3))
    for x in (-3, -1, 1):
        f.port(x, 0, 18, "north", "item_in", basin)
    f.port(3, 0, 18, "north", "fluid_in", (0, 3, 13))
    f.m(0, 8, 9, CONNECTOR)
    f.pad()
    f.hall(-4, 1, 4, 17, 1, 6, side=DARK, pilaster=None)
    f.window_side(-4, 2, 16, 3, 5)
    f.window_side(4, 2, 16, 3, 5)
    f.window_front(1, -2, 2, 2, 5)
    f.deck(-4, 1, 4, 17, 7)
    for z in (3, 9, 15):
        for x in (-5, 5):
            f.hazard_post(x, z, 1, 9, top=ORANGE)
            f.set(x, 7, z, ORANGE)
            f.set(x - 1 if x > 0 else x + 1, 9, z, dstair("east" if x > 0 else "west", "top"))
            f.set(x - 1 if x > 0 else x + 1, 8, z, DARK)
        f.box(-5, 10, z, 5, 10, z, ORANGE)
        f.set(0, 10, z, VENT)
    return f.done()


def packager():
    # 7 wide, 7 deep, 8 high (Satisfactory's 8 by 8): an orange box framed in dark pillars, a glass front and shoulders; on the
    # roof an orange hood with dark stair slopes carrying two light canisters. Inside, a Spout over a Depot fills empty
    # canisters and tanks, an Item Drain empties packaged fluids. Item and fluid in at the back, item and fluid out at the
    # front.
    f = Frame(7, 7)
    f.m(-1, 1, 3, DEPOT)
    f.m(-1, 2, 3, AIR)
    f.m(-1, 3, 3, SPOUT)
    f.m(1, 1, 3, DRAIN)
    f.m(0, 1, 3, AIR)
    f.controller(1, 1, 1)
    f.port(-1, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(1, 0, 0, "south", "fluid_out", (1, 1, 3))
    f.port(-1, 0, 6, "north", "item_in", (1, 1, 1))
    f.port(1, 0, 6, "north", "fluid_in", (-1, 3, 3))
    f.m(0, 7, 3, CONNECTOR)
    f.pad()
    f.hall(-2, 1, 2, 5, 1, 4)
    f.window_front(1, -1, 1, 2, 3)
    f.window_side(-2, 2, 4, 3, 3)
    f.window_side(2, 2, 4, 3, 3)
    f.deck(-2, 1, 2, 5, 5)
    f.hood(-1, 2, 1, 4, 6, 6)
    f.set(-1, 7, 3, LIGHT)
    f.set(1, 7, 3, LIGHT)
    f.set(-1, 7, 2, lslab())
    f.set(1, 7, 4, lslab())
    return f.done()


def water_extractor():
    # 7 by 7, 7 high, standing in water: four pontoons with hazard stripes, an orange deck with hazard edges and iron rails
    # along its sides, the pump over the open intake in the middle (the water cell stays) turned by the motor above, in a
    # glass pump house with dark corners, a light band and an orange lid, orange shoulders against it, and the water tank
    # at the front by the port
    f = Frame(7, 7)
    f.box(-3, 0, 0, 3, 0, 6, "minecraft:structure_void")
    for x in range(-3, 4):
        for z in range(7):
            if not (x in (-3, 3) and z in (0, 6)):
                f.void(x, 0, z)
    f.m(0, 1, 3, "create:mechanical_pump[facing=down]")
    f.m(0, 2, 3, MOTOR.format("down"))
    f.mbox(0, 3, 3, 0, 4, 3, AIR)
    f.m(0, 2, 1, TANK)
    f.controller(1, 2, 0)
    f.port(0, 1, 0, "south", "fluid_out", (0, 2, 1))
    f.m(0, 6, 3, CONNECTOR)
    for x in (-3, 3):
        for z in (0, 6):
            f.set(x, 0, z, YELLOW if z == 0 else BLACK)
    f.box(-3, 1, 0, 3, 1, 6, ORANGE)
    f.hazard_x(-3, 3, 1, 0)
    f.hazard_x(-3, 3, 1, 6)
    f.box(-1, 1, 2, 1, 1, 4, DARK)
    f.walls(2, 3, DARK, GLASS, -1, 1, 2, 4)
    f.walls(4, 4, DARK, LIGHT, -1, 1, 2, 4)
    f.box(-1, 5, 2, 1, 5, 4, ORANGE)
    f.box(-1, 2, 1, -1, 3, 1, DARK)
    f.box(1, 2, 1, 1, 3, 1, DARK)
    for x in (-2, 2):
        for z in (2, 3, 4):
            f.set(x, 2, z, ORANGE)
            f.set(x, 3, z, dstair("east" if x < 0 else "west"))
    for z in range(1, 6):
        f.set(-3, 2, z, RAIL)
        f.set(3, 2, z, RAIL)
    f.set(-1, 4, 1, dslab())
    f.set(1, 4, 1, dslab())
    return f.done()


def node_rig(f):
    """Four legs with hazard feet round a node in the middle of a 5 by 5 footprint, a dark deck over it with hazard edges
    front and back."""
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    f.void(0, 0, 2)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, YELLOW if z == 0 else BLACK)
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.hazard_x(-2, 2, 1, 0)
    f.hazard_x(-2, 2, 1, 4)


def oil_extractor():
    # 5 by 5, 14 high, over an oil node (Satisfactory's derrick): a deck on legs, the pump over the well turned by the
    # motor above in an orange pump house, and over it an open derrick of metal girders with orange collars and iron-bar
    # lattice, an orange crown and a copper ladder up its side; the oil tank at the front by the port
    f = Frame(5, 5)
    f.m(0, 1, 2, "create:mechanical_pump[facing=down]")
    f.m(0, 2, 2, MOTOR.format("down"))
    f.mbox(0, 3, 2, 0, 10, 2, AIR)
    f.m(0, 1, 1, TANK)
    f.controller(1, 1, 0)
    f.port(0, 0, 0, "south", "fluid_out", (0, 1, 1))
    f.m(0, 13, 2, CONNECTOR)
    node_rig(f)
    f.walls(2, 3, DARK, ORANGE, -1, 1, 1, 3)
    for y in range(4, 11):
        collar = y % 3 == 0
        f.walls(y, y, GIRDER, ORANGE if collar else RAIL, -1, 1, 1, 3)
        # the ladder's side stays solid
        f.set(1, y, 2, ORANGE if collar else DARK)
    f.box(-1, 11, 1, 1, 11, 3, ORANGE)
    for x, z, at in f.ring(-1, 1, 1, 3):
        if f.inside(x, 11, z):
            f.set(x, 11, z, dstair(INWARD[at], "top"))
    f.drop(2, 11, 2)
    f.set(0, 12, 2, LIGHT)
    for y in range(2, 11):
        f.set(2, y, 2, ladder("east"))
    for x, z in ((-2, 1), (-2, 3)):
        f.hazard_post(x, z, 2, 3)
    return f.done()


def resource_well_pressurizer():
    # 5 by 5, 10 high, over the nitrogen well: a deck on legs and a big round dark drum with orange bands and a glass front,
    # hazard posts and girders at its four open corners, a light cap; the powered pump over the well inside it
    f = Frame(5, 5)
    f.m(0, 1, 2, "create:mechanical_pump[facing=down]")
    f.mbox(-1, 2, 1, 1, 6, 3, AIR)
    f.m(0, 2, 2, MOTOR.format("down"))
    f.controller(1, 1, 0)
    f.m(0, 9, 2, CONNECTOR)
    node_rig(f)
    f.drum(0, 2, 2, 7, 2, DARK, band=ORANGE, every=3)
    f.window_front(0, -1, 1, 3, 4, lip=False)
    f.drum(0, 2, 8, 8, 1, LIGHT)
    for x in (-2, 2):
        for z in (0, 4):
            f.hazard_post(x, z, 2, 3)
            f.girders(x, z, 4, 7)
            f.set(x, 8, z, dslab())
    return f.done()


def resource_well_extractor():
    # 3 by 3, 4 high: a small orange cap with dark corners and a pump, its tank behind glass, a lid of light slabs and dark
    # stairs; nitrogen out at the front
    f = Frame(3, 3)
    f.m(0, 1, 1, TANK)
    f.m(0, 2, 1, "create:mechanical_pump[facing=down]")
    f.controller(1, 1, 0)
    f.port(0, 0, 0, "south", "fluid_out", (0, 1, 1))
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.hazard_x(-1, 1, 0, 2)
    f.walls(1, 2, DARK, ORANGE)
    f.set(0, 2, 0, GLASS)
    f.set(-1, 3, 1, dstair("east"))
    f.set(1, 3, 1, dstair("west"))
    f.box(0, 3, 0, 0, 3, 2, LIGHT)
    for x, z in ((-1, 0), (1, 0), (-1, 2), (1, 2)):
        f.set(x, 3, z, dslab())
    return f.done()


def particle_accelerator():
    # 13 wide, 21 deep, 11 high (Satisfactory's 24 by 38): a long oval track round an orange-pilastered dark console hall
    # in the middle, the track a dark course with an orange stripe, vents every few blocks and a dark slab cap. Inside
    # the hall, at the back, a Mixer over a Brass Basin packs a recipe's inputs into its Core; the building drops the Core
    # into the column over an Encased Fan, which blows it up between two Crushing Wheels turning against each other, and
    # what the wheels crush it into comes out over them. Two items and a fluid in at the back (into the Basin), the item
    # out at the front. An orange hood over the crusher slopes down to the roof in dark stairs.
    f = Frame(13, 21)
    f.m(0, 1, 13, BRASS_BASIN)
    f.m(0, 2, 13, AIR)
    mixer(f, 0, 3, 13)
    f.m(0, 0, 10, MOTOR.format("up"))
    f.m(0, 1, 10, "create:encased_fan[facing=up]")
    f.mbox(0, 2, 10, 0, 3, 10, AIR)
    f.m(-1, 4, 10, "create:crushing_wheel[axis=z]")
    f.m(1, 4, 10, "create:crushing_wheel[axis=z]")
    f.m(0, 4, 10, AIR)
    f.m(-1, 4, 9, MOTOR.format("north"))
    f.m(1, 4, 9, MOTOR.format("north"))
    # where the crushed goods come out, over the wheels, under a dark cap
    f.mbox(0, 5, 10, 0, 6, 10, AIR)
    f.m(0, 7, 10, DARK)
    f.controller(1, 1, 6)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 6))
    f.port(-2, 0, 20, "north", "item_in", (0, 1, 13))
    f.port(0, 0, 20, "north", "item_in", (0, 1, 13))
    f.port(2, 0, 20, "north", "fluid_in", (0, 1, 13))
    f.m(0, 10, 10, CONNECTOR)
    f.pad()
    # the track: an oval ring two blocks high and a slab
    for x in range(-6, 7):
        for z in range(1, 20):
            dx, dz = x / 6.0, (z - 10) / 9.0
            d = dx * dx + dz * dz
            if 0.72 <= d <= 1.02:
                f.set(x, 1, z, DARK)
                f.set(x, 2, z, VENT if (x + z) % 4 == 0 else ORANGE)
                f.set(x, 3, z, dslab())
    f.hall(-3, 6, 3, 14, 1, 7, side=DARK, pilaster=ORANGE, shoulders=False)
    f.window_front(6, -2, 2, 3, 6)
    f.window_front(14, -1, 1, 3, 5)
    f.window_side(-3, 8, 12, 3, 6)
    f.window_side(3, 8, 12, 3, 6)
    f.deck(-3, 6, 3, 14, 8, eave=False)
    f.hood(-1, 8, 1, 12, 9, 9)
    for x in (-3, 3):
        f.set(x, 9, 6, VENT)
        f.set(x, 9, 14, VENT)
    return f.done()


def converter():
    # 9 wide, 9 deep, 11 high (Satisfactory's 16 by 16): a dark hall with orange pilasters, glass round a glowing violet core,
    # an orange drum on the roof with an amethyst crown. At the back a Mixer over a Brass Basin packs a recipe's inputs into
    # its Core; on the Depot in front of it a Deployer adds the last of a second input (or the Converter Cell a fluid is
    # carried in), and an Encased Fan blowing through soul fire (Create's haunting) turns it into what the recipe makes. A
    # fluid comes out of its cell in the Item Drain beside the Depot. Two items in at the back (into the Basin), the item and
    # the fluid out at the front.
    f = Frame(9, 9)
    f.m(-1, 1, 6, BRASS_BASIN)
    f.m(-1, 2, 6, AIR)
    mixer(f, -1, 3, 6)
    # the haunting line: motor, fan, soul fire, the Depot under the Deployer, and the Item Drain behind the Depot
    f.m(-2, 1, 4, MOTOR.format("east"))
    f.m(-1, 1, 4, "create:encased_fan[facing=east]")
    f.m(0, 1, 4, "minecraft:soul_campfire[lit=true,signal_fire=false,waterlogged=false,facing=south]")
    f.m(1, 1, 4, DEPOT)
    f.m(1, 2, 4, AIR)
    f.m(1, 3, 4, DEPLOYER)
    f.m(1, 3, 3, MOTOR.format("north"))
    f.m(1, 1, 5, DRAIN)
    f.controller(1, 1, 1)
    f.port(-1, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(1, 0, 0, "south", "fluid_out", (1, 1, 5))
    f.port(-1, 0, 8, "north", "item_in", (-1, 1, 6))
    f.port(1, 0, 8, "north", "item_in", (-1, 1, 6))
    f.m(-2, 9, 4, CONNECTOR)
    f.pad()
    f.hall(-3, 1, 3, 7, 1, 7, side=DARK, pilaster=ORANGE)
    f.window_front(1, -2, 2, 2, 3)
    f.window_side(-3, 3, 5, 3, 6)
    f.window_side(3, 3, 5, 3, 6)
    f.box(-1, 4, 3, 1, 6, 5, "minecraft:purple_stained_glass")
    f.set(0, 5, 4, "minecraft:amethyst_block")
    f.deck(-3, 1, 3, 7, 8)
    f.drum(0, 4, 9, 9, 1, ORANGE)
    f.set(0, 10, 4, "minecraft:amethyst_block")
    for dx, dz in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        f.set(dx, 9, 4 + dz, dslab())
    for x in (0, 1):
        f.set(x, 9, 1, VENT)
    return f.done()

# ------------------------------------------------------------------------------------------------ work package 10

def equipment_workshop():
    # 5 wide, 3 deep, 5 high (Satisfactory's bench, at the pack's scale): on its pad a long dark workbench with an orange
    # top and a light lip, a tool board of light panels in a dark frame behind it with vents, a little orange roof with a
    # dark slab front edge, and lanterns on the bench. The controller is the bench's terminal, in the middle of the front;
    # using it opens the Workshop.
    f = Frame(5, 3)
    f.m(0, 1, 0, CONTROLLER)
    f.pad()
    f.box(-2, 1, 0, 2, 1, 1, DARK)
    f.box(-2, 2, 0, 2, 2, 1, ORANGE)
    f.box(-2, 1, 2, 2, 3, 2, LIGHT)
    for x in (-2, 2):
        f.box(x, 1, 2, x, 3, 2, DARK)
    f.set(0, 3, 2, VENT)
    f.box(-2, 4, 2, 2, 4, 2, ORANGE)
    f.box(-2, 4, 1, 2, 4, 1, dslab("top"))
    f.hazard_x(-2, 2, 0, 0)
    for x in (-1, 1):
        f.set(x, 3, 1, "minecraft:lantern[hanging=false,waterlogged=false]")
    return f.done()


def awesome_sink():
    # 5 wide, 5 deep, 10 high (the Sink's funnel of a tower): an orange body on a dark plinth with dark corners and a light
    # band, a glass band to see the magma it burns things in, a dark deck with a hazard front, and a funnel of orange over a
    # dark ring, slab caps on its rim. Items go in at the input port at the back, on the ground; the controller in front opens the Sink's screen.
    # 30 MW through the connector on the funnel's rim.
    f = Frame(5, 5)
    f.controller(1, 2, 0)
    f.port(0, 0, 4, "north", "item_in", (1, 2, 0))
    f.m(2, 9, 4, CONNECTOR)
    f.mbox(-1, 2, 1, 1, 5, 3, AIR)
    f.mbox(-1, 7, 1, 1, 8, 3, AIR)
    f.pad()
    f.walls(1, 1, DARK, DARK, -2, 2, 0, 4)
    f.walls(2, 4, DARK, ORANGE, -2, 2, 0, 4)
    f.walls(5, 5, DARK, LIGHT, -2, 2, 0, 4)
    f.window_front(0, -1, 1, 3, 4, lip=False)
    f.window_side(-2, 1, 3, 3, 4)
    f.window_side(2, 1, 3, 3, 4)
    f.box(-1, 1, 1, 1, 1, 3, "minecraft:magma_block")
    f.box(-2, 6, 0, 2, 6, 4, DARK)
    f.box(-1, 6, 1, 1, 6, 3, "minecraft:magma_block")
    f.hazard_x(-2, 2, 6, 0)
    f.walls(7, 7, DARK, DARK, -2, 2, 0, 4)
    f.walls(8, 8, DARK, ORANGE, -2, 2, 0, 4)
    for x in (-1, 1):
        f.set(x, 7, 0, VENT)
    for x, z in ((-2, 0), (2, 0), (-2, 4)):
        f.set(x, 9, z, dslab())
    return f.done()


def awesome_shop():
    # 3 wide, 3 deep, 5 high: a kiosk of light panels on dark corner posts with hazard feet, a glass front and a pitched
    # roof of dark stairs with an orange ridge; the controller is its counter terminal. No power (as in 1.0).
    f = Frame(3, 3)
    f.m(0, 1, 0, CONTROLLER)
    f.mbox(0, 1, 1, 0, 2, 1, AIR)
    f.pad()
    f.walls(1, 3, DARK, LIGHT)
    for x in (-1, 1):
        for z in (0, 2):
            f.set(x, 1, z, YELLOW)
    f.window_front(0, -1, 1, 2, 3, lip=False)
    f.box(0, 4, 0, 0, 4, 2, ORANGE)
    f.set(-1, 4, 1, dstair("east"))
    f.set(1, 4, 1, dstair("west"))
    for x, z in ((-1, 0), (1, 0), (-1, 2), (1, 2)):
        f.set(x, 4, z, dslab())
    f.set(0, 3, 1, "minecraft:lantern[hanging=true,waterlogged=false]")
    return f.done()


def radar_tower():
    # 3 by 3, 16 high: a girder mast on a pad, legs with hazard feet, orange collars, and a dish of light panels and iron
    # bars at the top on a dark slab rim; the controller at its foot, the connector on the dish. 30 MW.
    f = Frame(3, 3)
    f.m(0, 1, 0, CONTROLLER)
    f.m(0, 15, 1, CONNECTOR)
    f.pad()
    for x in (-1, 1):
        for z in (0, 2):
            f.set(x, 1, z, YELLOW)
            f.set(x, 2, z, BLACK)
            f.girders(x, z, 3, 11)
            f.set(x, 12, z, DARK)
    for y in (4, 8, 12):
        f.walls(y, y, ORANGE, ORANGE)
    for y in range(1, 13):
        if y not in (4, 8, 12):
            f.set(0, y, 1, "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]")
    f.set(0, 4, 1, DARK)
    f.set(0, 8, 1, DARK)
    f.set(0, 12, 1, DARK)
    f.box(-1, 13, 0, 1, 13, 2, LIGHT)
    f.set(-1, 13, 2, dslab())
    f.set(1, 13, 2, dslab())
    f.box(-1, 14, 0, 1, 14, 0, LIGHT)
    f.box(-1, 15, 0, 1, 15, 0, "minecraft:iron_bars[east=true,north=false,south=false,waterlogged=false,west=true]")
    f.set(0, 14, 1, DARK)
    return f.done()


def truck_station():
    # work package 14. 5 wide, 5 deep, 5 high: a garage on a pad, dark below and orange above with a light eave band, its
    # dock in front of it (the 5 by 5 just past its front edge, where a vehicle on a route stops). Inside: two Item Vaults
    # the vehicles load from or unload into (item_in on its right side, item_out on its left) and a fuel tank they fill up
    # from (fluid_in at the back). The roof is a ribbed deck with a dark slab lip over the front and vents. The controller
    # is the terminal in its front wall: using it switches between loading and unloading.
    f = Frame(5, 5)
    f.controller(0, 1, 1)
    f.m(-1, 1, 2, "create:item_vault[axis=z,large=false]")
    f.m(-1, 1, 3, "create:item_vault[axis=z,large=false]")
    f.m(1, 1, 3, TANK)
    f.port(2, 1, 2, "east", "item_in", (-1, 1, 2))
    f.port(-2, 1, 3, "west", "item_out", (-1, 1, 3))
    f.port(1, 1, 4, "north", "fluid_in", (1, 1, 3))
    f.pad()
    f.hall(-2, 1, 2, 4, 1, 3, side=ORANGE, front_hazard=False, pilaster=None, shoulders=False)
    f.window_front(1, -1, 1, 2, 2, lip=False)
    f.deck(-2, 1, 2, 4, 4, eave=False)
    f.box(-2, 4, 0, 2, 4, 0, dslab())
    f.hazard_x(-2, 2, 0, 0)
    for x in (-2, 2):
        f.set(x, 1, 0, YELLOW)
        f.set(x, 2, 0, BLACK)
    f.set(0, 3, 4, VENT)
    return f.done()


# the Drone Port's landing spot (Build Gun frame): its drone takes off from and lands on the pad here, straight over the
# loading terminal (DRONE_TERMINAL), so the spot is the terminal's whatever way the port faces (DronePortBlockEntity)
DRONE_TERMINAL = (0, 1, 11)
DRONE_PAD_Y = 11


def drone_port():
    # Satisfactory's Drone Port at 1 m = 1 block: 23 by 23 (its 24 by 24), 15 high. A raised landing pad on four legs:
    # a light deck with a yellow and black hazard rim, an orange landing ring and a dark touchdown square in the middle,
    # lamps at its corners and a beacon mast at the back; a ladder up the front left leg. The legs are 2 by 2 dark pillars
    # with hazard feet, a light band and an orange collar under the deck, braced to the rim beams with dark stairs.
    # Underneath, the base: an orange hall framed in dark pillars, a light band and framed glass on its own pad, with the
    # loading terminal (the Drone Port block, which keeps the drone, its packages and fire charges) straight under the
    # touchdown square, and the controller in its front wall. Ports on the base's pad: packages in at the back, fire
    # charges in on the left, arrivals out on the right.
    f = Frame(23, 23)
    lamp = "siftec:prefab_lamp"
    f.m(*DRONE_TERMINAL, "siftec:drone_port")
    f.mbox(0, 1, 5, 0, 2, 5, AIR)
    f.m(1, 2, 5, CONTROLLER)
    f.port(0, 0, 18, "north", "item_in", DRONE_TERMINAL)
    f.port(-6, 0, 11, "west", "item_in", DRONE_TERMINAL)
    f.port(6, 0, 11, "east", "item_out", DRONE_TERMINAL)
    # the legs
    for lx, lz in ((-11, 0), (10, 0), (-11, 21), (10, 21)):
        f.box(lx, 0, lz, lx + 1, 9, lz + 1, DARK)
        for i, (x, z) in enumerate(((lx, lz), (lx + 1, lz), (lx + 1, lz + 1), (lx, lz + 1))):
            f.set(x, 0, z, YELLOW if i % 2 == 0 else BLACK)
            f.set(x, 1, z, BLACK if i % 2 == 0 else YELLOW)
        f.box(lx, 5, lz, lx + 1, 5, lz + 1, LIGHT)
        f.box(lx, 9, lz, lx + 1, 9, lz + 1, ORANGE)
        # knee braces under the rim beams
        inx = lx + 2 if lx < 0 else lx - 1
        inz = lz + 2 if lz == 0 else lz - 1
        f.set(inx, 8, lz, dstair("west" if lx < 0 else "east", "top"))
        f.set(inx, 8, lz + 1, dstair("west" if lx < 0 else "east", "top"))
        f.set(lx, 8, inz, dstair("south" if lz == 0 else "north", "top"))
        f.set(lx + 1, 8, inz, dstair("south" if lz == 0 else "north", "top"))
    for x in range(-9, 10):
        f.set(x, 9, 0, DARK)
        f.set(x, 9, 22, DARK)
    for z in range(2, 21):
        f.set(-11, 9, z, DARK)
        f.set(11, 9, z, DARK)
    # the base's pad and hall
    f.pad(-6, 6, 4, 18)
    f.hall(-5, 5, 5, 17, 1, 6, shoulders=False)
    f.deck(-5, 5, 5, 17, 7, eave=False)
    f.window_side(-5, 8, 14, 3, 5)
    f.window_side(5, 8, 14, 3, 5)
    f.window_front(5, -3, -2, 3, 5, lip=False)
    f.window_front(5, 2, 3, 3, 5, lip=False)
    f.window_front(17, -2, 2, 3, 5, lip=False)
    # the base holds the deck up: dark columns from the hall's corners to the deck
    for x, z in ((-5, 5), (5, 5), (-5, 17), (5, 17)):
        f.box(x, 8, z, x, 9, z, DARK)
    # the deck
    cx, cz = 0, 11
    for x in range(-11, 12):
        for z in range(0, 23):
            rim = x in (-11, 11) or z in (0, 22)
            d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
            if rim:
                spec = YELLOW if (x + z) % 2 == 0 else BLACK
            elif abs(x - cx) <= 1 and abs(z - cz) <= 1:
                spec = DARK
            elif 6.5 <= d < 7.5:
                spec = ORANGE
            else:
                spec = PAD
            f.set(x, 10, z, spec)
    for x, z in ((-11, 0), (11, 0), (-11, 22), (11, 22)):
        f.set(x, 11, z, lamp)
    # a beacon mast on the back rim
    f.box(10, 11, 22, 10, 13, 22, DARK)
    f.set(10, 12, 22, ORANGE)
    f.set(10, 14, 22, lamp)
    # a ladder up the front left leg and through the deck
    for y in range(1, 11):
        f.set(-9, y, 1, ladder("east"))
    return f.done()


# ------------------------------------------------------------------------------------------------ the Blueprint Designers
# Satisfactory's Blueprint Designer: a build floor the size of the build area (9 by 9 for Mk.1, 13 for Mk.2, 17 for Mk.3),
# light grey with a white grid through its middle and a yellow and black hazard edge, inside a frame outlining the whole
# cube: dark rails round the floor, a dark pillar at each corner with hazard feet and an orange collar halfway up, and a
# top frame in the Mk's colour (orange, copper, light grey) with a lamp on each pillar. The cube over the floor (as tall
# as it is wide) is left out of the template: it is no part of the building, so it is open to walk, fly and build in, and
# dismantling never touches what stands there. The terminal (the Blueprint Designer block, which is the building's
# controller) is set into the middle of the front side at floor level, facing out; it saves, clears and keeps the
# flight of the cube behind it (blueprint/DesignerBlockEntity.java).
DESIGNER_TERMINAL = (0, 1, 0)


def blueprint_designer(reach, block, trim):
    side = 2 * reach + 1
    o, back = reach + 1, side + 1
    f = Frame(side + 2, side + 2)
    f.m(*DESIGNER_TERMINAL, f"siftec:{block}[facing=south]")
    for x in range(-reach, reach + 1):
        for z in range(1, side + 1):
            if abs(x) == reach or z in (1, side):
                spec = YELLOW if (x + z) % 2 == 0 else BLACK
            elif x % 4 == 0 or (z - o) % 4 == 0:
                spec = "minecraft:white_concrete"
            else:
                spec = PAD
            f.set(x, 0, z, spec)
    # the bottom rails round the floor and the top frame over it, then the corner pillars with their lamps
    for y, spec in ((0, DARK), (side + 1, trim)):
        for x in range(-o, o + 1):
            f.set(x, y, 0, spec)
            f.set(x, y, back, spec)
        for z in range(0, back + 1):
            f.set(-o, y, z, spec)
            f.set(o, y, z, spec)
    for x in (-o, o):
        for z in (0, back):
            f.box(x, 0, z, x, side, z, DARK)
            f.set(x, 1, z, YELLOW)
            f.set(x, 2, z, BLACK)
            f.set(x, side // 2 + 1, z, ORANGE)
            f.set(x, side + 1, z, "siftec:prefab_lamp")
    # dark slab lips on the top frame's inside edge, either side of each pillar
    for x in (-o + 1, o - 1):
        for z in (0, back):
            f.set(x, side, z, dslab("top"))
    for z in (1, back - 1):
        for x in (-o, o):
            f.set(x, side, z, dslab("top"))
    return f.done()


# ------------------------------------------------------------------------------------------------ the Foundations tab
# 8 by 8 foundations, 8-wide walls and a roof (Satisfactory's sizes in blocks), built of the blocks their materials
# replace (content.py MATERIALS): a foundation's top light grey concrete and body grey, a wall's panels white, a
# roof's tiles orange terracotta. The controller is the middle of the front edge.
F_TOP, F_BODY, W_BODY, R_BODY = "minecraft:light_gray_concrete", "minecraft:gray_concrete", "minecraft:white_concrete", "minecraft:orange_terracotta"


def foundation(height):
    f = Frame(8, 8, even=True)
    f.box(-4, 0, 0, 3, height - 2, 7, F_BODY) if height > 1 else None
    f.box(-4, height - 1, 0, 3, height - 1, 7, F_TOP)
    f.set(0, 0, 0, CONTROLLER)
    return f.done()


def wall():
    f = Frame(8, 1, even=True)
    f.box(-4, 0, 0, 3, 3, 0, W_BODY)
    f.set(0, 0, 0, CONTROLLER)
    return f.done()


def roof():
    # rising one block every two toward the back: a stepped slope four blocks high
    f = Frame(8, 8, even=True)
    for z in range(8):
        h = z // 2
        f.box(-4, h, z, 3, h, z, R_BODY)
    f.set(0, 0, 0, CONTROLLER)
    return f.done()


def billboard(width, height, post_x):
    # a board on two posts, a dark rim round a white face; its sign (the text) at the foot of the face, in front of it
    f = Frame(width, 2, even=True)
    left, right = -(width // 2), width // 2 - 1
    for x in post_x:
        f.box(x, 0, 1, x, 1, 1, LIGHT)
    f.box(left, 2, 1, right, height + 1, 1, DARK)
    f.box(left + 1, 3, 1, right - 1, height, 1, W_BODY)
    # the sign hangs on the rim's bottom row, which stays whole (a white block there left a gap in the frame)
    f.set(0, 2, 0, f"siftec:billboard_{'small' if width == 8 else 'large'}_sign[facing=south,waterlogged=false]")
    f.set(post_x[0], 0, 0, CONTROLLER)
    return f.done()


BUILT = {"test_building": test_building, "test_miner": test_miner,
         "constructor": constructor, "smelter": smelter,
         "miner_mk1": lambda: miner(8, ORANGE, ORANGE), "miner_mk2": lambda: miner(9, LIGHT, ORANGE),
         "miner_mk3": lambda: miner(10, DARK, LIGHT, extension=True), "portable_miner": portable_miner,
         "storage_container": lambda: storage(5, 1), "industrial_storage_container": lambda: storage(6, 2),
         "power_pole_mk1": lambda: pole(5, GIRDER, ORANGE), "power_pole_mk2": lambda: pole(6, GIRDER, YELLOW),
         "power_pole_mk3": lambda: pole(7, LIGHT, ORANGE), "power_tower": power_tower, "power_storage": power_storage,
         "biomass_burner": biomass_burner, "coal_generator": coal_generator, "fuel_generator": fuel_generator,
         "geothermal_generator": geothermal_generator, "nuclear_power_plant": nuclear_power_plant,
         "alien_power_augmenter": alien_power_augmenter,
         "assembler": assembler, "foundry": foundry, "refinery": refinery, "blender": blender, "manufacturer": manufacturer,
         "quantum_encoder": quantum_encoder, "packager": packager, "water_extractor": water_extractor, "oil_extractor": oil_extractor,
         "resource_well_pressurizer": resource_well_pressurizer, "resource_well_extractor": resource_well_extractor,
         "particle_accelerator": particle_accelerator, "converter": converter,
         "equipment_workshop": equipment_workshop, "awesome_sink": awesome_sink, "awesome_shop": awesome_shop, "radar_tower": radar_tower,
         "truck_station": truck_station, "drone_port": drone_port,
         "blueprint_designer": lambda: blueprint_designer(4, "blueprint_designer", ORANGE),
         "blueprint_designer_mk2": lambda: blueprint_designer(6, "blueprint_designer_mk2", "minecraft:waxed_copper_block"),
         "blueprint_designer_mk3": lambda: blueprint_designer(8, "blueprint_designer_mk3", LIGHT),
         "foundation_1m": lambda: foundation(1), "foundation_2m": lambda: foundation(2), "foundation_4m": lambda: foundation(4),
         "wall_4m": wall, "roof_4m": roof,
         "billboard_small": lambda: billboard(8, 4, (-3, 2)), "billboard_large": lambda: billboard(16, 8, (-6, 5))}

if __name__ == "__main__":
    for bid, make in BUILT.items():
        write_nbt(os.path.join(ROOT, bid + ".nbt"), make())
        print("wrote", bid)
