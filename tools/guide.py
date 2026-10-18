"""The guide (work package 13): Satisfactory-style text for every HUB milestone and Wormhole phase, and the Ponder
scenes' own lines for every building and vehicle the Build Gun puts down.

gen_assets.py writes all of it into the lang file and siftec_content.json, and stops if a milestone, phase or building
has no text. The scenes themselves are client/ponder/BuildingScenes.java: a building's lines come first, then the lines
every building shares (PONDER_SHARED) where they apply: its ports, power, node, control panel, cost and unlock.
"""

# ---- milestone and phase text, shown under the milestone's name in the HUB (and as its tooltip): FICSIT's voice
MILESTONE_TEXT = {
    # Tier 0
    "hub_upgrade_1": "Welcome to your new workplace, Pioneer. The HUB now has an Equipment Workshop and a Portable Miner: "
                     "dig by hand until the machines can do it for you. FICSIT believes in you.",
    "hub_upgrade_2": "Ore is only the beginning. The Smelter turns it into ingots, and the HUB gets its first Biomass Burner and "
                     "the Power Lines to carry what it makes.",
    "hub_upgrade_3": "One part at a time is how empires are built. The Constructor presses parts while you get on with "
                     "something more productive, like building more Constructors.",
    "hub_upgrade_4": "Carrying things is a waste of a perfectly good Pioneer. Mk.1 belts move items by themselves, no shaft "
                     "needed, at 60 items a minute.",
    "hub_upgrade_5": "Digging by hand has served its purpose. The Miner Mk.1 sits on a resource node and works around the "
                     "clock, and Storage Containers catch everything it digs.",
    "hub_upgrade_6": "The HUB is complete, and so is your probation. Build the Wormhole Gateway: FICSIT has a destination in "
                     "mind. From now on every milestone takes a little time to ship.",
    # Tier 1
    "base_building": "A factory is only as good as the floor it stands on. Foundations, walls and ramps keep production level, "
                     "tidy and up to FICSIT standards.",
    "logistics": "Items should go where they are needed, not where they happen to fall. Split, merge and lift your belts, and "
                 "measure every shaft while you are at it.",
    "field_research": "This planet is full of things FICSIT does not own yet. The MAM studies them, the Object Scanner finds "
                      "them, and the Map makes sure you find your way back.",
    # Tier 2
    "part_assembly": "Two parts in, one better part out. The Assembler puts components together, and Smart Plating is exactly "
                     "what the Wormhole Gateway is asking for.",
    "obstacle_clearing": "Nature has been slow to recognise FICSIT's claim to this land. Help it along with harvesters and "
                         "ploughs, and burn whatever you clear as Solid Biofuel.",
    "jump_pads": "Walking is for Pioneers with time to spare. Jump Pads put you where you need to be; the Landing Pad makes sure "
                 "you arrive in one piece.",
    "resource_sink_bonus_program": "Waste is only value nobody has collected yet. Feed your surplus to the AWESOME Sink and "
                                   "spend the coupons at the AWESOME Shop.",
    "logistics_mk2": "Faster belts, faster factory, happier FICSIT. Belts with no shaft now run at 120 items a minute.",
    # Tier 3
    "coal_power": "Biomass will only take you so far. Coal Generators burn coal and water for steady power, and pumps and pipes "
                  "bring them the water.",
    "vehicular_transport": "Some resources are inconveniently far away. The Tractor hauls them home, Truck Stations load and "
                           "unload it, and a recorded route means it drives itself.",
    "basic_steel_production": "Iron is fine. Steel is better. The Foundry turns iron and coal into steel for the heavier work "
                              "ahead.",
    "enhanced_asset_security": "The local wildlife does not respect property rights. The Xeno-Basher explains them.",
    # Tier 4
    "ficsit_blueprints": "Good designs deserve to be repeated. Save a section of your factory as a blueprint and build it again "
                         "wherever it is needed.",
    "logistics_mk3": "Throughput is a state of mind. Belts now run at 270 items a minute, and the Industrial Storage Container "
                     "holds what they bring.",
    "advanced_steel_production": "Better steel makes better machines. The Miner Mk.2 digs faster and brings up more from every "
                                 "node.",
    "expanded_power_infrastructure": "Keep power for when you need it and carry it further than ever: Power Storage evens out "
                                     "the grid, and Power Towers span the distance.",
    "contraption_transit": "FICSIT's research shows Pioneers prefer to arrive. Elevators, rope pulleys and gantries move you and "
                           "your cargo up, down and along.",
    # Tier 5
    "jetpack": "Gravity is only a suggestion. The Jetpack lets you ignore it for a while, and your backpack grows to carry more.",
    "oil_processing": "Deep under the surface lies crude oil, the raw material of modern life. Pump it up and refine it into "
                      "plastic, rubber and fuel.",
    "logistics_mk4": "Belts at 480 items a minute, and the Truck for the loads a Tractor cannot manage.",
    "fluid_packaging": "Fluids are hard to carry. Canisters are not. The Packager fills them at one end and empties them at the "
                       "other.",
    "petroleum_power": "Burn fuel, make power, keep growing. The Fuel Generator gives far more power than any coal plant.",
    # Tier 6
    "industrial_manufacturing": "Some products are too complex for an Assembler. The Manufacturer combines up to four inputs "
                                "into the parts the Wormhole needs most.",
    "monorail_train_technology": "Trains move more cargo further than anything else on this planet. Lay the track, build the "
                                 "stations and set the schedules.",
    "railway_signalling": "Two trains on one track is a FICSIT safety incident waiting to happen. Signals keep them apart.",
    "pipeline_engineering_mk2": "More pressure, more flow, more production. Pumps now move twice as much, twice as far.",
    "ficsit_blueprints_mk2": "Bigger factories need bigger blueprints. The Blueprint Designer Mk.2 has room for them.",
    # Tier 7
    "bauxite_refinement": "Bauxite makes aluminum, and aluminum makes everything lighter. A demanding process, and a rewarding "
                          "one.",
    "logistics_mk5": "780 items a minute. FICSIT's engineers had to find a new word for fast.",
    "hazmat_suit": "Some parts of this planet are bad for your health. The Hazmat Suit keeps them on the outside.",
    "hover_pack": "Why walk when you can hover? The Hover Pack keeps you in the air while you work, and your backpack grows once "
                  "more.",
    "control_system_development": "Complex factories need clever parts. The Blender mixes the chemistry, and the Supercomputer "
                                  "does the thinking.",
    # Tier 8
    "aeronautical_engineering": "When roads, rails and belts are not enough, take to the air. Drones carry packages between "
                                "Drone Ports.",
    "nuclear_power": "The most power FICSIT has ever let a Pioneer handle. Mind the waste: it is your responsibility, forever.",
    "advanced_aluminum_production": "Pressurised nitrogen opens a new chapter in aluminum production. Find a well, pressurise "
                                    "it, extract it.",
    "leading_edge_production": "The fastest miner FICSIT has ever built, and the motors and rockets to match.",
    "particle_enrichment": "Smash particles into each other until they become something more valuable. The Particle "
                           "Accelerator handles the smashing.",
    # Tier 9
    "matter_conversion": "Turn one resource into another. The Converter makes scarcity a thing of the past, and SAM is the key.",
    "quantum_encoding": "Production at the scale of the very small. The Quantum Encoder builds with excited photonic matter what "
                        "was impossible yesterday.",
    "ficsit_blueprints_mk3": "Your designs, at a size the Mk.2 could only dream of.",
    "spatial_energy_regulation": "Space is only distance FICSIT has not shortened yet. Portals join the far ends of your factory "
                                 "in an instant.",
    "peak_efficiency": "The height of FICSIT logistics: belts at 1,200 items a minute, and Ficsonium for those who still want "
                       "more.",
    # Wormhole phases, delivered at the Wormhole Gateway
    "phase_1": "The Wormhole Gateway needs proof that this colony is serious. Deliver Smart Plating to raise the ring's cradle and "
               "open Tiers 3 and 4.",
    "phase_2": "The cradle holds. Framework, plating and wiring raise the bottom third of the ring and open Tiers 5 and 6.",
    "phase_3": "A third of the ring stands. Engines and control units raise the next third and open Tiers 7 and 8.",
    "phase_4": "Close the ring and run the energy conduits into it. One last tier opens: Tier 9.",
    "phase_5": "Everything you have built comes down to this. Deliver the final parts and the Gateway opens onto The Sift. "
               "FICSIT thanks you for your continued service.",
}

# ---- each building's own lines in its Ponder scene, shown first (the HUB, the Wormhole Gateway and the vehicles have
# scenes of their own, and their lines are read in the order their scenes use them: see BuildingScenes.java)
BUILDING_GUIDE = {
    "hub": [
        "The HUB is where it all starts: the first thing every Pioneer builds with the Build Gun.",
        "Its terminal shows the milestones, and pays for them from what you carry. Beside it, the Craft Station's hand-cranked "
        "Press and Mixer make your first parts.",
        "Every HUB Upgrade adds to it: beds and lockers, Biomass Burners for power at the back, and at the last upgrade "
        "the freighter's dock tower.",
        "It is the company's base for the whole game. Its upgrades belong to the company, not to the building.",
    ],
    "wormhole_gateway": [
        "The Wormhole Gateway is the colony's goal. It arrives as a bare platform with a delivery terminal and two power "
        "hookups.",
        "Each Wormhole Phase delivered at its terminal opens new HUB tiers and builds more of the ring gate.",
        "The fifth phase closes the circuit: the ring lights up and a portal to The Sift opens inside it.",
    ],
    "portable_miner": [
        "The Portable Miner is a small drill rig for the very first ores. It needs no power at all.",
        "Set it over a resource node: it mines the node each time the ore grows back, and drops what it digs on the ground in "
        "front.",
    ],
    "miner_mk1": [
        "The Miner Mk.1 drills a resource node for as long as it has power, each time the ore grows back.",
        "Ore drops down a chute to the output port at the front, ready for a belt.",
    ],
    "miner_mk2": [
        "The Miner Mk.2 breaks the regrown ore twice as quickly as the Mk.1 and mines with Fortune III, so every node gives "
        "more.",
    ],
    "miner_mk3": [
        "The Miner Mk.3 is FICSIT's fastest miner: it breaks the ore five times as quickly as the Mk.1, with Fortune V. "
        "Its 45 MW takes a second motor, in the extension at the back.",
    ],
    "smelter": [
        "The Smelter melts ore into ingots. Inside, a motor drives an Encased Fan that blows through lava onto a Depot: "
        "Create's bulk blasting.",
    ],
    "constructor": [
        "The Constructor makes one part from one input: a Mechanical Press over a Basin, turned by its own motor.",
    ],
    "storage_container": [
        "The Storage Container holds items in an Item Vault. Belt things in at the back and take them out at the front.",
    ],
    "industrial_storage_container": [
        "The Industrial Storage Container is the bigger Storage Container: two Item Vaults side by side.",
    ],
    "power_pole_mk1": [
        "Power Poles carry power across the factory: each wire from its connector joins two parts of the grid. "
        "A Mk.1 takes up to 4 wires.",
    ],
    "power_pole_mk2": [
        "The Power Pole Mk.2 takes up to 7 wires.",
    ],
    "power_pole_mk3": [
        "The Power Pole Mk.3 takes up to 10 wires.",
    ],
    "power_tower": [
        "The Power Tower lifts the grid high above the ground and takes up to 10 wires, for long runs between distant parts "
        "of the factory.",
    ],
    "power_storage": [
        "Power Storage keeps the power the grid does not use, and gives it back when the generators cannot keep up.",
    ],
    "biomass_burner": [
        "The Biomass Burner burns leaves, wood, mycelia, Biomass and Solid Biofuel for 30 MW. Fuel goes in at the back.",
    ],
    "coal_generator": [
        "The Coal Generator burns coal, Compacted Coal or Petroleum Coke for 75 MW, and takes 45 m3 of water a minute to do "
        "it.",
    ],
    "fuel_generator": [
        "The Fuel Generator burns Fuel, Liquid Biofuel, Turbofuel, Rocket Fuel or Ionized Fuel for 250 MW.",
    ],
    "geothermal_generator": [
        "The Geothermal Generator stands over a geyser and gives 200 MW for free, forever. It only goes down where a geyser "
        "vents.",
    ],
    "nuclear_power_plant": [
        "The Nuclear Power Plant gives 2,500 MW from Uranium or Plutonium Fuel Rods, and takes 240 m3 of water a minute.",
        "Every spent rod leaves nuclear waste behind, which has to go somewhere.",
    ],
    "alien_power_augmenter": [
        "The Alien Power Augmenter adds 500 MW to its grid and boosts the grid's generators by 10%, or 30% while it is fed "
        "Alien Power Matrices.",
    ],
    "assembler": [
        "The Assembler combines two inputs into one part: a Mechanical Mixer over a Basin.",
    ],
    "foundry": [
        "The Foundry makes steel and alloys: a Mechanical Mixer over a Basin, heated by a Blaze Burner the building keeps lit "
        "while it has power.",
    ],
    "refinery": [
        "The Refinery turns crude oil into plastic, rubber, fuel and more: a heated Mixer over a Basin, with fluid ports for "
        "pipes.",
    ],
    "blender": [
        "The Blender mixes fluids and solids for the late-game chemistry: a heated Mixer over a Brass Basin, which holds four "
        "stacks of each item.",
    ],
    "manufacturer": [
        "The Manufacturer builds the most complex parts. The Mixer over the Brass Basin makes the recipe's Core first.",
        "Then the Core travels along a row of Depots, and a Deployer over each adds the last of one input.",
    ],
    "quantum_encoder": [
        "The Quantum Encoder works like the Manufacturer, with two extra steps: a Spout fills the Core with Excited Photonic "
        "Matter first, and an Item Drain takes the Dark Matter Residue off what comes out.",
    ],
    "packager": [
        "The Packager puts fluids into canisters and tanks, and takes them out again: a Spout over a Depot fills, and an Item "
        "Drain empties.",
    ],
    "water_extractor": [
        "The Water Extractor stands in water and pumps 120 m3 a minute into its tank. The cell under its intake has to be a "
        "water source.",
    ],
    "oil_extractor": [
        "The Oil Extractor stands over an oil node and pumps crude oil into its tank. Pure nodes give more, impure ones less.",
    ],
    "resource_well_pressurizer": [
        "The Resource Well Pressurizer stands over a nitrogen well and puts it under pressure, so the Resource Well Extractors "
        "around it can work.",
    ],
    "resource_well_extractor": [
        "The Resource Well Extractor takes nitrogen gas from a well while a Resource Well Pressurizer near it is running.",
    ],
    "particle_accelerator": [
        "The Particle Accelerator smashes particles together for the rarest materials, such as Plutonium and Nuclear Pasta. "
        "It draws a lot of power.",
    ],
    "converter": [
        "The Converter turns one resource into another with the help of SAM, and makes Ficsite, Diamonds and Time Crystals.",
    ],
    "equipment_workshop": [
        "The Equipment Workshop builds equipment by hand from the parts you carry. Use its control panel to open it.",
    ],
    "awesome_sink": [
        "The AWESOME Sink destroys whatever its input port takes, and pays FICSIT Coupons for it. Use it with something in "
        "hand to sink that.",
    ],
    "awesome_shop": [
        "The AWESOME Shop sells cosmetics for FICSIT Coupons, such as the Customizer for painting your buildings. Use its "
        "control panel to open it.",
    ],
    "radar_tower": [
        "The Radar Tower clears the company's map over a wide area around it while it has power.",
    ],
    "truck_station": [
        "The Truck Station loads vehicles from its two Item Vaults, or unloads them into the vaults, while they stand at the "
        "dock in front of it.",
        "Its fuel tank fills up every vehicle that docks. Its terminal switches between loading and unloading.",
    ],
    "drone_port": [
        "The Drone Port is a landing pad on legs with its loading terminal underneath. Use the terminal to pick which of your "
        "company's ports its drone flies to.",
        "Packages go in at the back port and fire charges at the left one; what arrives comes out of the right one. The drone "
        "takes off from the pad with everything waiting, burning fire charges for the distance, and lands on the other pad.",
    ],
    "tractor": [
        "The Tractor is a small, quick hauler with three rows of storage. Right-click it to drive: W and S for throttle, A "
        "and D to steer, Space to brake.",
        "It burns Fuel, Liquid Biofuel or Turbofuel: use a bucket on it, or dock it at a Truck Station. Sneak and right-click "
        "for its storage.",
        "Press V while driving to record a route through Truck Stations. Get out, and it drives the route by itself.",
    ],
    "truck": [
        "The Truck is slower than the Tractor, but carries six rows of storage. Right-click it to drive.",
        "It burns Fuel, Liquid Biofuel or Turbofuel: use a bucket on it, or dock it at a Truck Station. Sneak and right-click "
        "for its storage.",
        "Press V while driving to record a route through Truck Stations. Get out, and it drives the route by itself.",
    ],
    "explorer": [
        "The Explorer is the fastest vehicle on the planet, built for scouting rather than cargo: one row of storage. "
        "Right-click it to drive.",
        "It burns Fuel, Liquid Biofuel or Turbofuel: use a bucket on it, or dock it at a Truck Station. Sneak and right-click "
        "for its storage.",
        "Press V while driving to record a route through Truck Stations. Get out, and it drives the route by itself.",
    ],
}

# ---- lines every building scene can use (Ponder's shared text, siftec.ponder.shared.<key>); %s are filled in by the
# scene. The keys are BuildingScenes.java's.
PONDER_SHARED = {
    "item_in": "Belts and funnels bring items in here",
    "item_out": "Finished items come out here",
    "fluid_in": "Pipes bring fluid in here",
    "fluid_out": "Fluid comes out here, ready for a pipe",
    "power_use": "It runs on power: it draws %s SU from the grid it is wired to",
    "power_make": "It feeds %s MW (%s SU) into the grid it is wired to",
    "power_wire": "Wire it to the grid from the connector on top",
    "fuse": "If the grid takes more than it gets, the fuse blows: sneak and use a generator's control panel to reset it",
    "node": "It stands over a resource node. Mine the node's clump away first; aimed near the node, the Build Gun snaps it on",
    "panel": "Its control panel: use it to see what the building is doing",
    "panel_filter": "Use the control panel with an item to set what it makes, and sneak-use it to clear that",
    "cost": "Build it with the Build Gun from the %s tab: it costs %s",
    "unlock": "It is unlocked by %s",
    "dismantle": "Only the Build Gun's dismantle mode (X) takes it down, giving back its whole cost and whatever is inside",
    "vehicle_cost": "Put it down with the Build Gun from the %s tab: it costs %s",
}

# ---- Ponder tags: one per Build Gun tab, listed in the Ponder index (their icon is the Build Gun)
PONDER_TAGS = {
    "production": ("Production Buildings", "SIFTEC's machines: miners, smelters, constructors and the rest of the factory"),
    "power": ("Power Buildings", "Generators, poles and storage of the power grid"),
    "logistics": ("Logistics Buildings", "Storage, Truck Stations and vehicles"),
    "special": ("Special Buildings", "The HUB, the Wormhole Gateway and the FICSIT services"),
}
