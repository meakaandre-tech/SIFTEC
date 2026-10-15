package com.meakaandre.siftec.node;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a node site is made of (the Nodes and World tab of the design): the <b>patch</b>, flat unbreakable ground of
 * the node's own natural stone; the <b>clump</b>, a small breakable pile of that stone, the ore block and the clump
 * ore block, which never comes back and has to be mined before a miner goes on; and the <b>node</b>, the real ore
 * block itself on the patch (minecraft:iron_ore, create:zinc_ore, the stone block of a stone node; a SIFTEC ore block
 * only where neither the game nor Create has one), made a node by its position ({@link NodeSites}): mined, it drops
 * what that block always drops and leaves air that grows back into the same block. Every node type has one look,
 * except stone nodes, which have one per stone. tools/gen_assets.py (STONE and DEPOSIT) draws the same table; keep
 * the two in step.
 *
 * @param id     the look's name
 * @param patch  the patch's stone block (its patch block is {@code <patch name>_patch})
 * @param node   the node block: the real ore block (for fluid nodes, the SIFTEC well block)
 * @param ore    the ordinary ore block the clump holds some of, or "" if there is none
 * @param clump  the clump ore block: the raw ore block, or what the resource is in block form
 * @param drop   what mining the node gives without Fortune (the node block's own loot); "" for fluid nodes
 */
public record NodeLook(String id, NodeType type, String patch, String node, String ore, String clump, String drop) {
    private static final Map<String, NodeLook> ALL = new LinkedHashMap<>();

    static {
        //  look        type                 patch                     node                            clump's ore                    clump ore block              drop
        add("iron", NodeType.IRON, "minecraft:tuff", "minecraft:iron_ore", "minecraft:iron_ore", "minecraft:raw_iron_block", "minecraft:raw_iron");
        add("copper", NodeType.COPPER, "minecraft:granite", "minecraft:copper_ore", "minecraft:copper_ore", "minecraft:raw_copper_block", "minecraft:raw_copper");
        for (StoneKind kind : StoneKind.values()) add(kind.id(), NodeType.STONE, kind.block, kind.block, "", kind.block, kind.block);
        add("coal", NodeType.COAL, "minecraft:stone", "minecraft:coal_ore", "minecraft:coal_ore", "minecraft:coal_block", "minecraft:coal");
        add("zinc", NodeType.ZINC, "minecraft:andesite", "create:zinc_ore", "create:zinc_ore", "create:raw_zinc_block", "create:raw_zinc");
        add("oil", NodeType.OIL, "minecraft:basalt", "siftec:oil_well", "", "minecraft:obsidian", "");
        add("bauxite", NodeType.BAUXITE, "minecraft:cinnabar", "siftec:bauxite_ore", "", "minecraft:terracotta", "siftec:raw_bauxite");
        add("nitrogen", NodeType.NITROGEN, "minecraft:diorite", "siftec:nitrogen_node", "", "minecraft:packed_ice", "");
        add("uranium", NodeType.URANIUM, "minecraft:blackstone", "siftec:uranium_ore", "", "siftec:uranium_ore", "siftec:uranium");
        add("sam", NodeType.SAM, "minecraft:deepslate", "siftec:sam_ore", "", "minecraft:amethyst_block", "siftec:sam");
        add("quartz", NodeType.QUARTZ, "minecraft:netherrack", "minecraft:nether_quartz_ore", "minecraft:nether_quartz_ore", "minecraft:quartz_block", "minecraft:quartz");
        add("sulfur", NodeType.SULFUR, "minecraft:sulfur", "siftec:sulfur_ore", "", "minecraft:potent_sulfur", "cgs:sulfur");
    }

    private static void add(String id, NodeType type, String patch, String node, String ore, String clump, String drop) {
        ALL.put(id, new NodeLook(id, type, patch, node, ore, clump, drop));
    }

    public static NodeLook of(Node node) {
        return ALL.get(node.type() == NodeType.STONE ? StoneKind.of(node).id() : node.type().id());
    }

    /** The look of a type that has only one (any type but stone). */
    public static NodeLook of(NodeType type) {
        return type == NodeType.STONE ? ALL.get(StoneKind.GRANITE.id()) : ALL.get(type.id());
    }

    public static NodeLook byId(String id) {
        return ALL.get(id);
    }

    public static Collection<NodeLook> all() {
        return Collections.unmodifiableCollection(ALL.values());
    }

    /** Every patch stone's name, once each, in table order. */
    public static List<String> patches() {
        List<String> out = new ArrayList<>();
        for (NodeLook look : ALL.values()) if (!out.contains(look.patchName())) out.add(look.patchName());
        return out;
    }

    /** "tuff", "dripstone": the patch block is {@code siftec:<this>_patch}. */
    public String patchName() {
        String path = Identifier.parse(patch).getPath();
        return path.endsWith("_block") ? path.substring(0, path.length() - "_block".length()) : path;
    }

    public boolean fluid() {
        return type.isFluid();
    }

    /** The item mining the node gives. Air for fluid nodes, or if the mod that owns the item is missing. */
    public Item output() {
        if (drop.isEmpty()) return Items.AIR;
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(drop)).orElse(Items.AIR);
    }

    /** The node block (the ore block, or for a fluid node its well), or null if the mod that owns it is missing. */
    public @Nullable Block nodeBlock() {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(node)).orElse(null);
        return block == Blocks.AIR ? null : block;
    }

    /** The node block as it stands on a site: an ore node's plain default ore block. */
    public BlockState nodeState() {
        Block block = nodeBlock();
        return block == null ? patchStone() : block.defaultBlockState();
    }

    /** True if this is this look's node block (any of its states). */
    public boolean isNodeBlock(BlockState state) {
        Block block = nodeBlock();
        return block != null && state.is(block);
    }

    public BlockState patchStone() {
        return state(patch, Blocks.STONE.defaultBlockState());
    }

    /** The clump's ore block: the ordinary ore, or the clump ore block where there is no ordinary ore. */
    public BlockState oreState() {
        return ore.isEmpty() ? clumpState() : state(ore, clumpState());
    }

    public BlockState clumpState() {
        return state(clump, patchStone());
    }

    private static BlockState state(String id, BlockState fallback) {
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
        return block == null || block == Blocks.AIR ? fallback : block.defaultBlockState();
    }
}
