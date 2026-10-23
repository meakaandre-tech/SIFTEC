#!/usr/bin/env python3
"""Writes tools/tables/satisfactory.py, the Satisfactory 1.0 numbers the pack's data is built from, out of the game export
that SatisfactoryTools publishes (github.com/greeny/SatisfactoryTools, data/data1.0.json: every recipe, building cost,
milestone and MAM node of Satisfactory 1.0, as the game itself lists them).

    git clone --depth 1 https://github.com/greeny/SatisfactoryTools /tmp/st
    cd /tmp && python3 -I -B <repo>/tools/import_satisfactory.py /tmp/st/data/data1.0.json

Nothing here is a SIFTEC choice: tools/content.py and tools/recipes.py map these names onto the pack's items and
buildings. Fluid amounts are in m3, as the game shows them."""
import json, os, subprocess, sys

src = sys.argv[1]
d = json.load(open(src))
I, R, S = d["items"], d["recipes"], d["schematics"]
try:
    rev = subprocess.run(["git", "-C", os.path.dirname(src), "log", "-1", "--format=%h %cs", "--", os.path.basename(src)],
                         capture_output=True, text=True).stdout.strip()
except OSError:
    rev = ""


def name(cls):
    return I[cls]["name"] if cls in I else {"Desc_HardDrive_C": "Hard Drive"}.get(cls, cls)


def num(x):
    return int(x) if float(x).is_integer() else float(x)


def amounts(lst):
    return [(name(a["item"]), num(a["amount"])) for a in lst]


# what unlocks each recipe: HUB upgrade, milestone, MAM node, or a Hard Drive
unlocks = {}
for s in S.values():
    kind = {"EST_Tutorial": "hub", "EST_Milestone": "milestone", "EST_MAM": "mam", "EST_Alternate": "drive"}.get(s["type"])
    if not kind:
        continue
    for r in s["unlock"]["recipes"]:
        unlocks.setdefault(r, []).append((kind, None if kind == "drive" else s["name"]))
# recipes the export lists with no unlock of their own: the game hands them out with something else
by_name = {r["name"]: k for k, r in R.items()}
GIVEN_WITH = {"Iron Plate": ("start", None), "Iron Rod": ("start", None), "Iron Ingot": ("start", None),
              "Residual Fuel": ("milestone", "Oil Processing"), "Residual Plastic": ("milestone", "Oil Processing"),
              "Residual Rubber": ("milestone", "Oil Processing"), "Alternate: Distilled Silica": ("drive", None)}


def unlock_of(key, r):
    found = unlocks.get(key, [])
    if found:
        # a MAM node comes before the milestone that also gives it (Reanimated SAM, SAM Fluctuator)
        for kind in ("mam", "hub", "milestone", "drive"):
            for k, n in found:
                if k == kind:
                    return (k, n)
    if r["name"] in GIVEN_WITH:
        return GIVEN_WITH[r["name"]]
    if r["name"].startswith("Unpackage "):
        packed = by_name.get("Packaged " + r["name"][len("Unpackage "):])
        if packed:
            return unlock_of(packed, R[packed])
    if r["producedIn"] == ["Desc_Converter_C"]:
        return ("milestone", "Matter Conversion")
    return (None, None)


BUILDING = {"Desc_ConstructorMk1_C": "Constructor", "Desc_SmelterMk1_C": "Smelter", "Desc_AssemblerMk1_C": "Assembler",
            "Desc_FoundryMk1_C": "Foundry", "Desc_ManufacturerMk1_C": "Manufacturer", "Desc_OilRefinery_C": "Refinery",
            "Desc_Blender_C": "Blender", "Desc_Packager_C": "Packager", "Desc_HadronCollider_C": "Particle Accelerator",
            "Desc_Converter_C": "Converter", "Desc_QuantumEncoder_C": "Quantum Encoder"}
recipes = []
for key, r in R.items():
    if not r["inMachine"]:
        continue
    (building,) = [BUILDING[p] for p in r["producedIn"]]
    recipes.append((key, r["name"], building, r["alternate"], num(r["time"]), amounts(r["ingredients"]), amounts(r["products"]),
                    unlock_of(key, r)))
recipes.sort(key=lambda t: (list(BUILDING.values()).index(t[2]), t[3], t[1]))

# Build Gun costs of the buildings the pack has (Concrete stays Concrete here; content.py maps it to any stone)
WANTED = {"The HUB", "Space Elevator", "MAM", "Equipment Workshop", "AWESOME Sink", "AWESOME Shop", "Radar Tower", "Lookout Tower",
          "Blueprint Designer Mk.1", "Blueprint Designer Mk.2", "Blueprint Designer Mk.3", "Miner Mk.1", "Miner Mk.2", "Miner Mk.3",
          "Water Extractor", "Oil Extractor", "Resource Well Pressurizer", "Resource Well Extractor", "Smelter", "Constructor", "Assembler",
          "Foundry", "Manufacturer", "Refinery", "Blender", "Packager", "Particle Accelerator", "Converter", "Quantum Encoder",
          "Biomass Burner", "Coal-Powered Generator", "Fuel-Powered Generator", "Geothermal Generator", "Nuclear Power Plant",
          "Alien Power Augmenter", "Power Pole Mk.1", "Power Pole Mk.2", "Power Pole Mk.3", "Power Tower", "Power Storage",
          "Storage Container", "Industrial Storage Container", "Personal Storage Box", "Jump Pad", "U-Jelly Landing Pad",
          "Drone Port", "Drone", "Truck Station", "Tractor", "Truck", "Explorer", "Dimensional Depot Uploader", "Main Portal",
          "Satellite Portal", "Power Line"}
buildings = {}
for r in R.values():
    if r["forBuilding"] and r["name"] in WANTED and r["name"] not in buildings:
        buildings[r["name"]] = amounts(r["ingredients"])
missing = WANTED - set(buildings)
if missing:
    raise SystemExit(f"no build cost for {sorted(missing)}")
equipment = {r["name"]: amounts(r["ingredients"]) for r in R.values() if r["inWorkshop"]}

milestones = []
for s in S.values():
    if s["type"] not in ("EST_Tutorial", "EST_Milestone"):
        continue
    rec = [R[k]["name"] for k in s["unlock"]["recipes"] if k in R and R[k]["inMachine"]]
    bld = [R[k]["name"] for k in s["unlock"]["recipes"] if k in R and R[k]["forBuilding"]]
    eq = [R[k]["name"] for k in s["unlock"]["recipes"] if k in R and R[k]["inWorkshop"]]
    milestones.append((s["tier"], s["name"], amounts(s["cost"]), num(s["time"]), rec, bld, eq))
milestones.sort(key=lambda m: (m[0], m[1]))
mam = {s["name"] + ("" if s["name"] != "Expanded Toolbelt" and s["name"] != "Inflated Pocket Dimension" else " / " + k): amounts(s["cost"])
       for k, s in S.items() if s["type"] == "EST_MAM"}

used = {n for r in recipes for n, _ in r[5] + r[6]} | {n for c in list(buildings.values()) + list(equipment.values()) for n, _ in c} \
    | {n for m in milestones for n, _ in m[2]} | {n for c in mam.values() for n, _ in c}
items = {}
for cls, it in I.items():
    if it["name"] in used:
        items[it["name"]] = {"fluid": bool(it.get("liquid")), "sink": it.get("sinkPoints", 0)}

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tables", "satisfactory.py")
with open(out, "w") as f:
    f.write('"""Satisfactory 1.0, as the game export lists it. Written by tools/import_satisfactory.py from SatisfactoryTools\n')
    f.write(f'data/data1.0.json ({rev}); do not edit by hand. Fluid amounts are m3, times seconds."""\n\n')
    f.write("# name -> {fluid, sink points}\nITEMS = {\n")
    for k in sorted(items):
        f.write(f"    {k!r}: {items[k]!r},\n")
    f.write("}\n\n# (key, name, building, alternate, seconds, [(input, amount)], [(output, amount)], (unlocked by: start / hub / milestone /\n")
    f.write("# mam / drive, its name))\nRECIPES = [\n")
    for r in recipes:
        f.write(f"    {r!r},\n")
    f.write("]\n\n# Build Gun costs: building -> [(part, amount)]\nBUILDINGS = {\n")
    for k in sorted(buildings):
        f.write(f"    {k!r}: {buildings[k]!r},\n")
    f.write("}\n\n# Equipment Workshop costs\nEQUIPMENT = {\n")
    for k in sorted(equipment):
        f.write(f"    {k!r}: {equipment[k]!r},\n")
    f.write("}\n\n# (tier, name, cost, lock seconds, recipes, buildings, equipment); tier 0 is the HUB upgrades\nMILESTONES = [\n")
    for m in milestones:
        f.write(f"    {m!r},\n")
    f.write("]\n\n# MAM node -> cost (every node researches in 3 s; a Hard Drive takes 600 s)\nMAM = {\n")
    for k in sorted(mam):
        f.write(f"    {k!r}: {mam[k]!r},\n")
    f.write("}\n")
print("wrote", out, len(recipes), "recipes,", len(buildings), "buildings,", len(equipment), "equipment,", len(milestones), "milestones")
