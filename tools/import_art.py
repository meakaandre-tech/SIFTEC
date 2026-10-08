#!/usr/bin/env python3
"""Cuts the sprite sheets from the "SIFTEC Art & Sprite Set" canvas (tools/art/) into the mod's 16x16 textures.
The sheets are drawn at 4x, so every sprite pixel is a 4x4 square; each one is read back as a single pixel."""
import os
from PIL import Image

HERE = os.path.dirname(__file__)
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "siftec", "textures")

BLOCK_SHEETS = {
    "blocks_base.png": "hub wormhole_gateway mam awesome_sink awesome_shop equipment_workshop claim_marker dimensional_depot steel_casing "
                       "drone_port landing_pad main_portal satellite_portal blueprint_designer blueprint_designer_mk3",
    "blocks_machines.png": "portable_miner miner_mk1 miner_mk2 miner_mk3 resource_well_extractor converter particle_accelerator furnace_engine "
                           "hub_engine geyser_engine power_pole power_tower power_storage speed_governor radar_tower",
    "blocks_nodes.png": "iron_node copper_node zinc_node coal_node limestone_node sulfur_node bauxite_node quartz_node sam_node oil_node "
                        "oil_well nitrogen_node node_rock blue_power_slug_block yellow_power_slug_block purple_power_slug_block "
                        "mercer_sphere_block somersloop_block crash_site_block",
}
ITEM_SHEETS = {
    "items_materials.png": "raw_bauxite crushed_bauxite aluminum_scrap aluminum_ingot ficsite_ingot brass_blend copper_powder silica gypsum "
        "quartz_crystal compacted_coal petroleum_coke biomass solid_biofuel rubber plastic fabric toxic_residue iron_rod screw wire cable "
        "quickwire reinforced_iron_plate steel_beam steel_pipe encased_industrial_beam alclad_aluminum_sheet aluminum_casing modular_frame "
        "heavy_modular_frame fused_modular_frame rotor stator motor turbo_motor heat_sink cooling_system empty_fluid_tank "
        "packaged_nitrogen_gas pressure_conversion_cube",
    "items_tech.png": "circuit_board ai_limiter high_speed_connector computer supercomputer crystal_oscillator radio_control_unit "
        "electromagnetic_control_rod smart_plating versatile_framework automated_wiring modular_engine adaptive_control_unit "
        "assembly_director_system magnetic_field_generator thermal_propulsion_rocket nuclear_pasta biochemical_sculptor "
        "ballistic_warp_drive ai_expansion_server incomplete_ai_expansion_server blue_power_slug yellow_power_slug purple_power_slug "
        "power_shard somersloop mercer_sphere hard_drive dna_capsule sam reanimated_sam sam_fluctuator ficsite_trigon time_crystal "
        "dark_matter_crystal excited_photonic_matter singularity_cell superposition_oscillator incomplete_superposition_oscillator "
        "neural_quantum_processor incomplete_neural_quantum_processor",
    "items_gear.png": "jetpack hover_pack parachute hazmat_suit gas_mask blade_runners gas_filter iodine_infused_filter node_scanner "
        "object_scanner hub_planner blueprint cardboard_drone power_line toxic_shot apple_jam sweet_berry_jam glow_berry_jam melon_jam "
        "pickled_beetroot pickled_cabbage pickled_carrot pickled_kelp pickled_onion pickled_pumpkin pickled_tomato",
    # the sheet's last ten are buckets; the mod draws those from the game's own bucket instead (see bucket_overlay)
}
SCALE = 4
uneven = []


def shrink(sheet, x, y, name):
    """One 64x64 square of the sheet back to 16x16."""
    out = Image.new("RGBA", (16, 16))
    for py in range(16):
        for px in range(16):
            block = [sheet.getpixel((x + px * SCALE + dx, y + py * SCALE + dy)) for dy in range(SCALE) for dx in range(SCALE)]
            if len(set(block)) > 1:
                uneven.append(name)
            out.putpixel((px, py), max(set(block), key=block.count))
    return out


def save(image, kind, name):
    os.makedirs(os.path.join(OUT, kind), exist_ok=True)
    image.save(os.path.join(OUT, kind, name + ".png"))


# where the liquid shows in the game's bucket: the opening at the top, as rows of (first x, last x)
OPENING = {2: (5, 10), 3: (3, 12), 4: (3, 12), 5: (5, 10)}


def bucket_overlay():
    """A grey layer of liquid in the bucket's opening; each fluid's bucket tints it its own colour."""
    out = Image.new("RGBA", (16, 16))
    shade = {2: 236, 3: 214, 4: 196, 5: 172}
    for y, (x0, x1) in OPENING.items():
        for x in range(x0, x1 + 1):
            edge = x in (x0, x1)
            v = shade[y] - (18 if edge else 0)
            out.putpixel((x, y), (v, v, v, 255))
    save(out, "item", "fluid_in_bucket")


def main():
    blocks, items = [], []
    for file, ids in BLOCK_SHEETS.items():
        sheet = Image.open(os.path.join(HERE, "art", file)).convert("RGBA")
        for i, bid in enumerate(ids.split()):
            # each card: inventory picture, then top, front and side at 4x
            x, y = (i % 2) * 336, (i // 2) * 104
            for face, dx in (("top", 116), ("front", 192), ("side", 268)):
                save(shrink(sheet, x + dx, y + 20, bid), "block", f"{bid}_{face}")
            blocks.append(bid)
    for file, ids in ITEM_SHEETS.items():
        sheet = Image.open(os.path.join(HERE, "art", file)).convert("RGBA")
        for i, iid in enumerate(ids.split()):
            save(shrink(sheet, (i % 10) * 64, (i // 10) * 64, iid), "item", iid)
            items.append(iid)
    bucket_overlay()
    print(f"{len(blocks)} blocks, {len(items)} items; sprites not on the 4x grid: {sorted(set(uneven))}")


if __name__ == "__main__":
    main()
