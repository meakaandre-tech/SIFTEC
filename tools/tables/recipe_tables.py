"""Plain recipe lists: other mods' recipes the pack takes out, what the Equipment Workshop builds, the backpack upgrades,
and what the alternates' unlock check needs to know about machines and sources (gen_assets.py)."""
from . import content

C = "create:"

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
# (REMOVED); the Assembler (a Mixer on a Basin) makes each one too, under the same lock (recipes.backpack_upgrades()).
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

# ---- alternates: each is only offered once its company can get everything it uses
# the Create machines a recipe type runs on
MACHINE_OF = {"create:cutting": [C + "mechanical_saw"], "create:deploying": [C + "deployer"], "create:mixing": [C + "mechanical_mixer", C + "basin"],
              "create:compacting": [C + "mechanical_press", C + "basin"], "create:splashing": [C + "encased_fan"], "create:haunting": [C + "encased_fan"],
              "create:mechanical_crafting": [C + "mechanical_crafter"], "create:pressing": [C + "mechanical_press"], "create:milling": [C + "millstone"],
              "create:crushing": [C + "crushing_wheel"], "create:filling": [C + "spout"], "create:emptying": [C + "item_drain"], "create:sandpaper_polishing": [C + "sand_paper"],
              "create:sequenced_assembly": [C + "deployer", C + "mechanical_press"], "create:item_application": [],
              "createdieselgenerators:bulk_fermenting": ["createdieselgenerators:bulk_fermenter"],
              "minecraft:smelting": [], "minecraft:blasting": [], "minecraft:crafting_shaped": [], "minecraft:crafting_shapeless": []}
# Where the things no milestone locks come from: fluids and crushed ore from the machines that make them, and tags
# from what is in them. gen_assets.py adds anything one of the pack's own recipes makes, from that recipe's inputs,
# machine and lock.
SOURCES = {"createdieselgenerators:crude_oil": ["siftec:building/oil_extractor"],
           "createdieselgenerators:diesel": ["createdieselgenerators:distillation_controller", "createdieselgenerators:crude_oil"],
           "createdieselgenerators:biodiesel": ["siftec:solid_biofuel", "siftec:recipe/mixing/refinery/liquid_biofuel"],
           "siftec:nitrogen": ["siftec:building/resource_well_extractor"], "siftec:sam": ["siftec:building/portable_miner"], "siftec:raw_bauxite": ["siftec:building/portable_miner"],
           "#c:ingots/steel": ["cgs:steel_ingot"]}
for _ore in ("iron", "copper", "zinc", "gold"):
    SOURCES[f"create:crushed_raw_{_ore}"] = [C + "crushing_wheel"]
