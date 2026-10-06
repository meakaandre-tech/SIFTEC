"""The pack's content tables: parts, milestones, wormhole phases. gen_assets.py turns these into the
files the mod reads. Costs are written the way the design doc writes them ("20 Iron Rod, 10 Iron Sheet")."""

# ---------------------------------------------------------------------------------------------------
# Parts that already exist in vanilla or another mod: name -> item id
ALIAS = {
    "Iron Ingot": "minecraft:iron_ingot", "Copper Ingot": "minecraft:copper_ingot",
    "Iron Sheet": "create:iron_sheet", "Copper Sheet": "create:copper_sheet",
    "Concrete": "minecraft:light_gray_concrete", "Coal": "minecraft:coal",
    "Steel Ingot": "cgs:steel_ingot", "Sulfur": "cgs:sulfur",
    "Brass Ingot": "create:brass_ingot", "Brass Sheet": "create:brass_sheet",
    "Electron Tube": "create:electron_tube", "Precision Mechanism": "create:precision_mechanism",
    "Diamond": "minecraft:diamond", "Nether Quartz": "minecraft:quartz",
    "Limestone": "create:limestone", "Raw Iron": "minecraft:raw_iron", "Raw Copper": "minecraft:raw_copper",
    "Raw Zinc": "create:raw_zinc", "Gunpowder": "minecraft:gunpowder", "Cardboard": "create:cardboard",
    "Empty Canister": "createdieselgenerators:canister", "Mechanical Drill": "create:mechanical_drill",
}

# ---------------------------------------------------------------------------------------------------
# Custom parts: id, name, placeholder texture, tint (None = use the texture as it is).
# Every look here is a stand-in built from a vanilla or Create texture until real art is picked.
I, S, ROD, WIRE, NUG = "minecraft:item/iron_ingot", "create:item/iron_sheet", "minecraft:item/bone", "minecraft:item/string", "minecraft:item/iron_nugget"
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
    ("petroleum_coke", "Petroleum Coke", "minecraft:item/coal", 0x9090A0), ("circuit_board", "Circuit Board", "create:item/integrated_circuit", None),
    ("computer", "Computer", "minecraft:item/comparator", None), ("heavy_modular_frame", "Heavy Modular Frame", "minecraft:item/glow_item_frame", None),
    ("modular_engine", "Modular Engine", "minecraft:item/furnace_minecart", None),
    ("adaptive_control_unit", "Adaptive Control Unit", "minecraft:item/repeater", None),
    # tiers 7-8
    ("crushed_bauxite", "Crushed Bauxite", "create:item/crushed_raw_aluminum", None), ("aluminum_scrap", "Aluminum Scrap", NUG, 0xD0D8E8),
    ("aluminum_ingot", "Aluminum Ingot", I, 0xE0E8F8), ("alclad_aluminum_sheet", "Alclad Aluminum Sheet", S, 0xE8D0B8),
    ("aluminum_casing", "Aluminum Casing", "create:item/sturdy_sheet", 0xE0E8F8), ("radio_control_unit", "Radio Control Unit", "create:item/transmitter", None),
    ("iodine_infused_filter", "Iodine Infused Filter", "create:item/filter", 0xB080E0),
    ("supercomputer", "Supercomputer", "minecraft:item/comparator", 0x60C0FF), ("assembly_director_system", "Assembly Director System", "create:item/linked_controller", None),
    ("electromagnetic_control_rod", "Electromagnetic Control Rod", "minecraft:item/blaze_rod", 0x80C0FF),
    ("magnetic_field_generator", "Magnetic Field Generator", "minecraft:item/recovery_compass_16", None),
    ("empty_fluid_tank", "Empty Fluid Tank", "minecraft:item/bucket", 0xE0E8F8), ("packaged_nitrogen_gas", "Packaged Nitrogen Gas", "minecraft:item/powder_snow_bucket", None),
    ("heat_sink", "Heat Sink", "minecraft:item/iron_door", 0xE8C8A8), ("cooling_system", "Cooling System", "minecraft:item/snowball", 0x90D0FF),
    ("fused_modular_frame", "Fused Modular Frame", "minecraft:item/glow_item_frame", 0xA0D0FF), ("turbo_motor", "Turbo Motor", "minecraft:item/tnt_minecart", None),
    ("thermal_propulsion_rocket", "Thermal Propulsion Rocket", "minecraft:item/firework_rocket", None),
    ("copper_powder", "Copper Powder", "minecraft:item/glowstone_dust", 0xE08050), ("pressure_conversion_cube", "Pressure Conversion Cube", "minecraft:item/netherite_scrap", None),
    ("nuclear_pasta", "Nuclear Pasta", "minecraft:item/nether_star", 0xE08050),
    # tier 9
    ("reanimated_sam", "Reanimated SAM", "minecraft:item/amethyst_shard", 0x80F0FF), ("sam_fluctuator", "SAM Fluctuator", "minecraft:item/echo_shard", None),
    ("ficsite_ingot", "Ficsite Ingot", I, 0xF8D048), ("ficsite_trigon", "Ficsite Trigon", "minecraft:item/prismarine_shard", 0xF8D048),
    ("time_crystal", "Time Crystal", "minecraft:item/diamond", 0xF080F0), ("biochemical_sculptor", "Biochemical Sculptor", "minecraft:item/brush", None),
    ("superposition_oscillator", "Superposition Oscillator", "minecraft:item/clock_00", None), ("neural_quantum_processor", "Neural-Quantum Processor", "minecraft:item/ender_eye", None),
    ("ai_expansion_server", "AI Expansion Server", "minecraft:item/music_disc_5", None), ("singularity_cell", "Singularity Cell", "minecraft:item/ender_pearl", 0x8040C0),
    ("ballistic_warp_drive", "Ballistic Warp Drive", "minecraft:item/trident", None),
    # MAM parts
    ("dna_capsule", "DNA Capsule", "minecraft:item/experience_bottle", None), ("quickwire", "Quickwire", WIRE, 0xF0C850),
    ("ai_limiter", "AI Limiter", "minecraft:item/compass_16", None), ("high_speed_connector", "High-Speed Connector", "minecraft:item/shears", 0xF0C850),
    ("fabric", "Fabric", "minecraft:item/paper", 0xD8C8A0), ("gas_filter", "Gas Filter", "create:item/filter", None),
    ("power_shard", "Power Shard", "minecraft:item/amethyst_shard", 0x60E0FF), ("silica", "Silica", "minecraft:item/sugar", None),
    ("quartz_crystal", "Quartz Crystal", "create:item/polished_rose_quartz", 0xF8F8FF), ("crystal_oscillator", "Crystal Oscillator", "minecraft:item/clock_00", 0xF8F0FF),
    ("compacted_coal", "Compacted Coal", "minecraft:item/coal", 0xC0C060), ("brass_blend", "Brass Blend", "create:item/crushed_raw_gold", None),
    # collectibles
    ("blue_power_slug", "Blue Power Slug", "minecraft:item/slime_ball", 0x50A0FF), ("yellow_power_slug", "Yellow Power Slug", "minecraft:item/slime_ball", 0xFFE050),
    ("purple_power_slug", "Purple Power Slug", "minecraft:item/slime_ball", 0xC060FF),
    ("mercer_sphere", "Mercer Sphere", "minecraft:item/ender_pearl", 0xFF80C0), ("somersloop", "Somersloop", "minecraft:item/nautilus_shell", 0xFF6060),
    ("hard_drive", "Hard Drive", "minecraft:item/music_disc_11", None),
    ("excited_photonic_matter", "Excited Photonic Matter", "minecraft:item/glowstone_dust", 0xFFFFFF),
    ("dark_matter_crystal", "Dark Matter Crystal", "minecraft:item/amethyst_shard", 0x402060),
    # half-built parts on a Quantum Encoder line
    ("incomplete_superposition_oscillator", "Incomplete Superposition Oscillator", "minecraft:item/clock_00", 0x808080),
    ("incomplete_neural_quantum_processor", "Incomplete Neural-Quantum Processor", "minecraft:item/ender_eye", 0x808080),
    ("incomplete_ai_expansion_server", "Incomplete AI Expansion Server", "minecraft:item/music_disc_5", 0x808080),
]

# Custom fluids: id, name, colour. They look like tinted water until real textures are picked.
FLUIDS = [
    ("heavy_oil_residue", "Heavy Oil Residue", 0x6A3A8A), ("alumina_solution", "Alumina Solution", 0xD8E0E8),
    ("sulfuric_acid", "Sulfuric Acid", 0xE8E040), ("nitrogen", "Nitrogen", 0xC8E8FF), ("nitric_acid", "Nitric Acid", 0xD8F0A0),
    ("dark_matter_residue", "Dark Matter Residue", 0x301848), ("ignimbrite", "Ignimbrite", 0xF08020), ("turbofuel", "Turbofuel", 0xD03030),
]

# ---------------------------------------------------------------------------------------------------
# Milestones. (tier, id, name, cost, minutes:seconds, unlock text, locked item ids, tokens)
# "items" are the things nobody in the company can craft or place until the milestone is done.
# "tokens" are switches other systems read: scanner:<node>, cap:<rpm>, backpack (+3 slots each time).
C = "create:"
SAILS = [C + c + "_sail" for c in "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()]
TOOLBOXES = [C + c + "_toolbox" for c in "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()]
MILESTONES = [
    (0, "hub_upgrade_1", "HUB Upgrade 1", "10 Iron Rod", "0:00",
     "Portable Miner, Wrench and Goggles, +3 backpack slots",
     ["siftec:portable_miner", C + "wrench", C + "goggles", C + "mechanical_drill"], ["backpack"]),
    (0, "hub_upgrade_2", "HUB Upgrade 2", "20 Iron Rod, 10 Iron Sheet", "0:00",
     "Smelter (Encased Fan), Wire and Cable, copper nodes on the scanner",
     [C + "encased_fan", C + "nozzle", "siftec:wire", "siftec:cable"], ["scanner:copper"]),
    (0, "hub_upgrade_3", "HUB Upgrade 3", "20 Iron Sheet, 20 Iron Rod, 20 Wire", "0:00",
     "Constructor (Press, Saw, Millstone, Basin), Concrete, Screw, Reinforced Iron Plate, limestone nodes on the scanner",
     [C + "mechanical_press", C + "mechanical_saw", C + "depot", C + "millstone", C + "basin", "siftec:screw", "siftec:reinforced_iron_plate"],
     ["scanner:limestone"]),
    (0, "hub_upgrade_4", "HUB Upgrade 4", "75 Iron Sheet, 20 Cable, 10 Concrete", "0:00",
     "Mechanical Belt and funnels, +3 backpack slots", [C + "belt_connector", C + "andesite_funnel"], ["backpack"]),
    (0, "hub_upgrade_5", "HUB Upgrade 5", "75 Iron Rod, 50 Cable, 20 Concrete", "0:00",
     "Miner Mk.1, Item Vault, +3 backpack slots", ["siftec:miner_mk1", C + "item_vault"], ["backpack"]),
    (0, "hub_upgrade_6", "HUB Upgrade 6", "100 Iron Rod, 100 Iron Sheet, 100 Wire, 50 Concrete", "0:00",
     "Wormhole Gateway, flywheel, Biomass. Milestones lock the HUB from here on",
     ["siftec:wormhole_gateway", C + "flywheel", "siftec:biomass"], []),

    (1, "base_building", "Base Building", "200 Concrete, 100 Iron Sheet, 100 Iron Rod", "2:00",
     "Factory building blocks: girders, scaffolding, ladders, casings",
     [C + "metal_girder", C + "andesite_scaffolding", C + "andesite_ladder", C + "copper_ladder", C + "copper_scaffolding", C + "copycat_panel", C + "copycat_step"], []),
    (1, "logistics", "Logistics", "150 Iron Sheet, 150 Iron Rod, 300 Wire", "4:00",
     "Splitter and merger (Andesite Tunnel), Chute, Speedometer and Stressometer",
     [C + "andesite_tunnel", C + "chute", C + "item_hatch", C + "speedometer", C + "stressometer"], []),
    (1, "field_research", "Field Research", "300 Wire, 300 Screw, 100 Iron Sheet", "3:00",
     "MAM, Toolbox, Object Scanner, +3 backpack slots", ["siftec:mam", "siftec:object_scanner"] + TOOLBOXES, ["backpack"]),

    (2, "part_assembly", "Part Assembly", "200 Cable, 200 Iron Rod, 500 Screw, 300 Iron Sheet", "6:00",
     "Assembler (Deployer), Copper Sheet, Rotor, Modular Frame, Smart Plating",
     [C + "deployer", "siftec:rotor", "siftec:modular_frame", "siftec:smart_plating"], []),
    (2, "obstacle_clearing", "Obstacle Clearing", "500 Screw, 100 Cable, 100 Concrete", "3:00",
     "Solid Biofuel, harvesters and ploughs, +3 backpack slots",
     ["siftec:solid_biofuel", C + "mechanical_harvester", C + "mechanical_plough", C + "tree_fertilizer"], ["backpack"]),
    (2, "jump_pads", "Jump Pads", "50 Rotor, 300 Iron Sheet, 150 Cable", "4:00",
     "Jump Pad (Weighted Ejector)", [C + "weighted_ejector"], []),
    (2, "resource_sink_bonus_program", "Resource Sink Bonus Program", "400 Concrete, 500 Wire, 200 Iron Rod, 200 Iron Sheet", "5:00",
     "AWESOME Sink and AWESOME Shop", ["siftec:awesome_sink", "siftec:awesome_shop"], []),
    (2, "logistics_mk2", "Logistics Mk.2", "50 Reinforced Iron Plate, 200 Concrete, 300 Iron Rod, 300 Iron Sheet", "6:00",
     "Speed cap raised from 32 to 64 RPM", [], ["cap:64"]),

    (3, "coal_power", "Coal Power", "150 Reinforced Iron Plate, 50 Rotor, 500 Cable", "8:00",
     "Steam Engine, Blaze Burner, pipes, pumps and tanks, coal nodes on the scanner",
     [C + "steam_engine", C + "blaze_burner", C + "empty_blaze_burner", C + "fluid_pipe", C + "smart_fluid_pipe", C + "mechanical_pump", C + "fluid_tank", C + "hose_pulley"],
     ["scanner:coal"]),
    (3, "vehicular_transport", "Vehicular Transport", "25 Modular Frame, 100 Rotor, 200 Cable, 400 Iron Rod", "4:00",
     "Cart Assembler, Rope Pulley, +3 backpack slots",
     [C + "cart_assembler", C + "rope_pulley", C + "controller_rail", C + "minecart_coupling"], ["backpack"]),
    (3, "basic_steel_production", "Basic Steel Production", "50 Modular Frame, 150 Rotor, 500 Concrete, 1000 Wire", "8:00",
     "Foundry (Mechanical Mixer), Steel Ingot, Steel Beam, Steel Pipe, Versatile Framework",
     [C + "mechanical_mixer", "cgs:steel_ingot", "siftec:steel_beam", "siftec:steel_pipe", "siftec:versatile_framework"], []),
    (3, "enhanced_asset_security", "Enhanced Asset Security", "100 Reinforced Iron Plate, 600 Iron Rod, 1500 Wire", "3:00",
     "Xeno-Basher (Pneumatic Hammer), +3 backpack slots", ["cgs:hammer"], ["backpack"]),

    (4, "ficsit_blueprints", "FICSIT Blueprints", "100 Modular Frame, 200 Steel Beam, 500 Cable, 1000 Concrete", "5:00",
     "Blueprint Designer Mk.1", ["siftec:blueprint_designer"], []),
    (4, "logistics_mk3", "Logistics Mk.3", "200 Steel Beam, 200 Steel Pipe, 400 Reinforced Iron Plate", "5:00",
     "Speed cap raised to 96 RPM", [], ["cap:96"]),
    (4, "advanced_steel_production", "Advanced Steel Production", "100 Steel Pipe, 200 Rotor, 100 Modular Frame, 500 Concrete", "10:00",
     "Miner Mk.2, Crushing Wheels, Encased Industrial Beam, Stator, Motor, Automated Wiring",
     ["siftec:miner_mk2", C + "crushing_wheel", "siftec:encased_industrial_beam", "siftec:stator", "siftec:motor", "siftec:automated_wiring"], []),
    (4, "expanded_power_infrastructure", "Expanded Power Infrastructure", "100 Steel Beam, 50 Encased Industrial Beam, 200 Modular Frame, 2000 Wire", "5:00",
     "Power Tower, Power Storage, windmills, pistons, bearings and other contraption parts",
     ["siftec:power_tower", "siftec:power_storage", C + "windmill_bearing", C + "sail_frame", C + "mechanical_piston", C + "sticky_mechanical_piston",
      C + "mechanical_bearing", C + "clockwork_bearing", C + "gantry_carriage", C + "gantry_shaft", C + "elevator_pulley", C + "linear_chassis",
      C + "radial_chassis", C + "super_glue", C + "sticker", C + "contraption_controls"] + SAILS, []),
    (4, "hypertubes", "Hypertubes", "500 Copper Sheet, 300 Steel Pipe, 50 Encased Industrial Beam", "10:00",
     "Hypertube Entrance and Hypertubes",
     ["create_hypertube:hypertube", "create_hypertube:hypertube_entrance", "create_hypertube:hypertube_accelerator", "create_hypertube:hypertube_junction"], []),

    (5, "jetpack", "Jetpack", "50 Motor, 1000 Cable, 1000 Iron Sheet", "5:00", "Jetpack, +3 backpack slots", ["siftec:jetpack"], ["backpack"]),
    (5, "oil_processing", "Oil Processing", "50 Motor, 100 Encased Industrial Beam, 500 Steel Pipe, 500 Copper Sheet", "12:00",
     "Pumpjack (oil nodes only), distillation, Fluid Valve, Plastic, Rubber, Petroleum Coke, Circuit Board, oil nodes on the scanner",
     ["createdieselgenerators:pumpjack_bearing", "createdieselgenerators:pumpjack_crank", "createdieselgenerators:pumpjack_head",
      "createdieselgenerators:pumpjack_hole", "createdieselgenerators:distillation_controller", "createdieselgenerators:distillation_tank",
      C + "fluid_valve", "siftec:plastic", "siftec:rubber", "siftec:petroleum_coke", "siftec:circuit_board"], ["scanner:oil"]),
    (5, "logistics_mk4", "Logistics Mk.4", "50 Heavy Modular Frame, 100 Computer, 200 Encased Industrial Beam, 400 Rubber", "15:00",
     "Speed cap raised to 128 RPM", [], ["cap:128"]),
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
     "Aluminium chain and Radio Control Unit, bauxite nodes on the scanner",
     ["siftec:crushed_bauxite", "siftec:aluminum_scrap", "siftec:aluminum_ingot", "siftec:alclad_aluminum_sheet", "siftec:aluminum_casing",
      "siftec:radio_control_unit"], ["scanner:bauxite", "scanner:quartz"]),
    (7, "logistics_mk5", "Logistics Mk.5", "100 Alclad Aluminum Sheet, 200 Encased Industrial Beam, 300 Reinforced Iron Plate", "1:00",
     "Speed cap raised to 192 RPM", [], ["cap:192"]),
    (7, "hazmat_suit", "Hazmat Suit", "50 Aluminum Casing, 500 Quickwire, 50 Gas Filter", "5:00",
     "Iodine Infused Filter, +3 backpack slots", ["siftec:iodine_infused_filter"], ["backpack"]),
    (7, "hover_pack", "Hover Pack", "200 Motor, 100 Heavy Modular Frame, 100 Computer, 200 Alclad Aluminum Sheet", "5:00",
     "Hover Pack, +3 backpack slots", ["siftec:hover_pack"], ["backpack"]),

    (8, "aeronautical_engineering", "Aeronautical Engineering", "50 Radio Control Unit, 100 Alclad Aluminum Sheet, 200 Aluminum Casing, 300 Motor", "15:00",
     "Drone Port and Cardboard Drone, Create's package network, Supercomputer, Assembly Director System, sulfur nodes on the scanner",
     ["siftec:drone_port", "siftec:cardboard_drone", C + "packager", C + "package_frogport", C + "repackager", C + "stock_link", C + "stock_ticker",
      C + "redstone_requester", C + "factory_gauge", C + "chain_conveyor", "siftec:supercomputer", "siftec:assembly_director_system"], ["scanner:sulfur"]),
    (8, "geothermal_power", "Geothermal Power", "50 Supercomputer, 200 Heavy Modular Frame, 1000 Cable, 2000 Concrete", "10:00",
     "Geyser Engine, Electromagnetic Control Rod, Magnetic Field Generator",
     ["siftec:geyser_engine", "siftec:electromagnetic_control_rod", "siftec:magnetic_field_generator"], []),
    (8, "advanced_aluminum_production", "Advanced Aluminum Production", "50 Radio Control Unit, 200 Aluminum Casing, 200 Alclad Aluminum Sheet, 300 Wire", "15:00",
     "Resource Well extractor, Heat Sink, Cooling System, Fused Modular Frame, nitrogen nodes on the scanner",
     ["siftec:resource_well_extractor", "siftec:empty_fluid_tank", "siftec:heat_sink", "siftec:cooling_system", "siftec:fused_modular_frame"], ["scanner:nitrogen"]),
    (8, "leading_edge_production", "Leading-edge Production", "50 Fused Modular Frame, 100 Supercomputer, 1000 Steel Pipe", "5:00",
     "Miner Mk.3, Turbo Motor, Thermal Propulsion Rocket", ["siftec:miner_mk3", "siftec:turbo_motor", "siftec:thermal_propulsion_rocket"], []),
    (8, "particle_enrichment", "Particle Enrichment", "400 Electromagnetic Control Rod, 400 Cooling System, 200 Fused Modular Frame, 100 Turbo Motor", "20:00",
     "Particle Accelerator, Copper Powder, Pressure Conversion Cube, Nuclear Pasta",
     ["siftec:particle_accelerator", "siftec:copper_powder", "siftec:pressure_conversion_cube", "siftec:nuclear_pasta"], []),

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
     "Speed cap raised to 256 RPM", [], ["cap:256"]),
]

# Switched off for good: they can be neither crafted nor placed.
DISABLED = [C + "water_wheel", C + "large_water_wheel", C + "blaze_cake", C + "blaze_cake_base", C + "creative_blaze_cake",
            C + "chromatic_compound", C + "schematic_table", C + "schematicannon", C + "empty_schematic", C + "schematic_and_quill"]

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
        out.append((name if ":" in name else table[name], int(count)))
    return out
