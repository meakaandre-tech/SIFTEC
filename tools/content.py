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
# gone), the Portable Miner (a Build Gun building, not an item) and the Xeno-Zapper (the Xeno-Basher is Gunsmithing's).
SAT_LEFT_OUT = {"Nobelisk", "Gas Nobelisk", "Pulse Nobelisk", "Cluster Nobelisk", "Nuke Nobelisk", "Rifle Ammo", "Homing Rifle Ammo",
                "Turbo Rifle Ammo", "Explosive Rebar", "Blue Power Slug", "Yellow Power Slug", "Purple Power Slug", "Portable Miner",
                "Xeno-Zapper"}
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
    # collectibles. The power slugs and Power Shards go with overclocking (not this package's work): they are found, but
    # nothing makes or takes them any more
    ("blue_power_slug", "Blue Power Slug", "minecraft:item/slime_ball", 0x50A0FF), ("yellow_power_slug", "Yellow Power Slug", "minecraft:item/slime_ball", 0xFFE050),
    ("purple_power_slug", "Purple Power Slug", "minecraft:item/slime_ball", 0xC060FF), ("power_shard", "Power Shard", "minecraft:item/amethyst_shard", 0x60E0FF),
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
    ("dark_matter_residue", "Dark Matter Residue", 0x301848), ("ignimbrite", "Ignimbrite", 0xF08020), ("turbofuel", "Turbofuel", 0xD03030), ("vinegar", "Vinegar", 0xE8D8A0), ("mead", "Mead", 0xE0A030),
    ("rocket_fuel", "Rocket Fuel", 0xE06020), ("ionized_fuel", "Ionized Fuel", 0xF0F070), ("dissolved_silica", "Dissolved Silica", 0xE0E0F0),
    ("excited_photonic_matter", "Excited Photonic Matter", 0xFFFFE0),
    # what the old miners drilled with: Mk.1 took water, Mk.2 Drilling Mud, Mk.3 Coolant (the Build Gun miners decide in WP 5)
    ("drilling_mud", "Drilling Mud", 0x8A6A4A), ("coolant", "Coolant", 0x40C8D8),
]

# ---------------------------------------------------------------------------------------------------
# Milestones. (tier, id, name, cost, minutes:seconds, unlock text, locked item ids, tokens)
# "items" are the things nobody in the company can craft or place until the milestone is done.
# "tokens" are switches other systems read: scanner:<node>, cap:<rpm>, and the Traveler's Backpack: "backpack" gives each member
# of the company one (Leather tier), "backpack:<tier>" raises it to that tier (iron, gold, diamond, netherite).
C = "create:"
SAILS = [C + "white_sail"]   # the dyed sails are blocks only; they have no item to lock
TOOLBOXES = [C + c + "_toolbox" for c in "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()]
MILESTONES = [
    (0, "hub_upgrade_1", "HUB Upgrade 1", "10 Iron Rod", "0:00",
     "Portable Miner, Wrench and Goggles, a Traveler's Backpack for every member (Leather: 27 slots)",
     ["siftec:portable_miner", C + "wrench", C + "goggles", C + "mechanical_drill"], ["backpack"]),
    (0, "hub_upgrade_2", "HUB Upgrade 2", "20 Iron Rod, 10 Iron Sheet", "0:00",
     "Smelter (Encased Fan), Wire and Cable, the first HUB Engine, copper nodes on the scanner",
     [C + "encased_fan", C + "nozzle", "siftec:wire", "siftec:cable", "siftec:power_line", "siftec:hub_engine"], ["scanner:copper"]),
    (0, "hub_upgrade_3", "HUB Upgrade 3", "20 Iron Sheet, 20 Iron Rod, 20 Wire", "0:00",
     "Constructor (Press, Saw, Millstone, Basin), Screw, Reinforced Iron Plate, stone nodes on the scanner",
     [C + "mechanical_press", C + "mechanical_saw", C + "depot", C + "millstone", C + "basin", "siftec:screw", "siftec:reinforced_iron_plate", "siftec:power_pole"],
     ["scanner:stone"]),
    (0, "hub_upgrade_4", "HUB Upgrade 4", "75 Iron Sheet, 20 Cable, 10 Stone", "0:00",
     "Mechanical Belt and funnels, Backpack Crafting Upgrade", [C + "belt_connector", C + "andesite_funnel"], []),
    (0, "hub_upgrade_5", "HUB Upgrade 5", "75 Iron Rod, 50 Cable, 20 Stone", "0:00",
     "Miner Mk.1, Item Vault, a second HUB Engine, Backpack Pickup and Furnace Upgrades", [C + "item_vault"], []),
    (0, "hub_upgrade_6", "HUB Upgrade 6", "100 Iron Rod, 100 Iron Sheet, 100 Wire, 50 Stone", "0:00",
     "Wormhole Gateway, Furnace Engine, flywheel, Biomass. Milestones lock the HUB from here on",
     ["siftec:wormhole_gateway", "siftec:furnace_engine", C + "flywheel", "siftec:biomass"], []),

    (1, "base_building", "Base Building", "200 Stone, 100 Iron Sheet, 100 Iron Rod", "2:00",
     "Factory building blocks: girders, scaffolding, ladders, casings",
     [C + "metal_girder", C + "andesite_scaffolding", C + "andesite_ladder", C + "copper_ladder", C + "copper_scaffolding", C + "copycat_panel", C + "copycat_step"], []),
    (1, "logistics", "Logistics", "150 Iron Sheet, 150 Iron Rod, 300 Wire", "4:00",
     "Splitter and merger (Andesite Tunnel), Chute, Speedometer and Stressometer",
     [C + "andesite_tunnel", C + "chute", C + "item_hatch", C + "speedometer", C + "stressometer"], []),
    (1, "field_research", "Field Research", "300 Wire, 300 Screw, 100 Iron Sheet", "3:00",
     "MAM, Toolbox, Object Scanner, backpack to Iron tier (45 slots)", ["siftec:mam", "siftec:object_scanner"] + TOOLBOXES, ["backpack:iron"]),

    (2, "part_assembly", "Part Assembly", "200 Cable, 200 Iron Rod, 500 Screw, 300 Iron Sheet", "6:00",
     "Assembler (Deployer), Copper Sheet, Rotor, Modular Frame, Smart Plating",
     [C + "deployer", C + "copper_sheet", "siftec:rotor", "siftec:modular_frame", "siftec:smart_plating"], []),
    (2, "obstacle_clearing", "Obstacle Clearing", "500 Screw, 100 Cable, 100 Stone", "3:00",
     "Solid Biofuel, harvesters and ploughs, Backpack Feeding and Smoker Upgrades",
     ["siftec:solid_biofuel", C + "mechanical_harvester", C + "mechanical_plough", C + "tree_fertilizer"], []),
    (2, "jump_pads", "Jump Pads", "50 Rotor, 300 Iron Sheet, 150 Cable", "4:00",
     "Jump Pad (Weighted Ejector), landing pad that cancels fall damage", [C + "weighted_ejector", "siftec:landing_pad"], []),
    (2, "resource_sink_bonus_program", "Resource Sink Bonus Program", "400 Stone, 500 Wire, 200 Iron Rod, 200 Iron Sheet", "5:00",
     "AWESOME Sink and AWESOME Shop", ["siftec:awesome_sink", "siftec:awesome_shop"], []),
    (2, "logistics_mk2", "Logistics Mk.2", "50 Reinforced Iron Plate, 200 Stone, 300 Iron Rod, 300 Iron Sheet", "6:00",
     "Company speed limit raised from 32 to 64 RPM", [], ["cap:64"]),

    (3, "coal_power", "Coal Power", "150 Reinforced Iron Plate, 50 Rotor, 500 Cable", "8:00",
     "Steam Engine, Blaze Burner, pipes, pumps and tanks, Backpack Tanks Upgrade and hose, coal nodes on the scanner",
     [C + "steam_engine", C + "blaze_burner", C + "empty_blaze_burner", C + "fluid_pipe", C + "mechanical_pump", C + "fluid_tank", C + "hose_pulley"],
     ["scanner:coal"]),
    (3, "vehicular_transport", "Vehicular Transport", "25 Modular Frame, 100 Rotor, 200 Cable, 400 Iron Rod", "4:00",
     "Cart Assembler, Rope Pulley, backpack to Gold tier (63 slots)",
     [C + "cart_assembler", C + "rope_pulley", C + "minecart_coupling"], ["backpack:gold"]),
    (3, "basic_steel_production", "Basic Steel Production", "50 Modular Frame, 150 Rotor, 500 Stone, 1000 Wire", "8:00",
     "Foundry (Mechanical Mixer), Steel Ingot, Steel Beam, Steel Pipe, Versatile Framework, Backpack Blast Furnace Upgrade",
     [C + "mechanical_mixer", "cgs:steel_ingot", "siftec:steel_casing", "siftec:steel_beam", "siftec:steel_pipe", "siftec:versatile_framework"], []),
    (3, "enhanced_asset_security", "Enhanced Asset Security", "100 Reinforced Iron Plate, 600 Iron Rod, 1500 Wire", "3:00",
     "Xeno-Basher (Pneumatic Hammer), Backpack Magnet Upgrade", ["cgs:hammer"], []),

    (4, "ficsit_blueprints", "FICSIT Blueprints", "100 Modular Frame, 200 Steel Beam, 500 Cable, 1000 Stone", "5:00",
     "Blueprint Designer Mk.1", ["siftec:blueprint_designer"], []),
    (4, "logistics_mk3", "Logistics Mk.3", "200 Steel Beam, 200 Steel Pipe, 400 Reinforced Iron Plate", "5:00",
     "Company speed limit raised to 96 RPM", [], ["cap:96"]),
    (4, "advanced_steel_production", "Advanced Steel Production", "100 Steel Pipe, 200 Rotor, 100 Modular Frame, 500 Stone", "10:00",
     "Miner Mk.2, Crushing Wheels, Encased Industrial Beam, Stator, Motor, Automated Wiring",
     [C + "crushing_wheel", "siftec:encased_industrial_beam", "siftec:stator", "siftec:motor", "siftec:automated_wiring"], []),
    (4, "expanded_power_infrastructure", "Expanded Power Infrastructure", "100 Steel Beam, 50 Encased Industrial Beam, 200 Modular Frame, 2000 Wire", "5:00",
     "Power Tower, Power Storage, windmills, pistons, bearings and other contraption parts",
     ["siftec:power_tower", "siftec:power_storage", C + "windmill_bearing", C + "sail_frame", C + "mechanical_piston", C + "sticky_mechanical_piston",
      C + "mechanical_bearing", C + "clockwork_bearing", C + "gantry_carriage", C + "gantry_shaft", C + "elevator_pulley", C + "linear_chassis",
      C + "radial_chassis", C + "super_glue", C + "sticker", C + "contraption_controls"] + SAILS, []),
    (4, "hypertubes", "Hypertubes", "500 Copper Sheet, 300 Steel Pipe, 50 Encased Industrial Beam", "10:00",
     "Hypertube Entrance and Hypertubes",
     ["create_hypertube:hypertube", "create_hypertube:hypertube_entrance", "create_hypertube:hypertube_accelerator"], []),

    (5, "jetpack", "Jetpack", "50 Motor, 1000 Cable, 1000 Iron Sheet", "5:00", "Jetpack, backpack to Diamond tier (81 slots)", ["siftec:jetpack"], ["backpack:diamond"]),
    (5, "oil_processing", "Oil Processing", "50 Motor, 100 Encased Industrial Beam, 500 Steel Pipe, 500 Copper Sheet", "12:00",
     "Pumpjack (oil nodes only), distillation, Fluid Valve, Plastic, Rubber, Petroleum Coke, Circuit Board, oil nodes on the scanner",
     ["createdieselgenerators:pumpjack_bearing", "createdieselgenerators:pumpjack_crank", "createdieselgenerators:pumpjack_head",
      "createdieselgenerators:pumpjack_hole", "createdieselgenerators:distillation_controller",
      C + "fluid_valve", "siftec:plastic", "siftec:rubber", "siftec:petroleum_coke", "siftec:circuit_board"], ["scanner:oil"]),
    (5, "logistics_mk4", "Logistics Mk.4", "50 Heavy Modular Frame, 100 Computer, 200 Encased Industrial Beam, 400 Rubber", "15:00",
     "Company speed limit raised to 128 RPM", [], ["cap:128"]),
    (5, "fluid_packaging", "Fluid Packaging", "200 Plastic, 400 Steel Beam, 1000 Copper Sheet", "8:00",
     "Packager (Spout and Item Drain), canisters, Liquid Biofuel",
     [C + "spout", C + "item_drain", C + "portable_fluid_interface", "createdieselgenerators:canister"], []),
    (5, "petroleum_power", "Petroleum Power", "100 Motor, 100 Encased Industrial Beam, 200 Rubber, 200 Plastic", "8:00",
     "Diesel Engines, zinc nodes on the scanner",
     ["createdieselgenerators:diesel_engine", "createdieselgenerators:large_diesel_engine", "createdieselgenerators:huge_diesel_engine"], ["scanner:zinc"]),

    (6, "industrial_manufacturing", "Industrial Manufacturing", "100 Motor, 200 Plastic, 200 Rubber, 1000 Cable", "12:00",
     "Manufacturer (Mechanical Crafter), Computer, Heavy Modular Frame, Modular Engine, Adaptive Control Unit",
     [C + "mechanical_crafter", "siftec:computer", "siftec:heavy_modular_frame", "siftec:modular_engine", "siftec:adaptive_control_unit"], []),
    (6, "monorail_train_technology", "Monorail Train Technology", "50 Computer, 100 Heavy Modular Frame, 500 Steel Beam, 600 Steel Pipe", "15:00",
     "Train tracks, stations, controls, signals and schedules",
     [C + "track", C + "track_station", C + "track_signal", C + "track_observer", C + "controls", C + "schedule",
      C + "portable_storage_interface", C + "mechanical_roller", C + "railway_casing"], []),
    (6, "pipeline_engineering_mk2", "Pipeline Engineering Mk.2", "1000 Plastic, 1000 Rubber, 50 Heavy Modular Frame", "10:00",
     "Mechanical Pump range and flow rate doubled", [], ["pumps:2"]),

    (7, "bauxite_refinement", "Bauxite Refinement", "50 Computer, 100 Heavy Modular Frame, 200 Motor, 500 Rubber", "10:00",
     "Aluminium chain and Radio Control Unit, bauxite and quartz nodes on the scanner",
     ["siftec:aluminum_scrap", "siftec:aluminum_ingot", "siftec:alclad_aluminum_sheet", "siftec:aluminum_casing",
      "siftec:radio_control_unit"], ["scanner:bauxite", "scanner:quartz"]),
    (7, "logistics_mk5", "Logistics Mk.5", "100 Alclad Aluminum Sheet, 200 Encased Industrial Beam, 300 Reinforced Iron Plate", "1:00",
     "Company speed limit raised to 192 RPM", [], ["cap:192"]),
    (7, "hazmat_suit", "Hazmat Suit", "50 Aluminum Casing, 500 Quickwire, 50 Gas Filter", "5:00",
     "Hazmat Suit, Iodine Infused Filter, Backpack Lantern and Jukebox Upgrades", ["siftec:hazmat_suit", "siftec:iodine_infused_filter"], []),
    (7, "hover_pack", "Hover Pack", "200 Motor, 100 Heavy Modular Frame, 100 Computer, 200 Alclad Aluminum Sheet", "5:00",
     "Hover Pack, backpack to Netherite tier (99 slots)", ["siftec:hover_pack"], ["backpack:netherite"]),

    (8, "aeronautical_engineering", "Aeronautical Engineering", "50 Radio Control Unit, 100 Alclad Aluminum Sheet, 200 Aluminum Casing, 300 Motor", "15:00",
     "Drone Port and Cardboard Drone, Create's package network, Supercomputer, Assembly Director System, sulfur nodes on the scanner",
     ["siftec:drone_port", "siftec:cardboard_drone", C + "packager", C + "package_frogport", C + "repackager", C + "stock_link", C + "stock_ticker",
      C + "redstone_requester", C + "factory_gauge", C + "chain_conveyor", "siftec:supercomputer", "siftec:assembly_director_system"], ["scanner:sulfur"]),
    (8, "advanced_aluminum_production", "Advanced Aluminum Production", "50 Radio Control Unit, 200 Aluminum Casing, 200 Alclad Aluminum Sheet, 300 Wire", "15:00",
     "Resource Well extractor, packaged nitrogen, Heat Sink, Cooling System, Fused Modular Frame, Electromagnetic Control Rod, nitrogen nodes on the scanner",
     ["siftec:resource_well_extractor", "siftec:empty_fluid_tank", "siftec:packaged_nitrogen_gas", "siftec:heat_sink", "siftec:cooling_system", "siftec:fused_modular_frame",
      "siftec:electromagnetic_control_rod"], ["scanner:nitrogen"]),
    (8, "leading_edge_production", "Leading-edge Production", "50 Fused Modular Frame, 100 Supercomputer, 1000 Steel Pipe", "5:00",
     "Miner Mk.3, Turbo Motor, Thermal Propulsion Rocket, Magnetic Field Generator",
     ["siftec:turbo_motor", "siftec:thermal_propulsion_rocket", "siftec:magnetic_field_generator"], []),
    (8, "particle_enrichment", "Particle Enrichment", "400 Electromagnetic Control Rod, 400 Cooling System, 200 Fused Modular Frame, 100 Turbo Motor", "20:00",
     "Particle Accelerator, Copper Powder, Pressure Conversion Cube, Nuclear Pasta, uranium nodes on the scanner",
     ["siftec:particle_accelerator", "siftec:copper_powder", "siftec:pressure_conversion_cube", "siftec:nuclear_pasta"], ["scanner:uranium"]),

    (9, "matter_conversion", "Matter Conversion", "100 Fused Modular Frame, 250 Radio Control Unit, 500 Cooling System", "5:00",
     "Converter, Ficsite, Time Crystal, Biochemical Sculptor, Reanimated SAM, SAM nodes on the scanner",
     ["siftec:converter", "siftec:reanimated_sam", "siftec:sam_fluctuator", "siftec:ficsite_ingot", "siftec:ficsite_trigon", "siftec:time_crystal",
      "siftec:biochemical_sculptor"], ["scanner:sam"]),
    (9, "quantum_encoding", "Quantum Encoding", "50 Time Crystal, 10 Ficsite Trigon, 200 Turbo Motor, 400 Supercomputer", "15:00",
     "Quantum Encoder (sequenced assembly), Superposition Oscillator, Neural-Quantum Processor, AI Expansion Server",
     ["siftec:superposition_oscillator", "siftec:neural_quantum_processor", "siftec:ai_expansion_server"], []),
    (9, "ficsit_blueprints_mk3", "FICSIT Blueprints Mk.3", "100 Neural-Quantum Processor, 250 Time Crystal, 500 Ficsite Trigon, 500 Fused Modular Frame", "20:00",
     "Blueprint Designer Mk.3", ["siftec:blueprint_designer_mk3"], []),
    (9, "spatial_energy_regulation", "Spatial Energy Regulation", "100 Superposition Oscillator, 250 Turbo Motor, 500 Radio Control Unit, 1000 SAM Fluctuator", "20:00",
     "Main Portal and Satellite Portal, Singularity Cell, Ballistic Warp Drive",
     ["siftec:main_portal", "siftec:satellite_portal", "siftec:singularity_cell", "siftec:ballistic_warp_drive"], []),
    (9, "peak_efficiency", "Peak Efficiency", "250 Time Crystal, 250 Ficsite Trigon, 1000 Alclad Aluminum Sheet, 2000 Iron Sheet", "20:00",
     "Company speed limit raised to 256 RPM", [], ["cap:256"]),
]

# Satisfactory 1.0's cost and lock time for every milestone come from tools/satisfactory.py (Concrete as any stone,
# Iron Plate as Iron Sheet); the list above says only what each unlocks in the pack. Four 1.0 milestones the old list
# missed are added: FICSIT Blueprints Mk.2 and Railway Signalling (Tier 6), Control System Development (Tier 7) and
# Nuclear Power (Tier 8). Recipes follow the milestone that unlocks them in 1.0 (recipes.py), so the parts moved with them.
SAT_MILESTONES = {f"HUB Upgrade {n}": f"hub_upgrade_{n}" for n in range(1, 7)}
SAT_MILESTONES.update({"Base Building": "base_building", "Logistics": "logistics", "Field Research": "field_research",
    "Part Assembly": "part_assembly", "Obstacle Clearing": "obstacle_clearing", "Jump Pads": "jump_pads",
    "Resource Sink Bonus Program": "resource_sink_bonus_program", "Logistics Mk.2": "logistics_mk2", "Coal Power": "coal_power",
    "Vehicular Transport": "vehicular_transport", "Basic Steel Production": "basic_steel_production", "Enhanced Asset Security": "enhanced_asset_security",
    "FICSIT Blueprints": "ficsit_blueprints", "Logistics Mk.3": "logistics_mk3", "Advanced Steel Production": "advanced_steel_production",
    "Expanded Power Infrastructure": "expanded_power_infrastructure", "Hypertubes": "hypertubes", "Jetpack": "jetpack", "Oil Processing": "oil_processing",
    "Logistics Mk.4": "logistics_mk4", "Fluid Packaging": "fluid_packaging", "Petroleum Power": "petroleum_power",
    "Industrial Manufacturing": "industrial_manufacturing", "Monorail Train Technology": "monorail_train_technology",
    "Pipeline Engineering Mk.2": "pipeline_engineering_mk2", "FICSIT Blueprints Mk.2": "ficsit_blueprints_mk2", "Railway Signalling": "railway_signalling",
    "Bauxite Refinement": "bauxite_refinement", "Logistics Mk.5": "logistics_mk5", "Hazmat Suit": "hazmat_suit", "Hoverpack": "hover_pack",
    "Control System Development": "control_system_development", "Aeronautical Engineering": "aeronautical_engineering", "Nuclear Power": "nuclear_power",
    "Advanced Aluminum Production": "advanced_aluminum_production", "Leading-Edge Production": "leading_edge_production",
    "Particle Enrichment": "particle_enrichment", "Matter Conversion": "matter_conversion", "Quantum Encoding": "quantum_encoding",
    "FICSIT Blueprints Mk.3": "ficsit_blueprints_mk3", "Spatial Energy Regulation": "spatial_energy_regulation", "Peak Efficiency": "peak_efficiency"})
_NEW = {  # id: (tier, name, unlock text, locked item ids, tokens), placed after the last milestone of its tier
    "ficsit_blueprints_mk2": (6, "FICSIT Blueprints Mk.2", "Blueprint Designer Mk.2", [], []),
    "railway_signalling": (6, "Railway Signalling", "Train signals and the train observer", [C + "track_signal", C + "track_observer"], []),
    "control_system_development": (7, "Control System Development",
                                   "Blender; Sulfuric Acid, Battery, Radio Control Unit, Supercomputer and Assembly Director System", [], []),
    "nuclear_power": (8, "Nuclear Power", "Nuclear Power Plant; Encased Uranium Cell, Electromagnetic Control Rod, Uranium Fuel Rod and "
                      "Magnetic Field Generator; uranium nodes on the scanner", [], ["scanner:uranium"]),
}
_TEXT = {  # unlock texts that named a recipe 1.0 gives with another milestone
    "monorail_train_technology": "Train tracks, stations, controls and schedules",
    "bauxite_refinement": "Aluminium chain, bauxite and quartz nodes on the scanner",
    "aeronautical_engineering": "Drone Port and Cardboard Drone, Create's package network, sulfur nodes on the scanner",
    "advanced_aluminum_production": "Resource Well extractor, packaged nitrogen, Heat Sink, Cooling System, Fused Modular Frame, nitrogen nodes on the scanner",
    "leading_edge_production": "Miner Mk.3, Turbo Motor, Thermal Propulsion Rocket",
    "particle_enrichment": "Particle Accelerator, Nitric Acid, Non-Fissile Uranium, plutonium, Copper Powder, Pressure Conversion Cube, Nuclear Pasta",
    "matter_conversion": "Converter, Ficsite, Diamonds, Time Crystal, Biochemical Sculptor, Reanimated SAM, SAM Fluctuator, SAM nodes on the scanner",
    "quantum_encoding": "Quantum Encoder, Excited Photonic Matter, Dark Matter, Superposition Oscillator, Neural-Quantum Processor, AI Expansion Server",
    "peak_efficiency": "Belt Mk.6 speed, Ficsonium and the Ficsonium Fuel Rod",
}


def _sat_cost(cost):
    return ", ".join(f"{n} {sat_item(name)[1]}" for name, n in cost)


def _mmss(seconds):
    return f"{int(seconds) // 60}:{int(seconds) % 60:02d}"


MILESTONES = [(t, mid, name, cost, time, text, [i for i in items if i not in (C + "track_signal", C + "track_observer")],
               [k for k in tokens if k != "scanner:uranium"]) for t, mid, name, cost, time, text, items, tokens in MILESTONES]
for _mid, (_tier, _name, _text, _items, _tokens) in _NEW.items():
    _at = max(i for i, m in enumerate(MILESTONES) if m[0] == _tier) + 1
    MILESTONES.insert(_at, (_tier, _mid, _name, "", "0:00", _text, _items, _tokens))
_by_sat = {SAT_MILESTONES[name]: (cost, secs) for tier, name, cost, secs, rec, bld, eq in SF.MILESTONES}
if set(_by_sat) != {m[1] for m in MILESTONES}:
    raise SystemExit(f"milestones: no 1.0 match for {sorted({m[1] for m in MILESTONES} ^ set(_by_sat))}")
MILESTONES = [(t, mid, name, _sat_cost(_by_sat[mid][0]), _mmss(_by_sat[mid][1]), _TEXT.get(mid, text), items, tokens)
              for t, mid, name, cost, time, text, items, tokens in MILESTONES]


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
# MAM research. Tree: (id, name, icon item, [nodes]); node: (name, cost, time, unlock text, needs, items, tokens).
# "needs" lists the node numbers (1-based) that must be done first; None means the node above.
# A cost may name a tag ("#siftec:mycelia") for "any of these".
M = "minecraft:"
MAM = [
    ("megafauna", "Alien Megafauna", M + "rotten_flesh", [
        ("Hostile Remains", "5 minecraft:rotten_flesh", "0:30", "Biomass from mob drops in the Millstone", [], [], []),
        ("Bio-Organic Properties", "10 minecraft:bone, 10 minecraft:string", "1:00", "DNA Capsule, for AWESOME Sink points", None, ["siftec:dna_capsule"], []),
        ("The Rebar Gun", "10 Reinforced Iron Plate, 25 Iron Rod, 50 Screw", "2:00", "Nailgun and Copper Backtank", None, ["cgs:nailgun", C + "copper_backtank"], []),
        ("Hostile Organism Detection", "10 minecraft:spider_eye, 10 Gunpowder, 25 Wire", "2:00", "The Object Scanner can find hostile mobs", None, [], ["object:mobs"]),
        ("Inflated Pocket Dimension", "5 Alien DNA Capsule, 3 minecraft:ender_pearl, 25 Cable", "3:00", "Backpack Void and Refill Upgrades", None, [], []),
    ]),
    ("caterium", "Caterium", C + "raw_zinc", [
        ("Caterium", "10 create:crushed_raw_zinc", "0:30", "Brass blend, Brass Ingot, Brass Sheet", [], ["siftec:brass_blend", C + "brass_ingot", C + "brass_sheet"], []),
        ("Quickwire", "10 Brass Ingot", "1:00", "Quickwire", [1], ["siftec:quickwire"], []),
        ("Caterium Electronics", "50 Quickwire", "2:00", "Electron Tube, Precision Mechanism, Brass Funnel, Brass Tunnel, Smart Chute, filters, Smart Fluid Pipe, Controller Rail, Smart Observer, Threshold Switch", [2],
         [C + "electron_tube", C + "precision_mechanism", C + "brass_funnel", C + "brass_tunnel", C + "smart_chute", C + "filter", C + "attribute_filter",
          C + "smart_fluid_pipe", C + "controller_rail", C + "content_observer", C + "stockpile_switch"], []),
        ("AI Limiter", "100 Quickwire, 50 Copper Sheet", "3:00", "AI Limiter, Mechanical Arm", [3], ["siftec:ai_limiter", C + "mechanical_arm"], []),
        ("Power Switch", "50 Quickwire, 25 Rotor", "2:00", "Clutch, Gearshift, Sequenced Gearshift, Adjustable Chain Gearshift, Speed Governor", [2],
         [C + "clutch", C + "gearshift", C + "sequenced_gearshift", C + "adjustable_chain_gearshift", "siftec:speed_governor"], []),
        ("Zipline", "100 Quickwire, 50 Cable", "2:00", "Use a Wrench on a Power Pole or Tower to ride its Power Lines", [2], [], ["zipline"]),
        ("High-Speed Connector", "500 Quickwire, 25 Plastic, 10 Computer", "5:00", "High-Speed Connector", [4], ["siftec:high_speed_connector"], []),
    ]),
    ("mycelia", "Mycelia", M + "brown_mushroom", [
        ("Mycelia", "5 #siftec:mycelia", "0:30", "Biomass from mushrooms in the Millstone", [], [], []),
        ("Fabric", "25 #siftec:mycelia, 100 Biomass, 10 Rotor", "1:00", "Fabric", [1], ["siftec:fabric"], []),
        ("Parachute", "10 Fabric, 10 Cable", "2:00", "Parachute", [2], ["siftec:parachute"], []),
        ("Diving Gear", "10 Fabric, 10 Copper Sheet", "2:00", "Diving Helmet and Diving Boots, then the netherite backtank and diving gear", [2],
         [C + "copper_diving_helmet", C + "copper_diving_boots", C + "netherite_diving_helmet", C + "netherite_diving_boots", C + "netherite_backtank"], []),
        ("Toxic Cellular Modification", "50 Fabric, 25 Rubber, 5 Heavy Modular Frame", "3:00", "Gas Filter and Gas Mask", [2], ["siftec:gas_filter", "siftec:gas_mask"], []),
    ]),
    ("nutrients", "Nutrients", M + "sweet_berries", [
        ("Fermentation", "20 #c:crops, 10 minecraft:sugar", "1:00", "Bulk fermenter and vinegar", [], ["createdieselgenerators:bulk_fermenter", "siftec:vinegar_bucket"], ["food:vinegar"]),
        ("Preserves", "10 minecraft:glass_bottle, 20 minecraft:sweet_berries, 5 Empty Canister", "2:00", "Pickles from the Spout and jams from the heated Mixer. Sneak and use one to throw it", [1], ["siftec:pickled_tomato", "siftec:pickled_onion", "siftec:pickled_cabbage", "siftec:pickled_pumpkin", "siftec:pickled_carrot", "siftec:pickled_beetroot", "siftec:pickled_kelp", "siftec:sweet_berry_jam", "siftec:glow_berry_jam", "siftec:apple_jam", "siftec:melon_jam"], ["food:preserves"]),
        ("Brewing", "20 minecraft:apple, 10 minecraft:honey_bottle, 5 Empty Canister", "2:00", "Mead from the bulk fermenter, bottled at the Spout", [1], ["siftec:mead_bucket", "siftec:mead"], ["food:brewing"]),
        ("Seasoning", "10 minecraft:glowstone_dust, 10 minecraft:blaze_powder, 10 minecraft:nether_wart", "2:00", "Secret ingredients: hold glowstone dust, blaze powder or nether wart in your main hand and food in your off hand, and use", [1], [], ["food:seasoning"]),
        ("Automated Kitchen", "10 minecraft:bread, 10 Rotor", "3:00", "Cutting board recipes on the Deployer, cooking pot meals in the heated Mixer", [1], [], ["food:kitchen"]),
    ]),
    ("slugs", "Power Slugs", "siftec:blue_power_slug", [
        ("Blue Power Slugs", "1 Blue Power Slug", "0:30", "Power Shards from blue slugs", [], ["siftec:power_shard"], []),
        ("Overclock Production", "1 Power Shard, 50 Iron Sheet, 50 Wire", "1:00", "Machines take one shard", None, [], ["shards:1"]),
        ("Yellow Power Shards", "1 Yellow Power Slug, 25 Rotor, 100 Cable", "2:00", "Shards from yellow slugs, and a second shard slot", None, [], ["shards:2"]),
        ("Purple Power Shards", "1 Purple Power Slug, 25 Modular Frame, 25 Copper Sheet", "3:00", "Shards from purple slugs, and a third shard slot", None, [], ["shards:3"]),
        ("Synthetic Power Shards", "10 Time Crystal, 10 Dark Matter Crystal", "5:00", "Time Crystal + Dark Matter Crystal makes a Power Shard on the Deployer", None, [], []),
    ]),
    ("quartz", "Quartz", M + "quartz", [
        ("Quartz Crystals", "10 Nether Quartz", "0:30", "Quartz Crystal and Sand Paper", [], ["siftec:quartz_crystal", C + "sand_paper", C + "red_sand_paper"], []),
        ("Silica", "10 Nether Quartz", "0:30", "Silica", [], ["siftec:silica"], []),
        ("Radio Signal Scanning", "25 Quartz Crystal, 50 minecraft:redstone, 10 Brass Sheet", "2:00", "Rose quartz and Create's redstone components", [1],
         [C + "rose_quartz", C + "pulse_repeater", C + "pulse_extender", C + "pulse_timer", C + "powered_latch", C + "powered_toggle_latch",
          C + "analog_lever", C + "redstone_contact"], []),
        ("Crystal Oscillator", "100 Quartz Crystal, 50 Reinforced Iron Plate, 5 Heavy Modular Frame", "3:00", "Crystal Oscillator", [1], ["siftec:crystal_oscillator"], []),
        ("Frequency Mapping", "5 Crystal Oscillator, 50 minecraft:redstone", "3:00", "Redstone Link, Display Link, Display Board, Nixie Tube", [3, 4],
         [C + "redstone_link", C + "display_link", C + "display_board", C + "nixie_tube"], []),
        ("Blade Runners", "50 Silica, 10 Modular Frame", "2:00", "Blade Runners", [2], ["siftec:blade_runners"], []),
        ("Radar Technology", "10 Crystal Oscillator, 25 Heavy Modular Frame", "5:00", "Radar Tower", [5], ["siftec:radar_tower"], []),
    ]),
    ("sulfur", "Sulfur", "cgs:sulfur", [
        ("Black Powder", "10 Sulfur", "0:30", "Gunpowder from sulfur, coal or charcoal, and bone meal", [], [], []),
        ("Ignimbrite", "20 Sulfur, 20 minecraft:cobblestone, 10 Steel Pipe", "1:00", "Ignimbrite, fire charges, magma blocks and lava", [1], ["siftec:ignimbrite_bucket"], []),
        ("Explosives", "50 Gunpowder, 10 Steel Pipe, 10 Encased Industrial Beam", "2:00", "TNT from the Spout, Frag Grenade", [2], ["cgs:frag_grenade"], []),
        ("The Rifle", "50 Gunpowder, 25 Steel Pipe, 10 Motor, 5 Heavy Modular Frame", "3:00", "Revolver and Shotgun", [1], ["cgs:revolver", "cgs:shotgun"], []),
        ("Heavy Weapons", "100 Gunpowder, 25 Motor, 10 Encased Industrial Beam", "5:00", "Gatling and Launcher", [4], ["cgs:gatling", "cgs:launcher"], []),
        ("Compacted Coal", "25 Coal, 25 Sulfur", "2:00", "Compacted Coal", [1], ["siftec:compacted_coal"], []),
        ("Turbofuel", "15 Compacted Coal, 20 createdieselgenerators:diesel_bucket", "5:00", "Turbofuel", [6], ["siftec:turbofuel_bucket"], []),
        ("Geothermal Power", "50 Sulfur, 10 Heavy Modular Frame, 5 Supercomputer", "5:00", "Geyser Engine", [2], ["siftec:geyser_engine"], []),
    ]),
    ("alien", "Alien Technology", "siftec:mercer_sphere", [
        ("Mercer Sphere Analysis", "1 Mercer Sphere", "1:00", "The Object Scanner can find Mercer Spheres", [], [], ["object:mercer_sphere"]),
        ("Dimensional Depot", "2 Mercer Sphere, 10 Modular Frame, 50 Cable", "3:00", "Dimensional Depot", [1], ["siftec:dimensional_depot"], []),
        ("Depot Expansion", "3 Mercer Sphere, 25 Computer", "5:00", "Cloud stack size doubled", [2], [], ["depot:expansion"]),
        ("Upload Upgrade", "3 Mercer Sphere, 25 Motor", "5:00", "Upload speed doubled", [2], [], ["depot:upload"]),
        ("Somersloop Analysis", "1 Somersloop", "1:00", "The Object Scanner can find Somersloops", [], [], ["object:somersloop"]),
        ("Production Amplifier", "1 Somersloop, 25 Circuit Board, 10 Motor", "5:00", "Miners, the Press, Mixer, Saw, Millstone, Deployer, Crushing Wheels and Encased Fan take one Somersloop: double output at four times the stress", [5], [], ["amplifier"]),
        ("Alien Energy Harvesting", "2 Somersloop, 10 Supercomputer", "5:00", "A machine can hold a Somersloop and Power Shards together", [6], [], ["amplifier:shards"]),
    ]),
]
# 1.0 MAM research that unlocks recipes and that the trees above did not have yet. Appended, so the nodes above keep
# their ids; costs are 1.0's in the pack's names. Work package 8 rebuilds the trees from the MAM Research tab.
_MAM_MORE = {
    "caterium": [("Stun Rebar", "50 Quickwire, 10 Iron Rebar", "0:03", "Stun Rebar", [2], [], [])],
    "mycelia": [("Synthetic Polyester Fabric", "25 Fabric, 100 Polymer Resin", "0:03", "Fabric from Polymer Resin in the Refinery", [2], [], [])],
    "quartz": [("Shatter Rebar", "30 Quartz Crystal, 150 Iron Rebar", "0:03", "Shatter Rebar", [1], [], [])],
    "sulfur": [("Smokeless Powder", "100 Gunpowder, 50 Plastic", "0:03", "Smokeless Powder", [1], [], []),
               ("Rocket Fuel", "1 Hard Drive, 10 Empty Fluid Tank, 100 Packaged Turbofuel", "0:03", "Rocket Fuel, packed and unpacked", [7], [], []),
               ("Ionized Fuel", "1 Hard Drive, 100 Time Crystal, 200 Packaged Rocket Fuel", "0:03", "Ionized Fuel, packed and unpacked", [10], [], [])],
    "alien": [("SAM Reanimation", "20 SAM", "0:03", "Reanimated SAM", [], [], []),
              ("SAM Fluctuator", "10 Reanimated SAM, 100 Steel Pipe, 200 Wire", "0:03", "SAM Fluctuator", [8], [], []),
              ("Alien Power Matrix", "50 Singularity Cell, 500 SAM Fluctuator, 100 Time Crystal", "0:03", "Alien Power Matrix (Quantum Encoder)", [9], [], [])],
}
for _tree in MAM:
    _tree[3].extend(_MAM_MORE.get(_tree[0], []))
# the 1.0 MAM node behind each recipe it unlocks, as a node of the trees above
SAT_MAM = {"Hog Research": "mam_megafauna_1", "Hatcher Research": "mam_megafauna_1", "Spitter Research": "mam_megafauna_1",
           "Stinger Research": "mam_megafauna_1", "Bio-Organic Properties": "mam_megafauna_2", "The Rebar Gun": "mam_megafauna_3",
           "Caterium Ingots": "mam_caterium_1", "Quickwire": "mam_caterium_2", "AI Limiter": "mam_caterium_4", "High-Speed Connector": "mam_caterium_7",
           "Stun Rebar": "mam_caterium_8", "Mycelia": "mam_mycelia_1", "Fabric": "mam_mycelia_2", "Gas Mask": "mam_mycelia_5",
           "Synthetic Polyester Fabric": "mam_mycelia_6", "Quartz Crystals": "mam_quartz_1", "Silica": "mam_quartz_2", "Crystal Oscillator": "mam_quartz_4",
           "Shatter Rebar": "mam_quartz_8", "Black Powder": "mam_sulfur_1", "Compacted Coal": "mam_sulfur_6", "Turbofuel": "mam_sulfur_7",
           "Smokeless Powder": "mam_sulfur_9", "Rocket Fuel": "mam_sulfur_10", "Ionized Fuel": "mam_sulfur_11", "SAM Reanimation": "mam_alien_8",
           "SAM Fluctuator": "mam_alien_9", "Alien Power Matrix": "mam_alien_10"}


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
        return SAT_MAM[name]
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

# AWESOME Sink: points for one of each item, Satisfactory's own value for every Satisfactory part (where a part is the
# same item as a vanilla one, Satisfactory's value wins). Anything not listed is worth 1. A spare Hard Drive is 1,000
# (a SIFTEC rule: Satisfactory takes none).
SINK_POINTS = {}
for _sat, _info in sorted(SF.ITEMS.items()):
    _pack = sat_item(_sat, True)
    if _pack and _pack[0] == "item" and not _pack[1].startswith("#") and _info["sink"]:
        SINK_POINTS.setdefault(_pack[1], _info["sink"])
SINK_POINTS["Hard Drive"] = 1000
SINK_POINTS["siftec:object_scanner"] = SINK_POINTS.pop("Object Scanner")


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

# AWESOME Shop: (item id, how many, price in points)
SHOP = [
    (C + "potato_cannon", 1, 3000), (C + "extendo_grip", 1, 3000), (C + "wand_of_symmetry", 1, 5000), (C + "linked_controller", 1, 3000),
    (C + "cuckoo_clock", 1, 500), (C + "steam_whistle", 1, 500), (C + "peculiar_bell", 1, 1000), (C + "clipboard", 1, 200), (C + "placard", 4, 200),
    (C + "turntable", 1, 500), (C + "crafting_blueprint", 1, 500), (C + "copper_valve_handle", 4, 200), (C + "andesite_table_cloth", 4, 200),
    ("minecraft:white_dye", 16, 100), ("minecraft:red_dye", 16, 100), ("minecraft:blue_dye", 16, 100), ("minecraft:yellow_dye", 16, 100),
    ("minecraft:green_dye", 16, 100), ("minecraft:black_dye", 16, 100), ("minecraft:orange_dye", 16, 100), ("minecraft:light_blue_dye", 16, 100),
]

# Furnace burn times, in ticks (coal is 1600), after Satisfactory's energy values. A Blaze Burner burns anything a
# furnace burns, so these heat boilers too, and the Furnace Engine runs off the furnace they burn in.
FUELS = {"biomass": 960, "solid_biofuel": 2400, "compacted_coal": 3360, "petroleum_coke": 960}

# ---------------------------------------------------------------------------------------------------
# Everything else the other mods add. Locked by default: an item from Create, Diesel Generators, Gunsmithing,
# Hypertubes or Traveler's Backpack is unlocked by a milestone above, by one here (MORE_LOCKS), left free on purpose
# (FREE), switched off (DISABLED) or sold in the AWESOME Shop (SHOP, whose crafting recipes are removed).
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


DG, G, H = "createdieselgenerators:", "cgs:", "create_hypertube:"
TB = "travelersbackpack:"
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
    # with the AWESOME Shop: the dyed and haunted versions of what it sells, and the rest of the design's Shop list
    "resource_sink_bonus_program": [C + "desk_bell", C + "haunted_bell", C + "mysterious_cuckoo_clock", C + "metal_bracket", C + "wooden_bracket",
                                    C + "copper_table_cloth"] + [f"{C}{c}_table_cloth" for c in COLOURS] + [f"{C}{c}_valve_handle" for c in COLOURS],
    "basic_steel_production": [G + "steel_nugget", G + "steel_block", G + "steel_sheet", G + "nail_steel", TB + "blast_furnace_upgrade"],
    # Traveler's Backpack upgrades (the backpack itself is given, and its tiers come with milestones: see the tokens above)
    "hub_upgrade_4": [TB + "blank_upgrade", TB + "crafting_upgrade"],
    "hub_upgrade_5": [TB + "pickup_upgrade", TB + "furnace_upgrade"],
    "obstacle_clearing": [TB + "feeding_upgrade", TB + "smoker_upgrade"],
    "coal_power": [TB + "tanks_upgrade", TB + "backpack_tank", TB + "hose_nozzle", TB + "hose"],
    "hazmat_suit": [TB + "lantern_upgrade", TB + "jukebox_upgrade"],
    "mam_megafauna_5": [TB + "void_upgrade", TB + "refill_upgrade"],
    # melee weapons go with the Xeno-Basher
    "enhanced_asset_security": [G + "hammer_stone", G + "hammer_iron", G + "hammer_diamond", G + "hammer_netherite", G + "axe_stone", G + "axe_iron",
                                G + "axe_diamond", G + "axe_netherite", G + "spear", TB + "magnet_upgrade"],
    "expanded_power_infrastructure": [C + "piston_extension_pole", C + "secondary_linear_chassis", C + "elevator_contact"],
    "hypertubes": [H + "hypertube_funnel", H + "redstone_detector_tube_attachment", H + "tube_scanner_attachment"],
    "oil_processing": [DG + "asphalt_block", DG + "asphalt_slab", DG + "asphalt_stairs", DG + "oil_scanner", DG + "mold", DG + "sheet_metal_panel",
                       DG + "hammer", DG + "wire_cutters"],
    "fluid_packaging": [DG + "oil_barrel"],
    "petroleum_power": [DG + "burner", DG + "engine_piston", DG + "engine_silencer", DG + "engine_turbocharger", DG + "lighter"],
    # Mechanical Crafter recipes
    "industrial_manufacturing": [C + "crafter_slot_cover", H + "hypertube_junction", G + "nailgun_splitter"],
    "monorail_train_technology": [C + "train_door", C + "train_trapdoor", DG + "track_layers_bag"] + [f"{C}{c}_postbox" for c in COLOURS],
    "aeronautical_engineering": [C + "cardboard", C + "cardboard_block", C + "bound_cardboard_block", C + "cardboard_helmet", C + "cardboard_chestplate",
                                 C + "cardboard_leggings", C + "cardboard_boots", C + "cardboard_sword", C + "package_filter"],
    # MAM research
    "mam_megafauna_3": [G + "nail"],
    "mam_caterium_1": [C + "brass_nugget", C + "brass_block", C + "brass_casing", C + "brass_bars",
                       C + "brass_door", C + "brass_ladder", C + "brass_scaffolding", C + "brass_table_cloth", C + "brass_encased_shaft",
                       C + "brass_encased_cogwheel", C + "brass_encased_large_cogwheel"],
    "mam_caterium_3": [DG + "entity_filter"],
    "mam_nutrients_1": [DG + "basin_lid"],
    "mam_quartz_3": [C + "polished_rose_quartz", C + "rose_quartz_block", C + "rose_quartz_lamp", C + "rose_quartz_tiles", C + "small_rose_quartz_tiles"],
    # guns: the flintlock with black powder, then the Rifle and Heavy Weapons nodes with their parts and rounds
    "mam_sulfur_1": [G + "flintlock", G + "flintlock_chambers", G + "flintlock_long_barrel", G + "blunderbuss_barrel",
                     G + "blunderbuss_barrel_long", G + "paper_cartridge", G + "paper_shot", G + "lead_balls"],
    "mam_sulfur_4": [G + "stock", G + "scope", G + "bayonet", G + "big_bayonet", G + "hammer_chamber", G + "long_barrel", G + "auto_fire", G + "round_belt", G + "shotgun_drum", G + "shotgun_long_barrel", G + "shotgun_pump",
                     G + "shotgun_spread_barrel", G + "press_form_revolver", G + "press_form_shotgun", G + "round_revolver", G + "round_revolver_blank",
                     G + "round_revolver_piercing", G + "round_shotgun", G + "round_shotgun_blank", G + "round_shotgun_flechette",
                     G + "round_shotgun_flechette_steel", G + "round_shotgun_incendiary"],
    "mam_sulfur_5": [G + "gatling_drum", G + "press_form_gatling", G + "round_gatling", G + "round_gatling_blank", G + "round_gatling_piercing",
                     G + "rocket", G + "rocket_small", G + "launcher_auto", G + "ballistazooka", G + "blazegun", G + "steam_engine", G + "tank_empty",
                     G + "tank_lava", G + "tank_water", DG + "chemical_sprayer", DG + "chemical_sprayer_lighter", DG + "chemical_turret"],
}
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
    + [f"{DG}{c}_cement_bucket" for c in COLOURS] + [H + "tube_scanner_unfinished"]

# Traveler's Backpack: every player is given one backpack and it is never crafted, so every backpack design, the tier
# upgrades (tiers come with milestones) and the sleeping bags (only for crafting and placed backpacks) are switched off.
TB_BACKPACKS = [TB + i for i in (
    "bat bee blaze bookshelf cactus cake chicken coal cow creeper diamond dragon emerald end enderman fox ghast gold hay "
    "horse iron iron_golem lapis magma_cube melon nether netherite ocelot pig pumpkin quartz redstone sandstone sheep "
    "skeleton snow spider sponge squid standard villager warden wither wolf").split()]
TB_TIER_UPGRADES = [TB + t + "_tier_upgrade" for t in ("iron", "gold", "diamond", "netherite")]
TB_SLEEPING_BAGS = [f"{TB}{c}_sleeping_bag" for c in COLOURS]
DISABLED += TB_BACKPACKS + TB_TIER_UPGRADES + TB_SLEEPING_BAGS


# ---------------------------------------------------------------------------------------------------
# Build Gun buildings (building/Buildings.java): id, name, Build Gun tab, Satisfactory 1.0 build cost, the milestone or
# MAM node that unlocks it ("" = from the start), and whether it is a dev building (only in creative). Each has a
# structure template, src/main/resources/data/siftec/structure/building/<id>.nbt: built in game and saved with a
# Structure Block, or written by tools/structures.py. Work packages 5 and 9 add the real buildings here.
BUILD_TABS = ("production", "power", "logistics", "foundations", "special")
BUILDINGS = [
    ("test_building", "Test Building", "production", "2 Iron Sheet, 4 Iron Rod", "", True),
    ("test_miner", "Test Miner", "production", "2 Iron Sheet", "", True),
]
# Buildings that stand over a resource node and can only go down once its clump is mined (the miners, extractors)
NODE_BUILDINGS = {"test_miner"}


# ---------------------------------------------------------------------------------------------------
# Which milestone or MAM node unlocks each part: the 1.0 unlock of the earliest standard recipe that makes it (a HUB
# milestone before a MAM node). A part only an alternate makes, or one made from the start, is not locked by itself.
# The parts' old places in the lists above are replaced by these; anything else in those lists stays.
def _part_locks():
    table = names()
    parts = {"siftec:" + p[0] for p in PARTS}
    # MAM research opens at Field Research (Tier 1), so a MAM node counts as coming before Tier 2
    order = {m[1]: (m[0] if m[0] < 2 else m[0] + 1) * 1000 + i for i, m in enumerate(MILESTONES)}
    order.update({f"mam_{t[0]}_{k + 1}": 2000 for t in MAM for k in range(len(t[3]))})
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
    _tree[3][:] = [(n, cost, time, text, needs, [i for i in items if i not in PART_LOCKS and i not in _GONE]
                    + sorted(p for p, m in PART_LOCKS.items() if m == f"mam_{_tree[0]}_{k + 1}"), tokens)
                   for k, (n, cost, time, text, needs, items, tokens) in enumerate(_tree[3])]
_nowhere = set(PART_LOCKS.values()) - {m[1] for m in MILESTONES} - {f"mam_{t[0]}_{k + 1}" for t in MAM for k in range(len(t[3]))}
if _nowhere:
    raise SystemExit(f"part locks name no milestone or MAM node: {sorted(_nowhere)}")

# Equipment the Equipment Workshop builds by hand that machines can make too, the Manufacturer's way (recipes.py):
# item id -> its 1.0 name. Each gets a Core and an Incomplete item, locked with the equipment.
EQUIPMENT_BY_MACHINE = {"siftec:object_scanner": "Object Scanner", "siftec:jetpack": "Jetpack", "siftec:parachute": "Parachute",
                        "siftec:gas_mask": "Gas Mask", "siftec:hazmat_suit": "Hazmat Suit", "siftec:hover_pack": "Hoverpack",
                        "siftec:blade_runners": "Blade Runners"}
EQUIPMENT_CORES, EQUIPMENT_LOCKS = {}, {}
_unlocks_of = {i: m[1] for m in MILESTONES for i in m[6]}
_unlocks_of.update({i: f"mam_{t[0]}_{k + 1}" for t in MAM for k, n in enumerate(t[3]) for i in n[5]})
for _iid, _sat in EQUIPMENT_BY_MACHINE.items():
    _rid = _iid.split(":")[1]
    EQUIPMENT_CORES[_iid] = _rid + "_core"
    PARTS.append((_rid + "_core", _sat + " Core", "minecraft:item/magma_cream", 0x909098))
    PARTS.append(("incomplete_" + _rid, "Incomplete " + _sat, "minecraft:item/magma_cream", 0x606068))
    EQUIPMENT_LOCKS[_iid] = _unlocks_of.get(_iid)
    for _part in (_rid + "_core", "incomplete_" + _rid):
        if _unlocks_of.get(_iid):
            for _m, _ms in enumerate(MILESTONES):
                if _ms[1] == _unlocks_of[_iid]:
                    MILESTONES[_m] = _ms[:6] + (_ms[6] + ["siftec:" + _part],) + _ms[7:]
            for _t in MAM:
                for _k, _n in enumerate(_t[3]):
                    if f"mam_{_t[0]}_{_k + 1}" == _unlocks_of[_iid]:
                        _t[3][_k] = _n[:5] + (_n[5] + ["siftec:" + _part],) + _n[6:]
