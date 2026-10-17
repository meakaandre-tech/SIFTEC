# SIFTEC

Spatial Interlink and Factory Technology Engineering Corporation: the custom mod of a Satisfactory-inspired
Create modpack, where a company of players works through Satisfactory's tiers and milestones and builds a
wormhole instead of a space elevator.

Fabric, Minecraft 26.3, Java 25. Needs Fabric API, Create Fly (mod id `create`) and Create: New Age (mod id
`create_new_age`, the fabric-26.3 build of meakaandre-tech/Create-New-Age); the pack also runs
Create: Diesel Generators, Create: Gunsmithing and Farmer's Delight, which the mod's data
names (recipes that name another mod load only when that mod is installed).

What it adds: the HUB with milestones and wormhole phases, companies and land claims, per-company recipe and
item locks, resource nodes, the Build Gun and its buildings, the MAM research trees, the power grid, the Equipment
Workshop, the AWESOME Sink and Shop, blueprints, and the parts and fluids of Satisfactory's production
chain.

The Build Gun's buildings are multiblocks with real Create machines inside (`building/`): the Constructor (a Press
over a Basin, its recipe picked by the controller's filter), the Smelter (an Encased Fan blasting through lava onto a
Depot), Miner Mk.1 to Mk.3 and the Portable Miner (a Drill over a node), and the Storage Containers (Item Vaults).
The machines keep to Satisfactory's time per recipe (`BasinPace`, `BuildingLogic`), take belts at their input ports
and put what they make onto the belt at the output port, and are turned by Create: New Age motors inside that run on
the power grid. A node is the real ore block itself (minecraft:iron_ore, create:zinc_ore, a stone node's own stone; a
SIFTEC ore only for bauxite, uranium, SAM and sulfur), made a node by its position (`node/NodeSites`): mined by hand or by
a miner it drops exactly what that block drops (Fortune and Silk Touch as usual) and leaves air, which grows back into
the same block after its purity's time, kept through unloads and restarts. Nothing can be placed or poured into an empty
node, and pistons, contraptions and explosions leave its ore alone; if something gets into the spot anyway (a falling
block, anything with a block entity), the node waits for it to be cleared and never deletes it. A node's position is
never building space: no building covers one, grown or empty, a miner takes only the node in its own node cell, and a
Death Crate never goes on one. A miner really mines its node: its Drill breaks the
regrown ore block and what the block drops is the output, and it waits while the spot is air. The Mk sets how fast it breaks it (Mk.1 half a second, Mk.2 a quarter, Mk.3 a tenth, the unpowered Portable
Miner two seconds) and its built-in Fortune (Mk.2 III, Mk.3 V, by vanilla's ore formula); the node's purity sets how
fast it regrows, which caps every miner. Every player spawns with the Build Gun, and the first thing they build with it
is the HUB (Special tab, 20 Raw Iron, given back when it is dismantled), which has a hand-cranked Craft Station (a Press
and a Mixer over Basins) and two Biomass Burners.

## Companies and claims

Everything a player unlocks belongs to their company: milestones, Wormhole phases, MAM research, which Build Gun
buildings can be placed (`Buildings.locked`) and how fast self-moving belts run. The company's first HUB claims the 5×5
chunks around it and each Claim Marker the 3×3 around it; inside a claim only members can build, break or use
anything, and dispensers, open-ended pipes and contraptions putting their blocks back do nothing across its border. When a company folds into another (its last member joins it), everything stamped with the old company's id answers to the new one, and its Gateway, points and coupons printed come along. The Build Gun checks the land under a building's whole box before placing it, so nothing is built on or
across another company's claim. A company's building is dismantled only by its own members, wherever it stands; one
nobody owns comes down only for players the land lets build there. Creative players skip claims, unlocks and costs.
Only the Build Gun takes a building down: no hand, wrench, Deployer (but the building's own), drill, piston, contraption,
explosion, Wither or Blueprint Designer's sneak-clear takes one of its blocks. The same holds for the HUB and the Wormhole Gateway. A block lost anyway (a command, another mod)
brings the building down with its refund and contents, but gives nothing back for itself. Dismantled, a building's tanks
come back as packaged fluid (1 m3 a package; other fluids as full buckets).

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
vault's contents to the company's cloud at Satisfactory 1.0's rates (15 items a minute and one stack of each item,
raised by the MAM's Upload Upgrade and Depot Expansion research to 240 a minute and five stacks), which members take
out from the Cloud button above their inventory, `/depot`, or any depot; with the Manual Depot Uploader research they
shift-click their own items in. The Blueprint Designer
saves what is built in its volume as a Blueprint that places it whole again.

## Vehicles

The Tractor (Vehicular Transport), the Truck (Logistics Mk.4) and the Explorer (the Quartz tree's Display Systems) are
single entities, not contraptions (`vehicle/`): the Build Gun puts one down for Satisfactory's cost from its Logistics
tab and its dismantle mode takes it back with the cost and its cargo. They are drawn only from Create's own models and
textures (`client/VehicleRenderer.java`): casings, a seat, fluid tanks, Item Vaults, cogwheels for wheels that turn as
they roll and, on the Truck, belts for treads that run. Use one to get in (W and S throttle, A and D steer, Space
brakes), sneak-use it for its storage (Tractor 27 slots, Truck 54, Explorer 9), and use it with a bucket of any fuel the Fuel
Generator burns (Fuel, Liquid Biofuel, Turbofuel and up) to fill its tank; it burns its Satisfactory MW while the throttle is open.
The Explorer is the fastest.

The Truck Station is a building with two Item Vaults and a fuel tank; using its terminal switches it between loading
vehicles and unloading them. Press V while driving to record a route, drive it once through the docks (the ground in
front of each station) you want it to stop at, and press V again. With nobody in the seat it then drives the route by
itself in a loop, stopping five seconds at each dock while the station loads or unloads it and fills its tank. A station serves only its own company's vehicles, and only its company switches it. A vehicle
that falls out of the bottom of the world comes back to the dock of the Truck Station it last stopped at (else beside
its company's HUB), cargo and all. Its storage window stays open only near it and only for its company's members.

## Progression

Satisfactory 1.0's progression, owned by the company: Tier 0's six HUB Upgrades in order, then Tiers 1 to 9 with 1.0's
48 milestones, costs and lock times (Hypertubes is Contraption Transit: Create's elevators, rope pulleys and gantries).
Every building and recipe stays locked until its milestone or MAM node. Inventories stay vanilla size: no milestone
gives slots or a backpack. The Wormhole Gateway (HUB Upgrade 6, built with the Build Gun for the Space Elevator's
parts) takes the phases that open Tiers 3, 5, 7 and 9 and grows like the Space Elevator: a bare platform with the
terminal and two power hookups, then the ring's cradle and supports, the bottom third of a standing ring gate, the
second third, the closed ring with energy conduits, and with Phase 5 a lit ring and a swirling portal: walking in
takes the company's players to The Sift, and the game goes on as free play.

The MAM (Field Research) belongs to the company that placed it and only its members use it, once their company has done Field Research. It is fed by hand and every node takes 3 seconds; a node costs the same whatever the company's size (the 50% per member applies to milestones and Wormhole phases only). Seven trees, each shown once one of its
starting items is brought in: Alien Megafauna (mob drops), Caterium (crushed zinc: brass), Mycelia, Nutrients (wild
food), Quartz, Sulfur and Alien Technology, plus the Hard Drives. A node may need nodes in other trees, or any one of
several. Vanilla items come from research and machines, with no End trip or ancient debris: the Eye of Ender and the
Shulker Shell from the Assembler, the Totem, Elytra, Trident, Heart of the Sea and Netherite Ingot from the Manufacturer,
and the Elytra Backpack (an Elytra and a Copper Backtank in one, refilled from a placed backtank). The inhalers heal.

The Map comes with Field Research (see Map below). There is no overclocking: no Power Shards, power slugs or
Production Amplifier.

## Exploration

Mercer Spheres, Somersloops and crash sites are placed from the world seed (`collect/`), one possible spot per
96-block cell, so the Object Scanner finds them in land nobody has visited, and each company can collect each one once;
collecting one starts its MAM tree (Alien Technology, or the Hard Drive page). Mercer Spheres are common and float over
a small patch of The Sift's siftslate. Somersloops are rare: in a pocket deep underground well away from spawn, in some
far crash sites, and in every Ancient City's portal frame (found from the seed as /locate does, placed when the city's
centre loads). Crash sites are cargo pods with wreckage, each holding a Hard Drive: near spawn they open freely, further
out some want parts carried to them and, past 900 blocks, some want a Create: New Age wire from a grid with enough power
to spare strung to the connector beside them. The Object Scanner is switched with sneak-use between crash sites, Mercer
Spheres and Somersloops (after their analysis research), hostile mobs, and the wild food the Nutrients start nodes add:
ripe sweet berry bushes, apple trees and glow berries in loaded land. Aimed at a resource node (or wearing Create's Engineer's Goggles
and looking at one), it shows the node's type, purity and, while it is empty, how long until it grows back.

The Equipment Workshop, the AWESOME Sink and Shop and the Radar Tower are Build Gun buildings in the Special tab; using
one opens it. The Workshop builds equipment by hand from the parts carried (machines make the same, the Manufacturer's
way): the Object Scanner, Jetpack, Hover Pack, Blade Runners, Parachute, Gas Mask, Hazmat Suit, the inhalers, the
Xeno-Zapper (a stun jolt), and Gunsmithing's nail gun and hammer as the Rebar Gun and Xeno-Basher. The Radar Tower
(30 MW) clears its company's map over about a kilometre round it once a day while powered, so the crash sites, nodes
and geysers there show, and lists the nearest node and collectible of each kind when used.

## Map

SIFTEC has its own map (`map/`, `client/map/`) in place of Xaero's Minimap, laid out like Satisfactory's. A compass
strip runs across the top of the screen from the start: the bearing, the HUB, placed markers, pinned things with their
distance, company players, and scanned nodes and crash sites nearby. Vanilla's player locator bar is switched off
(company players are on the compass instead). Field Research opens a small square minimap at the top right (N hides
it) and the fullscreen map (M): soft biome colours, height shading and contour lines, of the Overworld's surface only,
with a filter panel down the side as Satisfactory's (players, map markers, resource nodes by type with purity, resource
wells, geysers, crash sites, artifacts, key buildings). Fog of war is shared by the company: what any member has been
near is mapped, and a Radar Tower's sweep clears its whole range, filling land nobody has loaded from the world
generator. Markers are placed from the map (right click) with an icon, a colour and a name, private or shared with the
company; left click pins anything to the compass. Dying leaves a private death marker, pinned at once: on the Death
Crate while there is one, otherwise where it happened (the last three of those are kept).

It stays light: the terrain is kept at one pixel for 4 by 4 blocks in tiles of 512 by 512 blocks, recorded only when a
chunk loads or a few seconds after a block in it changes (`map/MapTerrain.java`), saved under `data/siftec/map`, and
sent to a client only when it holds an older copy; clients keep their tiles on disk (`siftec/map/<map id>` in the game
folder), so a world's map is there at once on the next visit.

## Backpack and death crate

The inventory is a Traveler's Backpack (`compat/`, only when that mod is installed; the pack always has it). Every player
wears one from spawn and it is bound to them: it cannot be taken off, swapped, dropped or placed, and nobody can craft
one (every backpack, design, tier upgrade and sleeping bag is switched off). E opens the backpack window in place of the
vanilla inventory; the vanilla hotbar stays as it is, and the window has the armour and offhand slots in a column on its
left. Its tier grows with the company at four of the milestones where Satisfactory gives inventory slots: Leather (27
slots) from the start, Iron (45) at Field Research, Gold (63) at Vehicular Transport, Diamond (81) at Jetpack and
Netherite (99) at Hover Pack. One upgrade slot holds a built-in 2x2 crafting grid that stays in; using a Crafting
Upgrade turns it into 3x3. The upgrades are unlocked by MAM research (Crafting at Structural Analysis, Tanks and the
hose at Copper Backtank, Pickup at Quickwire, Lantern at Silica, Magnet at Crystal Oscillator, Jukebox at Display
Systems, Feeding at Preserved Foods, Smoker at Automated Cooking, Furnace and Blast Furnace at Ignimbrite, Void and Refill
at Pocket Storage), built at the Equipment Workshop from the pack's parts (the Assembler makes them too), and a locked
one does not fit an upgrade slot. Milestone payments, the Workshop, the Build Gun (buildings, vehicles, the HUB), blueprints,
jetpack fuel, filters and radiation see what the backpack holds. Its crafting grid keeps to the recipe locks, shift-craft
included, and its window has the inventory's Cloud button.

Dying leaves a Death Crate where it happened (`death/`), as in Satisfactory: everything carried goes into it, the hotbar,
inventory, armour, offhand and everything in the backpack, while the backpack and its upgrades stay on. Clicking it puts
every stack straight back into the slot it came from (whatever no longer fits stays in the crate); what lay on the
backpack's crafting grid goes back on it. Only its owner opens it, inside another company's claim too; it cannot be
broken, blown up or pushed and never despawns, and the map shows it as the death marker until it is emptied. It goes on
the first free spot of the column and never over a block or an ore node (with no free spot at all the things drop). The
Build Gun stays out of it wherever it was carried. With keepInventory on nothing changes, and Curse of Vanishing items vanish as in vanilla.

## AWESOME Sink and Shop

The Sink (30 MW) destroys whatever its input port or a player's hand gives it, except the radioactive and toxic waste,
and pays its company points (`sink/Sink.java`): Satisfactory's values for its parts, the AWESOME Sink and Shop tab's for
vanilla and other mods' items, the item's rarity otherwise, and 25 more per enchantment level. Its screen prints the
points as FICSIT Coupons on Satisfactory 1.0's curve. The Shop sells cosmetics only, unlocked for the whole company: the
Customizer (free), colour swatch slots, paint finishes and casing skins it paints Build Gun buildings with, statues,
lights, office furniture and cups (taken from the Shop for a few parts once owned).

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
  fuels, the Converter and Particle Accelerator, the AWESOME Sink's values and the Shop's cosmetics and decorations.
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
