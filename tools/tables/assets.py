"""How the mod's own blocks and items look until (and where) they have art of their own, and the other fixed lists
gen_assets.py writes: block tags, which blocks drop themselves, which mods' items must be locked. The art cut by
tools/import_art.py replaces a stand-in texture wherever it exists (gen_assets.py, at the end)."""

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
    "uranium_ore": ("Uranium Ore", "siftec:block/uranium_ore"),
    "sam_ore": ("SAM Ore", "siftec:block/sam_node_front"),
    "sulfur_ore": ("Sulfur Ore", "siftec:block/sulfur_node_front"),
}
# the fluid nodes' own blocks (unbreakable, never mined), and what the SIFTEC ores drop
FLUID_NODE_IDS = sorted(DEPOSIT[k][2].split(":")[1] for k in DEPOSIT if not DEPOSIT[k][3])
ORE_DROPS = {DEPOSIT[k][2].split(":")[1]: DEPOSIT[k][3] for k in DEPOSIT if DEPOSIT[k][2].startswith("siftec:") and DEPOSIT[k][3]}

# ---- item tags
# Recipes that take "Stone" (where Satisfactory takes Concrete) take any of these
STONES_TAG = ["minecraft:stone", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:deepslate", "minecraft:blackstone",
              "minecraft:basalt"] + [STONE[k][1] for k in STONE_KINDS]
# Satisfactory's Mycelia is any mushroom; the Stinger's remains are what spiders drop (content.SAT_NAMES)
MYCELIA_TAG = ["minecraft:brown_mushroom", "minecraft:red_mushroom"]
SPIDER_REMAINS_TAG = ["minecraft:spider_eye", "minecraft:string"]
# any pickle or jam (the Automated Cooking research takes 20)
PRESERVES_TAG = ["siftec:pickled_" + w for w in ("tomato", "onion", "cabbage", "pumpkin", "carrot", "beetroot", "kelp")] \
    + ["siftec:" + w + "_jam" for w in ("sweet_berry", "glow_berry", "apple", "melon")]

# ---- stand-in looks for items: id -> (texture, tint or None). Their names are in tables/lang.py.
FLAT_ITEMS = {
    "gypsum": ("minecraft:item/bone_meal", None), "toxic_residue": ("minecraft:item/slime_ball", 0x90C020),
    "cardboard_drone": ("create:item/cardboard", None), "blueprint": ("minecraft:item/filled_map", None),
    # the old HUB building rule's planner: kept as an item that does nothing, so old inventories load (no recipe, not in the tab)
    "hub_planner": ("minecraft:item/brush", None),
    "toxic_shot": ("minecraft:item/slime_ball", 0x607018),
    "node_scanner": ("minecraft:item/recovery_compass_16", None), "raw_bauxite": ("minecraft:item/brick", None),
    "sam": ("minecraft:item/amethyst_shard", None), "uranium": ("minecraft:item/emerald", None),
    "object_scanner": ("minecraft:item/compass_16", None),
}
# equipment: (name, stand-in icon from a vanilla item)
EQUIPMENT = {"jetpack": ("Jetpack", "minecraft:item/firework_rocket"), "hover_pack": ("Hover Pack", "minecraft:item/elytra"),
             "parachute": ("Parachute", "minecraft:item/phantom_membrane"), "hazmat_suit": ("Hazmat Suit", "minecraft:item/leather_chestplate"),
             "gas_mask": ("Gas Mask", "minecraft:item/leather_helmet"), "blade_runners": ("Blade Runners", "minecraft:item/iron_boots")}
# the MAM's inhalers and the Elytra Backpack (item/MamItems.java): (name, icon, tint)
MAM_ITEMS = {"protein_inhaler": ("Protein Inhaler", "minecraft:item/potion", 0xE070C0),
             "vitamin_inhaler": ("Vitamin Inhaler", "minecraft:item/potion", 0xF0A040),
             "therapeutic_inhaler": ("Therapeutic Inhaler", "minecraft:item/potion", 0x60D0F0),
             "nutritional_inhaler": ("Nutritional Inhaler", "minecraft:item/potion", 0x80E060),
             "elytra_backpack": ("Elytra Backpack", "minecraft:item/elytra", 0xE8A060)}
# pickles, jams and mead in a bottle: (name, tint); mead is a honey bottle, the rest a potion bottle
PRESERVE_ICONS = {"pickled_tomato": ("Pickled Tomato", 0xC84030), "pickled_onion": ("Pickled Onion", 0xD8C8A8), "pickled_cabbage": ("Pickled Cabbage", 0x90B850),
                  "pickled_pumpkin": ("Pickled Pumpkin", 0xE08828), "pickled_carrot": ("Pickled Carrot", 0xF09030), "pickled_beetroot": ("Pickled Beetroot", 0x902848),
                  "pickled_kelp": ("Pickled Kelp", 0x4C7A3A), "sweet_berry_jam": ("Sweet Berry Jam", 0xB01838), "glow_berry_jam": ("Glow Berry Jam", 0xF0B040),
                  "apple_jam": ("Apple Jam", 0xD86048), "melon_jam": ("Melon Jam", 0xE85868), "mead": ("Mead", 0xE0A030)}
# the AWESOME Shop's coupons, the Customizer, the Xeno-Zapper (held like a tool) and the cups: (texture, tint)
SHOP_ITEMS = {"ficsit_coupon": ("minecraft:item/paper", 0xF0A040), "customizer": ("minecraft:item/brush", 0xF08030),
              "xeno_zapper": ("minecraft:item/blaze_rod", 0x80C0FF), "coffee_cup": ("minecraft:item/flower_pot", 0xF0F0F0),
              "employee_of_the_planet_cup": ("minecraft:item/flower_pot", 0xF0C040)}
HANDHELD = {"xeno_zapper"}

# ---- blocks
# miners and the extractor: Create casings until real models are picked. (name, side texture)
MACHINES = {"blueprint_designer": ("Blueprint Designer Mk.1", "minecraft:block/lapis_block"), "blueprint_designer_mk2": ("Blueprint Designer Mk.2", "minecraft:block/emerald_block"), "blueprint_designer_mk3": ("Blueprint Designer Mk.3", "minecraft:block/diamond_block"), "drone_port": ("Drone Port", "create:block/cardboard_block_side"), "main_portal": ("Main Portal", "minecraft:block/crying_obsidian"), "satellite_portal": ("Satellite Portal", "minecraft:block/obsidian"), "landing_pad": ("Landing Pad", "minecraft:block/hay_block_side"), "claim_marker": ("Claim Marker", "minecraft:block/red_concrete"), "dimensional_depot": ("Dimensional Depot", "create:block/brass_casing"),
            "resource_well_extractor": ("Resource Well Extractor", "create:block/railway_casing")}
# the Drone Port block and the Blueprint Designers are only ever a Build Gun building's terminal: they have no item
BLOCK_ONLY_MACHINES = {"drone_port", "blueprint_designer", "blueprint_designer_mk2", "blueprint_designer_mk3"}
# the HUB and Wormhole Gateway structures (prefab/Templates.java): shell blocks by style, (name, texture)
PREFAB = {
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
# the collectibles in the world (collect/): (stand-in texture, name)
COLLECT = {"mercer_sphere": ("minecraft:block/pink_concrete", "Mercer Sphere"), "somersloop": ("minecraft:block/red_concrete", "Somersloop"),
           "crash_site": ("create:block/railway_casing", "Crash Site Pod")}
# their shapes (collect/CollectibleBlock.java): the sphere a rounded ball floating in its block, the Somersloop a low
# lumpy shell; the pod is a whole block, drawn by the art loop. Each takes its own art's side and top.
COLLECT_ELEMENTS = {
    "mercer_sphere": [([4, 3, 4], [12, 13, 12]), ([3, 4, 4], [13, 12, 12]), ([4, 4, 3], [12, 12, 13])],
    "somersloop": [([3, 0, 4], [13, 5, 12]), ([4, 0, 3], [12, 5, 13]), ([5, 5, 5], [11, 9, 11]), ([6, 9, 6], [10, 10, 10])],
}
# the Boom Box: a cassette player's case, its speakers, handle and tape door
BOOM_BOX_TEXTURES = {"particle": "create:block/andesite_casing", "case": "create:block/andesite_casing", "speaker": "minecraft:block/black_concrete",
                     "handle": "create:block/brass_casing", "tape": "minecraft:block/white_concrete"}
BOOM_BOX_ELEMENTS = [([1, 0, 4], [15, 9, 12], "case"), ([2, 2, 11.9], [6, 7, 12.2], "speaker"), ([10, 2, 11.9], [14, 7, 12.2], "speaker"),
                     ([6.5, 4, 11.9], [9.5, 7, 12.2], "tape"), ([3, 9, 7], [4, 11, 9], "handle"), ([12, 9, 7], [13, 11, 9], "handle"),
                     ([3, 11, 7], [13, 12, 9], "handle")]
# the Golden Cart Assembler: Create's Cart Assembler models, one per rail and powered state
CART_ASSEMBLER_RAILS = ("activator_rail", "controller_rail", "detector_rail", "powered_rail", "regular")
# a Building Port's face, by kind (Andre draws the real ones)
PORT_FACES = {"item_in": "minecraft:block/lime_glazed_terracotta", "item_out": "minecraft:block/orange_glazed_terracotta",
              "fluid_in": "minecraft:block/light_blue_glazed_terracotta", "fluid_out": "minecraft:block/blue_glazed_terracotta"}

# ---- the art (tools/import_art.py): which blocks take it how
# the Blueprint Designers' terminals face out of the building's front side, as its controller does
FRONTED = {"hub", "mam", "resource_well_extractor", "hub_engine", "crash_site_block", "blueprint_designer", "blueprint_designer_mk2", "blueprint_designer_mk3"}
# blocks that turn to face whoever placed them; the Crash Site Pod is set down by world generation, so it does not
TURNS = FRONTED - {"crash_site_block", "hub_engine"}

# ---- loot and block tags
# machines that drop themselves, unless an explosion destroys them
DROPS_ITSELF = ("steel_casing", "main_portal", "satellite_portal", "landing_pad", "claim_marker", "resource_well_extractor", "dimensional_depot",
                "speed_governor")
# the pickaxe is the right tool for the machines (and SIFTEC's ores and the decorations, added by gen_assets.py)
PICKAXE = ["siftec:resource_well_extractor", "siftec:dimensional_depot", "siftec:speed_governor", "siftec:landing_pad", "siftec:steel_casing",
           "siftec:blueprint_designer", "siftec:blueprint_designer_mk2", "siftec:blueprint_designer_mk3", "siftec:drone_port", "siftec:main_portal",
           "siftec:satellite_portal", "siftec:mam", "siftec:claim_marker", "siftec:hub", "siftec:wormhole_gateway"]

# ---- the item ids the pack's data may name: every item of these mods is in tools/known_ids.txt, so a typo stops the
# generator. The items of the mods in LOCKED_MODS are locked by default: each must be unlocked by something, be left
# free on purpose or be switched off (content.MORE_LOCKS, FREE, DISABLED).
CHECKED_MODS = ("minecraft", "create", "cgs", "createdieselgenerators", "create_new_age", "travelersbackpack")
LOCKED_MODS = ("create", "cgs", "createdieselgenerators", "create_new_age", "travelersbackpack")
# a recipe that names one of these mods' items or fluids only loads when that mod is installed
OPTIONAL_MODS = ("cgs", "createdieselgenerators", "farmersdelight", "travelersbackpack")
