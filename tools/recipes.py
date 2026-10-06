"""Every recipe the pack adds, written against the Recipes tab of the design doc.
build() returns {path under data/siftec/recipe: json}. Amounts of fluid are in mB here."""
import content

DROPLETS = 81  # Create Fly counts fluid in droplets: 81 per mB

FLUID_IDS = {
    "water": "minecraft:water", "lava": "minecraft:lava",
    "crude oil": "createdieselgenerators:crude_oil", "diesel": "createdieselgenerators:diesel",
    "biodiesel": "createdieselgenerators:biodiesel",
}
for fid, name, _ in content.FLUIDS:
    FLUID_IDS[name.lower()] = "siftec:" + fid

NAMES = content.names()
OUT = {}


def item(name):
    """A part name, a full id, or a #tag."""
    if name.startswith("#") or ":" in name:
        return name
    if name not in NAMES:
        raise SystemExit(f"recipes: unknown part {name!r}")
    return NAMES[name]


def res(name, n=1, chance=None):
    r = {"id": item(name)}
    if n != 1:
        r["count"] = n
    if chance is not None:
        r["chance"] = chance
    return r


def fl_in(name, mb):
    return {"type": "fluid_stack", "amount": mb * DROPLETS, "fluid": FLUID_IDS[name]}


def fl_out(name, mb):
    return {"id": FLUID_IDS[name], "amount": mb * DROPLETS}


def slug(name):
    return item(name).split(":")[-1].replace("/", "_")


def add(kind, name, body):
    path = f"{kind}/{name}"
    if path in OUT:
        raise SystemExit(f"recipes: duplicate {path}")
    OUT[path] = body


def simple(kind, inp, out, n=1, time=None, name=None):
    body = {"type": f"create:{kind}", "ingredient": item(inp), "results": [res(out, n)]}
    if time:
        body["processing_time"] = time
    add(kind, name or slug(out), body)


def press(inp, out, n=1, name=None): simple("pressing", inp, out, n, name=name)
def saw(inp, out, n=1, name=None): simple("cutting", inp, out, n, 100, name)
def mill(inp, out, n=1, name=None, chance=None):
    add("milling", name or slug(out), {"type": "create:milling", "ingredient": item(inp), "processing_time": 100, "results": [res(out, n, chance)]})
def crush(inp, out, n=1, name=None): simple("crushing", inp, out, n, 200, name)
def wash(inp, out, n=1, name=None): simple("splashing", inp, out, n, name=name)
def haunt(inp, out, n=1, name=None): simple("haunting", inp, out, n, name=name)


def basin(kind, name, items=(), fluids=(), results=(), fluid_results=(), heated=False):
    body = {"type": f"create:{kind}"}
    if heated:
        body["heat_requirement"] = "heated"
    if items:
        body["ingredients"] = [item(i) for i in items]
    if fluids:
        body["fluid_ingredients"] = [fl_in(f, mb) for f, mb in fluids]
    if results:
        body["results"] = [res(*r) if isinstance(r, tuple) else res(r) for r in results]
    if fluid_results:
        body["fluid_results"] = [fl_out(f, mb) for f, mb in fluid_results]
    add(kind, name, body)


def mix(name, **kw): basin("mixing", name, **kw)
def compact(name, **kw): basin("compacting", name, **kw)


def deploy(base, held, out, n=1, name=None):
    add("deploying", name or slug(out), {"type": "create:deploying", "target": item(base), "ingredient": item(held), "results": [res(out, n)]})


def crafter(out, parts, n=1, name=None):
    """parts: [(count, name)], at most nine items, laid out row by row."""
    keys, cells = {}, []
    for i, (count, part) in enumerate(parts):
        letter = "ABCDEFGHI"[i]
        keys[letter] = item(part)
        cells += [letter] * count
    if len(cells) > 9:
        raise SystemExit(f"recipes: {out} needs more than nine items")
    width = 3 if len(cells) > 4 else 2 if len(cells) > 1 else 1
    rows = ["".join(cells[i:i + width]).ljust(width) for i in range(0, len(cells), width)]
    add("mechanical_crafting", name or slug(out), {"type": "create:mechanical_crafting", "key": keys, "pattern": rows,
        "result": {"count": n, "id": item(out)}})


def spout(inp, fluid, mb, out, name=None):
    add("filling", name or slug(out), {"type": "create:filling", "ingredient": item(inp), "fluid_ingredient": fl_in(fluid, mb), "result": res(out)})


def smelt(inp, out, name=None):
    for kind, time in (("smelting", 200), ("blasting", 100)):
        add(kind, name or slug(out), {"type": f"minecraft:{kind}", "category": "misc", "cookingtime": time, "experience": 0.1,
            "ingredient": item(inp), "result": {"count": 1, "id": item(out)}})


def hand(out, parts, n=1, name=None):
    """A crafting-table version of a machine recipe, for before the machine is unlocked."""
    add("crafting", name or slug(out), {"type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [item(p) for count, p in parts for _ in range(count)], "result": {"count": n, "id": item(out)}})


def shaped(out, pattern, key, n=1, name=None):
    add("crafting", name or slug(out), {"type": "minecraft:crafting_shaped", "category": "misc",
        "key": {k: item(v) for k, v in key.items()}, "pattern": pattern, "result": {"count": n, "id": item(out)}})


def sequence(base, out, steps, loops, name=None):
    """steps: part names applied by Deployer in order; "press" for a pressing step."""
    seq = []
    for step in steps:
        if step == "press":
            seq.append({"type": "create:pressing", "ingredient": "$ingredient", "results": ["$result"]})
        else:
            seq.append({"type": "create:deploying", "target": "$ingredient", "ingredient": item(step), "results": ["$result"]})
    add("sequenced_assembly", name or slug(out), {"type": "create:sequenced_assembly", "ingredient": item(base),
        "transitional_item": {"id": "siftec:incomplete_" + slug(out)}, "result": {"id": item(out)}, "loops": loops, "sequence": seq})


def build():
    OUT.clear()
    # ---- tiers 0 to 2 -------------------------------------------------------------------------
    saw("Iron Ingot", "Iron Rod")
    saw("Copper Ingot", "Wire", 2)
    compact("cable", items=["Wire", "Wire"], results=["Cable"])
    compact("concrete", items=["Limestone"] * 3, results=["Concrete"])
    saw("Iron Rod", "Screw", 4)
    deploy("Iron Sheet", "Screw", "Reinforced Iron Plate")
    mill("#minecraft:leaves", "Biomass", name="biomass_from_leaves", chance=0.5)
    mill("#minecraft:logs", "Biomass", 4, name="biomass_from_logs")
    for crop in ("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "kelp", "sugar_cane", "cactus", "short_grass"):
        mill("minecraft:" + crop, "Biomass", name="biomass_from_" + crop, chance=0.25)
    deploy("Iron Rod", "Screw", "Rotor")
    deploy("Reinforced Iron Plate", "Iron Rod", "Modular Frame")
    deploy("Reinforced Iron Plate", "Rotor", "Smart Plating")
    compact("solid_biofuel", items=["Biomass", "Biomass"], results=["Solid Biofuel"])
    # by hand, until the machines unlock. Sheets and rods both start from ingots, so they are shaped.
    shaped("Iron Sheet", ["II"], {"I": "Iron Ingot"}, 2, name="iron_sheet_by_hand")
    shaped("Iron Rod", ["I", "I"], {"I": "Iron Ingot"}, 2, name="iron_rod_by_hand")
    shaped("Copper Sheet", ["II"], {"I": "Copper Ingot"}, 2, name="copper_sheet_by_hand")
    shaped("Wire", ["I", "I"], {"I": "Copper Ingot"}, 4, name="wire_by_hand")
    hand("Cable", [(2, "Wire")], name="cable_by_hand")
    hand("Concrete", [(3, "Limestone")], name="concrete_by_hand")
    hand("Screw", [(1, "Iron Rod")], 4, name="screw_by_hand")
    hand("Reinforced Iron Plate", [(1, "Iron Sheet"), (1, "Screw")], name="reinforced_iron_plate_by_hand")
    hand("Rotor", [(1, "Iron Rod"), (1, "Screw")], name="rotor_by_hand")
    hand("Modular Frame", [(1, "Reinforced Iron Plate"), (1, "Iron Rod")], name="modular_frame_by_hand")
    hand("Smart Plating", [(1, "Reinforced Iron Plate"), (1, "Rotor")], name="smart_plating_by_hand")
    hand("Solid Biofuel", [(2, "Biomass")], name="solid_biofuel_by_hand")
    shaped("siftec:equipment_workshop", ["SSS", "RRR", "R R"], {"S": "Iron Sheet", "R": "Iron Rod"}, name="equipment_workshop")

    # ---- tiers 3 and 4 ------------------------------------------------------------------------
    mix("steel_ingot", items=["Raw Iron", "Coal"], results=["Steel Ingot"], heated=True)
    press("Steel Ingot", "Steel Beam")
    saw("Steel Ingot", "Steel Pipe")
    deploy("Modular Frame", "Steel Beam", "Versatile Framework")
    deploy("Steel Beam", "Concrete", "Encased Industrial Beam")
    deploy("Steel Pipe", "Wire", "Stator")
    deploy("Rotor", "Stator", "Motor")
    deploy("Stator", "Cable", "Automated Wiring")

    # ---- tiers 5 and 6 ------------------------------------------------------------------------
    mix("plastic", fluids=[("crude oil", 250)], results=[("Plastic", 2)], fluid_results=[("heavy oil residue", 100)], heated=True)
    mix("rubber", fluids=[("crude oil", 250)], results=[("Rubber", 2)], fluid_results=[("heavy oil residue", 150)], heated=True)
    compact("petroleum_coke", fluids=[("heavy oil residue", 250)], results=[("Petroleum Coke", 3)], heated=True)
    deploy("Copper Sheet", "Plastic", "Circuit Board")
    press("Plastic", "Empty Canister", 2)
    mix("liquid_biofuel", items=["Solid Biofuel"], fluids=[("water", 250)], fluid_results=[("biodiesel", 250)], heated=True)
    crafter("Computer", [(2, "Circuit Board"), (2, "Cable"), (4, "Plastic")])
    crafter("Heavy Modular Frame", [(2, "Modular Frame"), (3, "Steel Pipe"), (2, "Encased Industrial Beam"), (2, "Screw")])
    crafter("Modular Engine", [(2, "Motor"), (4, "Rubber"), (2, "Smart Plating")])
    crafter("Adaptive Control Unit", [(3, "Automated Wiring"), (3, "Circuit Board"), (1, "Heavy Modular Frame"), (2, "Computer")])

    # ---- tier 7 -------------------------------------------------------------------------------
    crush("siftec:raw_bauxite", "Crushed Bauxite")
    mix("alumina_solution", items=["Crushed Bauxite"], fluids=[("water", 250)], results=["Silica"], fluid_results=[("alumina solution", 250)], heated=True)
    mix("aluminum_scrap", items=["Coal"], fluids=[("alumina solution", 250)], results=[("Aluminum Scrap", 3)], heated=True)
    mix("aluminum_ingot", items=["Aluminum Scrap"] * 3 + ["Silica"] * 2, results=[("Aluminum Ingot", 2)], heated=True)
    deploy("Aluminum Ingot", "Copper Ingot", "Alclad Aluminum Sheet")
    press("Aluminum Ingot", "Aluminum Casing")
    crafter("Radio Control Unit", [(4, "Aluminum Casing"), (1, "Crystal Oscillator"), (1, "Computer")])
    crafter("Iodine Infused Filter", [(1, "Gas Filter"), (2, "Quickwire"), (1, "Aluminum Casing")])

    # ---- tier 8 (the uranium and plutonium chain went with Create Nuclear) ----------------------
    mix("sulfuric_acid", items=["Sulfur"], fluids=[("water", 250)], fluid_results=[("sulfuric acid", 250)], heated=True)
    crafter("Supercomputer", [(2, "Computer"), (2, "AI Limiter"), (2, "High-Speed Connector"), (3, "Plastic")])
    deploy("Adaptive Control Unit", "Supercomputer", "Assembly Director System")
    deploy("Stator", "AI Limiter", "Electromagnetic Control Rod")
    deploy("Versatile Framework", "Electromagnetic Control Rod", "Magnetic Field Generator")
    saw("Aluminum Ingot", "Empty Fluid Tank")  # the Press already makes Aluminum Casing from an ingot
    spout("Empty Fluid Tank", "nitrogen", 250, "Packaged Nitrogen Gas")
    deploy("Alclad Aluminum Sheet", "Copper Sheet", "Heat Sink")
    mix("cooling_system", items=["Heat Sink", "Rubber"], fluids=[("water", 250), ("nitrogen", 250)], results=["Cooling System"], heated=True)
    mix("fused_modular_frame", items=["Heavy Modular Frame"] + ["Aluminum Casing"] * 4, fluids=[("nitrogen", 250)], results=["Fused Modular Frame"], heated=True)
    crafter("Turbo Motor", [(2, "Cooling System"), (1, "Radio Control Unit"), (2, "Motor"), (4, "Rubber")])
    crafter("Thermal Propulsion Rocket", [(3, "Modular Engine"), (2, "Turbo Motor"), (3, "Cooling System"), (1, "Fused Modular Frame")])
    mix("nitric_acid", items=["Iron Sheet"], fluids=[("nitrogen", 250), ("water", 250)], fluid_results=[("nitric acid", 250)], heated=True)
    crush("Copper Ingot", "Copper Powder")
    compact("pressure_conversion_cube", items=["Fused Modular Frame", "Radio Control Unit", "Radio Control Unit"], results=["Pressure Conversion Cube"])

    # ---- tier 9 (Converter and Particle Accelerator recipes come with those machines) ----------
    haunt("siftec:sam", "Reanimated SAM")
    crafter("SAM Fluctuator", [(3, "Reanimated SAM"), (3, "Wire"), (2, "Steel Pipe")])
    saw("Ficsite Ingot", "Ficsite Trigon", 3)
    mix("biochemical_sculptor", items=["Assembly Director System"] + ["Ficsite Trigon"] * 4, fluids=[("water", 250)], results=["Biochemical Sculptor"], heated=True)
    sequence("Crystal Oscillator", "Superposition Oscillator", ["Dark Matter Crystal", "Alclad Aluminum Sheet", "Excited Photonic Matter"], 2)
    sequence("Supercomputer", "Neural-Quantum Processor", ["Time Crystal", "Ficsite Trigon", "Excited Photonic Matter"], 3)
    sequence("Magnetic Field Generator", "AI Expansion Server", ["Neural-Quantum Processor", "Superposition Oscillator", "Excited Photonic Matter", "press"], 1)
    crafter("Singularity Cell", [(1, "Nuclear Pasta"), (2, "Dark Matter Crystal"), (3, "Iron Sheet"), (3, "Concrete")])
    crafter("Ballistic Warp Drive", [(1, "Thermal Propulsion Rocket"), (3, "Singularity Cell"), (2, "Superposition Oscillator"), (3, "Dark Matter Crystal")])

    # ---- MAM parts ----------------------------------------------------------------------------
    for drop in ("rotten_flesh", "bone", "string", "spider_eye"):
        mill("minecraft:" + drop, "Biomass", 2, name="biomass_from_" + drop)
        compact("dna_capsule_from_" + drop, items=["minecraft:" + drop] * 2, results=["DNA Capsule"])
    # brass, the pack's Caterium: crushed copper and zinc with a flux, then smelted
    for flux, n in (("minecraft:sand", 1), ("Coal", 2), ("Sulfur", 3)):
        mix("brass_blend_with_" + slug(flux), items=["create:crushed_raw_copper", "create:crushed_raw_zinc", flux], results=[("Brass Blend", n)])
    smelt("Brass Blend", "Brass Ingot", name="brass_ingot_from_blend")
    saw("Brass Ingot", "Quickwire", 4)
    deploy("Copper Sheet", "Quickwire", "AI Limiter")
    crafter("High-Speed Connector", [(5, "Quickwire"), (3, "Cable"), (1, "Circuit Board")])
    for mushroom in ("brown_mushroom", "red_mushroom"):
        deploy("Biomass", "minecraft:" + mushroom, "Fabric", name="fabric_from_" + mushroom)
        mill("minecraft:" + mushroom, "Biomass", name="biomass_from_" + mushroom)
    crafter("Gas Filter", [(2, "Coal"), (1, "Rubber"), (1, "Fabric")])
    for colour, n in (("Blue", 1), ("Yellow", 2), ("Purple", 5)):
        press(colour + " Power Slug", "Power Shard", n, name="power_shard_from_" + colour.lower() + "_slug")
    deploy("Time Crystal", "Dark Matter Crystal", "Power Shard", name="synthetic_power_shard")
    crush("Nether Quartz", "Silica", 2)
    add("sandpaper_polishing", "quartz_crystal", {"type": "create:sandpaper_polishing", "ingredient": item("Nether Quartz"), "result": res("Quartz Crystal")})
    crafter("Crystal Oscillator", [(4, "Quartz Crystal"), (3, "Cable"), (1, "Reinforced Iron Plate")])
    # the old tweaks pack: gunpowder, Ignimbrite and what it makes
    for carbon in ("minecraft:coal", "minecraft:charcoal"):
        mix("gunpowder_with_" + slug(carbon), items=["Sulfur", carbon, "minecraft:bone_meal"], results=[("Gunpowder", 3)])
    mix("ignimbrite", items=["minecraft:cobblestone", "Sulfur"], fluid_results=[("ignimbrite", 500)], heated=True)
    spout("minecraft:barrel", "ignimbrite", 250, "minecraft:tnt")
    spout("minecraft:clay_ball", "ignimbrite", 250, "minecraft:fire_charge")
    compact("magma_block", fluids=[("ignimbrite", 250)], results=["minecraft:magma_block"])
    mix("lava_from_magma_block", items=["minecraft:magma_block"], fluid_results=[("lava", 250)], heated=True)
    compact("compacted_coal", items=["Coal", "Sulfur"], results=["Compacted Coal"])
    mix("turbofuel", items=["Compacted Coal"], fluids=[("diesel", 250)], fluid_results=[("turbofuel", 250)], heated=True)
    # the sulfuric acid loop: neutralise it, or use it and deal with the residue
    for stone in ("Limestone", "minecraft:calcite"):
        mix("gypsum_from_" + slug(stone), items=[stone], fluids=[("sulfuric acid", 250)], results=["siftec:gypsum"], fluid_results=[("water", 250)])
    for ore, ingot in (("iron", "minecraft:iron_ingot"), ("copper", "minecraft:copper_ingot"), ("zinc", "create:zinc_ingot")):
        mix("acid_leached_" + ore, items=["create:crushed_raw_" + ore], fluids=[("sulfuric acid", 250)],
            results=[(ingot, 2), "siftec:toxic_residue"], heated=True)
    mix("sulfur_from_acid", fluids=[("sulfuric acid", 500)], results=["Sulfur", "siftec:toxic_residue"], heated=True)
    # Block of Sulfur (vanilla's sulfur block) packs four Gunsmithing sulfur
    shaped("minecraft:sulfur", ["SS", "SS"], {"S": "Sulfur"}, name="block_of_sulfur")
    hand("Sulfur", [(1, "minecraft:sulfur")], 4, name="sulfur_from_block")
    return dict(OUT)


# Recipes from other mods that the pack takes out.
REMOVED = [
    "cgs:mixing/steel_ingot",                                   # superheated steel; the Foundry recipe replaces it
    "create:mixing/brass_ingot",                                # brass comes from brass blend
    "create:mixing/lava_from_cobble",                           # superheated; lava comes from Ignimbrite
    "createdieselgenerators:distillation/superheated_crude_oil",
    "create:crafting/kinetics/water_wheel", "create:crafting/kinetics/large_water_wheel",
]

# What the Equipment Workshop builds: (item id, cost). It only offers what the company has unlocked.
WORKSHOP = [
    ("siftec:portable_miner", "1 Mechanical Drill, 2 Iron Sheet, 4 Iron Rod"),
    ("siftec:node_scanner", "2 Iron Sheet, 4 Wire"),
    ("siftec:miner_mk1", "1 siftec:portable_miner, 10 Iron Sheet, 10 Concrete"),
    ("siftec:wormhole_gateway", "500 Concrete, 250 Iron Sheet, 400 Iron Rod, 1500 Wire"),
    ("siftec:hub", "10 Iron Sheet, 10 Iron Rod"),
    ("siftec:claim_marker", "5 Iron Rod, 5 Concrete"),
    ("siftec:mam", "5 Reinforced Iron Plate, 15 Cable, 45 Wire"),
    ("siftec:object_scanner", "4 Reinforced Iron Plate, 20 Wire, 50 Screw"),
    ("siftec:power_line", "1 Cable"),
    ("siftec:power_pole", "1 Wire, 1 Iron Rod, 1 Concrete"),
    ("siftec:power_tower", "5 Steel Beam, 10 Concrete, 10 Wire"),
    ("siftec:power_storage", "20 Wire, 10 Modular Frame, 5 Stator"),
    ("siftec:dimensional_depot", "1 Mercer Sphere, 5 Modular Frame, 20 Cable"),
    ("siftec:geyser_engine", "20 Heavy Modular Frame, 10 Supercomputer, 50 Steel Pipe, 20 Rubber"),
    ("siftec:miner_mk2", "2 siftec:portable_miner, 10 Encased Industrial Beam, 20 Steel Pipe, 10 Modular Frame"),
    ("siftec:miner_mk3", "3 siftec:portable_miner, 50 Steel Pipe, 5 Supercomputer, 10 Fused Modular Frame, 3 Turbo Motor"),
    ("siftec:resource_well_extractor", "20 Aluminum Casing, 20 Encased Industrial Beam, 10 Motor, 50 Rubber"),
]
