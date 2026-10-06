#!/usr/bin/env python3
"""Writes the mod's blockstates, models, item definitions, lang file and loot tables.
Every texture is borrowed from vanilla or Create for now; nothing is drawn here."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import content
import recipes

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "siftec")
D = os.path.join(ROOT, "data", "siftec")

def write(path, obj):
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

# node rock
write(f"{A}/blockstates/node_rock.json", {"variants": {"": {"model": "siftec:block/node_rock"}}})
write(f"{A}/models/block/node_rock.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "minecraft:block/cobbled_deepslate"}})
item_def("node_rock", "siftec:block/node_rock")

for id, (name, tex) in NODES.items():
    b = f"{id}_node"
    lang[f"block.siftec.{b}"] = f"{name} Node"
    lang[f"siftec.node.{id}"] = name
    write(f"{A}/blockstates/{b}.json", {"variants": {
        "core=false": {"model": f"siftec:block/{b}"},
        "core=true": {"model": f"siftec:block/{b}_core"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": tex}})
    # the middle block of the mound, where the miner goes: marked on top
    write(f"{A}/models/block/{b}_core.json", {"parent": "minecraft:block/cube_bottom_top",
          "textures": {"top": "minecraft:block/lodestone_top", "side": tex, "bottom": tex}})
    item_def(b, f"siftec:block/{b}")

# the middle of an oil pool
write(f"{A}/blockstates/oil_well.json", {"variants": {"": {"model": "siftec:block/oil_node_core"}}})
item_def("oil_well", "siftec:block/oil_node_core")
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
             "siftec.geyser.none": "Geyser Engine: no geyser below (%s mB acid)", "siftec.geyser.waiting": "Geyser Engine: waiting for the next eruption (%s mB acid)"})
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

lang.update({"siftec.engine.running": "Furnace Engine: running", "siftec.engine.running_hub": "HUB Engine: running (%s fuel left)",
             "siftec.engine.no_furnace": "Furnace Engine: needs a burning furnace beside or under it",
             "siftec.engine.no_hub": "HUB Engine: has to touch a HUB", "siftec.engine.no_fuel": "HUB Engine: out of fuel. Click it with coal or any furnace fuel",
             "siftec.engine.too_many": "HUB Engine: this HUB cannot run that many engines yet"})
# ---- equipment: stand-in icons from vanilla items
EQUIPMENT = {"jetpack": ("Jetpack", "minecraft:item/firework_rocket"), "hover_pack": ("Hover Pack", "minecraft:item/elytra"),
             "parachute": ("Parachute", "minecraft:item/phantom_membrane"), "hazmat_suit": ("Hazmat Suit", "minecraft:item/leather_chestplate"),
             "gas_mask": ("Gas Mask", "minecraft:item/leather_helmet"), "blade_runners": ("Blade Runners", "minecraft:item/iron_boots"),
             "zipline": ("Zipline", "minecraft:item/lead")}
for eid, (ename, etex) in EQUIPMENT.items():
    lang[f"item.siftec.{eid}"] = ename
    write(f"{A}/models/item/{eid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": etex}})
    item_def(eid, f"siftec:item/{eid}")
lang.update({"siftec.jetpack.empty": "Jetpack: out of fuel. It burns Solid Biofuel, Compacted Coal, diesel or Turbofuel",
             "siftec.zipline.no_line": "No Power Line leads that way from this pole",
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
             "siftec.building.family.stone_wood": "logs, planks, stone bricks, polished stone, deepslate bricks, and any stairs, slabs, fences, walls and doors",
             "siftec.building.family.andesite": "Andesite Casing or Block of Andesite Alloy",
             "siftec.building.family.steel": "Block of Steel, Block of Industrial Iron or Block of Iron",
             "siftec.building.family.copper": "Copper Casing or copper blocks", "siftec.building.family.brass": "Brass Casing, Block of Brass or Train Casing"})
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

KNOWN = set()
ids_file = os.path.join(os.path.dirname(__file__), "known_ids.txt")
if os.path.exists(ids_file):
    KNOWN = set(open(ids_file).read().split())

def check(item):
    ns = item.split(":")[0]
    if KNOWN and ns in ("create", "cgs", "createdieselgenerators", "create_hypertube") and item not in KNOWN:
        print("warning: unknown item id", item)

data = {"parts": [], "milestones": [], "phases": [], "disabled": content.DISABLED}
for i in content.DISABLED: check(i)
for pid, name, tex, tint in content.PARTS:
    data["parts"].append({"id": pid})
    lang[f"item.siftec.{pid}"] = name
    write(f"{A}/models/item/{pid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": tex}})
    item_def(pid, f"siftec:item/{pid}", tint)
# the tier tabs show the tier's first milestones in their tooltip
for tier, mid, name, cost, time, text, items, tokens in content.MILESTONES:
    for i in items: check(i)
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
    check(icon)
    ids = [f"mam_{tree_id}_{n + 1}" for n in range(len(nodes))]
    data["trees"].append({"id": tree_id, "icon": icon, "nodes": ids})
    lang[f"siftec.tree.{tree_id}"] = tree_name
    for n, (name, cost, time, text, needs, items, tokens) in enumerate(nodes):
        for i in items: check(i)
        if needs is None:
            needs = [n] if n > 0 else []
        data["milestones_mam"] = data.get("milestones_mam", [])
        data["milestones_mam"].append({"id": ids[n], "tree": tree_id, "cost": [{"item": i, "count": c} for i, c in content.parse_cost(cost)],
                                       "seconds": secs(time), "items": items, "tokens": tokens, "needs": [ids[k - 1] for k in needs]})
        lang[f"siftec.milestone.{ids[n]}"] = name
        lang[f"siftec.milestone.{ids[n]}.unlocks"] = text
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
             "siftec.collect.got": "Collected: %s", "siftec.scanner.none_object": "No %s left in range"})
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
for i, c, p in content.SHOP: check(i)
lang.update({"block.siftec.awesome_sink": "AWESOME Sink", "block.siftec.awesome_shop": "AWESOME Shop", "siftec.sink.points": "AWESOME points: %s",
             "siftec.shop.title": "AWESOME Shop: %s points", "siftec.shop.price": "%s points", "siftec.shop.poor": "Not enough points",
             "siftec.sift.enter": "Enter The Sift", "siftec.sift.missing": "The Sift is not installed on this server"})
for b, top in (("awesome_sink", "minecraft:block/hopper_top"), ("awesome_shop", "minecraft:block/barrel_top")):
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": top, "side": "create:block/andesite_casing", "bottom": "create:block/andesite_casing"}})
    item_def(b, f"siftec:block/{b}")
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}]}]})
for i in content.ALIAS.values(): check(i)
# ---- fluids
data["fluids"] = []
for fid, name, colour in content.FLUIDS:
    data["fluids"].append({"id": fid, "color": colour})
    lang[f"block.siftec.{fid}"] = name
    lang[f"fluid.siftec.{fid}"] = name
    lang[f"item.siftec.{fid}_bucket"] = name + " Bucket"
    write(f"{A}/blockstates/{fid}.json", {"variants": {"": {"model": "siftec:block/fluid"}}})
    write(f"{A}/models/item/{fid}_bucket.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/milk_bucket"}})
    item_def(f"{fid}_bucket", f"siftec:item/{fid}_bucket", colour)
write(f"{A}/models/block/fluid.json", {"textures": {"particle": "minecraft:block/water_still"}})

# ---- recipes
import shutil
shutil.rmtree(f"{D}/recipe", ignore_errors=True)
for path, body in recipes.build().items():
    # a recipe that names another mod's item or fluid only loads when that mod is installed
    text = json.dumps(body)
    mods = [m for m in ("cgs", "createdieselgenerators", "create_hypertube") if f'"{m}:' in text or f'"#{m}:' in text]
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
    made = list(body.get("results", []))
    if "result" in body: made.append(body["result"])
    for r in made:
        rid = r if isinstance(r, str) else r.get("id", "")
        mid = lock_of.get(rid) or lock_of.get(rid + "_bucket")
        if mid:
            # the steps of a sequenced assembly get ids of their own, built from the result's name
            key = "seq:" + rid.split(":")[1] if body["type"] == "create:sequenced_assembly" else "siftec:" + path
            data["recipe_locks"][key] = mid
            break
# ---- alternates: each has its own lock, and is only offered once everything it uses is unlocked
C = "create:"
MACHINE_OF = {"create:cutting": [C + "mechanical_saw"], "create:deploying": [C + "deployer"], "create:mixing": [C + "mechanical_mixer"],
              "create:compacting": [C + "mechanical_press", C + "basin"], "create:splashing": [C + "encased_fan"], "create:mechanical_crafting": [C + "mechanical_crafter"],
              "minecraft:smelting": [], "minecraft:blasting": []}
FLUID_NEEDS = {"createdieselgenerators:diesel": "oil_processing", "createdieselgenerators:crude_oil": "oil_processing"}
built = recipes.build()


def ids_in(node, found):
    if isinstance(node, str):
        if ":" in node and not node.startswith("#") and " " not in node: found.add(node)
    elif isinstance(node, dict):
        for k, v in node.items():
            if k != "type": ids_in(v, found)
    elif isinstance(node, list):
        for v in node: ids_in(v, found)


def needs_of(things):
    out = []
    for t in things:
        mid = FLUID_NEEDS.get(t) or lock_of.get(t) or lock_of.get(t + "_bucket")
        if mid and mid not in out: out.append(mid)
    return out


data["alternates"] = []
for aid, info in recipes.ALTS.items():
    used = set()
    for path in info["paths"]:
        body = built[path]
        ids_in(body, used)
        used.update(MACHINE_OF[body["type"]])
        if body.get("heat_requirement"): used.add(C + "blaze_burner")
        data["recipe_locks"]["siftec:" + path] = "alt_" + aid
    data["alternates"].append({"id": "alt_" + aid, "requires": needs_of(sorted(used))})
    lang[f"siftec.alt.alt_{aid}"] = info["name"]
    lang[f"siftec.alt.alt_{aid}.text"] = info["text"]
for aid, aname, machine, items, fluid_in, result, seconds, text in content.ALT_PROCESSORS:
    used = {i for i, c in content.parse_cost(items)} | {i for i, c in content.parse_cost(result)} | {"siftec:" + machine}
    if fluid_in: used.add(recipes.FLUID_IDS[fluid_in[0]])
    data["alternates"].append({"id": "alt_" + aid, "requires": needs_of(sorted(used))})
    lang[f"siftec.alt.alt_{aid}"] = aname
    lang[f"siftec.alt.alt_{aid}.text"] = text
print("alternates:", len(data["alternates"]), "; with nothing required:", [a["id"] for a in data["alternates"] if not a["requires"]])
print("recipes locked to a milestone:", len(data["recipe_locks"]), "of", len(recipes.build()))
data["removed_recipes"] = recipes.REMOVED
data["workshop"] = [{"item": i, "cost": [{"item": c, "count": n} for c, n in content.parse_cost(cost)]} for i, cost in recipes.WORKSHOP]
lang.update({"block.siftec.equipment_workshop": "Equipment Workshop", "siftec.workshop.title": "Equipment Workshop",
             "siftec.workshop.click": "Click to build from the parts in your inventory", "siftec.workshop.missing": "You are missing parts for that",
             "siftec.workshop.built": "Built %s"})
write(f"{A}/blockstates/equipment_workshop.json", {"variants": {"": {"model": "siftec:block/equipment_workshop"}}})
write(f"{A}/models/block/equipment_workshop.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "minecraft:block/smithing_table_top", "side": "create:block/andesite_casing", "bottom": "create:block/andesite_casing"}})
item_def("equipment_workshop", "siftec:block/equipment_workshop")
write(f"{D}/loot_table/blocks/equipment_workshop.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
      "entries": [{"type": "minecraft:item", "name": "siftec:equipment_workshop"}]}]})
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
    "siftec.lock.item": "Locked: needs %s",
    "siftec.milestone.disabled": "nothing: it is switched off in this pack",
    "siftec.company.default_name": "%s's Company", "siftec.company.info": "%s: %s",
    "siftec.company.renamed": "Company renamed to %s", "siftec.company.invited": "Invited %s. They join with /company accept %s",
    "siftec.company.invite": "%s invited you to %s. Join with /company accept %s (you leave your own company)",
    "siftec.company.no_invite": "You have no invite from that player", "siftec.company.joined": "%s joined %s",
    "siftec.company.left": "You left and started %s",
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
for b in ("blueprint_designer", "blueprint_designer_mk3", "drone_port", "main_portal", "satellite_portal", "landing_pad", "radar_tower", "furnace_engine", "hub_engine", "geyser_engine", "claim_marker", "portable_miner", "miner_mk1", "miner_mk2", "miner_mk3", "resource_well_extractor", "dimensional_depot", "power_pole", "power_tower", "power_storage", "speed_governor"):
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
          "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}],
          "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
# pickaxe is the right tool for the miners
# a Pumpjack hole only counts an Oil Well as the bottom of its pipe
write(os.path.join(ROOT, "data", "createdieselgenerators", "tags", "block", "oil_deposit.json"), {"replace": True, "values": ["siftec:oil_well"]})
write(os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json"),
      {"replace": False, "values": ["siftec:portable_miner", "siftec:miner_mk1", "siftec:miner_mk2", "siftec:miner_mk3", "siftec:resource_well_extractor", "siftec:dimensional_depot", "siftec:power_pole", "siftec:power_tower", "siftec:power_storage", "siftec:speed_governor", "siftec:furnace_engine", "siftec:hub_engine", "siftec:landing_pad", "siftec:radar_tower", "siftec:blueprint_designer", "siftec:blueprint_designer_mk3", "siftec:drone_port", "siftec:main_portal", "siftec:satellite_portal", "siftec:mam", "siftec:claim_marker", "siftec:geyser_engine", "siftec:converter", "siftec:particle_accelerator", "siftec:awesome_sink", "siftec:awesome_shop", "siftec:hub", "siftec:wormhole_gateway", "siftec:equipment_workshop"]})
