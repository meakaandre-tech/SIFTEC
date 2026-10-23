"""Builders for the game's resource files: blockstates, block and item models, item definitions and loot tables.
Each one writes its file through common.write_json."""
import os

from common import ASSETS, DATAPACK, RESOURCES, write_json

# the facings a turning block takes, with its y rotation: models authored facing north, or facing south
NORTH_FIRST = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
SOUTH_FIRST = (("south", 0), ("west", 90), ("north", 180), ("east", 270))


def turned(model, y):
    """A blockstate variant: the model turned y degrees (no "y" at all when it is not turned)."""
    return {"model": model, "y": y} if y else {"model": model}


def blockstate(bid, variants):
    write_json(f"{ASSETS}/blockstates/{bid}.json", {"variants": variants})


def single_blockstate(bid, model=None):
    """A block with one look: its own model unless another is given."""
    blockstate(bid, {"": {"model": model or f"siftec:block/{bid}"}})


def block_model(bid, model):
    write_json(f"{ASSETS}/models/block/{bid}.json", model)


def item_model(iid, model):
    write_json(f"{ASSETS}/models/item/{iid}.json", model)


def cube_all(texture):
    return {"parent": "minecraft:block/cube_all", "textures": {"all": texture}}


def cube_bottom_top(top, side, bottom):
    return {"parent": "minecraft:block/cube_bottom_top", "textures": {"top": top, "side": side, "bottom": bottom}}


def item_def(name, model, tint=None):
    """The item definition (assets/siftec/items): which model the item shows, tinted one colour if a tint is given."""
    m = {"type": "minecraft:model", "model": model}
    if tint is not None:
        m["tints"] = [{"type": "minecraft:constant", "value": tint}]
    write_json(f"{ASSETS}/items/{name}.json", {"model": m})


def flat_item(iid, texture, tint=None, parent="minecraft:item/generated"):
    """An item drawn flat from one texture, and its definition."""
    item_model(iid, {"parent": parent, "textures": {"layer0": texture}})
    item_def(iid, f"siftec:item/{iid}", tint)


def drops_itself(bid, survives_explosion=False):
    """The block's loot table: the block itself (unless an explosion destroyed it, when survives_explosion is set)."""
    pool = {"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"siftec:{bid}"}]}
    if survives_explosion:
        pool["conditions"] = [{"condition": "minecraft:survives_explosion"}]
    write_json(f"{DATAPACK}/loot_table/blocks/{bid}.json", {"type": "minecraft:block", "pools": [pool]})


def write_tag(namespace, kind, name, values, replace=False):
    """A tag file, data/<namespace>/tags/<kind>/<name>.json; replace drops what other mods put in the same tag."""
    write_json(os.path.join(RESOURCES, "data", namespace, "tags", kind, name + ".json"), {"replace": replace, "values": values})
