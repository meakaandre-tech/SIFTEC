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
# parts that more than one mod makes: a recipe takes any of them, by their common tag
INGREDIENT_TAGS = {"Steel Ingot": "#c:ingots/steel"}


def item(name):
    """What a recipe takes: a part name, a full id, or a #tag."""
    if name in INGREDIENT_TAGS:
        return INGREDIENT_TAGS[name]
    return made(name)


def made(name):
    """What a recipe makes: a part name or a full id (never a tag)."""
    if name.startswith("#") or ":" in name:
        return name
    if name not in NAMES:
        raise SystemExit(f"recipes: unknown part {name!r}")
    return NAMES[name]


def res(name, n=1, chance=None):
    r = {"id": made(name)}
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
    return made(name).split(":")[-1].replace("/", "_")


ALTS = {}    # alternate id -> {"name", "text", "paths"}: the Hard Drive pool
_alt = None
LOCKED = {}  # recipe path -> milestone, for recipes whose result alone does not say what unlocks them
_under = None


def add(kind, name, body):
    path = f"{kind}/{name}"
    if path in OUT:
        raise SystemExit(f"recipes: duplicate {path}")
    OUT[path] = body
    if _alt:
        ALTS[_alt]["paths"].append(path)
    if _under:
        LOCKED[path] = _under


def under(milestone):
    """Everything added until the next under() (or under(None)) needs this milestone, whatever it makes."""
    global _under
    _under = milestone


def alt(aid, name, text):
    """Everything added until the next alt() or end_alts() belongs to this alternate. Returns the recipe name to use."""
    global _alt
    _alt = aid
    ALTS[aid] = {"name": name, "text": text, "paths": []}
    return "alt_" + aid


def end_alts():
    global _alt
    _alt = None


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


def crafter(out, parts, n=1, name=None, most=9):
    """parts: [(count, name)], at most nine items (or `most`, up to 25 on a 5 by 5 grid), laid out row by row."""
    keys, cells = {}, []
    for i, (count, part) in enumerate(parts):
        letter = "ABCDEFGHI"[i]
        keys[letter] = item(part)
        cells += [letter] * count
    if len(cells) > min(most, 25):
        raise SystemExit(f"recipes: {out} needs more than {min(most, 25)} items")
    width = 5 if len(cells) > 16 else 4 if len(cells) > 9 else 3 if len(cells) > 4 else 2 if len(cells) > 1 else 1
    rows = ["".join(cells[i:i + width]).ljust(width) for i in range(0, len(cells), width)]
    add("mechanical_crafting", name or slug(out), {"type": "create:mechanical_crafting", "key": keys, "pattern": rows,
        "result": {"count": n, "id": made(out)}})


def spout(inp, fluid, mb, out, name=None):
    add("filling", name or slug(out), {"type": "create:filling", "ingredient": item(inp), "fluid_ingredient": fl_in(fluid, mb), "result": res(out)})


def smelt(inp, out, name=None):
    for kind, time in (("smelting", 200), ("blasting", 100)):
        add(kind, name or slug(out), {"type": f"minecraft:{kind}", "category": "misc", "cookingtime": time, "experience": 0.1,
            "ingredient": item(inp), "result": {"count": 1, "id": made(out)}})


def hand(out, parts, n=1, name=None):
    """A crafting-table version of a machine recipe, for before the machine is unlocked."""
    add("crafting", name or slug(out), {"type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [item(p) for count, p in parts for _ in range(count)], "result": {"count": n, "id": made(out)}})


def shaped(out, pattern, key, n=1, name=None):
    add("crafting", name or slug(out), {"type": "minecraft:crafting_shaped", "category": "misc",
        "key": {k: item(v) for k, v in key.items()}, "pattern": pattern, "result": {"count": n, "id": made(out)}})


def sequence(base, out, steps, loops, name=None):
    """steps: part names applied by Deployer in order; "press" for a pressing step, "cut" for the Saw,
    ("fill", fluid, mB) for the Spout."""
    seq = []
    for step in steps:
        if step == "press":
            seq.append({"type": "create:pressing", "ingredient": "$ingredient", "results": ["$result"]})
        elif step == "cut":
            seq.append({"type": "create:cutting", "ingredient": "$ingredient", "results": ["$result"]})
        elif isinstance(step, tuple):
            seq.append({"type": "create:filling", "ingredient": "$ingredient", "fluid_ingredient": fl_in(step[1], step[2]), "results": ["$result"]})
        else:
            seq.append({"type": "create:deploying", "target": "$ingredient", "ingredient": item(step), "results": ["$result"]})
    add("sequenced_assembly", name or slug(out), {"type": "create:sequenced_assembly", "ingredient": item(base),
        "transitional_item": {"id": "siftec:incomplete_" + slug(out)}, "result": {"id": made(out)}, "loops": loops, "sequence": seq})


def alternates():
    """The Hard Drive pool. Each is a second way to make a part; a machine runs it once its company has picked it."""
    R, FR, MF, SP = "Reinforced Iron Plate", "Iron Rod", "Modular Frame", "Steel Pipe"
    HOR = "heavy oil residue"
    # tiers 0 to 2
    saw("Iron Sheet", "Wire", 3, alt("iron_wire", "Iron Wire", "1 Iron Sheet makes 3 Wire (Mechanical Saw)"))
    deploy("Iron Sheet", "Wire", R, 1, alt("stitched_iron_plate", "Stitched Iron Plate", "Iron Sheet + Wire makes 1 Reinforced Iron Plate (Deployer)"))
    deploy(R, "Screw", MF, 1, alt("bolted_frame", "Bolted Frame", "Reinforced Iron Plate + Screw makes 1 Modular Frame (Deployer)"))
    deploy("Copper Sheet", "Screw", "Rotor", 1, alt("copper_rotor", "Copper Rotor", "Copper Sheet + Screw makes 1 Rotor (Deployer)"))
    # tiers 3 and 4
    mix(alt("iron_alloy_ingot", "Iron Alloy Ingot", "2 Raw Iron + 1 Raw Copper makes 5 Iron Ingot (heated Mixer)"),
        items=["Raw Iron", "Raw Iron", "Raw Copper"], results=[("Iron Ingot", 5)], heated=True)
    mix(alt("solid_steel_ingot", "Solid Steel Ingot", "1 Iron Ingot + 1 Coal makes 2 Steel Ingot (heated Mixer)"),
        items=["Iron Ingot", "Coal"], results=[("Steel Ingot", 2)], heated=True)
    compact(alt("cast_screw", "Cast Screw", "1 Iron Ingot makes 5 Screw (heated Press over a Basin)"), items=["Iron Ingot"], results=[("Screw", 5)], heated=True)
    mix(alt("wet_concrete", "Wet Concrete", "2 Limestone + 250 mB water makes 2 Concrete (Mixer)"),
        items=["Limestone", "Limestone"], fluids=[("water", 250)], results=[("Concrete", 2)])
    saw("Steel Beam", FR, 4, alt("steel_rod", "Steel Rod", "1 Steel Beam makes 4 Iron Rod (Mechanical Saw)"))
    saw(SP, "Screw", 8, alt("steel_screw", "Steel Screw", "1 Steel Pipe makes 8 Screw (Mechanical Saw)"))
    deploy(SP, FR, "Rotor", 2, alt("steel_rotor", "Steel Rotor", "Steel Pipe + Iron Rod makes 2 Rotor (Deployer)"))
    deploy(R, SP, MF, 2, alt("steeled_frame", "Steeled Frame", "Reinforced Iron Plate + Steel Pipe makes 2 Modular Frame (Deployer)"))
    deploy(SP, "Concrete", "Encased Industrial Beam", 1, alt("encased_industrial_pipe", "Encased Industrial Pipe", "Steel Pipe + Concrete makes 1 Encased Industrial Beam (Deployer)"))
    wash("create:crushed_raw_iron", "Iron Ingot", 2, alt("pure_iron_ingot", "Pure Iron Ingot", "1 crushed raw iron makes 2 Iron Ingot (Fan washing)"))
    wash("create:crushed_raw_copper", "Copper Ingot", 2, alt("pure_copper_ingot", "Pure Copper Ingot", "1 crushed raw copper makes 2 Copper Ingot (Fan washing)"))
    smelt("Solid Biofuel", "Coal", alt("biocoal", "Biocoal", "1 Solid Biofuel makes 1 Coal (Fan smelting or a furnace)"))
    # tiers 5 and 6
    mix(alt("coke_steel_ingot", "Coke Steel Ingot", "2 Raw Iron + 1 Petroleum Coke makes 3 Steel Ingot (heated Mixer)"),
        items=["Raw Iron", "Raw Iron", "Petroleum Coke"], results=[("Steel Ingot", 3)], heated=True)
    mix(alt("recycled_plastic", "Recycled Plastic", "1 Rubber + 250 mB diesel makes 2 Plastic (heated Mixer)"),
        items=["Rubber"], fluids=[("diesel", 250)], results=[("Plastic", 2)], heated=True)
    mix(alt("recycled_rubber", "Recycled Rubber", "1 Plastic + 250 mB diesel makes 2 Rubber (heated Mixer)"),
        items=["Plastic"], fluids=[("diesel", 250)], results=[("Rubber", 2)], heated=True)
    mix(alt("residual_rubber", "Residual Rubber", "250 mB Heavy Oil Residue + 250 mB water makes 2 Rubber (heated Mixer)"),
        fluids=[(HOR, 250), ("water", 250)], results=[("Rubber", 2)], heated=True)
    deploy("Iron Ingot", "Plastic", "Iron Sheet", 3, alt("coated_iron_plate", "Coated Iron Plate", "Iron Ingot + Plastic makes 3 Iron Sheet (Deployer)"))
    deploy("Iron Sheet", "Rubber", R, 2, alt("adhered_iron_plate", "Adhered Iron Plate", "Iron Sheet + Rubber makes 2 Reinforced Iron Plate (Deployer)"))
    mix(alt("coated_cable", "Coated Cable", "2 Wire + 250 mB Heavy Oil Residue makes 4 Cable (Mixer)"),
        items=["Wire", "Wire"], fluids=[(HOR, 250)], results=[("Cable", 4)])
    mix(alt("insulated_cable", "Insulated Cable", "2 Wire + 1 Rubber makes 4 Cable (Mixer)"), items=["Wire", "Wire", "Rubber"], results=[("Cable", 4)])
    crafter("Smart Plating", [(1, R), (1, "Rotor"), (2, "Plastic")], 2,
            alt("plastic_smart_plating", "Plastic Smart Plating", "1 Reinforced Iron Plate, 1 Rotor, 2 Plastic makes 2 Smart Plating (Mechanical Crafter)"))
    crafter("Versatile Framework", [(1, MF), (3, "Steel Beam"), (2, "Rubber")], 2,
            alt("flexible_framework", "Flexible Framework", "1 Modular Frame, 3 Steel Beam, 2 Rubber makes 2 Versatile Framework (Mechanical Crafter)"))
    crafter("Heavy Modular Frame", [(3, MF), (2, "Encased Industrial Beam"), (2, SP), (2, "Concrete")], 2,
            alt("heavy_encased_frame", "Heavy Encased Frame", "3 Modular Frame, 2 Encased Industrial Beam, 2 Steel Pipe, 2 Concrete makes 2 Heavy Modular Frame (Mechanical Crafter)"))
    # needing a MAM tree
    mix(alt("fused_quickwire", "Fused Quickwire", "1 Brass Ingot + 2 Copper Ingot makes 12 Quickwire (Mixer)"),
        items=["Brass Ingot", "Copper Ingot", "Copper Ingot"], results=[("Quickwire", 12)])
    deploy("Quickwire", "Rubber", "Cable", 3, alt("quickwire_cable", "Quickwire Cable", "Quickwire + Rubber makes 3 Cable (Deployer)"))
    deploy(SP, "Quickwire", "Stator", 2, alt("quickwire_stator", "Quickwire Stator", "Steel Pipe + Quickwire makes 2 Stator (Deployer)"))
    deploy("Plastic", "Quickwire", "Circuit Board", 2, alt("caterium_circuit_board", "Caterium Circuit Board", "Plastic + Quickwire makes 2 Circuit Board (Deployer)"))
    crafter("Computer", [(2, "Circuit Board"), (5, "Quickwire"), (2, "Rubber")], 1,
            alt("caterium_computer", "Caterium Computer", "2 Circuit Board, 5 Quickwire, 2 Rubber makes 1 Computer (Mechanical Crafter)"))
    crafter("Automated Wiring", [(2, "Stator"), (4, "Wire"), (1, "High-Speed Connector")], 4,
            alt("automated_speed_wiring", "Automated Speed Wiring", "2 Stator, 4 Wire, 1 High-Speed Connector makes 4 Automated Wiring (Mechanical Crafter)"))
    compact(alt("fine_concrete", "Fine Concrete", "1 Silica + 2 Limestone makes 2 Concrete (Press over a Basin)"),
            items=["Silica", "Limestone", "Limestone"], results=[("Concrete", 2)])
    deploy("Copper Sheet", "Silica", "Circuit Board", 2, alt("silicon_circuit_board", "Silicon Circuit Board", "Copper Sheet + Silica makes 2 Circuit Board (Deployer)"))
    deploy("Circuit Board", "Crystal Oscillator", "Computer", 2, alt("crystal_computer", "Crystal Computer", "Circuit Board + Crystal Oscillator makes 2 Computer (Deployer)"))
    crafter("Motor", [(2, "Rotor"), (2, "Stator"), (1, "Crystal Oscillator")], 3,
            alt("rigor_motor", "Rigor Motor", "2 Rotor, 2 Stator, 1 Crystal Oscillator makes 3 Motor (Mechanical Crafter)"))
    crafter("Crystal Oscillator", [(3, "Quartz Crystal"), (2, "Rubber"), (1, "AI Limiter")], 1,
            alt("insulated_crystal_oscillator", "Insulated Crystal Oscillator", "3 Quartz Crystal, 2 Rubber, 1 AI Limiter makes 1 Crystal Oscillator (Mechanical Crafter)"))
    mix(alt("compacted_steel_ingot", "Compacted Steel Ingot", "2 Raw Iron + 1 Compacted Coal makes 4 Steel Ingot (heated Mixer)"),
        items=["Raw Iron", "Raw Iron", "Compacted Coal"], results=[("Steel Ingot", 4)], heated=True)
    mix(alt("turbo_heavy_fuel", "Turbo Heavy Fuel", "250 mB Heavy Oil Residue + 1 Compacted Coal makes 250 mB Turbofuel (heated Mixer)"),
        items=["Compacted Coal"], fluids=[(HOR, 250)], fluid_results=[("turbofuel", 250)], heated=True)
    # tiers 7 and 8
    mix(alt("sloppy_alumina", "Sloppy Alumina", "1 Crushed Bauxite + 500 mB water makes 500 mB Alumina Solution (heated Mixer)"),
        items=["Crushed Bauxite"], fluids=[("water", 500)], fluid_results=[("alumina solution", 500)], heated=True)
    mix(alt("electrode_aluminum_scrap", "Electrode Aluminum Scrap", "250 mB Alumina Solution + 1 Petroleum Coke makes 4 Aluminum Scrap (heated Mixer)"),
        items=["Petroleum Coke"], fluids=[("alumina solution", 250)], results=[("Aluminum Scrap", 4)], heated=True)
    compact(alt("pure_aluminum_ingot", "Pure Aluminum Ingot", "2 Aluminum Scrap makes 1 Aluminum Ingot, no Silica (heated Press over a Basin)"),
            items=["Aluminum Scrap", "Aluminum Scrap"], results=["Aluminum Ingot"], heated=True)
    mix(alt("diluted_fuel", "Diluted Fuel", "250 mB Heavy Oil Residue + 500 mB water makes 500 mB diesel (heated Mixer)"),
        fluids=[(HOR, 250), ("water", 500)], fluid_results=[("diesel", 500)], heated=True)
    deploy("Aluminum Casing", "Rubber", "Heat Sink", 1, alt("heat_exchanger", "Heat Exchanger", "Aluminum Casing + Rubber makes 1 Heat Sink (Deployer)"))
    mix(alt("cooling_device", "Cooling Device", "1 Heat Sink, 1 Motor, 250 mB Nitrogen makes 2 Cooling System (heated Mixer)"),
        items=["Heat Sink", "Motor"], fluids=[("nitrogen", 250)], results=[("Cooling System", 2)], heated=True)
    crafter("Radio Control Unit", [(1, "Crystal Oscillator"), (2, "Circuit Board"), (3, "Aluminum Casing"), (2, "Rubber")], 2,
            alt("radio_control_system", "Radio Control System", "1 Crystal Oscillator, 2 Circuit Board, 3 Aluminum Casing, 2 Rubber makes 2 Radio Control Unit (Mechanical Crafter)"))
    deploy("Electromagnetic Control Rod", "Rotor", "Motor", 2, alt("electric_motor", "Electric Motor", "Electromagnetic Control Rod + Rotor makes 2 Motor (Deployer)"))
    deploy("Stator", "High-Speed Connector", "Electromagnetic Control Rod", 2,
           alt("electromagnetic_connection_rod", "Electromagnetic Connection Rod", "Stator + High-Speed Connector makes 2 Electromagnetic Control Rod (Deployer)"))
    deploy("Radio Control Unit", "Cooling System", "Supercomputer", 1, alt("oc_supercomputer", "OC Supercomputer", "Radio Control Unit + Cooling System makes 1 Supercomputer (Deployer)"))
    crafter("Turbo Motor", [(2, "Motor"), (2, "Radio Control Unit"), (2, "Electromagnetic Control Rod"), (2, "Rotor")], 2,
            alt("turbo_electric_motor", "Turbo Electric Motor", "2 Motor, 2 Radio Control Unit, 2 Electromagnetic Control Rod, 2 Rotor makes 2 Turbo Motor (Mechanical Crafter)"))
    mix(alt("heat_fused_frame", "Heat-Fused Frame", "1 Heavy Modular Frame, 4 Aluminum Ingot, 250 mB Nitric Acid, 250 mB diesel makes 1 Fused Modular Frame (heated Mixer)"),
        items=["Heavy Modular Frame"] + ["Aluminum Ingot"] * 4, fluids=[("nitric acid", 250), ("diesel", 250)], results=["Fused Modular Frame"], heated=True)
    end_alts()


KITCHEN = []   # recipe paths converted from Farmer's Delight; they need the Automated Kitchen research


def kitchen():
    """Farmer's Delight on Create machines. Every cooking pot recipe becomes a heated Mixer recipe with its bowl or
    bottle as an ingredient, and every cutting board recipe that uses a knife becomes a Deployer recipe with the
    knife held (and kept). Converted from the recipes shipped in Farmer's Delight (tools/pack/farmersdelight.json)."""
    import json, os
    path = os.path.join(os.path.dirname(__file__), "pack", "farmersdelight.json")
    if not os.path.exists(path):
        return
    bottles = {"apple_cider", "hot_cocoa", "glow_berry_custard"}
    plain = {"cabbage_rolls", "dumplings", "dog_food"}
    for key, body in sorted(json.load(open(path)).items()):
        if not key.startswith("data/farmersdelight/recipe/"):
            continue
        name = key.split("/")[-1][:-5]
        if body.get("type") == "farmersdelight:cooking" and name != "tomato_sauce":
            extra = body.get("container", {}).get("id") if body.get("container") else ("minecraft:glass_bottle" if name in bottles else None if name in plain else "minecraft:bowl")
            result = dict(body["result"])
            add("mixing", "kitchen_" + name, {"type": "create:mixing", "heat_requirement": "heated",
                "ingredients": list(body["ingredients"]) + ([extra] if extra else []), "results": [result]})
            KITCHEN.append("mixing/kitchen_" + name)
        elif body.get("type") == "farmersdelight:cutting" and body.get("tool") == "#c:tools/knife":
            results = []
            for r in body["result"]:
                out = dict(r["item"])
                if "chance" in r: out["chance"] = r["chance"]
                results.append(out)
            add("deploying", "kitchen_" + name, {"type": "create:deploying", "target": body["ingredients"][0], "ingredient": "#c:tools/knife",
                "keep_held_item": True, "results": results})
            KITCHEN.append("deploying/kitchen_" + name)


def food():
    """Vinegar, pickles, jams and mead (the Nutrients research)."""
    FD = "farmersdelight:"
    add("bulk_fermenting", "vinegar", {"type": "createdieselgenerators:bulk_fermenting", "processing_time": 400,
        "ingredients": ["minecraft:sugar", "minecraft:sugar"], "fluid_ingredients": [fl_in("water", 250)], "fluid_results": [fl_out("vinegar", 250)]})
    add("bulk_fermenting", "mead", {"type": "createdieselgenerators:bulk_fermenting", "processing_time": 400,
        "ingredients": ["minecraft:honey_bottle", "minecraft:honey_bottle"], "fluid_ingredients": [fl_in("water", 250)], "fluid_results": [fl_out("mead", 500)],
        "results": [{"id": "minecraft:glass_bottle", "count": 2}]})
    spout("minecraft:glass_bottle", "mead", 250, "siftec:mead", name="mead")
    for what, source in (("tomato", FD + "tomato"), ("onion", FD + "onion"), ("cabbage", FD + "cabbage"), ("pumpkin", FD + "pumpkin_slice"),
                         ("carrot", "minecraft:carrot"), ("beetroot", "minecraft:beetroot"), ("kelp", "minecraft:kelp")):
        spout(source, "vinegar", 250, "siftec:pickled_" + what, name="pickled_" + what)
    for jam, fruit in (("sweet_berry_jam", "minecraft:sweet_berries"), ("glow_berry_jam", "minecraft:glow_berries"), ("apple_jam", "minecraft:apple"),
                       ("melon_jam", "minecraft:melon_slice")):
        mix(jam, items=[fruit, fruit, fruit, "minecraft:sugar"], results=["siftec:" + jam], heated=True)
    # crop waste into Biomass
    mill(FD + "straw", "Biomass", name="biomass_from_straw")
    mill(FD + "tree_bark", "Biomass", name="biomass_from_bark")


def build():
    OUT.clear()
    ALTS.clear()
    KITCHEN.clear()
    LOCKED.clear()
    alternates()
    kitchen()
    food()
    hand("siftec:hub_planner", [(1, "minecraft:stick"), (1, "#minecraft:planks")], name="hub_planner")
    # ---- tiers 0 to 2 -------------------------------------------------------------------------
    saw("Iron Ingot", "Iron Rod")
    saw("Copper Ingot", "Wire", 2)
    compact("cable", items=["Wire", "Wire"], results=["Cable"])
    compact("concrete", items=["Limestone"] * 3, results=["Concrete"])
    saw("Iron Rod", "Screw", 4)
    deploy("Iron Sheet", "Screw", "Reinforced Iron Plate")
    mill("#minecraft:leaves", "Biomass", name="biomass_from_leaves", chance=0.5)
    mill("#minecraft:logs", "Biomass", 4, name="biomass_from_logs")
    for crop in ("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "kelp"):
        mill("minecraft:" + crop, "Biomass", name="biomass_from_" + crop, chance=0.25)
    # Create already mills these into sugar, green dye and seeds, so their Biomass comes from the Saw (which picks by filter)
    for crop in ("sugar_cane", "cactus", "short_grass"):
        saw("minecraft:" + crop, "Biomass", name="biomass_from_" + crop)
    deploy("Iron Rod", "Screw", "Rotor")
    deploy("Reinforced Iron Plate", "Iron Rod", "Modular Frame")
    deploy("Reinforced Iron Plate", "Rotor", "Smart Plating")
    compact("solid_biofuel", items=["Biomass", "Biomass"], results=["Solid Biofuel"])
    # by hand, until the machines unlock. Sheets and rods both start from ingots, so they are shaped
    # (three in a row: two side by side is the heavy weighted pressure plate)
    shaped("Iron Sheet", ["III"], {"I": "Iron Ingot"}, 2, name="iron_sheet_by_hand")  # by hand: less than the Press (1 per ingot)
    shaped("Iron Rod", ["I", "I"], {"I": "Iron Ingot"}, 1, name="iron_rod_by_hand")  # by hand: half the Saw
    shaped("Copper Sheet", ["II"], {"I": "Copper Ingot"}, 1, name="copper_sheet_by_hand")  # by hand: half the Press
    shaped("Wire", ["I", "I"], {"I": "Copper Ingot"}, 2, name="wire_by_hand")  # by hand: half the Saw
    hand("Cable", [(2, "Wire")], name="cable_by_hand")
    hand("Concrete", [(3, "Limestone")], name="concrete_by_hand")
    hand("Screw", [(1, "Iron Rod")], 2, name="screw_by_hand")  # by hand: half the Saw
    hand("Reinforced Iron Plate", [(1, "Iron Sheet"), (1, "Screw")], name="reinforced_iron_plate_by_hand")
    hand("Rotor", [(1, "Iron Rod"), (1, "Screw")], name="rotor_by_hand")
    hand("Modular Frame", [(1, "Reinforced Iron Plate"), (1, "Iron Rod")], name="modular_frame_by_hand")
    hand("Smart Plating", [(1, "Reinforced Iron Plate"), (1, "Rotor")], name="smart_plating_by_hand")
    hand("Solid Biofuel", [(2, "Biomass")], name="solid_biofuel_by_hand")
    shaped("siftec:equipment_workshop", ["SSS", "RSR", "SRS"], {"S": "Iron Sheet", "R": "Iron Rod"}, name="equipment_workshop")
    # Create's own machines, rewritten with the pack's parts so each can be built at the tier that unlocks it
    # (Create's recipes need brass and Electron Tubes, which come later; theirs are in REMOVED)
    shaped("create:flywheel", ["RSR", "SAS", "RSR"], {"R": "Iron Rod", "S": "Iron Sheet", "A": "create:shaft"}, name="flywheel")  # HUB Upgrade 6
    shaped("create:deployer", ["R", "C", "P"], {"R": "Rotor", "C": "create:andesite_casing", "P": "Reinforced Iron Plate"}, name="deployer")
    # the Rebar Gun: Megafauna research, long before the Mechanical Crafter
    shaped("cgs:nailgun", ["PPT", "RS "], {"P": "Reinforced Iron Plate", "T": "create:copper_backtank", "R": "Iron Rod", "S": "Screw"}, name="nailgun")

    # ---- tiers 3 and 4 ------------------------------------------------------------------------
    mix("steel_ingot", items=["Raw Iron", "Coal"], results=["Steel Ingot"], heated=True)
    # pressed over a Basin: a plain Press already turns a steel ingot into Gunsmithing's Steel Sheet
    compact("steel_beam", items=["Steel Ingot", "Steel Ingot"], results=["Steel Beam"])
    # the Xeno-Basher (Enhanced Asset Security)
    shaped("cgs:hammer", [" PR", " MP", "F  "], {"P": "Reinforced Iron Plate", "R": "Rotor", "M": "Modular Frame", "F": "Iron Rod"}, name="hammer")
    # Crushing Wheels (Advanced Steel Production) on a crafting table: Create's is a 5x5 Mechanical Crafter recipe
    shaped("create:crushing_wheel", ["ASA", "SRS", "ASA"], {"A": "create:andesite_alloy", "S": "Steel Beam", "R": "Rotor"}, 2, name="crushing_wheel")
    saw("Steel Ingot", "Steel Pipe")
    deploy("Modular Frame", "Steel Beam", "Versatile Framework")
    deploy("Steel Beam", "Concrete", "Encased Industrial Beam")
    deploy("Steel Pipe", "Wire", "Stator")
    deploy("Rotor", "Stator", "Motor")
    deploy("Stator", "Cable", "Automated Wiring")
    # what Miner Mk.2 drills with (Mk.1 takes water; Mk.3 Coolant, below)
    mix("drilling_mud", items=["minecraft:clay_ball", "minecraft:gravel"], fluids=[("water", 250)], fluid_results=[("drilling mud", 500)])

    # ---- tiers 5 and 6 ------------------------------------------------------------------------
    mix("plastic", fluids=[("crude oil", 250)], results=[("Plastic", 2)], fluid_results=[("heavy oil residue", 100)], heated=True)
    # coal as carbon black: the Mixer prefers the recipe with more ingredients, so coal in the Basin means rubber
    mix("rubber", items=["Coal"], fluids=[("crude oil", 250)], results=[("Rubber", 2)], fluid_results=[("heavy oil residue", 150)], heated=True)
    # the Pumpjack crank on a crafting table: Diesel Generators' is a 3x5 Mechanical Crafter recipe (Tier 6)
    shaped("createdieselgenerators:pumpjack_crank", ["AIA", "ZSZ", "AIA"],
           {"A": "create:andesite_alloy", "I": "#c:plates/iron", "S": "create:shaft", "Z": "#c:ingots/zinc"}, name="pumpjack_crank")
    # what Miner Mk.3 drills with
    mix("coolant", fluids=[("diesel", 250), ("water", 250)], fluid_results=[("coolant", 500)])
    compact("petroleum_coke", fluids=[("heavy oil residue", 250)], results=[("Petroleum Coke", 3)], heated=True)
    deploy("Copper Sheet", "Plastic", "Circuit Board")
    press("Plastic", "Empty Canister", 2)
    under("fluid_packaging")
    mix("liquid_biofuel", items=["Solid Biofuel"], fluids=[("water", 250)], fluid_results=[("biodiesel", 250)], heated=True)
    under(None)
    # the Manufacturer (Industrial Manufacturing): Create's needs brass and Electron Tubes, which are MAM research
    shaped("create:mechanical_crafter", ["B", "C", "T"], {"B": "Circuit Board", "C": "siftec:steel_casing", "T": "minecraft:crafting_table"}, 3,
           name="mechanical_crafter")
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
    under("aeronautical_engineering")
    mix("sulfuric_acid", items=["Sulfur"], fluids=[("water", 250)], fluid_results=[("sulfuric acid", 250)], heated=True)
    under(None)
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
    under("particle_enrichment")
    mix("nitric_acid", items=["Iron Sheet"], fluids=[("nitrogen", 250), ("water", 250)], fluid_results=[("nitric acid", 250)], heated=True)
    under(None)
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
    under("mam_megafauna_1")   # Hostile Remains
    for drop in ("rotten_flesh", "string", "spider_eye"):
        mill("minecraft:" + drop, "Biomass", 2, name="biomass_from_" + drop)
    saw("minecraft:bone", "Biomass", 2, name="biomass_from_bone")  # the Millstone makes bone meal from bones
    under(None)
    for drop in ("rotten_flesh", "bone", "string", "spider_eye"):
        compact("dna_capsule_from_" + drop, items=["minecraft:" + drop] * 2, results=["DNA Capsule"])
    # brass, the pack's Caterium: crushed copper and zinc with a flux, then smelted
    for flux, n in (("minecraft:sand", 1), ("Coal", 2), ("Sulfur", 3)):
        mix("brass_blend_with_" + slug(flux), items=["create:crushed_raw_copper", "create:crushed_raw_zinc", flux], results=[("Brass Blend", n)])
    smelt("Brass Blend", "Brass Ingot", name="brass_ingot_from_blend")
    saw("Brass Ingot", "Quickwire", 4)
    # the Assembler makes Create's brass-tier parts too (Create's own recipes stay as well)
    deploy("Iron Sheet", "Quickwire", "Electron Tube")
    deploy("Brass Sheet", "Rotor", "Precision Mechanism")
    deploy("Copper Sheet", "Quickwire", "AI Limiter")
    crafter("High-Speed Connector", [(5, "Quickwire"), (3, "Cable"), (1, "Circuit Board")])
    for mushroom in ("brown_mushroom", "red_mushroom"):
        deploy("Biomass", "minecraft:" + mushroom, "Fabric", name="fabric_from_" + mushroom)
        under("mam_mycelia_1")   # Mycelia
        mill("minecraft:" + mushroom, "Biomass", name="biomass_from_" + mushroom)
        under(None)
    crafter("Gas Filter", [(2, "Coal"), (1, "Rubber"), (1, "Fabric")])
    for colour, n in (("Blue", 1), ("Yellow", 2), ("Purple", 5)):
        press(colour + " Power Slug", "Power Shard", n, name="power_shard_from_" + colour.lower() + "_slug")
    deploy("Time Crystal", "Dark Matter Crystal", "Power Shard", name="synthetic_power_shard")
    mill("Nether Quartz", "Silica", 2)  # the Millstone, so Silica comes with its research, not at Tier 4
    add("sandpaper_polishing", "quartz_crystal", {"type": "create:sandpaper_polishing", "ingredient": item("Nether Quartz"), "result": res("Quartz Crystal")})
    crafter("Crystal Oscillator", [(4, "Quartz Crystal"), (3, "Cable"), (1, "Reinforced Iron Plate")])
    # the old tweaks pack: gunpowder, Ignimbrite and what it makes
    under("mam_sulfur_1")   # Black Powder
    for carbon in ("minecraft:coal", "minecraft:charcoal"):
        mix("gunpowder_with_" + slug(carbon), items=["Sulfur", carbon, "minecraft:bone_meal"], results=[("Gunpowder", 3)])
    under("mam_sulfur_2")   # Ignimbrite: the only way to lava (Ignimbrite, then magma, then lava)
    mix("ignimbrite", items=["minecraft:cobblestone", "Sulfur"], fluid_results=[("ignimbrite", 500)], heated=True)
    spout("minecraft:clay_ball", "ignimbrite", 250, "minecraft:fire_charge")
    compact("magma_block", fluids=[("ignimbrite", 250)], results=["minecraft:magma_block"])
    mix("lava_from_magma_block", items=["minecraft:magma_block"], fluid_results=[("lava", 250)], heated=True)
    under("mam_sulfur_3")   # Explosives
    spout("minecraft:barrel", "ignimbrite", 250, "minecraft:tnt")
    under(None)
    compact("compacted_coal", items=["Coal", "Sulfur"], results=["Compacted Coal"])
    mix("turbofuel", items=["Compacted Coal"], fluids=[("diesel", 250)], fluid_results=[("turbofuel", 250)], heated=True)
    # the sulfuric acid loop: neutralise it, or use it and deal with the residue
    for stone in ("Limestone", "minecraft:calcite"):
        mix("gypsum_from_" + slug(stone), items=[stone], fluids=[("sulfuric acid", 250)], results=["siftec:gypsum"], fluid_results=[("water", 250)])
    for ore, ingot in (("iron", "minecraft:iron_ingot"), ("copper", "minecraft:copper_ingot"), ("zinc", "create:zinc_ingot")):
        mix("acid_leached_" + ore, items=["create:crushed_raw_" + ore], fluids=[("sulfuric acid", 250)],
            results=[(ingot, 2), "siftec:toxic_residue"], heated=True)
    # Steel Casing: steel on a stripped log, by hand or by Deployer, like Create's own casings
    for wood in ("stripped_logs", "stripped_woods"):
        add("item_application", "steel_casing_from_" + wood, {"type": "create:item_application", "target": "#c:" + wood, "ingredient": item("Steel Ingot"),
            "results": [{"id": "siftec:steel_casing"}]})
        add("deploying", "steel_casing_from_" + wood, {"type": "create:deploying", "target": "#c:" + wood, "ingredient": item("Steel Ingot"),
            "results": [{"id": "siftec:steel_casing"}]})
    # Toxic Residue packed into paper shot, for a gun with the Blunderbuss Barrel
    hand("siftec:toxic_shot", [(1, "cgs:paper_shot"), (1, "siftec:toxic_residue")], name="toxic_shot")
    deploy("cgs:paper_shot", "siftec:toxic_residue", "siftec:toxic_shot", name="toxic_shot")
    mix("sulfur_from_acid", fluids=[("sulfuric acid", 500)], results=["Sulfur", "siftec:toxic_residue"], heated=True)
    # Block of Sulfur (vanilla's sulfur block) packs four Gunsmithing sulfur
    shaped("minecraft:sulfur", ["SS", "SS"], {"S": "Sulfur"}, name="block_of_sulfur")
    hand("Sulfur", [(1, "minecraft:sulfur")], 4, name="sulfur_from_block")
    workshop_by_machine()
    return dict(OUT)


# Every Workshop build also has a machine route, at about the Workshop's cost: the Workshop is for building by
# hand. Builds of 25 parts or fewer go in a Mechanical Crafter (one part per crafter, at most 5 by 5). Bigger ones
# before the Mechanical Crafter (Tier 6) are made by sequenced assembly: Deployers on a belt or depot apply the
# parts, looping until the build is done. From Tier 6 on, the big ones are made in a Mechanical Crafter from a few
# sub-assemblies, each from a crafter or an assembly line. The sub-assemblies are in content.PARTS and unlock with
# their build (content.ASSEMBLY_LOCKS). The Power Line is pressed from Cable.
CRAFTER_MOST = 25
W = "siftec:"
# sequenced assembly: (what it makes, base item, the steps of one loop, loops)
ASSEMBLY_LINES = [
    (W + "furnace_engine", "Iron Sheet", ["Iron Rod", "Wire", "Iron Sheet", "Wire"], 14),
    (W + "mam", "Reinforced Iron Plate", ["Cable", "Wire", "Wire", "Wire"], 16),
    (W + "object_scanner", "Reinforced Iron Plate", ["Wire", "Screw", "Screw", "Wire", "Screw", "Screw", "Screw"], 10),
    # the Wormhole Gateway: 24 coils of wire and 25 frames of concrete, sheet and rod, put together on a third line
    (W + "gateway_coil", "Iron Rod", ["Wire"] * 5, 12),
    (W + "gateway_frame", "Concrete", ["Iron Rod", "Concrete", "Iron Sheet", "Concrete", "Iron Rod"], 9),
    (W + "wormhole_gateway", W + "gateway_frame", [W + "gateway_coil", W + "gateway_frame"], 24),
    (W + "landing_cushion", "Biomass", ["Biomass"], 19),
    (W + "landing_pad", "Rotor", ["Cable", "Cable", "Cable", W + "landing_cushion", "Rotor", "Rotor"], 10),
    (W + "awesome_sink", "Concrete", ["Reinforced Iron Plate", "Cable", "Concrete", "Cable", "Concrete", "Concrete"], 15),
    (W + "awesome_shop", "Iron Sheet", ["Cable"] + ["Screw"] * 6, 33),
    (W + "blueprint_designer", "Modular Frame", ["Concrete", "Concrete", "Cable", "Concrete", "Concrete"], 25),
    (W + "power_storage", "Stator", ["Modular Frame", "Wire", "Wire", "Wire", "Wire", "Modular Frame", "Stator"], 5),
    (W + "speed_governor", "Rotor", ["Quickwire"] * 6 + ["Reinforced Iron Plate"], 4),
    (W + "blade_runners", "Modular Frame", ["Silica"] * 5 + ["Rotor"], 4),
    (W + "dimensional_depot", "Mercer Sphere", ["Modular Frame", "Cable", "Cable", "Cable", "Cable"], 5),
    (W + "miner_mk2", W + "portable_miner", ["Encased Industrial Beam", "Steel Pipe", "Steel Pipe", "Modular Frame"], 10),
    (W + "jetpack_thruster", "Motor", ["Plastic", "Rubber"], 10),
    (W + "jetpack", W + "jetpack_thruster", ["Circuit Board", W + "jetpack_thruster"], 4),
    (W + "gas_mask", "Fabric", ["Plastic", "Rubber", "Plastic", "Fabric"], 50),
    (W + "radar_tower", "Heavy Modular Frame", ["Crystal Oscillator"] + ["Cable"] * 5 + ["Heavy Modular Frame"], 10),
    (W + "drill_shaft", "Fused Modular Frame", ["Steel Pipe"], 5),
    (W + "designer_frame", "Fused Modular Frame", ["Concrete"], 10),
]
# Mechanical Crafter, beyond the builds of 25 parts or fewer: (what it makes, [(count, part)])
CRAFTED = [
    (W + "hazmat_lining", [(5, "Rubber"), (5, "Plastic"), (5, "Alclad Aluminum Sheet"), (5, "Fabric")]),
    (W + "hazmat_suit", [(10, W + "hazmat_lining")]),
    (W + "hover_thruster", [(1, "Motor"), (1, "Computer"), (5, "Alclad Aluminum Sheet")]),
    (W + "hover_pack", [(8, W + "hover_thruster"), (4, "Heavy Modular Frame")]),
    (W + "drone_port_module", [(2, "Heavy Modular Frame"), (1, "High-Speed Connector"), (5, "Alclad Aluminum Sheet"), (5, "Aluminum Casing"),
                               (1, "Radio Control Unit")]),
    (W + "drone_port", [(10, W + "drone_port_module")]),
    (W + "accelerator_segment", [(10, "Electromagnetic Control Rod"), (5, "Cooling System"), (2, "Fused Modular Frame"), (2, "Radio Control Unit"),
                                 (1, "Supercomputer"), (1, "Turbo Motor")]),
    (W + "particle_accelerator", [(10, W + "accelerator_segment"), (5, "Radio Control Unit")]),
    (W + "converter_core", [(2, "Fused Modular Frame"), (2, "Cooling System"), (5, "Radio Control Unit"), (10, "SAM Fluctuator")]),
    (W + "converter", [(5, W + "converter_core")]),
    # one module is a fifth of a Satellite Portal; the Main Portal takes 17 and 8 more Turbo Motors
    (W + "portal_module", [(1, "Turbo Motor"), (2, "Radio Control Unit"), (1, "Superposition Oscillator"), (2, "SAM Fluctuator"), (10, "Ficsite Trigon")]),
    (W + "satellite_portal", [(5, W + "portal_module")]),
    (W + "main_portal", [(17, W + "portal_module"), (8, "Turbo Motor")]),
    (W + "geyser_core", [(2, "Heavy Modular Frame"), (1, "Supercomputer"), (5, "Steel Pipe"), (2, "Rubber")]),
    (W + "geyser_engine", [(10, W + "geyser_core")]),
    (W + "extractor_pump", [(2, "Aluminum Casing"), (2, "Encased Industrial Beam"), (1, "Motor"), (5, "Rubber")]),
    (W + "resource_well_extractor", [(10, W + "extractor_pump")]),
    (W + "miner_mk3", [(10, W + "drill_shaft"), (3, W + "portable_miner"), (5, "Supercomputer"), (3, "Turbo Motor")]),
    (W + "blueprint_designer_mk3", [(10, W + "designer_frame"), (5, "Neural-Quantum Processor")]),
]


def workshop_by_machine():
    import content as _c
    # the Power Line: one Cable pressed flat, as the Workshop makes it (1 Cable, 1 line)
    press("Cable", "siftec:power_line", name="power_line")
    for out, cost in WORKSHOP:
        if out == "siftec:power_line":
            continue
        parts = [(n, c) for c, n in _c.parse_cost(cost)]
        if sum(n for n, c in parts) <= CRAFTER_MOST:
            crafter(out, parts, name="workshop_" + out.split(":")[1], most=CRAFTER_MOST)
    for out, base, steps, loops in ASSEMBLY_LINES:
        sequence(base, out, steps, loops)
    for out, parts in CRAFTED:
        crafter(out, parts, name=out.split(":")[1], most=CRAFTER_MOST)


# Recipes from other mods that the pack takes out.
REMOVED = [
    "cgs:mixing/steel_ingot",                                   # superheated steel; the Foundry recipe replaces it
    "create:mixing/brass_ingot",                                # brass comes from brass blend
    "create:mixing/lava_from_cobble",                           # superheated; lava comes from Ignimbrite
    "createdieselgenerators:distillation/superheated_crude_oil",
    "create:crafting/kinetics/water_wheel", "create:crafting/kinetics/large_water_wheel",
    # washing crushed ore now belongs to the Pure Iron Ingot and Pure Copper Ingot alternates
    "create:splashing/crushed_raw_iron", "create:splashing/crushed_raw_copper",
    # rewritten in build() with the pack's parts, so each machine can be built at the tier that unlocks it
    "create:crafting/kinetics/deployer", "create:crafting/kinetics/mechanical_crafter", "create:crafting/kinetics/flywheel", "create:mechanical_crafting/crushing_wheel",
    "createdieselgenerators:mechanical_crafting/pumpjack_crank", "cgs:mechanical_crafting/hammer", "cgs:mechanical_crafting/nailgun",
    # sulfur and lava come only by the owner's routes: no sulfur from crushing magma (Gunsmithing ships this one under
    # Create's name), no lava from fermenting cobblestone, and no Potent Sulfur (a geyser) from nine Blocks of Sulfur
    "create:crushing/magma_block", "createdieselgenerators:bulk_fermenting/lava", "minecraft:potent_sulfur",
    # Create's dough replaces Farmer's Delight's
    "farmersdelight:wheat_dough_from_egg", "farmersdelight:wheat_dough_from_water", "farmersdelight:bread_from_smelting",
    "farmersdelight:bread_from_smoking",
    # what the AWESOME Shop sells is bought, not crafted
    "create:crafting/appliances/clipboard", "create:crafting/appliances/crafting_blueprint", "create:crafting/appliances/linked_controller",
    "create:crafting/curiosities/peculiar_bell", "create:crafting/kinetics/copper_valve_handle", "create:crafting/kinetics/cuckoo_clock",
    "create:crafting/kinetics/placard", "create:crafting/kinetics/steam_whistle", "create:crafting/kinetics/turntable",
    "create:mechanical_crafting/extendo_grip", "create:mechanical_crafting/potato_cannon", "create:mechanical_crafting/wand_of_symmetry",
    "create:andesite_table_cloth_from_andesite_alloy_stonecutting",
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
    ("siftec:hub_engine", "5 Iron Sheet, 5 Iron Rod"),
    ("siftec:power_pole", "1 Wire, 1 Iron Rod, 1 Concrete"),
    ("siftec:furnace_engine", "15 Iron Sheet, 15 Iron Rod, 25 Wire"),
    ("siftec:power_tower", "5 Steel Beam, 10 Concrete, 10 Wire"),
    ("siftec:power_storage", "20 Wire, 10 Modular Frame, 5 Stator"),
    ("siftec:speed_governor", "25 Quickwire, 2 Rotor, 2 Reinforced Iron Plate"),
    ("siftec:landing_pad", "20 Rotor, 30 Cable, 200 Biomass"),
    ("siftec:jetpack", "50 Plastic, 50 Rubber, 5 Circuit Board, 5 Motor"),
    ("siftec:hazmat_suit", "50 Rubber, 50 Plastic, 50 Alclad Aluminum Sheet, 50 Fabric"),
    ("siftec:hover_pack", "8 Motor, 4 Heavy Modular Frame, 8 Computer, 40 Alclad Aluminum Sheet"),
    ("siftec:blade_runners", "20 Silica, 3 Modular Frame, 3 Rotor"),
    ("siftec:parachute", "10 Fabric, 5 Cable"),
    ("siftec:gas_mask", "50 Rubber, 100 Plastic, 50 Fabric"),
    ("siftec:radar_tower", "10 Heavy Modular Frame, 10 Crystal Oscillator, 50 Cable"),
    ("siftec:blueprint_designer", "4 Modular Frame, 25 Cable, 100 Concrete"),
    ("siftec:blueprint_designer_mk3", "10 Fused Modular Frame, 5 Neural-Quantum Processor, 100 Concrete"),
    ("siftec:drone_port", "20 Heavy Modular Frame, 10 High-Speed Connector, 50 Alclad Aluminum Sheet, 50 Aluminum Casing, 10 Radio Control Unit"),
    ("siftec:cardboard_drone", "8 Cardboard, 4 Motor, 10 Alclad Aluminum Sheet, 1 Radio Control Unit, 2 AI Limiter"),
    ("siftec:main_portal", "25 Turbo Motor, 25 Radio Control Unit, 15 Superposition Oscillator, 20 SAM Fluctuator, 200 Ficsite Trigon"),
    ("siftec:satellite_portal", "5 Turbo Motor, 10 Radio Control Unit, 5 Superposition Oscillator, 10 SAM Fluctuator, 50 Ficsite Trigon"),
    ("siftec:dimensional_depot", "1 Mercer Sphere, 5 Modular Frame, 20 Cable"),
    ("siftec:geyser_engine", "20 Heavy Modular Frame, 10 Supercomputer, 50 Steel Pipe, 20 Rubber"),
    ("siftec:awesome_sink", "15 Reinforced Iron Plate, 30 Cable, 45 Concrete"),
    ("siftec:awesome_shop", "200 Screw, 5 Iron Sheet, 30 Cable"),
    ("siftec:particle_accelerator", "25 Radio Control Unit, 100 Electromagnetic Control Rod, 10 Supercomputer, 50 Cooling System, 20 Fused Modular Frame, 10 Turbo Motor"),
    ("siftec:converter", "10 Fused Modular Frame, 10 Cooling System, 25 Radio Control Unit, 50 SAM Fluctuator"),
    ("siftec:miner_mk2", "2 siftec:portable_miner, 10 Encased Industrial Beam, 20 Steel Pipe, 10 Modular Frame"),
    ("siftec:miner_mk3", "3 siftec:portable_miner, 50 Steel Pipe, 5 Supercomputer, 10 Fused Modular Frame, 3 Turbo Motor"),
    ("siftec:resource_well_extractor", "20 Aluminum Casing, 20 Encased Industrial Beam, 10 Motor, 50 Rubber"),
]
