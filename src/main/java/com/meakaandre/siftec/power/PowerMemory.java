package com.meakaandre.siftec.power;

import com.meakaandre.siftec.Siftec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every member of a grid was doing when it was last on a loaded connector, kept so a grid goes on the same when
 * part of it is unloaded. In Satisfactory the whole factory runs wherever the player is; here a generator, Power
 * Storage or consumer whose chunk unloads keeps its part in its grid as it last was ({@link Remembered}): a generator
 * gives what it last could for as long as the fuel it held lasts (a Geothermal Generator its geyser's average), an
 * Alien Power Augmenter keeps its fed boost for as long as its matrices last, a consumer asks for what it last asked, a
 * Power Storage charges and drains, and a blown fuse is blown there too. When it loads again, what changed while it was
 * away (its charge, its fuse, the fuel and matrices it used) is handed to the real block, and from then on the real
 * block counts again.
 * <p>
 * Kept by the position of each member (a building's controller, the block under a connector), with the connector it
 * hangs on; saved with the world, so a line whose far end never loads after a restart still has it. A connector that
 * is broken forgets its members.
 */
public class PowerMemory extends SavedData {
    /**
     * One member, saved as longs: connector, member, then capacity, demand, stored, store capacity, charge rate, boost,
     * fuel reserve and fuel spent while away (raw double bits), flags, and the fed boost's ticks left and used.
     */
    private record Entry(String dimension, List<Long> data) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dim").forGetter(Entry::dimension),
            Codec.LONG.listOf().fieldOf("data").forGetter(Entry::data)
        ).apply(i, Entry::new));
    }

    public static final Codec<PowerMemory> CODEC = Entry.CODEC.listOf().xmap(PowerMemory::new, PowerMemory::entries);
    private static final SavedDataType<PowerMemory> TYPE = new SavedDataType<>(Siftec.id("power_memory"), PowerMemory::new, CODEC, null);
    private static final int BOOSTABLE = 1, FUSE = 2, TRIPPED = 4, AWAY = 8;

    /** By dimension, by member position. */
    private final Map<String, Long2ObjectOpenHashMap<Remembered>> members = new HashMap<>();

    public PowerMemory() {
    }

    private PowerMemory(List<Entry> entries) {
        for (Entry e : entries) {
            List<Long> d = e.data();
            if (d.size() < 13) continue;
            Remembered r = new Remembered(this, d.get(0), d.get(1));
            r.capacity = Double.longBitsToDouble(d.get(2));
            r.demand = Double.longBitsToDouble(d.get(3));
            r.stored = Double.longBitsToDouble(d.get(4));
            r.storeCapacity = Double.longBitsToDouble(d.get(5));
            r.chargeRate = Double.longBitsToDouble(d.get(6));
            r.boost = Double.longBitsToDouble(d.get(7));
            r.reserve = Double.longBitsToDouble(d.get(8));
            r.spent = Double.longBitsToDouble(d.get(9));
            long f = d.get(10);
            r.boostable = (f & BOOSTABLE) != 0;
            r.fuse = (f & FUSE) != 0;
            r.tripped = (f & TRIPPED) != 0;
            r.away = (f & AWAY) != 0;
            r.boostLeft = d.get(11);
            r.boostUsed = d.get(12);
            dim(e.dimension()).put(r.key, r);
        }
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        members.forEach((dim, map) -> map.values().forEach(r -> out.add(new Entry(dim, List.of(r.connector, r.key,
            Double.doubleToRawLongBits(r.capacity), Double.doubleToRawLongBits(r.demand), Double.doubleToRawLongBits(r.stored),
            Double.doubleToRawLongBits(r.storeCapacity), Double.doubleToRawLongBits(r.chargeRate), Double.doubleToRawLongBits(r.boost),
            Double.doubleToRawLongBits(r.reserve), Double.doubleToRawLongBits(r.spent),
            (long) ((r.boostable ? BOOSTABLE : 0) | (r.fuse ? FUSE : 0) | (r.tripped ? TRIPPED : 0) | (r.away ? AWAY : 0)), r.boostLeft, r.boostUsed)))));
        return out;
    }

    public static PowerMemory get(ServerLevel level) {
        return level.getServer().getDataStorage().computeIfAbsent(TYPE);
    }

    private static String key(Level level) {
        return level.dimension().identifier().toString();
    }

    private Long2ObjectOpenHashMap<Remembered> dim(String dimension) {
        return members.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
    }

    /** The remembered member at this position, or null. */
    public @Nullable Remembered at(Level level, BlockPos member) {
        return dim(key(level)).get(member.asLong());
    }

    /** Every remembered member of a level by the connector it hangs on, for working out the grids. */
    Long2ObjectOpenHashMap<List<Remembered>> byConnector(Level level) {
        Long2ObjectOpenHashMap<List<Remembered>> out = new Long2ObjectOpenHashMap<>();
        for (Remembered r : dim(key(level)).values()) {
            List<Remembered> list = out.get(r.connector);
            if (list == null) out.put(r.connector, list = new ArrayList<>());
            list.add(r);
        }
        return out;
    }

    /** A remembered member on a connector that is not live: it stands in for the real one until that is. */
    static Remembered standIn(Remembered r) {
        r.away = true;
        return r;
    }

    /**
     * A real member found on a loaded connector: its memory (made now if new). If it was away, what changed meanwhile
     * goes to the real block first.
     */
    Remembered track(Level level, BlockPos connector, BlockPos member, PowerMember real) {
        Long2ObjectOpenHashMap<Remembered> map = dim(key(level));
        Remembered r = map.get(member.asLong());
        if (r == null) {
            r = new Remembered(this, connector.asLong(), member.asLong());
            map.put(r.key, r);
            setDirty();
        }
        r.connector = connector.asLong();
        if (r.away) {
            r.away = false;
            if (r.fuse && real.holdsFuse()) real.trip(r.tripped);
            if (r.storeCapacity > 0 && real.storeCapacity() > 0) real.store(r.stored - real.stored());
            if (r.spent > 0) real.spend(r.spent);
            if (r.boostUsed > 0) real.spendBoost((int) Math.min(Integer.MAX_VALUE, r.boostUsed));
            r.spent = 0;
            r.boostUsed = 0;
            setDirty();
        }
        return r;
    }

    /** The memory of a member whose own block is not loaded though its connector is (a building across a chunk border), or null. */
    @Nullable Remembered away(Level level, BlockPos member) {
        Remembered r = at(level, member);
        if (r != null) r.away = true;
        return r;
    }

    /** Forgets a member that is no longer on its connector (the connector now stands on something else). */
    void drop(Level level, Remembered r) {
        Long2ObjectOpenHashMap<Remembered> map = dim(key(level));
        if (map.get(r.key) != r) return;
        map.remove(r.key);
        setDirty();
    }

    /** A connector was broken: its members are gone from the grid. */
    public void forget(Level level, BlockPos connector) {
        long c = connector.asLong();
        if (dim(key(level)).values().removeIf(r -> r.connector == c)) setDirty();
    }

    /** How many members are remembered and how many of them stand in for an unloaded one, for the selftests. */
    public String describe(Level level) {
        int away = 0;
        for (Remembered r : dim(key(level)).values()) if (r.away) away++;
        return dim(key(level)).size() + " remembered, " + away + " away";
    }

    /**
     * A member as it last was on a loaded connector, standing in for it while it is away. Everything is what the real
     * member last said; its storage charges and drains, and its fuse trips and resets, as the real one's would.
     */
    public static final class Remembered implements PowerMember {
        private final PowerMemory owner;
        long connector;
        final long key;
        double capacity, demand, stored, storeCapacity, chargeRate, boost;
        /** SU-ticks of fuel it had when it went away, and what it has given since (burnt from its fuel when it is back). */
        double reserve = Double.POSITIVE_INFINITY, spent;
        /** Ticks its fed boost still lasts when it went away, and how many it has used since. */
        long boostLeft = Integer.MAX_VALUE, boostUsed;
        boolean boostable, fuse, tripped;
        private int sinceReserve;
        /** True while it stands in for a member that is not loaded. */
        boolean away;

        Remembered(PowerMemory owner, long connector, long key) {
            this.owner = owner;
            this.connector = connector;
            this.key = key;
        }

        /**
         * Takes what a real member did on a loaded tick: the capacity, demand and boost the grid was given by it. A
         * generator's capacity is only taken while its fuse holds (a blown one says 0), so a reset while it is away
         * brings it back; a New Age block's is never taken, as it only gives what it has stored.
         */
        void update(PowerMember real, double cap, double demand, double boost) {
            boolean tripped = real.tripped();
            if (!tripped) {
                capacity = real.holdsFuse() ? real.steady(cap) : 0;
                // what its fuel holds changes slowly: looked at once a second (a Fuel Generator's tank is a lookup)
                if (capacity <= 0) reserve = 0;
                else if (sinceReserve-- <= 0 || reserve <= 0) {
                    reserve = real.reserve();
                    if (boost > 0) boostLeft = real.boostTicks();
                    sinceReserve = 20;
                }
                this.boost = boost;
                chargeRate = real.chargeRate();
            }
            this.demand = demand;
            stored = real.stored();
            storeCapacity = real.storeCapacity();
            boostable = real.boostable();
            fuse = real.holdsFuse();
            this.tripped = tripped;
            owner.setDirty();
        }

        public boolean away() {
            return away;
        }

        /** Whether its grid gave it power on the last tick it was balanced. */
        private boolean on = true;

        @Override
        public void supplied(boolean on) {
            this.on = on;
        }

        public boolean lastOn() {
            return on;
        }

        /**
         * The factory simulation runs this member while its chunk does not tick (work package 17): what it can give, asks
         * for, holds and has in fuel is what the simulation says.
         */
        public void simulate(double capacity, double demand, double stored, double reserve) {
            this.capacity = capacity;
            this.demand = demand;
            this.stored = Math.min(stored, storeCapacity > 0 ? storeCapacity : stored);
            this.reserve = reserve;
            owner.setDirty();
        }

        /**
         * A simulated generator in a grid balanced here (part of it ticks): its fuel is the simulation's (belts keep
         * bringing more), so what it can give and how long that lasts are what the simulation says; what it gave was
         * taken just before ({@link #takeSpent}) and burnt there.
         */
        public void simFuel(double capacity, double reserve) {
            this.capacity = capacity;
            this.reserve = reserve;
            spent = 0;
            owner.setDirty();
        }

        /**
         * A member of a grid the simulation balances by itself (none of it ticks): it stands for what the simulation
         * has, so that when part of the grid ticks again it starts from that, not from what it was when it went away.
         */
        public void fromSim(double capacity, double reserve, double stored, boolean tripped) {
            if (capacity >= 0) this.capacity = capacity;
            if (reserve >= 0) this.reserve = reserve;
            spent = 0;
            if (storeCapacity > 0) this.stored = Math.clamp(stored, 0, storeCapacity);
            if (fuse) this.tripped = tripped;
            owner.setDirty();
        }

        /** A simulated consumer's demand: what it asks for now, as the simulation runs it. */
        public void simulateDemand(double demand) {
            if (this.demand == demand) return;
            this.demand = demand;
            owner.setDirty();
        }

        /** What it gave the grid since last asked (SU-ticks): the simulation burns that from its fuel. */
        public double takeSpent() {
            double s = spent;
            spent = 0;
            return s;
        }

        /** SU-ticks of fuel it has given while away, not yet burnt from the real generator. */
        public double spent() {
            return spent;
        }

        /** SU-ticks of fuel it held when last seen loaded (looked at once a second). */
        public double reserveWhenSeen() {
            return reserve;
        }

        /** What it last gave, while its fuel lasts. */
        @Override
        public double capacity() {
            return tripped ? 0 : Math.max(0, Math.min(capacity, reserve - spent));
        }

        @Override
        public void generated(double su) {
            if (su <= 0) return;
            spent += su;
            owner.setDirty();
        }

        @Override
        public boolean boostable() {
            return boostable;
        }

        @Override
        public double demand() {
            return demand;
        }

        @Override
        public double stored() {
            return stored;
        }

        @Override
        public double storeCapacity() {
            return storeCapacity;
        }

        @Override
        public double chargeRate() {
            return tripped ? 0 : chargeRate;
        }

        @Override
        public void store(double suTicks) {
            stored = Math.clamp(stored + suTicks, 0, storeCapacity);
            owner.setDirty();
        }

        /** Its fed boost (+30%) while its matrices last, then the unfed +10%; asked once a tick, as the grid does. */
        @Override
        public double boost() {
            if (tripped || boost <= 0) return 0;
            if (!away || boost <= 0.1) return boost;
            if (boostUsed >= boostLeft) return 0.1;
            boostUsed++;
            owner.setDirty();
            return boost;
        }

        @Override
        public boolean holdsFuse() {
            return fuse;
        }

        @Override
        public boolean tripped() {
            return tripped;
        }

        @Override
        public void trip(boolean tripped) {
            if (this.tripped == tripped) return;
            this.tripped = tripped;
            owner.setDirty();
        }
    }
}
