"""The AWESOME Sink and Shop (AWESOME Sink and Shop tab): what the Sink pays for each item, the FICSIT Coupon curve,
and the cosmetics the Shop sells with everything they are made of (decorations, signs, materials, emotes, tapes)."""
from .content import C, COLOURS, M, SAT_NAMES, SF, sat_item

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
SINK_VANILLA = {}
for _ids, _p in (
        # junk and basic blocks
        ("dirt coarse_dirt rooted_dirt cobblestone cobbled_deepslate gravel sand red_sand netherrack stone granite diorite andesite deepslate "
         "tuff calcite glass soul_sand soul_soil blackstone basalt end_stone clay mud", 1),
        # logs and wood
        ("stick", 1),
        # plants and crops; pumpkins and melons
        ("wheat carrot potato beetroot sugar_cane cactus kelp bamboo sweet_berries glow_berries apple wheat_seeds beetroot_seeds "
         "pumpkin_seeds melon_seeds melon_slice", 2),
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
        SINK_VANILLA[M + _id] = _p
# storage blocks are 9 of what they hold
for _block, _of in (("iron_block", "iron_ingot"), ("gold_block", "gold_ingot"), ("copper_block", "copper_ingot"), ("emerald_block", "emerald"),
                    ("diamond_block", "diamond"), ("netherite_block", "netherite_ingot"), ("coal_block", "coal"), ("redstone_block", "redstone"),
                    ("lapis_block", "lapis_lazuli"), ("raw_iron_block", "raw_iron"), ("raw_copper_block", "raw_copper"), ("raw_gold_block", "raw_gold")):
    SINK_VANILLA[M + _block] = 9 * SINK_VANILLA[M + _of]
SINK_VANILLA[M + "amethyst_block"] = 4 * SINK_VANILLA[M + "amethyst_shard"]
# music discs, and Create's own parts
for _disc in "13 cat blocks chirp far mall mellohi stal strad ward 11 wait otherside 5 pigstep relic creator creator_music_box precipice tears lava_chicken".split():
    SINK_VANILLA[f"{M}music_disc_{_disc}"] = 500
SINK_VANILLA.update({C + "andesite_alloy": 4, C + "brass_ingot": 20, C + "precision_mechanism": 150})
# tags, tried after the ids
SINK_TAGS = {"#minecraft:planks": 1, "#minecraft:saplings": 2, "#minecraft:flowers": 2,
             "#minecraft:decorated_pot_sherds": 100, "#minecraft:trim_templates": 300, "#c:crops": 2, "#c:foods/cooked_meat": 6,
             "#farmersdelight:meals": 25}
# Satisfactory's Leaves, Wood and Mycelia are tags in the pack (any leaves, any log, any mushroom): Satisfactory's value
# (3, 30 and 10), tried before the tab's own tags
SINK_TAGS = {**{SAT_NAMES[_sat]: SF.ITEMS[_sat]["sink"] for _sat in ("Leaves", "Wood", "Mycelia")}, **SINK_TAGS}
# vanilla tools, armour and weapons: the sum of what they are made of (sticks 1 each)
_MATERIALS = {"wooden": ("#minecraft:planks", 1), "stone": ("cobblestone", 1), "copper": ("copper_ingot", 6), "iron": ("iron_ingot", 2),
              "golden": ("gold_ingot", 20), "diamond": ("diamond", 200)}
_TOOLS = {"sword": (2, 1), "pickaxe": (3, 2), "axe": (3, 2), "shovel": (1, 2), "hoe": (2, 2)}
_ARMOUR = {"helmet": 5, "chestplate": 8, "leggings": 7, "boots": 4}
for _mat, (_part, _each) in _MATERIALS.items():
    for _tool, (_n, _sticks) in _TOOLS.items():
        SINK_VANILLA[f"{M}{_mat}_{_tool}"] = _n * _each + _sticks
    if _mat in ("copper", "iron", "diamond"):
        for _piece, _n in _ARMOUR.items():
            SINK_VANILLA[f"{M}{_mat}_{_piece}"] = _n * _each
for _piece, _n in _ARMOUR.items():
    SINK_VANILLA[f"{M}golden_{_piece}"] = _n * 20
    SINK_VANILLA[f"{M}leather_{_piece}"] = _n * 4
    SINK_VANILLA[f"{M}netherite_{_piece}"] = SINK_VANILLA[f"{M}diamond_{_piece}"] + 2500
for _tool in _TOOLS:
    SINK_VANILLA[f"{M}netherite_{_tool}"] = SINK_VANILLA[f"{M}diamond_{_tool}"] + 2500
SINK_VANILLA.update({M + "bow": 12, M + "crossbow": 3 * 1 + 2 * 3 + 2 + 3, M + "shield": 6 + 2, M + "fishing_rod": 3 + 6, M + "mace": 5000})
SINK_RARITY = {"common": 2, "uncommon": 50, "rare": 500, "epic": 2500}
SINK_ENCHANT = 25
# what the Sink will not take: the radioactive and toxic waste has to be dealt with properly (as Satisfactory's Sink
# refuses nuclear waste), and coupons are what it pays in
SINK_REFUSED = ["siftec:toxic_residue", "siftec:uranium_waste", "siftec:plutonium_waste", "siftec:ficsit_coupon"]
# FICSIT Coupons: Satisfactory 1.0's curve, unchanged (the Sink screen prints them; checked by the selftest)
COUPON_FIRST, COUPON_FIRST_COST, COUPON_LAST, COUPON_CAP = 3, 500, 3000, 249501250



# AWESOME Shop (AWESOME Sink and Shop tab): cosmetics only, bought with FICSIT Coupons, unlocked per company. Each entry:
# (id, name, category, coupons, what it gives). What it gives:
#   customizer       the Customizer (free once the Shop is built; any member takes one from the Shop)
#   swatch           one more colour swatch slot for the Customizer (repeatable, up to SWATCH_MAX)
#   finish:<id>      a paint finish for the Customizer (FINISHES)
#   skin:<id>        a casing skin for Build Gun buildings (SKINS), or a Storage Container skin (STORAGE_SKINS)
#   decor:<id>       a decoration (DECOR): once bought, members take them from the Shop for their part cost
#   cup:<id>         a player cosmetic held in the hand (CUPS), taken the same way
#   sign:<id>        a sign (SIGNS): members take them from the Shop for their part cost, and write on them as on a sign
#   unlock:<id>      Build Gun buildings whose unlock is "shop:<entry id>" (the Billboard Set's two billboards)
#   material:<id>    a foundation, wall or roof material for the Customizer (MATERIALS)
#   pattern:<id>     a floor pattern the Customizer paints on a foundation's top (PATTERNS)
#   emote:<id>       an emote (EMOTES): /emote <id>
#   helmet:<id>      a helmet look (HELMET_SKINS), and trim:<id> a suit trim (SUIT_TRIMS): the Customizer used in the air
#   banner           the company banner designer: a banner used on the Shop becomes the company's; members take copies
#   golden_cart      the Golden Cart Assembler skin: the Customizer turns a Cart Assembler golden, and back
#   boom_box, tape:<id>  the Boom Box (taken for its part cost) and its cassette tapes (taken free)
# An entry with a sixth field is seasonal (a holiday set): the months (1 to 12) it can be bought in; once bought, it
# can be taken all year.
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
DYES = COLOURS

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
# holiday sets (seasonal): FICSMAS in December, the Harvest set in October
_SNOW, _SPRUCE, _RED = "minecraft:block/snow", "minecraft:block/spruce_leaves", "minecraft:block/red_wool"
DECOR += [
    ("ficsmas_tree", "FICSMAS Tree", "holiday", 8, _SPRUCE,
     [([7, 0, 7], [9, 3, 9], "trunk"), ([2, 3, 2], [14, 7, 14]), ([4, 7, 4], [12, 11, 12]), ([6, 11, 6], [10, 14, 10]), ([7, 14, 7], [9, 16, 9], "star")],
     "8 minecraft:spruce_leaves, 1 minecraft:gold_ingot"),
    ("ficsmas_gift", "FICSMAS Gift", "holiday", 0, _RED,
     [([3, 0, 3], [13, 8, 13]), ([7, 0, 2.9], [9, 8.1, 13.1], "ribbon"), ([2.9, 0, 7], [13.1, 8.1, 9], "ribbon"), ([6, 8, 6], [10, 10, 10], "ribbon")],
     "2 minecraft:red_wool, 1 minecraft:gold_nugget"),
    ("ficsmas_snowman", "FICSMAS Snowman", "holiday", 0, _SNOW,
     [([3, 0, 3], [13, 8, 13]), ([4.5, 8, 4.5], [11.5, 13, 11.5]), ([5.5, 13, 5.5], [10.5, 16, 10.5]),
      ([7.5, 14, 10.5], [8.5, 15, 13], "nose")], "4 minecraft:snow_block, 1 minecraft:carrot"),
    ("harvest_lantern", "Harvest Lantern", "holiday", 12, "minecraft:block/pumpkin_side",
     [([7, 0, 7], [9, 6, 9], "stand"), ([4, 6, 4], [12, 13, 12]), ([7.5, 13, 7.5], [8.5, 15, 8.5], "stand")],
     "1 minecraft:carved_pumpkin, 1 minecraft:torch, 2 Iron Rod"),
    ("harvest_scarecrow", "Harvest Scarecrow", "holiday", 0, "minecraft:block/hay_block_side",
     [([7, 0, 7], [9, 10, 9], "pole"), ([5, 6, 6], [11, 12, 10]), ([1, 10, 7], [15, 11, 9], "pole"), ([5.5, 12, 5.5], [10.5, 16, 10.5], "head")],
     "2 minecraft:hay_block, 1 minecraft:carved_pumpkin, 2 #minecraft:planks"),
]
DECOR_TEXTURES.update({
    "ficsmas_tree": {"trunk": "minecraft:block/spruce_log", "star": "minecraft:block/gold_block"},
    "ficsmas_gift": {"ribbon": "minecraft:block/gold_block"},
    "ficsmas_snowman": {"nose": "minecraft:block/orange_concrete"},
    "harvest_lantern": {"stand": "minecraft:block/iron_block", "top": "minecraft:block/pumpkin_top"},
    "harvest_scarecrow": {"pole": "minecraft:block/stripped_spruce_log", "head": "minecraft:block/carved_pumpkin"},
})
DECEMBER, OCTOBER = [12], [10]
# Signs (Signs and billboards): written on like a sign (use one to write), drawn as Create panels. (id, name, part cost,
# panel (from, to) in the model facing south, against the wall at the north, the panel's texture)
SIGNS = [("label_sign", "Label Sign", "1 Iron Sheet", ([0, 4, 0], [16, 12, 1.5]), "create:block/brass_casing"),
         ("display_sign", "Display Sign", "2 Iron Sheet, 2 Wire", ([0, 0, 0], [16, 16, 1.5]), "create:block/andesite_casing")]
# a billboard's sign: its whole face; (id, board width and height in blocks, where the face's middle is from the sign's cell)
BILLBOARD_SIGNS = [("billboard_small_sign", 8, 4, (0, 1.5)), ("billboard_large_sign", 16, 8, (0, 3.5))]
# Foundation, wall and roof materials and floor patterns (the Customizer on a Foundations-tab building).
# material id -> (name, what it is for, {built block: the block it becomes})
FOUNDATION_TOP, FOUNDATION_BODY = "minecraft:light_gray_concrete", "minecraft:gray_concrete"
WALL_BODY, ROOF_BODY = "minecraft:white_concrete", "minecraft:orange_terracotta"
MATERIALS = {
    "concrete": ("Concrete", "foundation", {FOUNDATION_TOP: "minecraft:smooth_stone", FOUNDATION_BODY: "minecraft:stone"}),
    "grip_metal": ("Grip Metal", "foundation", {FOUNDATION_TOP: "create:industrial_iron_block", FOUNDATION_BODY: "minecraft:iron_block"}),
    "coated_concrete": ("Coated Concrete", "foundation", {FOUNDATION_TOP: "minecraft:white_concrete", FOUNDATION_BODY: "minecraft:light_gray_concrete"}),
    "asphalt": ("Asphalt", "foundation", {FOUNDATION_TOP: "minecraft:polished_blackstone", FOUNDATION_BODY: "minecraft:blackstone"}),
    "concrete_wall": ("Concrete Wall", "wall", {WALL_BODY: "minecraft:smooth_stone"}),
    "steel_wall": ("Steel Wall", "wall", {WALL_BODY: "create:industrial_iron_block"}),
    "tar_roof": ("Tar Roof", "roof", {ROOF_BODY: "minecraft:black_terracotta"}),
    "metal_roof": ("Metal Roof", "roof", {ROOF_BODY: "minecraft:iron_block"}),
    "glass_roof": ("Glass Roof", "roof", {ROOF_BODY: "minecraft:glass"}),
}
# floor patterns: painted on a foundation's top in yellow (and black for the Factory Zone), on its 8 by 8 cells
PATTERNS = {"pathway": "Pathway", "factory_zone": "Factory Zone", "dotted_line": "Dotted Line", "solid_line": "Solid Line",
            "arrow": "Arrow", "factory_icon": "Factory Icon", "transportation_icon": "Transportation Icon", "number": "Number"}
# emotes: (id, name, coupons); the Wave is free
EMOTES = [("wave", "Wave", 0), ("point", "Point", 1), ("clap", "Clap", 2), ("facepalm", "Facepalm", 2), ("heart", "Heart", 3), ("dance", "Dance", 3)]
# helmet looks (the equipment model a worn helmet is drawn with) and suit trims (pattern, material; on every armour piece)
HELMET_SKINS = {"gold": ("Gold Helmet", "minecraft:gold", 3), "copper": ("Copper Helmet", "minecraft:copper", 3),
                "diamond": ("Diamond Helmet", "minecraft:diamond", 5), "turtle": ("Turtle Shell Helmet", "minecraft:turtle_scute", 5),
                "netherite": ("Netherite Helmet", "minecraft:netherite", 8)}
SUIT_TRIMS = {"ficsit_orange": ("FICSIT Orange trim", "minecraft:flow", "minecraft:copper", 3),
              "caterium": ("Caterium trim", "minecraft:silence", "minecraft:gold", 3),
              "quartz": ("Quartz trim", "minecraft:wayfinder", "minecraft:quartz", 3),
              "sam": ("SAM trim", "minecraft:spire", "minecraft:amethyst", 3),
              "carbon": ("Carbon trim", "minecraft:snout", "minecraft:netherite", 3)}
# the Boom Box and its cassette tapes (each plays one of the game's songs); the first tape comes free
BOOM_BOX_COST = "2 Iron Sheet, 5 Wire"
TAPES = [("tape_cat", "Cassette Tape: Cat", "minecraft:cat"), ("tape_blocks", "Cassette Tape: Blocks", "minecraft:blocks"),
         ("tape_chirp", "Cassette Tape: Chirp", "minecraft:chirp"), ("tape_far", "Cassette Tape: Far", "minecraft:far"),
         ("tape_mall", "Cassette Tape: Mall", "minecraft:mall"), ("tape_stal", "Cassette Tape: Stal", "minecraft:stal"),
         ("tape_strad", "Cassette Tape: Strad", "minecraft:strad"), ("tape_pigstep", "Cassette Tape: Pigstep", "minecraft:pigstep")]
# the Shop's free things (the Customizer and the tapes, taken without limit once owned) are worth no points: the Sink
# refuses them, so they cannot be taken and sunk over and over
SINK_REFUSED += ["siftec:customizer"] + [f"siftec:{t}" for t, _, _ in TAPES]

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
       ("cup_employee_of_the_planet_cup", "Employee of the Planet Cup", "Player cosmetics", 1, "cup:employee_of_the_planet_cup")] \
    + [("sign_label", "Label Sign Bundle", "Signs", 2, "sign:label_sign"),
       ("sign_display", "Display Sign Bundle", "Signs", 5, "sign:display_sign"),
       ("billboard_set", "Billboard Set", "Signs", 10, "unlock:billboard_set")] \
    + [(f"material_{k}", f"{n} material", f"{w.title()} materials", c, f"material:{k}")
       for (k, (n, w, _)), c in zip(MATERIALS.items(), (3, 5, 6, 4, 3, 5, 2, 3, 5))] \
    + [(f"pattern_{k}", f"{n} floor pattern", "Floor patterns", c, f"pattern:{k}") for (k, n), c in zip(PATTERNS.items(), (6, 2, 4, 5, 1, 3, 3, 2))] \
    + [(f"decor_{d}", n, "Holiday sets", c, f"decor:{d}", m) for d, n, c, m in
       (("ficsmas_tree", "FICSMAS Tree", 5, DECEMBER), ("ficsmas_gift", "FICSMAS Gift", 2, DECEMBER), ("ficsmas_snowman", "FICSMAS Snowman", 3, DECEMBER),
        ("harvest_lantern", "Harvest Lantern", 2, OCTOBER), ("harvest_scarecrow", "Harvest Scarecrow", 3, OCTOBER))] \
    + [(f"emote_{k}", f"{n} emote", "Emotes", c, f"emote:{k}") for k, n, c in EMOTES] \
    + [(f"helmet_{k}", f"{n} look", "Helmet skins", c, f"helmet:{k}") for k, (n, _, c) in HELMET_SKINS.items()] \
    + [(f"trim_{k}", n, "Suit trims", c, f"trim:{k}") for k, (n, _, _, c) in SUIT_TRIMS.items()] \
    + [("company_banner", "Company Banner Designer", "Company", 5, "banner"),
       ("golden_cart", "Golden Cart Assembler skin", "Vehicles", 50, "golden_cart"),
       ("boom_box", "Boom Box", "Music", 3, "boom_box")] \
    + [(t, n, "Music", 0 if i == 0 else 1, f"tape:{t}") for i, (t, n, _) in enumerate(TAPES)]
