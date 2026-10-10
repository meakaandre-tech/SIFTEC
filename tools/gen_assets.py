#!/usr/bin/env python3
"""Writes the mod's blockstates, models, item definitions, lang file and loot tables.
Textures come from tools/import_art.py; anything without one borrows a vanilla or Create texture."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import content
import recipes

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "siftec")
D = os.path.join(ROOT, "data", "siftec")

WRITTEN = set()


def write(path, obj):
    WRITTEN.add(os.path.normpath(path))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")

def item_def(name, model, tint=None):
    m = {"type": "minecraft:model", "model": model}
    if tint is not None:
        m["tints"] = [{"type": "minecraft:constant", "value": tint}]
    write(f"{A}/items/{name}.json", {"model": m})

NODES = {  # id: (display name, texture)
    "iron": ("Iron Ore", "minecraft:block/raw_iron_block"),
    "copper": ("Copper Ore", "minecraft:block/raw_copper_block"),
    "limestone": ("Limestone", "create:block/palettes/stone_types/limestone"),
    "coal": ("Coal", "minecraft:block/coal_block"),
    "zinc": ("Zinc Ore", "create:block/raw_zinc_block"),
    "oil": ("Crude Oil", "minecraft:block/black_concrete"),
    "bauxite": ("Bauxite", "minecraft:block/granite"),
    "nitrogen": ("Nitrogen Gas", "minecraft:block/calcite"),
    "sam": ("SAM", "minecraft:block/amethyst_block"),
    "quartz": ("Nether Quartz", "minecraft:block/quartz_block_side"),
    "sulfur": ("Sulfur", "minecraft:block/yellow_concrete_powder"),
}
lang = {
    "itemGroup.siftec": "SIFTEC",
    "block.siftec.node_rock": "Node Rock",
    "block.siftec.portable_miner": "Portable Miner",
    "block.siftec.miner_mk1": "Miner Mk.1",
    "item.siftec.node_scanner": "Node Scanner",
    "item.siftec.raw_bauxite": "Raw Bauxite",
    "item.siftec.sam": "SAM",
    "siftec.purity.impure": "Impure",
    "siftec.purity.normal": "Normal",
    "siftec.purity.pure": "Pure",
    "siftec.node.label": "%s, %s",
    "siftec.scanner.selected": "Scanning for: %s",
    "siftec.scanner.found": "%s: %s blocks %s (x %s, z %s)",
    "siftec.scanner.none": "No %s node in range",
    "siftec.scanner.elsewhere": "%s nodes are in %s",
    "siftec.scanner.found_cave": "%s: %s blocks %s, underground (x %s, z %s)",
    "siftec.dimension.nether": "the Nether", "siftec.dimension.overworld": "the Overworld",
    "siftec.command.found": "%s at x %s, z %s (%s blocks away)",
    "siftec.command.unknown_type": "Unknown node type",
}
for d, n in {"north": "north", "north_east": "north-east", "east": "east", "south_east": "south-east",
             "south": "south", "south_west": "south-west", "west": "west", "north_west": "north-west"}.items():
    lang[f"siftec.direction.{d}"] = n

# node rock (the mounds of nodes placed before pads existed)
write(f"{A}/blockstates/node_rock.json", {"variants": {"": {"model": "siftec:block/node_rock"}}})
write(f"{A}/models/block/node_rock.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "minecraft:block/cobbled_deepslate"}})
item_def("node_rock", "siftec:block/node_rock")

# the old flat pad and its fill (sites placed before pads were made of each node's own stone); still registered so
# those pads keep loading, never placed any more
lang["block.siftec.node_pad"] = "Node Pad"
lang["block.siftec.node_pad_fill"] = "Node Pad"
write(f"{A}/blockstates/node_pad.json", {"variants": {"": {"model": "siftec:block/node_pad"}}})
write(f"{A}/models/block/node_pad.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "minecraft:block/smooth_stone", "side": "minecraft:block/smooth_stone_slab_side", "bottom": "minecraft:block/smooth_stone"}})
item_def("node_pad", "siftec:block/node_pad")
write(f"{A}/blockstates/node_pad_fill.json", {"variants": {"": {"model": "siftec:block/node_pad_fill"}}})
write(f"{A}/models/block/node_pad_fill.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "minecraft:block/tuff"}})
item_def("node_pad_fill", "siftec:block/node_pad_fill")

# ---- the node itself: a low, wide rock mound with the resource in chunky lumps on top, like Satisfactory's nodes,
# built from vanilla and Create textures. It is wider than its block (explicit UVs, no culling) and the deposit
# grows with purity. A miner's bit rests on top (16 px) and its legs stand on the pad outside the mound.
FACES = ("north", "south", "east", "west", "up", "down")

def mbox(frm, to, tex, angle=0, axis="y", origin=None):
    sx, sy, sz = (to[i] - frm[i] for i in range(3))
    def span(a, size):
        size = min(size, 16)
        a0 = a % 16
        if a0 + size > 16:
            a0 = 16 - size
        return a0, a0 + size
    ux, uy, uz = span(frm[0], sx), span(16 - to[1], sy), span(frm[2], sz)
    uv = {"north": [ux[0], uy[0], ux[1], uy[1]], "south": [ux[0], uy[0], ux[1], uy[1]],
          "east": [uz[0], uy[0], uz[1], uy[1]], "west": [uz[0], uy[0], uz[1], uy[1]],
          "up": [ux[0], uz[0], ux[1], uz[1]], "down": [ux[0], uz[0], ux[1], uz[1]]}
    e = {"from": list(frm), "to": list(to), "faces": {f: {"texture": tex, "uv": uv[f]} for f in FACES}}
    if angle:
        # each piece turns about its own middle, so it stays where it was put
        o = origin or [(frm[i] + to[i]) / 2 for i in range(3)]
        e["rotation"] = {"origin": list(o), "axis": axis, "angle": angle}
    return e

# a disc is eight long boxes turned in steps of 22.5 degrees (the angles a model allows, used twice over with long
# sides along x and along z), so its edge is nearly round; each box is a pixel longer or shorter for a rough edge
ROUGH = [0, 1, -1, 1, 0, -1, 1, 0, -1, 0, 1, -1, 0, 1, 0, -1]

def disc(r, y0, y1, tex, k=0):
    els = []
    for i, (angle, along_x) in enumerate([(0, True), (22.5, True), (-22.5, True), (45, True), (0, False), (22.5, False), (-22.5, False), (45, False)]):
        rr = r + ROUGH[(i + k) % len(ROUGH)]
        w = round(rr * 0.42)
        frm, to = ((8 - rr, y0, 8 - w), (8 + rr, y1, 8 + w)) if along_x else ((8 - w, y0, 8 - rr), (8 + w, y1, 8 + rr))
        els.append(mbox(frm, to, tex, angle, origin=(8, y0, 8)) if angle else mbox(frm, to, tex))
    return els

def rock_base(tiers=((14, 0, 2), (12, 2, 4), (9, 4, 7), (6, 7, 9))):
    """The rock: a low rounded mound in rough steps, about 1.75 blocks across, inside the miner's legs."""
    els = []
    for k, (r, y0, y1) in enumerate(tiers):
        els += disc(r, y0, y1, "#rock", k * 3)
    return els

LUMPS = {  # the deposit on top, by purity: (from, to, angle). The middle one carries the miner's bit (16 px at most).
    "impure": [((5, 8, 5), (11, 13, 11), 22.5), ((1, 4, 6), (5, 8, 10), -22.5), ((11, 4, 9), (14, 7, 12), 0)],
    "normal": [((4, 8, 4), (12, 15, 12), 22.5), ((9, 6, 1), (14, 11, 6), -22.5), ((1, 6, 8), (6, 11, 13), 22.5),
               ((10, 4, 10), (15, 8, 15), 45), ((-2, 2, 3), (3, 6, 8), -22.5)],
    "pure": [((3, 8, 3), (13, 16, 13), 22.5), ((8, 6, -1), (15, 13, 6), -22.5), ((0, 6, 9), (7, 13, 16), 22.5),
             ((10, 4, 10), (16, 10, 16), 45), ((-3, 2, 2), (3, 8, 8), -22.5), ((13, 2, 0), (18, 7, 5), 22.5),
             ((1, 2, 14), (6, 7, 19), -22.5), ((-4, 2, 11), (0, 6, 15), 45)],
}

def mound(kind, purity):
    lumps = LUMPS[purity]
    if kind == "nitrogen":
        # a vent: a pale collar round a dark hole on top of the rock, frost crystals round it
        els = rock_base() + [mbox((4, 9, 4), (12, 12, 5), "#vent"), mbox((4, 9, 11), (12, 12, 12), "#vent"),
                             mbox((4, 9, 5), (5, 12, 11), "#vent"), mbox((11, 9, 5), (12, 12, 11), "#vent"),
                             mbox((5, 9, 5), (11, 10, 11), "#hole")]
        ice = [((0, 4, 1), (4, 9, 5), 22.5), ((12, 4, 11), (16, 10, 15), -22.5), ((12, 4, 1), (15, 8, 4), 45),
               ((1, 4, 12), (4, 10, 15), 22.5), ((7, 7, -1), (10, 11, 2), 0), ((-2, 2, 7), (2, 7, 10), 0)]
        n = {"impure": 2, "normal": 4, "pure": 6}[purity]
        return els + [mbox(f, t, "#ore", a) for f, t, a in ice[:n]]
    if kind == "oil":
        # a tar pool on a low rock rim, crude welling up in black lumps
        els = rock_base(((14, 0, 2), (12, 2, 4), (10, 4, 6))) + disc(8, 6, 7, "#pool", 5)
        blobs = [((5, 6, 5), (10, 9, 10), 22.5), ((9, 6, 8), (12, 8, 12), -22.5), ((4, 6, 10), (7, 8, 13), 0),
                 ((6, 8, 6), (9, 11, 9), 45), ((10, 6, 3), (13, 8, 6), 22.5)]
        n = {"impure": 2, "normal": 3, "pure": 5}[purity]
        return els + [mbox(f, t, "#ore", a) for f, t, a in blobs[:n]]
    return rock_base() + [mbox(f, t, "#ore", a) for f, t, a in lumps]

# ---- the one table of each node's stone: its pad, the fill under it and the rock of its mound are all this natural,
# unpolished stone (vanilla or Create). id: (stone name, side texture, top texture). Change a node's stone here only.
STONE = {
    "iron": ("Tuff", "minecraft:block/tuff", "minecraft:block/tuff"),
    "copper": ("Granite", "minecraft:block/granite", "minecraft:block/granite"),
    "zinc": ("Andesite", "minecraft:block/andesite", "minecraft:block/andesite"),
    "limestone": ("Limestone", "create:block/palettes/stone_types/limestone", "create:block/palettes/stone_types/limestone"),
    "coal": ("Stone", "minecraft:block/stone", "minecraft:block/stone"),
    "oil": ("Basalt", "minecraft:block/basalt_side", "minecraft:block/basalt_top"),
    "bauxite": ("Cinnabar", "minecraft:block/cinnabar", "minecraft:block/cinnabar"),
    "nitrogen": ("Diorite", "minecraft:block/diorite", "minecraft:block/diorite"),
    "sam": ("Deepslate", "minecraft:block/deepslate", "minecraft:block/deepslate_top"),
    "quartz": ("Netherrack", "minecraft:block/netherrack", "minecraft:block/netherrack"),
    "sulfur": ("Sulfur", "minecraft:block/sulfur", "minecraft:block/sulfur"),
}
DEPOSIT = {  # the lumps on each node: vanilla and Create textures only
    "iron": "minecraft:block/raw_iron_block", "copper": "minecraft:block/raw_copper_block",
    "limestone": "minecraft:block/calcite", "coal": "minecraft:block/coal_block",
    "zinc": "create:block/raw_zinc_block", "oil": "minecraft:block/obsidian", "bauxite": "minecraft:block/terracotta",
    "nitrogen": "minecraft:block/packed_ice", "sam": "minecraft:block/amethyst_block",
    "quartz": "minecraft:block/quartz_block_bottom", "sulfur": "minecraft:block/potent_sulfur",
}
PURITIES = ("impure", "normal", "pure")

for id, (name, tex) in NODES.items():
    b = f"{id}_node"
    lang[f"block.siftec.{b}"] = f"{name} Node"
    lang[f"siftec.node.{id}"] = name
    write(f"{A}/blockstates/{b}.json", {"variants": {
        **{f"core=false,purity={p}": {"model": f"siftec:block/{b}"} for p in PURITIES},
        **{f"core=true,purity={p}": {"model": f"siftec:block/{b}_mound_{p}"} for p in PURITIES}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": tex}})
    stone, rock, top = STONE[id]
    # its pad (and the fill under it): a plain block of the stone, unbreakable; no item, so no item model
    lang[f"block.siftec.{b}_pad"] = f"{stone} Node Pad"
    write(f"{A}/blockstates/{b}_pad.json", {"variants": {"": {"model": f"siftec:block/{b}_pad"}}})
    write(f"{A}/models/block/{b}_pad.json", {"parent": "minecraft:block/cube_column", "textures": {"side": rock, "end": top}})
    for p in PURITIES:
        write(f"{A}/models/block/{b}_mound_{p}.json", {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": {
            "particle": rock, "rock": rock, "ore": DEPOSIT[id], "vent": "minecraft:block/light_gray_concrete",
            "hole": "minecraft:block/black_concrete", "pool": "minecraft:block/black_concrete"}, "elements": mound(id, p)})
    item_def(b, f"siftec:block/{b}_mound_normal")

# the middle of an oil node: the block a Pumpjack's pipe reaches down to
write(f"{A}/blockstates/oil_well.json", {"variants": {
    **{f"core=false,purity={p}": {"model": "siftec:block/oil_node_mound_normal"} for p in PURITIES},
    **{f"core=true,purity={p}": {"model": f"siftec:block/oil_node_mound_{p}"} for p in PURITIES}}})
item_def("oil_well", "siftec:block/oil_node_mound_normal")
lang["block.siftec.oil_well"] = "Oil Well"

# miners and the extractor: Create casings until real models are picked
lang.update({"block.siftec.dimensional_depot": "Dimensional Depot", "siftec.depot.title": "Cloud: %s", "siftec.depot.count": "%s in the cloud",
             "siftec.depot.click": "Click to take a stack", "siftec.depot.previous": "Previous page", "siftec.depot.next": "Next page",
             "item.siftec.power_line": "Power Line", "siftec.line.first": "Now click the pole to join it to",
             "siftec.line.too_far": "Too far: these poles reach %s blocks", "siftec.line.full": "A pole takes at most %s lines",
             "siftec.line.joined": "Poles joined", "siftec.storage.status": "Power Storage: %s%% (%s)",
             "siftec.storage.mode.0": "idle", "siftec.storage.mode.1": "charging", "siftec.storage.mode.2": "discharging"})
write(f"{A}/models/item/power_line.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/lead"}})
item_def("power_line", "siftec:item/power_line", 0xE0803C)
for b, name in (("power_pole", "Power Pole"), ("power_tower", "Power Tower")):
    lang[f"block.siftec.{b}"] = name
    tex = "create:block/andesite_casing" if b == "power_pole" else "create:block/railway_casing"
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/block", "textures": {"particle": tex, "all": tex},
          "elements": [{"from": [5, 0, 5], "to": [11, 16, 11], "faces": {f: {"texture": "#all"} for f in ("north", "south", "east", "west", "up", "down")}},
                       {"from": [2, 12, 7], "to": [14, 14, 9], "faces": {f: {"texture": "#all"} for f in ("north", "south", "east", "west", "up", "down")}}]})
    item_def(b, f"siftec:block/{b}")

lang.update({"siftec.claim.denied": "This land belongs to %s", "siftec.claim.budget": "Your company has used all %s of its Claim Markers",
             "siftec.claim.too_close": "Too close: markers must be more than %s chunks from another company's HUB",
             "siftec.claim.nothing": "Every chunk here is already claimed", "siftec.claim.claimed": "Claim Markers: %s of %s",
             "siftec.claim.hub": "HUB placed: %s chunks claimed"})
lang.update({"item.siftec.gypsum": "Gypsum", "item.siftec.toxic_residue": "Toxic Residue",
             "siftec.geyser.full": "Geyser Engine: acid tank full (%s mB). Pipe it away", "siftec.geyser.running": "Geyser Engine: erupting (%s mB acid)",
             "siftec.geyser.none": "Geyser Engine: no geyser below (%s mB acid)", "siftec.geyser.blocked": "Geyser Engine: sitting on the geyser's water shuts its gas in. Leave a block of air between (%s mB acid)", "siftec.geyser.blocked_place": "A Geyser Engine needs a block of air between it and the geyser's water", "siftec.geyser.waiting": "Geyser Engine: waiting for the next eruption (%s mB acid)"})
for name, tex, tint in (("gypsum", "minecraft:item/bone_meal", None), ("toxic_residue", "minecraft:item/slime_ball", 0x90C020)):
    write(f"{A}/models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": tex}})
    item_def(name, f"siftec:item/{name}", tint)
MACHINES = {"blueprint_designer": ("Blueprint Designer Mk.1", "minecraft:block/lapis_block"), "blueprint_designer_mk3": ("Blueprint Designer Mk.3", "minecraft:block/diamond_block"), "drone_port": ("Drone Port", "create:block/cardboard_block_side"), "main_portal": ("Main Portal", "minecraft:block/crying_obsidian"), "satellite_portal": ("Satellite Portal", "minecraft:block/obsidian"), "landing_pad": ("Landing Pad", "minecraft:block/hay_block_side"), "radar_tower": ("Radar Tower", "create:block/brass_casing"), "furnace_engine": ("Furnace Engine", "minecraft:block/furnace_side"), "hub_engine": ("HUB Engine", "minecraft:block/blast_furnace_side"), "geyser_engine": ("Geyser Engine", "create:block/copper_casing"), "claim_marker": ("Claim Marker", "minecraft:block/red_concrete"), "dimensional_depot": ("Dimensional Depot", "create:block/brass_casing"), "power_storage": ("Power Storage", "create:block/copper_casing"), "miner_mk1": ("Miner Mk.1", "create:block/andesite_casing"), "miner_mk2": ("Miner Mk.2", "create:block/copper_casing"),
            "miner_mk3": ("Miner Mk.3", "create:block/brass_casing"), "resource_well_extractor": ("Resource Well Extractor", "create:block/railway_casing")}
for b, (name, casing) in MACHINES.items():
    lang[f"block.siftec.{b}"] = name
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": "create:block/gearbox_top", "side": casing, "bottom": casing}})
    item_def(b, f"siftec:block/{b}")

# ---- powered miners, shaped like the old node drills: two blocks tall. The lower block's model draws the whole
# casing, running up into the upper block, with a lip and a ring of beams at its foot and four legs reaching down past
# the node to the pad; the renderer adds the turning drill head, cog and shaft end. One casing texture each (explicit
# UVs: the model reaches outside its block).
MINERS = {"miner_mk1": "create:block/andesite_casing", "miner_mk2": "create:block/brass_casing", "miner_mk3": "create:block/railway_casing"}

def miner_model(casing):
    c = "#casing"
    els = [mbox((0, 11, 0), (16, 27, 16), c),            # the casing, up into the block above
           mbox((-1, 11, -1), (17, 14, 17), c),          # the lip at its foot
           # the ring of beams round it, and the spokes joining it to the lip
           mbox((-7, 11, -7), (23, 13, -5), c), mbox((-7, 11, 21), (23, 13, 23), c),
           mbox((-7, 11, -5), (-5, 13, 21), c), mbox((21, 11, -5), (23, 13, 21), c),
           mbox((-5, 11, 7), (-1, 13, 9), c), mbox((17, 11, 7), (21, 13, 9), c),
           mbox((7, 11, -5), (9, 13, -1), c), mbox((7, 11, 17), (9, 13, 21), c)]
    # four legs from the ring down to the pad the node stands on
    for x in (-7, 20):
        for z in (-7, 20):
            els.append(mbox((x, -16, z), (x + 3, 15, z + 3), c))
    return {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": {"particle": casing, "casing": casing}, "elements": els}

for b, casing in MINERS.items():
    write(f"{A}/models/block/{b}.json", miner_model(casing))
    write(f"{A}/blockstates/{b}.json", {"variants": {f"facing={d}": {"model": f"siftec:block/{b}"} for d in ("north", "east", "south", "west")}})
    item_def(b, f"siftec:block/{b}")
lang["block.siftec.miner_top"] = "Miner"
write(f"{A}/blockstates/miner_top.json", {"variants": {"": {"model": "siftec:block/miner_top"}}})
write(f"{A}/models/block/miner_top.json", {"textures": {"particle": "create:block/andesite_casing"}})
item_def("miner_top", "siftec:block/miner_top")

lang.update({"siftec.engine.running": "Furnace Engine: running", "siftec.engine.running_hub": "HUB Engine: running (%s fuel left)",
             "siftec.engine.no_furnace": "Furnace Engine: needs a burning furnace beside or under it",
             "siftec.engine.no_hub": "HUB Engine: has to touch a HUB", "siftec.engine.no_fuel": "HUB Engine: out of fuel. Click it with coal or any furnace fuel",
             "siftec.engine.too_many": "HUB Engine: this HUB cannot run that many engines yet"})
# ---- equipment: stand-in icons from vanilla items
EQUIPMENT = {"jetpack": ("Jetpack", "minecraft:item/firework_rocket"), "hover_pack": ("Hover Pack", "minecraft:item/elytra"),
             "parachute": ("Parachute", "minecraft:item/phantom_membrane"), "hazmat_suit": ("Hazmat Suit", "minecraft:item/leather_chestplate"),
             "gas_mask": ("Gas Mask", "minecraft:item/leather_helmet"), "blade_runners": ("Blade Runners", "minecraft:item/iron_boots")
             }
for eid, (ename, etex) in EQUIPMENT.items():
    lang[f"item.siftec.{eid}"] = ename
    write(f"{A}/models/item/{eid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": etex}})
    item_def(eid, f"siftec:item/{eid}")
lang.update({"siftec.jetpack.empty": "Jetpack: out of fuel. It burns Solid Biofuel, Compacted Coal, diesel or Turbofuel",
             "siftec.zipline.no_line": "No Power Line leads that way from this pole", "siftec.zipline.locked": "Riding Power Lines needs the Zipline research (MAM, Caterium)",
             "siftec.crash.somersloop": "There was a Somersloop in the wreckage too", "siftec.boost.shards_only": "This machine takes Power Shards only",
             "siftec.radar.title": "Radar Tower: nearest of each, from here", "siftec.radar.line": "%s: %s blocks %s (x %s, z %s)",
             "siftec.radar.nothing": "Nothing in range"})
lang["item.siftec.cardboard_drone"] = "Cardboard Drone"
write(f"{A}/models/item/cardboard_drone.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "create:item/cardboard"}})
item_def("cardboard_drone", "siftec:item/cardboard_drone")
lang.update({"siftec.place.not_yours": "That belongs to another company", "siftec.place.where": "x %s, y %s, z %s (%s blocks away)",
             "siftec.drone.no_drone": "No drone here. Click the port with a Cardboard Drone", "siftec.drone.has_drone": "This port already has its drone",
             "siftec.drone.no_destination": "No destination yet. Click the port empty-handed to pick one",
             "siftec.drone.idle": "Drone ready. It leaves when packages are waiting", "siftec.drone.no_fuel": "Drone waiting for fuel: a trip needs %s fire charges",
             "siftec.drone.outbound": "Drone on its way out", "siftec.drone.waiting": "Drone waiting to unload at the far port", "siftec.drone.homebound": "Drone on its way home",
             "siftec.drone.choose": "Click to send packages here", "siftec.drone.chosen": "Packages go here",
             "siftec.drone.waiting_count": "%s packages waiting to leave", "siftec.drone.take_drone": "Click to take the drone out",
             "siftec.drone.away": "The drone is away", "siftec.drone.collect": "Arrived packages: %s. Click to collect",
             "siftec.drone.fuel": "Fire charges: %s (a trip needs %s)", "siftec.drone.fuel_how": "Click the port with fire charges, or feed them in by funnel",
             "siftec.portal.title": "Portals", "siftec.portal.no_main": "Portals need the company to have a Main Portal somewhere",
             "siftec.portal.where": "x %s, y %s, z %s in %s", "siftec.portal.go": "Click to go there"})
lang["item.siftec.blueprint"] = "Blueprint"
write(f"{A}/models/item/blueprint.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/filled_map"}})
item_def("blueprint", "siftec:item/blueprint")
lang.update({"siftec.blueprint.how": "Build inside the frame above (%s blocks each way); you can fly in there. Click with paper to save it, sneak-click to clear it",
             "siftec.blueprint.empty": "Nothing is built in the frame yet", "siftec.blueprint.saved": "Blueprint saved: %s blocks",
             "siftec.blueprint.cleared": "Cleared %s blocks from the frame", "siftec.blueprint.blank": "This blueprint is blank",
             "siftec.blueprint.blocked": "Something is in the way at x %s, y %s, z %s", "siftec.blueprint.missing": "The blueprint needs %s more %s",
             "siftec.blueprint.confirm": "%s blocks would go in the green frame. Use the blueprint here again to build",
             "siftec.blueprint.built": "Built %s blocks"})
lang["item.siftec.hub_planner"] = "HUB Planner"
write(f"{A}/models/item/hub_planner.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/brush"}})
item_def("hub_planner", "siftec:item/hub_planner")
lang.update({"siftec.planner.first": "First corner set. Now click the opposite corner", "siftec.planner.second": "Area %s x %s x %s. Click the HUB with the planner to measure it",
             "siftec.planner.cleared": "Planner cleared", "siftec.planner.how": "Click two opposite corners of the HUB building first, then the HUB",
             "siftec.building.title": "HUB building: good for %s", "siftec.building.none": "no tier yet",
             "siftec.building.hub_outside": "The HUB has to be inside the marked area", "siftec.building.too_big": "The marked area is too big: %s blocks, the limit is %s",
             "siftec.building.blocks": "For Tier %s: %s of %s building blocks", "siftec.building.newest": "Newest material (%s): %s of %s",
             "siftec.building.shelter": "Walls: %s of 2. Roof: %s", "siftec.building.ok": "Ready for Tier %s", "siftec.building.not_ok": "Not ready for Tier %s yet",
             "siftec.building.needed": "The HUB building is too small for Tier %s. Build it up, then click the HUB with the HUB Planner",
             "siftec.building.family.stone_wood": "logs, planks, stone bricks, polished stone, deepslate bricks, Twigs blocks, and any stairs, slabs, fences, walls and doors",
             "siftec.building.family.andesite": "Andesite Casing or Block of Andesite Alloy",
             "siftec.building.family.steel": "Steel Casing or Block of Steel",
             "siftec.building.family.copper": "Copper Casing or copper blocks", "siftec.building.family.brass": "Brass Casing or Block of Brass"})
lang["siftec.sift.closed"] = "The Sift is closed to you until your company finishes Wormhole Phase 5"
PRESERVE_ICONS = {"pickled_tomato": ("Pickled Tomato", 0xC84030), "pickled_onion": ("Pickled Onion", 0xD8C8A8), "pickled_cabbage": ("Pickled Cabbage", 0x90B850),
                  "pickled_pumpkin": ("Pickled Pumpkin", 0xE08828), "pickled_carrot": ("Pickled Carrot", 0xF09030), "pickled_beetroot": ("Pickled Beetroot", 0x902848),
                  "pickled_kelp": ("Pickled Kelp", 0x4C7A3A), "sweet_berry_jam": ("Sweet Berry Jam", 0xB01838), "glow_berry_jam": ("Glow Berry Jam", 0xF0B040),
                  "apple_jam": ("Apple Jam", 0xD86048), "melon_jam": ("Melon Jam", 0xE85868), "mead": ("Mead", 0xE0A030)}
for fid, (fname, tint) in PRESERVE_ICONS.items():
    lang[f"item.siftec.{fid}"] = fname
    write(f"{A}/models/item/{fid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/honey_bottle" if fid == "mead" else "minecraft:item/potion"}})
    item_def(fid, f"siftec:item/{fid}", tint)
lang.update({"siftec.season.locked": "Your company has not researched Seasoning yet", "siftec.season.already": "That food is already seasoned",
             "siftec.season.short": "You need %s of the ingredient: one for each item of food", "siftec.season.long": "Seasoned with glowstone: effects last twice as long",
             "siftec.season.strong": "Seasoned with blaze powder: effects one level stronger", "siftec.season.bonus": "Seasoned with nether wart: one more effect at random"})
lang["item.siftec.toxic_shot"] = "Toxic Shot"
write(f"{A}/models/item/toxic_shot.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/slime_ball"}})
item_def("toxic_shot", "siftec:item/toxic_shot", 0x607018)
lang["block.siftec.steel_casing"] = "Steel Casing"
write(f"{A}/blockstates/steel_casing.json", {"variants": {"": {"model": "siftec:block/steel_casing"}}})
write(f"{A}/models/block/steel_casing.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "create:block/railway_casing"}})
item_def("steel_casing", "siftec:block/steel_casing")
lang.update({"siftec.lock.alt": "Locked: an alternate recipe from a Hard Drive (%s)", "siftec.lock.disabled": "Switched off in this pack", "siftec.blueprint.turned": "Blueprint turned to %s degrees"})
# Speed Governor: borrows the Gearshift's model until it has its own
lang.update({"block.siftec.speed_governor": "Speed Governor", "siftec.governor.status": "%s RPM out (your company's limit is %s)",
             "siftec.governor.set": "Set to %s RPM", "siftec.governor.over": "%s RPM: above your company's limit", "siftec.governor.step": "%s RPM"})
write(f"{A}/blockstates/speed_governor.json", {"variants": {
    "axis=x": {"model": "create:block/gearshift/block", "x": 90, "y": 90}, "axis=y": {"model": "create:block/gearshift/block"},
    "axis=z": {"model": "create:block/gearshift/block", "x": 90, "y": 180}}})
item_def("speed_governor", "create:block/gearshift/item")

write(f"{A}/blockstates/portable_miner.json", {"variants": {"": {"model": "siftec:block/portable_miner"}}})
def box(frm, to, tex):
    return {"from": frm, "to": to, "faces": {f: {"texture": tex} for f in ("north", "south", "east", "west", "up", "down")}}
write(f"{A}/models/block/portable_miner.json", {"parent": "minecraft:block/block", "textures": {
    "particle": "create:block/andesite_casing", "body": "create:block/andesite_casing", "drill": "minecraft:block/iron_block"},
    "elements": [box([3, 6, 3], [13, 14, 13], "#body"), box([6, 0, 6], [10, 6, 10], "#drill"),
                 box([3, 0, 3], [5, 6, 5], "#drill"), box([11, 0, 3], [13, 6, 5], "#drill"),
                 box([3, 0, 11], [5, 6, 13], "#drill"), box([11, 0, 11], [13, 6, 13], "#drill")]})
item_def("portable_miner", "siftec:block/portable_miner")

# plain items
for name, tex in {"node_scanner": "minecraft:item/recovery_compass_16", "raw_bauxite": "minecraft:item/brick",
                  "sam": "minecraft:item/amethyst_shard"}.items():
    write(f"{A}/models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": tex}})
    item_def(name, f"siftec:item/{name}")


# ---- parts, milestones and phases: one file the mod reads at start-up
def secs(t):
    m, sec = t.split(":")
    return int(m) * 60 + int(sec)

# every item id of the game and of the mods the pack's data names (items only: blocks without an item are not
# in it), so a typo or a renamed item stops the generator instead of loading as nothing
KNOWN = set(open(os.path.join(os.path.dirname(__file__), "known_ids.txt")).read().split())
CHECKED = ("minecraft", "create", "cgs", "createdieselgenerators", "create_hypertube")
UNKNOWN = []

def check(item, where=""):
    if item.split(":")[0] in CHECKED and item not in KNOWN:
        UNKNOWN.append(f"{item} ({where})")

data = {"parts": [], "milestones": [], "phases": [], "disabled": content.DISABLED}
for i in content.DISABLED: check(i, "disabled")
for pid, name, tex, tint in content.PARTS:
    part = {"id": pid}
    if pid in content.FUELS:
        # a furnace fuel, burning as long as the number says (coal is 1600); the Blaze Burner reads the same value
        part["fuel"] = f"cooking/time_{pid}"
        write(f"{D}/context_int_provider/cooking/time_{pid}.json", {"type": "minecraft:div", "left": content.FUELS[pid], "right": {
            "type": "minecraft:conditional", "condition": "minecraft:block/fast_cooking",
            "on_false": "minecraft:cooking/normal_burn_time_reduction_factor", "on_true": "minecraft:cooking/fast_burn_time_reduction_factor"}})
    data["parts"].append(part)
    lang[f"item.siftec.{pid}"] = name
    write(f"{A}/models/item/{pid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": tex}})
    item_def(pid, f"siftec:item/{pid}", tint)
# the tier tabs show the tier's first milestones in their tooltip
for tier, mid, name, cost, time, text, items, tokens in content.MILESTONES:
    for i in items: check(i, mid)
    for i, c in content.parse_cost(cost): check(i, mid + " cost")
    data["milestones"].append({"id": mid, "tier": tier, "cost": [{"item": i, "count": c} for i, c in content.parse_cost(cost)],
                               "seconds": secs(time), "items": items, "tokens": tokens})
    lang[f"siftec.milestone.{mid}"] = name
    lang[f"siftec.milestone.{mid}.unlocks"] = text
for mid, name, cost, text in content.PHASES:
    data["phases"].append({"id": mid, "tier": -1, "cost": [{"item": i, "count": c} for i, c in content.parse_cost(cost)],
                           "seconds": 0, "items": [], "tokens": []})
    lang[f"siftec.milestone.{mid}"] = name
    lang[f"siftec.milestone.{mid}.unlocks"] = text
# ---- MAM research: every node is stored like a milestone, with its tree and what it needs first
data["trees"] = []
for tree_id, tree_name, icon, nodes in content.MAM:
    check(icon, tree_id)
    ids = [f"mam_{tree_id}_{n + 1}" for n in range(len(nodes))]
    data["trees"].append({"id": tree_id, "icon": icon, "nodes": ids})
    lang[f"siftec.tree.{tree_id}"] = tree_name
    for n, (name, cost, time, text, needs, items, tokens) in enumerate(nodes):
        for i in items: check(i, ids[n])
        for i, c in content.parse_cost(cost): check(i, ids[n] + " cost")
        if needs is None:
            needs = [n] if n > 0 else []
        data["milestones_mam"] = data.get("milestones_mam", [])
        data["milestones_mam"].append({"id": ids[n], "tree": tree_id, "cost": [{"item": i, "count": c} for i, c in content.parse_cost(cost)],
                                       "seconds": secs(time), "items": items, "tokens": tokens, "needs": [ids[k - 1] for k in needs]})
        lang[f"siftec.milestone.{ids[n]}"] = name
        lang[f"siftec.milestone.{ids[n]}.unlocks"] = text
# the other mods' items that unlock with a milestone, beyond the ones listed with it
by_id = {m["id"]: m for m in data["milestones"] + data["milestones_mam"]}
for mid, more in content.MORE_LOCKS.items():
    if mid not in by_id:
        raise SystemExit(f"MORE_LOCKS: no milestone {mid}")
    for i in more:
        check(i, mid)
        by_id[mid]["items"].append(i)
# the machine routes' sub-assemblies unlock with the build they go into
parts_ids = {"siftec:" + pid for pid, _, _, _ in content.PARTS}
for mid, more in content.ASSEMBLY_LOCKS.items():
    if mid not in by_id:
        raise SystemExit(f"ASSEMBLY_LOCKS: no milestone {mid}")
    for i in more:
        if i not in parts_ids:
            raise SystemExit(f"ASSEMBLY_LOCKS: {i} is not a part")
        by_id[mid]["items"].append(i)
# locked by default: every item of those mods has to be unlocked by something, or be left free on purpose
placed = {}
for m in data["milestones"] + data["milestones_mam"]:
    for i in m["items"]:
        if i in placed:
            raise SystemExit(f"{i} is unlocked twice: {placed[i]} and {m['id']}")
        placed[i] = m["id"]
for i in content.FREE: check(i, "FREE")
accounted = set(placed) | set(content.FREE) | set(content.DISABLED) | {i for i, c, p in content.SHOP}
both = sorted((set(placed) | set(content.DISABLED)) & set(content.FREE))
if both:
    raise SystemExit(f"both locked and free: {both}")
loose = sorted(i for i in KNOWN if i.split(":")[0] in ("create", "cgs", "createdieselgenerators", "create_hypertube") and i not in accounted)
if loose:
    raise SystemExit(f"{len(loose)} items have no milestone and are not listed as free: {loose}")
lang.update({"block.siftec.mam": "MAM", "siftec.mam.title": "MAM: %s", "siftec.mam.research": "Research time: %s",
             "siftec.mam.busy": "Already researching %s", "siftec.mam.running": "Researching %s: %s left", "siftec.mam.idle": "No research running",
             "siftec.mam.started": "Research started: %s", "siftec.mam.click": "Click to deliver parts from your inventory",
             "siftec.tag.mushrooms": "any mushroom", "siftec.tag.crops": "any crop"})
write(f"{A}/blockstates/mam.json", {"variants": {"": {"model": "siftec:block/mam"}}})
write(f"{A}/models/block/mam.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "minecraft:block/lodestone_top", "side": "create:block/copper_casing", "bottom": "create:block/copper_casing"}})
item_def("mam", "siftec:block/mam")
write(f"{D}/loot_table/blocks/mam.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "siftec:mam"}]}]})
# ---- slugs and artefacts in the world
lang.update({"item.siftec.object_scanner": "Object Scanner", "siftec.boost.no_slot": "No free slot for that: research more at the MAM", "siftec.boost.status": "Power Shards: %s, Somersloop: %s",
             "siftec.boost.yes": "yes", "siftec.boost.no": "no", "siftec.collect.already": "Your company already collected this one",
             "siftec.collect.got": "Collected: %s", "siftec.scanner.none_object": "No %s left in range", "siftec.scanner.mobs": "hostile mobs"})
write(f"{A}/models/item/object_scanner.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/compass_16"}})
item_def("object_scanner", "siftec:item/object_scanner")
COLLECT = {"blue_power_slug": ("minecraft:block/blue_concrete", "Blue Power Slug"), "yellow_power_slug": ("minecraft:block/yellow_concrete", "Yellow Power Slug"),
           "purple_power_slug": ("minecraft:block/purple_concrete", "Purple Power Slug"), "mercer_sphere": ("minecraft:block/pink_concrete", "Mercer Sphere"),
           "somersloop": ("minecraft:block/red_concrete", "Somersloop"), "crash_site": ("create:block/railway_casing", "Crash Site Pod")}
lang.update({"item.siftec.crash_site": "Crash Site", "siftec.crash.needs": "The pod is sealed. It opens for %s %s",
             "siftec.tree.hard_drives": "Hard Drives", "siftec.mam.hard_drive": "Hard Drive", "siftec.alt.research": "Research a Hard Drive",
             "siftec.alt.cost": "Takes one Hard Drive from your inventory", "siftec.alt.owned": "Alternate recipes: %s of %s",
             "siftec.alt.choose_first": "Choose one of the offered alternates first", "siftec.alt.none": "No alternate fits what your company has unlocked. Keep the drive for later, or sink it",
             "siftec.alt.no_drive": "You are not carrying a Hard Drive", "siftec.alt.pick": "Click to take this one",
             "siftec.alt.ready": "The Hard Drive is decoded. Choose an alternate recipe at the MAM",
             "siftec.alt.chosen": "Alternate recipe unlocked: %s. %s"})
for cid, (tex, name) in COLLECT.items():
    lang[f"block.siftec.{cid}_block"] = name
    write(f"{A}/blockstates/{cid}_block.json", {"variants": {"": {"model": f"siftec:block/{cid}_block"}}})
    write(f"{A}/models/block/{cid}_block.json", {"parent": "minecraft:block/block", "textures": {"particle": tex, "all": tex},
          "elements": [{"from": [4, 0, 4], "to": [12, 8, 12], "faces": {f: {"texture": "#all"} for f in ("north", "south", "east", "west", "up", "down")}}]})
# the power slugs: a low, lumpy, glowing blob, cut from the owner's own slug drawing (the side texture tools/import_art.py
# imports from tools/art). Every colour's drawing has the same layout, so one set of UVs serves all three. Each slug
# sits turned one of four ways, picked by its position.
SLUG_UV = {"side": [7, 8, 13, 12], "skirt": [3, 11, 13, 13], "under": [4, 12, 12, 14], "top": [7, 6, 13, 10], "core": [6, 6, 9, 9]}
# (from, to, uv of the sides, uv of the top, light the faces give off)
# (from, to, uv of the sides, uv of the top, light the faces give off): three layers, each two crossed boxes so the
# corners come out round, the second a shade lower so no two tops lie in one plane; a glowing core and two lumps on top
SLUG_PARTS = [([3, 0, 5], [13, 2, 11], "skirt", "side", 9), ([5, 0, 3], [11, 1.75, 13], "skirt", "side", 9),
              ([4, 0, 4], [12, 1.5, 12], "skirt", "side", 9),
              ([4, 2, 5], [12, 4, 11], "side", "top", 10), ([5, 2, 4], [11, 3.75, 12], "side", "top", 10),
              ([5, 4, 6], [11, 5.5, 10], "side", "top", 11), ([6, 4, 5], [10, 5.25, 11], "side", "top", 11),
              ([6.5, 5.5, 6.5], [9.5, 6.5, 9.5], "core", "core", 15),
              ([8.75, 5.25, 5.75], [10, 6.25, 7], "top", "core", 13), ([5.75, 5.25, 8.75], [7, 6, 10], "top", "core", 13)]
SLUG_MODELS = set()
for cid in ("blue_power_slug", "yellow_power_slug", "purple_power_slug"):
    bid = f"{cid}_block"
    SLUG_MODELS.add(bid)
    write(f"{A}/blockstates/{bid}.json", {"variants": {"": [({"model": f"siftec:block/{bid}", "y": y} if y else {"model": f"siftec:block/{bid}"}) for y in (0, 90, 180, 270)]}})
    elements = []
    for frm, to, side, top, glow in SLUG_PARTS:
        faces = {f: {"texture": "#slug", "uv": SLUG_UV[side]} for f in ("north", "south", "east", "west")}
        faces["up"] = {"texture": "#slug", "uv": SLUG_UV[top]}
        faces["down"] = {"texture": "#slug", "uv": SLUG_UV["under"]}
        elements.append({"from": frm, "to": to, "light_emission": glow, "faces": faces})
    tex = f"siftec:block/{bid}_side"
    write(f"{A}/models/block/{bid}.json", {"parent": "minecraft:block/block", "ambientocclusion": False,
          "textures": {"particle": tex, "slug": tex}, "elements": elements})
# ---- Converter and Particle Accelerator
data["processors"] = {}
names_table = content.names()
for pid, (pname, recipe_list) in content.PROCESSORS.items():
    out = []
    for items, fluid_in, result, seconds in recipe_list:
        r = {"in": [{"item": i, "count": c} for i, c in content.parse_cost(items)], "seconds": seconds}
        if fluid_in: r["fluid_in"] = {"fluid": recipes.FLUID_IDS[fluid_in[0]], "mb": fluid_in[1]}
        if isinstance(result, tuple): r["fluid_out"] = {"fluid": recipes.FLUID_IDS[result[0]], "mb": result[1]}
        else:
            (rid, rc), = content.parse_cost(result)
            r["out"] = {"item": rid, "count": rc}
        out.append(r)
    for aid, aname, machine, items, fluid_in, result, seconds, text in content.ALT_PROCESSORS:
        if machine != pid: continue
        (rid, rc), = content.parse_cost(result)
        r = {"in": [{"item": i, "count": c} for i, c in content.parse_cost(items)], "seconds": seconds, "out": {"item": rid, "count": rc}, "alt": "alt_" + aid}
        if fluid_in: r["fluid_in"] = {"fluid": recipes.FLUID_IDS[fluid_in[0]], "mb": fluid_in[1]}
        out.append(r)
    data["processors"][pid] = out
    lang[f"block.siftec.{pid}"] = pname
    casing = "create:block/brass_casing" if pid == "converter" else "create:block/railway_casing"
    write(f"{A}/blockstates/{pid}.json", {"variants": {"": {"model": f"siftec:block/{pid}"}}})
    write(f"{A}/models/block/{pid}.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": "create:block/gearbox_top", "side": casing, "bottom": casing}})
    item_def(pid, f"siftec:block/{pid}")
    write(f"{D}/loot_table/blocks/{pid}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"siftec:{pid}"}]}]})
lang.update({"siftec.processor.selected": "Making: %s", "siftec.processor.fluid": "%s mB of %s"})

# ---- AWESOME Sink and Shop
data["sink_points"] = {names_table[n]: p for n, p in content.SINK_POINTS.items()}
data["shop"] = [{"item": i, "count": c, "price": p} for i, c, p in content.SHOP]
for i, c, p in content.SHOP: check(i, "shop")
lang.update({"block.siftec.awesome_sink": "AWESOME Sink", "block.siftec.awesome_shop": "AWESOME Shop", "siftec.sink.points": "AWESOME points: %s",
             "siftec.shop.title": "AWESOME Shop: %s points", "siftec.shop.price": "%s points", "siftec.shop.poor": "Not enough points",
             "siftec.sift.enter": "Enter The Sift", "siftec.sift.missing": "The Sift is not installed on this server"})
for b, top in (("awesome_sink", "minecraft:block/hopper_top"), ("awesome_shop", "minecraft:block/barrel_top")):
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": top, "side": "create:block/andesite_casing", "bottom": "create:block/andesite_casing"}})
    item_def(b, f"siftec:block/{b}")
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}]}]})
for i in content.ALIAS.values(): check(i, "alias")
for i, cost in recipes.WORKSHOP:
    check(i, "workshop")
    for c, n in content.parse_cost(cost): check(c, "workshop " + i)
# ---- fluids
data["fluids"] = []
for fid, name, colour in content.FLUIDS:
    data["fluids"].append({"id": fid, "color": colour})
    lang[f"block.siftec.{fid}"] = name
    lang[f"fluid.siftec.{fid}"] = name
    lang[f"item.siftec.{fid}_bucket"] = name + " Bucket"
    write(f"{A}/blockstates/{fid}.json", {"variants": {"": {"model": "siftec:block/fluid"}}})
    # the game's own empty bucket, with the liquid in its opening tinted the fluid's colour
    write(f"{A}/models/item/{fid}_bucket.json", {"parent": "minecraft:item/generated", "textures": {
        "layer0": "minecraft:item/bucket", "layer1": "siftec:item/fluid_in_bucket"}})
    write(f"{A}/items/{fid}_bucket.json", {"model": {"type": "minecraft:model", "model": f"siftec:item/{fid}_bucket",
          "tints": [{"type": "minecraft:constant", "value": -1}, {"type": "minecraft:constant", "value": colour}]}})
write(f"{A}/models/block/fluid.json", {"textures": {"particle": "minecraft:block/water_still"}})

# ---- recipes
import shutil
shutil.rmtree(f"{D}/recipe", ignore_errors=True)
part_ids = {"siftec:" + pid for pid, _, _, _ in content.PARTS}
for path, body in recipes.build().items():
    if body["type"] == "create:sequenced_assembly" and body["transitional_item"]["id"] not in part_ids:
        raise SystemExit(f"{path}: its half-built item {body['transitional_item']['id']} is not in content.PARTS")
    # a recipe that names another mod's item or fluid only loads when that mod is installed
    text = json.dumps(body)
    mods = [m for m in ("cgs", "createdieselgenerators", "create_hypertube", "farmersdelight") if f'"{m}:' in text or f'"#{m}:' in text]
    if "#c:tools/knife" in text or path.split("/")[-1].startswith("kitchen_"):
        mods = sorted(set(mods) | {"farmersdelight"})
    if mods:
        body = {"fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": mods}], **body}
    write(f"{D}/recipe/{path}.json", body)
# ---- which milestone a machine's company needs before the machine will run each recipe
lock_of = {}
for m in data["milestones"] + data["milestones_mam"]:
    for i in m["items"]:
        lock_of.setdefault(i, m["id"])
data["recipe_locks"] = {}
for path, body in recipes.build().items():
    made = list(body.get("results", [])) + list(body.get("fluid_results", []))
    if "result" in body: made.append(body["result"])
    for r in made:
        rid = r if isinstance(r, str) else r.get("id", "")
        mid = lock_of.get(rid) or lock_of.get(rid + "_bucket")
        if mid:
            # the steps of a sequenced assembly get ids of their own, built from the result's name
            key = "seq:" + rid.split(":")[1] if body["type"] == "create:sequenced_assembly" else "siftec:" + path
            data["recipe_locks"][key] = mid
            break
# recipes that need a milestone their result does not show (gunpowder, lava, TNT, Biomass from mob drops, acids)
for path, mid in recipes.LOCKED.items():
    if mid not in by_id:
        raise SystemExit(f"recipes.LOCKED: no milestone {mid} for {path}")
    data["recipe_locks"]["siftec:" + path] = mid
# Farmer's Delight recipes moved onto Create machines need the Automated Kitchen research
kitchen_node = next(m["id"] for m in data["milestones_mam"] if "food:kitchen" in m["tokens"])
for path in recipes.KITCHEN:
    data["recipe_locks"]["siftec:" + path] = kitchen_node
print("kitchen recipes:", len(recipes.KITCHEN), "locked to", kitchen_node)
# ---- alternates: each has its own lock, and is only offered once everything it uses is unlocked
C = "create:"
MACHINE_OF = {"create:cutting": [C + "mechanical_saw"], "create:deploying": [C + "deployer"], "create:mixing": [C + "mechanical_mixer", C + "basin"],
              "create:compacting": [C + "mechanical_press", C + "basin"], "create:splashing": [C + "encased_fan"], "create:haunting": [C + "encased_fan"],
              "create:mechanical_crafting": [C + "mechanical_crafter"], "create:pressing": [C + "mechanical_press"], "create:milling": [C + "millstone"],
              "create:crushing": [C + "crushing_wheel"], "create:filling": [C + "spout"], "create:sandpaper_polishing": [C + "sand_paper"],
              "create:sequenced_assembly": [C + "deployer", C + "mechanical_press"], "create:item_application": [],
              "createdieselgenerators:bulk_fermenting": ["createdieselgenerators:bulk_fermenter"],
              "minecraft:smelting": [], "minecraft:blasting": [], "minecraft:crafting_shaped": [], "minecraft:crafting_shapeless": []}
built = recipes.build()
# Where the things no milestone locks come from, so an alternate is only offered once its company can get every
# input: fluids and crushed ore from the machines that make them, tags from what is in them, and anything one of
# the pack's own recipes makes from that recipe's inputs, machine and lock.
SOURCES = {"createdieselgenerators:crude_oil": ["createdieselgenerators:pumpjack_crank", "createdieselgenerators:pumpjack_bearing"],
           "createdieselgenerators:diesel": ["createdieselgenerators:distillation_controller", "createdieselgenerators:crude_oil"],
           "createdieselgenerators:biodiesel": ["siftec:solid_biofuel", "siftec:recipe/mixing/liquid_biofuel"],
           "siftec:nitrogen": ["siftec:resource_well_extractor"], "siftec:sam": ["siftec:portable_miner"], "siftec:raw_bauxite": ["siftec:portable_miner"],
           "#c:ingots/steel": ["cgs:steel_ingot"]}
for ore in ("iron", "copper", "zinc", "gold"):
    SOURCES[f"create:crushed_raw_{ore}"] = [C + "crushing_wheel"]


def ids_in(node, found):
    if isinstance(node, str):
        if ":" in node and " " not in node: found.add(node)
    elif isinstance(node, dict):
        for k, v in node.items():
            if k != "type": ids_in(v, found)
    elif isinstance(node, list):
        for v in node: ids_in(v, found)


def inputs_of(body):
    """Everything a recipe needs: its ingredients and fluids, its machines, heat, and its own lock."""
    used = set()
    for k, v in body.items():
        if k not in ("type", "results", "result", "fluid_results", "fabric:load_conditions", "transitional_item"): ids_in(v, used)
    used.update(MACHINE_OF[body["type"]])
    if body.get("heat_requirement"): used.add(C + "blaze_burner")
    return used


alt_paths = {p for info in recipes.ALTS.values() for p in info["paths"]}
for path, body in built.items():
    if path in alt_paths: continue
    made = [r if isinstance(r, str) else r.get("id", "") for r in list(body.get("results", [])) + list(body.get("fluid_results", [])) + ([body["result"]] if "result" in body else [])]
    for o in made:
        if o and o not in lock_of and o not in SOURCES and not o.startswith("minecraft:"):
            SOURCES[o] = sorted(inputs_of(body)) + ["siftec:recipe/" + path]
for path, mid in data["recipe_locks"].items():
    lock_of["siftec:recipe/" + path.split(":", 1)[1]] = mid


def needs_of(things, seen=None):
    seen = set() if seen is None else seen
    out = []
    for t in sorted(things):
        if t in seen: continue
        seen.add(t)
        mid = lock_of.get(t) or lock_of.get(t + "_bucket")
        found = [mid] if mid else needs_of(SOURCES.get(t, []), seen)
        for f in found:
            if f not in out: out.append(f)
    return out



data["alternates"] = []
for aid, info in recipes.ALTS.items():
    used = set()
    for path in info["paths"]:
        used |= inputs_of(built[path])
        data["recipe_locks"]["siftec:" + path] = "alt_" + aid
    data["alternates"].append({"id": "alt_" + aid, "requires": needs_of(used)})
    lang[f"siftec.alt.alt_{aid}"] = info["name"]
    lang[f"siftec.alt.alt_{aid}.text"] = info["text"]
for aid, aname, machine, items, fluid_in, result, seconds, text in content.ALT_PROCESSORS:
    used = {i for i, c in content.parse_cost(items)} | {i for i, c in content.parse_cost(result)} | {"siftec:" + machine}
    if fluid_in: used.add(recipes.FLUID_IDS[fluid_in[0]])
    data["alternates"].append({"id": "alt_" + aid, "requires": needs_of(used)})
    lang[f"siftec.alt.alt_{aid}"] = aname
    lang[f"siftec.alt.alt_{aid}.text"] = text
print("alternates:", len(data["alternates"]), "; with nothing required:", [a["id"] for a in data["alternates"] if not a["requires"]])
print("recipes locked to a milestone:", len(data["recipe_locks"]), "of", len(recipes.build()))
data["removed_recipes"] = recipes.REMOVED
data["workshop"] = [{"item": i, "cost": [{"item": c, "count": n} for c, n in content.parse_cost(cost)]} for i, cost in recipes.WORKSHOP]
lang.update({"block.siftec.equipment_workshop": "Equipment Workshop", "siftec.workshop.title": "Equipment Workshop",
             "siftec.workshop.click": "Click to build from the parts in your inventory", "siftec.workshop.missing": "You are missing parts for that",
             "siftec.workshop.built": "Built %s",
             "siftec.workshop.leftover": "The Workshop gave you back the parts its old automatic mode was holding"})
write(f"{A}/blockstates/equipment_workshop.json", {"variants": {"": {"model": "siftec:block/equipment_workshop"}}})
write(f"{A}/models/block/equipment_workshop.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "minecraft:block/smithing_table_top", "side": "create:block/andesite_casing", "bottom": "create:block/andesite_casing"}})
item_def("equipment_workshop", "siftec:block/equipment_workshop")
write(f"{D}/loot_table/blocks/equipment_workshop.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
      "entries": [{"type": "minecraft:item", "name": "siftec:equipment_workshop"}]}]})
if UNKNOWN:
    raise SystemExit("unknown item ids (not items of the game or of the pack's mods):\n  " + "\n  ".join(UNKNOWN))
write(os.path.join(ROOT, "siftec_content.json"), data)

# ---- HUB and Wormhole Gateway
lang.update({
    "block.siftec.hub": "HUB", "block.siftec.wormhole_gateway": "Wormhole Gateway",
    "siftec.hub.title": "HUB: %s", "siftec.gateway.title": "Wormhole Gateway: %s",
    "siftec.hub.tier": "Tier %s", "siftec.hub.tier.locked": "Locked: deliver %s at the Wormhole Gateway",
    "siftec.hub.tier.locked0": "Locked: finish Tier 0 first", "siftec.hub.done": "Completed",
    "siftec.hub.cost": "%s: %s / %s", "siftec.hub.time": "HUB lock: %s", "siftec.hub.unlocks": "Unlocks: %s",
    "siftec.hub.click": "Click to deliver parts from your inventory", "siftec.hub.needs": "Needs: %s",
    "siftec.hub.locked_for": "HUB locked for %s", "siftec.hub.ready": "HUB ready",
    "siftec.hub.company": "%s (%s members, costs x%s)", "siftec.hub.not_yours": "This belongs to %s",
    "siftec.hub.delivered": "Delivered %s parts", "siftec.hub.nothing": "You are not carrying any of the parts it needs",
    "siftec.hub.complete": "%s completed %s", "siftec.hub.busy": "The HUB is locked for another %s",
    "siftec.hub.at_gateway": "Wormhole phases are delivered at the Wormhole Gateway",
    "siftec.hub.at_hub": "Milestones are delivered at the HUB",
    "siftec.hub.now_active": "%s is active: pay by hand, or by belt and funnel into the HUB",
    # the HUB screen, laid out like the old Tweaker HUB screen
    "siftec.hubui.wormhole": "Wormhole", "siftec.hubui.building": "HUB building", "siftec.hubui.building_for": "HUB building for Tier %s",
    "siftec.hubui.set_active": "Set as active", "siftec.hubui.pay": "Pay from inventory", "siftec.hubui.rescan": "Rescan",
    "siftec.hubui.loading": "Connecting to the HUB...", "siftec.hubui.tier_locked": "Tier %s is locked",
    "siftec.hubui.complete": "Complete", "siftec.hubui.build_first": "Build the HUB up to Tier %s first",
    "siftec.hubui.hint": "Set it active, then pay by hand or feed the HUB",
    "siftec.hubui.active": "Pay by hand, or feed the HUB by belt or funnel",
    "siftec.hubui.hint_gateway": "Set it active, then pay by hand or feed the Gateway",
    "siftec.hubui.active_gateway": "Pay by hand, or feed the Gateway by belt or funnel",
    "siftec.hubui.any": "Any %s", "siftec.hubui.lock_after": "Locks the HUB for %s when done",
    "siftec.hubui.phases": "Wormhole: %s of %s phases delivered",
    "siftec.hubui.blocks": "HUB blocks: %s / %s", "siftec.hubui.shelter": "Walls %s/2   Roof %s",
    "siftec.hubui.half": "= half a block each", "siftec.hubui.half_tip": "Slabs, doors and trapdoors count as half a block",
    "siftec.hubui.newest": "Needs at least %s of these; the rest can be any mix",
    "siftec.hubui.exempt": "Creative: the building rule is skipped",
    "siftec.hubui.not_marked": "Mark it: HUB Planner on two corners, then on the HUB",
    "siftec.hubui.too_big": "The marked area is too big: mark it again",
    "siftec.hubui.built": "Built: good for Tier %s", "siftec.hubui.build_more": "Build it up, then Rescan",
    "siftec.hubui.status": "%s  |  Members %s  |  Costs x%s", "siftec.hubui.status_locked": "%s  |  Locked %s",
    "siftec.hubui.status_built": "Building: Tier %s", "siftec.hubui.status_none": "Building: no tier yet",
    "siftec.hubui.status_exempt": "Building: any tier",
    "siftec.lock.item": "Locked: needs %s",
    "siftec.milestone.disabled": "nothing: it is switched off in this pack",
    "siftec.company.default_name": "%s's Company", "siftec.company.info": "%s: %s",
    "siftec.company.renamed": "Company renamed to %s", "siftec.company.invited": "Invited %s. They join with /company accept %s",
    "siftec.company.invite": "%s invited you to %s. Join with /company accept %s (you leave your own company)",
    "siftec.company.no_invite": "You have no invite from that player", "siftec.company.joined": "%s joined %s",
    "siftec.company.left": "You left and started %s",
    "siftec.portal.gone": "That portal is not there any more", "siftec.portal.blocked": "Something is standing in that portal's way",
    "siftec.company.alone": "You are the only one in this company. Accept an invite to move to another",
})
for b, top in (("hub", "create:block/andesite_casing"), ("wormhole_gateway", "create:block/railway_casing")):
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": "minecraft:block/lodestone_top", "side": top, "bottom": top}})
    item_def(b, f"siftec:block/{b}")
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
          "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}]}]})

write(f"{A}/lang/en_us.json", dict(sorted(lang.items())))

# miners drop themselves
for b in ("steel_casing", "blueprint_designer", "blueprint_designer_mk3", "drone_port", "main_portal", "satellite_portal", "landing_pad", "radar_tower", "furnace_engine", "hub_engine", "geyser_engine", "claim_marker", "portable_miner", "miner_mk1", "miner_mk2", "miner_mk3", "resource_well_extractor", "dimensional_depot", "power_pole", "power_tower", "power_storage", "speed_governor"):
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
          "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}],
          "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
# pickaxe is the right tool for the miners
# a Pumpjack hole only counts an Oil Well as the bottom of its pipe
write(os.path.join(ROOT, "data", "createdieselgenerators", "tags", "block", "oil_deposit.json"), {"replace": True, "values": ["siftec:oil_well"]})
write(os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json"),
      {"replace": False, "values": ["siftec:portable_miner", "siftec:miner_mk1", "siftec:miner_mk2", "siftec:miner_mk3", "siftec:miner_top", "siftec:resource_well_extractor", "siftec:dimensional_depot", "siftec:power_pole", "siftec:power_tower", "siftec:power_storage", "siftec:speed_governor", "siftec:furnace_engine", "siftec:hub_engine", "siftec:landing_pad", "siftec:radar_tower", "siftec:steel_casing", "siftec:blueprint_designer", "siftec:blueprint_designer_mk3", "siftec:drone_port", "siftec:main_portal", "siftec:satellite_portal", "siftec:mam", "siftec:claim_marker", "siftec:geyser_engine", "siftec:converter", "siftec:particle_accelerator", "siftec:awesome_sink", "siftec:awesome_shop", "siftec:hub", "siftec:wormhole_gateway", "siftec:equipment_workshop"]})

# ---------------------------------------------------------------------------------------------------
# The mod's own art, cut from the sprite sheets by tools/import_art.py. Anything with a texture file
# drops its borrowed stand-in; anything without one keeps it.
TEX = os.path.join(A, "textures")
def has(kind, name):
    return os.path.exists(os.path.join(TEX, kind, name + ".png"))

FRONTED = {"hub", "mam", "awesome_shop", "resource_well_extractor", "converter",
           "particle_accelerator", "furnace_engine", "hub_engine", "geyser_engine", "power_storage", "crash_site_block"}
# drawn as a post or a tripod on a clear background: shown as two crossed planes, like a flower
CROSSED = {"portable_miner", "power_pole", "power_tower"}
NODE_IDS = {f"{n}_node" for n in NODES} | {"oil_well"}
# blocks that turn to face whoever placed them; the Crash Site Pod is set down by world generation, so it does not
TURNS = FRONTED - {"crash_site_block"}
# these share a block class with one that turns, so they have the same facing, though nothing on them shows it
for bid in ("wormhole_gateway", "awesome_sink"):
    write(f"{A}/blockstates/{bid}.json", {"variants": {f"facing={d}": {"model": f"siftec:block/{bid}"} for d in ("north", "east", "south", "west")}})
for bid in sorted({f[:-len("_side.png")] for f in os.listdir(os.path.join(TEX, "block")) if f.endswith("_side.png")} if os.path.isdir(os.path.join(TEX, "block")) else []):
    if bid in SLUG_MODELS:
        continue  # the slugs have their own model (above)
    if bid == "speed_governor" or bid in MINERS:
        continue  # the governor keeps Create's gearshift model; the miners have their own (below)
    t = lambda face: f"siftec:block/{bid}_{face}"
    if bid in CROSSED:
        write(f"{A}/models/block/{bid}.json", {"parent": "minecraft:block/cross", "textures": {"cross": t("side"), "particle": t("side")}})
        write(f"{A}/models/item/{bid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": t("side")}})
        item_def(bid, f"siftec:item/{bid}")
    elif bid == "claim_marker":
        # a post six pixels wide; the faces take the middle of the drawn textures
        write(f"{A}/models/block/{bid}.json", {"parent": "minecraft:block/block", "textures": {"particle": t("side"), "side": t("side"), "top": t("top")},
              "elements": [{"from": [5, 0, 5], "to": [11, 16, 11], "faces": {
                  "north": {"texture": "#side"}, "south": {"texture": "#side"}, "east": {"texture": "#side"}, "west": {"texture": "#side"},
                  "up": {"texture": "#top"}, "down": {"texture": "#side"}}}]})
    elif bid in FRONTED:
        if bid in TURNS:
            write(f"{A}/blockstates/{bid}.json", {"variants": {f"facing={d}": ({"model": f"siftec:block/{bid}", "y": y} if y else {"model": f"siftec:block/{bid}"})
                                                                for d, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
        write(f"{A}/models/block/{bid}.json", {"parent": "minecraft:block/cube", "textures": {"particle": t("side"),
              "north": t("front"), "south": t("side"), "east": t("side"), "west": t("side"), "up": t("top"), "down": t("side")}})
    else:
        bottom = "siftec:block/node_rock_side" if bid in NODE_IDS else t("side")
        model = {"parent": "minecraft:block/cube_bottom_top", "textures": {"top": t("top"), "side": t("side"), "bottom": bottom}}
        write(f"{A}/models/block/{bid}.json", model)
        if bid == "oil_well":
            continue  # its blockstate shows the oil node's mound; the drawn texture stays unused
for f in sorted(os.listdir(os.path.join(TEX, "item"))) if os.path.isdir(os.path.join(TEX, "item")) else []:
    iid = f[:-4]
    if iid == "fluid_in_bucket":
        continue
    write(f"{A}/models/item/{iid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"siftec:item/{iid}"}})
    item_def(iid, f"siftec:item/{iid}")

# Create's dough replaces Farmer's Delight's (whose recipes are removed and whose dough is switched off). A tag can
# only lose an entry by being replaced, which works when this mod's data loads after Farmer's Delight's; if it loads
# first, the dough is still uncraftable and hidden, so nothing is lost.
write(os.path.join(ROOT, "data", "c", "tags", "item", "foods", "dough", "wheat.json"), {"replace": True, "values": ["create:dough"]})

# ---- whatever an earlier run wrote that this one did not (a removed item's model, say) is deleted; these folders
# hold only generated files. Textures are tools/import_art.py's.
for folder in (f"{A}/items", f"{A}/models", f"{A}/blockstates", f"{D}/loot_table", f"{D}/context_int_provider", f"{D}/recipe"):
    for dirpath, dirs, files in os.walk(folder):
        for f in files:
            path = os.path.normpath(os.path.join(dirpath, f))
            if f.endswith(".json") and path not in WRITTEN:
                print("removed stale", os.path.relpath(path, ROOT))
                os.remove(path)
