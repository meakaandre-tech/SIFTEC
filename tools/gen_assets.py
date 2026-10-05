#!/usr/bin/env python3
"""Writes the mod's blockstates, models, item definitions, lang file and loot tables.
Every texture is borrowed from vanilla or Create for now; nothing is drawn here."""
import json, os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "siftec")
D = os.path.join(ROOT, "data", "siftec")

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")

def item_def(name, model):
    write(f"{A}/items/{name}.json", {"model": {"type": "minecraft:model", "model": model}})

NODES = {  # id: (display name, texture)
    "iron": ("Iron Ore", "minecraft:block/raw_iron_block"),
    "copper": ("Copper Ore", "minecraft:block/raw_copper_block"),
    "limestone": ("Limestone", "create:block/palettes/stone_types/limestone"),
    "coal": ("Coal", "minecraft:block/coal_block"),
    "zinc": ("Zinc Ore", "create:block/raw_zinc_block"),
    "bauxite": ("Bauxite", "minecraft:block/granite"),
    "sam": ("SAM", "minecraft:block/amethyst_block"),
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
    "siftec.scanner.no_nodes": "There are no nodes in this dimension",
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

# miners
write(f"{A}/blockstates/miner_mk1.json", {"variants": {"": {"model": "siftec:block/miner_mk1"}}})
write(f"{A}/models/block/miner_mk1.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "create:block/gearbox_top", "side": "create:block/andesite_casing", "bottom": "create:block/andesite_casing"}})
item_def("miner_mk1", "siftec:block/miner_mk1")

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

write(f"{A}/lang/en_us.json", dict(sorted(lang.items())))

# miners drop themselves
for b in ("portable_miner", "miner_mk1"):
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
          "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}],
          "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
# pickaxe is the right tool for the miners
write(os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json"),
      {"replace": False, "values": ["siftec:portable_miner", "siftec:miner_mk1"]})
