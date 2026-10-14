#!/usr/bin/env python3
"""The recipe checkers (Technical tab): reachability, automatability and conflicts, plus what Create's machines can
hold. gen_assets.py runs them on what it writes and stops on any error; run this file on its own for the report:

    cd /tmp && python3 -I -B <repo>/tools/check_recipes.py

Reachability plays the game through: from what the world gives (node resources, vanilla items, other mods' items the
pack does not make itself), each HUB milestone in order, the Wormhole phases that open Tiers 3, 5, 7 and 9, and the
MAM, using only the recipes each step has unlocked (no Hard Drive alternates). Every milestone, phase and MAM node
must get paid for, and every part must come out of it.
Automatability: every part, and the equipment machines make, has a recipe that runs on a machine.
Conflicts: two recipes a machine cannot tell apart. A Basin's filter picks a recipe by what it makes, so two Basin
recipes clash only when they take the same kinds of input and make the same thing; on the other machines, the same
input is enough. A clash with a Hard Drive alternate is listed, not refused: the company chose the alternate."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import content
import recipes

C = "create:"
BASIN = {"create:mixing", "create:compacting"}
CRAFTING = {"minecraft:crafting_shaped", "minecraft:crafting_shapeless"}
# what the world gives: node resources, fluids from extractors, and the world finds
RAW = {"minecraft:raw_iron", "minecraft:raw_copper", "create:raw_zinc", "minecraft:coal", "minecraft:quartz", "siftec:raw_bauxite", "siftec:uranium",
       "siftec:sam", "cgs:sulfur", "#siftec:stones", "minecraft:stone", "minecraft:water", "createdieselgenerators:crude_oil", "siftec:nitrogen",
       "minecraft:lava", "siftec:hard_drive", "siftec:mercer_sphere", "siftec:somersloop"}
# pack tags the MAM takes, of things the pack itself makes
TAGS = {"#siftec:preserves": ["siftec:pickled_" + w for w in ("tomato", "onion", "cabbage", "pumpkin", "carrot", "beetroot", "kelp")]
        + ["siftec:" + w + "_jam" for w in ("sweet_berry", "glow_berry", "apple", "melon")]}
# found and never made: the power slugs and Power Shards stay as items until overclocking's removal takes them out
FOUND_ONLY = {"siftec:blue_power_slug", "siftec:yellow_power_slug", "siftec:purple_power_slug", "siftec:power_shard"}
# what a building gives that is not a recipe: the Nuclear Power Plant's waste (its fuel rods go in, work package 6)
GENERATED = [({"siftec:uranium_fuel_rod"}, {"siftec:uranium_waste"}, "nuclear_power"),
             ({"siftec:plutonium_fuel_rod"}, {"siftec:plutonium_waste"}, "nuclear_power")]
# vanilla smelting the Smelter runs (the fan's blasting)
SMELTING = [({"minecraft:raw_iron"}, {"minecraft:iron_ingot"}, None), ({"minecraft:raw_copper"}, {"minecraft:copper_ingot"}, None)]
PHASE_OF_TIER = {3: "phase_1", 4: "phase_1", 5: "phase_2", 6: "phase_2", 7: "phase_3", 8: "phase_3", 9: "phase_4"}
MAX_STACK = 64    # a Basin holds one stack of each kind of item
BRASS_STACKS = 4  # a Brass Basin (the Manufacturer's and the Blender's) holds four stacks of each kind
BASIN_KINDS = 9   # either has nine input slots
TANK_MB = 1000    # a Basin tank, a Spout and an Item Drain


def _ids(x):
    if isinstance(x, str):
        return [x]
    if isinstance(x, dict):
        if "fluid" in x:
            return [x["fluid"]]
        if "id" in x:
            return [x["id"]]
        if "item" in x:
            return [x["item"]]
        if "tag" in x:
            return ["#" + x["tag"]]
    if isinstance(x, list):
        return [i for v in x for i in _ids(v)]
    return []


def io(body):
    """(inputs, outputs) of a recipe as sets of ids, fluids and #tags."""
    t = body["type"]
    ins, outs = [], []
    for k in ("ingredients", "fluid_ingredients", "ingredient", "fluid_ingredient", "target"):
        if k in body:
            ins += _ids(body[k])
    if t in CRAFTING and "key" in body:
        ins += _ids(list(body["key"].values()))
    if t == "create:sequenced_assembly":
        for step in body["sequence"]:
            for k in ("ingredient", "fluid_ingredient"):
                v = step.get(k)
                if v and v != "$ingredient":
                    ins += _ids(v)
    for k in ("results", "fluid_results", "result", "fluid_result"):
        if k in body:
            outs += _ids(body[k])
    return set(ins), [o for o in outs if not o.startswith("$")]


def machine_of(body):
    t = body["type"]
    return (t, body.get("heat_requirement", "none"))


def check(data=None):
    """Returns (errors, notes). data is the siftec_content.json gen_assets.py writes (for the recipe locks)."""
    built = recipes.build()
    names = content.names()
    errors, notes = [], []
    parts = {"siftec:" + p[0] for p in content.PARTS}
    sat_items = {content.sat_item(n, True) for n in content.SF.ITEMS}
    checked = (parts | {names[n[1]] for n in sat_items if n and n[0] == "item" and n[1] in names}) - RAW - FOUND_ONLY
    checked = {c for c in checked if not c.startswith("#")}
    alt_paths = {p for info in recipes.ALTS.values() for p in info["paths"]}
    # half-built items come out of their own sequenced assembly; an alternate's own Core is only made once a company
    # picks that alternate
    transitional = {b["transitional_item"]["id"] for b in built.values() if b["type"] == "create:sequenced_assembly"}
    standard_out = {o for p, b in built.items() if p not in alt_paths for o in io(b)[1]}
    alt_only = {o for p, b in built.items() if p in alt_paths for o in io(b)[1]} - standard_out
    checked -= transitional | alt_only
    locks = (data or {}).get("recipe_locks", {})

    def lock_of(path, body):
        if path in alt_paths:
            return "alt"
        key = "siftec:" + path
        if key in locks:
            return locks[key]
        if body["type"] == "create:sequenced_assembly":
            return locks.get("seq:" + body["result"]["id"].split(":")[1])
        return recipes.LOCKED.get(path)

    # ---- reachability, played through in order
    steps = []  # (inputs, outputs, lock)
    for path, body in built.items():
        ins, outs = io(body)
        steps.append((ins, set(outs), lock_of(path, body), path))
    for ins, outs, lock in GENERATED + SMELTING:
        steps.append((ins, outs, lock, "(" + ",".join(sorted(outs)) + ")"))
    for machine, (pname, recipe_list) in content.PROCESSORS.items():
        for p in recipe_list:
            ins = {names.get(n, n) for n, c in p["in"]} | ({recipes.FLUID_IDS[p["fluid_in"][0]]} if p["fluid_in"] else set())
            outs = {names.get(p[k][0], p[k][0]) for k in ("out", "also") if p.get(k)} | ({recipes.FLUID_IDS[p["fluid_out"][0]]} if p["fluid_out"] else set())
            # the Converter comes with Matter Conversion and the Particle Accelerator with Particle Enrichment (1.0)
            steps.append((ins, outs, "alt" if p["alt"] else ("matter_conversion" if machine == "converter" else "particle_enrichment"), machine + ":" + p["name"]))

    def given(i):
        # anything the pack does not make itself comes from vanilla or the other mods
        return i in RAW or (i not in checked and not i.startswith("siftec:") and i not in recipes.FLUID_IDS.values()) or i.startswith("#minecraft:") \
            or i.startswith("#c:") or i in ("#siftec:mycelia", "#siftec:spider_remains")

    def have(i):
        # a pack tag of things the pack makes counts once any of them is made
        if i in TAGS:
            return any(have(t) for t in TAGS[i])
        return given(i) or i in known

    milestones = {m[1]: m for m in content.MILESTONES}
    mam = {n[0]: n for n in content.mam_nodes()}
    phases = {p[0]: p for p in content.PHASES}
    done, known = set(), set()

    def closure():
        progress = True
        while progress:
            progress = False
            for ins, outs, lock, path in steps:
                if lock == "alt" or (lock and lock not in done) or outs <= known:
                    continue
                if all(have(i) for i in ins):
                    known.update(outs)
                    progress = True

    def payable(cost):
        return all(have(i) for i, n in content.parse_cost(cost))

    order = [m[1] for m in content.MILESTONES]
    while True:
        closure()
        before = len(done)
        tier0 = [m for m in order if milestones[m][0] == 0]
        for mid in order:
            t = milestones[mid][0]
            if mid in done:
                continue
            if t == 0 and any(x not in done for x in tier0[:tier0.index(mid)]):
                continue
            if t > 0 and any(x not in done for x in tier0):
                continue
            if t in PHASE_OF_TIER and PHASE_OF_TIER[t] not in done:
                continue
            if payable(milestones[mid][3]):
                done.add(mid)
        for k, (pid, pname, cost, text) in enumerate(content.PHASES):
            if pid not in done and "hub_upgrade_6" in done and (k == 0 or content.PHASES[k - 1][0] in done) and payable(cost):
                done.add(pid)
        if "field_research" in done:
            # a need is a node id, or "a|b" for any of them; research is hand-fed, one node at a time
            for nid, (_, tree, name, cost, time, text, needs, items, tokens) in mam.items():
                if nid not in done and all(any(a in done for a in need.split("|")) for need in needs) and payable(cost):
                    done.add(nid)
        if len(done) == before:
            break
    closure()
    for mid in order:
        if mid not in done:
            errors.append(f"reachability: milestone {mid} can never be paid ({milestones[mid][3]})")
    for pid, pname, cost, text in content.PHASES:
        if pid not in done:
            errors.append(f"reachability: {pname} can never be paid ({cost})")
    for nid, (_, tree, name, cost, time, text, needs, items, tokens) in mam.items():
        if nid not in done:
            errors.append(f"reachability: MAM {tree} / {name} can never be researched ({cost})")
        for need in needs:
            for a in need.split("|"):
                if a not in mam:
                    errors.append(f"MAM {tree} / {name} needs {a}, which is no MAM node")
    for i in sorted(checked - known):
        errors.append(f"reachability: nothing reachable makes {i}")
    for name, cost in list(content.BUILD_COSTS.items()) + [(i, c) for i, c in recipes.WORKSHOP]:
        if cost and not payable(cost):
            errors.append(f"reachability: {name} costs something nothing makes ({cost})")
    reach = len(checked & known)

    # ---- automatability
    machine_made = set()
    for path, body in built.items():
        if body["type"] in CRAFTING:
            continue
        machine_made.update(io(body)[1])
    for ins, outs, lock in GENERATED + SMELTING:
        machine_made.update(outs)
    for machine, (pname, recipe_list) in content.PROCESSORS.items():
        for p in recipe_list:
            machine_made.update(names.get(p[k][0], p[k][0]) for k in ("out", "also") if p.get(k))
    must = checked | set(content.EQUIPMENT_BY_MACHINE)
    for i in sorted(must - machine_made):
        errors.append(f"automatability: no machine makes {i}")
    auto = len(must & machine_made)

    # ---- conflicts and what the machines can hold
    by_key = {}
    for path, body in built.items():
        t = body["type"]
        ins, outs = io(body)
        if t in CRAFTING:
            continue
        first = outs[0] if outs else ""
        key = (machine_of(body), frozenset(ins), first if t in BASIN else "")
        if t == "create:deploying":
            key = (machine_of(body), (tuple(_ids(body["target"])), tuple(_ids(body["ingredient"]))), "")
        by_key.setdefault(key, []).append(path)
        if t in BASIN:
            counts = {}
            for i in body.get("ingredients", []):
                i = "|".join(_ids(i)) if not isinstance(i, str) else i
                counts[i] = counts.get(i, 0) + 1
            if len(counts) > BASIN_KINDS:
                errors.append(f"capacity: {path} takes {len(counts)} kinds of item; a Basin recipe takes at most {BASIN_KINDS}")
            # a recipe runs in the building its path names; the Brass Basin's buildings hold four stacks of each input
            # (a stack a slot, nine slots), every other Basin one stack of each
            brass = path.split("/")[1] in content.BRASS_BASIN_BUILDINGS if path.count("/") >= 2 else False
            most = MAX_STACK * (BRASS_STACKS if brass else 1)
            over = {i.split(':')[-1]: n for i, n in counts.items() if n > most}
            if over:
                errors.append(f"capacity: {path} needs more than {'a Brass' if brass else 'a'} Basin holds of {', '.join(f'{n} {i}' for i, n in sorted(over.items()))}")
            slots = sum(-(-n // MAX_STACK) for n in counts.values())
            if slots > BASIN_KINDS:
                errors.append(f"capacity: {path} needs {slots} stacks at once; a Basin has {BASIN_KINDS} input slots")
            if brass and any(n > MAX_STACK for n in counts.values()):
                notes.append(f"brass basin: {path} holds {', '.join(f'{n} {i.split(chr(58))[-1]}' for i, n in sorted(counts.items()) if n > MAX_STACK)} in its Brass Basin")
            if len(body.get("results", [])) > 4 or len(body.get("fluid_results", [])) > 2 or len(body.get("fluid_ingredients", [])) > 2:
                errors.append(f"capacity: {path} has more outputs or fluids than a Basin takes (4 items, 2 fluids in, 2 out)")
        for k in ("fluid_ingredients", "fluid_ingredient", "fluid_results", "fluid_result"):
            for f in (body.get(k) if isinstance(body.get(k), list) else [body[k]] if k in body else []):
                if f["amount"] > TANK_MB * recipes.DROPLETS:
                    errors.append(f"capacity: {path} moves {f['amount'] // recipes.DROPLETS} mB at once; a tank holds {TANK_MB}")
        for step in body.get("sequence", []):
            f = step.get("fluid_ingredient")
            if f and f["amount"] > TANK_MB * recipes.DROPLETS:
                errors.append(f"capacity: {path} fills {f['amount'] // recipes.DROPLETS} mB in one step; a Spout holds {TANK_MB}")
    clashes = 0
    for key, paths in sorted(by_key.items(), key=lambda kv: kv[1]):
        if len(paths) < 2:
            continue
        standard = [p for p in paths if p not in alt_paths]
        if len(standard) > 1:
            errors.append(f"conflict: {', '.join(standard)} take the same inputs on the same machine" + (" and make the same thing" if key[2] else ""))
            clashes += 1
        else:
            notes.append(f"alternate overlap: {', '.join(paths)} (only for a company that picked the alternate)")
    summary = (f"checkers: reachability {reach}/{len(checked)} parts, {len(done & set(order))}/{len(order)} milestones, "
               f"{len(done & set(phases))}/{len(phases)} phases, {len(done & set(mam))}/{len(mam)} MAM nodes; "
               f"automatability {auto}/{len(must)}; conflicts {clashes}")
    return errors, notes, summary


if __name__ == "__main__":
    import json
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "siftec_content.json")
    errors, notes, summary = check(json.load(open(path)) if os.path.exists(path) else None)
    for n in notes:
        print("note:", n)
    for e in errors:
        print("ERROR:", e)
    print(summary)
    sys.exit(1 if errors else 0)
