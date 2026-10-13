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
# work package 6: a powered building is turned by Create: New Age's motors on the grid, and joins the grid at the wire
# connector on its roof (one wire, as Satisfactory's machines take one power line)
MOTOR = "create_new_age:reinforced_motor[facing={}]"
EXTENSION = "create_new_age:basic_motor_extension[facing={}]"
CONNECTOR = "create_new_age:electrical_connector[facing=up,mode=inert]"


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
    f.set(1, 3, 2, MOTOR.format("west"))
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (0, 1, 2))
    f.port(0, 0, 4, "north", "item_in", (0, 1, 2))
    f.set(0, 6, 2, CONNECTOR)
    return f.done()


def smelter():
    # 5 wide, 5 deep, 6 high: the motor in the left wall turns an Encased Fan that blows through a lava cell onto a Depot
    # (Create's bulk blasting). The controller feeds the Depot and takes the ingots off; the ports work on the controller.
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.walls(1, 4, CASE, GLASS)
    f.box(-2, 5, 0, 2, 5, 4, CASE)
    f.set(-2, 1, 2, MOTOR.format("east"))
    f.set(-1, 1, 2, "create:encased_fan[facing=east]")
    f.set(0, 1, 2, "minecraft:lava")
    f.set(1, 1, 2, "create:depot")
    # the lava is closed in on every side but the fan's and the Depot's
    for at in ((0, 2, 2), (0, 1, 1), (0, 1, 3)):
        f.set(*at, TRIM)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 0))
    f.port(0, 0, 4, "north", "item_in", (1, 1, 0))
    f.set(0, 6, 2, CONNECTOR)
    return f.done()


def miner(height, corner, deck, side, extension=False):
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
    f.set(0, 2, 2, MOTOR.format("down"))
    if extension:
        # Miner Mk.3's 45 MW is more than one motor gives: an extension behind it doubles it
        f.set(0, 3, 2, EXTENSION.format("down"))
    f.set(0, 2, 1, "create:chute")
    f.box(-1, height - 1, 1, 1, height - 1, 3, corner)
    f.set(0, height, 2, CONNECTOR)
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


# ---- work package 6: power. Each joins the grid at the wire connector on top (New Age's, wired by hand).
TURBINE = "siftec:turbine[facing={}]"
COIL = "create_new_age:generator_coil[axis=x]"
TANK = "create:fluid_tank"
# New Age's own reactor casing and glass draw as missing textures in the Fabric 26.3 port (its connected-texture models),
# so the plant is built from Create's casings until that is fixed
R_CASING, R_GLASS = "create:railway_casing", "create:framed_glass"


def pole(height, column):
    # one block wide: the controller at its foot, a column, the connector on top
    f = Frame(1, 1)
    f.set(0, 0, 0, CONTROLLER)
    for y in range(1, height - 1):
        f.set(0, y, 0, column)
    f.set(0, height - 1, 0, CONNECTOR)
    return f.done()


def power_tower():
    # 3 by 3, 13 high: four legs and a core of industrial iron up to a platform, the connector on top
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.set(0, 0, 0, CONTROLLER)
    for x in (-1, 1):
        for z in (0, 2):
            f.box(x, 1, z, x, 3, z, TRIM)
    f.box(0, 1, 1, 0, 10, 1, TRIM)
    f.box(-1, 11, 0, 1, 11, 2, DARK)
    f.set(0, 12, 1, CONNECTOR)
    return f.done()


def power_storage():
    # 3 by 3, 6 high: brass casing round a core of New Age's overcharged iron coils; the connector on the roof
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.walls(1, 3, BRASS, GLASS)
    f.box(0, 1, 1, 0, 3, 1, "create_new_age:overcharged_iron_wire_block")
    f.box(-1, 4, 0, 1, 4, 2, BRASS)
    f.set(1, 1, 0, CONTROLLER)
    f.set(0, 5, 1, CONNECTOR)
    return f.done()


def biomass_burner():
    # 3 by 3, 5 high: a fire behind glass with a chimney; fuel goes in at the back, the connector on the roof
    f = Frame(3, 3)
    f.box(-1, 0, 0, 1, 0, 2, DARK)
    f.walls(1, 2, CASE, GLASS)
    f.set(0, 1, 1, "minecraft:campfire[lit=false,signal_fire=false,waterlogged=false,facing=south]")
    f.box(-1, 3, 0, 1, 3, 2, CASE)
    f.set(0, 3, 1, TRIM)
    f.set(1, 4, 2, TRIM)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 2, "north", "item_in", (1, 1, 0))
    f.set(-1, 4, 1, CONNECTOR)
    return f.done()


def turbine_hall(f, y, z):
    """A turbine at x = 1 turning two generator coils toward the left, at height y and depth z."""
    f.set(1, y, z, TURBINE.format("west"))
    f.set(0, y, z, COIL)
    f.set(-1, y, z, COIL)


def coal_generator():
    # 5 by 5, 8 high: two furnaces under a boiler (a fluid tank of water), and the turbine and coils above; coal in at the
    # back left, water in at the back middle, the connector on the roof
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.walls(1, 5, CASE, GLASS)
    f.box(-2, 6, 0, 2, 6, 4, CASE)
    f.set(-1, 1, 2, "minecraft:blast_furnace[facing=south,lit=false]")
    f.set(1, 1, 2, "minecraft:blast_furnace[facing=south,lit=false]")
    f.set(0, 1, 2, TANK)
    turbine_hall(f, 3, 2)
    f.set(1, 1, 0, CONTROLLER)
    f.port(-1, 0, 4, "north", "item_in", (1, 1, 0))
    f.port(0, 0, 4, "north", "fluid_in", (0, 1, 2))
    f.set(0, 7, 2, CONNECTOR)
    return f.done()


def fuel_generator():
    # 5 by 5, 8 high: a fuel tank feeding the engine room, the turbine and coils above; fuel in at the back
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.walls(1, 5, TRIM, GLASS)
    f.box(-2, 6, 0, 2, 6, 4, TRIM)
    f.set(-1, 1, 2, TANK)
    f.set(1, 1, 2, "create:copper_casing")
    turbine_hall(f, 3, 2)
    f.set(1, 1, 0, CONTROLLER)
    f.port(-1, 0, 4, "north", "fluid_in", (-1, 1, 2))
    f.set(0, 7, 2, CONNECTOR)
    return f.done()


def geothermal_generator():
    # 5 by 5, 8 high, on four legs over a sulfur geyser: the vent cell under the middle is left as air, so the geyser
    # breathes; an acid tank and the turbine inside, the acid out at the back
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, AIR)
    for x in (-2, 2):
        for z in (0, 4):
            f.set(x, 0, z, CASE)
    f.box(-2, 1, 0, 2, 1, 4, DARK)
    f.walls(2, 5, CASE, GLASS)
    f.box(-2, 6, 0, 2, 6, 4, CASE)
    f.set(-1, 2, 2, TANK)
    turbine_hall(f, 4, 2)
    f.set(1, 1, 0, CONTROLLER)
    f.port(-1, 2, 4, "north", "fluid_out", (-1, 2, 2))
    f.set(0, 7, 2, CONNECTOR)
    return f.done()


def nuclear_power_plant():
    # 7 by 7, 10 high: reactor casing and glass round three reactor rods, a water tank, the turbine and coils; rods in
    # and water in at the back, waste out at the front, the connector on the roof
    f = Frame(7, 7)
    f.box(-3, 0, 0, 3, 0, 6, DARK)
    f.walls(1, 7, R_CASING, R_GLASS)
    f.box(-3, 8, 0, 3, 8, 6, R_CASING)
    f.box(0, 1, 3, 0, 3, 3, "create_new_age:reactor_rod")
    f.set(-2, 1, 3, TANK)
    turbine_hall(f, 5, 3)
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 0, "south", "item_out", (1, 1, 0))
    f.port(0, 0, 6, "north", "item_in", (1, 1, 0))
    f.port(-2, 0, 6, "north", "fluid_in", (-2, 1, 3))
    f.set(0, 9, 3, CONNECTOR)
    return f.done()


def alien_power_augmenter():
    # 5 by 5, 7 high: brass and reactor glass round a glowing core; Alien Power Matrices in at the back
    f = Frame(5, 5)
    f.box(-2, 0, 0, 2, 0, 4, DARK)
    f.walls(1, 4, BRASS, R_GLASS)
    f.box(-2, 5, 0, 2, 5, 4, BRASS)
    f.set(0, 1, 2, "minecraft:sea_lantern")
    f.set(0, 2, 2, "minecraft:end_rod[facing=up]")
    f.set(0, 3, 2, "minecraft:sea_lantern")
    f.set(1, 1, 0, CONTROLLER)
    f.port(0, 0, 4, "north", "item_in", (1, 1, 0))
    f.set(0, 6, 2, CONNECTOR)
    return f.done()


BUILT = {"test_building": test_building, "test_miner": test_miner,
         "constructor": constructor, "smelter": smelter,
         "miner_mk1": lambda: miner(8, CASE, DARK, GLASS), "miner_mk2": lambda: miner(9, TRIM, DARK, GLASS),
         "miner_mk3": lambda: miner(10, BRASS, TRIM, GLASS, extension=True), "portable_miner": portable_miner,
         "storage_container": lambda: storage(5, 1), "industrial_storage_container": lambda: storage(6, 2),
         "power_pole_mk1": lambda: pole(5, "create:andesite_casing"), "power_pole_mk2": lambda: pole(6, CASE),
         "power_pole_mk3": lambda: pole(7, BRASS), "power_tower": power_tower, "power_storage": power_storage,
         "biomass_burner": biomass_burner, "coal_generator": coal_generator, "fuel_generator": fuel_generator,
         "geothermal_generator": geothermal_generator, "nuclear_power_plant": nuclear_power_plant,
         "alien_power_augmenter": alien_power_augmenter}

if __name__ == "__main__":
    for bid, make in BUILT.items():
        write_nbt(os.path.join(ROOT, bid + ".nbt"), make())
        print("wrote", bid)
