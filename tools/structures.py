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


# ---- the Build Gun's buildings, in the look of Polartt's Satisfactory-in-Minecraft builds (the Build Gun and Buildings
# tab's reference screenshots): flat orange, dark grey framed panels (Create's industrial iron) with light grey framed ones
# (iron blocks), framed glass to see the Create machines inside, yellow and black hazard stripes, and a light grey pad with
# hazard corners under each building. Belts and pipes attach at the ports, at ground level: outputs on the front, inputs
# on the back, as Satisfactory's machines have them.
ORANGE, DARK, LIGHT, GLASS = "minecraft:orange_concrete", "create:industrial_iron_block", "minecraft:iron_block", "create:framed_glass"
YELLOW, BLACK, PAD = "minecraft:yellow_concrete", "minecraft:black_concrete", "minecraft:light_gray_concrete"
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


def ladder(facing):
    return f"create:copper_ladder[facing={facing},waterlogged=false]"


class Frame:
    """A template drawn in the Build Gun frame: x to the right of the middle column, y up from the bottom row, z from the
    front row (where the player stands) away. Directions in block states are as built facing north: south is the front,
    east is to the player's right."""

    def __init__(self, width, depth, even=False):
        # a building is an odd number of blocks wide, so it has a middle column; the Foundations tab's 8-wide pieces
        # (even=True) have theirs just right of the middle
        assert width % 2 == 1 or even, "a building is an odd number of blocks wide, so it has a middle column"
        self.w, self.d = width, depth
        self.cells, self.nbt = {}, {}

    def file(self, x, y, z):
        return (x + self.w // 2, y, self.d - 1 - z)

    def set(self, x, y, z, spec):
        self.cells[self.file(x, y, z)] = spec

    def get(self, x, y, z):
        return self.cells.get(self.file(x, y, z))

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

    # ---- the look
    def pad(self, x0=None, x1=None, z0=0, z1=None, y=0):
        """The floor pad: light grey, with a yellow and black hazard corner at each corner."""
        x0 = -(self.w // 2) if x0 is None else x0
        x1 = self.w // 2 if x1 is None else x1
        z1 = self.d - 1 if z1 is None else z1
        self.box(x0, y, z0, x1, y, z1, PAD)
        for cx, sx in ((x0, 1), (x1, -1)):
            for cz, sz in ((z0, 1), (z1, -1)):
                self.set(cx, y, cz, YELLOW)
                self.set(cx + sx, y, cz, BLACK)
                self.set(cx, y, cz + sz, BLACK)

    def hazard_x(self, x0, x1, y, z):
        """Hazard stripes along a row (yellow and black blocks in turn)."""
        for x in range(x0, x1 + 1):
            self.set(x, y, z, YELLOW if (x - x0) % 2 == 0 else BLACK)

    def hazard_z(self, x, y, z0, z1):
        for z in range(z0, z1 + 1):
            self.set(x, y, z, YELLOW if (z - z0) % 2 == 0 else BLACK)

    def checker(self, x0, z0, x1, z1, y, a=LIGHT, b=DARK):
        """A roof of light and dark panels in a checkerboard, as on Polartt's Constructor."""
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                self.set(x, y, z, a if (x + z) % 2 == 0 else b)

    def hall(self, x0, z0, x1, z1, y0, y1, side=ORANGE, corner=DARK, sill=DARK):
        """A machine hall: a dark sill row, walls of `side` with dark corner pillars, the inside air."""
        self.walls(y0, y0, sill, sill, x0, x1, z0, z1)
        self.walls(y0 + 1, y1, corner, side, x0, x1, z0, z1)

    def window_front(self, z, x0, x1, y0, y1, spec=GLASS):
        self.box(x0, y0, z, x1, y1, z, spec)

    def window_side(self, x, z0, z1, y0, y1, spec=GLASS):
        self.box(x, y0, z0, x, y1, z1, spec)

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
    # 7 wide, 9 deep, 9 high (Satisfactory's 8 by 10): on its pad, a hall of orange with dark corners and a big window
    # front and back, a checkered roof and an orange hood. Inside, a Mechanical Press over a Basin, turned by the motor
    # beside it; input at the back, output at the front, both on the Basin. The controller's panel is right of the window.
    f = Frame(7, 9)
    f.pad()
    f.hall(-2, 1, 2, 7, 1, 5)
    f.window_front(1, -1, 1, 2, 4)
    f.window_front(7, -1, 1, 2, 4)
    f.window_side(-2, 3, 5, 3, 4)
    f.window_side(2, 3, 5, 3, 4)
    f.hazard_x(-2, 2, 1, 1)
    f.checker(-2, 1, 2, 7, 6)
    f.box(-1, 7, 3, 1, 7, 5, ORANGE)
    f.set(-2, 7, 1, DARK)
    f.set(2, 7, 1, DARK)
    f.set(0, 2, 4, AIR)
    f.set(0, 1, 4, BASIN)
    f.set(0, 3, 4, "create:mechanical_press[facing=west]")
    f.set(1, 3, 4, MOTOR.format("west"))
    f.set(1, 1, 1, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 4))
    f.port(0, 0, 8, "north", "item_in", (0, 1, 4))
    f.set(0, 8, 4, CONNECTOR)
    return f.done()


def smelter():
    # 7 wide, 9 deep, 11 high (Satisfactory's 6 by 9 with its stack): a tall dark furnace hall with an orange band and a
    # glass front to see the lava, a chimney at the back. The motor in the left wall turns an Encased Fan that blows through
    # a lava cell onto a Depot (Create's bulk blasting); the controller feeds the Depot and takes the ingots off, and the
    # ports work on the controller.
    f = Frame(7, 9)
    f.pad()
    f.hall(-2, 1, 2, 7, 1, 6, side=DARK, corner=DARK)
    f.walls(4, 4, ORANGE, ORANGE, -2, 2, 1, 7)
    f.window_front(1, -1, 1, 2, 3)
    f.window_side(-2, 3, 5, 2, 3)
    f.window_side(2, 3, 5, 2, 3)
    f.checker(-2, 1, 2, 7, 7)
    f.drum(0, 6, 8, 10, 1, DARK, band=ORANGE, every=2)
    f.set(0, 11, 6, LIGHT)
    f.set(-2, 1, 4, MOTOR.format("east"))
    f.set(-1, 1, 4, "create:encased_fan[facing=east]")
    f.set(0, 1, 4, "minecraft:lava")
    f.set(1, 1, 4, DEPOT)
    # the lava is closed in on every side but the fan's and the Depot's
    for at in ((0, 2, 4), (0, 1, 3), (0, 1, 5)):
        f.set(*at, DARK)
    f.set(1, 1, 1, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(0, 0, 8, "north", "item_in", (1, 1, 1))
    f.set(-1, 8, 3, CONNECTOR)
    return f.done()


def miner(height, side, cap, extension=False):
    # 5 wide, 5 deep (it has to fit over a node's patch): four dark legs round the node with hazard feet, a deck over it with
    # the Drill pointing down at the node, the motor on the Drill and a tower round them with a ladder up its side; a chute
    # down to the output port at the front. The node's own cell is left out.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, YELLOW if (x + z) % 4 == 2 else BLACK)
    f.void(0, 0, 2)
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.hazard_x(-2, 2, 1, 0)
    f.set(0, 1, 2, "create:mechanical_drill[facing=down,waterlogged=false]")
    f.set(1, 1, 0, CONTROLLER)
    f.walls(2, height - 2, DARK, side, -1, 1, 1, 3)
    f.window_front(1, 0, 0, 3, height - 3)
    f.set(0, 2, 2, MOTOR.format("down"))
    if extension:
        # Miner Mk.3's 45 MW is more than one motor gives: an extension behind it doubles it
        f.set(0, 3, 2, EXTENSION.format("down"))
    f.set(0, 2, 1, "create:chute[facing=down,shape=normal,waterlogged=false]")
    f.box(-1, height - 1, 1, 1, height - 1, 3, cap)
    f.box(-2, height - 1, 2, 2, height - 1, 2, cap)
    for y in range(2, height - 1):
        f.set(2, y, 2, ladder("east"))
    f.set(0, height, 2, CONNECTOR)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 0))
    return f.done()


def portable_miner():
    # 3 by 3, 3 high: a small orange drill rig with no power on four dark legs over the node; the ore drops on the ground
    # in front
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, AIR)
    for x in (-1, 1):
        for z in (0, 2):
            f.set(x, 0, z, DARK)
    f.void(0, 0, 1)
    f.box(-1, 1, 0, 1, 1, 2, ORANGE)
    f.hazard_x(-1, 1, 1, 0)
    f.set(0, 1, 1, "create:mechanical_drill[facing=down,waterlogged=false]")
    f.box(-1, 2, 0, 1, 2, 2, AIR)
    f.set(0, 2, 1, CONTROLLER)
    return f.done()


def storage(depth, vaults):
    # 3 wide: Item Vaults along the middle in a light panelled box with dark corners and an orange lid; output at the
    # front and input at the back, both on the vault
    f = Frame(3, depth)
    f.box(-1, 0, 0, 1, 0, depth - 1, DARK)
    f.walls(1, 1, DARK, LIGHT)
    f.box(-1, 2, 0, 1, 2, depth - 1, ORANGE)
    f.hazard_x(-1, 1, 0, 0)
    for z in range(2, 2 + vaults):
        f.set(0, 1, z, "create:item_vault[axis=z,large=false]")
    f.set(0, 1, 0, GLASS)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 2))
    f.port(0, 0, depth - 1, "north", "item_in", (0, 1, 2))
    return f.done()


# ------------------------------------------------------------------------------------------------ work package 6: power
TURBINE = "siftec:turbine[facing={}]"
COIL = "create_new_age:generator_coil[axis=x]"
# New Age's own reactor casing and glass draw as missing textures in the Fabric 26.3 port (its connected-texture models),
# so the plant is built from Create's and vanilla blocks until that is fixed
R_GLASS = GLASS


def pole(height, column, band):
    # one block wide: the controller at its foot, a column with a coloured band, the connector on top
    f = Frame(1, 1)
    f.set(0, 0, 0, CONTROLLER)
    for y in range(1, height - 1):
        f.set(0, y, 0, band if y == height - 2 else column)
    f.set(0, height - 1, 0, CONNECTOR)
    return f.done()


def power_tower():
    # 3 by 3, 13 high: four dark legs and a core up to an orange platform with hazard edges, the connector on top
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.set(0, 0, 0, CONTROLLER)
    for x in (-1, 1):
        for z in (0, 2):
            f.box(x, 1, z, x, 3, z, DARK)
    f.box(0, 1, 1, 0, 10, 1, DARK)
    f.set(0, 5, 1, ORANGE)
    f.set(0, 8, 1, ORANGE)
    f.box(-1, 11, 0, 1, 11, 2, ORANGE)
    f.hazard_x(-1, 1, 11, 0)
    f.set(0, 12, 1, CONNECTOR)
    return f.done()


def power_storage():
    # 3 by 3, 6 high: light panels and glass round a core of New Age's overcharged iron coils, orange lid; the connector
    # on the roof
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.walls(1, 3, DARK, GLASS)
    f.box(0, 1, 1, 0, 3, 1, "create_new_age:overcharged_iron_wire_block")
    f.box(-1, 4, 0, 1, 4, 2, ORANGE)
    f.hazard_x(-1, 1, 0, 0)
    f.set(1, 1, 0, CONTROLLER)
    f.set(0, 5, 1, CONNECTOR)
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
VENT = "create_new_age:reactor_heat_vent[facing=down]"
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
    `filler` (no magnet)."""
    f.set(x, y, z, COIL_A.format(axis))
    for k, at in enumerate(ring(axis, x, y, z)):
        f.set(*at, magnets[k] if k < len(magnets) else filler)


def generator_box(f, y1, y2, side=ORANGE):
    """The house: a dark floor with a hazard front, walls of `side` with dark corners from y1 to y2, the inside air."""
    f.box(-(f.w // 2), 0, 0, f.w // 2, 0, f.d - 1, DARK)
    f.hazard_x(-(f.w // 2), f.w // 2, 0, 0)
    f.walls(1, y2, DARK, side)


def biomass_burner():
    # 5 wide, 6 deep, 10 high: an orange house with glass sides. At the back a Blaze Burner under a four-high Fluid Tank
    # (a boiler of size 4: one heat level), its Steam Engine on the tank's front driving a shaft that turns one generator
    # coil with four netherite magnets (96 SU a turn at 64 RPM: 6,144 SU, 30 MW) and the carbon brushes in the left wall.
    # The burner is fed from the fuel port at the back; the boiler is a closed loop (Satisfactory's burner takes no water).
    f = Frame(5, 6)
    generator_box(f, 1, 8)
    f.window_side(2, 1, 3, 2, 4)
    f.window_front(0, 0, 1, 2, 3)
    f.checker(-2, 0, 2, 5, 9)
    f.set(1, 1, 5, BURNER)
    f.box(1, 2, 5, 1, 5, 5, TANK)
    f.set(1, 5, 4, ENGINE.format("south"))
    f.set(1, 5, 3, AIR)
    f.set(1, 5, 2, POWERED_SHAFT.format("x"))
    f.set(0, 5, 2, SHAFT_A.format("x"))
    coil(f, -1, 5, 2, "x", [NETHERITE] * 4)
    f.set(-2, 5, 2, BRUSHES.format("east"))
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 5, "north", "item_in", (1, 1, 0))
    f.set(0, 10, 2, CONNECTOR)
    return f.done()


def coal_generator():
    # 7 wide, 8 deep, 10 high: an orange boiler house with glass sides and two dark smokestacks. At the back a 2 by 2 by 2
    # Fluid Tank (size 8: two heat levels) over two Blaze Burners, two Steam Engines on its front driving one shaft that turns
    # a generator coil with ten netherite magnets (240 SU a turn at 64 RPM: 15,360 SU, 75 MW), the carbon brushes beside
    # it. Coal goes into the burners from the item port; water comes in at the fluid port into the intake tank, from which
    # the boiler drinks Satisfactory's 45 m3 a minute at full load.
    f = Frame(7, 8)
    generator_box(f, 1, 8)
    f.window_side(-3, 2, 5, 3, 6)
    f.window_side(3, 2, 5, 3, 6)
    f.window_front(0, -1, 1, 2, 3)
    f.checker(-3, 0, 3, 7, 9)
    f.box(-3, 10, 7, -3, 11, 7, DARK)
    f.box(3, 10, 7, 3, 11, 7, DARK)
    f.box(1, 1, 5, 2, 2, 6, DARK)
    f.set(1, 3, 5, BURNER)
    f.set(2, 3, 5, BURNER)
    f.set(1, 3, 6, DARK)
    f.set(2, 3, 6, DARK)
    f.box(1, 4, 5, 2, 5, 6, TANK)
    for x in (1, 2):
        f.set(x, 5, 4, ENGINE.format("south"))
        f.set(x, 5, 3, AIR)
        f.set(x, 5, 2, POWERED_SHAFT.format("x"))
    f.set(0, 5, 2, SHAFT_A.format("x"))
    coil(f, -1, 5, 2, "x", [NETHERITE] * 10)
    f.set(-2, 5, 2, BRUSHES.format("east"))
    f.set(0, 1, 6, TANK)
    f.set(1, 1, 0, CONTROLLER)
    f.port(-1, 0, 7, "north", "item_in", (1, 1, 0))
    f.port(0, 0, 7, "north", "fluid_in", (0, 1, 6))
    f.set(0, 10, 2, CONNECTOR)
    return f.done()


def fuel_generator():
    # 5 wide, 6 deep, 9 high: a dark engine house with an orange band and glass. Inside, Diesel Generators' engine turns a
    # generator coil at 256 RPM; its eight netherite magnets and one fluxuated magnetite take 200 SU a turn (51,200 SU,
    # 250 MW), and the carbon brushes beside it give that to the grid. Fuel goes straight into the engine at the fuel port.
    f = Frame(5, 6)
    generator_box(f, 1, 7, side=LIGHT)
    f.walls(3, 3, ORANGE, ORANGE)
    f.window_front(0, -1, 1, 1, 2)
    f.window_side(-2, 1, 4, 5, 6)
    f.window_side(2, 1, 4, 5, 6)
    f.box(-2, 8, 0, 2, 8, 5, ORANGE)
    f.set(1, 4, 2, DIESEL_ENGINE.format("west"))
    coil(f, 0, 4, 2, "x", [NETHERITE] * 8 + [FLUXUATED])
    f.set(-1, 4, 2, BRUSHES.format("east"))
    f.set(1, 1, 0, CONTROLLER)
    f.port(1, 0, 5, "north", "fluid_in", (1, 4, 2))
    f.set(0, 9, 2, CONNECTOR)
    return f.done()


def geothermal_generator():
    # 5 wide, 5 deep, 10 high, on four legs over a sulfur geyser: the vent cell under the middle is left as air, so the
    # geyser breathes. Inside an orange house the geyser's turbine turns two generator coils with twenty netherite magnets
    # (480 SU a turn) at the speed the geyser gives: 256 RPM at a pure geyser's peak (600 MW), so the power swings with the
    # geyser as in Satisfactory. The acid tank and its port are at the back.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, DARK)
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.hazard_x(-2, 2, 1, 0)
    f.walls(2, 8, DARK, ORANGE)
    f.window_side(-2, 1, 3, 2, 2)
    f.window_side(2, 1, 3, 2, 2)
    f.checker(-2, 0, 2, 4, 9)
    f.set(-1, 2, 2, TANK)
    f.set(1, 5, 2, TURBINE.format("west"))
    coil(f, 0, 5, 2, "x", [NETHERITE] * 12)
    coil(f, -1, 5, 2, "x", [NETHERITE] * 8)
    f.set(-2, 5, 2, BRUSHES.format("east"))
    f.set(1, 1, 0, CONTROLLER)
    f.port(-1, 2, 4, "north", "fluid_out", (-1, 2, 2))
    f.set(0, 10, 2, CONNECTOR)
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
    f = Frame(21, 11)
    f.pad()
    f.walls(1, 14, DARK, DARK)
    f.walls(5, 5, ORANGE, ORANGE)
    f.walls(10, 10, ORANGE, ORANGE)
    f.window_front(0, -8, -2, 2, 4)
    f.window_front(0, 2, 8, 2, 4)
    f.window_side(-10, 2, 8, 6, 9)
    f.window_side(10, 6, 8, 6, 9)
    f.hazard_x(-10, 10, 1, 0)
    f.checker(-10, 0, 10, 10, 15)
    for bx, bz in NUCLEAR_BOILERS:
        f.box(bx, 4, bz, bx + 2, 11, bz + 2, TANK)
        f.box(bx, 3, bz, bx + 2, 3, bz + 2, HEATER)
        f.box(bx, 2, bz, bx + 2, 2, bz + 2, VENT)
        f.box(bx, 1, bz, bx + 2, 1, bz + 2, DARK)
        # seven rods: two rows of three and one, each row fed along its line by a fuel acceptor outside the boiler
        west = bx < 0
        ax = bx - 1 if west else bx + 3
        for k, z in enumerate(range(bz, bz + 3)):
            f.set(ax, 1, z, ACCEPTOR.format("east" if west else "west"))
            n = 3 if k < 2 else 1
            for i in range(n):
                f.set(bx + i if west else bx + 2 - i, 1, z, ROD)
        for y in NUCLEAR_ENGINE_ROWS:
            for x in range(bx, bx + 3):
                f.set(x, y, bz - 1, ENGINE.format("south"))
                f.set(x, y, bz - 2, AIR)
                f.set(x, y, NUCLEAR_SHAFT_Z, POWERED_SHAFT.format("x"))
    for y in NUCLEAR_ENGINE_ROWS:
        for x in range(-5, 6):
            f.set(x, y, NUCLEAR_SHAFT_Z, SHAFT_A.format("x") if x != 0 else COG_A.format("x"))
    # the column of cogwheels joins the six rows; then 64 -> 128 -> 256 RPM through two large and small cogwheel steps
    f.set(0, 10, 4, COG_A.format("x"))
    f.set(1, 10, 4, LARGE_COG.format("x"))
    f.set(1, 11, 3, COG_A.format("x"))
    f.set(2, 11, 3, LARGE_COG.format("x"))
    f.set(2, 12, 2, COG_A.format("x"))
    for k, x in enumerate(range(3, 10)):
        coil(f, x, 12, 2, "x", [NETHERITE] * 12 if k < 6 else [NETHERITE] * 11 + [FLUXUATED])
    f.set(10, 12, 2, BRUSHES.format("west"))
    f.set(-2, 1, 9, TANK)
    f.set(1, 1, 1, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(0, 0, 10, "north", "item_in", (1, 1, 1))
    f.port(-2, 0, 10, "north", "fluid_in", (-2, 1, 9))
    f.set(0, 16, 5, CONNECTOR)
    return f.done()

def alien_power_augmenter():
    # 5 by 5, 7 high: brass and glass round a glowing core, orange lid; Alien Power Matrices in at the back
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.hazard_x(-2, 2, 0, 0)
    f.walls(1, 4, BRASS, GLASS)
    f.box(-2, 5, 0, 2, 5, 4, ORANGE)
    f.set(0, 1, 2, "minecraft:sea_lantern")
    f.set(0, 2, 2, "minecraft:end_rod[facing=up]")
    f.set(0, 3, 2, "minecraft:sea_lantern")
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 4, "north", "item_in", (1, 1, 0))
    f.set(0, 6, 2, CONNECTOR)
    return f.done()


# ------------------------------------------------------------------------------------------------ work package 9

def assembler():
    # 9 wide, 11 deep, 9 high (Satisfactory's 10 by 15): a long low orange hall with a raised glass hood over the Mixer and
    # dark arms either side. Inside, a Mechanical Mixer over a Basin, turned by the motor on top; two inputs at the back
    # and the output at the front, all on the Basin.
    f = Frame(9, 11)
    f.pad()
    f.hall(-3, 1, 3, 9, 1, 4)
    f.window_front(1, -1, 1, 2, 3)
    f.window_side(-3, 3, 7, 3, 3)
    f.window_side(3, 3, 7, 3, 3)
    f.hazard_x(-3, 3, 1, 1)
    f.checker(-3, 1, 3, 9, 5)
    # the hood over the Mixer: glass sides, a dark roof
    f.walls(5, 6, DARK, GLASS, -1, 1, 3, 7)
    f.box(-1, 7, 3, 1, 7, 7, ORANGE)
    for side in (-3, 3):
        f.box(side, 5, 2, side, 5, 8, DARK)
    f.set(0, 1, 5, BASIN)
    f.set(0, 2, 5, AIR)
    mixer(f, 0, 3, 5)
    f.set(1, 1, 1, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 5))
    f.port(-1, 0, 10, "north", "item_in", (0, 1, 5))
    f.port(1, 0, 10, "north", "item_in", (0, 1, 5))
    f.set(0, 8, 5, CONNECTOR)
    return f.done()


def mixer(f, x, y, z):
    """A Mechanical Mixer has no shaft of its own: it turns as a cog, so a cogwheel beside it (to its right) is turned by
    the motor on top of the cogwheel."""
    f.set(x, y, z, MIXER)
    f.set(x + 1, y, z, COG)
    f.set(x + 1, y + 1, z, MOTOR.format("down"))


def heated_mixer(f, x, z, basin=BASIN):
    """A heated Mixer: a Blaze Burner (kept lit by the building while it has power), the Basin on it, the Mixer two above
    and its cogwheel and motor beside it. Returns the Basin's cell."""
    f.set(x, 1, z, BURNER)
    f.set(x, 2, z, basin)
    f.set(x, 3, z, AIR)
    mixer(f, x, 4, z)
    return (x, 2, z)


def foundry():
    # 9 wide, 9 deep, 11 high (Satisfactory's 10 by 9): two round furnace towers either side of a glass-fronted hall with
    # the heated Mixer; two inputs at the back, the output at the front
    f = Frame(9, 9)
    f.pad()
    f.hall(-2, 1, 2, 7, 1, 6, side=ORANGE)
    f.window_front(1, -1, 1, 2, 5)
    f.window_front(7, -1, 1, 3, 4)
    f.checker(-2, 1, 2, 7, 7)
    f.hazard_x(-2, 2, 1, 1)
    for cx in (-3, 3):
        f.drum(cx, 4, 1, 8, 1, DARK, band=ORANGE, every=3)
        f.set(cx, 9, 4, LIGHT)
        f.set(cx, 10, 4, DARK)
    basin = heated_mixer(f, 0, 4)
    f.set(1, 1, 1, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", basin)
    f.port(-1, 0, 8, "north", "item_in", basin)
    f.port(1, 0, 8, "north", "item_in", basin)
    f.set(0, 8, 4, CONNECTOR)
    return f.done()


def refinery():
    # 9 wide, 13 deep, 16 high (Satisfactory's 10 by 20): a glass-fronted hall at the front with the heated Mixer, a tall
    # round distillation tower behind it, light with orange bands and a ladder. Item and fluid in at the back, item and
    # fluid out at the front, all on the Basin.
    f = Frame(9, 13)
    f.pad()
    f.hall(-3, 1, 3, 7, 1, 6)
    f.window_front(1, -1, 1, 2, 5)
    f.window_side(-3, 3, 5, 3, 5)
    f.window_side(3, 3, 5, 3, 5)
    f.checker(-3, 1, 3, 7, 7)
    f.hazard_x(-3, 3, 1, 1)
    f.drum(0, 10, 1, 14, 2, LIGHT, band=ORANGE, every=4)
    f.drum(0, 10, 15, 15, 1, DARK)
    for y in range(1, 15):
        f.set(3, y, 10, ladder("east"))
    basin = heated_mixer(f, 0, 4)
    f.set(1, 1, 1, CONTROLLER)
    f.port(-1, 0, 0, "south", "item_out", basin)
    f.port(1, 0, 0, "south", "fluid_out", basin)
    f.port(-3, 0, 12, "north", "item_in", basin)
    f.port(3, 0, 12, "north", "fluid_in", basin)
    f.set(0, 8, 4, CONNECTOR)
    return f.done()


def blender():
    # 11 wide, 11 deep, 11 high (Satisfactory's 18 by 16): a wide orange hall with the heated Mixer over a Brass Basin,
    # and two big round tanks on its roof. Two fluids and two items in at the back, a fluid and an item out at the front.
    f = Frame(11, 11)
    f.pad()
    f.hall(-4, 1, 4, 9, 1, 6)
    f.window_front(1, -2, 2, 2, 5)
    f.window_side(-4, 3, 7, 3, 5)
    f.window_side(4, 3, 7, 3, 5)
    f.checker(-4, 1, 4, 9, 7)
    f.hazard_x(-4, 4, 1, 1)
    for cx in (-2, 2):
        f.drum(cx, 6, 8, 10, 1, LIGHT, band=DARK, every=3)
    basin = heated_mixer(f, 0, 5, BRASS_BASIN)
    f.set(1, 1, 1, CONTROLLER)
    f.port(-1, 0, 0, "south", "item_out", basin)
    f.port(1, 0, 0, "south", "fluid_out", basin)
    f.port(-3, 0, 10, "north", "item_in", basin)
    f.port(-1, 0, 10, "north", "item_in", basin)
    f.port(1, 0, 10, "north", "fluid_in", basin)
    f.port(3, 0, 10, "north", "fluid_in", basin)
    f.set(0, 8, 3, CONNECTOR)
    return f.done()


def assembly_line(f, x, z_basin, stations, motor_z, basin=BRASS_BASIN):
    """The Manufacturer's way: a Mixer over a Brass Basin at the back makes the Core, then a row of Depots toward the
    front, each under a Deployer (or a Spout, "spout"), and an Item Drain ("drain") last. The Deployers' shafts run front to
    back through shafts between them, turned by one motor at the front end. Returns the Basin's cell."""
    f.set(x, 1, z_basin, basin)
    f.set(x, 2, z_basin, AIR)
    mixer(f, x, 3, z_basin)
    z = z_basin - 2
    deployers = []
    for kind in stations:
        if kind == "drain":
            f.set(x, 1, z, DRAIN)
        else:
            f.set(x, 1, z, DEPOT)
            f.set(x, 2, z, AIR)
            f.set(x, 3, z, SPOUT if kind == "spout" else DEPLOYER)
            if kind == "deployer":
                deployers.append(z)
        z -= 2
    if deployers:
        for zz in range(min(deployers), max(deployers)):
            if zz not in deployers:
                f.set(x, 3, zz, SHAFT_Z)
        f.set(x, 3, min(deployers) - 1, MOTOR.format("north"))
    return (x, 1, z_basin)


def manufacturer():
    # 11 wide, 15 deep, 10 high (Satisfactory's 18 by 20): a long hall with a sloped glass roof over the line, orange
    # walls with dark pillars. At the back the Mixer over the Brass Basin makes the Core; four Depots under Deployers carry
    # it to the front, each Deployer adding the last of one input. Four inputs at the back, the output at the front.
    f = Frame(11, 15)
    f.pad()
    f.hall(-4, 1, 4, 13, 1, 6)
    for z in range(3, 13, 3):
        f.box(-4, 2, z, -4, 6, z, DARK)
        f.box(4, 2, z, 4, 6, z, DARK)
    f.window_front(1, -2, 2, 2, 5)
    f.window_side(-4, 2, 12, 3, 4)
    f.window_side(4, 2, 12, 3, 4)
    f.hazard_x(-4, 4, 1, 1)
    # the glass roof: a ridge over the line, sloping down to the walls
    f.box(-4, 7, 1, 4, 7, 13, DARK)
    f.box(-3, 7, 2, 3, 7, 12, GLASS)
    f.box(-1, 8, 2, 1, 8, 12, GLASS)
    f.box(-1, 8, 1, 1, 8, 1, ORANGE)
    f.box(-1, 8, 13, 1, 8, 13, ORANGE)
    basin = assembly_line(f, 0, 11, ["deployer", "deployer", "deployer", "deployer"], 0)
    f.set(2, 1, 1, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (2, 1, 1))
    for x in (-3, -1, 1, 3):
        f.port(x, 0, 14, "north", "item_in", basin)
    f.set(0, 9, 7, CONNECTOR)
    return f.done()


def quantum_encoder():
    # 11 wide, 19 deep, 12 high (Satisfactory's long encoder with its arches): a long dark hall with glass sides under
    # orange arches. At the back the Mixer over the Brass Basin makes the Core; the line toward the front is a Spout that
    # fills it with Excited Photonic Matter, four Deployers, and an Item Drain that takes the Dark Matter Residue off.
    # Three item inputs and the photonic matter at the back, the item and the residue out at the front.
    f = Frame(11, 19)
    f.pad()
    f.hall(-4, 1, 4, 17, 1, 6, side=DARK, corner=DARK)
    f.window_side(-4, 2, 16, 3, 5)
    f.window_side(4, 2, 16, 3, 5)
    f.window_front(1, -2, 2, 2, 5)
    f.hazard_x(-4, 4, 1, 1)
    f.checker(-4, 1, 4, 17, 7)
    for z in (3, 9, 15):
        f.box(-5, 1, z, -5, 9, z, ORANGE)
        f.box(5, 1, z, 5, 9, z, ORANGE)
        f.box(-5, 10, z, 5, 10, z, ORANGE)
    basin = assembly_line(f, 0, 15, ["spout", "deployer", "deployer", "deployer", "deployer", "drain"], 0)
    f.set(2, 1, 1, CONTROLLER)
    f.port(-1, 0, 0, "south", "item_out", (2, 1, 1))
    f.port(1, 0, 0, "south", "fluid_out", (0, 1, 3))
    for x in (-3, -1, 1):
        f.port(x, 0, 18, "north", "item_in", basin)
    f.port(3, 0, 18, "north", "fluid_in", (0, 3, 13))
    f.set(0, 8, 9, CONNECTOR)
    return f.done()


def packager():
    # 7 wide, 7 deep, 8 high (Satisfactory's 8 by 8): an orange box with a glass front and a rack of canisters on the
    # roof. Inside, a Spout over a Depot fills empty canisters and tanks, an Item Drain empties packaged fluids. Item and
    # fluid in at the back, item and fluid out at the front.
    f = Frame(7, 7)
    f.pad()
    f.hall(-2, 1, 2, 5, 1, 4)
    f.window_front(1, -1, 1, 2, 3)
    f.window_side(-2, 2, 4, 3, 3)
    f.window_side(2, 2, 4, 3, 3)
    f.hazard_x(-2, 2, 1, 1)
    f.checker(-2, 1, 2, 5, 5)
    f.box(-1, 6, 2, 1, 6, 4, DARK)
    f.set(-1, 7, 3, LIGHT)
    f.set(1, 7, 3, LIGHT)
    f.set(-1, 1, 3, DEPOT)
    f.set(-1, 2, 3, AIR)
    f.set(-1, 3, 3, SPOUT)
    f.set(1, 1, 3, DRAIN)
    f.set(0, 1, 3, AIR)
    f.set(1, 1, 1, CONTROLLER)
    f.port(-1, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(1, 0, 0, "south", "fluid_out", (1, 1, 3))
    f.port(-1, 0, 6, "north", "item_in", (1, 1, 1))
    f.port(1, 0, 6, "north", "fluid_in", (-1, 3, 3))
    f.set(0, 7, 3, CONNECTOR)
    return f.done()


def water_extractor():
    # 7 by 7, 8 high, standing in water: four dark pontoons, an orange deck with hazard edges, the pump over the open
    # intake in the middle (the water cell stays) turned by the motor above, and the water tank at the front by the port
    f = Frame(7, 7)
    f.box(-3, 0, 0, 3, 0, 6, "minecraft:structure_void")
    for x in (-3, 3):
        for z in (0, 6):
            f.box(x, 0, z, x, 0, z, DARK)
    f.box(-3, 1, 0, 3, 1, 6, ORANGE)
    f.hazard_x(-3, 3, 1, 0)
    f.hazard_x(-3, 3, 1, 6)
    f.box(-1, 1, 2, 1, 1, 4, DARK)
    f.set(0, 1, 3, "create:mechanical_pump[facing=down]")
    f.set(0, 2, 3, MOTOR.format("down"))
    f.walls(2, 4, DARK, GLASS, -1, 1, 2, 4)
    f.set(0, 2, 3, MOTOR.format("down"))
    f.box(-1, 5, 2, 1, 5, 4, ORANGE)
    f.set(0, 2, 1, TANK)
    f.box(-1, 2, 1, -1, 3, 1, DARK)
    f.box(1, 2, 1, 1, 3, 1, DARK)
    f.set(1, 2, 0, CONTROLLER)
    f.port(0, 1, 0, "south", "fluid_out", (0, 2, 1))
    f.set(0, 6, 3, CONNECTOR)
    return f.done()


def node_rig(f, top, cap):
    """Four dark legs round a node in the middle of a 5 by 5 footprint, a dark deck over it with hazard edges."""
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, DARK)
    f.void(0, 0, 2)
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.hazard_x(-2, 2, 1, 0)
    f.hazard_x(-2, 2, 1, 4)


def oil_extractor():
    # 5 by 5, 12 high, over an oil node (Satisfactory's derrick): a deck on legs, the pump over the well turned by the
    # motor above, an orange derrick tower of dark girders with a ladder, and the oil tank at the front by the port
    f = Frame(5, 5)
    node_rig(f, 11, ORANGE)
    f.set(0, 1, 2, "create:mechanical_pump[facing=down]")
    f.set(0, 2, 2, MOTOR.format("down"))
    f.walls(2, 3, DARK, ORANGE, -1, 1, 1, 3)
    f.set(0, 2, 2, MOTOR.format("down"))
    f.set(0, 3, 2, AIR)
    # the derrick: dark girder corners, orange bands, an open inside
    for y in range(4, 11):
        f.walls(y, y, DARK, ORANGE if y % 3 == 0 else GLASS, -1, 1, 1, 3)
    f.box(-1, 11, 1, 1, 11, 3, ORANGE)
    f.set(0, 12, 2, LIGHT)
    for y in range(2, 11):
        f.set(2, y, 2, ladder("east"))
    f.set(0, 1, 1, TANK)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "fluid_out", (0, 1, 1))
    f.set(0, 13, 2, CONNECTOR)
    return f.done()


def resource_well_pressurizer():
    # 5 by 5, 9 high, over the nitrogen well: a deck on legs and a big round light drum with orange and dark bands, the
    # powered pump over the well inside it
    f = Frame(5, 5)
    node_rig(f, 8, ORANGE)
    f.set(0, 1, 2, "create:mechanical_pump[facing=down]")
    f.drum(0, 2, 2, 7, 2, LIGHT, band=ORANGE, every=3)
    f.box(-1, 2, 1, 1, 6, 3, AIR)
    f.set(0, 2, 2, MOTOR.format("down"))
    f.window_front(0, -1, 1, 3, 4)
    f.drum(0, 2, 8, 8, 1, DARK)
    f.set(1, 1, 0, CONTROLLER)
    f.set(0, 9, 2, CONNECTOR)
    return f.done()


def resource_well_extractor():
    # 3 by 3, 4 high: a small dark cap with an orange band and a pump, its tank behind glass; nitrogen out at the front
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.hazard_x(-1, 1, 0, 2)
    f.walls(1, 2, DARK, ORANGE)
    f.set(0, 1, 1, TANK)
    f.set(0, 2, 1, "create:mechanical_pump[facing=down]")
    f.set(0, 2, 0, GLASS)
    f.box(-1, 3, 0, 1, 3, 2, LIGHT)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "fluid_out", (0, 1, 1))
    return f.done()


def particle_accelerator():
    # 13 wide, 21 deep, 10 high (Satisfactory's 24 by 38): a long oval track of light and dark panels with glass round an
    # orange console hall in the middle. Inside the hall, at the back, a Mixer over a Brass Basin packs a recipe's inputs
    # into its Core; the building drops the Core into the column over an Encased Fan, which blows it up between two
    # Crushing Wheels turning against each other, and what the wheels crush it into comes out over them. Two items and a
    # fluid in at the back (into the Basin), the item out at the front.
    f = Frame(13, 21)
    f.pad()
    # the track: an oval ring two blocks high
    for x in range(-6, 7):
        for z in range(1, 20):
            dx, dz = x / 6.0, (z - 10) / 9.0
            d = dx * dx + dz * dz
            if 0.72 <= d <= 1.02:
                f.set(x, 1, z, DARK)
                f.set(x, 2, z, GLASS if (x + z) % 3 else LIGHT)
                f.set(x, 3, z, DARK)
    f.hall(-3, 6, 3, 14, 1, 7)
    f.window_front(6, -2, 2, 3, 6)
    f.window_front(14, -1, 1, 3, 5)
    f.window_side(-3, 8, 12, 3, 6)
    f.window_side(3, 8, 12, 3, 6)
    f.checker(-3, 6, 3, 14, 8)
    f.box(-1, 9, 8, 1, 9, 12, ORANGE)
    f.hazard_x(-3, 3, 1, 6)
    # the Core maker at the back
    f.set(0, 1, 13, BRASS_BASIN)
    f.set(0, 2, 13, AIR)
    mixer(f, 0, 3, 13)
    # the crusher: the fan blows up through the column between the two wheels, which turn so the gap carries upward
    f.set(0, 0, 10, MOTOR.format("up"))
    f.set(0, 1, 10, "create:encased_fan[facing=up]")
    f.box(0, 2, 10, 0, 3, 10, AIR)
    f.set(-1, 4, 10, "create:crushing_wheel[axis=z]")
    f.set(1, 4, 10, "create:crushing_wheel[axis=z]")
    f.set(0, 4, 10, AIR)
    f.set(-1, 4, 9, MOTOR.format("north"))
    f.set(1, 4, 9, MOTOR.format("north"))
    # where the crushed goods come out, over the wheels, under a dark cap
    f.box(0, 5, 10, 0, 6, 10, AIR)
    f.set(0, 7, 10, DARK)
    f.set(1, 1, 6, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 6))
    f.port(-2, 0, 20, "north", "item_in", (0, 1, 13))
    f.port(0, 0, 20, "north", "item_in", (0, 1, 13))
    f.port(2, 0, 20, "north", "fluid_in", (0, 1, 13))
    f.set(0, 10, 10, CONNECTOR)
    return f.done()


def converter():
    # 9 wide, 9 deep, 11 high (Satisfactory's 16 by 16): a dark tower with orange corners and glass round a glowing core.
    # At the back a Mixer over a Brass Basin packs a recipe's inputs into its Core; on the Depot in front of it a Deployer
    # adds the last of a second input (or the Converter Cell a fluid is carried in), and an Encased Fan blowing through
    # soul fire (Create's haunting) turns it into what the recipe makes. A fluid comes out of its cell in the Item Drain
    # beside the Depot. Two items in at the back (into the Basin), the item and the fluid out at the front.
    f = Frame(9, 9)
    f.pad()
    f.hall(-3, 1, 3, 7, 1, 7, side=DARK, corner=ORANGE)
    f.window_front(1, -2, 2, 2, 3)
    f.window_side(-3, 3, 5, 3, 6)
    f.window_side(3, 3, 5, 3, 6)
    f.hazard_x(-3, 3, 1, 1)
    f.checker(-3, 1, 3, 7, 8)
    f.drum(0, 4, 9, 9, 1, ORANGE)
    f.set(0, 10, 4, "minecraft:amethyst_block")
    f.box(-1, 4, 3, 1, 6, 5, "minecraft:purple_stained_glass")
    f.set(0, 5, 4, "minecraft:amethyst_block")
    # the Core maker
    f.set(-1, 1, 6, BRASS_BASIN)
    f.set(-1, 2, 6, AIR)
    mixer(f, -1, 3, 6)
    # the haunting line: motor, fan, soul fire, the Depot under the Deployer, and the Item Drain behind the Depot
    f.set(-2, 1, 4, MOTOR.format("east"))
    f.set(-1, 1, 4, "create:encased_fan[facing=east]")
    f.set(0, 1, 4, "minecraft:soul_campfire[lit=true,signal_fire=false,waterlogged=false,facing=south]")
    f.set(1, 1, 4, DEPOT)
    f.set(1, 2, 4, AIR)
    f.set(1, 3, 4, DEPLOYER)
    f.set(1, 3, 3, MOTOR.format("north"))
    f.set(1, 1, 5, DRAIN)
    f.set(1, 1, 1, CONTROLLER)
    f.port(-1, 0, 0, "south", "item_out", (1, 1, 1))
    f.port(1, 0, 0, "south", "fluid_out", (1, 1, 5))
    f.port(-1, 0, 8, "north", "item_in", (-1, 1, 6))
    f.port(1, 0, 8, "north", "item_in", (-1, 1, 6))
    f.set(-2, 9, 4, CONNECTOR)
    return f.done()

# ------------------------------------------------------------------------------------------------ work package 10

def equipment_workshop():
    # 5 wide, 3 deep, 4 high (Satisfactory's bench, at the pack's scale): on its pad a long dark workbench with an orange
    # top, a light tool board behind it under a little orange roof, and lanterns on the board. The controller is the
    # bench's terminal, in the middle of the front; using it opens the Workshop.
    f = Frame(5, 3)
    f.pad()
    f.box(-2, 1, 0, 2, 1, 1, DARK)
    f.box(-2, 2, 0, 2, 2, 1, ORANGE)
    f.set(0, 1, 0, CONTROLLER)
    f.box(-2, 1, 2, 2, 3, 2, LIGHT)
    f.box(-2, 4, 1, 2, 4, 2, ORANGE)
    f.hazard_x(-2, 2, 0, 0)
    for x in (-1, 1):
        f.set(x, 3, 1, "minecraft:lantern[hanging=false,waterlogged=false]")
    return f.done()


def awesome_sink():
    # 5 wide, 5 deep, 9 high (the Sink's funnel of a tower): an orange body on dark legs, a glass band to see the magma
    # it burns things in, and a dark funnel on top. Items go in at the input port at the back, on the ground; the
    # controller in front opens the Sink's screen. 30 MW through the connector on the funnel's rim.
    f = Frame(5, 5)
    f.pad()
    f.walls(1, 1, DARK, DARK, -2, 2, 0, 4)
    f.hall(-2, 0, 2, 4, 1, 5, side=ORANGE, corner=DARK)
    f.window_front(0, -1, 1, 3, 4)
    f.window_side(-2, 1, 3, 3, 4)
    f.window_side(2, 1, 3, 3, 4)
    f.box(-1, 1, 1, 1, 1, 3, "minecraft:magma_block")
    f.box(-2, 6, 0, 2, 6, 4, DARK)
    f.walls(7, 8, DARK, ORANGE, -2, 2, 0, 4)
    f.box(-1, 6, 1, 1, 6, 3, "minecraft:magma_block")
    f.hazard_x(-2, 2, 6, 0)
    f.set(1, 2, 0, CONTROLLER)
    f.port(0, 0, 4, "north", "item_in", (1, 2, 0))
    f.set(2, 9, 4, CONNECTOR)
    return f.done()


def awesome_shop():
    # 3 wide, 3 deep, 5 high: a kiosk of light panels with an orange roof and a glass front; the controller is its counter
    # terminal. No power (as in 1.0).
    f = Frame(3, 3)
    f.pad()
    f.walls(1, 3, DARK, LIGHT)
    f.window_front(0, -1, 1, 2, 3)
    f.set(0, 1, 0, CONTROLLER)
    f.box(-1, 4, 0, 1, 4, 2, ORANGE)
    f.set(0, 3, 1, "minecraft:lantern[hanging=true,waterlogged=false]")
    return f.done()


def radar_tower():
    # 3 by 3, 17 high: a dark lattice mast on a pad with hazard edges, orange bands, and a dish of light panels and iron
    # bars at the top; the controller at its foot, the connector on the dish. 30 MW.
    f = Frame(3, 3)
    f.pad()
    f.set(0, 1, 0, CONTROLLER)
    for x in (-1, 1):
        for z in (0, 2):
            f.box(x, 1, z, x, 12, z, DARK)
    for y in (4, 8, 12):
        f.walls(y, y, ORANGE, ORANGE)
    for y in range(1, 13):
        if y not in (4, 8, 12):
            f.set(0, y, 1, "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]")
    f.set(0, 4, 1, DARK)
    f.set(0, 8, 1, DARK)
    f.set(0, 12, 1, DARK)
    f.box(-1, 13, 0, 1, 13, 2, LIGHT)
    f.box(-1, 14, 0, 1, 14, 0, LIGHT)
    f.box(-1, 15, 0, 1, 15, 0, "minecraft:iron_bars[east=true,north=false,south=false,waterlogged=false,west=true]")
    f.set(0, 14, 1, DARK)
    f.set(0, 15, 1, CONNECTOR)
    return f.done()


def truck_station():
    # work package 14. 5 wide, 5 deep, 4 high: an orange garage on a pad, its dock in front of it (the 5 by 5 just past
    # its front edge, where a vehicle on a route stops). Inside: two Item Vaults the vehicles load from or unload into
    # (item_in on its right side, item_out on its left) and a fuel tank they fill up from (fluid_in at the back). The
    # controller is the terminal in its front wall: using it switches between loading and unloading.
    f = Frame(5, 5)
    f.pad()
    f.hall(-2, 1, 2, 4, 1, 3, side=ORANGE, corner=DARK)
    f.box(-2, 4, 1, 2, 4, 4, DARK)
    f.hazard_x(-2, 2, 0, 0)
    f.window_front(1, -1, 1, 2, 3)
    f.set(0, 1, 1, CONTROLLER)
    f.set(-1, 1, 2, "create:item_vault[axis=z,large=false]")
    f.set(-1, 1, 3, "create:item_vault[axis=z,large=false]")
    f.set(1, 1, 3, TANK)
    f.port(2, 1, 2, "east", "item_in", (-1, 1, 2))
    f.port(-2, 1, 3, "west", "item_out", (-1, 1, 3))
    f.port(1, 1, 4, "north", "fluid_in", (1, 1, 3))
    return f.done()


# the Drone Port's landing spot (Build Gun frame): its drone takes off from and lands on the pad here, straight over the
# loading terminal (DRONE_TERMINAL), so the spot is the terminal's whatever way the port faces (DronePortBlockEntity)
DRONE_TERMINAL = (0, 1, 11)
DRONE_PAD_Y = 11


def drone_port():
    # Satisfactory's Drone Port at 1 m = 1 block: 23 by 23 (its 24 by 24), 15 high. A raised landing pad on four legs:
    # a light deck with a yellow and black hazard rim, an orange landing ring and a dark touchdown square in the middle,
    # lamps at its corners and a beacon mast at the back; a ladder up the front left leg. Underneath, the base: an orange
    # hall with dark corners and framed glass on its own pad, with the loading terminal (the Drone Port block, which keeps
    # the drone, its packages and fire charges) straight under the touchdown square, and the controller in its front wall.
    # Ports on the base's pad: packages in at the back, fire charges in on the left, arrivals out on the right.
    f = Frame(23, 23)
    lamp = "siftec:prefab_lamp"
    # the legs: 2 by 2 dark pillars at the corners with hazard feet, braced under the deck
    for lx, lz in ((-11, 0), (10, 0), (-11, 21), (10, 21)):
        f.box(lx, 0, lz, lx + 1, 9, lz + 1, DARK)
        for i, (x, z) in enumerate(((lx, lz), (lx + 1, lz), (lx + 1, lz + 1), (lx, lz + 1))):
            f.set(x, 0, z, YELLOW if i % 2 == 0 else BLACK)
        f.box(lx, 9, lz, lx + 1, 9, lz + 1, ORANGE)
    for x in range(-9, 10):
        f.set(x, 9, 0, DARK)
        f.set(x, 9, 22, DARK)
    for z in range(2, 21):
        f.set(-11, 9, z, DARK)
        f.set(11, 9, z, DARK)
    # the base's pad and hall
    f.pad(-6, 6, 4, 18)
    f.hall(-5, 5, 5, 17, 1, 6, side=ORANGE, corner=DARK)
    f.checker(-5, 5, 5, 17, 7)
    f.window_side(-5, 8, 14, 3, 5)
    f.window_side(5, 8, 14, 3, 5)
    f.window_front(5, -3, -2, 3, 5)
    f.window_front(5, 2, 3, 3, 5)
    f.window_front(17, -2, 2, 3, 5)
    # a doorway to the terminal, the controller beside it
    f.box(0, 1, 5, 0, 2, 5, AIR)
    f.set(1, 2, 5, CONTROLLER)
    # the base holds the deck up: dark columns from the hall's corners to the deck
    for x, z in ((-5, 5), (5, 5), (-5, 17), (5, 17)):
        f.box(x, 8, z, x, 9, z, DARK)
    # the loading terminal under the touchdown square
    f.set(*DRONE_TERMINAL, "siftec:drone_port")
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
    f.set(10, 14, 22, lamp)
    # a ladder up the front left leg and through the deck
    for y in range(1, 11):
        f.set(-9, y, 1, ladder("east"))
    f.port(0, 0, 18, "north", "item_in", DRONE_TERMINAL)
    f.port(-6, 0, 11, "west", "item_in", DRONE_TERMINAL)
    f.port(6, 0, 11, "east", "item_out", DRONE_TERMINAL)
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
         "power_pole_mk1": lambda: pole(5, DARK, ORANGE), "power_pole_mk2": lambda: pole(6, DARK, YELLOW),
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
         "foundation_1m": lambda: foundation(1), "foundation_2m": lambda: foundation(2), "foundation_4m": lambda: foundation(4),
         "wall_4m": wall, "roof_4m": roof,
         "billboard_small": lambda: billboard(8, 4, (-3, 2)), "billboard_large": lambda: billboard(16, 8, (-6, 5))}

if __name__ == "__main__":
    for bid, make in BUILT.items():
        write_nbt(os.path.join(ROOT, bid + ".nbt"), make())
        print("wrote", bid)
