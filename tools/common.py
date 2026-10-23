"""Paths and file helpers the generators share."""
import json
import os

TOOLS = os.path.dirname(os.path.abspath(__file__))
RESOURCES = os.path.join(TOOLS, "..", "src", "main", "resources")
ASSETS = os.path.join(RESOURCES, "assets", "siftec")
DATAPACK = os.path.join(RESOURCES, "data", "siftec")
CONTENT_JSON = os.path.join(RESOURCES, "siftec_content.json")

# every file this run wrote (normalised paths): gen_assets.py deletes the generated files it did not write
WRITTEN = set()


def write_json(path, obj):
    """Writes obj as the pack's JSON files are written: two-space indent and a final newline."""
    WRITTEN.add(os.path.normpath(path))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def read_pack(name):
    """Another mod's shipped files, as saved in tools/pack/<name>.json ({path in its jar: JSON}), or None when the
    file is not there."""
    path = os.path.join(TOOLS, "pack", name + ".json")
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return json.load(f)
