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



# ---- the Build Gun's blocks and the drawing kit
ORANGE, DARK, LIGHT, GLASS = "minecraft:orange_concrete", "siftec:steel_casing", "minecraft:iron_block", "create:framed_glass"
YELLOW, BLACK, PAD = "minecraft:yellow_concrete", "minecraft:black_concrete", "minecraft:light_gray_concrete"
VENT = "siftec:steel_window"
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




# ------------------------------------------------------------------------------------------------ the plain look
# Every building is as small as its working parts allow while still reading as its machine: the Create machines are
# what you see, through open sides. The look is plain on purpose (the final looks are designed in game): a light grey
# pad, a one-block Steel Casing plinth all round, Steel Casing corner posts with the sides open between them, and a flat
# orange roof. A building's paint (building/Paint.java) recolours its orange, its Steel Casing and its iron blocks.
#
# Ports are where belts and pipes join, outputs at the front and inputs at the back, as Satisfactory's machines have
# them. An item port stands one block over the pad (y 1): a belt on the ground ends in the block outside it and Create's
# own funnel goes on the port's face, sitting on that belt's end (into the port for an input, out onto the belt for an
# output), exactly as a funnel feeds a chest from a belt. A fluid port is in the pad row (y 0), so a pipe on the ground
# runs straight in.


def ctl(f, x, y, z):
    """The controller set into the front wall, facing out (its face is outside the footprint, so nothing to keep clear)."""
    f.m(x, y, z, CONTROLLER)


def item_port(f, x, z, facing, kind, target):
    """An item port one block over the pad, where a belt on the ground with a funnel on its end reaches it."""
    f.port(x, 1, z, facing, kind, target)


def fluid_port(f, x, z, facing, kind, target):
    """A fluid port in the pad row, where a pipe on the ground runs straight in."""
    f.port(x, 0, z, facing, kind, target)


def shell(f, y_top, x0=None, x1=None, z0=0, z1=None, roof=ORANGE, pad=True, y_bottom=1):
    """The plain look: the pad, a Steel Casing plinth, Steel Casing corner posts with open sides up to y_top, and a flat
    roof over it."""
    x0 = -(f.w // 2) if x0 is None else x0
    x1 = f.w // 2 if x1 is None else x1
    z1 = f.d - 1 if z1 is None else z1
    if pad:
        f.box(-(f.w // 2), 0, 0, f.w // 2, 0, f.d - 1, PAD)
    f.walls(y_bottom, y_bottom, DARK, DARK, x0, x1, z0, z1)
    f.walls(y_bottom + 1, y_top, DARK, AIR, x0, x1, z0, z1)
    if roof:
        f.box(x0, y_top + 1, z0, x1, y_top + 1, z1, roof)


def mixer(f, x, y, z):
    """A Mechanical Mixer turned through the cogwheel beside it by a motor on top of the cogwheel."""
    f.m(x, y, z, MIXER)
    f.m(x + 1, y, z, COG)
    f.m(x + 1, y + 1, z, MOTOR.format("down"))


def heated_mixer(f, x, z, basin=BASIN):
    """A Blaze Burner under a Basin under a Mixer; returns the Basin's cell."""
    f.m(x, 1, z, BURNER)
    f.m(x, 2, z, basin)
    f.m(x, 3, z, AIR)
    mixer(f, x, 4, z)
    return (x, 2, z)


# ------------------------------------------------------------------------------------------------ production

def constructor():
    # 5 by 5, 6 high: a Mechanical Press over a Basin, its motor beside it
    f = Frame(5, 5)
    f.m(0, 1, 2, BASIN)
    f.m(0, 2, 2, AIR)
    f.m(0, 3, 2, "create:mechanical_press[facing=west]")
    f.m(1, 3, 2, MOTOR.format("west"))
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (0, 1, 2))
    item_port(f, 0, 4, "north", "item_in", (0, 1, 2))
    shell(f, 3)
    f.m(0, 5, 2, CONNECTOR)
    return f.done()


def smelter():
    # 5 by 5, 7 high: the motor in the left wall turns an Encased Fan that blows through a lava cell onto a Depot (Create's
    # bulk blasting); the controller feeds the Depot and takes the ingots off, and the ports work on the controller. A
    # stack at the back.
    f = Frame(5, 5)
    f.m(-2, 1, 2, MOTOR.format("east"))
    f.m(-1, 1, 2, "create:encased_fan[facing=east]")
    f.m(0, 1, 2, "minecraft:lava")
    f.m(1, 1, 2, DEPOT)
    # the lava is closed in on every side but the fan's and the Depot's
    for at in ((0, 2, 2), (0, 1, 1), (0, 1, 3)):
        f.m(*at, DARK)
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (1, 1, 0))
    item_port(f, 0, 4, "north", "item_in", (1, 1, 0))
    shell(f, 2)
    f.box(-1, 4, 3, -1, 6, 3, DARK)
    f.m(1, 4, 2, CONNECTOR)
    return f.done()


MINER_HEIGHT = {1: 6, 2: 7, 3: 8}


def miner(tier):
    # 5 by 5 over a node (the node's own cell left out), 6, 7 or 8 high by Mk: four legs, a deck with the Drill pointing
    # down at the node, the motor on the Drill (Mk.3: an extension behind it for its 45 MW), an open tower round them
    # with a roof in the Mk's colour; a chute down to the controller, the output at the front
    height = MINER_HEIGHT[tier]
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    f.void(0, 0, 2)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, DARK)
    f.m(0, 1, 2, "create:mechanical_drill[facing=down,waterlogged=false]")
    f.m(0, 2, 2, MOTOR.format("down"))
    if tier == 3:
        f.m(0, 3, 2, EXTENSION.format("down"))
    f.m(0, 2, 1, "create:chute[facing=down,shape=normal,waterlogged=false]")
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (1, 1, 0))
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.walls(2, height - 3, DARK, AIR, -1, 1, 1, 3)
    f.box(-1, height - 2, 1, 1, height - 2, 3, {1: ORANGE, 2: LIGHT, 3: DARK}[tier])
    f.m(0, height - 1, 2, CONNECTOR)
    return f.done()


def assembler():
    # 5 wide, 7 deep, 7 high: a Mixer over a Basin, two inputs at the back
    f = Frame(5, 7)
    f.m(0, 1, 3, BASIN)
    f.m(0, 2, 3, AIR)
    mixer(f, 0, 3, 3)
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (0, 1, 3))
    item_port(f, -1, 6, "north", "item_in", (0, 1, 3))
    item_port(f, 1, 6, "north", "item_in", (0, 1, 3))
    shell(f, 4)
    f.m(0, 6, 3, CONNECTOR)
    return f.done()


def foundry():
    # 7 wide, 5 deep, 8 high: a heated Mixer (Blaze Burner, Basin, Mixer), two inputs at the back
    f = Frame(7, 5)
    basin = heated_mixer(f, 0, 2)
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", basin)
    item_port(f, -1, 4, "north", "item_in", basin)
    item_port(f, 1, 4, "north", "item_in", basin)
    shell(f, 5)
    f.m(-2, 7, 2, CONNECTOR)
    return f.done()


def refinery():
    # 7 by 7, 10 high: a heated Mixer, two stacks on the roof; item and fluid out at the front, in at the back
    f = Frame(7, 7)
    basin = heated_mixer(f, 0, 3)
    ctl(f, 0, 1, 0)
    item_port(f, -1, 0, "south", "item_out", basin)
    fluid_port(f, 1, 0, "south", "fluid_out", basin)
    item_port(f, -2, 6, "north", "item_in", basin)
    fluid_port(f, 2, 6, "north", "fluid_in", basin)
    shell(f, 5)
    f.box(-2, 7, 5, -2, 9, 5, DARK)
    f.box(2, 7, 5, 2, 8, 5, DARK)
    f.m(0, 7, 3, CONNECTOR)
    return f.done()


def blender():
    # 9 wide, 7 deep, 8 high: a heated Mixer over a Brass Basin; two items and two fluids in at the back
    f = Frame(9, 7)
    basin = heated_mixer(f, 0, 3, BRASS_BASIN)
    ctl(f, 0, 1, 0)
    item_port(f, -1, 0, "south", "item_out", basin)
    fluid_port(f, 1, 0, "south", "fluid_out", basin)
    item_port(f, -3, 6, "north", "item_in", basin)
    item_port(f, -1, 6, "north", "item_in", basin)
    fluid_port(f, 1, 6, "north", "fluid_in", basin)
    fluid_port(f, 3, 6, "north", "fluid_in", basin)
    shell(f, 5)
    f.m(0, 7, 3, CONNECTOR)
    return f.done()


def assembly_line(f, x, z_basin, stations, basin=BRASS_BASIN):
    """The Manufacturer's way: a Mixer over a Brass Basin at the back makes the Core, then a row of Depots toward the
    front, each under a Deployer (or a Spout, "spout"), and an Item Drain ("drain") last. The Deployers' shafts run front to
    back through shafts between them, turned by one motor at the front end. Returns the Basin's cell and the last
    station's z."""
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
    for zz in range(min(deployers), max(deployers)):
        if zz not in deployers:
            f.m(x, 3, zz, SHAFT_Z)
    f.m(x, 3, min(deployers) - 1, MOTOR.format("north"))
    return (x, 1, z_basin), z + 2


def manufacturer():
    # 9 wide, 11 deep, 7 high: the Mixer over the Brass Basin at the back makes the Core; four Depots under Deployers carry
    # it to the front, each Deployer adding the last of one input. Four inputs at the back, the output at the front.
    f = Frame(9, 11)
    basin, _ = assembly_line(f, 0, 9, ["deployer"] * 4)
    ctl(f, 2, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (2, 1, 0))
    for x in (-3, -1, 1, 3):
        item_port(f, x, 10, "north", "item_in", basin)
    shell(f, 4)
    f.m(0, 6, 5, CONNECTOR)
    return f.done()


def quantum_encoder():
    # 9 wide, 15 deep, 7 high: the Core from the Mixer over the Brass Basin; a Spout fills it with Excited Photonic Matter,
    # four Deployers add the inputs, and an Item Drain takes the Dark Matter Residue off. Three items and the photonic
    # matter in at the back, the item and the residue out at the front.
    f = Frame(9, 15)
    basin, last = assembly_line(f, 0, 13, ["spout", "deployer", "deployer", "deployer", "deployer", "drain"])
    ctl(f, 2, 1, 0)
    item_port(f, -1, 0, "south", "item_out", (2, 1, 0))
    fluid_port(f, 1, 0, "south", "fluid_out", (0, 1, last))
    for x in (-3, -1, 1):
        item_port(f, x, 14, "north", "item_in", basin)
    fluid_port(f, 3, 14, "north", "fluid_in", (0, 3, 11))
    shell(f, 4)
    f.m(0, 6, 7, CONNECTOR)
    return f.done()


def packager():
    # 5 by 5, 6 high: a Spout over a Depot fills empty canisters and tanks, an Item Drain empties packaged fluids
    f = Frame(5, 5)
    f.m(-1, 1, 2, DEPOT)
    f.m(-1, 2, 2, AIR)
    f.m(-1, 3, 2, SPOUT)
    f.m(1, 1, 2, DRAIN)
    f.m(0, 1, 2, AIR)
    ctl(f, 1, 1, 0)
    item_port(f, -1, 0, "south", "item_out", (1, 1, 0))
    fluid_port(f, 1, 0, "south", "fluid_out", (1, 1, 2))
    item_port(f, -1, 4, "north", "item_in", (1, 1, 0))
    fluid_port(f, 1, 4, "north", "fluid_in", (-1, 3, 2))
    shell(f, 3)
    f.m(0, 5, 2, CONNECTOR)
    return f.done()


def water_extractor():
    # 5 by 5, 6 high, standing in water: the pad row is left out but for four pontoon legs, an orange deck, the pump over
    # the open intake in the middle (the water cell stays) turned by the motor above it, the water tank at the front by
    # the port
    f = Frame(5, 5)
    for x in range(-2, 3):
        for z in range(5):
            if not (x in (-2, 2) and z in (0, 4)):
                f.void(x, 0, z)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, DARK)
    f.m(0, 1, 2, "create:mechanical_pump[facing=down]")
    f.m(0, 2, 2, MOTOR.format("down"))
    f.m(0, 2, 1, TANK)
    ctl(f, 1, 2, 0)
    f.port(0, 1, 0, "south", "fluid_out", (0, 2, 1))
    f.box(-2, 1, 0, 2, 1, 4, ORANGE)
    f.walls(2, 3, DARK, AIR, -1, 1, 1, 3)
    f.box(-1, 4, 1, 1, 4, 3, ORANGE)
    f.m(0, 5, 2, CONNECTOR)
    return f.done()


def node_rig(f):
    """Four legs round a node in the middle of a 5 by 5 footprint, and a Steel Casing deck over it."""
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    f.void(0, 0, 2)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, DARK)
    f.box(-2, 1, 0, 2, 1, 4, DARK)


def oil_extractor():
    # 5 by 5, 8 high over an oil node: the pump on the node turned by the motor above it, an open derrick round them
    f = Frame(5, 5)
    f.m(0, 1, 2, "create:mechanical_pump[facing=down]")
    f.m(0, 2, 2, MOTOR.format("down"))
    f.m(0, 1, 1, TANK)
    ctl(f, 1, 1, 0)
    fluid_port(f, 0, 0, "south", "fluid_out", (0, 1, 1))
    node_rig(f)
    f.walls(2, 5, DARK, AIR, -1, 1, 1, 3)
    f.box(-1, 6, 1, 1, 6, 3, ORANGE)
    f.m(0, 7, 2, CONNECTOR)
    return f.done()


def resource_well_pressurizer():
    # 5 by 5, 7 high over a well's node: the pump on the node turned by the motor above it, in an open pump house
    f = Frame(5, 5)
    f.m(0, 1, 2, "create:mechanical_pump[facing=down]")
    f.m(0, 2, 2, MOTOR.format("down"))
    ctl(f, 1, 1, 0)
    node_rig(f)
    f.walls(2, 4, DARK, AIR, -2, 2, 0, 4)
    f.box(-2, 5, 0, 2, 5, 4, ORANGE)
    f.m(0, 6, 2, CONNECTOR)
    return f.done()


def resource_well_extractor():
    # 3 by 3, 3 high: a tank under a pump, four posts round them; the fluid out at the front
    f = Frame(3, 3)
    f.m(0, 1, 1, TANK)
    f.m(0, 2, 1, "create:mechanical_pump[facing=down]")
    ctl(f, 1, 1, 0)
    fluid_port(f, 0, 0, "south", "fluid_out", (0, 1, 1))
    f.box(-1, 0, 0, 1, 0, 2, PAD)
    for x in (-1, 1):
        for z in (0, 2):
            f.set(x, 1, z, DARK)
    return f.done()


def particle_accelerator():
    # 9 wide, 11 deep, 10 high: a fan blows up through a pair of Crushing Wheels (the collider), a Mixer over a Brass
    # Basin behind it; two items and a fluid in at the back, the item out at the front
    f = Frame(9, 11)
    f.m(0, 1, 7, BRASS_BASIN)
    f.m(0, 2, 7, AIR)
    mixer(f, 0, 3, 7)
    f.m(0, 0, 4, MOTOR.format("up"))
    f.m(0, 1, 4, "create:encased_fan[facing=up]")
    f.mbox(0, 2, 4, 0, 3, 4, AIR)
    for x in (-1, 1):
        f.m(x, 4, 4, "create:crushing_wheel[axis=z]")
        f.m(x, 4, 3, MOTOR.format("north"))
    f.m(0, 4, 4, AIR)
    f.mbox(0, 5, 4, 0, 6, 4, AIR)
    f.m(0, 7, 4, DARK)
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (1, 1, 0))
    item_port(f, -2, 10, "north", "item_in", (0, 1, 7))
    item_port(f, 0, 10, "north", "item_in", (0, 1, 7))
    fluid_port(f, 2, 10, "north", "fluid_in", (0, 1, 7))
    shell(f, 7)
    f.m(0, 9, 4, CONNECTOR)
    return f.done()


def converter():
    # 7 by 7, 8 high: a fan blowing through a soul campfire onto a Depot under a Deployer (haunting), an Item Drain beside
    # it, and a Mixer over a Brass Basin behind; two items in at the back, item and fluid out at the front
    f = Frame(7, 7)
    f.m(-1, 1, 4, BRASS_BASIN)
    f.m(-1, 2, 4, AIR)
    mixer(f, -1, 3, 4)
    f.m(-2, 1, 2, MOTOR.format("east"))
    f.m(-1, 1, 2, "create:encased_fan[facing=east]")
    f.m(0, 1, 2, "minecraft:soul_campfire[lit=true,signal_fire=false,waterlogged=false,facing=south]")
    f.m(1, 1, 2, DEPOT)
    f.m(1, 2, 2, AIR)
    f.m(1, 3, 2, DEPLOYER)
    f.m(1, 3, 1, MOTOR.format("north"))
    f.m(1, 1, 3, DRAIN)
    ctl(f, 0, 1, 0)
    item_port(f, -1, 0, "south", "item_out", (0, 1, 0))
    fluid_port(f, 1, 0, "south", "fluid_out", (1, 1, 3))
    item_port(f, -1, 6, "north", "item_in", (-1, 1, 4))
    item_port(f, 1, 6, "north", "item_in", (-1, 1, 4))
    shell(f, 5)
    f.m(-2, 7, 2, CONNECTOR)
    return f.done()


# ------------------------------------------------------------------------------------------------ power
TURBINE = "siftec:turbine[facing={}]"
# the generators' machines: a real Create boiler (Fluid Tank over Blaze Burners, or over Create: New Age's Heaters in the
# Nuclear Power Plant) drives Steam Engines, Diesel Generators' engine drives the Fuel Generator and the geyser's
# turbine the Geothermal Generator; the shaft turns Create: New Age generator coils, ringed with magnets, and carbon
# brushes take their energy for the grid. The magnets are what make the MW: a coil gives the SU its magnets take off the
# shaft as New Age energy, so magnets x RPM = Satisfactory's MW x 204.8.
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
POLE_HEIGHT = {1: 4, 2: 5, 3: 6}
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




def pole(tier):
    # one block wide, 4, 5 or 6 high by Mk: the controller at its foot, a column in the Mk's colour, the connector on top
    height = POLE_HEIGHT[tier]
    f = Frame(1, 1)
    f.m(0, 0, 0, CONTROLLER)
    f.m(0, height - 1, 0, CONNECTOR)
    f.box(0, 1, 0, 0, height - 2, 0, {1: DARK, 2: LIGHT, 3: ORANGE}[tier])
    return f.done()


def power_tower():
    # 3 by 3, 11 high: four Steel Casing legs up to an orange platform, the connector on top
    f = Frame(3, 3)
    f.m(0, 0, 0, CONTROLLER)
    for x in (-1, 1):
        for z in (0, 2):
            f.box(x, 0, z, x, 8, z, DARK)
    f.box(-1, 9, 0, 1, 9, 2, ORANGE)
    f.m(0, 10, 1, CONNECTOR)
    return f.done()


def power_storage():
    # 3 by 3, 6 high: a core of New Age's overcharged iron coils, the connector on the roof
    f = Frame(3, 3)
    f.mbox(0, 1, 1, 0, 3, 1, "create_new_age:overcharged_iron_wire_block")
    ctl(f, 1, 1, 0)
    shell(f, 3)
    f.m(0, 5, 1, CONNECTOR)
    return f.done()


def biomass_burner():
    # 5 wide, 6 deep, 8 high. At the back a Blaze Burner under a four-high Fluid Tank (a boiler of size 4: one heat
    # level), its Steam Engine on the tank's second block driving a shaft that turns one generator coil with four netherite
    # magnets (96 SU a turn at 64 RPM: 6,144 SU, 30 MW) and the carbon brushes in the left wall. The burner is fed from the
    # fuel port at the back; the boiler is a closed loop (Satisfactory's burner takes no water). The HUB's burners are the
    # same machine (prefab/HubBurnerBlockEntity.java, MACHINE).
    f = Frame(5, 6)
    f.m(1, 1, 5, BURNER)
    f.mbox(1, 2, 5, 1, 5, 5, TANK)
    f.m(1, 3, 4, ENGINE.format("south"))
    f.m(1, 3, 3, AIR)
    f.m(1, 3, 2, POWERED_SHAFT.format("x"))
    f.m(0, 3, 2, SHAFT_A.format("x"))
    coil(f, -1, 3, 2, "x", [NETHERITE] * 4)
    f.m(-2, 3, 2, BRUSHES.format("east"))
    ctl(f, 1, 1, 0)
    item_port(f, 0, 5, "north", "item_in", (1, 1, 0))
    shell(f, 5)
    f.m(0, 7, 2, CONNECTOR)
    return f.done()


def coal_generator():
    # 7 wide, 8 deep, 8 high (75 MW). Two Blaze Burners under a 2 by 2 by 2 Fluid Tank, two Steam Engines on its front
    # driving the shaft through a coil with ten netherite magnets, the brushes in the left wall; coal in at the back,
    # water into the intake tank beside the boiler
    f = Frame(7, 8)
    for x in (1, 2):
        f.m(x, 1, 5, BURNER)
        f.m(x, 1, 6, DARK)
    f.mbox(1, 2, 5, 2, 3, 6, TANK)
    for x in (1, 2):
        f.m(x, 3, 4, ENGINE.format("south"))
        f.m(x, 3, 3, AIR)
        f.m(x, 3, 2, POWERED_SHAFT.format("x"))
    f.m(0, 3, 2, SHAFT_A.format("x"))
    coil(f, -1, 3, 2, "x", [NETHERITE] * 10)
    f.m(-2, 3, 2, BRUSHES.format("east"))
    f.m(0, 1, 6, TANK)
    ctl(f, 1, 1, 0)
    item_port(f, -1, 7, "north", "item_in", (1, 1, 0))
    fluid_port(f, 0, 7, "north", "fluid_in", (0, 1, 6))
    shell(f, 5)
    f.m(0, 7, 2, CONNECTOR)
    return f.done()


def fuel_generator():
    # 7 wide, 5 deep, 8 high (250 MW): Diesel Generators' engine turning a coil with eight netherite magnets and one
    # fluxuated magnetite, the brushes beside it; fuel in at the back
    f = Frame(7, 5)
    f.m(1, 3, 2, DIESEL_ENGINE.format("west"))
    coil(f, 0, 3, 2, "x", [NETHERITE] * 8 + [FLUXUATED])
    f.m(-1, 3, 2, BRUSHES.format("east"))
    ctl(f, 2, 1, 0)
    fluid_port(f, 1, 4, "north", "fluid_in", (1, 3, 2))
    shell(f, 5)
    f.m(0, 7, 2, CONNECTOR)
    return f.done()


def geothermal_generator():
    # 5 by 5, 10 high, on four legs over a sulfur geyser (the vent cell under the middle is left as air): the geyser's
    # steam turns the turbine, which turns two coils (twelve and eight netherite magnets) high over the vent; the
    # sulfuric acid collects in the tank on the deck and goes out at the back
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, DARK)
    f.m(-1, 2, 2, TANK)
    f.m(1, 5, 2, TURBINE.format("west"))
    coil(f, 0, 5, 2, "x", [NETHERITE] * 12)
    coil(f, -1, 5, 2, "x", [NETHERITE] * 8)
    f.m(-2, 5, 2, BRUSHES.format("east"))
    ctl(f, 1, 1, 0)
    f.port(-1, 2, 4, "north", "fluid_out", (-1, 2, 2))
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.walls(2, 7, DARK, AIR)
    f.box(-2, 8, 0, 2, 8, 4, ORANGE)
    f.m(0, 9, 2, CONNECTOR)
    return f.done()


# The Nuclear Power Plant's layout (Build Gun frame), shared with building/Reactors.java through siftec_content.json
NUCLEAR_BOILERS = ((-6, 7), (4, 7))      # each boiler's lowest x and z: 3 by 3 by 8 Fluid Tanks from y 4 to 11
NUCLEAR_ENGINE_ROWS = range(4, 10)       # six rows of three Steam Engines on each boiler's front
NUCLEAR_SHAFT_Z = 4


def nuclear_power_plant():
    # 17 wide, 11 deep, 17 high (2,500 MW). The two cooling towers are Create boilers: 3 by 3 by 8 Fluid Tanks (eighteen
    # heat levels each) over nine of Create: New Age's Heaters, which take the heat of the reactor below them: a layer of
    # New Age heat vents over seven reactor rods each, fed by the fuel acceptors at the sides. Eighteen Steam Engines on
    # the front of each tower drive six shafts joined by a column of cogwheels; two large-to-small cogwheel steps take it
    # from 64 to 256 RPM, where seven generator coils with 83 netherite magnets and one fluxuated magnetite take 2,000 SU a
    # turn (512,000 SU, 2,500 MW) and the carbon brushes at the end give it to the grid. Rods in and waste out at the
    # controller's ports, water into the intake tank at the back.
    f = Frame(17, 11)
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
        for x in range(-3, 4):
            f.m(x, y, NUCLEAR_SHAFT_Z, SHAFT_A.format("x") if x != -2 else COG_A.format("x"))
    # the column of cogwheels joins the six rows; then 64 -> 128 -> 256 RPM through two large and small cogwheel steps
    f.m(-2, 10, 4, COG_A.format("x"))
    f.m(-1, 10, 4, LARGE_COG.format("x"))
    f.m(-1, 11, 3, COG_A.format("x"))
    f.m(0, 11, 3, LARGE_COG.format("x"))
    f.m(0, 12, 2, COG_A.format("x"))
    for k, x in enumerate(range(1, 8)):
        coil(f, x, 12, 2, "x", [NETHERITE] * 12 if k < 6 else [NETHERITE] * 11 + [FLUXUATED])
    f.m(8, 12, 2, BRUSHES.format("west"))
    f.m(-2, 1, 9, TANK)
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (1, 1, 0))
    item_port(f, 0, 10, "north", "item_in", (1, 1, 0))
    fluid_port(f, -2, 10, "north", "fluid_in", (-2, 1, 9))
    shell(f, 14)
    f.m(0, 16, 5, CONNECTOR)
    return f.done()


def alien_power_augmenter():
    # 3 by 3, 6 high: a glowing column (sea lanterns round an end rod); Somersloops in at the back
    f = Frame(3, 3)
    f.m(0, 1, 1, "minecraft:sea_lantern")
    f.m(0, 2, 1, "minecraft:end_rod[facing=up]")
    f.m(0, 3, 1, "minecraft:sea_lantern")
    ctl(f, 1, 1, 0)
    item_port(f, 0, 2, "north", "item_in", (1, 1, 0))
    shell(f, 3)
    f.m(0, 5, 1, CONNECTOR)
    return f.done()


# ------------------------------------------------------------------------------------------------ logistics and special

def storage(vaults):
    # 3 wide, the Item Vaults along the middle with the output at the front and the input at the back, both on the vault
    depth = vaults + 2
    f = Frame(3, depth)
    for z in range(1, 1 + vaults):
        f.m(0, 1, z, "create:item_vault[axis=z,large=false]")
    ctl(f, 1, 1, 0)
    item_port(f, 0, 0, "south", "item_out", (0, 1, 1))
    item_port(f, 0, depth - 1, "north", "item_in", (0, 1, 1))
    shell(f, 1, roof=None)
    f.box(-1, 2, 0, 1, 2, depth - 1, ORANGE)
    return f.done()


def truck_station():
    # 5 by 5, 4 high: a garage with its dock in front of it (where a vehicle on a route stops). Two Item Vaults the
    # vehicles load from or unload into (item_in on its right side, item_out on its left) and a fuel tank they fill up from
    # (fluid_in at the back). The controller is the terminal in its front wall: using it switches between loading and
    # unloading.
    f = Frame(5, 5)
    ctl(f, 0, 1, 0)
    f.m(-1, 1, 2, "create:item_vault[axis=z,large=false]")
    f.m(-1, 1, 3, "create:item_vault[axis=z,large=false]")
    f.m(1, 1, 3, TANK)
    item_port(f, 2, 2, "east", "item_in", (-1, 1, 2))
    item_port(f, -2, 3, "west", "item_out", (-1, 1, 3))
    fluid_port(f, 1, 4, "north", "fluid_in", (1, 1, 3))
    shell(f, 2)
    return f.done()


# the Drone Port's landing spot (Build Gun frame): its drone takes off from and lands on the pad here, straight over the
# loading terminal (DRONE_TERMINAL), so the spot is the terminal's whatever way the port faces (DronePortBlockEntity)
DRONE_TERMINAL = (0, 1, 4)
DRONE_PAD_Y = 6  # where the drone sits: on the deck, one over it


def drone_port():
    # 9 by 9, 6 high: a landing pad on four legs over the base, the loading terminal (the Drone Port block, which keeps the
    # drone, its packages and fire charges) in the base straight under the pad's middle. Packages in at the back, fire
    # charges in on the left, arrivals out on the right.
    f = Frame(9, 9)
    f.m(*DRONE_TERMINAL, "siftec:drone_port")
    f.mbox(0, 2, 4, 0, DRONE_PAD_Y - 2, 4, AIR)
    ctl(f, 1, 1, 0)
    item_port(f, 0, 8, "north", "item_in", DRONE_TERMINAL)
    item_port(f, -4, 4, "west", "item_in", DRONE_TERMINAL)
    item_port(f, 4, 4, "east", "item_out", DRONE_TERMINAL)
    f.box(-4, 0, 0, 4, 0, 8, PAD)
    for x in (-4, 4):
        for z in (0, 8):
            f.box(x, 1, z, x, DRONE_PAD_Y - 2, z, DARK)
    f.walls(1, 1, DARK, DARK, -2, 2, 2, 6)
    f.walls(2, 2, DARK, AIR, -2, 2, 2, 6)
    f.box(-4, DRONE_PAD_Y - 1, 0, 4, DRONE_PAD_Y - 1, 8, LIGHT)
    f.box(-1, DRONE_PAD_Y - 1, 3, 1, DRONE_PAD_Y - 1, 5, ORANGE)
    return f.done()


def equipment_workshop():
    # 3 wide, 2 deep, 4 high: a bench; the controller is its terminal, in the middle of the front, and opens the Workshop
    f = Frame(3, 2)
    f.m(0, 1, 0, CONTROLLER)
    f.box(-1, 0, 0, 1, 0, 1, PAD)
    f.set(-1, 1, 0, DARK)
    f.set(1, 1, 0, DARK)
    f.box(-1, 1, 1, 1, 2, 1, DARK)
    f.box(-1, 3, 0, 1, 3, 1, ORANGE)
    return f.done()


def awesome_sink():
    # 5 by 5, 8 high: an open hopper over the shredder's space; anything in at the back
    f = Frame(5, 5)
    ctl(f, 1, 1, 0)
    item_port(f, 0, 4, "north", "item_in", (1, 1, 0))
    f.mbox(-1, 2, 1, 1, 5, 3, AIR)
    shell(f, 4)
    f.box(-2, 6, 0, 2, 6, 4, ORANGE)
    f.box(-1, 6, 1, 1, 6, 3, AIR)
    f.m(2, 7, 4, CONNECTOR)
    return f.done()


def awesome_shop():
    # 3 by 3, 4 high: a kiosk; the controller is its counter and opens the Shop
    f = Frame(3, 3)
    f.m(0, 1, 0, CONTROLLER)
    f.mbox(0, 1, 1, 0, 2, 1, AIR)
    shell(f, 2)
    return f.done()


def radar_tower():
    # 3 by 3, 10 high: four Steel Casing legs up to an orange platform, the connector on top
    f = Frame(3, 3)
    f.m(0, 1, 0, CONTROLLER)
    f.box(-1, 0, 0, 1, 0, 2, PAD)
    for x in (-1, 1):
        for z in (0, 2):
            f.box(x, 1, z, x, 7, z, DARK)
    f.box(-1, 8, 0, 1, 8, 2, ORANGE)
    f.m(0, 9, 1, CONNECTOR)
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
         "miner_mk1": lambda: miner(1), "miner_mk2": lambda: miner(2), "miner_mk3": lambda: miner(3),
         "portable_miner": portable_miner,
         "storage_container": lambda: storage(1), "industrial_storage_container": lambda: storage(2),
         "power_pole_mk1": lambda: pole(1), "power_pole_mk2": lambda: pole(2), "power_pole_mk3": lambda: pole(3),
         "power_tower": power_tower, "power_storage": power_storage,
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
