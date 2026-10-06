#!/usr/bin/env python3
"""Writes the mod's blockstates, models, item definitions, lang file and loot tables.
Every texture is borrowed from vanilla or Create for now; nothing is drawn here."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import content

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

data = {"parts": [], "milestones": [], "phases": []}
for pid, name, tex, tint in content.PARTS:
    data["parts"].append({"id": pid})
    lang[f"item.siftec.{pid}"] = name
    write(f"{A}/models/item/{pid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": tex}})
    item_def(pid, f"siftec:item/{pid}", tint)
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
for i in content.ALIAS.values(): check(i)
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
for b in ("portable_miner", "miner_mk1"):
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
          "entries": [{"type": "minecraft:item", "name": f"siftec:{b}"}],
          "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
# pickaxe is the right tool for the miners
write(os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json"),
      {"replace": False, "values": ["siftec:portable_miner", "siftec:miner_mk1", "siftec:hub", "siftec:wormhole_gateway"]})
