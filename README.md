# SIFTEC

Spatial Interlink and Factory Technology Engineering Corporation: the custom mod of a Satisfactory-inspired
Create modpack, where a company of players works through Satisfactory's tiers and milestones and builds a
wormhole instead of a space elevator.

Fabric, Minecraft 26.3, Java 25. Needs Fabric API, Create Fly (mod id `create`) and Create: New Age (mod id
`create_new_age`, the fabric-26.3 build of meakaandre-tech/Create-New-Age); the pack also runs
Create: Diesel Generators, Create: Gunsmithing, Create: Hypertubes and Farmer's Delight, which the mod's data
names (recipes that name another mod load only when that mod is installed).

What it adds: the HUB with milestones and wormhole phases, companies and land claims, per-company recipe and
item locks, resource nodes, the Build Gun and its buildings, the MAM research trees, the power grid, the Equipment
Workshop, the AWESOME Sink and Shop, the backpack, blueprints, and the parts and fluids of Satisfactory's production
chain.

The Build Gun's buildings are multiblocks with real Create machines inside (`building/`): the Constructor (a Press
over a Basin, its recipe picked by the controller's filter), the Smelter (an Encased Fan blasting through lava onto a
Depot), Miner Mk.1 to Mk.3 and the Portable Miner (a Drill over a node), and the Storage Containers (Item Vaults).
The machines keep to Satisfactory's time per recipe (`BasinPace`, `BuildingLogic`), take belts at their input ports
and put what they make onto the belt at the output port, and are turned by Create: New Age motors inside that run on
the power grid. A miner really mines its node: its Drill breaks the regrown node block and what the block drops is the
output. The Mk sets how fast it breaks it (Mk.1 half a second, Mk.2 a quarter, Mk.3 a tenth, the unpowered Portable
Miner two seconds) and its built-in Fortune (Mk.2 III, Mk.3 V, by vanilla's ore formula); the node's purity sets how
fast it regrows, which caps every miner. Every player spawns with the Build Gun, and the first thing they build with it
is the HUB (Special tab, 20 Raw Iron, given back when it is dismantled), which has a hand-cranked Craft Station (a Press
and a Mixer over Basins) and two Biomass Burners.

## Power

Power is Create: New Age electricity underneath and SU on top: Satisfactory's MW at **1 MW = 204.8 SU**, with FE
following New Age's own SU-to-energy rate (6 FE a tick per MW at its default), so a New Age motor fed a building's FE
gives exactly that building's SU. Every building, generator, pole and tower has a New Age wire connector on top;
wires are New Age's, strung by hand (Power Towers reach 256 blocks tower to tower, building to building 32; a pole
takes 4, 7 or 10 wires, a machine one). `power/PowerGrids.java` balances every wired network Satisfactory's way each
tick, in place of New Age's own path-finding: generators give, and burn fuel for, only what is used; spare power
charges Power Storage (100 MWh, 100 MW); a shortfall is covered from storage, and past that the fuse blows and the
grid goes dark until it is reset (sneak-use a generator or Power Storage). Lines keep working over unloaded chunks
(`power/WireMap.java`). The generators (`building/BuildingPower.java`): Biomass Burner 30 MW, Coal Generator 75 MW (with
water), Fuel Generator 250 MW, Geothermal Generator (a burst at every eruption of the sulfur geyser under it, sulfuric
acid as waste), Nuclear Power Plant 2,500 MW (Uranium and Plutonium Fuel Rods, Satisfactory's waste), and the Alien
Power Augmenter (500 MW, +10% to its grid, +30% fed Alien Power Matrices). A New Age reactor a player builds burns the
same rods.

## Logistics

Items move on Create's own belts, chutes, funnels, tunnels and pipes, with no speed limit on anything a shaft turns.
A belt no shaft drives moves by itself at its company's self-moving speed (`tweak/SelfMovingBelts.java`), raised by
the Logistics milestones through Satisfactory's belt tiers: Logistics 60 items/min, Logistics Mk.2 120, Mk.3 270, Mk.4
480, Mk.5 780 and Peak Efficiency 1,200 (single items, one per block of belt: 2.5 items/min per RPM). Items run from
the end the belt was started at. An Andesite Tunnel over a belt with belts leaving at its sides splits evenly between
straight on and each side, and a belt running into its side merges in (`tweak/TunnelSplitter.java`). Drone Ports fly
Create packages between ports, burning fire charges for each trip. A Dimensional Depot on an Item Vault uploads the
vault's contents to the company's cloud (8 items a second, at most 320 of an item, both doubled by MAM research),
which members take out from the Cloud button above their inventory, `/depot`, or any depot. The Blueprint Designer
saves what is built in its volume as a Blueprint that places it whole again.

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
