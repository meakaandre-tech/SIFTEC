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
 * swaps those for the swatch's colours, the finish's material and the casing skin. Everything else (glass, hazard
 * stripes, the machines inside) stays as built. Saved on the controller as "primary/secondary/finish/skin"; empty is
 * as built.
 */
public record Paint(String primary, String secondary, String finish, String skin) {
    public static final Paint NONE = new Paint("orange", "industrial", "painted", "");
    private static final String INDUSTRIAL = "industrial";

    public static Paint parse(String saved) {
        if (saved == null || saved.isEmpty()) return NONE;
        String[] p = saved.split("/", -1);
        if (p.length != 4) return NONE;
        return new Paint(p[0], p[1], p[2], p[3]);
    }

    public String write() {
        return equals(NONE) ? "" : primary + "/" + secondary + "/" + finish + "/" + skin;
    }

    /** The same paint with another swatch ("primary/secondary"). */
    public Paint withSwatch(String swatch) {
        String[] p = swatch.split("/", -1);
        return new Paint(p[0], p.length > 1 ? p[1] : INDUSTRIAL, finish, skin);
    }

    public Paint withFinish(String finish) {
        return new Paint(primary, secondary, finish, skin);
    }

    public Paint withSkin(String skin) {
        return new Paint(primary, secondary, finish, skin);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(Blocks.AIR);
    }

    private static Block concrete(String dye) {
        return block("minecraft:" + dye + "_concrete");
    }

    /** What a cell built as this block becomes under this paint (the block itself where paint does not touch it). */
    public BlockState look(BlockState built, boolean storage) {
        Block b = built.getBlock(), out = null;
        if (b == Blocks.ORANGE_CONCRETE) {
            String finishBlock = Shop.finishes().getOrDefault(finish, "minecraft:{colour}_concrete");
            out = block(finishBlock.replace("{colour}", primary));
        } else if (b == block("create:industrial_iron_block")) {
            out = secondary.equals(INDUSTRIAL) ? b : concrete(secondary);
        } else if (b == Blocks.IRON_BLOCK && !skin.isEmpty()) {
            String skinBlock = storage ? Shop.storageSkins().getOrDefault(skin, Shop.skins().get(skin)) : Shop.skins().get(skin);
            if (skinBlock != null) out = block(skinBlock);
        }
        if (out == null || out == Blocks.AIR) return built;
        return out.defaultBlockState();
    }

    /** True if this block is what the cell should be: as built, or as painted. */
    public boolean fits(BlockState built, Block now, boolean storage) {
        return now == built.getBlock() || now == look(built, storage).getBlock();
    }
}
