#!/usr/bin/env python3
"""Writes the mod's blockstates, models, item definitions, lang file, loot tables, tags, recipes and
siftec_content.json (the file the mod reads at start-up) from the tables in tools/tables/, then runs the recipe
checkers on what it wrote. Textures come from tools/import_art.py; anything without one borrows a vanilla or Create
texture.

    cd /tmp && python3 -I -B <repo>/tools/gen_assets.py"""
import json
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_recipes
import recipes
import structures
from common import ASSETS as A, DATAPACK as D, RESOURCES as ROOT, TOOLS, WRITTEN, write_json as write
from models import (NORTH_FIRST, SOUTH_FIRST, block_model, blockstate, cube_all, cube_bottom_top, drops_itself, flat_item, item_def,
                    single_blockstate, turned, write_tag)
from tables import content, guide, shop
from tables.assets import (BLOCK_ONLY_MACHINES, BOOM_BOX_ELEMENTS, BOOM_BOX_TEXTURES, CART_ASSEMBLER_RAILS, CHECKED_MODS, COLLECT,
                           COLLECT_ELEMENTS, DEPOSIT, DROPS_ITSELF, EQUIPMENT, FLAT_ITEMS, FLUID_NODE_IDS, FRONTED, HANDHELD,
                           LOCKED_MODS, MACHINES, MAM_ITEMS, MYCELIA_TAG, NODE_TYPES, OPTIONAL_MODS, ORE_DROPS, PICKAXE, PORT_FACES,
                           PREFAB, PRESERVE_ICONS, PRESERVES_TAG, SHOP_ITEMS, SIFTEC_ORES, SPIDER_REMAINS_TAG, STONE, STONES_TAG, TURNS)
from tables.lang import DIRECTIONS, LANG, MONTHS
from tables.recipe_tables import MACHINE_OF, REMOVED, SOURCES, WORKSHOP

lang = dict(LANG)
for d, n in DIRECTIONS.items():
    lang[f"siftec.direction.{d}"] = n

# ---- every item id of the game and of the mods the pack's data names (items only: blocks without an item are not
# in it), so a typo or a renamed item stops the generator instead of loading as nothing
with open(os.path.join(TOOLS, "known_ids.txt")) as _f:
    KNOWN = set(_f.read().split())
UNKNOWN = []


def check(item, where=""):
    if item.split(":")[0] in CHECKED_MODS and item not in KNOWN:
        UNKNOWN.append(f"{item} ({where})")


def cost_list(cost, where=None):
    """A cost as siftec_content.json stores it; every item is checked first when where says what it is the cost of."""
    parsed = content.parse_cost(cost)
    if where is not None:
        for c, n in parsed:
            check(c, where)
    return [{"item": c, "count": n} for c, n in parsed]


def seconds(time):
    """ "m:ss" -> seconds """
    m, sec = time.split(":")
    return int(m) * 60 + int(sec)


# ---- resource nodes (the Nodes and World tab): patches, the fluid nodes' blocks and SIFTEC's ores (tables/assets.py)
for t, name in NODE_TYPES.items():
    lang[f"siftec.node.{t}"] = name
for key, (name, block, side, top) in STONE.items():
    b = f"{key}_patch"
    lang[f"block.siftec.{b}"] = f"{name} Patch"
    single_blockstate(b)
    block_model(b, {"parent": "minecraft:block/cube_column", "textures": {"side": side, "end": top}})
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
        block_model(path, cube_bottom_top("siftec:block/oil_well_top", side, top))
    else:
        block_model(path, cube_all("minecraft:block/packed_ice"))
    blockstate(path, {f"purity={p}": {"model": f"siftec:block/{path}"} for p in ("impure", "normal", "pure")})
    item_def(path, f"siftec:block/{path}")
for b, (name, tex) in SIFTEC_ORES.items():
    lang[f"block.siftec.{b}"] = name
    single_blockstate(b)
    block_model(b, cube_all(tex))
    item_def(b, f"siftec:block/{b}")
    # vanilla's ore loot: Silk Touch keeps the block; otherwise the resource, more with Fortune by the ore formula (a
    # pickaxe's, or a miner's built-in Fortune III or V), less from an explosion (the same shape as the game's own
    # iron_ore.json)
    write(f"{D}/loot_table/blocks/{b}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:alternatives", "children": [
        {"type": "minecraft:item", "condition": "minecraft:tool/can_silk_touch", "name": f"siftec:{b}"},
        {"type": "minecraft:item", "modifier": [
            {"type": "minecraft:apply_bonus", "enchantment": "minecraft:fortune", "formula": "minecraft:ore_drops"},
            {"type": "minecraft:explosion_decay"}], "name": ORE_DROPS[b]}]}]}], "random_sequence": f"siftec:blocks/{b}"})
write_tag("siftec", "item", "stones", STONES_TAG)
write_tag("siftec", "item", "mycelia", MYCELIA_TAG)
write_tag("siftec", "item", "spider_remains", SPIDER_REMAINS_TAG)
write_tag("siftec", "item", "preserves", PRESERVES_TAG)

# ---- the mod's own blocks and items with stand-in looks
for b, (name, casing) in MACHINES.items():
    lang[f"block.siftec.{b}"] = name
    single_blockstate(b)
    block_model(b, cube_bottom_top("create:block/gearbox_top", casing, casing))
    if b not in BLOCK_ONLY_MACHINES:
        item_def(b, f"siftec:block/{b}")
for iid, (tex, tint) in FLAT_ITEMS.items():
    flat_item(iid, tex, tint)
for eid, (ename, etex) in EQUIPMENT.items():
    lang[f"item.siftec.{eid}"] = ename
    flat_item(eid, etex)
for eid, (ename, etex, tint) in MAM_ITEMS.items():
    lang[f"item.siftec.{eid}"] = ename
    flat_item(eid, etex, tint)
# the Elytra Backpack counts as a backtank for Create's diving gear and air-hungry tools
write_tag("create", "item", "pressurized_air_sources", ["siftec:elytra_backpack"])
for fid, (fname, tint) in PRESERVE_ICONS.items():
    lang[f"item.siftec.{fid}"] = fname
    flat_item(fid, "minecraft:item/honey_bottle" if fid == "mead" else "minecraft:item/potion", tint)
single_blockstate("steel_casing")
block_model("steel_casing", cube_all("create:block/railway_casing"))
item_def("steel_casing", "siftec:block/steel_casing")
# the Speed Governor borrows the Gearshift's model until it has its own
blockstate("speed_governor", {
    "axis=x": {"model": "create:block/gearshift/block", "x": 90, "y": 90}, "axis=y": {"model": "create:block/gearshift/block"},
    "axis=z": {"model": "create:block/gearshift/block", "x": 90, "y": 180}})
item_def("speed_governor", "create:block/gearshift/item")

# ---- the HUB and Wormhole Gateway structures (prefab/Templates.java): shell blocks by style, and the HUB's parts
for style, (name, tex) in PREFAB.items():
    b = f"prefab_{style}"
    lang[f"block.siftec.{b}"] = name
    single_blockstate(b)
    if style == "step":
        block_model(b, {"parent": "minecraft:block/slab", "textures": {"bottom": tex, "top": tex, "side": tex}})
    else:
        block_model(b, cube_all(tex))
blockstate("hub_bed", {f"facing={d},part={part}": turned(f"minecraft:block/orange_bed_{part}", y) for d, y in NORTH_FIRST for part in ("foot", "head")})
single_blockstate("personal_storage", "minecraft:block/barrel")
# the built-in Biomass Burners keep the old HUB Engine's look until they have their own
blockstate("hub_burner", {f"facing={d}": turned("siftec:block/hub_engine", y) for d, y in NORTH_FIRST})
write_tag("minecraft", "block", "beds", ["siftec:hub_bed"])

# ---- parts, milestones and phases: one file the mod reads at start-up
data = {"parts": [], "milestones": [], "phases": [], "disabled": content.DISABLED}
for i in content.DISABLED:
    check(i, "disabled")
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
    flat_item(pid, tex, tint)
# the tier tabs show the tier's first milestones in their tooltip
for tier, mid, name, cost, time, text, items, tokens in content.MILESTONES:
    for i in items:
        check(i, mid)
    data["milestones"].append({"id": mid, "tier": tier, "cost": cost_list(cost, mid + " cost"), "seconds": seconds(time), "items": items,
                               "tokens": tokens})
    lang[f"siftec.milestone.{mid}"] = name
    lang[f"siftec.milestone.{mid}.unlocks"] = text
for mid, name, cost, text in content.PHASES:
    data["phases"].append({"id": mid, "tier": -1, "cost": cost_list(cost), "seconds": 0, "items": [], "tokens": []})
    lang[f"siftec.milestone.{mid}"] = name
    lang[f"siftec.milestone.{mid}.unlocks"] = text
# every milestone and phase has its Satisfactory-style text (tools/tables/guide.py), shown in the HUB under its name
for m in data["milestones"] + data["phases"]:
    if m["id"] not in guide.MILESTONE_TEXT:
        raise SystemExit(f"milestone {m['id']}: no text in tools/tables/guide.py (MILESTONE_TEXT)")
    m["desc"] = True
    lang[f"siftec.milestone.{m['id']}.desc"] = guide.MILESTONE_TEXT[m["id"]]
_described = {m["id"] for m in data["milestones"] + data["phases"]}
if set(guide.MILESTONE_TEXT) - _described:
    raise SystemExit(f"text for milestones that do not exist: {sorted(set(guide.MILESTONE_TEXT) - _described)}")

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
    for i in items:
        check(i, nid)
    data["milestones_mam"].append({"id": nid, "tree": tree_id, "cost": cost_list(cost, nid + " cost"), "seconds": seconds(time),
                                   "items": items, "tokens": tokens, "needs": needs})
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
for i in content.FREE:
    check(i, "FREE")
accounted = set(placed) | set(content.FREE) | set(content.DISABLED)
both = sorted((set(placed) | set(content.DISABLED)) & set(content.FREE))
if both:
    raise SystemExit(f"both locked and free: {both}")
loose = sorted(i for i in KNOWN if i.split(":")[0] in LOCKED_MODS and i not in accounted)
if loose:
    raise SystemExit(f"{len(loose)} items have no milestone and are not listed as free: {loose}")
single_blockstate("mam")
block_model("mam", cube_bottom_top("minecraft:block/lodestone_top", "create:block/copper_casing", "create:block/copper_casing"))
item_def("mam", "siftec:block/mam")
drops_itself("mam")

# ---- Mercer Spheres, Somersloops and crash sites in the world (collect/)
SHAPED_COLLECT = set()
for cid, (tex, name) in COLLECT.items():
    bid = f"{cid}_block"
    lang[f"block.siftec.{bid}"] = name
    single_blockstate(bid)
    if cid not in COLLECT_ELEMENTS:
        block_model(bid, cube_all(tex))
        continue
    SHAPED_COLLECT.add(bid)
    art = os.path.exists(os.path.join(A, "textures", "block", bid + "_side.png"))
    side = f"siftec:block/{bid}_side" if art else tex
    top = f"siftec:block/{bid}_top" if art else tex
    elements = [{"from": f, "to": t, "faces": {**{d: {"texture": "#side"} for d in ("north", "south", "east", "west")},
                                                 "up": {"texture": "#top"}, "down": {"texture": "#side"}}} for f, t in COLLECT_ELEMENTS[cid]]
    block_model(bid, {"parent": "minecraft:block/block", "textures": {"particle": side, "side": side, "top": top}, "elements": elements})

# ---- Converter and Particle Accelerator: 1.0's recipes (content.PROCESSORS), alternates with their lock. The recipes
# only: the Converter and Particle Accelerator are Build Gun buildings (the old single blocks are gone).
data["processors"] = {}
for pid, (pname, recipe_list) in content.PROCESSORS.items():
    out = []
    for p in recipe_list:
        r = {"in": [{"item": recipes.made(n), "count": c} for n, c in p["in"]], "seconds": round(p["seconds"])}
        for n, c in p["in"]:
            check(recipes.made(n), pid)
        if p["fluid_in"]:
            r["fluid_in"] = {"fluid": recipes.FLUID_IDS[p["fluid_in"][0]], "mb": p["fluid_in"][1]}
        if p["out"]:
            r["out"] = {"item": recipes.made(p["out"][0]), "count": p["out"][1]}
        if p.get("also"):
            r["also"] = {"item": recipes.made(p["also"][0]), "count": p["also"][1]}
        if p["fluid_out"]:
            r["fluid_out"] = {"fluid": recipes.FLUID_IDS[p["fluid_out"][0]], "mb": p["fluid_out"][1]}
        if p["alt"]:
            r["alt"] = "alt_" + p["alt"]
        # how the building's machines run it (recipes.processors): the Core its Mixer makes, the Deployer's steps, what the
        # fan haunts or the Crushing Wheels crush, the charged cell its Item Drain empties, and Satisfactory's MW over the
        # recipe's cycle
        ch = content.PROCESSOR_CHAINS[(pid, p["name"])]
        if ch["core"]:
            r["core"] = "siftec:" + ch["core"]
        if ch.get("crush"):
            r["crush"] = "siftec:" + ch["crush"]
        if ch.get("haunt"):
            r["haunt"] = ch["haunt"]
        if ch["steps"]:
            r["steps"] = [{"on": on, "apply": recipes.item(a), "makes": m} for on, a, m in ch["steps"]]
        if ch["charged"]:
            r["charged"] = "siftec:" + ch["charged"]
        if ch["batch"] > 1:
            r["batch"] = ch["batch"]
        r["mw"] = list(p["mw"])
        r["name"] = p["name"]
        out.append(r)
    data["processors"][pid] = out

# ---- AWESOME Sink and Shop (sink/, the AWESOME Sink and Shop tab; tables/shop.py)
data["sink_points"] = {recipes.made(n): p for n, p in shop.SINK_POINTS.items()}
for i, p in shop.SINK_VANILLA.items():
    check(i, "sink")
    data["sink_points"].setdefault(i, p)   # a Satisfactory value wins
data["sink_tags"] = [{"tag": t[1:], "points": p} for t, p in shop.SINK_TAGS.items()]
data["sink_rarity"] = shop.SINK_RARITY
data["sink_enchant"] = shop.SINK_ENCHANT
data["sink_refused"] = shop.SINK_REFUSED
data["coupons"] = {"first": shop.COUPON_FIRST, "first_cost": shop.COUPON_FIRST_COST, "last": shop.COUPON_LAST, "cap": shop.COUPON_CAP}
data["shop"] = [dict({"id": e[0], "category": e[2], "coupons": e[3], "gives": e[4]}, **({"season": e[5]} if len(e) > 5 else {})) for e in shop.SHOP]
data["swatch_max"] = shop.SWATCH_MAX
data["dyes"] = shop.DYES
data["finishes"] = {k: b for k, (n, b) in shop.FINISHES.items()}
data["skins"] = {k: b for k, (n, b) in shop.SKINS.items()}
data["storage_skins"] = {k: b for k, (n, b) in shop.STORAGE_SKINS.items()}
for k, (n, b) in shop.FINISHES.items():
    lang[f"siftec.finish.{k}"] = n
    if "{" not in b:
        check(b, "finish")
for k, (n, b) in list(shop.SKINS.items()) + list(shop.STORAGE_SKINS.items()):
    lang[f"siftec.skin.{k}"] = n
    check(b, "skin")
for i, name, cat, n, g, *_ in shop.SHOP:
    lang[f"siftec.shop.entry.{i}"] = name
    lang[f"siftec.shop.category.{cat.lower().replace(' ', '_')}"] = cat
# every Shop entry gives something the pack has
_decor_ids = {d[0] for d in shop.DECOR}
_cup_ids = {c[0] for c in shop.CUPS}
_shop_unlocks = {b[4][5:] for b in content.BUILDINGS if b[4].startswith("shop:")}
for i, name, cat, n, g, *_ in shop.SHOP:
    kind, _, arg = g.partition(":")
    ok = (kind in ("customizer", "swatch", "banner", "golden_cart", "boom_box") and not arg) or (kind == "finish" and arg in shop.FINISHES) or \
         (kind == "skin" and (arg in shop.SKINS or arg in shop.STORAGE_SKINS)) or (kind == "decor" and arg in _decor_ids) or (kind == "cup" and arg in _cup_ids) or \
         (kind == "sign" and arg in {x[0] for x in shop.SIGNS}) or (kind == "unlock" and arg == i and arg in _shop_unlocks) or \
         (kind == "material" and arg in shop.MATERIALS) or (kind == "pattern" and arg in shop.PATTERNS) or \
         (kind == "emote" and arg in {x[0] for x in shop.EMOTES}) or (kind == "helmet" and arg in shop.HELMET_SKINS) or \
         (kind == "trim" and arg in shop.SUIT_TRIMS) or (kind == "tape" and arg in {x[0] for x in shop.TAPES})
    if not ok:
        raise SystemExit(f"AWESOME Shop entry {i}: gives {g!r}, which is nothing the pack has")
# the decorations, authored facing south (the statues look at whoever put them down)
data["decor"] = []
for did, name, cat, light, tex, elements, cost in shop.DECOR:
    decor_cost = cost_list(cost, "decor " + did)
    boxes = [e[:2] for e in elements]
    for e in elements:
        if isinstance(e[-1], str) and e[-1] not in shop.DECOR_TEXTURES.get(did, {}):
            raise SystemExit(f"decor {did}: an element is drawn with {e[-1]!r}, which DECOR_TEXTURES does not give it")
    lo = [min(b[0][i] for b in boxes) for i in range(3)]
    hi = [max(b[1][i] for b in boxes) for i in range(3)]
    # one box round the whole thing, made even round the middle so it fits whichever way the block faces
    r = max(8 - lo[0], hi[0] - 8, 8 - lo[2], hi[2] - 8)
    shape = [max(0, 8 - r), max(0, lo[1]), max(0, 8 - r), min(16, 8 + r), min(16, hi[1]), min(16, 8 + r)]
    data["decor"].append({"id": did, "light": light, "shape": shape, "cost": decor_cost})
    lang[f"block.siftec.{did}"] = name
    textures = {"particle": tex, "all": tex}
    textures.update(shop.DECOR_TEXTURES.get(did, {}))
    els = []
    for e in elements:
        own = e[-1] if isinstance(e[-1], str) else None
        e = e[:-1] if own else e
        faces = {f: {"texture": "#" + (own or "all")} for f in ("north", "south", "east", "west", "down")}
        faces["up"] = {"texture": "#" + (own or ("top" if "top" in textures else "all"))}
        el = {"from": e[0], "to": e[1], "faces": faces}
        if len(e) > 2:
            axis, angle, origin = e[2]
            el["rotation"] = {"axis": axis, "angle": angle, "origin": origin}
        if light:
            el["light_emission"] = light
        els.append(el)
    block_model(did, {"parent": "minecraft:block/block", "textures": textures, "elements": els})
    blockstate(did, {f"facing={d}": turned(f"siftec:block/{did}", y) for d, y in SOUTH_FIRST})
    item_def(did, f"siftec:block/{did}")
    drops_itself(did)
# signs: a panel against the wall with a white face, written on like a sign
data["signs"] = []
for sid, name, cost, panel, tex in shop.SIGNS:
    data["signs"].append({"id": sid, "cost": cost_list(cost, "sign " + sid)})
    lang[f"block.siftec.{sid}"] = name
    faces = {f: {"texture": "#side"} for f in ("north", "east", "west", "up", "down")}
    faces["south"] = {"texture": "#face"}
    block_model(sid, {"parent": "minecraft:block/block", "textures": {"particle": tex, "side": tex, "face": "minecraft:block/white_concrete"},
                      "elements": [{"from": panel[0], "to": panel[1], "faces": faces},
                                   {"from": [panel[0][0] + 1, panel[0][1] + 1, panel[1][2]], "to": [panel[1][0] - 1, panel[1][1] - 1, panel[1][2] + 0.01],
                                    "faces": {"south": {"texture": "#face"}}}]})
    item_def(sid, f"siftec:block/{sid}")
    drops_itself(sid)
for bid, w, h, _ in shop.BILLBOARD_SIGNS:
    # it hangs on the rim's bottom row and draws nothing itself: only its text shows, on the board's face
    block_model(bid, {"parent": "minecraft:block/block", "textures": {"particle": "create:block/industrial_iron_block"}})
    lang[f"block.siftec.{bid}"] = "Billboard"
for sid in [x[0] for x in shop.SIGNS] + [x[0] for x in shop.BILLBOARD_SIGNS]:
    blockstate(sid, {f"facing={d},waterlogged={wl}": turned(f"siftec:block/{sid}", y) for d, y in SOUTH_FIRST for wl in ("false", "true")})
# the Boom Box, with a light that shows while it plays
data["boom_box"] = {"cost": cost_list(shop.BOOM_BOX_COST, "boom box")}
for playing in (False, True):
    els = [{"from": a, "to": b, "faces": {f: {"texture": "#" + t} for f in ("north", "south", "east", "west", "up", "down")}}
           for a, b, t in BOOM_BOX_ELEMENTS]
    if playing:
        els.append({"from": [7, 5, 12.2], "to": [9, 6, 12.3], "faces": {"south": {"texture": "#handle"}}, "light_emission": 8})
    block_model("boom_box_playing" if playing else "boom_box", {"parent": "minecraft:block/block", "textures": BOOM_BOX_TEXTURES, "elements": els})
blockstate("boom_box", {f"facing={d},has_record={r}": turned(f"siftec:block/boom_box{'_playing' if r == 'true' else ''}", y)
                        for d, y in SOUTH_FIRST for r in ("false", "true")})
item_def("boom_box", "siftec:block/boom_box")
drops_itself("boom_box")
data["tapes"] = {t: song for t, _, song in shop.TAPES}
for t, name, song in shop.TAPES:
    lang[f"item.siftec.{t}"] = name
    flat_item(t, "minecraft:item/music_disc_" + song.split(":")[1])
# the Golden Cart Assembler: Create's models in gold (client/CosmeticsClient.java picks them for a golden Cart Assembler)
for rail in CART_ASSEMBLER_RAILS:
    for powered in ("", "_powered"):
        name = f"block_{rail}{powered}"
        block_model(f"golden_cart_assembler/{name}", {"parent": f"create:block/cart_assembler/{name}",
                    "textures": {"4": "minecraft:block/gold_block", "6": "minecraft:block/raw_gold_block",
                                 "translation_chassis_side": "minecraft:block/gold_block", "particle": "minecraft:block/gold_block"}})
data["materials"] = {k: {"kind": w, "blocks": b} for k, (n, w, b) in shop.MATERIALS.items()}
for k, (n, w, b) in shop.MATERIALS.items():
    lang[f"siftec.material.{k}"] = n
    for to in b.values():
        check(to, "material " + k)
data["patterns"] = list(shop.PATTERNS)
for k, n in shop.PATTERNS.items():
    lang[f"siftec.pattern.{k}"] = n
data["emotes"] = [e[0] for e in shop.EMOTES]
data["helmet_skins"] = {k: a for k, (n, a, c) in shop.HELMET_SKINS.items()}
data["suit_trims"] = {k: {"pattern": p, "material": m} for k, (n, p, m, c) in shop.SUIT_TRIMS.items()}
for m, name in enumerate(MONTHS, 1):
    lang[f"siftec.month.{m}"] = name
data["cups"] = []
for cid, name, cost, needs in shop.CUPS:
    data["cups"].append({"id": cid, "needs": needs, "cost": cost_list(cost, "cup " + cid)})
    lang[f"item.siftec.{cid}"] = name
for iid, (tex, tint) in SHOP_ITEMS.items():
    flat_item(iid, tex, tint, "minecraft:item/handheld" if iid in HANDHELD else "minecraft:item/generated")
for i in content.ALIAS.values():
    check(i, "alias")
for i, cost in WORKSHOP:
    check(i, "workshop")
    for c, n in content.parse_cost(cost):
        check(c, "workshop " + i)

# ---- fluids
data["fluids"] = []
for fid, name, colour in content.FLUIDS:
    data["fluids"].append({"id": fid, "color": colour})
    lang[f"block.siftec.{fid}"] = name
    lang[f"fluid.siftec.{fid}"] = name
    lang[f"item.siftec.{fid}_bucket"] = name + " Bucket"
    single_blockstate(fid, "siftec:block/fluid")
    # the game's own empty bucket, with the liquid in its opening tinted the fluid's colour
    write(f"{A}/models/item/{fid}_bucket.json", {"parent": "minecraft:item/generated", "textures": {
        "layer0": "minecraft:item/bucket", "layer1": "siftec:item/fluid_in_bucket"}})
    write(f"{A}/items/{fid}_bucket.json", {"model": {"type": "minecraft:model", "model": f"siftec:item/{fid}_bucket",
          "tints": [{"type": "minecraft:constant", "value": -1}, {"type": "minecraft:constant", "value": colour}]}})
block_model("fluid", {"textures": {"particle": "minecraft:block/water_still"}})
# The Sift's ichor ships no blockstate for its fluid block, so the client warns once per level; like vanilla's water
# and lava, every level gets an empty model with only a particle texture (the fluid renderer draws the liquid)
_sift = os.path.join(ROOT, "assets", "the_sift")
write(f"{_sift}/blockstates/ichor.json", {"variants": {"": {"model": "the_sift:block/ichor"}}})
write(f"{_sift}/models/block/ichor.json", {"textures": {"particle": "minecraft:block/water_still"}})

# ---- recipes
built = recipes.build()
shutil.rmtree(f"{D}/recipe", ignore_errors=True)
part_ids = {"siftec:" + pid for pid, _, _, _ in content.PARTS}
for path, body in built.items():
    if body["type"] == "create:sequenced_assembly" and body["transitional_item"]["id"] not in part_ids:
        raise SystemExit(f"{path}: its half-built item {body['transitional_item']['id']} is not in content.PARTS")
    # a recipe that names another mod's item or fluid only loads when that mod is installed
    text = json.dumps(body)
    mods = [m for m in OPTIONAL_MODS if f'"{m}:' in text or f'"#{m}:' in text]
    if "#c:tools/knife" in text or path.split("/")[-1].startswith("kitchen_"):
        mods = sorted(set(mods) | {"farmersdelight"})
    if mods:
        body = {"fabric:load_conditions": [{"condition": "fabric:all_mods_loaded", "values": mods}], **body}
    write(f"{D}/recipe/{path}.json", body)


def made_by(body):
    """The ids a recipe makes, fluids included."""
    return [r if isinstance(r, str) else r.get("id", "")
            for r in list(body.get("results", [])) + list(body.get("fluid_results", [])) + ([body["result"]] if "result" in body else [])]


# ---- which milestone a machine's company needs before the machine will run each recipe
lock_of = {}
for m in data["milestones"] + data["milestones_mam"]:
    for i in m["items"]:
        lock_of.setdefault(i, m["id"])
data["recipe_locks"] = {}
for path, body in built.items():
    for rid in made_by(body):
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
# other mods' recipes locked by their own id (Diesel Generators' biodiesel)
for rid, mid in recipes.FOREIGN_LOCKS.items():
    if mid not in by_id:
        raise SystemExit(f"recipes.FOREIGN_LOCKS: no milestone {mid} for {rid}")
    data["recipe_locks"][rid] = mid
# Farmer's Delight recipes moved onto Create machines need the Automated Kitchen research
kitchen_node = next(m["id"] for m in data["milestones_mam"] if "food:kitchen" in m["tokens"])
for path in recipes.KITCHEN:
    data["recipe_locks"]["siftec:" + path] = kitchen_node
print("kitchen recipes:", len(recipes.KITCHEN), "locked to", kitchen_node)


# ---- alternates: each has its own lock, and is only offered once everything it uses is unlocked
def ids_in(node, found):
    if isinstance(node, str):
        if ":" in node and " " not in node:
            found.add(node)
    elif isinstance(node, dict):
        for k, v in node.items():
            if k != "type":
                ids_in(v, found)
    elif isinstance(node, list):
        for v in node:
            ids_in(v, found)


BUILDING_IDS = {b[0] for b in content.BUILDINGS}


def building_of(path):
    """The SIFTEC building that runs a recipe, from its path (mixing/refinery/fuel: the Refinery), or None."""
    parts = path.split("/")
    return parts[1] if len(parts) > 2 and parts[1] in BUILDING_IDS else None


def inputs_of(body, path=""):
    """Everything a recipe needs: its ingredients and fluids, its machines, heat, and its own lock. A recipe a SIFTEC
    building runs needs that building (which holds its machines, heat and all) in place of the loose Create machines."""
    used = set()
    for k, v in body.items():
        if k not in ("type", "results", "result", "fluid_results", "fabric:load_conditions", "transitional_item"):
            ids_in(v, used)
    building = building_of(path)
    if building:
        used.add("siftec:building/" + building)
        return used
    used.update(MACHINE_OF[body["type"]])
    if body.get("heat_requirement"):
        used.add("create:blaze_burner")
    return used


# where the things no milestone locks come from: the fixed sources (tables/recipe_tables.py), and anything one of the
# pack's own recipes makes, from that recipe's inputs, machine and lock
sources = dict(SOURCES)
alt_paths = {p for info in recipes.ALTS.values() for p in info["paths"]}
for path, body in built.items():
    if path in alt_paths:
        continue
    for o in made_by(body):
        if o and o not in lock_of and o not in sources and not o.startswith("minecraft:"):
            sources[o] = sorted(inputs_of(body, path)) + ["siftec:recipe/" + path]
for path, mid in data["recipe_locks"].items():
    lock_of["siftec:recipe/" + path.split(":", 1)[1]] = mid
# the Build Gun's buildings count as what unlocks them (a node's resource needs a miner on the node)
for _b in content.BUILDINGS:
    if _b[4]:
        # a building from the AWESOME Shop counts as the milestone that brings the Shop
        lock_of["siftec:building/" + _b[0]] = "resource_sink_bonus_program" if _b[4].startswith("shop:") else _b[4]


def needs_of(things, seen=None):
    seen = set() if seen is None else seen
    out = []
    for t in sorted(things):
        if t in seen:
            continue
        seen.add(t)
        mid = lock_of.get(t) or lock_of.get(t + "_bucket")
        found = [mid] if mid else needs_of(sources.get(t, []), seen)
        for f in found:
            if f not in out:
                out.append(f)
    return out


# (the Particle Accelerator's and the Converter's alternates are among recipes.ALTS: their machines' recipes carry them)
data["alternates"] = []
for aid, info in recipes.ALTS.items():
    used, where = set(), set()
    for path in info["paths"]:
        if not building_of(path):
            raise SystemExit(f"alternate {aid}: {path} names no SIFTEC building to run it")
        where.add(building_of(path))
        used |= inputs_of(built[path], path)
        data["recipe_locks"]["siftec:" + path] = "alt_" + aid
    # a Hard Drive only offers an alternate once its company has the building that runs it (and everything it uses)
    (building,) = where
    data["alternates"].append({"id": "alt_" + aid, "building": building, "requires": needs_of(used)})
    lang[f"siftec.alt.alt_{aid}"] = info["name"]
    lang[f"siftec.alt.alt_{aid}.text"] = info["text"]
print("alternates:", len(data["alternates"]), "; with nothing required:", [a["id"] for a in data["alternates"] if not a["requires"]])


# ---- what the Manufacturer, the Quantum Encoder and the Packager run (building/BuildingLogic.java): the steps of each
# recipe and the milestone, MAM node or alternate it needs, so the building only takes on a recipe its company has
def recipe_lock(path, made):
    lock = data["recipe_locks"].get("siftec:" + path)
    if lock is None and path.startswith("sequenced_assembly/"):
        lock = data["recipe_locks"].get("seq:" + made.split(":")[1])
    return lock or ""


data["assembly"] = []
for e in recipes.ASSEMBLY:
    entry = {k: v for k, v in e.items() if k != "seq"}
    entry["lock"] = recipe_lock(e["seq"], e.get("charged", e["result"]))
    for st in e["steps"]:
        if "item" in st and not st["item"].startswith("#"):
            check(st["item"], "assembly " + e["result"])
    if not e["first"].startswith("#"):
        check(e["first"], "assembly " + e["result"])
    data["assembly"].append(entry)
data["packager"] = [{**{k: v for k, v in p.items() if k != "path"}, "lock": recipe_lock(p["path"], p["out"])} for p in recipes.PACKAGER]
print("assembly recipes:", len(data["assembly"]), "; packager recipes:", len(data["packager"]))
# the locks also name sequenced assembly steps ("seq:...") and other mods' recipes, which are not among this pack's own
_own_locks = sum(1 for k in data["recipe_locks"] if k.startswith("siftec:") and k[len("siftec:"):] in built)
_seq_locks = sum(1 for k in data["recipe_locks"] if k.startswith("seq:"))
print(f"recipes locked to a milestone: {_own_locks} of the pack's {len(built)}; also {_seq_locks} sequenced assembly steps and "
      f"{len(data['recipe_locks']) - _own_locks - _seq_locks} other mods' recipes")
data["removed_recipes"] = REMOVED
data["workshop"] = [{"item": i, "cost": cost_list(cost)} for i, cost in WORKSHOP]
# 1.0 build costs of every building the Build Gun will place, under its 1.0 name, for work packages 5 and 9
data["build_costs"] = {}
for bname, cost in sorted(content.BUILD_COSTS.items()):
    if cost is None:
        raise SystemExit(f"build cost of {bname}: it takes something the pack leaves out")
    data["build_costs"][bname] = cost_list(cost, "build cost " + bname)

# ---- Build Gun and its buildings (building/BuildGun.java, building/Buildings.java)
data["buildings"] = []
LOGICS = {"basin", "smelter", "miner", "portable_miner", "storage", "power", "assembly", "packager", "extractor", "pressurizer",
          "well_extractor", "processor", "workshop", "sink", "shop", "radar", "truck_station", "drone_port", "foundation", "designer"}
POWERS = {"pole", "storage", "augmenter", "biomass", "coal", "fuel", "geothermal", "nuclear"}
for bid, bname, tab, cost, unlock, dev, extra in content.BUILDINGS:
    if tab not in content.BUILD_TABS:
        raise SystemExit(f"building {bid}: unknown Build Gun tab {tab!r}")
    if unlock.startswith("shop:"):
        if not any(e[0] == unlock[5:] for e in shop.SHOP):
            raise SystemExit(f"building {bid}: unlocked by {unlock!r}, which is no AWESOME Shop entry")
    elif unlock and unlock not in by_id:
        raise SystemExit(f"building {bid}: unlocked by {unlock!r}, which is no milestone or MAM node")
    if not extra.get("prefab") and not os.path.exists(os.path.join(D, "structure", "building", bid + ".nbt")):
        raise SystemExit(f"building {bid}: no template at data/siftec/structure/building/{bid}.nbt (run tools/structures.py)")
    b = {"id": bid, "category": tab, "cost": cost_list(cost, "building " + bid)}
    if unlock:
        b["unlock"] = unlock
    if dev:
        b["dev"] = True
    if bid in content.NODE_BUILDINGS:
        b["node"] = True
        b["node_cell"] = list(extra["node"])
    if extra.get("logic"):
        if extra["logic"] not in LOGICS:
            raise SystemExit(f"building {bid}: unknown logic {extra['logic']!r}")
        b["logic"] = extra["logic"]
    for k in ("su", "break", "fortune", "mw", "water", "lines", "rate"):
        if k in extra:
            b[k] = extra[k]
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
    # where the build menu shows it (client/BuildMenuScreen.java)
    menu = content.BUILD_MENU.get(bid)
    if menu is None:
        raise SystemExit(f"building {bid}: not in the build menu (content.BUILD_MENU)")
    if menu[0] not in dict(content.MENU_SECTIONS) or menu[1] not in dict(content.MENU_SUBS):
        raise SystemExit(f"building {bid}: unknown build menu place {menu!r}")
    b["menu"] = list(menu)
    data["buildings"].append(b)
    lang[f"siftec.building.{bid}"] = bname
    if dev:
        continue
    # the guide (work package 13): an item that stands for the building in Ponder, JEI and the Ponder index (it has no
    # recipe and does nothing), and its Ponder scene's own lines (client/ponder/BuildingScenes.java)
    lines = guide.BUILDING_GUIDE.get(bid)
    if not lines:
        raise SystemExit(f"building {bid}: no guide text in tools/tables/guide.py (BUILDING_GUIDE)")
    b["guide"] = lines
    lang[f"item.siftec.building_{bid}"] = bname
    item_def(f"building_{bid}", "siftec:item/blueprint")
    lang[f"siftec.ponder.{bid}.header"] = bname
    for n, line in enumerate(lines, 1):
        lang[f"siftec.ponder.{bid}.text_{n}"] = line
_guided = {b[0] for b in content.BUILDINGS if not b[5]}
if set(guide.BUILDING_GUIDE) - _guided:
    raise SystemExit(f"guide text for buildings that do not exist: {sorted(set(guide.BUILDING_GUIDE) - _guided)}")
if set(content.BUILD_MENU) - {b[0] for b in content.BUILDINGS}:
    raise SystemExit(f"build menu places for buildings that do not exist: {sorted(set(content.BUILD_MENU) - {b[0] for b in content.BUILDINGS})}")
# the build menu's categories and subcategories, in order, and the first build hotbar of a new player
data["build_menu"] = {"sections": [k for k, _ in content.MENU_SECTIONS], "subs": [k for k, _ in content.MENU_SUBS],
                      "hotbar": list(content.DEFAULT_HOTBAR)}
for k, name in content.MENU_SECTIONS:
    lang[f"siftec.build_menu.section.{k}"] = name
for k, name in content.MENU_SUBS:
    lang[f"siftec.build_menu.sub.{k}"] = name
for _bid in content.DEFAULT_HOTBAR:
    if _bid not in content.BUILD_MENU:
        raise SystemExit(f"default build hotbar: no building {_bid}")
for key, text in guide.PONDER_SHARED.items():
    lang[f"siftec.ponder.shared.{key}"] = text
for tab, (title, text) in guide.PONDER_TAGS.items():
    if tab not in content.BUILD_TABS:
        raise SystemExit(f"Ponder tag {tab}: no Build Gun tab of that name")
    lang[f"siftec.ponder.tag.{tab}"] = title
    lang[f"siftec.ponder.tag.{tab}.description"] = text
# Ponder scenes need a schematic to start from; a building's scene puts its own blocks in (BuildingScenes.java)
_plate = os.path.join(A, "ponder", "building.nbt")
structures.write_nbt(_plate, structures.structure({(0, 0, 0): "minecraft:smooth_stone"}))
WRITTEN.add(os.path.normpath(_plate))
# Satisfactory's time for each machine recipe a building paces (building/MachineTimes.java), in ticks, and the Smelter's
# time per item for the vanilla smelting its fan runs
data["machine_ticks"] = {}
for path, body in sorted(built.items()):
    if body["type"] in ("create:compacting", "create:mixing"):
        if not body.get("processing_time"):
            raise SystemExit(f"{path}: a Basin recipe with no time (recipes.basin_times)")
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
            out[recipes.FLUID_IDS[content.SAT_FLUIDS[key]]] = value
        elif isinstance(value, tuple):
            mj, waste, n = value
            check(key, "generator fuel " + kind)
            check(waste, "generator waste " + kind)
            out[key] = {"mj": mj, "waste": waste, "count": n}
        else:
            if not key.startswith("#"):
                check(key, "generator fuel " + kind)
            out[key] = value
    data["generator_fuel"][kind] = out
# a New Age reactor a player builds burns Satisfactory's rods, not New Age's own fuel: its fuel tag is replaced, and
# each rod's energy is New Age's fuel's for a uranium rod and twice that for plutonium (as in Satisfactory)
write_tag("create_new_age", "item", "nuclear/fuel", ["siftec:uranium_fuel_rod", "siftec:plutonium_fuel_rod"], replace=True)
# A rod lasts Satisfactory's time in the Nuclear Power Plant (2,500 MW): a uranium rod 750,000 MJ (5 minutes), plutonium
# 1,500,000 MJ (10 minutes). New Age burns one unit of fuel a tick in every rod, and the plant has content.NUCLEAR_RODS
# of them, so a rod's energy is its ticks times that.
for rod, (mj, waste, n) in content.GENERATOR_FUEL["nuclear"].items():
    ticks = round(mj / 2500 * 20)
    write_tag("create_new_age", "item", f"nuclear/energy_{ticks * content.NUCLEAR_RODS}", [rod], replace=True)
data["nuclear"] = {"rods": content.NUCLEAR_RODS, "boilers": [list(b) for b in structures.NUCLEAR_BOILERS], "levels": 36}
# Diesel Generators burns a fluid only if it has a fuel type: Satisfactory's Turbofuel, Rocket Fuel and Ionized Fuel get
# one at the Fuel Generator's 1.0 numbers (250 MW, MJ a m3), for an engine anywhere; the Fuel Generator's own engine is
# held to them for every fuel it burns (Diesel Generators' own Fuel and biodiesel too, mixin DieselEngineMixin)
for fname, mj in content.GENERATOR_FUEL["fuel"].items():
    fid = recipes.FLUID_IDS[content.SAT_FLUIDS[fname]]
    if not fid.startswith("siftec:"):
        continue
    per = {"speed": 256.0, "strength": round(250 * 204.8, 1), "burn_rate": round(250 / 20 / (mj / content.MB_PER_M3), 6)}
    write(os.path.join(D, "createdieselgenerators", "fuel_type", fid.split(":")[1] + ".json"),
          {"fluid": fid, "normal": per, "modular": per, "huge": per, "sound_pitch": 0.8, "burner_multiplier": 1.0})
# thorium, which New Age's own fuel is made from, does not generate (its ore is switched off with the fuel)
write(os.path.join(ROOT, "data", "create_new_age", "worldgen", "placed_feature", "thorium_ore.json"), {"feature": "create_new_age:thorium_ore",
      "placement": [{"type": "minecraft:count", "count": 0}]})
item_def("build_gun", "create:item/handheld_worldshaper/item")
for tab in content.BUILD_TABS:
    lang[f"siftec.build_gun.category.{tab}"] = tab.capitalize()
# a generator building's turbine: the steam engine's look, turning the generator coils while it runs
block_model("turbine", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "create:block/gearbox", "bottom": "create:block/gearbox", "side": "create:block/copper_casing"}})
blockstate("turbine", {f"facing={d}": {"model": "siftec:block/turbine", **turn}
                       for d, turn in {"up": {}, "down": {"x": 180}, "north": {"x": 90}, "south": {"x": 90, "y": 180}, "east": {"x": 90, "y": 90},
                                       "west": {"x": 90, "y": 270}}.items()})
item_def("turbine", "siftec:block/turbine")
# the controller: a control panel; the ports: a casing with a coloured face pointing out
II = "create:block/industrial_iron_block"


def panel_cube(front):
    """An industrial iron cube whose north face is front."""
    return {"parent": "minecraft:block/cube", "textures": {"particle": II, "north": front, "south": II, "east": II, "west": II,
                                                            "up": "create:block/industrial_iron_block_top", "down": II}}


blockstate("building_controller", {f"facing={d}": turned("siftec:block/building_controller", y) for d, y in NORTH_FIRST})
block_model("building_controller", panel_cube("minecraft:block/crafter_north"))
item_def("building_controller", "siftec:block/building_controller")
PORT_TURN = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}, "up": {"x": 270}, "down": {"x": 90}}
port_variants = {}
for kind, face in PORT_FACES.items():
    block_model(f"building_port_{kind}", panel_cube(face))
    for d, turn in PORT_TURN.items():
        port_variants[f"facing={d},kind={kind}"] = {"model": f"siftec:block/building_port_{kind}", **turn}
blockstate("building_port", port_variants)
item_def("building_port", "siftec:block/building_port_item_in")
# the death crate (death/DeathCrates.java)
block_model("death_crate", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "top": "minecraft:block/barrel_top", "bottom": "create:block/andesite_casing", "side": "create:block/andesite_casing"}})
single_blockstate("death_crate")
# the Brass Basin (the Manufacturer's and the Blender's Basin): Create's Basin in Create's brass casing, holding four
# stacks of each item; Create's own Basin block entity runs it
BB = "create:block/brass_casing"
block_model("brass_basin", {"parent": "minecraft:block/block", "textures": {"particle": BB, "case": BB}, "elements": [
    {"from": [0, 2, 0], "to": [16, 16, 16], "faces": {**{d: {"uv": [0, 0, 16, 14], "texture": "#case"} for d in ("north", "east", "south", "west")},
                                                       "up": {"uv": [0, 0, 16, 16], "texture": "#case"}, "down": {"uv": [0, 0, 16, 16], "texture": "#case"}}},
    {"from": [1.95, 16, 1.95], "to": [14.05, 2, 14.05], "faces": {**{d: {"uv": [2, 2, 14, 14], "rotation": 180, "texture": "#case"} for d in ("north", "east", "south", "west")},
                                                                    "up": {"uv": [2, 2, 14, 14], "rotation": 90, "texture": "#case"}}},
    {"from": [2, 0, 2], "to": [14, 2, 14], "faces": {**{d: {"uv": [2, 14, 14, 16], "texture": "#case"} for d in ("north", "east", "south", "west")},
                                                     "down": {"uv": [2, 2, 14, 14], "texture": "#case"}}}]})
blockstate("brass_basin", {f"facing={d}": {"model": "siftec:block/brass_basin"} for d in ("down", "north", "south", "east", "west")})
if UNKNOWN:
    raise SystemExit("unknown item ids (not items of the game or of the pack's mods):\n  " + "\n  ".join(UNKNOWN))
write(os.path.join(ROOT, "siftec_content.json"), data)

# ---- the recipe checkers (tools/check_recipes.py): reachability, automatability, conflicts
_errors, _notes, _summary = check_recipes.check(data)
print(_summary)
for _n in _notes:
    print("  note:", _n)
if _errors:
    raise SystemExit("recipe checkers failed:\n  " + "\n  ".join(_errors))

# ---- the HUB and the Wormhole Gateway blocks
for b, top in (("hub", "create:block/andesite_casing"), ("wormhole_gateway", "create:block/railway_casing")):
    single_blockstate(b)
    block_model(b, cube_bottom_top("minecraft:block/lodestone_top", top, top))
    # the HUB is built with the Build Gun and has no item; the Gateway is still an item
    if b != "hub":
        item_def(b, f"siftec:block/{b}")

# every siftec.* key the mod's code names outright (Component.translatable / translatableWithFallback with a literal key)
# needs its text here: a fallback in the code is only English for a missing key, not a translation file entry
_KEY_IN_CODE = re.compile(r'translatable(?:WithFallback)?\(\s*"(siftec\.[^"]+)"\s*[,)]')
_missing_keys = set()
for _src in ("main", "client"):
    for _dir, _, _files in os.walk(os.path.join(TOOLS, "..", "src", _src, "java")):
        for _name in _files:
            if _name.endswith(".java"):
                with open(os.path.join(_dir, _name)) as _f:
                    _missing_keys.update(k for k in _KEY_IN_CODE.findall(_f.read()) if k not in lang)
if _missing_keys:
    raise SystemExit("lang: keys named in the code with no text in tables/lang.py: " + ", ".join(sorted(_missing_keys)))

write(f"{A}/lang/en_us.json", dict(sorted(lang.items())))

for b in DROPS_ITSELF:
    drops_itself(b, survives_explosion=True)
# a Pumpjack hole only counts an Oil Well as the bottom of its pipe
write_tag("createdieselgenerators", "block", "oil_deposit", ["siftec:oil_well"], replace=True)
# the pickaxe is the right tool for the machines, SIFTEC's ores and the decorations
write_tag("minecraft", "block", "mineable/pickaxe", PICKAXE + sorted(f"siftec:{b}" for b in SIFTEC_ORES) + [f"siftec:{d[0]}" for d in shop.DECOR])
# SIFTEC's ores take a stone pickaxe or better, like iron ore
write_tag("minecraft", "block", "needs_stone_tool", sorted(f"siftec:{b}" for b in SIFTEC_ORES))
# Create's contraptions never move what has to stay put: the HUB and Gateway shells, claim markers, patches and fluid
# nodes. (Drills skip patches and fluid nodes, which are unbreakable.) An ore node's block is an ordinary ore block: it
# is kept still by its position instead (node/NodeSites.java and the claim mixins), and a drill mines it.
write_tag("create", "block", "non_movable",
          ["siftec:hub", "siftec:wormhole_gateway"] + [f"siftec:prefab_{k}" for k in PREFAB] + ["siftec:hub_bed", "siftec:personal_storage",
           "siftec:hub_burner", "siftec:claim_marker"] + [f"siftec:{k}_patch" for k in STONE] + [f"siftec:{b}" for b in FLUID_NODE_IDS])

# ---- The mod's own art, cut from the sprite sheets by tools/import_art.py. Anything with a texture file drops its
# borrowed stand-in; anything without one keeps it.
TEX = os.path.join(A, "textures")
# the Gateway shares a block class with blocks that turn, so it has the same facing, though nothing on it shows it
blockstate("wormhole_gateway", {f"facing={d}": {"model": "siftec:block/wormhole_gateway"} for d in ("north", "east", "south", "west")})
for bid in sorted({f[:-len("_side.png")] for f in os.listdir(os.path.join(TEX, "block")) if f.endswith("_side.png")} if os.path.isdir(os.path.join(TEX, "block")) else []):
    if bid in SHAPED_COLLECT:
        continue  # the Mercer Sphere and Somersloop have their own shapes (above)
    t = lambda face: f"siftec:block/{bid}_{face}"
    if bid == "claim_marker":
        # a post six pixels wide; the faces take the middle of the drawn textures
        block_model(bid, {"parent": "minecraft:block/block", "textures": {"particle": t("side"), "side": t("side"), "top": t("top")},
                          "elements": [{"from": [5, 0, 5], "to": [11, 16, 11], "faces": {
                              "north": {"texture": "#side"}, "south": {"texture": "#side"}, "east": {"texture": "#side"}, "west": {"texture": "#side"},
                              "up": {"texture": "#top"}, "down": {"texture": "#side"}}}]})
    elif bid in FRONTED:
        if bid in TURNS:
            blockstate(bid, {f"facing={d}": turned(f"siftec:block/{bid}", y) for d, y in NORTH_FIRST})
        block_model(bid, {"parent": "minecraft:block/cube", "textures": {"particle": t("side"),
                          "north": t("front"), "south": t("side"), "east": t("side"), "west": t("side"), "up": t("top"), "down": t("side")}})
    else:
        block_model(bid, cube_bottom_top(t("top"), t("side"), t("side")))
for f in sorted(os.listdir(os.path.join(TEX, "item"))) if os.path.isdir(os.path.join(TEX, "item")) else []:
    iid = f[:-4]
    if iid == "fluid_in_bucket":
        continue
    flat_item(iid, f"siftec:item/{iid}")

# Create's dough replaces Farmer's Delight's (whose recipes are removed and whose dough is switched off). A tag can
# only lose an entry by being replaced, which works when this mod's data loads after Farmer's Delight's; if it loads
# first, the dough is still uncraftable and hidden, so nothing is lost.
write_tag("c", "item", "foods/dough/wheat", ["create:dough"], replace=True)

# ---- whatever an earlier run wrote that this one did not (a removed item's model, say) is deleted; these folders
# hold only generated files. Textures are tools/import_art.py's.
for folder in (f"{A}/items", f"{A}/models", f"{A}/blockstates", f"{D}/loot_table", f"{D}/context_int_provider", f"{D}/recipe"):
    for dirpath, dirs, files in os.walk(folder):
        for f in files:
            path = os.path.normpath(os.path.join(dirpath, f))
            if f.endswith(".json") and path not in WRITTEN:
                print("removed stale", os.path.relpath(path, ROOT))
                os.remove(path)
