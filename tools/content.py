"""The pack's content tables: parts, milestones, wormhole phases. gen_assets.py turns these into the
files the mod reads. Costs are written the way the design doc writes them ("20 Iron Rod, 10 Iron Sheet").
Satisfactory's own numbers (recipes, build costs, milestone costs and times, sink points) come from
tools/satisfactory.py, the 1.0 game export; this file says what each Satisfactory name is in the pack."""
import satisfactory as SF

# ---------------------------------------------------------------------------------------------------
# Parts that already exist in vanilla or another mod: name -> item id
ALIAS = {
    "Iron Ingot": "minecraft:iron_ingot", "Copper Ingot": "minecraft:copper_ingot",
    "Iron Sheet": "create:iron_sheet", "Copper Sheet": "create:copper_sheet",
    "Coal": "minecraft:coal",
    "Steel Ingot": "cgs:steel_ingot", "Sulfur": "cgs:sulfur",
    "Brass Ingot": "create:brass_ingot", "Brass Sheet": "create:brass_sheet",
    "Electron Tube": "create:electron_tube", "Precision Mechanism": "create:precision_mechanism",
    "Diamond": "minecraft:diamond", "Nether Quartz": "minecraft:quartz",
    "Raw Iron": "minecraft:raw_iron", "Raw Copper": "minecraft:raw_copper",
    "Raw Zinc": "create:raw_zinc", "Gunpowder": "minecraft:gunpowder", "Cardboard": "create:cardboard",
    "Mechanical Drill": "create:mechanical_drill",
    # what the nodes give that the mod adds itself (blocks and items outside PARTS)
    "Raw Bauxite": "siftec:raw_bauxite", "Uranium": "siftec:uranium", "SAM": "siftec:sam",
    # equipment the Equipment Workshop builds: the Xeno-Zapper is the pack's own, the Xeno-Basher Gunsmithing's hammer
    "Xeno-Zapper": "siftec:xeno_zapper",
    # where Satisfactory takes Concrete or Limestone, the pack takes any stone (stone nodes, or what any pickaxe digs up)
    "Stone": "#siftec:stones",
}

# ---------------------------------------------------------------------------------------------------
# Satisfactory's names in the pack (the Recipes tab's Parts table). Anything not listed keeps its Satisfactory name
# and is a SIFTEC part (PARTS below) or a fluid (FLUIDS below).
SAT_NAMES = {
    "Iron Ore": "Raw Iron", "Copper Ore": "Raw Copper", "Raw Quartz": "Nether Quartz", "Bauxite": "Raw Bauxite",
    "Iron Plate": "Iron Sheet", "Black Powder": "Gunpowder", "Diamonds": "Diamond",
    # Caterium is brass (Food and Brass tab): its ingot is the Brass Ingot, and its ore the raw zinc that goes into brass
    "Caterium Ingot": "Brass Ingot", "Caterium Ore": "Raw Zinc",
    "Concrete": "Stone", "Limestone": "Stone",
    "Leaves": "#minecraft:leaves", "Wood": "#minecraft:logs", "Mycelia": "#siftec:mycelia",
    # the creatures' remains are mob drops (MAM Research tab, Alien Megafauna)
    "Hog Remains": "minecraft:rotten_flesh", "Spitter Remains": "minecraft:bone", "Stinger Remains": "#siftec:spider_remains",
    "Hatcher Remains": "minecraft:gunpowder",
    # the wild plants are wild food (Nutrients tree)
    "Paleberry": "minecraft:sweet_berries", "Beryl Nut": "minecraft:apple", "Bacon Agaric": "minecraft:glow_berries",
    "Iodine-Infused Filter": "Iodine Infused Filter",
    # Power Shards are gone: where a recipe or research takes them, it takes Time Crystals (as the Alien Power Matrix does)
    "Power Shard": "Time Crystal",
}
# made from Limestone in the Converter: a block of plain stone
SAT_OUTPUT_NAMES = {"Limestone": "minecraft:stone"}
# Satisfactory fluids, by the name recipes.FLUID_IDS knows them by (Fuel is diesel, Liquid Biofuel is biodiesel)
SAT_FLUIDS = {"Water": "water", "Crude Oil": "crude oil", "Fuel": "diesel", "Liquid Biofuel": "biodiesel", "Heavy Oil Residue": "heavy oil residue",
              "Alumina Solution": "alumina solution", "Sulfuric Acid": "sulfuric acid", "Nitric Acid": "nitric acid", "Nitrogen Gas": "nitrogen",
              "Turbofuel": "turbofuel", "Rocket Fuel": "rocket fuel", "Ionized Fuel": "ionized fuel", "Dissolved Silica": "dissolved silica",
              "Dark Matter Residue": "dark matter residue", "Excited Photonic Matter": "excited photonic matter"}
# 1 m3 of a Satisfactory fluid is this many mB. A Basin tank and a Spout hold 1000 mB, and the biggest single amount in
# any 1.0 recipe is 25 m3 (Nitrogen Gas for the Fused Modular Frame and Cooling System, Excited Photonic Matter), so 40.
MB_PER_M3 = 40
# Satisfactory items the pack leaves out, and with them every recipe that makes or takes them: the Nobelisks and rifle
# rounds (Gunsmithing's guns, TNT and grenades take their place), Explosive Rebar, the power slugs (Power Shards are
# gone) and the Portable Miner (a Build Gun building, not an item).
SAT_LEFT_OUT = {"Nobelisk", "Gas Nobelisk", "Pulse Nobelisk", "Cluster Nobelisk", "Nuke Nobelisk", "Rifle Ammo", "Homing Rifle Ammo",
                "Turbo Rifle Ammo", "Explosive Rebar", "Blue Power Slug", "Yellow Power Slug", "Purple Power Slug", "Portable Miner"}
# made in a recipe, these are left out too: Concrete is gone, and the Power Shard recipes with it
SAT_NOT_MADE = {"Concrete", "Power Shard"}


def sat_item(name, made=False):
    """A Satisfactory item as the pack has it: ("item", part name, id or #tag) or ("fluid", fluid name), or None when it
    is left out."""
    if name in SAT_LEFT_OUT or (made and name in SAT_NOT_MADE):
        return None
    if name in SAT_FLUIDS:
        return ("fluid", SAT_FLUIDS[name])
    if made and name in SAT_OUTPUT_NAMES:
        return ("item", SAT_OUTPUT_NAMES[name])
    return ("item", SAT_NAMES.get(name, name))


def slug(text):
    return "".join(c if c.isalnum() else "_" for c in text.lower().replace("alternate: ", "").replace("-", "_")).strip("_").replace("__", "_")


def sat_recipes():
    """Every Satisfactory 1.0 machine recipe the pack keeps, with the pack's names: (key, name, building, alternate,
    seconds, [(kind, name, amount)] in, [(kind, name, amount)] out, unlock)."""
    out = []
    for key, name, building, alt, secs, ins, outs, unlock in SF.RECIPES:
        i = [sat_item(n) for n, _ in ins]
        o = [sat_item(n, True) for n, _ in outs]
        if None in i or None in o:
            continue
        out.append((key, name, building, alt, secs, [(a[0], a[1], n) for a, (_, n) in zip(i, ins)],
                    [(a[0], a[1], n) for a, (_, n) in zip(o, outs)], unlock))
    return out


# ---------------------------------------------------------------------------------------------------
# Custom parts: id, name, placeholder texture, tint (None = use the texture as it is).
# Every look here is a stand-in built from a vanilla or Create texture until real art is picked.
I, S, ROD, WIRE, NUG = "minecraft:item/iron_ingot", "create:item/iron_sheet", "minecraft:item/bone", "minecraft:item/string", "minecraft:item/iron_nugget"
CAN, TANK = "minecraft:item/bucket", "minecraft:item/bucket"
PARTS = [
    # tiers 0-2
    ("iron_rod", "Iron Rod", ROD, 0xB8B8C0), ("wire", "Wire", WIRE, 0xE0803C), ("cable", "Cable", "minecraft:item/lead", None),
    ("screw", "Screw", NUG, 0xC8C8D0), ("reinforced_iron_plate", "Reinforced Iron Plate", "create:item/sturdy_sheet", 0xD8D8E0),
    ("biomass", "Biomass", "minecraft:item/dried_kelp", None), ("solid_biofuel", "Solid Biofuel", "minecraft:item/charcoal", 0x9CC060),
    ("rotor", "Rotor", "create:item/propeller", None), ("modular_frame", "Modular Frame", "minecraft:item/item_frame", None),
    ("smart_plating", "Smart Plating", S, 0xF0C060),
    # tiers 3-4
    ("steel_beam", "Steel Beam", I, 0x70747C), ("steel_pipe", "Steel Pipe", ROD, 0x70747C),
    ("versatile_framework", "Versatile Framework", "minecraft:item/item_frame", 0xF0A050),
    ("encased_industrial_beam", "Encased Industrial Beam", I, 0xA8A8A0), ("stator", "Stator", "create:item/belt_connector", None),
    ("motor", "Motor", "minecraft:item/minecart", 0xF0B050), ("automated_wiring", "Automated Wiring", "minecraft:item/redstone", 0xF0A040),
    # tiers 5-6
    ("plastic", "Plastic", S, 0x50A0E8), ("rubber", "Rubber", "minecraft:item/slime_ball", 0x404048),
    ("polymer_resin", "Polymer Resin", "minecraft:item/glowstone_dust", 0x6A3A8A),
    ("petroleum_coke", "Petroleum Coke", "minecraft:item/coal", 0x9090A0), ("circuit_board", "Circuit Board", "create:item/integrated_circuit", None),
    ("computer", "Computer", "minecraft:item/comparator", None), ("heavy_modular_frame", "Heavy Modular Frame", "minecraft:item/glow_item_frame", None),
    ("modular_engine", "Modular Engine", "minecraft:item/furnace_minecart", None),
    ("adaptive_control_unit", "Adaptive Control Unit", "minecraft:item/repeater", None),
    ("empty_canister", "Empty Canister", CAN, 0xD04030),
    # tiers 7-8
    ("aluminum_scrap", "Aluminum Scrap", NUG, 0xD0D8E8),
    ("aluminum_ingot", "Aluminum Ingot", I, 0xE0E8F8), ("alclad_aluminum_sheet", "Alclad Aluminum Sheet", S, 0xE8D0B8),
    ("aluminum_casing", "Aluminum Casing", "create:item/sturdy_sheet", 0xE0E8F8), ("radio_control_unit", "Radio Control Unit", "create:item/transmitter", None),
    ("battery", "Battery", "minecraft:item/redstone_torch", None),
    ("iodine_infused_filter", "Iodine Infused Filter", "create:item/filter", 0xB080E0),
    ("supercomputer", "Supercomputer", "minecraft:item/comparator", 0x60C0FF), ("assembly_director_system", "Assembly Director System", "create:item/linked_controller", None),
    ("electromagnetic_control_rod", "Electromagnetic Control Rod", "minecraft:item/blaze_rod", 0x80C0FF),
    ("magnetic_field_generator", "Magnetic Field Generator", "minecraft:item/recovery_compass_16", None),
    ("empty_fluid_tank", "Empty Fluid Tank", TANK, 0xE0E8F8),
    ("heat_sink", "Heat Sink", "minecraft:item/iron_door", 0xE8C8A8), ("cooling_system", "Cooling System", "minecraft:item/snowball", 0x90D0FF),
    ("fused_modular_frame", "Fused Modular Frame", "minecraft:item/glow_item_frame", 0xA0D0FF), ("turbo_motor", "Turbo Motor", "minecraft:item/tnt_minecart", None),
    ("thermal_propulsion_rocket", "Thermal Propulsion Rocket", "minecraft:item/firework_rocket", None),
    ("copper_powder", "Copper Powder", "minecraft:item/glowstone_dust", 0xE08050), ("pressure_conversion_cube", "Pressure Conversion Cube", "minecraft:item/netherite_scrap", None),
    ("nuclear_pasta", "Nuclear Pasta", "minecraft:item/nether_star", 0xE08050),
    # the nuclear chain: uranium and plutonium as in Satisfactory (the New Age reactor burns the rods, work package 6)
    ("encased_uranium_cell", "Encased Uranium Cell", "minecraft:item/emerald", 0x80F080), ("uranium_fuel_rod", "Uranium Fuel Rod", ROD, 0x60E060),
    ("uranium_waste", "Uranium Waste", "minecraft:item/emerald", 0x408040), ("non_fissile_uranium", "Non-Fissile Uranium", "minecraft:item/glowstone_dust", 0x70C070),
    ("plutonium_pellet", "Plutonium Pellet", "minecraft:item/slime_ball", 0x40A0A0), ("encased_plutonium_cell", "Encased Plutonium Cell", "minecraft:item/emerald", 0x40C0C0),
    ("plutonium_fuel_rod", "Plutonium Fuel Rod", ROD, 0x40C0C0), ("plutonium_waste", "Plutonium Waste", "minecraft:item/emerald", 0x306060),
    # tier 9
    ("reanimated_sam", "Reanimated SAM", "minecraft:item/amethyst_shard", 0x80F0FF), ("sam_fluctuator", "SAM Fluctuator", "minecraft:item/echo_shard", None),
    ("ficsite_ingot", "Ficsite Ingot", I, 0xF8D048), ("ficsite_trigon", "Ficsite Trigon", "minecraft:item/prismarine_shard", 0xF8D048),
    ("time_crystal", "Time Crystal", "minecraft:item/diamond", 0xF080F0), ("biochemical_sculptor", "Biochemical Sculptor", "minecraft:item/brush", None),
    ("superposition_oscillator", "Superposition Oscillator", "minecraft:item/clock_00", None), ("neural_quantum_processor", "Neural-Quantum Processor", "minecraft:item/ender_eye", None),
    ("ai_expansion_server", "AI Expansion Server", "minecraft:item/music_disc_5", None), ("singularity_cell", "Singularity Cell", "minecraft:item/ender_pearl", 0x8040C0),
    ("ballistic_warp_drive", "Ballistic Warp Drive", "minecraft:item/trident", None),
    ("dark_matter_crystal", "Dark Matter Crystal", "minecraft:item/amethyst_shard", 0x402060),
    ("ficsonium", "Ficsonium", "minecraft:item/echo_shard", 0xF8D048), ("ficsonium_fuel_rod", "Ficsonium Fuel Rod", ROD, 0xF8D048),
    ("alien_power_matrix", "Alien Power Matrix", "minecraft:item/heart_of_the_sea", None),
    # MAM parts
    ("alien_protein", "Alien Protein", "minecraft:item/rotten_flesh", 0xE070C0), ("alien_dna_capsule", "Alien DNA Capsule", "minecraft:item/experience_bottle", None),
    ("quickwire", "Quickwire", WIRE, 0xF0C850),
    ("ai_limiter", "AI Limiter", "minecraft:item/compass_16", None), ("high_speed_connector", "High-Speed Connector", "minecraft:item/shears", 0xF0C850),
    ("fabric", "Fabric", "minecraft:item/paper", 0xD8C8A0), ("gas_filter", "Gas Filter", "create:item/filter", None),
    ("silica", "Silica", "minecraft:item/sugar", None),
    ("quartz_crystal", "Quartz Crystal", "create:item/polished_rose_quartz", 0xF8F8FF), ("crystal_oscillator", "Crystal Oscillator", "minecraft:item/clock_00", 0xF8F0FF),
    ("compacted_coal", "Compacted Coal", "minecraft:item/coal", 0xC0C060), ("brass_blend", "Brass Blend", "create:item/crushed_raw_gold", None),
    ("smokeless_powder", "Smokeless Powder", "minecraft:item/gunpowder", 0xD0D0D8),
    ("iron_rebar", "Iron Rebar", ROD, 0x909098), ("stun_rebar", "Stun Rebar", ROD, 0xF0C850), ("shatter_rebar", "Shatter Rebar", ROD, 0xF8F8FF),
    # vinegar in a bottle (Spout), what the Preserved Foods research takes
    ("vinegar_bottle", "Vinegar Bottle", "minecraft:item/potion", 0xE8D8A0),
    # collectibles (Exploration and Equipment tab): found in the world, never made. The power slugs and Power Shards are
    # gone with overclocking
    ("mercer_sphere", "Mercer Sphere", "minecraft:item/ender_pearl", 0xFF80C0), ("somersloop", "Somersloop", "minecraft:item/nautilus_shell", 0xFF6060),
    ("hard_drive", "Hard Drive", "minecraft:item/music_disc_11", None),
]
# Packaged fluids: one per Satisfactory package, each holding 1 m3 (MB_PER_M3 mB)
PACKAGED = {}
for _key, _name, _building, _alt, _secs, _ins, _outs, _unlock in SF.RECIPES:
    if _building == "Packager" and _name.startswith("Packaged "):
        _fluid = _name[len("Packaged "):]
        PACKAGED[_fluid] = "packaged_" + slug(_fluid)
        _tex, _tint = ("minecraft:item/bucket", 0xE0E8F8) if any(n == "Empty Fluid Tank" for n, _ in _ins) else ("minecraft:item/bucket", 0xD04030)
        PARTS.append((PACKAGED[_fluid], _name, _tex, _tint))
# Gone: the Wire Spool, Screw, Iron, Quickwire and Silica bundles; the old Workshop routes' sub-assemblies and their
# half-built items; the DNA Capsule (now Satisfactory's Alien DNA Capsule); Excited Photonic Matter (a fluid in 1.0).

# Custom fluids: id, name, colour. They look like tinted water until real textures are picked.
FLUIDS = [
    ("heavy_oil_residue", "Heavy Oil Residue", 0x6A3A8A), ("alumina_solution", "Alumina Solution", 0xD8E0E8),
    ("sulfuric_acid", "Sulfuric Acid", 0xE8E040), ("nitrogen", "Nitrogen", 0xC8E8FF), ("nitric_acid", "Nitric Acid", 0xD8F0A0),
    ("dark_matter_residue", "Dark Matter Residue", 0x301848), ("ignimbrite", "Ignimbrite", 0xF08020), ("turbofuel", "Turbofuel", 0xD03030), ("vinegar", "Vinegar", 0xE8D8A0), ("mead", "Mead", 0xE0A030), ("cider", "Cider", 0xE8B060),
    ("rocket_fuel", "Rocket Fuel", 0xE06020), ("ionized_fuel", "Ionized Fuel", 0xF0F070), ("dissolved_silica", "Dissolved Silica", 0xE0E0F0),
    ("excited_photonic_matter", "Excited Photonic Matter", 0xFFFFE0),
    # what the old miners drilled with: Mk.1 took water, Mk.2 Drilling Mud, Mk.3 Coolant (the Build Gun miners decide in WP 5)
    ("drilling_mud", "Drilling Mud", 0x8A6A4A), ("coolant", "Coolant", 0x40C8D8),
]

# ---------------------------------------------------------------------------------------------------
# Milestones (HUB Milestones tab). (tier, id, name, unlock text, locked item ids, tokens). Cost and lock time are
# Satisfactory 1.0's and come from tools/satisfactory.py (below); the list says what each unlocks in the pack.
# "items" are the things nobody in the company can craft or place until the milestone is done (the parts each
# milestone's recipes make are added from PART_LOCKS, and the other mods' items from MORE_LOCKS).
# "tokens" are switches other systems read: minimap (Xaero's Minimap works), scanner:<node> (the node shows on the Resource Scanner), belt:<items a
# minute> (how fast a belt with no shaft runs by itself, Satisfactory's belt tiers), pumps:2 (Pipeline Mk.2).
# Every inventory-slot reward is gone: inventories stay vanilla size and there is no backpack.
C = "create:"
SAILS = [C + "white_sail"]   # the dyed sails are blocks only; they have no item to lock
TOOLBOXES = [C + c + "_toolbox" for c in "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()]
MILESTONES = [
    # Tier 0: HUB Upgrades, in order, with no lock time
    (0, "hub_upgrade_1", "HUB Upgrade 1",
     "Equipment Workshop, Portable Miner, Personal Storage Box, Wrench and Goggles, Mechanical Drill",
     [C + "wrench", C + "goggles", C + "mechanical_drill"], []),
    (0, "hub_upgrade_2", "HUB Upgrade 2",
     "Smelter, Power Line (wire and connectors), Electric Motor, Copper Ingot, Wire and Cable, copper nodes on the scanner, "
     "the first HUB Biomass Burner, Encased Fan and Nozzle",
     [C + "encased_fan", C + "nozzle"], ["scanner:copper"]),
    (0, "hub_upgrade_3", "HUB Upgrade 3",
     "Constructor, Power Pole, Screw and Reinforced Iron Plate, Mechanical Press, Saw, Depot, Millstone and Basin, stone nodes on the scanner",
     [C + "mechanical_press", C + "mechanical_saw", C + "depot", C + "millstone", C + "basin"], ["scanner:stone"]),
    (0, "hub_upgrade_4", "HUB Upgrade 4",
     "Belt Mk.1: belts with no shaft run by themselves at 60 items/min; Andesite Funnel",
     [C + "belt_connector", C + "andesite_funnel"], ["belt:60"]),
    (0, "hub_upgrade_5", "HUB Upgrade 5",
     "Miner Mk.1, Storage Container, Item Vault, a second HUB Biomass Burner", [C + "item_vault"], []),
    (0, "hub_upgrade_6", "HUB Upgrade 6",
     "Wormhole Gateway, Biomass Burner (6,144 SU), Biomass, Flywheel. Milestones lock the HUB from here on",
     [C + "flywheel"], []),

    # Tier 1
    (1, "base_building", "Base Building",
     "Foundations, ramps and walls, Lookout Tower; girders, scaffolding, ladders, casings, copycat blocks, windows, doors and seats",
     [C + "metal_girder", C + "andesite_scaffolding", C + "andesite_ladder", C + "copper_ladder", C + "copper_scaffolding", C + "copycat_panel",
      C + "copycat_step"], []),
    (1, "logistics", "Logistics",
     "Splitter and Merger (Andesite Tunnel), Conveyor Lift (Chute and fan lift), Item Hatch, Speedometer and Stressometer",
     [C + "andesite_tunnel", C + "chute", C + "item_hatch", C + "speedometer", C + "stressometer"], []),
    # the Map: Xaero's Minimap is switched off for a company's players until Field Research is done (hub/MapLock.java)
    (1, "field_research", "Field Research", "MAM, Object Scanner, Toolbox, the Map (Xaero's Minimap)", ["siftec:mam", "siftec:object_scanner"] + TOOLBOXES,
     ["minimap"]),

    # Tier 2
    (2, "part_assembly", "Part Assembly", "Assembler (Deployer); Copper Sheet, Rotor, Modular Frame and Smart Plating",
     [C + "deployer", C + "copper_sheet"], []),
    (2, "obstacle_clearing", "Obstacle Clearing", "Solid Biofuel; Mechanical Harvester, Mechanical Plough and Tree Fertilizer",
     [C + "mechanical_harvester", C + "mechanical_plough", C + "tree_fertilizer"], []),
    (2, "jump_pads", "Jump Pads", "Jump Pad (Weighted Ejector), U-Jelly Landing Pad", [C + "weighted_ejector", "siftec:landing_pad"], []),
    (2, "resource_sink_bonus_program", "Resource Sink Bonus Program", "AWESOME Sink and AWESOME Shop (cosmetics only)",
     [], []),
    (2, "logistics_mk2", "Logistics Mk.2", "Belt Mk.2: belts with no shaft run at 120 items/min", [], ["belt:120"]),

    # Tier 3 (Wormhole Phase 1)
    (3, "coal_power", "Coal Power",
     "Coal Generator, Water Extractor, Fluid Pipe, Mechanical Pump, Fluid Tank, Hose Pulley, Steam Engine and Blaze Burner, coal nodes on the scanner",
     [C + "steam_engine", C + "blaze_burner", C + "empty_blaze_burner", C + "fluid_pipe", C + "mechanical_pump", C + "fluid_tank", C + "hose_pulley"],
     ["scanner:coal"]),
    (3, "vehicular_transport", "Vehicular Transport",
     "Tractor, Truck Station, Cart Assembler minecart contraptions, Portable Storage Interface, Minecart Coupling",
     [C + "cart_assembler", C + "portable_storage_interface", C + "minecart_coupling"], []),
    (3, "basic_steel_production", "Basic Steel Production",
     "Foundry (Mechanical Mixer); Steel Ingot, Steel Beam, Steel Pipe and Versatile Framework",
     [C + "mechanical_mixer", "cgs:steel_ingot", "siftec:steel_casing"], []),
    (3, "enhanced_asset_security", "Enhanced Asset Security", "Xeno-Basher (Pneumatic Hammer) and the melee weapons", ["cgs:hammer"], []),

    # Tier 4
    (4, "ficsit_blueprints", "FICSIT Blueprints", "Blueprint Designer Mk.1", ["siftec:blueprint_designer"], []),
    (4, "logistics_mk3", "Logistics Mk.3", "Belt Mk.3: belts with no shaft run at 270 items/min; Industrial Storage Container", [], ["belt:270"]),
    (4, "advanced_steel_production", "Advanced Steel Production",
     "Miner Mk.2, Crushing Wheels; Encased Industrial Beam, Stator, Motor and Automated Wiring", [C + "crushing_wheel"], []),
    (4, "expanded_power_infrastructure", "Expanded Power Infrastructure",
     "Power Storage, Power Tower and Power Tower Platform; Mechanical Piston, Mechanical Bearing and Clockwork Bearing; chassis, "
     "Super Glue, Sticker and Contraption Controls",
     [C + "mechanical_piston", C + "sticky_mechanical_piston", C + "mechanical_bearing", C + "clockwork_bearing", C + "linear_chassis",
      C + "radial_chassis", C + "super_glue", C + "sticker", C + "contraption_controls"], []),
    (4, "contraption_transit", "Contraption Transit",
     "Replaces Hypertubes: Elevator Pulley and Elevator Contact, Rope Pulley, Gantry Shaft and Gantry Carriage",
     [C + "elevator_pulley", C + "rope_pulley", C + "gantry_shaft", C + "gantry_carriage"], []),

    # Tier 5 (Wormhole Phase 2)
    (5, "jetpack", "Jetpack", "Jetpack", ["siftec:jetpack"], []),
    (5, "oil_processing", "Oil Processing",
     "Oil Extractor (pumpjack, oil nodes only), Refinery, Fluid Valve; Plastic, Rubber, Fuel, Heavy Oil Residue, Petroleum Coke and Circuit Board; "
     "oil nodes on the scanner",
     ["createdieselgenerators:pumpjack_bearing", "createdieselgenerators:pumpjack_crank", "createdieselgenerators:pumpjack_head",
      "createdieselgenerators:pumpjack_hole", "createdieselgenerators:distillation_controller", C + "fluid_valve"], ["scanner:oil"]),
    (5, "logistics_mk4", "Logistics Mk.4", "Belt Mk.4: belts with no shaft run at 480 items/min; Truck", [], ["belt:480"]),
    (5, "fluid_packaging", "Fluid Packaging",
     "Packager (Spout and Item Drain), Empty Canister and packaged fluids, Liquid Biofuel, Portable Fluid Interface",
     [C + "spout", C + "item_drain", C + "portable_fluid_interface", "createdieselgenerators:canister"], []),
    (5, "petroleum_power", "Petroleum Power", "Fuel Generator (51,200 SU), Diesel Engines",
     ["createdieselgenerators:diesel_engine", "createdieselgenerators:large_diesel_engine", "createdieselgenerators:huge_diesel_engine"], []),

    # Tier 6
    (6, "industrial_manufacturing", "Industrial Manufacturing",
     "Manufacturer (Mechanical Crafter and Sequenced Assembly); Computer, Heavy Modular Frame, Modular Engine and Adaptive Control Unit",
     [C + "mechanical_crafter"], []),
    (6, "monorail_train_technology", "Monorail Train Technology",
     "Train Track, Train Station, Train Controls and Schedule; train doors, Mechanical Roller, Railway Casing and Postboxes",
     [C + "track", C + "track_station", C + "controls", C + "schedule", C + "mechanical_roller", C + "railway_casing"], []),
    (6, "railway_signalling", "Railway Signalling", "Train Signal and Train Observer", [C + "track_signal", C + "track_observer"], []),
    (6, "pipeline_engineering_mk2", "Pipeline Engineering Mk.2", "Pipeline and Pump Mk.2: Mechanical Pump flow and range doubled", [], ["pumps:2"]),
    (6, "ficsit_blueprints_mk2", "FICSIT Blueprints Mk.2", "Blueprint Designer Mk.2", [], []),

    # Tier 7 (Wormhole Phase 3)
    (7, "bauxite_refinement", "Bauxite Refinement",
     "Alumina Solution, Aluminum Scrap, Aluminum Ingot, Alclad Aluminum Sheet and Aluminum Casing; bauxite and quartz nodes on the scanner",
     [], ["scanner:bauxite", "scanner:quartz"]),
    (7, "logistics_mk5", "Logistics Mk.5", "Belt Mk.5: belts with no shaft run at 780 items/min", [], ["belt:780"]),
    (7, "hazmat_suit", "Hazmat Suit", "Hazmat Suit, Iodine Infused Filter", ["siftec:hazmat_suit"], []),
    (7, "hover_pack", "Hover Pack", "Hover Pack", ["siftec:hover_pack"], []),
    (7, "control_system_development", "Control System Development",
     "Blender; Sulfuric Acid, Battery, Radio Control Unit, Supercomputer and Assembly Director System", [], []),

    # Tier 8
    (8, "aeronautical_engineering", "Aeronautical Engineering",
     "Drone Port and Drone (Create's package network), sulfur nodes on the scanner",
     ["siftec:drone_port", "siftec:cardboard_drone", C + "packager", C + "package_frogport", C + "repackager", C + "stock_link", C + "stock_ticker",
      C + "redstone_requester", C + "factory_gauge", C + "chain_conveyor"], ["scanner:sulfur"]),
    (8, "nuclear_power", "Nuclear Power",
     "Nuclear Power Plant (512,000 SU); Encased Uranium Cell, Electromagnetic Control Rod, Uranium Fuel Rod and Magnetic Field Generator; "
     "uranium nodes on the scanner", [], ["scanner:uranium"]),
    (8, "advanced_aluminum_production", "Advanced Aluminum Production",
     "Resource Well Pressurizer and Extractor; Empty Fluid Tank, packaged Nitrogen Gas, Heat Sink, Cooling System and Fused Modular Frame; "
     "nitrogen wells on the scanner", ["siftec:resource_well_extractor"], ["scanner:nitrogen"]),
    (8, "leading_edge_production", "Leading-edge Production", "Miner Mk.3; Turbo Motor and Thermal Propulsion Rocket", [], []),
    (8, "particle_enrichment", "Particle Enrichment",
     "Particle Accelerator; Nitric Acid, Non-Fissile Uranium, Plutonium, Copper Powder, Pressure Conversion Cube and Nuclear Pasta",
     ["siftec:particle_accelerator"], []),

    # Tier 9 (Wormhole Phase 4)
    (9, "matter_conversion", "Matter Conversion",
     "Converter; Ficsite, Diamonds, Time Crystal, Biochemical Sculptor, Reanimated SAM and SAM Fluctuator; SAM nodes on the scanner",
     ["siftec:converter"], ["scanner:sam"]),
    (9, "quantum_encoding", "Quantum Encoding",
     "Quantum Encoder; Excited Photonic Matter, Dark Matter, Superposition Oscillator, Neural-Quantum Processor and AI Expansion Server", [], []),
    (9, "ficsit_blueprints_mk3", "FICSIT Blueprints Mk.3", "Blueprint Designer Mk.3", ["siftec:blueprint_designer_mk3"], []),
    (9, "spatial_energy_regulation", "Spatial Energy Regulation", "Main Portal and Satellite Portal; Singularity Cell and Ballistic Warp Drive",
     ["siftec:main_portal", "siftec:satellite_portal"], []),
    (9, "peak_efficiency", "Peak Efficiency", "Belt Mk.6: belts with no shaft run at 1,200 items/min; Ficsonium and the Ficsonium Fuel Rod", [], ["belt:1200"]),
]

# Satisfactory 1.0's cost and lock time for every milestone come from tools/satisfactory.py (Concrete as any stone,
# Iron Plate as Iron Sheet). Hypertubes is Contraption Transit at the same cost.
SAT_MILESTONES = {f"HUB Upgrade {n}": f"hub_upgrade_{n}" for n in range(1, 7)}
SAT_MILESTONES.update({"Base Building": "base_building", "Logistics": "logistics", "Field Research": "field_research",
    "Part Assembly": "part_assembly", "Obstacle Clearing": "obstacle_clearing", "Jump Pads": "jump_pads",
    "Resource Sink Bonus Program": "resource_sink_bonus_program", "Logistics Mk.2": "logistics_mk2", "Coal Power": "coal_power",
    "Vehicular Transport": "vehicular_transport", "Basic Steel Production": "basic_steel_production", "Enhanced Asset Security": "enhanced_asset_security",
    "FICSIT Blueprints": "ficsit_blueprints", "Logistics Mk.3": "logistics_mk3", "Advanced Steel Production": "advanced_steel_production",
    "Expanded Power Infrastructure": "expanded_power_infrastructure", "Hypertubes": "contraption_transit", "Jetpack": "jetpack", "Oil Processing": "oil_processing",
    "Logistics Mk.4": "logistics_mk4", "Fluid Packaging": "fluid_packaging", "Petroleum Power": "petroleum_power",
    "Industrial Manufacturing": "industrial_manufacturing", "Monorail Train Technology": "monorail_train_technology",
    "Pipeline Engineering Mk.2": "pipeline_engineering_mk2", "FICSIT Blueprints Mk.2": "ficsit_blueprints_mk2", "Railway Signalling": "railway_signalling",
    "Bauxite Refinement": "bauxite_refinement", "Logistics Mk.5": "logistics_mk5", "Hazmat Suit": "hazmat_suit", "Hoverpack": "hover_pack",
    "Control System Development": "control_system_development", "Aeronautical Engineering": "aeronautical_engineering", "Nuclear Power": "nuclear_power",
    "Advanced Aluminum Production": "advanced_aluminum_production", "Leading-Edge Production": "leading_edge_production",
    "Particle Enrichment": "particle_enrichment", "Matter Conversion": "matter_conversion", "Quantum Encoding": "quantum_encoding",
    "FICSIT Blueprints Mk.3": "ficsit_blueprints_mk3", "Spatial Energy Regulation": "spatial_energy_regulation", "Peak Efficiency": "peak_efficiency"})


def _sat_cost(cost):
    return ", ".join(f"{n} {sat_item(name)[1]}" for name, n in cost)


def _mmss(seconds):
    return f"{int(seconds) // 60}:{int(seconds) % 60:02d}"


_by_sat = {SAT_MILESTONES[name]: (tier, cost, secs) for tier, name, cost, secs, rec, bld, eq in SF.MILESTONES}
if set(_by_sat) != {m[1] for m in MILESTONES}:
    raise SystemExit(f"milestones: no 1.0 match for {sorted({m[1] for m in MILESTONES} ^ set(_by_sat))}")
for _m in MILESTONES:
    if _by_sat[_m[1]][0] != _m[0]:
        raise SystemExit(f"milestones: {_m[1]} is in tier {_m[0]}, 1.0 has it in tier {_by_sat[_m[1]][0]}")
# (tier, id, name, cost, minutes:seconds, unlock text, locked item ids, tokens)
MILESTONES = [(t, mid, name, _sat_cost(_by_sat[mid][1]), _mmss(_by_sat[mid][2]), text, items, tokens)
              for t, mid, name, text, items, tokens in MILESTONES]

# Switched off for good: they can be neither crafted nor placed.
DISABLED = [C + "water_wheel", C + "large_water_wheel", C + "blaze_cake", C + "blaze_cake_base", C + "creative_blaze_cake",
            "the_sift:sift_portal", "the_sift:sift_rift", C + "rotation_speed_controller", C + "schematic_table", C + "schematicannon", C + "empty_schematic", C + "schematic_and_quill",
            C + "schematic", "farmersdelight:wheat_dough"]   # Create's dough replaces Farmer's Delight's

# Wormhole phases, delivered at the Wormhole Gateway. Phase n opens the tiers listed.
PHASES = [
    ("phase_1", "Wormhole Phase 1", "50 Smart Plating", "Tiers 3 and 4"),
    ("phase_2", "Wormhole Phase 2", "1000 Smart Plating, 1000 Versatile Framework, 100 Automated Wiring", "Tiers 5 and 6"),
    ("phase_3", "Wormhole Phase 3", "2500 Versatile Framework, 500 Modular Engine, 100 Adaptive Control Unit", "Tiers 7 and 8"),
    ("phase_4", "Wormhole Phase 4", "500 Assembly Director System, 500 Magnetic Field Generator, 100 Nuclear Pasta, 250 Thermal Propulsion Rocket", "Tier 9"),
    ("phase_5", "Wormhole Phase 5", "1000 Nuclear Pasta, 1000 Biochemical Sculptor, 256 AI Expansion Server, 200 Ballistic Warp Drive", "Opens the portal to The Sift"),
]


def names():
    table = dict(ALIAS)
    for pid, name, _, _ in PARTS:
        table[name] = "siftec:" + pid
    return table


def parse_cost(text):
    """ "20 Iron Rod, 10 Iron Sheet" -> [("siftec:iron_rod", 20), ("create:iron_sheet", 10)] """
    table = names()
    out = []
    for piece in text.split(","):
        count, name = piece.strip().split(" ", 1)
        if ":" not in name and name not in table:
            raise SystemExit(f"unknown part in cost: {name!r}")
        out.append((name if ":" in name else table[name], int(count)))  # ids and #tags pass straight through
    return out


# ---------------------------------------------------------------------------------------------------
# MAM research (MAM Research tab). Tree: (id, name, icon item, [nodes]); node: (name, cost, unlock text, needs, items,
# tokens). Every node researches in 0:03, as in 1.0 (the Hard Drive, on its own page, takes 10:00).
# "needs" lists what must be researched first, by node name: "Name" in the same tree, "Tree name:Name" in another,
# and "A|B" for either. A node that needs nothing starts its tree; a tree shows in the MAM once one of its starting
# items is brought to it (or once anything in it is researched). A cost may name a tag ("#siftec:mycelia").
# Tokens: object:<thing> (the Object Scanner finds it: mercer_sphere, somersloop, mobs, and the wild food sweet_berries,
# apples and glow_berries), scanner:<node>, zipline (ride Power Lines with a Wrench),
# depot:stacks:<n> and depot:upload:<items a minute> (the Dimensional Depot's cloud), depot:manual (upload from the
# inventory), food:<what> (the Nutrients branches). The vanilla items' recipes are in recipes.py (VANILLA_RECIPES).
M = "minecraft:"
MAM_SECONDS = "0:03"
MAM = [
    ("megafauna", "Alien Megafauna", M + "rotten_flesh", [
        ("Zombie Research", "1 minecraft:rotten_flesh", "Alien Protein from rotten flesh", [], [], []),
        ("Skeleton Research", "1 minecraft:bone", "Alien Protein from bone", [], [], []),
        ("Spider Research", "1 minecraft:spider_eye", "Alien Protein from spider eye or string", [], [], []),
        ("Creeper Research", "1 Gunpowder", "Alien Protein from gunpowder", [], [], []),
        ("Bio-Organic Properties", "5 Alien Protein", "Alien DNA Capsule; Biomass from Alien Protein and mob drops",
         ["Zombie Research|Skeleton Research|Spider Research|Creeper Research"], [], []),
        ("Protein Inhaler", "2 Alien Protein, 20 minecraft:apple, 50 Rotor", "Protein Inhaler: heals you", ["Bio-Organic Properties"],
         ["siftec:protein_inhaler"], []),
        ("Structural Analysis", "5 Alien DNA Capsule, 100 Iron Rod", "Opens the equipment branch", ["Bio-Organic Properties"], [], []),
        ("Copper Backtank", "50 Copper Sheet, 25 Rotor, 5 Alien DNA Capsule", "Copper Backtank", ["Structural Analysis"], [C + "copper_backtank"], []),
        ("The Rebar Gun", "25 Rotor, 50 Reinforced Iron Plate, 500 Screw", "Rebar Gun (the Nailgun) and Iron Rebar", ["Copper Backtank"],
         ["cgs:nailgun"], []),
        ("Hostile Organism Detection", "10 Alien DNA Capsule, 5 Crystal Oscillator, 5 High-Speed Connector", "The Object Scanner finds hostile mobs",
         ["Structural Analysis"], [], ["object:mobs"]),
        ("Undying Biology", "20 Alien DNA Capsule, 10 minecraft:emerald, 4 minecraft:gold_block",
         "Totem of Undying (Manufacturer: 10 Alien DNA Capsule, 4 block of gold, 8 emerald, 5 AI Limiter)", ["Bio-Organic Properties"], [], []),
        ("Ender Analysis", "16 minecraft:ender_pearl, 20 Alien Protein", "Eye of Ender (Assembler: 1 ender pearl, 2 Quartz Crystal; no blaze powder)",
         ["Bio-Organic Properties", "Quartz:Quartz Crystals"], [], []),
    ]),
    ("caterium", "Caterium", C + "crushed_raw_zinc", [
        ("Caterium", "10 create:crushed_raw_zinc", "Zinc nodes on the scanner", [], [], ["scanner:zinc"]),
        ("Brass", "50 create:crushed_raw_zinc", "Brass blend, Brass Ingot and Brass Sheet; Create's brass blocks", ["Caterium"],
         ["siftec:brass_blend", C + "brass_ingot", C + "brass_sheet"], []),
        ("Quickwire", "50 Brass Ingot", "Quickwire (brass wire)", ["Brass"], [], []),
        ("Power Line Riding", "100 Quickwire, 50 Cable", "Ride Power Lines with a Wrench", ["Quickwire"], [], ["zipline"]),
        ("Caterium Electronics", "100 Quickwire",
         "Electron Tube and Precision Mechanism; Smart Fluid Pipe, Controller Rail, Smart Observer and Threshold Switch", ["Quickwire"],
         [C + "electron_tube", C + "precision_mechanism", C + "smart_fluid_pipe", C + "controller_rail", C + "content_observer", C + "stockpile_switch"], []),
        ("Stun Rebar", "50 Quickwire, 10 Iron Rebar", "Stun Rebar", ["Quickwire", "Alien Megafauna:The Rebar Gun"], [], []),
        ("Power Poles Mk.2", "300 Quickwire", "Power Pole Mk.2: seven wires", ["Quickwire"], [], []),
        ("AI Limiter", "200 Quickwire, 50 Copper Sheet", "AI Limiter", ["Caterium Electronics"], [], []),
        ("Mechanical Arm", "25 AI Limiter, 25 Precision Mechanism, 50 Rotor", "Mechanical Arm", ["AI Limiter"], [C + "mechanical_arm"], []),
        ("Smart Splitter", "10 AI Limiter, 50 Reinforced Iron Plate", "Brass Tunnel, Brass Funnel and Filter", ["AI Limiter"],
         [C + "brass_tunnel", C + "brass_funnel", C + "filter"], []),
        ("Power Switch", "100 Steel Beam, 50 AI Limiter", "Clutch and Gearshift", ["AI Limiter"], [C + "clutch", C + "gearshift"], []),
        ("High-Speed Connector", "500 Quickwire, 50 Plastic", "High-Speed Connector", ["AI Limiter"], [], []),
        ("Power Poles Mk.3", "50 High-Speed Connector, 200 Steel Pipe", "Power Pole Mk.3: ten wires", ["High-Speed Connector", "Power Poles Mk.2"], [], []),
        ("Priority Power Switch", "25 High-Speed Connector, 500 Quickwire", "Sequenced Gearshift, Adjustable Chain Gearshift and Speed Governor",
         ["Power Switch", "High-Speed Connector"], [C + "sequenced_gearshift", C + "adjustable_chain_gearshift", "siftec:speed_governor"], []),
        ("Programmable Splitter", "100 AI Limiter, 50 Computer, 50 Heavy Modular Frame", "Smart Chute and Attribute Filter", ["Smart Splitter"],
         [C + "smart_chute", C + "attribute_filter"], []),
        ("Windmills", "100 High-Speed Connector, 1000 Quickwire, 50 Motor", "Windmill Bearing and sails, at twice Create's SU", ["High-Speed Connector"],
         [C + "windmill_bearing", C + "sail_frame"] + SAILS, []),
    ]),
    ("mycelia", "Mycelia", M + "brown_mushroom", [
        ("Mycelia", "5 #siftec:mycelia", "Biomass from mushrooms", [], [], []),
        ("Fabric", "25 #siftec:mycelia, 100 Biomass", "Fabric", ["Mycelia"], [], []),
        ("Medical Properties", "25 #siftec:mycelia, 10 Stator", "Opens the medicine branch", ["Mycelia"], [], []),
        ("Parachute", "10 Fabric, 50 Cable", "Parachute", ["Fabric"], ["siftec:parachute"], []),
        ("Diving Gear", "50 Fabric, 100 Rotor", "Diving Helmet and Diving Boots, and the netherite backtank and diving gear",
         ["Fabric", "Alien Megafauna:Copper Backtank"],
         [C + "copper_diving_helmet", C + "copper_diving_boots", C + "netherite_diving_helmet", C + "netherite_diving_boots", C + "netherite_backtank"], []),
        ("Synthetic Polyester Fabric", "25 Fabric, 100 Polymer Resin", "Fabric from Polymer Resin in the Refinery", ["Fabric"], [], []),
        ("Gas Mask", "10 Coal, 50 Fabric, 50 Steel Pipe", "Gas Mask and Gas Filter", ["Fabric"], ["siftec:gas_mask"], []),
        ("Toxic Cellular Modification", "10 cgs:frag_grenade, 100 #siftec:mycelia, 200 Biomass",
         "Toxic Shot, the pack's gas round (Gunsmithing has no gas grenade)", ["Fabric", "Sulfur:Explosives"], ["siftec:toxic_shot"], []),
        ("Vitamin Inhaler", "10 #siftec:mycelia, 5 minecraft:sweet_berries", "Vitamin Inhaler: regeneration", ["Medical Properties"],
         ["siftec:vitamin_inhaler"], []),
        ("Therapeutic Inhaler", "15 #siftec:mycelia, 1 Alien Protein, 1 minecraft:glow_berries", "Therapeutic Inhaler: clears bad effects",
         ["Medical Properties"], ["siftec:therapeutic_inhaler"], []),
        ("Wingsuit Research", "8 minecraft:phantom_membrane, 100 Fabric, 50 Rotor",
         "Elytra (Manufacturer: 25 Fabric, 10 Alclad Aluminum Sheet, 4 Motor, 8 phantom membrane)", ["Parachute"], [], []),
        ("Elytra Backpack", "2 minecraft:elytra, 25 Precision Mechanism, 10 High-Speed Connector",
         "Elytra Backpack (Manufacturer: 1 Elytra, 1 Copper Backtank, 25 Precision Mechanism, 10 High-Speed Connector): Elytra and backtank in one chest item",
         ["Wingsuit Research", "Alien Megafauna:Copper Backtank"], ["siftec:elytra_backpack"], []),
    ]),
    ("nutrients", "Nutrients", M + "sweet_berries", [
        ("Sweet Berry Research", "2 minecraft:sweet_berries", "Sweet berry bushes on the Object Scanner", [], [], ["object:sweet_berries"]),
        ("Apple Research", "5 minecraft:apple", "Apple trees on the Object Scanner", [], [], ["object:apples"]),
        ("Glow Berry Research", "1 minecraft:glow_berries", "Glow berries on the Object Scanner", [], [], ["object:glow_berries"]),
        ("Bulk Fermentation", "25 Modular Frame, 50 Steel Pipe, 500 Wire", "Bulk Fermenter; vinegar, bottled at the Spout",
         ["Sweet Berry Research|Apple Research|Glow Berry Research"],
         ["createdieselgenerators:bulk_fermenter", "siftec:vinegar_bucket", "siftec:vinegar_bottle"], ["food:vinegar"]),
        ("Preserved Foods", "50 siftec:vinegar_bottle, 50 Iron Sheet, 100 minecraft:glass_bottle",
         "Pickles and jams on the Spout. Sneak and use a jar to throw it", ["Bulk Fermentation"], [], ["food:preserves"]),
        ("Cider and Mead", "50 minecraft:apple, 20 minecraft:honey_bottle, 50 Steel Pipe", "Cider and mead from the Bulk Fermenter, bottled at the Spout",
         ["Bulk Fermentation"], ["siftec:mead_bucket", "siftec:mead", "siftec:cider_bucket"], ["food:brewing"]),
        ("Automated Cooking", "25 Rotor, 50 Reinforced Iron Plate, 20 #siftec:preserves",
         "Cooking pot meals on the heated Mixer, cutting board recipes on the Deployer, and the secret ingredients (glowstone, blaze powder, "
         "nether wart: hold one and the food in your other hand, and use)", ["Preserved Foods"], [], ["food:kitchen", "food:seasoning"]),
        ("Nutritional Inhaler", "4 minecraft:sweet_berries, 10 minecraft:apple, 2 minecraft:glow_berries", "Nutritional Inhaler: a big heal",
         ["Bulk Fermentation"], ["siftec:nutritional_inhaler"], []),
    ]),
    ("quartz", "Quartz", M + "quartz", [
        ("Quartz", "10 Nether Quartz", "Quartz nodes on the scanner", [], [], ["scanner:quartz"]),
        ("Quartz Crystals", "20 Nether Quartz", "Quartz Crystal (Deployer with Sand Paper); Sand Paper and Rose Quartz", ["Quartz"],
         [C + "sand_paper", C + "red_sand_paper", C + "rose_quartz"], []),
        ("Silica", "20 Nether Quartz", "Silica (Millstone)", ["Quartz"], [], []),
        ("Blade Runners", "50 Silica, 10 Modular Frame", "Blade Runners", ["Silica"], ["siftec:blade_runners"], []),
        ("Shatter Rebar", "30 Quartz Crystal, 150 Iron Rebar", "Shatter Rebar", ["Quartz Crystals", "Alien Megafauna:The Rebar Gun"], [], []),
        ("Crystal Oscillator", "100 Quartz Crystal, 50 Reinforced Iron Plate", "Crystal Oscillator; Redstone Link and Create's redstone parts",
         ["Quartz Crystals"], [C + "redstone_link", C + "pulse_repeater", C + "pulse_extender", C + "pulse_timer", C + "powered_latch",
                               C + "powered_toggle_latch", C + "analog_lever", C + "redstone_contact"], []),
        ("Display Systems", "10 Crystal Oscillator, 100 Modular Frame", "Display Link, Display Board and Nixie Tube; the Explorer",
         ["Crystal Oscillator"], [C + "display_link", C + "display_board", C + "nixie_tube"], []),
        ("Radar Technology", "50 Crystal Oscillator, 50 Heavy Modular Frame, 50 Computer", "Radar Tower (reveals Xaero's map and crash sites)",
         ["Crystal Oscillator"], [], []),
        ("Tidal Research", "4 minecraft:nautilus_shell, 32 minecraft:prismarine_shard, 20 Quartz Crystal",
         "Trident (Manufacturer: 10 Steel Pipe, 5 Crystal Oscillator, 50 Quickwire, 16 prismarine shard)", ["Crystal Oscillator"], [], []),
        ("Heart of the Sea", "8 minecraft:nautilus_shell, 32 minecraft:prismarine_crystals, 20 Crystal Oscillator",
         "Heart of the Sea (Manufacturer: 8 Nautilus Shell, 16 prismarine crystals, 10 Crystal Oscillator, 50 Quickwire) and the Conduit",
         ["Tidal Research"], [M + "conduit"], []),
    ]),
    ("sulfur", "Sulfur", "cgs:sulfur", [
        ("Sulfur", "10 Sulfur", "Sulfur nodes on the scanner", [], [], ["scanner:sulfur"]),
        ("Black Powder", "50 Sulfur, 25 Coal", "Black Powder: gunpowder from sulfur, coal or charcoal, and bone meal", ["Sulfur"], [], []),
        ("Smokeless Powder", "100 Gunpowder, 50 Plastic", "Smokeless Powder (Refinery)", ["Black Powder"], [], []),
        ("Flintlock", "25 Gunpowder, 50 Iron Rod, 10 Reinforced Iron Plate", "Flintlock, its barrels and rounds", ["Black Powder"], ["cgs:flintlock"], []),
        ("Ignimbrite", "50 Sulfur, 100 minecraft:cobblestone", "Ignimbrite; fire charges, magma blocks and lava", ["Sulfur"], ["siftec:ignimbrite_bucket"], []),
        ("Explosives", "50 Gunpowder, 100 Steel Pipe, 200 Cable", "TNT from the Spout, Frag Grenade", ["Black Powder", "Ignimbrite"], ["cgs:frag_grenade"], []),
        ("The Rifle", "50 Smokeless Powder, 100 Motor, 200 Rubber", "Revolver, Shotgun and their rounds", ["Flintlock", "Smokeless Powder"],
         ["cgs:revolver", "cgs:shotgun"], []),
        ("Heavy Weapons", "50 cgs:frag_grenade, 25 Computer, 50 Heavy Modular Frame", "Gatling, Launcher and the other heavy guns",
         ["The Rifle", "Explosives"], ["cgs:gatling", "cgs:launcher"], []),
        ("Compacted Coal", "1 Hard Drive, 25 Sulfur, 25 Coal", "Compacted Coal", ["Sulfur"], [], []),
        ("Experimental Power Generation", "25 Sulfur, 50 Modular Frame, 100 Rotor", "Opens the fuel branch", ["Compacted Coal"], [], []),
        ("Turbofuel", "1 Hard Drive, 15 Compacted Coal, 50 Packaged Fuel", "Turbofuel", ["Experimental Power Generation"], ["siftec:turbofuel_bucket"], []),
        ("Rocket Fuel", "1 Hard Drive, 10 Empty Fluid Tank, 100 Packaged Turbofuel", "Rocket Fuel, packed and unpacked", ["Turbofuel"], [], []),
        ("Ionized Fuel", "1 Hard Drive, 100 Time Crystal, 200 Packaged Rocket Fuel", "Ionized Fuel, packed and unpacked", ["Rocket Fuel"], [], []),
        ("Geothermal Power", "100 High-Speed Connector, 1000 Quickwire, 50 Motor", "Geothermal Generator (Geyser Engine) over a sulfur geyser",
         ["Experimental Power Generation"], [], []),
    ]),
    ("alien", "Alien Technology", "siftec:mercer_sphere", [
        ("Mercer Sphere Analysis", "1 Mercer Sphere", "The Object Scanner finds Mercer Spheres", [], [], ["object:mercer_sphere"]),
        ("Somersloop Analysis", "1 Somersloop", "The Object Scanner finds Somersloops", [], [], ["object:somersloop"]),
        ("SAM Analysis", "10 SAM", "SAM nodes on the scanner", [], [], ["scanner:sam"]),
        ("SAM Reanimation", "20 SAM", "Reanimated SAM (fan haunting)", ["SAM Analysis"], [], []),
        ("SAM Fluctuator", "10 Reanimated SAM, 100 Steel Pipe, 200 Wire", "SAM Fluctuator", ["SAM Reanimation"], [], []),
        ("Dimensional Depot", "1 Mercer Sphere, 11 SAM Fluctuator", "Dimensional Depot: uploads the Item Vault it sits on to the company's cloud",
         ["Mercer Sphere Analysis", "SAM Fluctuator"], ["siftec:dimensional_depot"], []),
        ("Depot Expansion (200%)", "3 Mercer Sphere, 47 SAM Fluctuator", "The cloud holds 2 stacks of each item", ["Dimensional Depot"], [], ["depot:stacks:2"]),
        ("Upload Upgrade 30/min", "3 Mercer Sphere, 47 SAM Fluctuator", "Depots upload 30 items/min", ["Dimensional Depot"], [], ["depot:upload:30"]),
        ("Manual Depot Uploader", "3 Mercer Sphere, 17 Computer, 29 SAM Fluctuator",
         "Upload from your inventory: shift-click an item in the cloud window", ["Dimensional Depot"], [], ["depot:manual"]),
        ("Depot Expansion (300%)", "7 Mercer Sphere, 103 SAM Fluctuator", "3 stacks of each item", ["Depot Expansion (200%)"], [], ["depot:stacks:3"]),
        ("Upload Upgrade 60/min", "7 Mercer Sphere, 103 SAM Fluctuator", "60 items/min", ["Upload Upgrade 30/min"], [], ["depot:upload:60"]),
        ("Depot Expansion (400%)", "13 Mercer Sphere, 151 SAM Fluctuator", "4 stacks of each item", ["Depot Expansion (300%)"], [], ["depot:stacks:4"]),
        ("Upload Upgrade 120/min", "13 Mercer Sphere, 151 SAM Fluctuator", "120 items/min", ["Upload Upgrade 60/min"], [], ["depot:upload:120"]),
        ("Depot Expansion (500%)", "23 Mercer Sphere, 199 SAM Fluctuator", "5 stacks of each item", ["Depot Expansion (400%)"], [], ["depot:stacks:5"]),
        ("Upload Upgrade 240/min", "23 Mercer Sphere, 199 SAM Fluctuator", "240 items/min", ["Upload Upgrade 120/min"], [], ["depot:upload:240"]),
        ("Alien Energy Harvesting", "10 SAM Fluctuator", "Opens the power branch", ["SAM Fluctuator"], [], []),
        ("Power Augmenter", "1 Somersloop, 100 SAM Fluctuator, 50 Computer",
         "Alien Power Augmenter (built with 10 Somersloops and parts): flat SU, and +10% to its grid", ["Somersloop Analysis", "Alien Energy Harvesting"],
         [], []),
        ("Alien Power Matrix", "50 Singularity Cell, 500 SAM Fluctuator, 100 Time Crystal", "Alien Power Matrix (Quantum Encoder): raises an Augmenter's boost to 30%",
         ["Power Augmenter"], [], []),
        ("Pocket Storage", "5 SAM Fluctuator, 50 Iron Sheet", "Shulker Shell (Assembler: 1 SAM Fluctuator, 5 Iron Sheet), so shulker boxes need no End trip",
         ["SAM Fluctuator"], [], []),
        ("Netherite Synthesis", "64 minecraft:netherrack, 10 Diamond, 100 Steel Ingot, 20 SAM Fluctuator",
         "Netherite Ingot (Manufacturer: 4 gold ingot, 2 diamond, 20 Steel Ingot, 16 netherrack); no ancient debris needed", ["SAM Fluctuator"], [], []),
    ]),
]
MAM_TREES = {t[1]: t[0] for t in MAM}


def mam_id(tree, name):
    """The research id of a node: mam_<tree>_<name>."""
    return f"mam_{tree}_{slug(name)}"


def mam(tree_name, node):
    """The id of a node, by its tree's name and its own ("Caterium", "Quickwire")."""
    tid = MAM_TREES[tree_name]
    tree = next(t for t in MAM if t[0] == tid)
    if node not in [n[0] for n in tree[3]]:
        raise SystemExit(f"MAM: {tree_name} has no node {node!r}")
    return mam_id(tid, node)


def _mam_needs(tid, needs):
    """The needs of a node as ids, each alternative of an "A|B" joined by "|"."""
    names = {t[1]: t for t in MAM}
    out = []
    for need in needs:
        alts = []
        for one in need.split("|"):
            tree_name, node = one.split(":", 1) if ":" in one else (next(t[1] for t in MAM if t[0] == tid), one)
            if tree_name not in names:
                raise SystemExit(f"MAM: no tree {tree_name!r} (needed by a {tid} node)")
            alts.append(mam(tree_name, node))
        out.append("|".join(alts))
    return out


# each node as gen_assets.py and the checkers read it: (id, tree, name, cost, time, text, needs (ids), items, tokens)
def mam_nodes():
    for tid, tname, icon, nodes in MAM:
        for name, cost, text, needs, items, tokens in nodes:
            yield (mam_id(tid, name), tid, name, cost, MAM_SECONDS, text, _mam_needs(tid, needs), items, tokens)


# what brings each tree up in the MAM: the items its starting nodes take
def mam_triggers(tid):
    tree = next(t for t in MAM if t[0] == tid)
    return [i for name, cost, text, needs, items, tokens in tree[3] if not needs for i, c in parse_cost(cost)]


# the 1.0 MAM node behind each recipe it unlocks, as a node of the trees above
SAT_MAM = {"Hog Research": ("Alien Megafauna", "Zombie Research"), "Spitter Research": ("Alien Megafauna", "Skeleton Research"),
           "Stinger Research": ("Alien Megafauna", "Spider Research"), "Hatcher Research": ("Alien Megafauna", "Creeper Research"),
           "Bio-Organic Properties": ("Alien Megafauna", "Bio-Organic Properties"), "The Rebar Gun": ("Alien Megafauna", "The Rebar Gun"),
           "Caterium Ingots": ("Caterium", "Brass"), "Quickwire": ("Caterium", "Quickwire"), "AI Limiter": ("Caterium", "AI Limiter"),
           "High-Speed Connector": ("Caterium", "High-Speed Connector"), "Stun Rebar": ("Caterium", "Stun Rebar"),
           "Power Poles Mk.2": ("Caterium", "Power Poles Mk.2"), "Power Poles Mk.3": ("Caterium", "Power Poles Mk.3"),
           "Mycelia": ("Mycelia", "Mycelia"), "Fabric": ("Mycelia", "Fabric"), "Gas Mask": ("Mycelia", "Gas Mask"),
           "Synthetic Polyester Fabric": ("Mycelia", "Synthetic Polyester Fabric"), "Quartz Crystals": ("Quartz", "Quartz Crystals"),
           "Silica": ("Quartz", "Silica"), "Crystal Oscillator": ("Quartz", "Crystal Oscillator"), "Shatter Rebar": ("Quartz", "Shatter Rebar"),
           "Black Powder": ("Sulfur", "Black Powder"), "Compacted Coal": ("Sulfur", "Compacted Coal"), "Turbofuel": ("Sulfur", "Turbofuel"),
           "Smokeless Powder": ("Sulfur", "Smokeless Powder"), "Rocket Fuel": ("Sulfur", "Rocket Fuel"), "Ionized Fuel": ("Sulfur", "Ionized Fuel"),
           "SAM Reanimation": ("Alien Technology", "SAM Reanimation"), "SAM Fluctuator": ("Alien Technology", "SAM Fluctuator"),
           "Alien Power Matrix": ("Alien Technology", "Alien Power Matrix"), "Power Augmenter": ("Alien Technology", "Power Augmenter")}


def unlock_id(unlock):
    """The milestone or MAM node id behind a 1.0 unlock; None from the start, "drive" for the Hard Drive pool."""
    kind, name = unlock
    if kind == "start":
        return None
    if kind == "drive":
        return "drive"
    if kind in ("hub", "milestone"):
        return SAT_MILESTONES[name]
    if kind == "mam":
        return mam(*SAT_MAM[name])
    raise SystemExit(f"no unlock for {unlock}")


# The Manufacturer and the Quantum Encoder build in two steps (Build Gun tab): a Mixer on a Basin takes all but one of
# each input and makes the recipe's Core; then one loop of sequenced assembly adds the last one of each, as
# "Incomplete <item>". The Quantum Encoder's Spout adds the Excited Photonic Matter in that loop, and its result comes
# out Charged, for the Item Drain to take the Dark Matter Residue off. Each recipe, alternates too, has its own Core.
TWO_STEP = ("Manufacturer", "Quantum Encoder")
CORES = {}   # Satisfactory recipe key -> (core part id or None when every input is a single item, incomplete id, charged id or None)


def _part_name(n):
    return n if n in ALIAS or ":" in n else n


for _key, _name, _building, _alt, _secs, _ins, _outs, _unlock in sat_recipes():
    if _building not in TWO_STEP:
        continue
    _label = _name.replace("Alternate: ", "")
    _id = slug(_label)
    _items = [(n, a) for k, n, a in _ins if k == "item"]
    _core = None
    if any(a > 1 for n, a in _items):
        _core = _id + "_core"
        PARTS.append((_core, _label + " Core", "minecraft:item/magma_cream", 0x909098))
    _inc = "incomplete_" + _id
    PARTS.append((_inc, "Incomplete " + _label, "minecraft:item/magma_cream", 0x606068))
    _charged = None
    if _building == "Quantum Encoder":
        _charged = "charged_" + _id
        PARTS.append((_charged, "Charged " + _label, "minecraft:item/magma_cream", 0x402060))
    CORES[_key] = (_core, _inc, _charged)

# ---------------------------------------------------------------------------------------------------
# The pack's own late-game machines, the Converter and the Particle Accelerator, with 1.0's recipes (alternates
# included, each with "alt"). In: [(part, count)]; fluids as (fluid name, mB). The machine makes whichever recipe is
# selected on it; how it does that is work package 9's.
PROCESSORS = {"converter": ("Converter", []), "particle_accelerator": ("Particle Accelerator", [])}
for _key, _name, _building, _alt, _secs, _ins, _outs, _unlock in sat_recipes():
    if _building not in ("Converter", "Particle Accelerator"):
        continue
    _r = {"in": [(n, a) for k, n, a in _ins if k == "item"], "fluid_in": None, "out": None, "fluid_out": None, "seconds": _secs,
          "alt": slug(_name) if unlock_id(_unlock) == "drive" else None, "name": _name.replace("Alternate: ", "")}
    for k, n, a in _ins:
        if k == "fluid":
            _r["fluid_in"] = (n, round(a * MB_PER_M3))
    for k, n, a in _outs:
        if k == "fluid":
            _r["fluid_out"] = (n, round(a * MB_PER_M3))
        elif _r["out"] is None:
            _r["out"] = (n, a)
        else:
            _r["also"] = (n, a)
    PROCESSORS["converter" if _building == "Converter" else "particle_accelerator"][1].append(_r)

# AWESOME Sink (AWESOME Sink and Shop tab): points for one of each item. Every Satisfactory part has Satisfactory's own
# value (where a part is the same item as a vanilla one, Satisfactory's value wins). A spare Hard Drive is 1,000 (a
# SIFTEC rule: Satisfactory takes none).
SINK_POINTS = {}
for _sat, _info in sorted(SF.ITEMS.items()):
    _pack = sat_item(_sat, True)
    if _pack and _pack[0] == "item" and not _pack[1].startswith("#") and _info["sink"]:
        SINK_POINTS.setdefault(_pack[1], _info["sink"])
SINK_POINTS["Hard Drive"] = 1000
SINK_POINTS["siftec:object_scanner"] = SINK_POINTS.pop("Object Scanner")
SINK_POINTS["siftec:xeno_zapper"] = SINK_POINTS.pop("Xeno-Zapper")

# Vanilla and modded items: the tab's SIFTEC choices, kept at or below similar Satisfactory parts so a factory always
# beats farming for points. An id here never beats a Satisfactory value above; tags ("#...") are tried after ids.
# Anything in neither goes by its Minecraft rarity (SINK_RARITY), and tools, armour and weapons not listed are the sum
# of what they are made of; every one gets 25 more for each enchantment level on it (the Sink reads that).
M_ = "minecraft:"
SINK_VANILLA = {}
for _ids, _p in (
        # junk and basic blocks
        ("dirt coarse_dirt rooted_dirt cobblestone cobbled_deepslate gravel sand red_sand netherrack stone granite diorite andesite deepslate "
         "tuff calcite glass soul_sand soul_soil blackstone basalt end_stone clay mud", 1),
        # logs and wood
        ("stick", 1),
        # plants and crops; pumpkins and melons
        ("wheat carrot potato beetroot sugar_cane cactus kelp bamboo sweet_berries glow_berries apple wheat_seeds beetroot_seeds "
         "pumpkin_seeds melon_seeds melon_slice brown_mushroom red_mushroom", 2),
        ("pumpkin melon carved_pumpkin", 4),
        # cooked food
        ("bread cooked_beef cooked_porkchop cooked_chicken cooked_mutton cooked_rabbit cooked_cod cooked_salmon baked_potato", 6),
        ("golden_carrot", 60),
        # mob drops
        ("rotten_flesh feather", 2), ("bone string spider_eye", 3), ("gunpowder leather", 4),
        ("slime_ball", 10), ("phantom_membrane", 25), ("ender_pearl blaze_rod", 30), ("ghast_tear", 60), ("shulker_shell", 250),
        # ores, ingots and gems
        ("raw_iron raw_copper", 1), ("coal redstone", 3), ("lapis_lazuli quartz", 4), ("raw_gold", 8),
        ("iron_ingot", 2), ("copper_ingot", 6), ("amethyst_shard", 8), ("gold_ingot", 20), ("emerald", 60),
        ("diamond", 200), ("ancient_debris", 400), ("netherite_scrap", 500), ("netherite_ingot", 2500),
        # rare loot and boss items
        ("totem_of_undying", 1000), ("heart_of_the_sea", 1500), ("trident", 2000), ("enchanted_golden_apple", 2500), ("elytra", 5000),
        ("dragon_head", 5000), ("nether_star", 10000), ("dragon_egg", 25000)):
    for _id in _ids.split():
        SINK_VANILLA[M_ + _id] = _p
# storage blocks are 9 of what they hold
for _block, _of in (("iron_block", "iron_ingot"), ("gold_block", "gold_ingot"), ("copper_block", "copper_ingot"), ("emerald_block", "emerald"),
                    ("diamond_block", "diamond"), ("netherite_block", "netherite_ingot"), ("coal_block", "coal"), ("redstone_block", "redstone"),
                    ("lapis_block", "lapis_lazuli"), ("raw_iron_block", "raw_iron"), ("raw_copper_block", "raw_copper"), ("raw_gold_block", "raw_gold")):
    SINK_VANILLA[M_ + _block] = 9 * SINK_VANILLA[M_ + _of]
SINK_VANILLA[M_ + "amethyst_block"] = 4 * SINK_VANILLA[M_ + "amethyst_shard"]
# music discs, and Create's own parts
for _disc in "13 cat blocks chirp far mall mellohi stal strad ward 11 wait otherside 5 pigstep relic creator creator_music_box precipice tears lava_chicken".split():
    SINK_VANILLA[f"{M_}music_disc_{_disc}"] = 500
SINK_VANILLA.update({C + "andesite_alloy": 4, C + "brass_ingot": 20, C + "precision_mechanism": 150})
# tags, tried after the ids
SINK_TAGS = {"#minecraft:logs": 3, "#minecraft:planks": 1, "#minecraft:saplings": 2, "#minecraft:flowers": 2, "#minecraft:leaves": 1,
             "#minecraft:decorated_pot_sherds": 100, "#minecraft:trim_templates": 300, "#c:crops": 2, "#c:foods/cooked_meat": 6,
             "#farmersdelight:meals": 25}
# vanilla tools, armour and weapons: the sum of what they are made of (sticks 1 each)
_MATERIALS = {"wooden": ("#minecraft:planks", 1), "stone": ("cobblestone", 1), "copper": ("copper_ingot", 6), "iron": ("iron_ingot", 2),
              "golden": ("gold_ingot", 20), "diamond": ("diamond", 200)}
_TOOLS = {"sword": (2, 1), "pickaxe": (3, 2), "axe": (3, 2), "shovel": (1, 2), "hoe": (2, 2)}
_ARMOUR = {"helmet": 5, "chestplate": 8, "leggings": 7, "boots": 4}
for _mat, (_part, _each) in _MATERIALS.items():
    for _tool, (_n, _sticks) in _TOOLS.items():
        SINK_VANILLA[f"{M_}{_mat}_{_tool}"] = _n * _each + _sticks
    if _mat in ("copper", "iron", "diamond"):
        for _piece, _n in _ARMOUR.items():
            SINK_VANILLA[f"{M_}{_mat}_{_piece}"] = _n * _each
for _piece, _n in _ARMOUR.items():
    SINK_VANILLA[f"{M_}golden_{_piece}"] = _n * 20
    SINK_VANILLA[f"{M_}leather_{_piece}"] = _n * 4
    SINK_VANILLA[f"{M_}netherite_{_piece}"] = SINK_VANILLA[f"{M_}diamond_{_piece}"] + 2500
for _tool in _TOOLS:
    SINK_VANILLA[f"{M_}netherite_{_tool}"] = SINK_VANILLA[f"{M_}diamond_{_tool}"] + 2500
SINK_VANILLA.update({M_ + "bow": 12, M_ + "crossbow": 3 * 1 + 2 * 3 + 2 + 3, M_ + "shield": 6 + 2, M_ + "fishing_rod": 3 + 6, M_ + "mace": 5000})
SINK_RARITY = {"common": 2, "uncommon": 50, "rare": 500, "epic": 2500}
SINK_ENCHANT = 25
# what the Sink will not take: the radioactive and toxic waste has to be dealt with properly (as Satisfactory's Sink
# refuses nuclear waste), and coupons are what it pays in
SINK_REFUSED = ["siftec:toxic_residue", "siftec:uranium_waste", "siftec:plutonium_waste", "siftec:ficsit_coupon"]
# FICSIT Coupons: Satisfactory 1.0's curve, unchanged (the Sink screen prints them; checked by the selftest)
COUPON_FIRST, COUPON_FIRST_COST, COUPON_LAST, COUPON_CAP = 3, 500, 3000, 249501250


# Build costs at Satisfactory 1.0's amounts, in the pack's names (Concrete is any stone). BUILD_COSTS is every
# building the Build Gun will place, under its 1.0 name (work packages 5 and 9 give each its id in BUILDINGS);
# EQUIPMENT_COSTS is what the Equipment Workshop builds.
def sat_cost(cost):
    """A 1.0 cost as the pack writes costs, or None when it takes something the pack leaves out. A Portable Miner in a
    cost (the miners, the Drone) is a Build Gun building in the pack, not an item, so its own parts are paid instead."""
    merged = {}
    for n, a in cost:
        for m, b in (SF.EQUIPMENT["Portable Miner"] if n == "Portable Miner" else [(n, 1)]):
            merged[m] = merged.get(m, 0) + a * b
    cost = list(merged.items())
    parts = [sat_item(n) for n, _ in cost]
    if None in parts or any(p[0] == "fluid" for p in parts):
        return None
    return ", ".join(f"{a} {p[1]}" for p, (_, a) in zip(parts, cost))


BUILD_COSTS = {name: sat_cost(cost) for name, cost in SF.BUILDINGS.items()}
EQUIPMENT_COSTS = {name: sat_cost(cost) for name, cost in SF.EQUIPMENT.items() if sat_cost(cost)}

# AWESOME Shop (AWESOME Sink and Shop tab): cosmetics only, bought with FICSIT Coupons, unlocked per company. Each entry:
# (id, name, category, coupons, what it gives). What it gives:
#   customizer       the Customizer (free once the Shop is built; any member takes one from the Shop)
#   swatch           one more colour swatch slot for the Customizer (repeatable, up to SWATCH_MAX)
#   finish:<id>      a paint finish for the Customizer (FINISHES)
#   skin:<id>        a casing skin for Build Gun buildings (SKINS), or a Storage Container skin (STORAGE_SKINS)
#   decor:<id>       a decoration (DECOR): once bought, members take them from the Shop for their part cost
#   cup:<id>         a player cosmetic held in the hand (CUPS), taken the same way
# The tab's other entries (signs and billboards, foundation, floor, wall and roof materials, holiday sets, helmet skins
# and suit trims, emote packs, the company banner designer, the Golden Cart Assembler skin and the Boom Box) need the
# systems they decorate first: foundations, signs, emotes, banners and vehicles are not in the pack yet.
SWATCH_MAX = 18
# finish id -> (name, the block the primary-colour panels become; "{colour}" is the swatch's primary colour)
FINISHES = {"painted": ("Painted", "minecraft:{colour}_concrete"), "unpainted": ("Unpainted", "create:industrial_iron_block"),
            "copper": ("Copper", "minecraft:waxed_copper_block"), "chrome": ("Chrome", "minecraft:iron_block"),
            "carbon_steel": ("Carbon Steel", "minecraft:netherite_block"), "caterium": ("Caterium", "create:brass_block")}
# casing skin id -> (name, the block a building's light grey panels become)
SKINS = {"andesite": ("Andesite Casing", "create:andesite_casing"), "brass": ("Brass Casing", "create:brass_casing"),
         "copper": ("Copper Casing", "create:copper_casing"), "steel": ("Steel Casing", "siftec:steel_casing")}
STORAGE_SKINS = {"oak": ("Oak Crate", "minecraft:oak_planks"), "spruce": ("Spruce Crate", "minecraft:spruce_planks"),
                 "brick": ("Brick Store", "minecraft:bricks"), "barrel": ("Barrel Store", "minecraft:barrel")}
DYES = "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()

# Decorations (blocks): (id, name, category, light, texture, [elements as (from, to) or (from, to, rotation)], part cost).
# Statues stand on a small plinth; the creature statues are Minecraft mobs in metal or stone.
_P = ([3, 0, 3], [13, 2, 13])   # a plinth
STONE_T, IRON_T, GOLD_T, COPPER_T = "minecraft:block/smooth_stone", "minecraft:block/iron_block", "minecraft:block/gold_block", "minecraft:block/copper_block"
DECOR = [
    ("checkmark_statue", "SIFTEC Checkmark Statue", "statues", 0, "minecraft:block/orange_concrete",
     [_P, ([4, 2, 7], [7, 10, 9], ("z", 45, [5.5, 2, 8])), ([7, 2, 7], [10, 16, 9], ("z", -22.5, [8.5, 2, 8]))], "10 Iron Sheet, 5 Iron Rod"),
    ("adequate_award", "Adequate Pioneering Award", "statues", 0, COPPER_T,
     [_P, ([7, 2, 7], [9, 7, 9]), ([5, 7, 5], [11, 12, 11]), ([4, 11, 4], [12, 13, 12])], "20 Copper Sheet"),
    ("pretty_good_award", "Pretty Good Pioneering Award", "statues", 0, IRON_T,
     [_P, ([7, 2, 7], [9, 7, 9]), ([5, 7, 5], [11, 12, 11]), ([4, 11, 4], [12, 13, 12])], "20 Iron Sheet"),
    ("satisfactory_award", "Satisfactory Pioneering Award", "statues", 0, GOLD_T,
     [_P, ([7, 2, 7], [9, 7, 9]), ([5, 7, 5], [11, 12, 11]), ([4, 11, 4], [12, 13, 12])], "10 minecraft:gold_ingot"),
    ("silver_hog_statue", "Silver Hog Statue", "statues", 0, IRON_T,
     [_P, ([4, 5, 3], [12, 11, 12]), ([5, 6, 12], [11, 12, 16]), ([6.5, 7, 15.5], [9.5, 9, 16]),
      ([4.5, 2, 3.5], [6.5, 5, 5.5]), ([9.5, 2, 3.5], [11.5, 5, 5.5]), ([4.5, 2, 9.5], [6.5, 5, 11.5]), ([9.5, 2, 9.5], [11.5, 5, 11.5])],
     "20 Iron Sheet, 10 Iron Rod"),
    ("lizard_doggo_statue", "Lizard Doggo Statue", "statues", 0, "minecraft:block/pink_terracotta",
     [_P, ([5, 4, 2], [11, 8, 11]), ([5, 5, 11], [11, 10, 15]), ([4, 9, 12], [5, 12, 13]), ([11, 9, 12], [12, 12, 13]),
      ([7, 5, 0], [9, 7, 2]), ([5, 2, 3], [7, 4, 5]), ([9, 2, 3], [11, 4, 5]), ([5, 2, 8], [7, 4, 10]), ([9, 2, 8], [11, 4, 10])],
     "20 Iron Sheet, 5 Rotor"),
    ("confusing_creature_statue", "Confusing Creature Statue", "statues", 0, "minecraft:block/purpur_block",
     [_P, ([5, 2, 5], [11, 8, 11], ("y", 45, [8, 5, 8])), ([6, 8, 6], [10, 13, 10]), ([4, 12, 7], [12, 14, 9], ("x", 22.5, [8, 13, 8])),
      ([7, 13, 4], [9, 16, 12], ("z", -22.5, [8, 14, 8]))], "10 Reinforced Iron Plate"),
    ("golden_nut_statue", "Golden Nut Statue", "statues", 6, GOLD_T,
     [_P, ([5, 2, 5], [11, 12, 11]), ([4, 4, 6], [12, 10, 10]), ([6, 4, 4], [10, 10, 12]), ([7, 12, 7], [9, 14, 9])], "4 minecraft:gold_block"),
    ("creeper_statue", "Creeper Statue", "mob statues", 0, STONE_T,
     [_P, ([4, 9, 4], [12, 16, 12]), ([5, 4, 6], [11, 10, 10]), ([4, 2, 3], [7, 4, 6]), ([9, 2, 3], [12, 4, 6]),
      ([4, 2, 10], [7, 4, 13]), ([9, 2, 10], [12, 4, 13])], "16 Stone"),
    ("zombie_statue", "Zombie Statue", "mob statues", 0, STONE_T,
     [_P, ([5, 12, 5], [11, 16, 11]), ([5, 7, 6], [11, 12, 10]), ([3, 10, 6], [5, 12, 13]), ([11, 10, 6], [13, 12, 13]),
      ([5, 2, 6], [8, 7, 10]), ([8, 2, 6], [11, 7, 10])], "16 Stone"),
    ("skeleton_statue", "Skeleton Statue", "mob statues", 0, "minecraft:block/bone_block_side",
     [_P, ([5, 12, 5], [11, 16, 11]), ([6, 7, 7], [10, 12, 9]), ([4, 7, 7], [5, 12, 9]), ([11, 7, 7], [12, 12, 9]),
      ([6, 2, 7], [7, 7, 9]), ([9, 2, 7], [10, 7, 9])], "16 minecraft:bone"),
    ("cow_statue", "Cow Statue", "mob statues", 0, STONE_T,
     [_P, ([4, 6, 2], [12, 12, 13]), ([5, 8, 13], [11, 14, 16]), ([4, 13, 14], [5, 15, 15]), ([11, 13, 14], [12, 15, 15]),
      ([4.5, 2, 2.5], [6.5, 6, 4.5]), ([9.5, 2, 2.5], [11.5, 6, 4.5]), ([4.5, 2, 10.5], [6.5, 6, 12.5]), ([9.5, 2, 10.5], [11.5, 6, 12.5])],
     "16 Stone"),
    # decorative lights and the control panel
    ("ceiling_light", "Ceiling Light", "lighting", 15, "minecraft:block/sea_lantern", [([3, 14, 3], [13, 16, 13])], "2 Iron Sheet, 5 Wire"),
    ("flood_light", "Flood Light", "lighting", 15, "minecraft:block/redstone_lamp_on",
     [([5, 0, 5], [11, 2, 11]), ([7, 2, 7], [9, 8, 9]), ([4, 8, 5], [12, 14, 11], ("x", -22.5, [8, 11, 8]))], "4 Iron Sheet, 10 Wire"),
    ("street_light", "Street Light", "lighting", 15, "minecraft:block/iron_block",
     [([6, 0, 6], [10, 1, 10]), ([7, 1, 7], [9, 14, 9]), ([5, 14, 5], [11, 16, 11])], "4 Iron Rod, 10 Wire"),
    ("control_panel", "Control Panel", "lighting", 4, "create:block/brass_casing",
     [([1, 0, 4], [15, 8, 12]), ([1, 8, 6], [15, 12, 12], ("x", 22.5, [8, 8, 8]))], "2 Reinforced Iron Plate, 5 Cable"),
    # plants and office furniture
    ("office_desk", "Office Desk", "furniture", 0, "minecraft:block/stripped_spruce_log",
     [([0, 12, 2], [16, 14, 14]), ([1, 0, 3], [3, 12, 5]), ([13, 0, 3], [15, 12, 5]), ([1, 0, 11], [3, 12, 13]), ([13, 0, 11], [15, 12, 13])],
     "6 #minecraft:planks"),
    ("office_chair", "Office Chair", "furniture", 0, "minecraft:block/black_wool",
     [([7, 0, 7], [9, 7, 9]), ([4, 7, 4], [12, 9, 12]), ([4, 9, 11], [12, 18, 13])], "4 minecraft:black_wool, 2 Iron Rod"),
    ("filing_cabinet", "Filing Cabinet", "furniture", 0, IRON_T, [([3, 0, 3], [13, 16, 13])], "4 Iron Sheet"),
    ("office_plant", "Office Plant", "furniture", 0, "minecraft:block/terracotta",
     [([5, 0, 5], [11, 6, 11]), ([3, 6, 3], [13, 14, 13])], "1 #minecraft:saplings, 1 minecraft:flower_pot"),
]
# the leaves of the Office Plant are drawn green, not in the pot's colour
DECOR_TEXTURES = {"office_plant": {"top": "minecraft:block/azalea_leaves"}}
# player cosmetics held in the hand: (id, name, part cost, what has to be done first ("" for nothing))
CUPS = [("coffee_cup", "FICSIT Coffee Cup", "2 Iron Sheet", ""),
        ("employee_of_the_planet_cup", "Employee of the Planet Cup", "4 minecraft:gold_ingot", "phase_5")]

SHOP = [("customizer", "Customizer", "Customizer", 0, "customizer"),
        ("swatch", "Colour swatch slot", "Swatches", 2, "swatch"),
        ("finish_unpainted", "Unpainted finish", "Paint finishes", 3, "finish:unpainted"),
        ("finish_copper", "Copper finish", "Paint finishes", 5, "finish:copper"),
        ("finish_chrome", "Chrome finish", "Paint finishes", 5, "finish:chrome"),
        ("finish_carbon_steel", "Carbon Steel finish", "Paint finishes", 8, "finish:carbon_steel"),
        ("finish_caterium", "Caterium finish", "Paint finishes", 10, "finish:caterium")] \
    + [(f"skin_{k}", f"{n} skin", "Building skins", 4, f"skin:{k}") for k, (n, _) in SKINS.items()] \
    + [(f"storage_skin_{k}", f"{n} skin", "Storage skins", 1, f"skin:{k}") for k, (n, _) in STORAGE_SKINS.items()] \
    + [("decor_checkmark_statue", "SIFTEC Checkmark Statue", "Statues", 8, "decor:checkmark_statue"),
       ("decor_adequate_award", "Adequate Pioneering Award", "Statues", 25, "decor:adequate_award"),
       ("decor_pretty_good_award", "Pretty Good Pioneering Award", "Statues", 50, "decor:pretty_good_award"),
       ("decor_satisfactory_award", "Satisfactory Pioneering Award", "Statues", 150, "decor:satisfactory_award"),
       ("decor_silver_hog_statue", "Silver Hog Statue", "Statues", 50, "decor:silver_hog_statue"),
       ("decor_lizard_doggo_statue", "Lizard Doggo Statue", "Statues", 100, "decor:lizard_doggo_statue"),
       ("decor_confusing_creature_statue", "Confusing Creature Statue", "Statues", 200, "decor:confusing_creature_statue"),
       ("decor_golden_nut_statue", "Golden Nut Statue", "Statues", 1000, "decor:golden_nut_statue")] \
    + [(f"decor_{d[0]}", d[1], "Mob statues", 15, f"decor:{d[0]}") for d in DECOR if d[2] == "mob statues"] \
    + [("decor_ceiling_light", "Ceiling Light", "Lighting", 1, "decor:ceiling_light"),
       ("decor_flood_light", "Flood Light", "Lighting", 3, "decor:flood_light"),
       ("decor_street_light", "Street Light", "Lighting", 3, "decor:street_light"),
       ("decor_control_panel", "Control Panel", "Lighting", 5, "decor:control_panel"),
       ("decor_office_desk", "Office Desk", "Furniture", 4, "decor:office_desk"),
       ("decor_office_chair", "Office Chair", "Furniture", 3, "decor:office_chair"),
       ("decor_filing_cabinet", "Filing Cabinet", "Furniture", 3, "decor:filing_cabinet"),
       ("decor_office_plant", "Office Plant", "Furniture", 3, "decor:office_plant"),
       ("cup_coffee_cup", "FICSIT Coffee Cup", "Player cosmetics", 1, "cup:coffee_cup"),
       ("cup_employee_of_the_planet_cup", "Employee of the Planet Cup", "Player cosmetics", 1, "cup:employee_of_the_planet_cup")]

# Furnace burn times, in ticks (coal is 1600), after Satisfactory's energy values. A Blaze Burner burns anything a
# furnace burns, so these heat boilers too.
FUELS = {"biomass": 960, "solid_biofuel": 2400, "compacted_coal": 3360, "petroleum_coke": 960}

# ---------------------------------------------------------------------------------------------------
# Everything else the other mods add. Locked by default: an item from Create, Diesel Generators, Gunsmithing or
# Create: New Age is unlocked by a milestone above, by one here (MORE_LOCKS), left free on purpose
# (FREE) or switched off (DISABLED).
# gen_assets.py stops with a list if any item is in none of these.
COLOURS = "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()
WOODS = "oak spruce birch jungle acacia dark_oak mangrove cherry bamboo crimson warped".split()
STONES = "andesite asurine calcite crimsite deepslate diorite dripstone granite limestone ochrum scorchia scoria tuff veridium".split()


def _palette(stone):
    """Create's cut, polished, brick and pillar blocks for one stone."""
    out = [f"cut_{stone}", f"polished_cut_{stone}", f"small_{stone}_bricks", f"cut_{stone}_bricks", f"layered_{stone}", f"{stone}_pillar"]
    for base in (f"cut_{stone}", f"polished_cut_{stone}", f"small_{stone}_brick", f"cut_{stone}_brick"):
        out += [base + "_slab", base + "_stairs", base + "_wall"]
    return [C + i for i in out]


def _roofs():
    out = []
    for wax in ("", "waxed_"):
        for age in ("", "exposed_", "weathered_", "oxidized_"):
            for kind in ("shingle", "tile"):
                out += [f"{C}{wax}{age}copper_{kind}s", f"{C}{wax}{age}copper_{kind}_slab", f"{C}{wax}{age}copper_{kind}_stairs"]
    return out


DG, G = "createdieselgenerators:", "cgs:"
NA = "create_new_age:"
MORE_LOCKS = {
    # factory building blocks
    "base_building": [C + "copper_casing", C + "andesite_bars", C + "copper_bars", C + "andesite_door", C + "copper_door",
                      C + "framed_glass", C + "framed_glass_pane", C + "framed_glass_door", C + "framed_glass_trapdoor",
                      C + "horizontal_framed_glass", C + "horizontal_framed_glass_pane", C + "vertical_framed_glass", C + "vertical_framed_glass_pane",
                      C + "tiled_glass", C + "tiled_glass_pane", C + "ornate_iron_window", C + "ornate_iron_window_pane",
                      C + "industrial_iron_block", C + "industrial_iron_window", C + "industrial_iron_window_pane",
                      C + "weathered_iron_block", C + "weathered_iron_window", C + "weathered_iron_window_pane",
                      DG + "andesite_girder", DG + "chip_wood_block", DG + "chip_wood_slab", DG + "chip_wood_stairs", DG + "chip_wood_beam"]
                     + [f"{C}{w}_window{p}" for w in WOODS for p in ("", "_pane")] + [f"{C}{c}_seat" for c in COLOURS]
                     + _roofs() + [i for s in STONES for i in _palette(s)],
    # Create's gadgets and trinkets the AWESOME Shop used to sell for points: the Shop now sells cosmetics only, so they
    # are made by Create's own recipes again, from the milestone that brings the Sink and Shop
    "resource_sink_bonus_program": [C + "potato_cannon", C + "extendo_grip", C + "wand_of_symmetry", C + "linked_controller", C + "cuckoo_clock",
                                    C + "steam_whistle", C + "peculiar_bell", C + "clipboard", C + "placard", C + "turntable", C + "crafting_blueprint",
                                    C + "copper_valve_handle", C + "andesite_table_cloth",
                                    C + "desk_bell", C + "haunted_bell", C + "mysterious_cuckoo_clock", C + "metal_bracket", C + "wooden_bracket",
                                    C + "copper_table_cloth"] + [f"{C}{c}_table_cloth" for c in COLOURS] + [f"{C}{c}_valve_handle" for c in COLOURS],
    "basic_steel_production": [G + "steel_nugget", G + "steel_block", G + "steel_sheet", G + "nail_steel"],
    # melee weapons go with the Xeno-Basher
    "enhanced_asset_security": [G + "hammer_stone", G + "hammer_iron", G + "hammer_diamond", G + "hammer_netherite", G + "axe_stone", G + "axe_iron",
                                G + "axe_diamond", G + "axe_netherite", G + "spear"],
    "expanded_power_infrastructure": [C + "piston_extension_pole", C + "secondary_linear_chassis"],
    "contraption_transit": [C + "elevator_contact"],
    "oil_processing": [DG + "asphalt_block", DG + "asphalt_slab", DG + "asphalt_stairs", DG + "oil_scanner", DG + "mold", DG + "sheet_metal_panel",
                       DG + "hammer", DG + "wire_cutters"],
    "fluid_packaging": [DG + "oil_barrel"],
    "petroleum_power": [DG + "burner", DG + "engine_piston", DG + "engine_silencer", DG + "engine_turbocharger", DG + "lighter"],
    # Mechanical Crafter recipes
    "industrial_manufacturing": [C + "crafter_slot_cover", G + "nailgun_splitter"],
    "monorail_train_technology": [C + "train_door", C + "train_trapdoor", DG + "track_layers_bag"] + [f"{C}{c}_postbox" for c in COLOURS],
    "aeronautical_engineering": [C + "cardboard", C + "cardboard_block", C + "bound_cardboard_block", C + "cardboard_helmet", C + "cardboard_chestplate",
                                 C + "cardboard_leggings", C + "cardboard_boots", C + "cardboard_sword", C + "package_filter"],
    # MAM research
    mam("Alien Megafauna", "The Rebar Gun"): [G + "nail"],
    mam("Caterium", "Brass"): [C + "brass_nugget", C + "brass_block", C + "brass_casing", C + "brass_bars",
                       C + "brass_door", C + "brass_ladder", C + "brass_scaffolding", C + "brass_table_cloth", C + "brass_encased_shaft",
                       C + "brass_encased_cogwheel", C + "brass_encased_large_cogwheel"],
    mam("Caterium", "Smart Splitter"): [DG + "entity_filter"],
    mam("Nutrients", "Bulk Fermentation"): [DG + "basin_lid"],
    mam("Quartz", "Quartz Crystals"): [C + "polished_rose_quartz", C + "rose_quartz_block", C + "rose_quartz_lamp", C + "rose_quartz_tiles", C + "small_rose_quartz_tiles"],
    # guns: the flintlock with black powder, then the Rifle and Heavy Weapons nodes with their parts and rounds
    mam("Sulfur", "Flintlock"): [G + "flintlock_chambers", G + "flintlock_long_barrel", G + "blunderbuss_barrel",
                     G + "blunderbuss_barrel_long", G + "paper_cartridge", G + "paper_shot", G + "lead_balls"],
    mam("Sulfur", "The Rifle"): [G + "stock", G + "scope", G + "bayonet", G + "big_bayonet", G + "hammer_chamber", G + "long_barrel", G + "auto_fire", G + "round_belt", G + "shotgun_drum", G + "shotgun_long_barrel", G + "shotgun_pump",
                     G + "shotgun_spread_barrel", G + "press_form_revolver", G + "press_form_shotgun", G + "round_revolver", G + "round_revolver_blank",
                     G + "round_revolver_piercing", G + "round_shotgun", G + "round_shotgun_blank", G + "round_shotgun_flechette",
                     G + "round_shotgun_flechette_steel", G + "round_shotgun_incendiary"],
    mam("Sulfur", "Heavy Weapons"): [G + "gatling_drum", G + "press_form_gatling", G + "round_gatling", G + "round_gatling_blank", G + "round_gatling_piercing",
                     G + "rocket", G + "rocket_small", G + "launcher_auto", G + "ballistazooka", G + "blazegun", G + "steam_engine", G + "tank_empty",
                     G + "tank_lava", G + "tank_water", DG + "chemical_sprayer", DG + "chemical_sprayer_lighter", DG + "chemical_turret"],
}
# Create: New Age (the grid's wires and connectors, the motors players can put on it, its generators, the reactor).
# Its own nuclear fuel and the thorium it is made from are switched off: reactors burn Satisfactory's fuel rods.
for _mid, _items in {
    "hub_upgrade_2": ["copper_wire", "incomplete_wire", "copper_wire_block", "electrical_connector", "basic_motor", "basic_motor_extension"],
    "base_building": ["lamp_post", "street_light"],
    "coal_power": ["generator_coil", "carbon_brushes", "redstone_magnet", "layered_magnet", "fluxuated_magnetite", "netherite_magnet", "magnetite_block",
                   "heat_pipe", "heat_casing", "heater", "heat_pump", "stirling_engine", "basic_solar_heating_plate",
                   "advanced_solar_heating_plate"],
    "expanded_power_infrastructure": ["advanced_motor", "advanced_motor_extension", "basic_energiser", "advanced_energiser", "overcharged_iron",
                                      "overcharged_iron_sheet", "overcharged_iron_wire", "overcharged_iron_wire_block", "overcharged_gold",
                                      "overcharged_golden_sheet", "overcharged_golden_wire", "overcharged_golden_wire_block", "copper_circuit", "blank_circuit"],
    "industrial_manufacturing": ["reinforced_motor", "reinforced_energiser", "overcharged_diamond", "overcharged_diamond_wire", "overcharged_diamond_wire_block"],
    "nuclear_power": ["reactor_casing", "incomplete_reactor_casing", "reactor_glass", "reactor_rod", "reactor_fuel_acceptor", "reactor_heat_vent"],
}.items():
    MORE_LOCKS.setdefault(_mid, []).extend(NA + i for i in _items)
DISABLED += [NA + i for i in ("nuclear_fuel", "incomplete_fuel", "thorium", "radioactive_thorium", "thorium_ore", "corium", "solid_corium",
                              "incomplete_enchanted_golden_apple")]

# Never locked: the starting kinetics, raw and crushed materials, crafting ingredients, food, fluids in buckets,
# creative-only and contraption items, and what machines make on their own (packages, half-made parts)
FREE = [C + i for i in (
    "shaft cogwheel large_cogwheel gearbox vertical_gearbox encased_chain_drive hand_crank andesite_alloy andesite_alloy_block andesite_casing "
    "andesite_encased_shaft andesite_encased_cogwheel andesite_encased_large_cogwheel iron_sheet golden_sheet sturdy_sheet brass_hand propeller whisk "
    "unprocessed_obsidian_sheet powdered_obsidian pulp transmitter raw_zinc raw_zinc_block zinc_ore deepslate_zinc_ore zinc_ingot zinc_nugget zinc_block limestone asurine crimsite ochrum scorchia "
    "scoria veridium bar_of_chocolate builders_tea chocolate_glazed_berries dough honeyed_apple sweet_roll wheat_flour cinder_flour chocolate_bucket "
    "honey_bucket experience_nugget experience_block incomplete_precision_mechanism incomplete_track creative_crate creative_fluid_tank creative_motor "
    "handheld_worldshaper minecart_contraption chest_minecart_contraption furnace_minecart_contraption refined_radiance_casing shadow_steel_casing "
    "shopping_list cardboard_package_10x8 cardboard_package_10x12 cardboard_package_12x10 cardboard_package_12x12").split()] \
    + [f"{C}crushed_raw_{m}" for m in "iron copper gold zinc lead aluminum nickel osmium platinum quicksilver silver tin uranium".split()] \
    + [f"{C}rare_{n}_package" for n in "creeper darcy evan jinx kryppers simi starlotte thunder up vector".split()] \
    + [G + i for i in "sulfur sulfur_ore lead_ore deepslate_lead_ore raw_lead raw_lead_block lead_ingot lead_nugget lead_block niter guano charcoal_dust "
       "shell_gatling shell_revolver shell_shotgun".split()] \
    + [DG + i for i in "kelp_handle wood_chip crude_oil_bucket diesel_bucket gasoline_bucket biodiesel_bucket ethanol_bucket plant_oil_bucket".split()] \
    + [f"{DG}{c}_cement_bucket" for c in COLOURS]

# Out of the pack (Overview, Removed): Traveler's Backpack and every backpack (inventories stay vanilla size), and
# Create: Hypertubes (Contraption Transit takes its milestone). Their items are no longer in the pack's mod list, so
# gen_assets.py no longer asks for each to be locked or free.


# ---------------------------------------------------------------------------------------------------
# Build Gun buildings (building/Buildings.java): id, name, Build Gun tab, Satisfactory 1.0 build cost, the milestone or
# MAM node that unlocks it ("" = from the start), and whether it is a dev building (only in creative). Each has a
# structure template, src/main/resources/data/siftec/structure/building/<id>.nbt: built in game and saved with a
# Structure Block, or written by tools/structures.py. Work packages 5 and 9 add the real buildings here.
# The last field says how the building works (building/BuildingLogic.java):
#   logic   "basin" (a Press or Mixer over a Basin, its recipe set by the controller's filter and paced to the recipe's
#           Satisfactory time), "smelter" (an Encased Fan blasting through lava onto a Depot, fed by the controller),
#           "miner" (a Drill over a node that really mines it: it breaks the node block once it has regrown, and what the
#           block drops is the output), "portable_miner" (the same with no power, dropping it in front), "storage" (Item Vaults)
#           "power" (work package 6: generators, Power Storage, poles and towers; what it does is its "power" below)
#   su      the SU the building draws (Satisfactory's MW at 1 MW = 204.8 SU; the Power tab); its New Age motors give exactly that
#   power   its part in the power grid (building/BuildingPower.java): "pole", "storage", "augmenter", or a generator kind,
#           "biomass", "coal", "fuel", "geothermal", "nuclear"; mw what a generator gives, water the m3 a minute of water it
#           takes at full load, lines how many wires its connector takes (1 if not given), vent the open cell a
#           Geothermal Generator stands over a geyser with
#   break   a miner's ticks to break the regrown node (the Mk's speed; the node's purity sets how fast it regrows)
#   fortune a miner's built-in Fortune (vanilla's ore formula, extended past III)
#   node    the cell (x, y, z in the Build Gun frame) that stands on the node: the template leaves it out
#   prefab  a structure the Build Gun puts down through prefab/Prefabs.java instead of a structure template ("hub": the
#           HUB, built in parts as its upgrades are done; "gateway": the Wormhole Gateway, its ring gate growing with each
#           phase delivered). Its cost comes back when it is dismantled
MW = 204.8
BUILD_TABS = ("production", "power", "logistics", "foundations", "special")
BUILDINGS = [
    # every player spawns with the Build Gun and builds the HUB with it first: Satisfactory's 20 Iron Ore (Raw Iron)
    ("hub", "The HUB", "special", BUILD_COSTS["The HUB"], "", False, {"prefab": "hub"}),
    # the Wormhole Gateway (Satisfactory's Space Elevator): a structure that grows a ring gate with each phase delivered
    ("wormhole_gateway", "Wormhole Gateway", "special", BUILD_COSTS["Space Elevator"], "hub_upgrade_6", False, {"prefab": "gateway"}),
    ("test_building", "Test Building", "production", "2 Iron Sheet, 4 Iron Rod", "", True, {}),
    ("test_miner", "Test Miner", "production", "2 Iron Sheet", "", True, {"node": (0, 0, 1)}),
    # work package 5
    ("portable_miner", "Portable Miner", "production", EQUIPMENT_COSTS["Portable Miner"], "hub_upgrade_1", False,
     {"logic": "portable_miner", "break": 40, "node": (0, 0, 1)}),
    ("miner_mk1", "Miner Mk.1", "production", BUILD_COSTS["Miner Mk.1"], "hub_upgrade_5", False,
     {"logic": "miner", "su": round(5 * MW), "break": 10, "node": (0, 0, 2)}),
    ("miner_mk2", "Miner Mk.2", "production", BUILD_COSTS["Miner Mk.2"], "advanced_steel_production", False,
     {"logic": "miner", "su": round(15 * MW), "break": 5, "fortune": 3, "node": (0, 0, 2)}),
    ("miner_mk3", "Miner Mk.3", "production", BUILD_COSTS["Miner Mk.3"], "leading_edge_production", False,
     {"logic": "miner", "su": round(45 * MW), "break": 2, "fortune": 5, "node": (0, 0, 2)}),
    ("smelter", "Smelter", "production", BUILD_COSTS["Smelter"], "hub_upgrade_2", False, {"logic": "smelter", "su": round(4 * MW)}),
    ("constructor", "Constructor", "production", BUILD_COSTS["Constructor"], "hub_upgrade_3", False, {"logic": "basin", "su": round(4 * MW)}),
    ("storage_container", "Storage Container", "logistics", BUILD_COSTS["Storage Container"], "hub_upgrade_5", False, {"logic": "storage"}),
    ("industrial_storage_container", "Industrial Storage Container", "logistics", BUILD_COSTS["Industrial Storage Container"], "logistics_mk3", False,
     {"logic": "storage"}),
    # work package 6: power. Satisfactory 1.0's MW, unlocks and wires per connector (Power Pole Mk.1 4, Mk.2 7, Mk.3 10)
    ("power_pole_mk1", "Power Pole Mk.1", "power", BUILD_COSTS["Power Pole Mk.1"], "hub_upgrade_3", False, {"logic": "power", "power": "pole", "lines": 4}),
    ("power_pole_mk2", "Power Pole Mk.2", "power", BUILD_COSTS["Power Pole Mk.2"], mam("Caterium", "Power Poles Mk.2"), False, {"logic": "power", "power": "pole", "lines": 7}),
    ("power_pole_mk3", "Power Pole Mk.3", "power", BUILD_COSTS["Power Pole Mk.3"], mam("Caterium", "Power Poles Mk.3"), False, {"logic": "power", "power": "pole", "lines": 10}),
    ("power_tower", "Power Tower", "power", BUILD_COSTS["Power Tower"], "expanded_power_infrastructure", False, {"logic": "power", "power": "pole", "lines": 10}),
    ("power_storage", "Power Storage", "power", BUILD_COSTS["Power Storage"], "expanded_power_infrastructure", False, {"logic": "power", "power": "storage"}),
    ("biomass_burner", "Biomass Burner", "power", BUILD_COSTS["Biomass Burner"], "hub_upgrade_6", False, {"logic": "power", "power": "biomass", "mw": 30}),
    ("coal_generator", "Coal Generator", "power", BUILD_COSTS["Coal-Powered Generator"], "coal_power", False,
     {"logic": "power", "power": "coal", "mw": 75, "water": 45}),
    ("fuel_generator", "Fuel Generator", "power", BUILD_COSTS["Fuel-Powered Generator"], "petroleum_power", False, {"logic": "power", "power": "fuel", "mw": 250}),
    ("geothermal_generator", "Geothermal Generator", "power", BUILD_COSTS["Geothermal Generator"], mam("Sulfur", "Geothermal Power"), False,
     {"logic": "power", "power": "geothermal", "mw": 200, "vent": (0, 0, 2)}),
    ("nuclear_power_plant", "Nuclear Power Plant", "power", BUILD_COSTS["Nuclear Power Plant"], "nuclear_power", False,
     {"logic": "power", "power": "nuclear", "mw": 2500, "water": 240}),
    ("alien_power_augmenter", "Alien Power Augmenter", "power", BUILD_COSTS["Alien Power Augmenter"], mam("Alien Technology", "Power Augmenter"), False,
     {"logic": "power", "power": "augmenter", "mw": 500}),
    # work package 9: the rest of Satisfactory 1.0's production buildings, at 1.0's MW and unlocks. The Assembler, Foundry,
    # Refinery and Blender are a Mixer over a Basin like the Constructor's Press (heated by a Blaze Burner the building keeps
    # lit while it has power, for the last three); the Blender's and the Manufacturer's Basin is a Brass Basin, which holds
    # four stacks of each item.
    ("assembler", "Assembler", "production", BUILD_COSTS["Assembler"], "part_assembly", False, {"logic": "basin", "su": round(15 * MW)}),
    ("foundry", "Foundry", "production", BUILD_COSTS["Foundry"], "basic_steel_production", False, {"logic": "basin", "su": round(16 * MW)}),
    ("refinery", "Refinery", "production", BUILD_COSTS["Refinery"], "oil_processing", False, {"logic": "basin", "su": round(30 * MW)}),
    ("blender", "Blender", "production", BUILD_COSTS["Blender"], "control_system_development", False, {"logic": "basin", "su": round(75 * MW)}),
    # assembly: the Mixer over the Brass Basin makes the recipe's Core, then the Core goes along a row of Depots, a Deployer
    # over each adding the last one of one input (the Quantum Encoder's Spout first fills it with Excited Photonic Matter,
    # and its Item Drain takes the Dark Matter Residue off what comes out)
    ("manufacturer", "Manufacturer", "production", BUILD_COSTS["Manufacturer"], "industrial_manufacturing", False, {"logic": "assembly", "su": round(55 * MW)}),
    ("quantum_encoder", "Quantum Encoder", "production", BUILD_COSTS["Quantum Encoder"], "quantum_encoding", False,
     {"logic": "assembly", "su": round(1000 * MW)}),
    # packager: a Spout over a Depot fills empty canisters and tanks, an Item Drain empties packaged fluids
    ("packager", "Packager", "production", BUILD_COSTS["Packager"], "fluid_packaging", False, {"logic": "packager", "su": round(10 * MW)}),
    # extractors: fluid into a tank at 1.0's rate (rate: mB a minute on a normal node, purity scaling it); the Water
    # Extractor stands in water over its intake cell, the Oil Extractor over an oil node, the Resource Well Pressurizer
    # over a nitrogen well, and Resource Well Extractors near a running Pressurizer take the well's nitrogen
    ("water_extractor", "Water Extractor", "production", BUILD_COSTS["Water Extractor"], "coal_power", False,
     {"logic": "extractor", "su": round(20 * MW), "fluid": "minecraft:water", "rate": 120 * MB_PER_M3, "intake": (0, 0, 3)}),
    ("oil_extractor", "Oil Extractor", "production", BUILD_COSTS["Oil Extractor"], "oil_processing", False,
     {"logic": "extractor", "su": round(40 * MW), "fluid": "createdieselgenerators:crude_oil", "rate": 120 * MB_PER_M3, "node": (0, 0, 2), "fluid_node": "oil"}),
    ("resource_well_pressurizer", "Resource Well Pressurizer", "production", BUILD_COSTS["Resource Well Pressurizer"], "advanced_aluminum_production", False,
     {"logic": "pressurizer", "su": round(150 * MW), "node": (0, 0, 2), "fluid_node": "nitrogen"}),
    ("resource_well_extractor", "Resource Well Extractor", "production", BUILD_COSTS["Resource Well Extractor"], "advanced_aluminum_production", False,
     {"logic": "well_extractor", "fluid": "siftec:nitrogen", "rate": 60 * MB_PER_M3}),
    # the pack's own late-game machines (PROCESSORS), run by the building from the recipe its filter picks; SU at the
    # middle of 1.0's range (Particle Accelerator 250 to 750 MW for most recipes, Converter 100 to 400)
    ("particle_accelerator", "Particle Accelerator", "production", BUILD_COSTS["Particle Accelerator"], "particle_enrichment", False,
     {"logic": "processor", "su": round(500 * MW)}),
    ("converter", "Converter", "production", BUILD_COSTS["Converter"], "matter_conversion", False, {"logic": "processor", "su": round(250 * MW)}),
    # work package 10 (Exploration and Equipment, AWESOME Sink and Shop): the Equipment Workshop (builds equipment by hand
    # from the parts carried), the AWESOME Sink (destroys what its input port takes, for points; 30 MW as in 1.0), the
    # AWESOME Shop (prints nothing itself: coupons buy cosmetics) and the Radar Tower (30 MW: reveals crash sites to the
    # company on Xaero's map). Using any of them opens its window.
    ("equipment_workshop", "Equipment Workshop", "special", BUILD_COSTS["Equipment Workshop"], "hub_upgrade_1", False, {"logic": "workshop"}),
    ("awesome_sink", "AWESOME Sink", "special", BUILD_COSTS["AWESOME Sink"], "resource_sink_bonus_program", False, {"logic": "sink", "su": round(30 * MW)}),
    ("awesome_shop", "AWESOME Shop", "special", BUILD_COSTS["AWESOME Shop"], "resource_sink_bonus_program", False, {"logic": "shop"}),
    ("radar_tower", "Radar Tower", "special", BUILD_COSTS["Radar Tower"], mam("Quartz", "Radar Technology"), False, {"logic": "radar", "su": round(30 * MW)}),
    # work package 14: vehicles (vehicle/Vehicles.java). The Tractor, Truck and Explorer are single entities the Build Gun
    # puts down (prefab "vehicle", no template), drawn from Create's own models; they burn the Fuel Generator's fuels. The
    # Truck Station is a building: an Item Vault vehicles load from or unload into (using its terminal switches which),
    # and a fuel tank they fill up from, while they stand at its dock in front of it on a recorded route.
    ("truck_station", "Truck Station", "logistics", BUILD_COSTS["Truck Station"], "vehicular_transport", False, {"logic": "truck_station"}),
    ("tractor", "Tractor", "logistics", BUILD_COSTS["Tractor"], "vehicular_transport", False, {"prefab": "vehicle"}),
    ("truck", "Truck", "logistics", BUILD_COSTS["Truck"], "logistics_mk4", False, {"prefab": "vehicle"}),
    ("explorer", "Explorer", "logistics", BUILD_COSTS["Explorer"], mam("Quartz", "Display Systems"), False, {"prefab": "vehicle"}),
]
# The buildings whose Basin is a Brass Basin (four stacks of each item), for the recipe checkers
BRASS_BASIN_BUILDINGS = ("manufacturer", "blender")
# Buildings that stand over a resource node and can only go down once its clump is mined (the miners, extractors)
NODE_BUILDINGS = {b[0] for b in BUILDINGS if "node" in b[6]}
# What the generators burn, at Satisfactory 1.0's energy in MJ (a fluid's per m3): the Biomass Burners (the HUB's two and
# the building), the Coal Generator, the Fuel Generator, the Nuclear Power Plant (and a New Age reactor a player builds:
# each rod leaves Satisfactory's waste), and the Alien Power Augmenter's Alien Power Matrix (it only keeps it fed).
GENERATOR_FUEL = {
    "biomass": {"#minecraft:leaves": 15, "#minecraft:logs": 100, "#siftec:mycelia": 20, "siftec:biomass": 180, "siftec:solid_biofuel": 450},
    "coal": {"minecraft:coal": 300, "siftec:compacted_coal": 630, "siftec:petroleum_coke": 180},
    "fuel": {"Fuel": 750, "Liquid Biofuel": 750, "Turbofuel": 2000, "Rocket Fuel": 3600, "Ionized Fuel": 5000},
    "nuclear": {"siftec:uranium_fuel_rod": (750000, "siftec:uranium_waste", 10), "siftec:plutonium_fuel_rod": (1500000, "siftec:plutonium_waste", 1)},
    "augmenter": {"siftec:alien_power_matrix": 1},
}


# ---------------------------------------------------------------------------------------------------
# Which milestone or MAM node unlocks each part: the 1.0 unlock of the earliest standard recipe that makes it (a HUB
# milestone before a MAM node). A part only an alternate makes, or one made from the start, is not locked by itself.
# The parts' old places in the lists above are replaced by these; anything else in those lists stays.
def _part_locks():
    table = names()
    parts = {"siftec:" + p[0] for p in PARTS}
    # MAM research opens at Field Research (Tier 1), so a MAM node counts as coming before Tier 2
    order = {m[1]: (m[0] if m[0] < 2 else m[0] + 1) * 1000 + i for i, m in enumerate(MILESTONES)}
    order.update({n[0]: 2000 for n in mam_nodes()})
    found = {}
    for key, name, building, alt, secs, ins, outs, unlock in sat_recipes():
        uid = unlock_id(unlock)
        if uid in (None, "drive") or name.startswith("Unpackage "):
            continue  # unpacking gives the empty canister or tank back; it does not unlock them
        made = [table.get(n, n) for k, n, a in outs if k == "item"]
        if key in CORES:
            made += ["siftec:" + p for p in CORES[key] if p]
        for pid in made:
            if pid in parts and (pid not in found or order[uid] < order[found[pid]]):
                found[pid] = uid
    return found


PART_LOCKS = _part_locks()
_GONE = {"siftec:dna_capsule"}
MILESTONES = [(t, mid, name, cost, time, text, [i for i in items if i not in PART_LOCKS and i not in _GONE]
               + sorted(p for p, m in PART_LOCKS.items() if m == mid), tokens) for t, mid, name, cost, time, text, items, tokens in MILESTONES]
for _tree in MAM:
    _tree[3][:] = [(n, cost, text, needs, [i for i in items if i not in PART_LOCKS and i not in _GONE]
                    + sorted(p for p, m in PART_LOCKS.items() if m == mam_id(_tree[0], n)), tokens)
                   for n, cost, text, needs, items, tokens in _tree[3]]
_nowhere = set(PART_LOCKS.values()) - {m[1] for m in MILESTONES} - {n[0] for n in mam_nodes()}
if _nowhere:
    raise SystemExit(f"part locks name no milestone or MAM node: {sorted(_nowhere)}")


def _add_lock(uid, iid):
    """Adds an item to what a milestone or MAM node unlocks."""
    for _m, _ms in enumerate(MILESTONES):
        if _ms[1] == uid:
            MILESTONES[_m] = _ms[:6] + (_ms[6] + [iid],) + _ms[7:]
            return
    for _t in MAM:
        for _k, _n in enumerate(_t[3]):
            if mam_id(_t[0], _n[0]) == uid:
                _t[3][_k] = _n[:4] + (_n[4] + [iid],) + _n[5:]
                return
    raise SystemExit(f"no milestone or MAM node {uid}")


# Equipment the Equipment Workshop builds by hand that machines can make too, the Manufacturer's way (recipes.py):
# item id -> its 1.0 name. Each gets a Core and an Incomplete item, locked with the equipment.
EQUIPMENT_BY_MACHINE = {"siftec:object_scanner": "Object Scanner", "siftec:jetpack": "Jetpack", "siftec:parachute": "Parachute",
                        "siftec:gas_mask": "Gas Mask", "siftec:hazmat_suit": "Hazmat Suit", "siftec:hover_pack": "Hoverpack",
                        "siftec:blade_runners": "Blade Runners", "siftec:xeno_zapper": "Xeno-Zapper"}
EQUIPMENT_CORES, EQUIPMENT_LOCKS = {}, {}
_unlocks_of = {i: m[1] for m in MILESTONES for i in m[6]}
_unlocks_of.update({i: mam_id(t[0], n[0]) for t in MAM for n in t[3] for i in n[4]})
for _iid, _sat in EQUIPMENT_BY_MACHINE.items():
    _rid = _iid.split(":")[1]
    EQUIPMENT_CORES[_iid] = _rid + "_core"
    PARTS.append((_rid + "_core", _sat + " Core", "minecraft:item/magma_cream", 0x909098))
    PARTS.append(("incomplete_" + _rid, "Incomplete " + _sat, "minecraft:item/magma_cream", 0x606068))
    EQUIPMENT_LOCKS[_iid] = _unlocks_of.get(_iid)
    if _unlocks_of.get(_iid):
        for _part in (_rid + "_core", "incomplete_" + _rid):
            _add_lock(_unlocks_of[_iid], "siftec:" + _part)

# Vanilla items the MAM's research lets machines make (MAM Research tab), each locked to its node: (result, how many,
# machine, [(part, count)], tree, node). The crafting table keeps only vanilla's own recipes, so these are the pack's
# way to them with no End trip, no ancient debris and no blaze rods. Manufacturer recipes build the Manufacturer's way
# (a Core in the Mixer, then one loop of sequenced assembly); Assembler recipes are a Mixer on a Basin.
VANILLA_RECIPES = [
    (M + "totem_of_undying", 1, "Manufacturer", [("Alien DNA Capsule", 10), (M + "gold_block", 4), (M + "emerald", 8), ("AI Limiter", 5)],
     "Alien Megafauna", "Undying Biology"),
    (M + "ender_eye", 1, "Assembler", [(M + "ender_pearl", 1), ("Quartz Crystal", 2)], "Alien Megafauna", "Ender Analysis"),
    (M + "elytra", 1, "Manufacturer", [("Fabric", 25), ("Alclad Aluminum Sheet", 10), ("Motor", 4), (M + "phantom_membrane", 8)],
     "Mycelia", "Wingsuit Research"),
    ("siftec:elytra_backpack", 1, "Manufacturer", [(M + "elytra", 1), (C + "copper_backtank", 1), ("Precision Mechanism", 25), ("High-Speed Connector", 10)],
     "Mycelia", "Elytra Backpack"),
    (M + "trident", 1, "Manufacturer", [("Steel Pipe", 10), ("Crystal Oscillator", 5), ("Quickwire", 50), (M + "prismarine_shard", 16)],
     "Quartz", "Tidal Research"),
    (M + "heart_of_the_sea", 1, "Manufacturer", [(M + "nautilus_shell", 8), (M + "prismarine_crystals", 16), ("Crystal Oscillator", 10), ("Quickwire", 50)],
     "Quartz", "Heart of the Sea"),
    (M + "shulker_shell", 1, "Assembler", [("SAM Fluctuator", 1), ("Iron Sheet", 5)], "Alien Technology", "Pocket Storage"),
    (M + "netherite_ingot", 1, "Manufacturer", [(M + "gold_ingot", 4), ("Diamond", 2), ("Steel Ingot", 20), (M + "netherrack", 16)],
     "Alien Technology", "Netherite Synthesis"),
]
VANILLA_CORES = {}   # result id -> (core part id, incomplete part id), for the Manufacturer ones
for _out, _n, _machine, _ins, _tree_name, _node in VANILLA_RECIPES:
    if _machine != "Manufacturer":
        continue
    _rid = _out.split(":")[1]
    _label = _rid.replace("_", " ").title()
    VANILLA_CORES[_out] = (_rid + "_core", "incomplete_" + _rid)
    PARTS.append((_rid + "_core", _label + " Core", "minecraft:item/magma_cream", 0x909098))
    PARTS.append(("incomplete_" + _rid, "Incomplete " + _label, "minecraft:item/magma_cream", 0x606068))
    for _part in VANILLA_CORES[_out]:
        _add_lock(mam(_tree_name, _node), "siftec:" + _part)

