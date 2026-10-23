"""Every recipe the pack adds, written against the Recipes tab of the design doc. build() returns {path under
data/siftec/recipe: json}. Amounts of fluid are in mB here.

Satisfactory 1.0's machine recipes come from tools/tables/satisfactory.py at Satisfactory's amounts and times, each on the
Create machines of the building Satisfactory makes it in:

    Constructor     Press over a Basin (compacting)          Foundry     heated Mixer over a Basin
    Assembler       Mixer over a Basin                       Refinery    heated Mixer over a Basin, with fluids
    Smelter         fan blasting (vanilla smelting)          Blender     heated Mixer over a Basin, with fluids
    Packager        Spout (packing) and Item Drain (unpacking)
    Manufacturer    Mixer over a Basin makes the Core, then one loop of sequenced assembly adds the last of each input
    Quantum Encoder the same, with the Spout adding Excited Photonic Matter; the Item Drain takes the Dark Matter
                    Residue off what comes out
    Converter, Particle Accelerator: the pack's own machines (content.PROCESSORS)

A Basin's filter picks which recipe runs when more than one fits what is in it, so the Constructor and the other
basin buildings set their recipe with that filter. Every recipe is locked to the milestone, MAM node or Hard Drive
alternate that unlocks it in 1.0. The food, brass and heat recipes (Food and Brass tab) are carried over below."""
from tables import content

DROPLETS = 81  # Create Fly counts fluid in droplets: 81 per mB
TICKS = 20

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


def results(pairs):
    """Create's outputs hold at most 99 of an item each, so a bigger count (100 Biomass) is split into even parts."""
    out = []
    for name, n in pairs:
        parts = -(-n // 99)
        for k in range(parts):
            out.append(res(name, n // parts + (1 if k < n % parts else 0)))
    return out


def fl_in(name, mb):
    return {"type": "fluid_stack", "amount": mb * DROPLETS, "fluid": FLUID_IDS[name]}


def fl_out(name, mb):
    return {"id": FLUID_IDS[name], "amount": mb * DROPLETS}


def mb(m3):
    return round(m3 * content.MB_PER_M3)


def slug(name):
    return made(name).split(":")[-1].replace("/", "_")


ALTS = {}    # alternate id -> {"name", "text", "paths"}: the Hard Drive pool
_alt = None
LOCKED = {}  # recipe path -> milestone, for recipes whose result alone does not say what unlocks them
_under = None
SATISFACTORY = {}  # recipe path -> the 1.0 recipe it comes from (key), for the checkers
# What the Manufacturer and the Quantum Encoder run (building/BuildingLogic.java, "assembly"): one entry a recipe, with
# what goes on the line first (the Core the Mixer makes, or the first input itself), each step's item or fluid, and what
# the Item Drain takes off at the end. The Create recipes do the work; the building moves the item along its line.
ASSEMBLY = []
# What the Packager runs ("packager"): its Spout's filling and its Item Drain's emptying recipes, with Satisfactory's time
PACKAGER = []


def add(kind, name, body):
    path = f"{kind}/{name}"
    if path in OUT:
        raise SystemExit(f"recipes: duplicate {path}")
    OUT[path] = body
    if _alt:
        ALTS[_alt]["paths"].append(path)
    if _under:
        LOCKED[path] = _under
    return path


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


def basin(kind, name, items=(), fluids=(), results=(), fluid_results=(), heated=False, time=None):
    body = {"type": f"create:{kind}"}
    if heated:
        body["heat_requirement"] = "heated"
    if items:
        body["ingredients"] = [item(i) for i in items]
    if fluids:
        body["fluid_ingredients"] = [fl_in(f, mb_) for f, mb_ in fluids]
    if results:
        body["results"] = [res(*r) if isinstance(r, tuple) else res(r) for r in results]
    if fluid_results:
        body["fluid_results"] = [fl_out(f, mb_) for f, mb_ in fluid_results]
    if time:
        body["processing_time"] = time
    return add(kind, name, body)


def mix(name, **kw): return basin("mixing", name, **kw)
def compact(name, **kw): return basin("compacting", name, **kw)


def deploy(base, held, out, n=1, name=None):
    add("deploying", name or slug(out), {"type": "create:deploying", "target": item(base), "ingredient": item(held), "results": [res(out, n)]})


def spout(inp, fluid, mb_, out, name=None):
    add("filling", name or slug(out), {"type": "create:filling", "ingredient": item(inp), "fluid_ingredient": fl_in(fluid, mb_), "result": res(out)})


def smelt(inp, out, name=None):
    for kind, time in (("smelting", 200), ("blasting", 100)):
        add(kind, name or slug(out), {"type": f"minecraft:{kind}", "category": "misc", "cookingtime": time, "experience": 0.1,
            "ingredient": item(inp), "result": {"count": 1, "id": made(out)}})


def hand(out, parts, n=1, name=None):
    add("crafting", name or slug(out), {"type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [item(p) for count, p in parts for _ in range(count)], "result": {"count": n, "id": made(out)}})


def shaped(out, pattern, key, n=1, name=None):
    add("crafting", name or slug(out), {"type": "minecraft:crafting_shaped", "category": "misc",
        "key": {k: item(v) for k, v in key.items()}, "pattern": pattern, "result": {"count": n, "id": made(out)}})


def sequence(base, out, steps, loops, name=None, n=1, transitional=None):
    """steps: part names applied by Deployer in order; "press" for a pressing step, "cut" for the Saw,
    ("fill", fluid, mB) for the Spout."""
    seq = []
    for step in steps:
        if step == "press":
            seq.append({"type": "create:pressing", "ingredient": "$ingredient", "results": ["$result"]})
        elif step == "cut":
            seq.append({"type": "create:cutting", "ingredient": "$ingredient", "results": ["$result"]})
        elif isinstance(step, tuple):
            # the Spout's recipe has one result, not a list
            seq.append({"type": "create:filling", "ingredient": "$ingredient", "fluid_ingredient": fl_in(step[1], step[2]), "result": "$result"})
        else:
            seq.append({"type": "create:deploying", "target": "$ingredient", "ingredient": item(step), "results": ["$result"]})
    return add("sequenced_assembly", name or slug(out), {"type": "create:sequenced_assembly", "ingredient": item(base),
        "transitional_item": {"id": transitional or "siftec:incomplete_" + slug(out)}, "result": res(out, n), "loops": loops, "sequence": seq})


# ---------------------------------------------------------------------------------------------------------------------
# Satisfactory 1.0
BUILDING_SLUG = {b: content.slug(b) for b in ("Constructor", "Smelter", "Assembler", "Foundry", "Manufacturer", "Refinery", "Blender",
                                              "Packager", "Quantum Encoder")}
HEATED = {"Foundry", "Refinery", "Blender"}
# 1.0 recipes the pack does not write: the Smelter's iron and copper ingots are vanilla smelting (which Create's fan
# blasting runs), and Caterium Ingot is brass from brass blend (below).
VANILLA = {"Iron Ingot", "Copper Ingot", "Caterium Ingot"}


def _fmt(pairs):
    return ", ".join(f"{a:g} {'mB ' if k == 'fluid' else ''}{n.split(':')[-1].replace('_', ' ') if ':' in n else n}" for k, n, a in pairs)


def _text(building, ins, outs):
    show = lambda lst: [(k, n, mb(a) if k == "fluid" else a) for k, n, a in lst]
    return f"{_fmt(show(ins))} makes {_fmt(show(outs))} ({building})"


def satisfactory():
    global _under
    for key, name, building, is_alt, secs, ins, outs, unlock in content.sat_recipes():
        if building in ("Converter", "Particle Accelerator") or name in VANILLA:
            continue
        if name == "Alternate: Pure Aluminum Ingot":
            # the Smelter's one recipe of two items into one cannot run on its fan, which smelts item by item: the
            # Foundry, the other building that smelts, runs it in its heated Mixer
            building = "Foundry"
        uid = content.unlock_id(unlock)
        rslug = content.slug(name)
        where = BUILDING_SLUG[building]
        if uid == "drive":
            alt(rslug, name.replace("Alternate: ", ""), _text(building, ins, outs))
            _under = None
        else:
            end_alts()
            _under = uid
        before = set(OUT)
        items = [n for k, n, a in ins if k == "item" for _ in range(a)]
        fluids = [(n, mb(a)) for k, n, a in ins if k == "fluid"]
        item_out = [(n, a) for k, n, a in outs if k == "item"]
        fluid_out = [(n, mb(a)) for k, n, a in outs if k == "fluid"]
        ticks = round(secs * TICKS)
        if building in ("Constructor", "Assembler", "Foundry", "Refinery", "Blender"):
            kind = "compacting" if building == "Constructor" else "mixing"
            body = {"type": f"create:{kind}"}
            if building in HEATED:
                body["heat_requirement"] = "heated"
            if items:
                body["ingredients"] = [item(i) for i in items]
            if fluids:
                body["fluid_ingredients"] = [fl_in(f, m) for f, m in fluids]
            if item_out:
                body["results"] = results(item_out)
            if fluid_out:
                body["fluid_results"] = [fl_out(f, m) for f, m in fluid_out]
            body["processing_time"] = ticks
            add(kind, f"{where}/{rslug}", body)
        elif building == "Packager":
            # Satisfactory packs two canisters' worth at a time; the Spout and the Item Drain do one container each
            container = [(n, a) for k, n, a in (ins if name.startswith("Packaged ") else outs) if k == "item" and n in ("Empty Canister", "Empty Fluid Tank")]
            packed = [(n, a) for k, n, a in (outs if name.startswith("Packaged ") else ins) if k == "item"]
            fluid = [(n, a) for k, n, a in (ins if name.startswith("Packaged ") else outs) if k == "fluid"]
            if name.startswith("Packaged ") or name.startswith("Unpackage "):
                (cname, cn), (pname, pn), (fname, fa) = container[0], packed[0], fluid[0]
                per = fa / pn
                if name.startswith("Packaged "):
                    path = add("filling", f"{where}/{rslug}", {"type": "create:filling", "ingredient": item(cname), "fluid_ingredient": fl_in(fname, mb(per)),
                        "result": res(pname), "processing_time": round(ticks / pn)})
                    PACKAGER.append({"kind": "fill", "in": made(cname), "fluid": FLUID_IDS[fname], "mb": mb(per), "out": made(pname), "ticks": round(ticks / pn), "path": path})
                else:
                    path = add("emptying", f"{where}/{rslug}", {"type": "create:emptying", "ingredient": item(pname), "result": res(cname),
                        "fluid_result": fl_out(fname, mb(per)), "processing_time": round(ticks / pn)})
                    PACKAGER.append({"kind": "empty", "in": made(pname), "fluid": FLUID_IDS[fname], "mb": mb(per), "out": made(cname),
                                     "ticks": round(ticks / pn), "path": path})
            else:
                raise SystemExit(f"recipes: Packager recipe {name} is neither packing nor unpacking")
        elif building in content.TWO_STEP:
            core, incomplete, charged = content.CORES[key]
            item_in = [(n, a) for k, n, a in ins if k == "item"]
            if core:
                mix(f"{where}/{rslug}_core", items=[n for n, a in item_in for _ in range(a - 1)], results=["siftec:" + core], time=ticks)
                base = "siftec:" + core
                steps = [n for n, a in item_in]
            else:
                base = item_in[0][0]
                steps = [n for n, a in item_in[1:]]
            if fluids:
                steps = [("fill", f, m) for f, m in fluids] + steps
            (out_name, out_n), = item_out
            entry = {"building": where, "result": made(out_name), "count": out_n, "first": item(base), "core": bool(core),
                     "steps": [{"fluid": FLUID_IDS[st[1]], "mb": st[2]} if isinstance(st, tuple) else {"item": item(st)} for st in steps],
                     "ticks": ticks}
            if charged:
                entry["seq"] = sequence(base, "siftec:" + charged, steps, 1, name=f"{where}/{rslug}", transitional="siftec:" + incomplete)
                (fname, fmb), = fluid_out
                add("emptying", f"{where}/{rslug}_drain", {"type": "create:emptying", "ingredient": item("siftec:" + charged),
                    "result": res(out_name), "fluid_result": fl_out(fname, fmb)})
                entry["charged"] = "siftec:" + charged
            else:
                entry["seq"] = sequence(base, out_name, steps, 1, name=f"{where}/{rslug}", n=out_n, transitional="siftec:" + incomplete)
            ASSEMBLY.append(entry)
        else:
            raise SystemExit(f"recipes: no machine for {building}")
        for path in set(OUT) - before:
            SATISFACTORY[path] = key
    end_alts()
    under(None)


# ---------------------------------------------------------------------------------------------------------------------
# The Equipment Workshop builds by hand. Each piece of equipment can also be made by machine, the Manufacturer's way:
# a Mixer makes its Core from all but one of each part, and one loop of sequenced assembly adds the rest.
EQUIPMENT_BY_MACHINE = content.EQUIPMENT_BY_MACHINE


def equipment_by_machine():
    for iid, sat in EQUIPMENT_BY_MACHINE.items():
        parts = [(c, n) for c, n in content.parse_cost(content.EQUIPMENT_COSTS[sat])]
        rid = iid.split(":")[1]
        core = content.EQUIPMENT_CORES[iid]
        under(content.EQUIPMENT_LOCKS.get(iid))
        mix(f"manufacturer/{rid}_core", items=[c for c, n in parts for _ in range(n - 1)], results=["siftec:" + core])
        seq = sequence("siftec:" + core, iid, [c for c, n in parts], 1, name=f"manufacturer/{rid}", transitional="siftec:incomplete_" + rid)
        ASSEMBLY.append({"building": "manufacturer", "result": iid, "count": 1, "first": "siftec:" + core, "core": True,
                         "steps": [{"item": item(c)} for c, n in parts], "ticks": 0, "seq": seq})
        under(None)


def vanilla():
    """The vanilla items MAM research lets machines make (content.VANILLA_RECIPES), each locked to its node: an Assembler
    recipe is a Mixer on a Basin; a Manufacturer recipe makes a Core in the Mixer from all but one of each part, then
    one loop of sequenced assembly adds the last of each."""
    for out, n, machine, parts, tree, node in content.VANILLA_RECIPES:
        rid = out.split(":")[1]
        under(content.mam(tree, node))
        if machine == "Assembler":
            mix(f"assembler/{rid}", items=[p for p, c in parts for _ in range(c)], results=[(out, n)])
        else:
            core, incomplete = content.VANILLA_CORES[out]
            mix(f"manufacturer/{rid}_core", items=[p for p, c in parts for _ in range(c - 1)], results=["siftec:" + core])
            seq = sequence("siftec:" + core, out, [p for p, c in parts], 1, name=f"manufacturer/{rid}", n=n, transitional="siftec:" + incomplete)
            ASSEMBLY.append({"building": "manufacturer", "result": out, "count": n, "first": "siftec:" + core, "core": True,
                             "steps": [{"item": item(p)} for p, c in parts], "ticks": 0, "seq": seq})
        under(None)


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
            out = []
            for r in body["result"]:
                o = dict(r["item"])
                if "chance" in r: o["chance"] = r["chance"]
                out.append(o)
            add("deploying", "kitchen_" + name, {"type": "create:deploying", "target": body["ingredients"][0], "ingredient": "#c:tools/knife",
                "keep_held_item": True, "results": out})
            KITCHEN.append("deploying/kitchen_" + name)


def food():
    """Vinegar, pickles, jams, cider and mead (the Nutrients research), each locked to its node."""
    FD = "farmersdelight:"
    # vinegar, bottled at the Spout (Bulk Fermentation; Preserved Foods takes 50)
    under(content.mam("Nutrients", "Bulk Fermentation"))
    add("bulk_fermenting", "vinegar", {"type": "createdieselgenerators:bulk_fermenting", "processing_time": 400,
        "ingredients": ["minecraft:sugar", "minecraft:sugar"], "fluid_ingredients": [fl_in("water", 250)], "fluid_results": [fl_out("vinegar", 250)]})
    spout("minecraft:glass_bottle", "vinegar", 250, "siftec:vinegar_bottle", name="vinegar_bottle")
    # mead, and cider from apples bottled as Farmer's Delight's Apple Cider (Cider and Mead)
    under(content.mam("Nutrients", "Cider and Mead"))
    add("bulk_fermenting", "mead", {"type": "createdieselgenerators:bulk_fermenting", "processing_time": 400,
        "ingredients": ["minecraft:honey_bottle", "minecraft:honey_bottle"], "fluid_ingredients": [fl_in("water", 250)], "fluid_results": [fl_out("mead", 500)],
        "results": [{"id": "minecraft:glass_bottle", "count": 2}]})
    spout("minecraft:glass_bottle", "mead", 250, "siftec:mead", name="mead")
    add("bulk_fermenting", "cider", {"type": "createdieselgenerators:bulk_fermenting", "processing_time": 400,
        "ingredients": ["minecraft:apple", "minecraft:apple"], "fluid_ingredients": [fl_in("water", 250)], "fluid_results": [fl_out("cider", 500)]})
    spout("minecraft:glass_bottle", "cider", 250, FD + "apple_cider", name="apple_cider")
    # pickles and jams are the Preserved Foods research (their items carry no lock of their own, so the recipes do)
    under(content.mam("Nutrients", "Preserved Foods"))
    for what, source in (("tomato", FD + "tomato"), ("onion", FD + "onion"), ("cabbage", FD + "cabbage"), ("pumpkin", FD + "pumpkin_slice"),
                         ("carrot", "minecraft:carrot"), ("beetroot", "minecraft:beetroot"), ("kelp", "minecraft:kelp")):
        spout(source, "vinegar", 250, "siftec:pickled_" + what, name="pickled_" + what)
    for jam, fruit in (("sweet_berry_jam", "minecraft:sweet_berries"), ("glow_berry_jam", "minecraft:glow_berries"), ("apple_jam", "minecraft:apple"),
                       ("melon_jam", "minecraft:melon_slice")):
        mix(jam, items=[fruit, fruit, fruit, "minecraft:sugar"], results=["siftec:" + jam], heated=True)
    under(None)
    # crop waste into Biomass
    mill(FD + "straw", "Biomass", name="biomass_from_straw")
    mill(FD + "tree_bark", "Biomass", name="biomass_from_bark")


def carried_over():
    """The pack's own recipes that 1.0 has no counterpart for (Food and Brass tab, Power tab), and Create's machines
    rewritten with the pack's parts."""
    # more Biomass than 1.0's leaves, wood, mushrooms and Alien Protein: crops in the Millstone, with HUB Upgrade 6
    under("hub_upgrade_6")
    for crop in ("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "kelp"):
        mill("minecraft:" + crop, "Biomass", name="biomass_from_" + crop, chance=0.25)
    # Create already mills these into sugar, green dye and seeds, so their Biomass comes from the Saw (which picks by filter)
    for crop in ("sugar_cane", "cactus", "short_grass"):
        saw("minecraft:" + crop, "Biomass", name="biomass_from_" + crop)
    under(None)
    # Create's own machines, rewritten with the pack's parts so each can be built at the tier that unlocks it
    # (Create's recipes need brass and Electron Tubes, which come later; theirs are in REMOVED)
    shaped("create:flywheel", ["RSR", "SAS", "RSR"], {"R": "Iron Rod", "S": "Iron Sheet", "A": "create:shaft"}, name="flywheel")  # HUB Upgrade 6
    shaped("create:deployer", ["R", "C", "P"], {"R": "Rotor", "C": "create:andesite_casing", "P": "Reinforced Iron Plate"}, name="deployer")
    # the Rebar Gun (Gunsmithing's nail gun) and the Xeno-Basher (its hammer) are built at the Equipment Workshop (WORKSHOP)
    # Crushing Wheels (Advanced Steel Production) on a crafting table: Create's is a 5x5 Mechanical Crafter recipe
    shaped("create:crushing_wheel", ["ASA", "SRS", "ASA"], {"A": "create:andesite_alloy", "S": "Steel Beam", "R": "Rotor"}, 2, name="crushing_wheel")
    # the Pumpjack crank on a crafting table: Diesel Generators' is a 3x5 Mechanical Crafter recipe (Tier 6)
    shaped("createdieselgenerators:pumpjack_crank", ["AIA", "ZSZ", "AIA"],
           {"A": "create:andesite_alloy", "I": "#c:plates/iron", "S": "create:shaft", "Z": "#c:ingots/zinc"}, name="pumpjack_crank")
    # the Manufacturer (Industrial Manufacturing): Create's needs brass and Electron Tubes, which are MAM research
    shaped("create:mechanical_crafter", ["B", "C", "T"], {"B": "Circuit Board", "C": "siftec:steel_casing", "T": "minecraft:crafting_table"}, 3,
           name="mechanical_crafter")
    # what the old miners drilled with (Mk.2 Drilling Mud, Mk.3 Coolant); the Build Gun miners decide in work package 5
    mix("drilling_mud", items=["minecraft:clay_ball", "minecraft:gravel"], fluids=[("water", 250)], fluid_results=[("drilling mud", 500)])
    mix("coolant", fluids=[("diesel", 250), ("water", 250)], fluid_results=[("coolant", 500)])
    # a wire for the grid (New Age's copper wire): one Cable pressed flat, as 1.0 builds a Power Line from 1 Cable
    press("Cable", "create_new_age:copper_wire", name="copper_wire")
    # brass, the pack's Caterium: crushed copper and zinc with a flux, then smelted
    under(content.mam("Caterium", "Brass"))
    for flux, n in (("minecraft:sand", 1), ("Coal", 2), ("Sulfur", 3)):
        mix("brass_blend_with_" + slug(flux), items=["create:crushed_raw_copper", "create:crushed_raw_zinc", flux], results=[("Brass Blend", n)])
    smelt("Brass Blend", "Brass Ingot", name="brass_ingot_from_blend")
    under(None)
    # the Assembler makes Create's brass-tier parts too (Create's own recipes stay as well)
    under(content.mam("Caterium", "Caterium Electronics"))
    deploy("Iron Sheet", "Quickwire", "Electron Tube")
    deploy("Brass Sheet", "Rotor", "Precision Mechanism")
    under(None)
    # the old tweaks pack: gunpowder, Ignimbrite and what it makes
    under(content.mam("Sulfur", "Black Powder"))
    for carbon in ("minecraft:coal", "minecraft:charcoal"):
        mix("gunpowder_with_" + slug(carbon), items=["Sulfur", carbon, "minecraft:bone_meal"], results=[("Gunpowder", 3)])
    under(content.mam("Sulfur", "Ignimbrite"))   # the only way to lava (Ignimbrite, then magma, then lava)
    mix("ignimbrite", items=["minecraft:cobblestone", "Sulfur"], fluid_results=[("ignimbrite", 500)], heated=True)
    spout("minecraft:clay_ball", "ignimbrite", 250, "minecraft:fire_charge")
    compact("magma_block", fluids=[("ignimbrite", 250)], results=["minecraft:magma_block"])
    mix("lava_from_magma_block", items=["minecraft:magma_block"], fluid_results=[("lava", 250)], heated=True)
    under(content.mam("Sulfur", "Explosives"))
    spout("minecraft:barrel", "ignimbrite", 250, "minecraft:tnt")
    under(None)
    # the sulfuric acid loop (Power tab): neutralise it, or use it and deal with the residue
    under("control_system_development")
    mix("gypsum_from_calcite", items=["minecraft:calcite"], fluids=[("sulfuric acid", 250)], results=["siftec:gypsum"], fluid_results=[("water", 250)])
    for ore, ingot in (("iron", "minecraft:iron_ingot"), ("copper", "minecraft:copper_ingot"), ("zinc", "create:zinc_ingot")):
        mix("acid_leached_" + ore, items=["create:crushed_raw_" + ore], fluids=[("sulfuric acid", 250)],
            results=[(ingot, 2), "siftec:toxic_residue"], heated=True)
    mix("sulfur_from_acid", fluids=[("sulfuric acid", 500)], results=["Sulfur", "siftec:toxic_residue"], heated=True)
    # Toxic Residue packed into paper shot, for a gun with the Blunderbuss Barrel
    hand("siftec:toxic_shot", [(1, "cgs:paper_shot"), (1, "siftec:toxic_residue")], name="toxic_shot")
    deploy("cgs:paper_shot", "siftec:toxic_residue", "siftec:toxic_shot", name="toxic_shot")
    under(None)
    # Heat (Food and Brass tab): superheating is gone, so a recipe that asked for it becomes a heated one that also takes
    # diesel, which puts it at Tier 5. Create: New Age's Blank Circuit was the one left (its own recipe is in REMOVED).
    under("expanded_power_infrastructure")
    compact("blank_circuit", items=["#c:stones", "#c:dusts/redstone", "#c:ingots/iron", "minecraft:quartz"], fluids=[("diesel", 100)],
            results=[("create_new_age:blank_circuit", 4)], heated=True)
    under(None)
    # Steel Casing: steel on a stripped log, by hand or by Deployer, like Create's own casings
    for wood in ("stripped_logs", "stripped_woods"):
        add("item_application", "steel_casing_from_" + wood, {"type": "create:item_application", "target": "#c:" + wood, "ingredient": item("Steel Ingot"),
            "results": [{"id": "siftec:steel_casing"}]})
        add("deploying", "steel_casing_from_" + wood, {"type": "create:deploying", "target": "#c:" + wood, "ingredient": item("Steel Ingot"),
            "results": [{"id": "siftec:steel_casing"}]})
    # Block of Sulfur (vanilla's sulfur block) packs four Gunsmithing sulfur
    shaped("minecraft:sulfur", ["SS", "SS"], {"S": "Sulfur"}, name="block_of_sulfur")
    hand("Sulfur", [(1, "minecraft:sulfur")], 4, name="sulfur_from_block")


# The Particle Accelerator's and the Converter's recipes on their machines (content.PROCESSOR_CHAINS): the Mixer's Core,
# the Crushing Wheels' crushing or the Deployer's steps and the fan's haunting, and the Item Drain emptying a charged
# cell. Each recipe's paths, for building/Processors.java.
PROCESSING = {}


def processors():
    global _under
    emptied, done_empty = {}, set()
    for machine, (pname, recipe_list) in content.PROCESSORS.items():
        for p in recipe_list:
            ch = content.PROCESSOR_CHAINS[(machine, p["name"])]
            s = content.slug(p["name"])
            b = ch["batch"]
            ticks = round(p["seconds"] * TICKS) * b
            # a Core carries b crafts (content.CORE_SECONDS): its results are b crafts' worth
            outs = [(n, c * b) for n, c in (x for x in (p["out"], p.get("also")) if x)]
            if p["alt"]:
                took = ", ".join([f"{c} {n}" for n, c in p["in"]] + ([f"{p['fluid_in'][1]} mB {p['fluid_in'][0]}"] if p["fluid_in"] else []))
                made_text = ", ".join([f"{c // b} {n}" for n, c in outs] + ([f"{p['fluid_out'][1]} mB {p['fluid_out'][0]}"] if p["fluid_out"] else []))
                alt(p["alt"], p["name"], f"{took} makes {made_text} ({pname})")
                _under = None
            else:
                end_alts()
                _under = p["unlock"]
            paths = {}
            if ch["mix"] or p["fluid_in"]:
                paths["mix"] = mix(f"{machine}/{s}", items=[n for n, c in ch["mix"] for _ in range(c)],
                                   fluids=[(p["fluid_in"][0], p["fluid_in"][1] * b)] if p["fluid_in"] else [], results=["siftec:" + ch["core"]], time=ticks)
            if machine == "particle_accelerator":
                paths["crush"] = add("crushing", f"{machine}/{s}", {"type": "create:crushing", "ingredient": item("siftec:" + ch["crush"]),
                                                                   "processing_time": 20, "results": results(outs)})
            else:
                for k, (on, applied, makes) in enumerate(ch["steps"]):
                    paths.setdefault("deploy", []).append(deploy(on, applied, makes, name=f"{machine}/{s}" + ("_cell" if applied.endswith("converter_cell") else "")))
                haunted = []
                if ch["charged"]:
                    haunted.append(res("siftec:" + ch["charged"]))
                haunted += results(outs)
                paths["haunt"] = add("haunting", f"{machine}/{s}", {"type": "create:haunting", "ingredient": item(ch["haunt"]), "results": haunted})
                if ch["charged"]:
                    # a charged cell is emptied the same for every recipe that fills it
                    if emptied.setdefault(ch["charged"], b * p["fluid_out"][1]) != b * p["fluid_out"][1]:
                        raise SystemExit(f"{p['name']}: its charged cell would hold another amount than another recipe's")
                if ch["charged"] and ch["charged"] not in done_empty:
                    done_empty.add(ch["charged"])
                    fname, fmb = p["fluid_out"][0], p["fluid_out"][1] * b
                    paths["empty"] = add("emptying", f"{machine}/{ch['charged']}", {"type": "create:emptying", "ingredient": item("siftec:" + ch["charged"]),
                                                                                 "result": res("siftec:" + content.CONVERTER_CELL), "fluid_result": fl_out(fname, fmb)})
            PROCESSING[(machine, p["name"])] = paths
    end_alts()
    under(None)


def build():
    OUT.clear()
    ALTS.clear()
    ASSEMBLY.clear()
    PACKAGER.clear()
    KITCHEN.clear()
    LOCKED.clear()
    SATISFACTORY.clear()
    PROCESSING.clear()
    satisfactory()
    processors()
    equipment_by_machine()
    backpack_upgrades()
    vanilla()
    kitchen()
    food()
    carried_over()
    basin_times()
    return dict(OUT)


def _sat_seconds(name):
    """Satisfactory 1.0's time for one of its recipes, by name."""
    return next(secs for key, n, building, alt, secs, ins, outs, unlock in content.sat_recipes() if n == name)


def _sat_median(building):
    """The middle time of a building's standard 1.0 recipes."""
    times = sorted(secs for key, n, b, alt, secs, ins, outs, unlock in content.sat_recipes() if b == building and not alt)
    return times[len(times) // 2]


def basin_times():
    """Every Basin recipe has a time a building keeps to (building/MachineTimes.java). The 1.0 recipes carry their own;
    the rest take the time of the 1.0 recipe they stand in for, or their building's usual time:
      Assembler recipes (backpack upgrades, Eye of Ender, Shulker Shell)  the Assembler's middle time (12 s)
      Manufacturer Cores (equipment and vanilla items)                    the Manufacturer's middle time, on its line too
      brass blend (Caterium)                                              Caterium Ingot (4 s)
      gunpowder                                                           Black Powder (4 s)
      acid leaching                                                       Leached Iron Ingot, per ingot made
      sulfur from acid, gypsum                                            Sulfuric Acid, per Sulfur
      Blank Circuit                                                       Circuit Board (8 s)
      Ignimbrite, lava, magma, drilling mud, coolant                      the Refinery's middle time (6 s)
      kitchen recipes and jams                                            the Cooking Pot's own time (10 s unless it says)"""
    import json, os
    assembler, manufacturer, refinery = _sat_median("Assembler"), _sat_median("Manufacturer"), _sat_median("Refinery")
    leached = _sat_seconds("Alternate: Leached Iron ingot") / 10   # 5 Raw Iron and 1 m3 acid make 10 Iron Ingots
    acid = _sat_seconds("Sulfuric Acid") / 5                       # 5 Sulfur a run
    pot = {}
    fd = os.path.join(os.path.dirname(__file__), "pack", "farmersdelight.json")
    if os.path.exists(fd):
        for key, body in json.load(open(fd)).items():
            if body.get("type") == "farmersdelight:cooking":
                pot["mixing/kitchen_" + key.split("/")[-1][:-5]] = body.get("cookingtime", 200) / TICKS
    for path, body in OUT.items():
        if body["type"] not in ("create:mixing", "create:compacting") or body.get("processing_time"):
            continue
        name = path.split("/")[-1]
        if path.startswith("mixing/assembler/"):
            secs = assembler
        elif path.startswith("mixing/manufacturer/"):
            secs = manufacturer
        elif name.startswith("brass_blend_with_"):
            secs = _sat_seconds("Caterium Ingot")
        elif name.startswith("gunpowder_with_"):
            secs = _sat_seconds("Black Powder")
        elif name.startswith("acid_leached_"):
            secs = leached * sum(r.get("count", 1) for r in body["results"] if not r["id"].startswith("siftec:"))
        elif name in ("sulfur_from_acid", "gypsum_from_calcite"):
            secs = acid
        elif name == "blank_circuit":
            secs = _sat_seconds("Circuit Board")
        elif name.startswith("kitchen_") or name.endswith("_jam"):
            secs = pot.get(path, 200 / TICKS)
        else:
            secs = refinery
        body["processing_time"] = round(secs * TICKS)
    # the Manufacturer's line for the same items goes at the same pace as their Cores
    for entry in ASSEMBLY:
        if not entry["ticks"]:
            entry["ticks"] = round(manufacturer * TICKS)


# Recipes from other mods that the pack takes out.
REMOVED = [
    "cgs:mixing/steel_ingot",                                   # superheated steel; the Foundry recipe replaces it
    "create:mixing/brass_ingot",                                # brass comes from brass blend
    "create:mixing/lava_from_cobble",                           # superheated; lava comes from Ignimbrite
    "createdieselgenerators:distillation/superheated_crude_oil",
    "create_new_age:compacting/blank_circuit",                  # superheated; the heated diesel recipe in carried_over() replaces it
    "create:crafting/kinetics/water_wheel", "create:crafting/kinetics/large_water_wheel",
    # washing crushed ore would make ingots outside 1.0's recipes (the Pure Iron and Pure Copper Ingot alternates)
    "create:splashing/crushed_raw_iron", "create:splashing/crushed_raw_copper",
    # rewritten in carried_over() with the pack's parts, so each machine can be built at the tier that unlocks it
    "create:crafting/kinetics/deployer", "create:crafting/kinetics/mechanical_crafter", "create:crafting/kinetics/flywheel", "create:mechanical_crafting/crushing_wheel",
    "createdieselgenerators:mechanical_crafting/pumpjack_crank", "cgs:mechanical_crafting/hammer", "cgs:mechanical_crafting/nailgun",
    # sulfur and lava come only by the owner's routes: no sulfur from crushing magma (Gunsmithing ships this one under
    # Create's name), no lava from fermenting cobblestone, and no Potent Sulfur (a geyser) from nine Blocks of Sulfur
    "create:crushing/magma_block", "createdieselgenerators:bulk_fermenting/lava", "minecraft:potent_sulfur",
    # Create's dough replaces Farmer's Delight's
    "farmersdelight:wheat_dough_from_egg", "farmersdelight:wheat_dough_from_water", "farmersdelight:bread_from_smelting",
    "farmersdelight:bread_from_smoking",
    # an ingot pressed flat would be Iron or Copper Sheet without the Constructor: only the Constructor makes them
    "create:pressing/iron_ingot", "create:pressing/copper_ingot",
]


def _foreign_locks():
    """Other mods' recipes the pack locks by their own id (their result alone does not say what unlocks them): every
    Create: Diesel Generators recipe that makes biodiesel (Satisfactory's Liquid Biofuel) needs Fluid Packaging.
    Read from the recipes shipped in Diesel Generators (tools/pack/diesel.json)."""
    import json, os
    out = {}
    path = os.path.join(os.path.dirname(__file__), "pack", "diesel.json")
    if not os.path.exists(path):
        return out
    for key, body in sorted(json.load(open(path)).items()):
        if not key.startswith("data/createdieselgenerators/recipe/"):
            continue
        made = [r.get("id") for r in body.get("fluid_results", []) + body.get("results", []) if isinstance(r, dict)]
        if "createdieselgenerators:biodiesel" in made:
            out["createdieselgenerators:" + key[len("data/createdieselgenerators/recipe/"):-len(".json")]] = "fluid_packaging"
    return out


FOREIGN_LOCKS = _foreign_locks()
# What the Equipment Workshop builds: (item id, cost). It only offers what the company has unlocked. Equipment and the
# buildings 1.0 has cost what they cost in 1.0 (content.EQUIPMENT_COSTS and content.BUILD_COSTS, under the 1.0 name);
# the buildings have moved to the Build Gun in work packages 5 and 9 (the HUB is every player's to start with and costs its
# 20 Raw Iron when it goes down). The rest are the pack's own.
_B, _E = content.BUILD_COSTS, content.EQUIPMENT_COSTS
WORKSHOP = [
    ("siftec:node_scanner", "2 Iron Sheet, 4 Wire"),
    ("siftec:claim_marker", "5 Iron Rod, 5 Stone"),
    ("siftec:mam", _B["MAM"]),
    ("siftec:object_scanner", _E["Object Scanner"]),
    ("siftec:speed_governor", "25 Quickwire, 2 Rotor, 2 Reinforced Iron Plate"),
    ("siftec:landing_pad", _B["U-Jelly Landing Pad"]),
    ("siftec:jetpack", _E["Jetpack"]),
    ("siftec:hazmat_suit", _E["Hazmat Suit"]),
    ("siftec:hover_pack", _E["Hoverpack"]),
    ("siftec:blade_runners", _E["Blade Runners"]),
    ("siftec:parachute", _E["Parachute"]),
    ("siftec:gas_mask", _E["Gas Mask"]),
    ("siftec:protein_inhaler", _E["Protein Inhaler"]),
    ("siftec:vitamin_inhaler", _E["Vitamin Inhaler"]),
    ("siftec:therapeutic_inhaler", _E["Therapeutic Inhaler"]),
    ("siftec:nutritional_inhaler", _E["Nutritional Inhaler"]),
    ("siftec:cardboard_drone", _B["Drone"]),
    ("siftec:main_portal", _B["Main Portal"]),
    ("siftec:satellite_portal", _B["Satellite Portal"]),
    ("siftec:dimensional_depot", _B["Dimensional Depot Uploader"]),
    # equipment from 1.0's Workshop list: the Xeno-Zapper (the pack's own), and Gunsmithing's nail gun and hammer as the
    # Rebar Gun and the Xeno-Basher
    ("siftec:xeno_zapper", _E["Xeno-Zapper"]),
    ("cgs:nailgun", _E["Rebar Gun"]),
    ("cgs:hammer", _E["Xeno-Basher"]),
    # the Particle Accelerator, the Converter, the Resource Well Extractor, the AWESOME Sink and Shop and the Radar Tower
    # are Build Gun buildings (work packages 9 and 10)
]
# Traveler's Backpack upgrades (Exploration and Equipment tab, Backpack): built at the Equipment Workshop, each once its
# MAM node is researched (content.TB_UPGRADES), from the pack's parts. The mod's own crafting recipes are removed
# (REMOVED); the Assembler (a Mixer on a Basin) makes each one too, under the same lock (backpack_upgrades()).
TB = content.TB
BACKPACK_UPGRADES = [
    (TB + "crafting_upgrade", "4 Reinforced Iron Plate, 25 Screw"),
    (TB + "tanks_upgrade", "10 Copper Sheet, 2 Rotor"),
    (TB + "backpack_tank", "5 Copper Sheet, 4 minecraft:glass"),
    (TB + "hose_nozzle", "2 Copper Sheet, 4 Screw"),
    (TB + "hose", "1 travelersbackpack:hose_nozzle, 5 Cable"),
    (TB + "pickup_upgrade", "20 Quickwire, 2 Rotor"),
    (TB + "lantern_upgrade", "10 Silica, 4 Wire"),
    (TB + "magnet_upgrade", "2 Crystal Oscillator, 10 Quickwire"),
    (TB + "jukebox_upgrade", "1 minecraft:jukebox, 2 Crystal Oscillator"),
    (TB + "feeding_upgrade", "1 minecraft:golden_carrot, 2 Rotor, 10 Iron Rod"),
    (TB + "smoker_upgrade", "1 minecraft:smoker, 5 Reinforced Iron Plate"),
    (TB + "furnace_upgrade", "1 minecraft:furnace, 5 Reinforced Iron Plate"),
    (TB + "blast_furnace_upgrade", "1 minecraft:blast_furnace, 10 Steel Pipe"),
    (TB + "void_upgrade", "1 SAM Fluctuator, 5 Iron Sheet"),
    (TB + "refill_upgrade", "1 SAM Fluctuator, 2 Rotor"),
]
WORKSHOP += BACKPACK_UPGRADES
# the mod's own recipe for each upgrade has the upgrade's id; the backpack itself, its designs, tier upgrades and sleeping
# bags are switched off (content.DISABLED)
REMOVED += [out for out, cost in BACKPACK_UPGRADES]


def backpack_upgrades():
    """Each Traveler's Backpack upgrade from the Assembler (a Mixer on a Basin), locked to its MAM node like the
    Workshop build."""
    lock_of = {TB + i: content.mam(tree, node) for (tree, node), items in content.TB_UPGRADES.items() for i in items}
    for out, cost in BACKPACK_UPGRADES:
        under(lock_of[out])
        mix(f"assembler/backpack/{out.split(':')[1]}", items=[c for c, n in content.parse_cost(cost) for _ in range(n)], results=[out])
        under(None)
