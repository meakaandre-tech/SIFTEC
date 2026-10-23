package com.meakaandre.siftec.power;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * One grid as it was balanced on its last tick, in SU: what its generators could give (after any Augmenter boost),
 * what they gave, what its consumers asked for, its storage, and whether its fuse is blown.
 */
public final class GridStats {
    public double capacity, produced, demand, stored, storeCapacity, boost;
    public int generators, consumers, stores, connectors;
    public boolean tripped, powered;
    /** True when the consumers asked for more than the generators and storage could give, and there was nothing to trip. */
    public boolean unpowered;

    /** "Grid: 1,234 of 6,144 SU used (4,915 SU free), storage 12.0 / 20,480 SUh" or "Grid: fuse blown, ..." */
    public Component line() {
        String head = tripped ? "siftec.power.grid_tripped" : "siftec.power.grid";
        Component storage = storeCapacity > 0 ? Component.translatable("siftec.power.grid_storage", Power.suHours(stored), Power.suHours(storeCapacity))
            : Component.empty();
        return Component.translatable(head, Power.fmt(Math.min(demand, capacity + (stored > 0 ? demand : 0))), Power.fmt(capacity), Power.fmt(Math.max(0, capacity - demand)), storage);
    }

    public String describe() {
        return String.format(Locale.ROOT, "capacity %.1f SU, produced %.1f SU, demand %.1f SU, stored %.0f of %.0f SU-ticks, boost %.2f, %d generators, %d consumers, %d stores, %d connectors, tripped %s",
            capacity, produced, demand, stored, storeCapacity, boost, generators, consumers, stores, connectors, tripped);
    }
}
