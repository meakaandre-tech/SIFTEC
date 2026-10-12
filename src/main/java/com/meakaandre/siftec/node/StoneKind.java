package com.meakaandre.siftec.node;

import java.util.Locale;

/**
 * The natural stones a stone node can be (never deepslate, cobblestone or plain stone). Which one a stone node is
 * comes from its position, so it is the same every time the node map is worked out.
 */
public enum StoneKind {
    GRANITE("minecraft:granite"), DIORITE("minecraft:diorite"), ANDESITE("minecraft:andesite"), TUFF("minecraft:tuff"),
    CALCITE("minecraft:calcite"), DRIPSTONE("minecraft:dripstone_block"), ASURINE("create:asurine"), CRIMSITE("create:crimsite"),
    OCHRUM("create:ochrum"), VERIDIUM("create:veridium"), SCORIA("create:scoria"), SCORCHIA("create:scorchia");

    /** The stone block: the patch, the clump, the node and what the node drops. */
    public final String block;

    StoneKind(String block) {
        this.block = block;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static StoneKind of(Node node) {
        return values()[(int) Long.remainderUnsigned(NodeMap.mix(node.key() ^ 0x570E5L), values().length)];
    }
}
