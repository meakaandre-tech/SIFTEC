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

lang = {
    "itemGroup.siftec": "SIFTEC",
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

# ---- resource nodes (the Nodes and World tab). node/NodeLook.java holds the same table for the game; keep them in
# step. A site is a patch of the node's own natural stone (unbreakable), the node and a clump of ordinary blocks over
# it. An ore node is the real ore block itself (minecraft:iron_ore, create:zinc_ore, a stone node's stone), kept as a
# node by its position (node/NodeSites.java): it drops what that block always drops, and leaves air that grows back.
# Only resources neither the game nor Create has an ore of get a SIFTEC ore block, an ordinary ore in every way.
NODE_TYPES = {"iron": "Iron Ore", "copper": "Copper Ore", "stone": "Stone", "coal": "Coal", "zinc": "Zinc Ore", "oil": "Crude Oil",
              "bauxite": "Bauxite", "nitrogen": "Nitrogen Gas", "uranium": "Uranium", "sam": "SAM", "quartz": "Nether Quartz", "sulfur": "Sulfur"}
CS = "create:block/palettes/stone_types/"
# every patch stone: (name, block id, side texture, top texture). A patch block is siftec:<key>_patch, a plain
# unbreakable block of that stone. Stone nodes are one natural stone each (node/StoneKind.java), never deepslate,
# cobblestone or plain stone.
STONE = {
    "tuff": ("Tuff", "minecraft:tuff", "minecraft:block/tuff", "minecraft:block/tuff"),
    "granite": ("Granite", "minecraft:granite", "minecraft:block/granite", "minecraft:block/granite"),
    "diorite": ("Diorite", "minecraft:diorite", "minecraft:block/diorite", "minecraft:block/diorite"),
    "andesite": ("Andesite", "minecraft:andesite", "minecraft:block/andesite", "minecraft:block/andesite"),
    "calcite": ("Calcite", "minecraft:calcite", "minecraft:block/calcite", "minecraft:block/calcite"),
    "dripstone": ("Dripstone", "minecraft:dripstone_block", "minecraft:block/dripstone_block", "minecraft:block/dripstone_block"),
    "asurine": ("Asurine", "create:asurine", CS + "natural/asurine_0", CS + "natural/asurine_0"),
    "crimsite": ("Crimsite", "create:crimsite", CS + "natural/crimsite_0", CS + "natural/crimsite_0"),
    "ochrum": ("Ochrum", "create:ochrum", CS + "natural/ochrum_0", CS + "natural/ochrum_0"),
    "veridium": ("Veridium", "create:veridium", CS + "natural/veridium_0", CS + "natural/veridium_0"),
    "scoria": ("Scoria", "create:scoria", CS + "scoria", CS + "scoria"),
    "scorchia": ("Scorchia", "create:scorchia", CS + "scorchia", CS + "scorchia"),
    "stone": ("Stone", "minecraft:stone", "minecraft:block/stone", "minecraft:block/stone"),
    "basalt": ("Basalt", "minecraft:basalt", "minecraft:block/basalt_side", "minecraft:block/basalt_top"),
    "cinnabar": ("Cinnabar", "minecraft:cinnabar", "minecraft:block/cinnabar", "minecraft:block/cinnabar"),
    "blackstone": ("Blackstone", "minecraft:blackstone", "minecraft:block/blackstone", "minecraft:block/blackstone_top"),
    "deepslate": ("Deepslate", "minecraft:deepslate", "minecraft:block/deepslate", "minecraft:block/deepslate_top"),
    "netherrack": ("Netherrack", "minecraft:netherrack", "minecraft:block/netherrack", "minecraft:block/netherrack"),
    "sulfur": ("Sulfur", "minecraft:sulfur", "minecraft:block/sulfur", "minecraft:block/sulfur"),
}
STONE_KINDS = ["granite", "diorite", "andesite", "tuff", "calcite", "dripstone", "asurine", "crimsite", "ochrum", "veridium", "scoria", "scorchia"]
# every node look: (type, patch stone, the node block, what mining it drops without Fortune ("" for a fluid node))
DEPOSIT = {
    "iron": ("iron", "tuff", "minecraft:iron_ore", "minecraft:raw_iron"),
    "copper": ("copper", "granite", "minecraft:copper_ore", "minecraft:raw_copper"),
    **{k: ("stone", k, STONE[k][1], STONE[k][1]) for k in STONE_KINDS},
    "coal": ("coal", "stone", "minecraft:coal_ore", "minecraft:coal"),
    "zinc": ("zinc", "andesite", "create:zinc_ore", "create:raw_zinc"),
    "oil": ("oil", "basalt", "siftec:oil_well", ""),
    "bauxite": ("bauxite", "cinnabar", "siftec:bauxite_ore", "siftec:raw_bauxite"),
    "nitrogen": ("nitrogen", "diorite", "siftec:nitrogen_node", ""),
    "uranium": ("uranium", "blackstone", "siftec:uranium_ore", "siftec:uranium"),
    "sam": ("sam", "deepslate", "siftec:sam_ore", "siftec:sam"),
    "quartz": ("quartz", "netherrack", "minecraft:nether_quartz_ore", "minecraft:quartz"),
    "sulfur": ("sulfur", "sulfur", "siftec:sulfur_ore", "cgs:sulfur"),
}
# SIFTEC's own ore blocks: (name, texture). Bauxite, SAM and sulfur take the ore art drawn for their nodes (stone with
# the resource in it); uranium borrows the emerald ore texture until it has its own.
SIFTEC_ORES = {
    "bauxite_ore": ("Bauxite Ore", "siftec:block/bauxite_node_front"),
    "uranium_ore": ("Uranium Ore", "minecraft:block/emerald_ore"),
    "sam_ore": ("SAM Ore", "siftec:block/sam_node_front"),
    "sulfur_ore": ("Sulfur Ore", "siftec:block/sulfur_node_front"),
}
for t, name in NODE_TYPES.items():
    lang[f"siftec.node.{t}"] = name
lang["siftec.node.regrowing"] = "%s: mined, grows back in %s s"
for key, (name, block, side, top) in STONE.items():
    b = f"{key}_patch"
    lang[f"block.siftec.{b}"] = f"{name} Patch"
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_column", "textures": {"side": side, "end": top}})
for look, (t, patch, block, drop) in DEPOSIT.items():
    ns, path = block.split(":")
    if ns != "siftec":
        continue
    if path in SIFTEC_ORES:
        assert drop, look
        continue
    # the fluid nodes' own blocks: the Oil Well and the nitrogen node (unbreakable, never mined)
    assert not drop, look
    lang[f"block.siftec.{path}"] = "Oil Well" if look == "oil" else f"{NODE_TYPES[t]} Node"
    _, _, side, top = STONE[patch]
    if look == "oil":
        # the well: crude oil welling up in a basalt collar
        model = {"parent": "minecraft:block/cube_bottom_top", "textures": {"top": "siftec:block/oil_well_top", "side": side, "bottom": top}}
    else:
        model = {"parent": "minecraft:block/cube_all", "textures": {"all": "minecraft:block/packed_ice"}}
    write(f"{A}/models/block/{path}.json", model)
    write(f"{A}/blockstates/{path}.json", {"variants": {f"purity={p}": {"model": f"siftec:block/{path}"} for p in ("impure", "normal", "pure")}})
    item_def(path, f"siftec:block/{path}")
FLUID_NODE_IDS = sorted(DEPOSIT[k][2].split(":")[1] for k in DEPOSIT if not DEPOSIT[k][3])
ORE_DROPS = {DEPOSIT[k][2].split(":")[1]: DEPOSIT[k][3] for k in DEPOSIT if DEPOSIT[k][2].startswith("siftec:") and DEPOSIT[k][3]}
for b, (name, tex) in SIFTEC_ORES.items():
    lang[f"block.siftec.{b}"] = name
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": tex}})
    item_def(b, f"siftec:block/{b}")
    # vanilla's ore loot: Silk Touch keeps the block; otherwise the resource, more with Fortune by the ore formula (a
    # pickaxe's, or a miner's built-in Fortune III or V), less from an explosion
    # (the same shape as the game's own iron_ore.json)
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:alternatives", "children": [
        {"type": "minecraft:item", "condition": "minecraft:tool/can_silk_touch", "name": f"siftec:{b}"},
        {"type": "minecraft:item", "modifier": [
            {"type": "minecraft:apply_bonus", "enchantment": "minecraft:fortune", "formula": "minecraft:ore_drops"},
            {"type": "minecraft:explosion_decay"}], "name": ORE_DROPS[b]}]}]}], "random_sequence": f"siftec:blocks/{b}"})
lang["item.siftec.uranium"] = "Uranium"
NODE_IDS = set(FLUID_NODE_IDS) | set(SIFTEC_ORES)
# Recipes that take "Stone" (where Satisfactory takes Concrete) take any of these
write(os.path.join(ROOT, "data", "siftec", "tags", "item", "stones.json"), {"replace": False, "values":
      ["minecraft:stone", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:deepslate", "minecraft:blackstone", "minecraft:basalt"]
      + [STONE[k][1] for k in STONE_KINDS]})
lang["siftec.tag.stones"] = "any stone"
# Satisfactory's Mycelia is any mushroom; the Stinger's remains are what spiders drop (content.SAT_NAMES)
write(os.path.join(ROOT, "data", "siftec", "tags", "item", "mycelia.json"), {"replace": False, "values": ["minecraft:brown_mushroom", "minecraft:red_mushroom"]})
write(os.path.join(ROOT, "data", "siftec", "tags", "item", "spider_remains.json"), {"replace": False, "values": ["minecraft:spider_eye", "minecraft:string"]})
lang["siftec.tag.mycelia"] = "any mushroom"
lang["siftec.tag.spider_remains"] = "spider eye or string"
# any pickle or jam (the Automated Cooking research takes 20)
PRESERVE_TAG = ["siftec:pickled_" + w for w in ("tomato", "onion", "cabbage", "pumpkin", "carrot", "beetroot", "kelp")] \
    + ["siftec:" + w + "_jam" for w in ("sweet_berry", "glow_berry", "apple", "melon")]
write(os.path.join(ROOT, "data", "siftec", "tags", "item", "preserves.json"), {"replace": False, "values": PRESERVE_TAG})
lang["siftec.tag.preserves"] = "any pickle or jam"

# miners and the extractor: Create casings until real models are picked
lang.update({"block.siftec.dimensional_depot": "Dimensional Depot", "siftec.depot.title": "Cloud: %s", "siftec.depot.count": "%s in the cloud",
             "siftec.depot.click": "Click to take a stack", "siftec.depot.previous": "Previous page", "siftec.depot.next": "Next page",
             "siftec.depot.button": "Cloud", "siftec.depot.button.tooltip": "Your company's Dimensional Depot cloud: take items out",
             "siftec.depot.manual_locked": "Uploading from your inventory needs the Manual Depot Uploader research (MAM, Alien Technology)",
             "siftec.depot.uploaded": "Uploaded %s %s to the cloud", "siftec.depot.full": "The cloud has no room for that"})

lang.update({"siftec.claim.denied": "This land belongs to %s", "siftec.claim.budget": "Your company has used all %s of its Claim Markers",
             "siftec.claim.too_close": "Too close: markers must be more than %s chunks from another company's HUB",
             "siftec.claim.nothing": "Every chunk here is already claimed", "siftec.claim.claimed": "Claim Markers: %s of %s",
             "siftec.claim.hub": "HUB placed: %s chunks claimed"})
lang.update({"item.siftec.gypsum": "Gypsum", "item.siftec.toxic_residue": "Toxic Residue"})
for name, tex, tint in (("gypsum", "minecraft:item/bone_meal", None), ("toxic_residue", "minecraft:item/slime_ball", 0x90C020)):
    write(f"{A}/models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": tex}})
    item_def(name, f"siftec:item/{name}", tint)
MACHINES = {"blueprint_designer": ("Blueprint Designer Mk.1", "minecraft:block/lapis_block"), "blueprint_designer_mk3": ("Blueprint Designer Mk.3", "minecraft:block/diamond_block"), "drone_port": ("Drone Port", "create:block/cardboard_block_side"), "main_portal": ("Main Portal", "minecraft:block/crying_obsidian"), "satellite_portal": ("Satellite Portal", "minecraft:block/obsidian"), "landing_pad": ("Landing Pad", "minecraft:block/hay_block_side"), "claim_marker": ("Claim Marker", "minecraft:block/red_concrete"), "dimensional_depot": ("Dimensional Depot", "create:block/brass_casing"),
            "resource_well_extractor": ("Resource Well Extractor", "create:block/railway_casing")}
for b, (name, casing) in MACHINES.items():
    lang[f"block.siftec.{b}"] = name
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": "create:block/gearbox_top", "side": casing, "bottom": casing}})
    item_def(b, f"siftec:block/{b}")

# ---- power (power/, building/BuildingPower.java): everything shown in SU, Satisfactory's MW at 1 MW = 204.8 SU
lang.update({
    "siftec.power.grid": "grid: %s of %s SU used, %s SU spare%s",
    "siftec.power.grid_tripped": "grid: FUSE BLOWN (%s of %s SU, %s SU spare)%s",
    "siftec.power.grid_storage": ", storage %s of %s",
    "siftec.power.stored": "stores %s of %s",
    "siftec.power.gives": "gives %s of %s",
    "siftec.power.augmenter": "gives %s and +%s%% to its grid's generators",
    "siftec.power.no_power": "needs %s SU: no power",
    "siftec.power.fuse_blown": "FUSE BLOWN: the grid used more than it makes. Sneak-use a generator or Power Storage to reset it",
    "siftec.power.fuse_reset": "Fuse reset",
    "siftec.power.no_fuel": "out of fuel",
    "siftec.power.no_water": "no water",
    "siftec.power.waste_full": "waste output full",
    "siftec.power.no_geyser": "no geyser under it",
    "siftec.power.no_geyser_place": "A Geothermal Generator has to stand over a sulfur geyser, with a block of air over the geyser's water",
    "siftec.power.waiting": "waiting for the next eruption",
    "siftec.power.acid_full": "acid tank full: pipe the sulfuric acid away",
    "siftec.power.lines_full": "That connector takes no more wires",
    "siftec.power.too_long": "Too long: this wire can reach %s blocks",
    "siftec.burner.running": "HUB Biomass Burner: gives %s of %s, %s fuel left",
    "siftec.burner.no_fuel": "HUB Biomass Burner: out of fuel (%s of %s, %s left). Feed it leaves, logs, Biomass or Solid Biofuel",
    "siftec.burner.tripped": "HUB Biomass Burner: FUSE BLOWN (%s of %s, %s fuel). Sneak-use it to reset",
    "siftec.burner.fuel": "The Biomass Burner burns leaves, logs, mushrooms, Biomass and Solid Biofuel",
    "block.siftec.turbine": "Generator Turbine",
})
# ---- equipment: stand-in icons from vanilla items
EQUIPMENT = {"jetpack": ("Jetpack", "minecraft:item/firework_rocket"), "hover_pack": ("Hover Pack", "minecraft:item/elytra"),
             "parachute": ("Parachute", "minecraft:item/phantom_membrane"), "hazmat_suit": ("Hazmat Suit", "minecraft:item/leather_chestplate"),
             "gas_mask": ("Gas Mask", "minecraft:item/leather_helmet"), "blade_runners": ("Blade Runners", "minecraft:item/iron_boots")
             }
for eid, (ename, etex) in EQUIPMENT.items():
    lang[f"item.siftec.{eid}"] = ename
    write(f"{A}/models/item/{eid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": etex}})
    item_def(eid, f"siftec:item/{eid}")
# the MAM's inhalers and the Elytra Backpack (item/MamItems.java)
MAM_ITEMS = {"protein_inhaler": ("Protein Inhaler", "minecraft:item/potion", 0xE070C0),
             "vitamin_inhaler": ("Vitamin Inhaler", "minecraft:item/potion", 0xF0A040),
             "therapeutic_inhaler": ("Therapeutic Inhaler", "minecraft:item/potion", 0x60D0F0),
             "nutritional_inhaler": ("Nutritional Inhaler", "minecraft:item/potion", 0x80E060),
             "elytra_backpack": ("Elytra Backpack", "minecraft:item/elytra", 0xE8A060)}
for eid, (ename, etex, tint) in MAM_ITEMS.items():
    lang[f"item.siftec.{eid}"] = ename
    write(f"{A}/models/item/{eid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": etex}})
    item_def(eid, f"siftec:item/{eid}", tint)
lang.update({"siftec.inhaler.protein": "Heals 6 health", "siftec.inhaler.vitamin": "Regeneration II for 20 seconds",
             "siftec.inhaler.therapeutic": "Clears every bad effect and heals 4 health", "siftec.inhaler.nutritional": "Heals 14 health, and Regeneration for 10 seconds",
             "siftec.elytra_backpack.tip": "An Elytra and a Copper Backtank in one: glides, and feeds diving gear its air",
             "siftec.elytra_backpack.refill": "Use it on a placed backtank to take its air",
             "siftec.elytra_backpack.filled": "Took %s air from the backtank", "siftec.elytra_backpack.empty_tank": "That backtank has no air to give",
             "siftec.elytra_backpack.air": "Air: %s / %s"})
# the Elytra Backpack counts as a backtank for Create's diving gear and air-hungry tools
write(os.path.join(ROOT, "data", "create", "tags", "item", "pressurized_air_sources.json"), {"replace": False, "values": ["siftec:elytra_backpack"]})
lang.update({"siftec.jetpack.empty": "Jetpack: out of fuel. It burns Solid Biofuel, Compacted Coal, diesel or Turbofuel",
             "siftec.zipline.no_line": "No Power Line leads that way from this pole", "siftec.zipline.locked": "Riding Power Lines needs the Power Line Riding research (MAM, Caterium)",
             "siftec.crash.somersloop": "There was a Somersloop in the wreckage too",
             "siftec.radar.title": "Radar Tower: nearest of each, from here", "siftec.radar.line": "%s: %s blocks %s (x %s, z %s)",
             "siftec.radar.nothing": "Nothing in range", "siftec.radar.no_power": "The Radar Tower has no power",
             "siftec.radar.revealed": "Radar Tower: %s crash sites found. Add them to Xaero's map:",
             "siftec.map.unlocked": "Field Research: the Map is yours. Xaero's Minimap is switched on"})
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
# the old HUB building rule's planner: kept as an item that does nothing, so old inventories load (no recipe, not in the tab)
lang["item.siftec.hub_planner"] = "HUB Planner (no longer used)"
write(f"{A}/models/item/hub_planner.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/brush"}})
item_def("hub_planner", "siftec:item/hub_planner")

# ---- the HUB and Wormhole Gateway structures (prefab/Templates.java): shell blocks by style, and the HUB's parts
PREFAB = {  # style: (name, texture)
    # Polartt's palette, like the Build Gun's buildings: light and dark framed panels, flat orange, hazard stripes
    "white": ("HUB Panel", "minecraft:block/iron_block"), "orange": ("HUB Trim", "minecraft:block/orange_concrete"),
    "hazard_yellow": ("HUB Hazard Stripe", "minecraft:block/yellow_concrete"), "hazard_black": ("HUB Hazard Stripe (black)", "minecraft:block/black_concrete"),
    "metal": ("HUB Frame", "create:block/industrial_iron_block"), "floor": ("HUB Floor", "minecraft:block/light_gray_concrete"),
    "glass": ("HUB Window", "create:block/palettes/framed_glass"), "lamp": ("HUB Lamp", "minecraft:block/redstone_lamp_on"),
    "step": ("HUB Step", "minecraft:block/light_gray_concrete"), "locker": ("HUB Locker", "minecraft:block/iron_block"),
    "railway": ("Gateway Frame", "create:block/railway_casing"), "coil": ("Gateway Ring", "create:block/dark_metal_block"),
    "coil_lit": ("Gateway Ring (charged)", "minecraft:block/amethyst_block"), "rift": ("Gateway Rift", "minecraft:block/crying_obsidian"),
    "foundation": ("Structure Leg", "minecraft:block/gray_concrete"),
    "conduit": ("Gateway Conduit", "create:block/copper_casing"), "portal": ("Wormhole", "minecraft:block/nether_portal"),
}
for style, (name, tex) in PREFAB.items():
    b = f"prefab_{style}"
    lang[f"block.siftec.{b}"] = name
    write(f"{A}/blockstates/{b}.json", {"variants": {"": {"model": f"siftec:block/{b}"}}})
    if style == "step":
        write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/slab", "textures": {"bottom": tex, "top": tex, "side": tex}})
    else:
        write(f"{A}/models/block/{b}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": tex}})
ROT = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
lang.update({"block.siftec.hub_bed": "HUB Bunk", "block.siftec.personal_storage": "Personal Storage Box",
             "block.siftec.hub_burner": "HUB Biomass Burner"})
write(f"{A}/blockstates/hub_bed.json", {"variants": {f"facing={d},part={part}": ({"model": f"minecraft:block/orange_bed_{part}", "y": y} if y else {"model": f"minecraft:block/orange_bed_{part}"})
                                                     for d, y in ROT for part in ("foot", "head")}})
write(f"{A}/blockstates/personal_storage.json", {"variants": {"": {"model": "minecraft:block/barrel"}}})
# the built-in Biomass Burners keep the old HUB Engine's look until they have their own
write(f"{A}/blockstates/hub_burner.json", {"variants": {f"facing={d}": ({"model": "siftec:block/hub_engine", "y": y} if y else {"model": "siftec:block/hub_engine"}) for d, y in ROT}})
write(os.path.join(ROOT, "data", "minecraft", "tags", "block", "beds.json"), {"replace": False, "values": ["siftec:hub_bed"]})
lang.update({
    "siftec.prefab.hint": "Right-click to build the %s here. Sneak and right-click to turn it",
    "siftec.prefab.blocked": "%s blocks are in the way (marked red)",
    "siftec.prefab.no_ground": "No ground under part of it: it can stand at most %s blocks above the ground",
    "siftec.prefab.unloaded": "Part of that area is not loaded: come closer",
    "siftec.prefab.height": "It does not fit in the build height here",
    "siftec.prefab.entity": "Something is standing where it would go",
    "siftec.prefab.spawn": "Nothing can be built within %s blocks of spawn",
    "siftec.prefab.claimed": "That land belongs to %s",
    "siftec.prefab.one_gateway": "Your company already has a Wormhole Gateway (at %s). Pick it up from its terminal to move it",
    "siftec.prefab.aim": "Aim at the ground, at most %s blocks away",
    "siftec.prefab.turned": "Turned %s degrees",
    "siftec.prefab.no_sleep": "The HUB's bunks cannot be slept in here",
    "siftec.prefab.placed": "%s built",
    "siftec.prefab.dismantle_confirm": "Sneak and use it again to pick up the whole %s. Its contents come with it and progress is kept",
    "siftec.prefab.hub_gun": "Take the HUB down with the Build Gun's dismantle mode (X): its 20 Raw Iron and contents come back",
    "siftec.prefab.dismantled": "%s picked up",
    "siftec.prefab.part": "Part of the %s: sneak and use its terminal twice to pick up the whole structure",
    "siftec.claim.hub_too_close": "Too close to another company's land: a claiming HUB must be more than %s chunks from another company's HUB",
    "siftec.claim.hub_extra": "HUB placed. Your company's first HUB already holds its land, so this one claims nothing",
    "siftec.prefab.cost": "Building the HUB takes %s %s from your inventory",
    "siftec.prefab.cost_any": "Building it takes %s %s from your inventory",
    "siftec.prefab.build_gun": "You have a Build Gun: build the HUB with it first (Tab to the Special tab, 20 Raw Iron)",
    "siftec.burner.fuel": "The HUB's Biomass Burners burn leaves, logs, Biomass and Solid Biofuel",
})
lang["siftec.sift.closed"] = "The Sift is closed to you until your company finishes Wormhole Phase 5"
lang.update({"siftec.sift.open": "The Wormhole is open: walk into the gate to reach The Sift. From here on it is free play",
             "siftec.hubui.free_play": "Wormhole open: free play"})
PRESERVE_ICONS = {"pickled_tomato": ("Pickled Tomato", 0xC84030), "pickled_onion": ("Pickled Onion", 0xD8C8A8), "pickled_cabbage": ("Pickled Cabbage", 0x90B850),
                  "pickled_pumpkin": ("Pickled Pumpkin", 0xE08828), "pickled_carrot": ("Pickled Carrot", 0xF09030), "pickled_beetroot": ("Pickled Beetroot", 0x902848),
                  "pickled_kelp": ("Pickled Kelp", 0x4C7A3A), "sweet_berry_jam": ("Sweet Berry Jam", 0xB01838), "glow_berry_jam": ("Glow Berry Jam", 0xF0B040),
                  "apple_jam": ("Apple Jam", 0xD86048), "melon_jam": ("Melon Jam", 0xE85868), "mead": ("Mead", 0xE0A030)}
for fid, (fname, tint) in PRESERVE_ICONS.items():
    lang[f"item.siftec.{fid}"] = fname
    write(f"{A}/models/item/{fid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/honey_bottle" if fid == "mead" else "minecraft:item/potion"}})
    item_def(fid, f"siftec:item/{fid}", tint)
lang.update({"siftec.season.locked": "Your company has not researched Automated Cooking yet (MAM, Nutrients)", "siftec.season.already": "That food is already seasoned",
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
lang.update({"block.siftec.speed_governor": "Speed Governor", "siftec.governor.status": "%s RPM out (at most %s)",
             "siftec.governor.set": "Set to %s RPM", "siftec.governor.step": "%s RPM"})
write(f"{A}/blockstates/speed_governor.json", {"variants": {
    "axis=x": {"model": "create:block/gearshift/block", "x": 90, "y": 90}, "axis=y": {"model": "create:block/gearshift/block"},
    "axis=z": {"model": "create:block/gearshift/block", "x": 90, "y": 180}}})
item_def("speed_governor", "create:block/gearshift/item")


# plain items
for name, tex in {"node_scanner": "minecraft:item/recovery_compass_16", "raw_bauxite": "minecraft:item/brick",
                  "sam": "minecraft:item/amethyst_shard", "uranium": "minecraft:item/emerald"}.items():
    write(f"{A}/models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": tex}})
    item_def(name, f"siftec:item/{name}")


# ---- parts, milestones and phases: one file the mod reads at start-up
def secs(t):
    m, sec = t.split(":")
    return int(m) * 60 + int(sec)

# every item id of the game and of the mods the pack's data names (items only: blocks without an item are not
# in it), so a typo or a renamed item stops the generator instead of loading as nothing
KNOWN = set(open(os.path.join(os.path.dirname(__file__), "known_ids.txt")).read().split())
CHECKED = ("minecraft", "create", "cgs", "createdieselgenerators", "create_new_age")
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
# ---- MAM research: every node is stored like a milestone, with its tree and what it needs first. A need is a node id,
# or several joined by "|" for any of them. A tree shows in the MAM once one of its trigger items is brought in.
data["trees"] = []
data["milestones_mam"] = []
for tree_id, tree_name, icon, nodes in content.MAM:
    check(icon, tree_id)
    data["trees"].append({"id": tree_id, "icon": icon, "nodes": [content.mam_id(tree_id, n[0]) for n in nodes],
                          "triggers": content.mam_triggers(tree_id)})
    lang[f"siftec.tree.{tree_id}"] = tree_name
for nid, tree_id, name, cost, time, text, needs, items, tokens in content.mam_nodes():
    for i in items: check(i, nid)
    for i, c in content.parse_cost(cost): check(i, nid + " cost")
    data["milestones_mam"].append({"id": nid, "tree": tree_id, "cost": [{"item": i, "count": c} for i, c in content.parse_cost(cost)],
                                   "seconds": secs(time), "items": items, "tokens": tokens, "needs": needs})
    lang[f"siftec.milestone.{nid}"] = name
    lang[f"siftec.milestone.{nid}.unlocks"] = text
if len({m["id"] for m in data["milestones_mam"]}) != len(data["milestones_mam"]):
    raise SystemExit("MAM: two nodes share an id")
# the other mods' items that unlock with a milestone, beyond the ones listed with it
by_id = {m["id"]: m for m in data["milestones"] + data["milestones_mam"]}
for mid, more in content.MORE_LOCKS.items():
    if mid not in by_id:
        raise SystemExit(f"MORE_LOCKS: no milestone {mid}")
    for i in more:
        check(i, mid)
        by_id[mid]["items"].append(i)
# locked by default: every item of those mods has to be unlocked by something, or be left free on purpose
placed = {}
for m in data["milestones"] + data["milestones_mam"]:
    for i in m["items"]:
        if i in placed:
            raise SystemExit(f"{i} is unlocked twice: {placed[i]} and {m['id']}")
        placed[i] = m["id"]
for i in content.FREE: check(i, "FREE")
accounted = set(placed) | set(content.FREE) | set(content.DISABLED)
both = sorted((set(placed) | set(content.DISABLED)) & set(content.FREE))
if both:
    raise SystemExit(f"both locked and free: {both}")
loose = sorted(i for i in KNOWN if i.split(":")[0] in ("create", "cgs", "createdieselgenerators", "create_new_age") and i not in accounted)
if loose:
    raise SystemExit(f"{len(loose)} items have no milestone and are not listed as free: {loose}")
lang.update({"block.siftec.mam": "MAM", "siftec.mam.title": "MAM: %s", "siftec.mam.research": "Research time: %s",
             "siftec.mam.busy": "Already researching %s", "siftec.mam.running": "Researching %s: %s left", "siftec.mam.idle": "No research running",
             "siftec.mam.started": "Research started: %s", "siftec.mam.click": "Click to deliver parts from your inventory",
             "siftec.mam.undiscovered": "Undiscovered research", "siftec.mam.bring": "Bring %s to the MAM to start this tree",
             "siftec.tag.mushrooms": "any mushroom", "siftec.tag.crops": "any crop"})
write(f"{A}/blockstates/mam.json", {"variants": {"": {"model": "siftec:block/mam"}}})
write(f"{A}/models/block/mam.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "minecraft:block/lodestone_top", "side": "create:block/copper_casing", "bottom": "create:block/copper_casing"}})
item_def("mam", "siftec:block/mam")
write(f"{D}/loot_table/blocks/mam.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "siftec:mam"}]}]})
# ---- Mercer Spheres, Somersloops and crash sites in the world (collect/), and the Object Scanner
lang.update({"item.siftec.object_scanner": "Object Scanner", "siftec.collect.already": "Your company already collected this one",
             "siftec.collect.got": "Collected: %s", "siftec.scanner.none_object": "No %s found in range", "siftec.scanner.mobs": "hostile mobs",
             "siftec.scanner.target.hard_drive": "Crash Site (Hard Drive)", "siftec.scanner.target.mercer_sphere": "Mercer Sphere",
             "siftec.scanner.target.somersloop": "Somersloop", "siftec.scanner.target.sweet_berries": "Sweet Berry Bush",
             "siftec.scanner.target.apples": "Apple Tree", "siftec.scanner.target.glow_berries": "Glow Berries",
             "siftec.scanner.target.mobs": "Hostile Mob",
             "siftec.mam.tree_started": "MAM: the %s research tree is open to your company",
             "siftec.crash.needs_power": "The pod is sealed. Wire its connector to a grid with %s MW (%s SU) to spare; it has %s SU"})
write(f"{A}/models/item/object_scanner.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/compass_16"}})
item_def("object_scanner", "siftec:item/object_scanner")
COLLECT = {"mercer_sphere": ("minecraft:block/pink_concrete", "Mercer Sphere"), "somersloop": ("minecraft:block/red_concrete", "Somersloop"),
           "crash_site": ("create:block/railway_casing", "Crash Site Pod")}
lang.update({"item.siftec.crash_site": "Crash Site", "siftec.crash.needs": "The pod is sealed. It opens for %s %s",
             "siftec.tree.hard_drives": "Hard Drives", "siftec.mam.hard_drive": "Hard Drive", "siftec.alt.research": "Research a Hard Drive",
             "siftec.alt.cost": "Takes one Hard Drive from your inventory", "siftec.alt.owned": "Alternate recipes: %s of %s",
             "siftec.alt.choose_first": "Choose one of the offered alternates first", "siftec.alt.none": "No alternate fits what your company has unlocked. Keep the drive for later, or sink it",
             "siftec.alt.no_drive": "You are not carrying a Hard Drive", "siftec.alt.pick": "Click to take this one",
             "siftec.alt.ready": "The Hard Drive is decoded. Choose an alternate recipe at the MAM",
             "siftec.alt.chosen": "Alternate recipe unlocked: %s. %s"})
# their shapes (collect/CollectibleBlock.java): the sphere a rounded ball floating in its block, the Somersloop a low
# lumpy shell; the pod is a whole block, drawn by the art loop below. Each takes its own art's side and top.
COLLECT_ELEMENTS = {
    "mercer_sphere": [([4, 3, 4], [12, 13, 12]), ([3, 4, 4], [13, 12, 12]), ([4, 4, 3], [12, 12, 13])],
    "somersloop": [([3, 0, 4], [13, 5, 12]), ([4, 0, 3], [12, 5, 13]), ([5, 5, 5], [11, 9, 11]), ([6, 9, 6], [10, 10, 10])],
}
SHAPED_COLLECT = set()
for cid, (tex, name) in COLLECT.items():
    bid = f"{cid}_block"
    lang[f"block.siftec.{bid}"] = name
    write(f"{A}/blockstates/{bid}.json", {"variants": {"": {"model": f"siftec:block/{bid}"}}})
    if cid not in COLLECT_ELEMENTS:
        write(f"{A}/models/block/{bid}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": tex}})
        continue
    SHAPED_COLLECT.add(bid)
    art = os.path.exists(os.path.join(A, "textures", "block", bid + "_side.png"))
    side = f"siftec:block/{bid}_side" if art else tex
    top = f"siftec:block/{bid}_top" if art else tex
    elements = [{"from": f, "to": t, "faces": {**{d: {"texture": "#side"} for d in ("north", "south", "east", "west")},
                                                 "up": {"texture": "#top"}, "down": {"texture": "#side"}}} for f, t in COLLECT_ELEMENTS[cid]]
    write(f"{A}/models/block/{bid}.json", {"parent": "minecraft:block/block", "textures": {"particle": side, "side": side, "top": top},
          "elements": elements})
# ---- Converter and Particle Accelerator: 1.0's recipes (content.PROCESSORS), alternates with their lock
data["processors"] = {}
names_table = content.names()


def _item_id(name):
    return name if ":" in name else names_table[name]


for pid, (pname, recipe_list) in content.PROCESSORS.items():
    out = []
    for p in recipe_list:
        r = {"in": [{"item": _item_id(n), "count": c} for n, c in p["in"]], "seconds": round(p["seconds"])}
        for n, c in p["in"]: check(_item_id(n), pid)
        if p["fluid_in"]: r["fluid_in"] = {"fluid": recipes.FLUID_IDS[p["fluid_in"][0]], "mb": p["fluid_in"][1]}
        if p["out"]: r["out"] = {"item": _item_id(p["out"][0]), "count": p["out"][1]}
        if p.get("also"): r["also"] = {"item": _item_id(p["also"][0]), "count": p["also"][1]}
        if p["fluid_out"]: r["fluid_out"] = {"fluid": recipes.FLUID_IDS[p["fluid_out"][0]], "mb": p["fluid_out"][1]}
        if p["alt"]: r["alt"] = "alt_" + p["alt"]
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

# ---- AWESOME Sink and Shop (sink/, the AWESOME Sink and Shop tab)
data["sink_points"] = {_item_id(n): p for n, p in content.SINK_POINTS.items()}
for i, p in content.SINK_VANILLA.items():
    check(i, "sink")
    data["sink_points"].setdefault(i, p)   # a Satisfactory value wins
data["sink_tags"] = [{"tag": t[1:], "points": p} for t, p in content.SINK_TAGS.items()]
data["sink_rarity"] = content.SINK_RARITY
data["sink_enchant"] = content.SINK_ENCHANT
data["sink_refused"] = content.SINK_REFUSED
data["coupons"] = {"first": content.COUPON_FIRST, "first_cost": content.COUPON_FIRST_COST, "last": content.COUPON_LAST, "cap": content.COUPON_CAP}
data["shop"] = [{"id": i, "category": cat, "coupons": n, "gives": g} for i, name, cat, n, g in content.SHOP]
data["swatch_max"] = content.SWATCH_MAX
data["dyes"] = content.DYES
data["finishes"] = {k: b for k, (n, b) in content.FINISHES.items()}
data["skins"] = {k: b for k, (n, b) in content.SKINS.items()}
data["storage_skins"] = {k: b for k, (n, b) in content.STORAGE_SKINS.items()}
for k, (n, b) in content.FINISHES.items():
    lang[f"siftec.finish.{k}"] = n
    if "{" not in b: check(b, "finish")
for k, (n, b) in list(content.SKINS.items()) + list(content.STORAGE_SKINS.items()):
    lang[f"siftec.skin.{k}"] = n
    check(b, "skin")
for i, name, cat, n, g in content.SHOP:
    lang[f"siftec.shop.entry.{i}"] = name
    lang[f"siftec.shop.category.{cat.lower().replace(' ', '_')}"] = cat
_decor_ids = {d[0] for d in content.DECOR}
_cup_ids = {c[0] for c in content.CUPS}
for i, name, cat, n, g in content.SHOP:
    kind, _, arg = g.partition(":")
    ok = (kind in ("customizer", "swatch") and not arg) or (kind == "finish" and arg in content.FINISHES) or \
         (kind == "skin" and (arg in content.SKINS or arg in content.STORAGE_SKINS)) or (kind == "decor" and arg in _decor_ids) or (kind == "cup" and arg in _cup_ids)
    if not ok:
        raise SystemExit(f"AWESOME Shop entry {i}: gives {g!r}, which is nothing the pack has")
lang.update({
    "item.siftec.ficsit_coupon": "FICSIT Coupon", "item.siftec.customizer": "Customizer", "item.siftec.xeno_zapper": "Xeno-Zapper",
    "siftec.sink.title": "AWESOME Sink", "siftec.sink.points": "AWESOME points: %s",
    "siftec.sink.status": "%s points toward coupon %s (%s needed)", "siftec.sink.ever": "Points from the Sink, ever: %s",
    "siftec.sink.printed": "Coupons printed: %s", "siftec.sink.print": "Print Coupons", "siftec.sink.print_none": "Not enough points for a coupon yet",
    "siftec.sink.print_done": "Printed %s FICSIT Coupons", "siftec.sink.feed": "Feed it by belt at the input port, or use it with what you hold",
    "siftec.sink.no_power": "The AWESOME Sink has no power", "siftec.sink.refused": "The AWESOME Sink will not take that",
    "siftec.sink.sunk": "Sunk %s for %s points",
    "siftec.shop.title": "AWESOME Shop: %s coupons", "siftec.shop.price": "%s FICSIT Coupons", "siftec.shop.poor": "Not enough FICSIT Coupons",
    "siftec.shop.owned": "Your company owns this", "siftec.shop.bought": "Bought: %s", "siftec.shop.take": "Click to take one (%s)",
    "siftec.shop.take_free": "Click to take one", "siftec.shop.taken": "Took %s", "siftec.shop.missing": "You are missing parts for that",
    "siftec.shop.swatches": "Swatch slots: %s of %s", "siftec.shop.full": "Your company has every swatch slot",
    "siftec.shop.needs": "Needs %s first", "siftec.shop.page": "Page %s of %s", "siftec.shop.next": "Next page", "siftec.shop.previous": "Previous page",
    "siftec.shop.category": "%s",
    "siftec.customizer.title": "Customizer: %s", "siftec.customizer.swatch": "Swatch %s: %s / %s",
    "siftec.customizer.swatch_pick": "Click: paint the building with it. Right-click: make it the swatch you are editing",
    "siftec.customizer.editing": "Editing swatch %s", "siftec.customizer.primary": "Click: primary colour %s. Right-click: secondary",
    "siftec.customizer.industrial": "Industrial grey (as built)", "siftec.customizer.finish": "Finish: %s",
    "siftec.customizer.skin": "Casing: %s", "siftec.customizer.skin_none": "As built", "siftec.customizer.locked": "Buy it in the AWESOME Shop",
    "siftec.customizer.reset": "Back to how it was built", "siftec.customizer.not_building": "Use the Customizer on a Build Gun building",
    "siftec.customizer.applied": "Repainted %s",
    "siftec.cup.employee": "Awarded to the company that finished the Wormhole",
})
data["decor"] = []
for did, name, cat, light, tex, elements, cost in content.DECOR:
    for c, n in content.parse_cost(cost): check(c, "decor " + did)
    boxes = [e[:2] for e in elements]
    lo = [min(b[0][i] for b in boxes) for i in range(3)]
    hi = [max(b[1][i] for b in boxes) for i in range(3)]
    # one box round the whole thing, made even round the middle so it fits whichever way the block faces
    r = max(8 - lo[0], hi[0] - 8, 8 - lo[2], hi[2] - 8)
    shape = [max(0, 8 - r), max(0, lo[1]), max(0, 8 - r), min(16, 8 + r), min(16, hi[1]), min(16, 8 + r)]
    data["decor"].append({"id": did, "light": light, "shape": shape, "cost": [{"item": c, "count": n} for c, n in content.parse_cost(cost)]})
    lang[f"block.siftec.{did}"] = name
    textures = {"particle": tex, "all": tex}
    textures.update(content.DECOR_TEXTURES.get(did, {}))
    els = []
    for k, e in enumerate(elements):
        faces = {f: {"texture": "#all"} for f in ("north", "south", "east", "west", "down")}
        faces["up"] = {"texture": "#top" if "top" in textures else "#all"}
        el = {"from": e[0], "to": e[1], "faces": faces}
        if len(e) > 2:
            axis, angle, origin = e[2]
            el["rotation"] = {"axis": axis, "angle": angle, "origin": origin}
        if light:
            el["light_emission"] = light
        els.append(el)
    write(f"{A}/models/block/{did}.json", {"parent": "minecraft:block/block", "textures": textures, "elements": els})
    # authored facing south (the statues look at whoever put them down)
    write(f"{A}/blockstates/{did}.json", {"variants": {f"facing={d}": ({"model": f"siftec:block/{did}", "y": y} if y else {"model": f"siftec:block/{did}"})
                                                       for d, y in (("south", 0), ("west", 90), ("north", 180), ("east", 270))}})
    item_def(did, f"siftec:block/{did}")
    write(f"{D}/loot_table/blocks/{did}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"siftec:{did}"}]}]})
data["cups"] = []
for cid, name, cost, needs in content.CUPS:
    for c, n in content.parse_cost(cost): check(c, "cup " + cid)
    data["cups"].append({"id": cid, "needs": needs, "cost": [{"item": c, "count": n} for c, n in content.parse_cost(cost)]})
    lang[f"item.siftec.{cid}"] = name
for iid, tex, tint in (("ficsit_coupon", "minecraft:item/paper", 0xF0A040), ("customizer", "minecraft:item/brush", 0xF08030),
                       ("xeno_zapper", "minecraft:item/blaze_rod", 0x80C0FF), ("coffee_cup", "minecraft:item/flower_pot", 0xF0F0F0),
                       ("employee_of_the_planet_cup", "minecraft:item/flower_pot", 0xF0C040)):
    write(f"{A}/models/item/{iid}.json", {"parent": "minecraft:item/handheld" if iid == "xeno_zapper" else "minecraft:item/generated",
                                          "textures": {"layer0": tex}})
    item_def(iid, f"siftec:item/{iid}", tint)
lang.update({"siftec.sift.enter": "Enter The Sift", "siftec.sift.missing": "The Sift is not installed on this server"})
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
    mods = [m for m in ("cgs", "createdieselgenerators", "farmersdelight") if f'"{m}:' in text or f'"#{m}:' in text]
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
              "create:crushing": [C + "crushing_wheel"], "create:filling": [C + "spout"], "create:emptying": [C + "item_drain"], "create:sandpaper_polishing": [C + "sand_paper"],
              "create:sequenced_assembly": [C + "deployer", C + "mechanical_press"], "create:item_application": [],
              "createdieselgenerators:bulk_fermenting": ["createdieselgenerators:bulk_fermenter"],
              "minecraft:smelting": [], "minecraft:blasting": [], "minecraft:crafting_shaped": [], "minecraft:crafting_shapeless": []}
built = recipes.build()
# Where the things no milestone locks come from, so an alternate is only offered once its company can get every
# input: fluids and crushed ore from the machines that make them, tags from what is in them, and anything one of
# the pack's own recipes makes from that recipe's inputs, machine and lock.
SOURCES = {"createdieselgenerators:crude_oil": ["siftec:building/oil_extractor"],
           "createdieselgenerators:diesel": ["createdieselgenerators:distillation_controller", "createdieselgenerators:crude_oil"],
           "createdieselgenerators:biodiesel": ["siftec:solid_biofuel", "siftec:recipe/mixing/liquid_biofuel"],
           "siftec:nitrogen": ["siftec:building/resource_well_extractor"], "siftec:sam": ["siftec:building/portable_miner"], "siftec:raw_bauxite": ["siftec:building/portable_miner"],
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
# the Build Gun's buildings count as what unlocks them (a node's resource needs a miner on the node)
for _b in content.BUILDINGS:
    if _b[4]:
        lock_of["siftec:building/" + _b[0]] = _b[4]


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
for machine, (pname, recipe_list) in content.PROCESSORS.items():
    for p in recipe_list:
        if not p["alt"]: continue
        used = {_item_id(n) for n, c in p["in"]} | {"siftec:building/" + machine}
        if p["fluid_in"]: used.add(recipes.FLUID_IDS[p["fluid_in"][0]])
        data["alternates"].append({"id": "alt_" + p["alt"], "requires": needs_of(used)})
        lang[f"siftec.alt.alt_{p['alt']}"] = p["name"]
        made_text = ", ".join(x for x in (f"{p['out'][1]} {p['out'][0]}" if p["out"] else "", f"{p['also'][1]} {p['also'][0]}" if p.get("also") else "",
                                           f"{p['fluid_out'][1]} mB {p['fluid_out'][0]}" if p["fluid_out"] else "") if x)
        took = ", ".join([f"{c} {n}" for n, c in p["in"]] + ([f"{p['fluid_in'][1]} mB {p['fluid_in'][0]}"] if p["fluid_in"] else []))
        lang[f"siftec.alt.alt_{p['alt']}.text"] = f"{took} makes {made_text} ({pname})"
print("alternates:", len(data["alternates"]), "; with nothing required:", [a["id"] for a in data["alternates"] if not a["requires"]])
# ---- what the Manufacturer, the Quantum Encoder and the Packager run (building/BuildingLogic.java): the steps of each
# recipe and the milestone, MAM node or alternate it needs, so the building only takes on a recipe its company has
recipes.build()


def _recipe_lock(path, made):
    lock = data["recipe_locks"].get("siftec:" + path)
    if lock is None and path.startswith("sequenced_assembly/"):
        lock = data["recipe_locks"].get("seq:" + made.split(":")[1])
    return lock or ""


data["assembly"] = []
for e in recipes.ASSEMBLY:
    entry = {k: v for k, v in e.items() if k != "seq"}
    entry["lock"] = _recipe_lock(e["seq"], e.get("charged", e["result"]))
    for st in e["steps"]:
        if "item" in st and not st["item"].startswith("#"): check(st["item"], "assembly " + e["result"])
    if not e["first"].startswith("#"): check(e["first"], "assembly " + e["result"])
    data["assembly"].append(entry)
data["packager"] = [{**{k: v for k, v in p.items() if k != "path"}, "lock": _recipe_lock(p["path"], p["out"])} for p in recipes.PACKAGER]
print("assembly recipes:", len(data["assembly"]), "; packager recipes:", len(data["packager"]))
print("recipes locked to a milestone:", len(data["recipe_locks"]), "of", len(recipes.build()))
data["removed_recipes"] = recipes.REMOVED
data["workshop"] = [{"item": i, "cost": [{"item": c, "count": n} for c, n in content.parse_cost(cost)]} for i, cost in recipes.WORKSHOP]
# 1.0 build costs of every building the Build Gun will place, under its 1.0 name, for work packages 5 and 9
data["build_costs"] = {}
for bname, cost in sorted(content.BUILD_COSTS.items()):
    if cost is None:
        raise SystemExit(f"build cost of {bname}: it takes something the pack leaves out")
    data["build_costs"][bname] = [{"item": c, "count": n} for c, n in content.parse_cost(cost)]
    for c, n in content.parse_cost(cost): check(c, "build cost " + bname)
lang.update({"siftec.workshop.title": "Equipment Workshop",
             "siftec.workshop.click": "Click to build from the parts in your inventory", "siftec.workshop.missing": "You are missing parts for that",
             "siftec.workshop.built": "Built %s"})
# ---- Build Gun and its buildings (building/BuildGun.java, building/Buildings.java)
data["buildings"] = []
LOGICS = {"basin", "smelter", "miner", "portable_miner", "storage", "power", "assembly", "packager", "extractor", "pressurizer",
          "well_extractor", "processor", "workshop", "sink", "shop", "radar", "truck_station"}
POWERS = {"pole", "storage", "augmenter", "biomass", "coal", "fuel", "geothermal", "nuclear"}
for bid, bname, tab, cost, unlock, dev, extra in content.BUILDINGS:
    if tab not in content.BUILD_TABS:
        raise SystemExit(f"building {bid}: unknown Build Gun tab {tab!r}")
    if unlock and unlock not in by_id:
        raise SystemExit(f"building {bid}: unlocked by {unlock!r}, which is no milestone or MAM node")
    if not extra.get("prefab") and not os.path.exists(os.path.join(D, "structure", "building", bid + ".nbt")):
        raise SystemExit(f"building {bid}: no template at data/siftec/structure/building/{bid}.nbt (run tools/structures.py)")
    parsed = content.parse_cost(cost)
    for c, n in parsed: check(c, "building " + bid)
    b = {"id": bid, "category": tab, "cost": [{"item": c, "count": n} for c, n in parsed]}
    if unlock: b["unlock"] = unlock
    if dev: b["dev"] = True
    if bid in content.NODE_BUILDINGS:
        b["node"] = True
        b["node_cell"] = list(extra["node"])
    if extra.get("logic"):
        if extra["logic"] not in LOGICS:
            raise SystemExit(f"building {bid}: unknown logic {extra['logic']!r}")
        b["logic"] = extra["logic"]
    for k in ("su", "break", "fortune", "mw", "water", "lines", "rate"):
        if k in extra: b[k] = extra[k]
    # an extractor's fluid, the fluid node it stands on (oil, nitrogen) and the water cell a Water Extractor stands over
    if "fluid" in extra:
        b["fluid"] = extra["fluid"]
    if "fluid_node" in extra:
        if extra["fluid_node"] not in ("oil", "nitrogen"):
            raise SystemExit(f"building {bid}: unknown fluid node {extra['fluid_node']!r}")
        b["fluid_node"] = extra["fluid_node"]
    if "intake" in extra:
        b["intake"] = list(extra["intake"])
    if "power" in extra:
        if extra["power"] not in POWERS:
            raise SystemExit(f"building {bid}: unknown power {extra['power']!r}")
        b["power"] = extra["power"]
    if "vent" in extra:
        b["vent"] = list(extra["vent"])
    if extra.get("prefab"):
        if extra["prefab"] not in ("hub", "gateway", "vehicle"):
            raise SystemExit(f"building {bid}: unknown prefab {extra['prefab']!r}")
        b["prefab"] = extra["prefab"]
    data["buildings"].append(b)
    lang[f"siftec.building.{bid}"] = bname
# Satisfactory's time for each machine recipe a building paces (building/MachineTimes.java), in ticks, and the Smelter's
# time per item for the vanilla smelting its fan runs
data["machine_ticks"] = {}
for path, body in sorted(built.items()):
    if path in recipes.SATISFACTORY and body["type"] in ("create:compacting", "create:mixing") and "processing_time" in body:
        data["machine_ticks"]["siftec:" + path] = body["processing_time"]
data["smelter_ticks"] = {}
for key, name, building, alt, secs, ins, outs, unlock in content.sat_recipes():
    if building == "Smelter" and name in ("Iron Ingot", "Copper Ingot"):
        (k, n, a), = ins
        data["smelter_ticks"][recipes.item(n)] = round(secs * 20 / a)
# what the generators burn (building/BuildingPower.java): MJ an item, or MJ a m3 of a fluid (by fluid id); a nuclear
# rod also the waste it leaves
data["mb_per_m3"] = content.MB_PER_M3
data["generator_fuel"] = {}
for kind, table in content.GENERATOR_FUEL.items():
    out = {}
    for key, value in table.items():
        if kind == "fuel":
            fid = recipes.FLUID_IDS[content.SAT_FLUIDS[key]]
            out[fid] = value
        elif isinstance(value, tuple):
            mj, waste, n = value
            check(key, "generator fuel " + kind)
            check(waste, "generator waste " + kind)
            out[key] = {"mj": mj, "waste": waste, "count": n}
        else:
            if not key.startswith("#"): check(key, "generator fuel " + kind)
            out[key] = value
    data["generator_fuel"][kind] = out
# a New Age reactor a player builds burns Satisfactory's rods, not New Age's own fuel: its fuel tag is replaced, and
# each rod's energy is New Age's fuel's for a uranium rod and twice that for plutonium (as in Satisfactory)
NA_DATA = os.path.join(ROOT, "data", "create_new_age")
write(os.path.join(NA_DATA, "tags", "item", "nuclear", "fuel.json"), {"replace": True, "values": ["siftec:uranium_fuel_rod", "siftec:plutonium_fuel_rod"]})
write(os.path.join(NA_DATA, "tags", "item", "nuclear", "energy_28800.json"), {"replace": True, "values": ["siftec:uranium_fuel_rod"]})
write(os.path.join(NA_DATA, "tags", "item", "nuclear", "energy_57600.json"), {"replace": True, "values": ["siftec:plutonium_fuel_rod"]})
# thorium, which New Age's own fuel is made from, does not generate (its ore is switched off with the fuel)
write(os.path.join(NA_DATA, "worldgen", "placed_feature", "thorium_ore.json"), {"feature": "create_new_age:thorium_ore",
      "placement": [{"type": "minecraft:count", "count": 0}]})
lang["item.siftec.build_gun"] = "Build Gun"
item_def("build_gun", "create:item/handheld_worldshaper/item")
lang.update({"block.siftec.building_controller": "Building Controller", "block.siftec.building_port": "Building Port",
             "key.category.siftec.build_gun": "SIFTEC Build Gun", "key.siftec.build_gun.tab": "Next tab",
             "key.siftec.build_gun.rotate": "Rotate", "key.siftec.build_gun.flip": "Flip",
             "key.siftec.build_gun.nudge": "Nudge mode (hold and scroll: pick a building)", "key.siftec.build_gun.dismantle": "Dismantle mode"})
for tab in content.BUILD_TABS:
    lang[f"siftec.build_gun.category.{tab}"] = tab.capitalize()
lang.update({
    "siftec.build_gun.overlay": "%s: %s | %s",
    "siftec.build_gun.overlay_nudge": "%s: %s (nudge: W A S D, Ctrl to let go) | %s",
    "siftec.build_gun.overlay_none": "%s | Ctrl + Scroll to pick a building, Tab for the next tab",
    "siftec.build_gun.overlay_dismantle": "Dismantle | %s",
    "siftec.build_gun.fits": "Right-click to build the %s. R rotate, F flip, Ctrl nudge",
    "siftec.build_gun.fits_hub": "Right-click to build the HUB. R rotate, Ctrl nudge",
    "siftec.build_gun.fits_gateway": "Right-click to build the Wormhole Gateway. R rotate, Ctrl nudge",
    "siftec.build_gun.blocked": "%s blocks are in the way (marked red)",
    "siftec.build_gun.missing": "Missing %s %s",
    "siftec.build_gun.no_template": "The %s building has no template yet",
    "siftec.build_gun.locked": "Locked: needs %s",
    "siftec.build_gun.dev": "Test building: creative only",
    "siftec.build_gun.pick": "Pick a building first: Ctrl + Scroll",
    "siftec.build_gun.dismantle_aim": "Aim at a building to take it down",
    "siftec.build_gun.dismantle_hint": "Right-click to take down the %s: its cost and contents come back",
    "siftec.build_gun.dismantled": "%s taken down",
    "siftec.build_gun.not_yours": "That building belongs to another company",
    "siftec.build_gun.part": "Part of the %s: take it down with the Build Gun's dismantle mode (X)",
    "siftec.build_gun.building": "building",
    "siftec.building.controller": "%s",
    "siftec.building.unknown": "Unknown building %s",
    "siftec.building.status": "%s: %s",
    "siftec.building.made": "%s made",
    "siftec.building.su": "draws %s SU",
    "siftec.building.filter": "makes %s",
    "siftec.building.no_filter": "right-click it with an item to pick what it makes",
    "siftec.building.filter_set": "The %s makes %s now",
    "siftec.building.filter_cleared": "The %s's recipe filter is cleared",
    "siftec.building.filter_refused": "The %s cannot make that",
    "siftec.building.node": "on %s, breaks it in %s s once it regrows",
    "siftec.building.fortune": "Fortune %s",
    "siftec.building.mined": "%s mined",
    "siftec.building.no_node": "no node under it",
    "siftec.building.full": "output full",
    "siftec.building.stopped": "not turning",
    "siftec.building.storage": "%s of %s slots used",
    # work package 9
    "siftec.building.no_filter_set": "no recipe set: use the panel with what it should make",
    "siftec.building.no_recipe": "it cannot make that (or your company has not unlocked it)",
    "siftec.building.no_water": "its intake is out of the water",
    "siftec.build_gun.no_water": "A Water Extractor has to stand in water: its intake (the middle) over a water source",
    "siftec.build_gun.no_node": "The %s has to stand over a node of its kind",
    "siftec.build_gun.clump": "Mine the node's clump away first: %s blocks left",
    "siftec.building.no_well": "no running Resource Well Pressurizer of your company within 32 blocks",
    "siftec.building.extracting": "%s m3 of %s a minute",
    "siftec.building.extracted": "%s mB out so far",
    "siftec.building.pressurized": "pressurizing a %s well",
    # work package 14: vehicles (vehicle/Vehicles.java) and the Truck Station (building/TruckStations.java)
    "entity.siftec.tractor": "Tractor", "entity.siftec.truck": "Truck", "entity.siftec.explorer": "Explorer",
    "key.category.siftec.vehicles": "SIFTEC Vehicles", "key.siftec.vehicle.record": "Record a self-driving route",
    "siftec.build_gun.fits_vehicle": "Right-click to put the %s down here. R rotate",
    "siftec.build_gun.dismantle_vehicle": "Right-click to take the %s back: its cost and cargo come back",
    "siftec.vehicle.blocked": "Not enough room for it here",
    "siftec.vehicle.hud": "%s | fuel %s%% | %s",
    "siftec.vehicle.no_fuel": "Out of fuel: use a bucket of Fuel, Liquid Biofuel or Turbofuel on it, or dock it at a Truck Station with fuel",
    "siftec.vehicle.fuelled": "%s filled up: fuel %s%%",
    "siftec.vehicle.full": "The %s's tank is full",
    "siftec.vehicle.driving": "driving (V: record a route)",
    "siftec.vehicle.recording": "recording: %s points, %s stops (V: finish)",
    "siftec.vehicle.route": "route: %s points, %s stops; get out and it drives itself (V: record a new one)",
    "siftec.vehicle.record_start": "Recording the route: drive it once, through the Truck Stations to stop at, and press V again",
    "siftec.vehicle.record_done": "Route saved: %s points, %s stops. Get out and the %s drives it by itself",
    "siftec.vehicle.record_short": "Too short to drive by itself: drive further before you finish",
    "siftec.vehicle.not_yours": "That vehicle belongs to another company",
    "siftec.vehicle.autopilot": "%s: self-driving, %s stops on its route, fuel %s%%",
    "siftec.building.station_load": "loads vehicles from its storage",
    "siftec.building.station_unload": "unloads vehicles into its storage",
    "siftec.building.station_fuel": "%s m3 of fuel",
    "siftec.building.station_mode": "The %s now %s",
})
# a generator building's turbine: the steam engine's look, turning the generator coils while it runs
write(f"{A}/models/block/turbine.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "create:block/gearbox", "bottom": "create:block/gearbox", "side": "create:block/copper_casing"}})
write(f"{A}/blockstates/turbine.json", {"variants": {f"facing={d}": {"model": "siftec:block/turbine", **turn}
      for d, turn in {"up": {}, "down": {"x": 180}, "north": {"x": 90}, "south": {"x": 90, "y": 180}, "east": {"x": 90, "y": 90}, "west": {"x": 90, "y": 270}}.items()}})
item_def("turbine", "siftec:block/turbine")
# the controller: a control panel; the ports: a casing with a coloured face pointing out (Andre draws the real ones)
write(f"{A}/blockstates/building_controller.json", {"variants": {f"facing={d}": ({"model": "siftec:block/building_controller", "y": y} if y else {"model": "siftec:block/building_controller"}) for d, y in ROT}})
II = "create:block/industrial_iron_block"
write(f"{A}/models/block/building_controller.json", {"parent": "minecraft:block/cube", "textures": {"particle": II,
      "north": "minecraft:block/crafter_north", "south": II, "east": II, "west": II, "up": "create:block/industrial_iron_block_top", "down": II}})
item_def("building_controller", "siftec:block/building_controller")
PORT_FACES = {"item_in": "minecraft:block/lime_glazed_terracotta", "item_out": "minecraft:block/orange_glazed_terracotta",
              "fluid_in": "minecraft:block/light_blue_glazed_terracotta", "fluid_out": "minecraft:block/blue_glazed_terracotta"}
PORT_TURN = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}, "up": {"x": 270}, "down": {"x": 90}}
port_variants = {}
for kind, face in PORT_FACES.items():
    write(f"{A}/models/block/building_port_{kind}.json", {"parent": "minecraft:block/cube", "textures": {"particle": II,
          "north": face, "south": II, "east": II, "west": II, "up": "create:block/industrial_iron_block_top", "down": II}})
    for d, turn in PORT_TURN.items():
        port_variants[f"facing={d},kind={kind}"] = {"model": f"siftec:block/building_port_{kind}", **turn}
write(f"{A}/blockstates/building_port.json", {"variants": port_variants})
item_def("building_port", "siftec:block/building_port_item_in")
# the Brass Basin (the Manufacturer's and the Blender's Basin): Create's Basin in Create's brass casing, holding four
# stacks of each item; Create's own Basin block entity runs it
lang["block.siftec.brass_basin"] = "Brass Basin"
BB = "create:block/brass_casing"
write(f"{A}/models/block/brass_basin.json", {"parent": "minecraft:block/block", "textures": {"particle": BB, "case": BB}, "elements": [
    {"from": [0, 2, 0], "to": [16, 16, 16], "faces": {**{d: {"uv": [0, 0, 16, 14], "texture": "#case"} for d in ("north", "east", "south", "west")},
                                                       "up": {"uv": [0, 0, 16, 16], "texture": "#case"}, "down": {"uv": [0, 0, 16, 16], "texture": "#case"}}},
    {"from": [1.95, 16, 1.95], "to": [14.05, 2, 14.05], "faces": {**{d: {"uv": [2, 2, 14, 14], "rotation": 180, "texture": "#case"} for d in ("north", "east", "south", "west")},
                                                                    "up": {"uv": [2, 2, 14, 14], "rotation": 90, "texture": "#case"}}},
    {"from": [2, 0, 2], "to": [14, 2, 14], "faces": {**{d: {"uv": [2, 14, 14, 16], "texture": "#case"} for d in ("north", "east", "south", "west")},
                                                     "down": {"uv": [2, 2, 14, 14], "texture": "#case"}}}]})
write(f"{A}/blockstates/brass_basin.json", {"variants": {f"facing={d}": {"model": "siftec:block/brass_basin"} for d in ("down", "north", "south", "east", "west")}})
if UNKNOWN:
    raise SystemExit("unknown item ids (not items of the game or of the pack's mods):\n  " + "\n  ".join(UNKNOWN))
write(os.path.join(ROOT, "siftec_content.json"), data)
# ---- the recipe checkers (tools/check_recipes.py): reachability, automatability, conflicts
import check_recipes
_errors, _notes, _summary = check_recipes.check(data)
print(_summary)
for _n in _notes:
    print("  note:", _n)
if _errors:
    raise SystemExit("recipe checkers failed:\n  " + "\n  ".join(_errors))

# ---- HUB and Wormhole Gateway
lang.update({
    "block.siftec.hub": "HUB", "block.siftec.wormhole_gateway": "Wormhole Gateway",
    "siftec.hub.title": "HUB: %s", "siftec.gateway.title": "Wormhole Gateway: %s",
    "siftec.hub.tier": "Tier %s", "siftec.hub.tier.locked": "Locked: deliver %s at the Wormhole Gateway",
    "siftec.hub.tier.locked0": "Locked: finish Tier 0 first", "siftec.hub.done": "Completed",
    "siftec.hub.cost": "%s: %s / %s", "siftec.hub.time": "HUB lock: %s", "siftec.hub.unlocks": "Unlocks: %s",
    "siftec.hub.click": "Click to deliver parts from your inventory", "siftec.hub.needs": "Needs: %s",
    "siftec.hub.locked_for": "The freighter is away: back in %s", "siftec.hub.ready": "HUB ready",
    "siftec.hub.company": "%s (%s members, costs x%s)", "siftec.hub.not_yours": "This belongs to %s",
    "siftec.hub.delivered": "Delivered %s parts", "siftec.hub.nothing": "You are not carrying any of the parts it needs",
    "siftec.hub.complete": "%s completed %s", "siftec.hub.busy": "The freighter is away for another %s",
    "siftec.hub.at_gateway": "Wormhole phases are delivered at the Wormhole Gateway",
    "siftec.hub.at_hub": "Milestones are delivered at the HUB",
    "siftec.hub.now_active": "%s is active: pay by hand, or by belt and funnel into the HUB",
    # the HUB screen, laid out like the old Tweaker HUB screen
    "siftec.hubui.wormhole": "Wormhole",
    "siftec.hubui.set_active": "Set as active", "siftec.hubui.pay": "Pay from inventory",
    "siftec.hubui.loading": "Connecting to the HUB...", "siftec.hubui.tier_locked": "Tier %s is locked",
    "siftec.hubui.complete": "Complete",
    "siftec.hubui.hint": "Set it active, then pay by hand or feed the HUB",
    "siftec.hubui.active": "Pay by hand, or feed the HUB by belt or funnel",
    "siftec.hubui.hint_gateway": "Set it active, then pay by hand or feed the Gateway",
    "siftec.hubui.active_gateway": "Pay by hand, or feed the Gateway by belt or funnel",
    "siftec.hubui.any": "Any %s", "siftec.hubui.lock_after": "The freighter is away for %s after this",
    "siftec.hubui.phases": "Wormhole: %s of %s phases delivered",
    "siftec.hubui.status": "%s  |  Members %s  |  Costs x%s", "siftec.hubui.status_locked": "%s  |  Freighter back in %s",
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
    # the HUB is built with the Build Gun and has no item; the Gateway is still an item
    if b != "hub":
        item_def(b, f"siftec:block/{b}")

write(f"{A}/lang/en_us.json", dict(sorted(lang.items())))

# machines drop themselves
for b in ("steel_casing", "blueprint_designer", "blueprint_designer_mk3", "drone_port", "main_portal", "satellite_portal", "landing_pad", "claim_marker", "resource_well_extractor", "dimensional_depot", "speed_governor"):
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
          "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}],
          "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
# pickaxe is the right tool for the machines and SIFTEC's ores
# a Pumpjack hole only counts an Oil Well as the bottom of its pipe
write(os.path.join(ROOT, "data", "createdieselgenerators", "tags", "block", "oil_deposit.json"), {"replace": True, "values": ["siftec:oil_well"]})
write(os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json"),
      {"replace": False, "values": ["siftec:resource_well_extractor", "siftec:dimensional_depot", "siftec:speed_governor", "siftec:landing_pad", "siftec:steel_casing", "siftec:blueprint_designer", "siftec:blueprint_designer_mk3", "siftec:drone_port", "siftec:main_portal", "siftec:satellite_portal", "siftec:mam", "siftec:claim_marker", "siftec:converter", "siftec:particle_accelerator", "siftec:hub", "siftec:wormhole_gateway"]
      + sorted(f"siftec:{b}" for b in SIFTEC_ORES) + [f"siftec:{d[0]}" for d in content.DECOR]})
# SIFTEC's ores take a stone pickaxe or better, like iron ore
write(os.path.join(ROOT, "data", "minecraft", "tags", "block", "needs_stone_tool.json"), {"replace": False, "values": sorted(f"siftec:{b}" for b in SIFTEC_ORES)})
# Create's contraptions never move what has to stay put: the HUB and Gateway shells, claim markers, patches and fluid
# nodes. (Drills skip patches and fluid nodes, which are unbreakable.) An ore node's block is an ordinary ore block: it
# is kept still by its position instead (node/NodeSites.java and the claim mixins), and a drill mines it.
write(os.path.join(ROOT, "data", "create", "tags", "block", "non_movable.json"), {"replace": False, "values":
      ["siftec:hub", "siftec:wormhole_gateway"] + [f"siftec:prefab_{k}" for k in PREFAB] + ["siftec:hub_bed", "siftec:personal_storage",
       "siftec:hub_burner", "siftec:claim_marker"] + [f"siftec:{k}_patch" for k in STONE] + [f"siftec:{b}" for b in FLUID_NODE_IDS]})

# ---------------------------------------------------------------------------------------------------
# The mod's own art, cut from the sprite sheets by tools/import_art.py. Anything with a texture file
# drops its borrowed stand-in; anything without one keeps it.
TEX = os.path.join(A, "textures")
def has(kind, name):
    return os.path.exists(os.path.join(TEX, kind, name + ".png"))

FRONTED = {"hub", "mam", "resource_well_extractor", "converter",
           "particle_accelerator", "hub_engine", "crash_site_block"}
# drawn as a post or a tripod on a clear background: shown as two crossed planes, like a flower
CROSSED = set()
# art of blocks that are gone (work package 6 made the power blocks Build Gun buildings); the HUB Engine's is kept as
# the look of the HUB's Biomass Burners (its model only)
RETIRED = {"furnace_engine", "geyser_engine", "power_pole", "power_tower", "power_storage",
           # work package 10 made these Build Gun buildings: their art stays for when the buildings use it
           "awesome_sink", "awesome_shop", "equipment_workshop", "radar_tower"}
# blocks that turn to face whoever placed them; the Crash Site Pod is set down by world generation, so it does not
TURNS = FRONTED - {"crash_site_block", "hub_engine"}
# these share a block class with one that turns, so they have the same facing, though nothing on them shows it
for bid in ("wormhole_gateway",):
    write(f"{A}/blockstates/{bid}.json", {"variants": {f"facing={d}": {"model": f"siftec:block/{bid}"} for d in ("north", "east", "south", "west")}})
for bid in sorted({f[:-len("_side.png")] for f in os.listdir(os.path.join(TEX, "block")) if f.endswith("_side.png")} if os.path.isdir(os.path.join(TEX, "block")) else []):
    if bid in SHAPED_COLLECT or bid in RETIRED:
        continue  # the Mercer Sphere and Somersloop have their own shapes (above); retired blocks have none
    if bid == "speed_governor" or bid.endswith("_node") or bid in NODE_IDS or bid == "node_rock" or bid.startswith("miner_mk") or bid == "portable_miner":
        continue  # the governor keeps Create's gearshift model; nodes look like ore blocks; old node and miner art is unused
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
        model = {"parent": "minecraft:block/cube_bottom_top", "textures": {"top": t("top"), "side": t("side"), "bottom": t("side")}}
        write(f"{A}/models/block/{bid}.json", model)
for f in sorted(os.listdir(os.path.join(TEX, "item"))) if os.path.isdir(os.path.join(TEX, "item")) else []:
    iid = f[:-4]
    if iid in ("fluid_in_bucket", "power_line"):
        continue  # the Power Line item is gone: the grid's wires are Create: New Age's
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
