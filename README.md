# SIFTEC

Spatial Interlink and Factory Technology Engineering Corporation: the custom mod of a Satisfactory-inspired
Create modpack, where a company of players works through Satisfactory's tiers and milestones and builds a
wormhole instead of a space elevator.

Fabric, Minecraft 26.3, Java 25. Needs Fabric API and Create Fly (mod id `create`); the pack also runs
Create: Diesel Generators, Create: Gunsmithing, Create: Hypertubes and Farmer's Delight, which the mod's data
names (recipes that name another mod load only when that mod is installed).

What it adds: the HUB with milestones and wormhole phases, companies and land claims, per-company recipe and
item locks, resource nodes and miners, the MAM research trees, Power Poles and Power Storage, the Equipment
Workshop, the AWESOME Sink and Shop, the backpack, blueprints, geothermal power, and the parts and fluids of
Satisfactory's production chain.

## Building

    ./gradlew build

Create Fly is not on a Maven repository: put its jar at `libs/create-fly-26.3.jar` first (the CI downloads the
`pack-26.3-latest` release of meakaandre-tech/Create-Fly). The built jar is in `build/libs/`.

The CI (`.github/workflows/build.yml`) builds on every push to `main`, starts a dedicated server with the
pack's mods, runs the self-tests in `.github/smoke-commands*.txt` (`/siftec selftest ...`), publishes the logs
to the `ci-logs` branch and the jar as the `latest` release.

## Where the data comes from

Most of `src/main/resources` is generated. Edit the generators, not the JSON:

- `tools/content.py`: parts, fluids, milestones, wormhole phases, MAM research, which item each milestone
  unlocks (including every item of the other mods: `MORE_LOCKS` and `FREE`), switched-off items, furnace
  fuels, the Converter and Particle Accelerator, the AWESOME Sink and Shop.
- `tools/recipes.py`: every recipe the mod adds, the Hard Drive alternates, the recipes it removes from
  other mods (`REMOVED`) and the Equipment Workshop's build costs.
- `tools/gen_assets.py`: turns those into `siftec_content.json`, the recipes, models, blockstates, loot
  tables and the lang file. It stops on an unknown item id (checked against `tools/known_ids.txt`, the item
  ids of the game and the pack's mods), on an item of another mod that nothing unlocks or frees, and it deletes
  generated files that a run no longer writes.
- `tools/import_art.py`: cuts the textures out of the sprite sheets in `tools/art`.

Run them from outside the repository, so nothing in the working directory shadows a module:

    cd /tmp && python3 -I -B /path/to/SIFTEC/tools/gen_assets.py

The design is in `design/design.txt` and `design/recipes.txt`.
