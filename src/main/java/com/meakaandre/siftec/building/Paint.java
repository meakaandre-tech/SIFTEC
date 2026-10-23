package com.meakaandre.siftec.building;

import com.meakaandre.siftec.sink.Shop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a Build Gun building is painted with the Customizer (AWESOME Sink and Shop tab). Buildings are built of orange
 * panels (the primary colour), dark grey industrial iron (the secondary colour) and light grey iron (the casing); paint
 * swaps those for the swatch's colours, the finish's material and the casing skin. A Foundations-tab building takes a
 * material instead (its top and body, a wall's panels, a roof's tiles) and a foundation a floor pattern on its top.
 * Everything else (glass, hazard stripes, the machines inside) stays as built. Saved on the controller as
 * "primary/secondary/finish/skin/material/pattern"; empty is as built.
 */
public record Paint(String primary, String secondary, String finish, String skin, String material, String pattern) {
    public static final Paint NONE = new Paint("orange", "industrial", "painted", "", "", "");
    private static final String INDUSTRIAL = "industrial";
    /** The blocks a Foundations-tab building is built of, which its material and floor pattern change. */
    public static final String FOUNDATION_TOP = "minecraft:light_gray_concrete";

    public Paint(String primary, String secondary, String finish, String skin) {
        this(primary, secondary, finish, skin, "", "");
    }

    public static Paint parse(String saved) {
        if (saved == null || saved.isEmpty()) return NONE;
        String[] p = saved.split("/", -1);
        if (p.length == 4) return new Paint(p[0], p[1], p[2], p[3]);
        if (p.length != 6) return NONE;
        return new Paint(p[0], p[1], p[2], p[3], p[4], p[5]);
    }

    public String write() {
        if (equals(NONE)) return "";
        return primary + "/" + secondary + "/" + finish + "/" + skin + (material.isEmpty() && pattern.isEmpty() ? "" : "/" + material + "/" + pattern);
    }

    /** The same paint with another swatch ("primary/secondary"). */
    public Paint withSwatch(String swatch) {
        String[] p = swatch.split("/", -1);
        return new Paint(p[0], p.length > 1 ? p[1] : INDUSTRIAL, finish, skin, material, pattern);
    }

    public Paint withFinish(String finish) {
        return new Paint(primary, secondary, finish, skin, material, pattern);
    }

    public Paint withSkin(String skin) {
        return new Paint(primary, secondary, finish, skin, material, pattern);
    }

    public Paint withMaterial(String material) {
        return new Paint(primary, secondary, finish, skin, material, pattern);
    }

    public Paint withPattern(String pattern) {
        return new Paint(primary, secondary, finish, skin, material, pattern);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(Blocks.AIR);
    }

    private static Block concrete(String dye) {
        return block("minecraft:" + dye + "_concrete");
    }

    /** What a cell built as this block becomes under this paint (the block itself where paint does not touch it). */
    public BlockState look(BlockState built, boolean storage) {
        return look(built, storage, Integer.MIN_VALUE, 0);
    }

    /** The same for a cell of a template (across from -4, along from 0): a foundation's top also takes the floor pattern at its cell. */
    public BlockState look(BuildingTemplate.Cell cell, boolean storage) {
        return look(cell.state(), storage, cell.x(), cell.z());
    }

    private BlockState look(BlockState built, boolean storage, int x, int z) {
        Block b = built.getBlock(), out = null;
        String id = BuiltInRegistries.BLOCK.getKey(b).toString();
        if (!pattern.isEmpty() && x != Integer.MIN_VALUE && id.equals(FOUNDATION_TOP)) {
            String painted = Patterns.at(pattern, x + Patterns.SIZE / 2, z);
            if (painted != null) out = block(painted);
        }
        if (out == null && !material.isEmpty()) {
            String to = Shop.materialBlock(material, id);
            if (to != null) out = block(to);
        }
        if (out == null) {
            if (b == block("minecraft:orange_concrete")) {
                String finishBlock = Shop.finishes().getOrDefault(finish, "minecraft:{colour}_concrete");
                out = block(finishBlock.replace("{colour}", primary));
            } else if (b == block("create:industrial_iron_block")) {
                out = secondary.equals(INDUSTRIAL) ? b : concrete(secondary);
            } else if (b == Blocks.IRON_BLOCK && !skin.isEmpty()) {
                String skinBlock = storage ? Shop.storageSkins().getOrDefault(skin, Shop.skins().get(skin)) : Shop.skins().get(skin);
                if (skinBlock != null) out = block(skinBlock);
            }
        }
        if (out == null || out == Blocks.AIR) return built;
        return out.withPropertiesOf(built);
    }

    /** True if this block is what the cell should be: as built, or as painted. */
    public boolean fits(BlockState built, Block now, boolean storage) {
        return now == built.getBlock() || now == look(built, storage).getBlock();
    }

    public boolean fits(BuildingTemplate.Cell cell, Block now, boolean storage) {
        return now == cell.state().getBlock() || now == look(cell, storage).getBlock();
    }
}
