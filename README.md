# SIFTEC

Spatial Interlink and Factory Technology Engineering Corporation: the custom mod of a Satisfactory-inspired
Create modpack, where a company of players works through Satisfactory's tiers and milestones and builds a
wormhole instead of a space elevator.

Fabric, Minecraft 26.3, Java 25. Needs Fabric API and Create Fly (mod id `create`); the pack also runs
Create: Diesel Generators, Create: Gunsmithing, Create: Hypertubes and Farmer's Delight, which the mod's data
names (recipes that name another mod load only when that mod is installed).

What it adds: the HUB with milestones and wormhole phases, companies and land claims, per-company recipe and
item locks, resource nodes, the Build Gun and its buildings, the MAM research trees, Power Poles and Power Storage,
the Equipment Workshop, the AWESOME Sink and Shop, the backpack, blueprints, geothermal power, and the parts and
fluids of Satisfactory's production chain.

The Build Gun's buildings are multiblocks with real Create machines inside (`building/`): the Constructor (a Press
over a Basin, its recipe picked by the controller's filter), the Smelter (an Encased Fan blasting through lava onto a
Depot), Miner Mk.1 to Mk.3 and the Portable Miner (a Drill over a node), and the Storage Containers (Item Vaults).
Each keeps to Satisfactory's time per recipe and rate per minute (`BasinPace`, `BuildingLogic`), takes belts at its
input ports and puts what it makes onto the belt at its output port, and is turned by a motor inside that gives the SU
it draws (Satisfactory's MW at 1 MW = 204.8 SU). Every player starts with a HUB, which costs 20 Raw Iron to put down,
hands out the Build Gun, and has a hand-cranked Craft Station (a Press and a Mixer over Basins) and two Biomass
Burners.

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
- `tools/satisfactory.py`: Satisfactory 1.0's recipes, build costs, milestones, MAM costs and sink points, written by
  `tools/import_satisfactory.py` from the game export SatisfactoryTools publishes. Never edited by hand.
- `tools/recipes.py`: every recipe the mod adds (1.0's at Satisfactory amounts, on the Create machines of each
  building, and the pack's own), the Hard Drive alternates, the recipes it removes from other mods (`REMOVED`) and the
  Equipment Workshop's build costs.
- `tools/check_recipes.py`: the checkers (reachability, automatability, conflicts). `gen_assets.py` runs them and
  stops on an error; the CI runs the generator and fails if the committed files differ from what it writes.
- `tools/gen_assets.py`: turns those into `siftec_content.json`, the recipes, models, blockstates, loot
  tables and the lang file. It stops on an unknown item id (checked against `tools/known_ids.txt`, the item
  ids of the game and the pack's mods), on an item of another mod that nothing unlocks or frees, and it deletes
  generated files that a run no longer writes.
- `tools/import_art.py`: cuts the textures out of the sprite sheets in `tools/art`.
- `tools/structures.py`: writes the structure templates of the Build Gun's buildings that are drawn in code
  (`data/siftec/structure/building/<id>.nbt`). A building can also be built in game and saved with a Structure
  Block: author it facing north (the player stands on its south side), with one `siftec:building_controller` and a
  `siftec:building_port` (facing out, `kind` item_in, item_out, fluid_in or fluid_out) wherever belts and pipes
  attach. The building's tab, cost and unlock are listed in `tools/content.py` (`BUILDINGS`).

Run them from outside the repository, so nothing in the working directory shadows a module:

    cd /tmp && python3 -I -B /path/to/SIFTEC/tools/gen_assets.py

The design is in the SIFTEC Master Design doc; `design/design.txt` is the old design, kept as a reference.
