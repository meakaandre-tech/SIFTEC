package com.meakaandre.siftec.claim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.config.SiftecConfig;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Land claims. A company's first HUB claims the 5 by 5 chunks around it and a Claim Marker the 3 by 3 around it.
 * Inside a claim only the company's members (and their machines) can place, break or use anything; explosions,
 * fire and fluids from outside, pistons, hoppers and funnels reaching across the border are stopped as well.
 * Claimed chunks stay loaded while at least one member is online, with a ticket of our own that is never saved.
 */
public final class Claims {
    public static final int HUB_RADIUS = 2, MARKER_RADIUS = 1, HUB_SPACING = 8;
    public static final int HUB_CHUNKS = 25, MARKER_CHUNKS = 9;
    /** Radius 2 is the ticket level of a /forceload: the chunk itself ticks entities and block entities. */
    private static final int TICKET_RADIUS = 2;
    /** Keeps claimed chunks loaded. Not saved, so after a restart nothing is loaded until a member joins. */
    public static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, Siftec.id("claim"),
        new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
    /** The chunks this mod holds a ticket on right now, per dimension. */
    private static final Map<ResourceKey<Level>, LongSet> LOADED = new HashMap<>();

    /** One claimed chunk: who holds it and which block (HUB or marker) made the claim. */
    public static class Claim {
        public String company = "";
        public long source;
        public boolean marker;
    }

    public static class Data extends JsonSavedData<Data.Stored> {
        public static class Stored {
            /** "dimension|chunk" -> claim */
            Map<String, Claim> chunks = new HashMap<>();
            /** Set once the vanilla force-loads older versions put on claimed chunks have been taken off. */
            boolean ticketsMigrated;
        }

        static final Codec<Data> CODEC = JsonSavedData.codec(Data::new);
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Siftec.id("claims"), Data::new, CODEC, null);
        /** The same claims by dimension and packed chunk position, for cheap lookups. Rebuilt on every change. */
        private Map<ResourceKey<Level>, Long2ObjectMap<Claim>> index = new HashMap<>();
        /** Sources (HUB or marker positions) by dimension and the chunk they stand in. */
        private Map<ResourceKey<Level>, Long2ObjectMap<Set<Long>>> sources = new HashMap<>();

        public Data() {
            super("claims", Stored.class, Stored::new);
        }

        static Data get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }

        @Override
        protected void loaded() {
            reindex();
        }

        void reindex() {
            Map<ResourceKey<Level>, Long2ObjectMap<Claim>> byChunk = new HashMap<>();
            Map<ResourceKey<Level>, Long2ObjectMap<Set<Long>>> bySource = new HashMap<>();
            for (Map.Entry<String, Claim> e : stored.chunks.entrySet()) {
                int bar = e.getKey().lastIndexOf('|');
                if (bar < 0) continue;
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(e.getKey().substring(0, bar)));
                long packed = Long.parseLong(e.getKey().substring(bar + 1));
                byChunk.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>()).put(packed, e.getValue());
                BlockPos source = BlockPos.of(e.getValue().source);
                bySource.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>())
                    .computeIfAbsent(ChunkPos.pack(source.getX() >> 4, source.getZ() >> 4), k -> new HashSet<>()).add(e.getValue().source);
            }
            index = byChunk;
            sources = bySource;
        }

        Map<String, Claim> chunks() {
            return stored.chunks;
        }
    }

    private Claims() {
    }

    static String key(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return dimension.identifier() + "|" + ChunkPos.pack(chunkX, chunkZ);
    }

    static void changed(MinecraftServer server) {
        Data data = Data.get(server);
        data.reindex();
        data.setDirty();
    }

    /** Hands every claim of one company to another. */
    public static void reassign(MinecraftServer server, String from, String to) {
        Data data = Data.get(server);
        for (Claim claim : data.chunks().values()) if (claim.company.equals(from)) claim.company = to;
        changed(server);
        refreshLoading(server);
    }

    public static @Nullable Claim at(Level level, int chunkX, int chunkZ) {
        if (!(level instanceof ServerLevel server)) return null;
        Long2ObjectMap<Claim> claims = Data.get(server.getServer()).index.get(level.dimension());
        return claims == null ? null : claims.get(ChunkPos.pack(chunkX, chunkZ));
    }

    public static @Nullable Claim at(Level level, BlockPos pos) {
        return at(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** True if something at {@code from} reaching to {@code to} would cross into someone else's claim. */
    public static boolean crosses(Level level, BlockPos from, BlockPos to) {
        Claim target = at(level, to);
        if (target == null) return false;
        Claim here = at(level, from);
        return here == null || !here.company.equals(target.company);
    }

    /** True if the player may build and use things here: unclaimed land, their own claim, or creative mode. */
    public static boolean allowed(Player player, Level level, BlockPos pos) {
        if (!(player instanceof ServerPlayer server)) return true;
        Claim claim = at(level, pos);
        if (claim == null) return true;
        Company company = Companies.find(server);
        if (company != null && claim.company.equals(company.id)) return true;
        if (Companies.isFake(server)) {
            // a Deployer without a known owner may still work inside the claim it stands in
            Claim here = at(level, server.blockPosition());
            return here != null && here.company.equals(claim.company);
        }
        return server.hasInfiniteMaterials();
    }

    /** True if a machine (a drill or saw) may break the block at the target. */
    public static boolean machineMayBreak(BlockEntity machine, BlockPos target) {
        Level level = machine.getLevel();
        if (level == null) return true;
        Claim claim = at(level, target);
        if (claim == null) return true;
        Company owner = Ownership.of(machine);
        if (owner != null && owner.id.equals(claim.company)) return true;
        return !crosses(level, machine.getBlockPos(), target);
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (allowed(player, level, pos)) return true;
            deny(player, level, pos);
            return false;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (allowed(player, level, hit.getBlockPos())) return InteractionResult.PASS;
            deny(player, level, hit.getBlockPos());
            resync(player, level, hit.getBlockPos(), hit.getDirection());
            return InteractionResult.FAIL;
        });
        // a bucket finds its own target, so a click the block check refused still reaches it
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer) || !(player.getItemInHand(hand).getItem() instanceof BucketItem)) return InteractionResult.PASS;
            Vec3 eye = player.getEyePosition();
            Vec3 end = eye.add(player.getViewVector(1f).scale(player.blockInteractionRange()));
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
            if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos(), next = pos.relative(hit.getDirection());
            boolean here = allowed(player, level, pos);
            if (here && allowed(player, level, next)) return InteractionResult.PASS;
            deny(player, level, here ? next : pos);
            resync(player, level, pos, hit.getDirection());
            return InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> entityCheck(player, level, entity));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
            entity instanceof Player ? InteractionResult.PASS : entityCheck(player, level, entity));
        ServerPlayerEvents.JOIN.register(player -> refreshLoading(player.level().getServer()));
        // the player is still in the player list while this runs, so they are left out by hand
        ServerPlayerEvents.LEAVE.register(player -> refreshLoading(player.level().getServer(), player.getUUID()));
        ServerLifecycleEvents.SERVER_STARTED.register(Claims::migrateTickets);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
        ServerChunkEvents.CHUNK_LOAD.register(Claims::heal);
        StateSelfTest.register();
    }

    private static InteractionResult entityCheck(Player player, Level level, Entity entity) {
        if (entity instanceof Enemy || allowed(player, level, entity.blockPosition())) return InteractionResult.PASS;
        deny(player, level, entity.blockPosition());
        return InteractionResult.FAIL;
    }

    public static void deny(Player player, Level level, BlockPos pos) {
        if (!(player instanceof ServerPlayer server) || Companies.isFake(server)) return;
        Claim claim = at(level, pos);
        Company owner = claim == null ? null : CompanyData.get(server.level().getServer()).byId(claim.company);
        server.sendOverlayMessage(Component.translatable("siftec.claim.denied", owner == null ? "?" : owner.name));
    }

    /** The client has already drawn the block it thought it placed and used up the item: put both right again. */
    public static void resync(Player player, Level level, BlockPos pos, Direction face) {
        if (!(player instanceof ServerPlayer server) || Companies.isFake(server) || server.connection == null) return;
        server.containerMenu.broadcastFullState();
        server.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        server.connection.send(new ClientboundBlockUpdatePacket(level, pos.relative(face)));
    }

    /** How many Claim Markers a company may have: 4 at Tier 0 and 2 more for each tier opened. */
    public static int markerBudget(Company company) {
        int tiers = 0;
        for (int t = 1; t < Milestones.TIERS; t++) if (Milestones.tierOpen(company, t)) tiers = t;
        return 4 + 2 * tiers;
    }

    /** How many chunks a company may claim in all: one HUB's worth plus a full marker's worth per marker allowed. */
    public static int chunkBudget(Company company) {
        return HUB_CHUNKS + MARKER_CHUNKS * markerBudget(company);
    }

    public static int chunksUsed(MinecraftServer server, Company company) {
        int n = 0;
        for (Claim claim : Data.get(server).chunks().values()) if (claim.company.equals(company.id)) n++;
        return n;
    }

    public static int markersUsed(MinecraftServer server, Company company) {
        Set<String> sources = new HashSet<>();
        for (Map.Entry<String, Claim> e : Data.get(server).chunks().entrySet()) {
            if (e.getValue().marker && e.getValue().company.equals(company.id)) sources.add(e.getKey().split("\\|")[0] + e.getValue().source);
        }
        return sources.size();
    }

    /** True if the company already holds land through a HUB. */
    public static boolean hasClaimingHub(MinecraftServer server, Company company) {
        for (Claim claim : Data.get(server).chunks().values()) if (!claim.marker && claim.company.equals(company.id)) return true;
        return false;
    }

    /** True if another company's HUB claim is within the spacing distance of this chunk. */
    public static boolean nearForeignHub(ServerLevel level, Company company, BlockPos pos) {
        return nearForeign(level, company, pos, HUB_SPACING, true);
    }

    /** True if another company holds any chunk within the given number of chunks (HUB claims only, if asked). */
    public static boolean nearForeign(ServerLevel level, Company company, BlockPos pos, int chunks, boolean hubsOnly) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int dx = -chunks; dx <= chunks; dx++) {
            for (int dz = -chunks; dz <= chunks; dz++) {
                Claim claim = at(level, cx + dx, cz + dz);
                if (claim != null && (!hubsOnly || !claim.marker) && !claim.company.equals(company.id)) return true;
            }
        }
        return false;
    }

    /** True if the chunk lies (even partly) within the spawn area where nothing can be claimed. */
    public static boolean inSpawnZone(ServerLevel level, int chunkX, int chunkZ) {
        int radius = SiftecConfig.spawnFreeRadius;
        if (radius <= 0) return false;
        LevelData.RespawnData spawn = level.getServer().overworld().getRespawnData();
        if (!spawn.dimension().equals(level.dimension())) return false;
        BlockPos at = spawn.pos();
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        return x0 + 15 >= at.getX() - radius && x0 <= at.getX() + radius && z0 + 15 >= at.getZ() - radius && z0 <= at.getZ() + radius;
    }

    public static boolean inSpawnZone(ServerLevel level, BlockPos pos) {
        return inSpawnZone(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Claims unclaimed chunks within the radius, up to {@code limit} of them, skipping the spawn area.
     * Returns how many chunks were claimed.
     */
    public static int claim(ServerLevel level, Company company, BlockPos source, int radius, boolean marker, int limit) {
        Map<String, Claim> chunks = Data.get(level.getServer()).chunks();
        int cx = source.getX() >> 4, cz = source.getZ() >> 4, claimed = 0;
        for (int dx = -radius; dx <= radius && claimed < limit; dx++) {
            for (int dz = -radius; dz <= radius && claimed < limit; dz++) {
                String key = key(level.dimension(), cx + dx, cz + dz);
                if (chunks.containsKey(key) || inSpawnZone(level, cx + dx, cz + dz)) continue;
                Claim claim = new Claim();
                claim.company = company.id;
                claim.source = source.asLong();
                claim.marker = marker;
                chunks.put(key, claim);
                claimed++;
            }
        }
        changed(level.getServer());
        refreshLoading(level.getServer());
        return claimed;
    }

    /** Claims for a HUB within the company's budget. Only the company's first HUB claims; later ones get -1. */
    public static int claimForHub(ServerLevel level, Company company, BlockPos source) {
        if (hasClaimingHub(level.getServer(), company)) return -1;
        int room = chunkBudget(company) - chunksUsed(level.getServer(), company);
        return room <= 0 ? 0 : claim(level, company, source, HUB_RADIUS, false, room);
    }

    /** Gives up every chunk claimed by the HUB or marker at this position. Returns how many. */
    public static int release(ServerLevel level, BlockPos source) {
        Map<String, Claim> chunks = Data.get(level.getServer()).chunks();
        String prefix = level.dimension().identifier() + "|";
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, Claim> e : chunks.entrySet()) {
            if (e.getValue().source == source.asLong() && e.getKey().startsWith(prefix)) gone.add(e.getKey());
        }
        if (gone.isEmpty()) return 0;
        for (String key : gone) chunks.remove(key);
        changed(level.getServer());
        refreshLoading(level.getServer());
        return gone.size();
    }

    /** Drops claims whose HUB or marker is no longer there, checked whenever the chunk it stood in loads. */
    private static void heal(ServerLevel level, LevelChunk chunk, boolean generated) {
        Data data = Data.get(level.getServer());
        Long2ObjectMap<Set<Long>> here = data.sources.get(level.dimension());
        if (here == null) return;
        Set<Long> sources = here.get(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
        if (sources == null) return;
        for (long source : new ArrayList<>(sources)) {
            BlockPos pos = BlockPos.of(source);
            if (holdsClaim(chunk.getBlockState(pos).getBlock())) continue;
            Siftec.LOGGER.info("SIFTEC: the claim block at {} in {} is gone; releasing its chunks", pos.toShortString(), level.dimension().identifier());
            level.getServer().execute(() -> release(level, pos));
        }
    }

    public static boolean holdsClaim(Block block) {
        return block instanceof HubBlock hub && !hub.gateway || block instanceof ClaimMarkerBlock;
    }

    /** Older versions force-loaded claims the vanilla way, which is saved with the world; take those off once. */
    private static void migrateTickets(MinecraftServer server) {
        Data data = Data.get(server);
        if (data.stored.ticketsMigrated || data.broken()) return;
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            Long2ObjectMap<Claim> claims = data.index.get(level.dimension());
            if (claims == null) continue;
            for (long packed : claims.keySet()) {
                if (level.getForceLoadedChunks().contains(packed)) {
                    level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
                    n++;
                }
            }
        }
        data.stored.ticketsMigrated = true;
        data.setDirty();
        Siftec.LOGGER.info("SIFTEC: took the old saved force-load off {} claimed chunks; claims now load only while a member is online", n);
    }

    public static void refreshLoading(MinecraftServer server) {
        refreshLoading(server, (UUID) null);
    }

    /** Keeps claimed chunks loaded while a member of the company is online, and lets them go when none is. */
    public static void refreshLoading(MinecraftServer server, @Nullable UUID leaving) {
        refreshLoading(server, uuid -> !uuid.equals(leaving) && server.getPlayerList().getPlayer(uuid) != null);
    }

    /** The same, with who counts as online given (the self-test has no players to join). */
    public static void refreshLoading(MinecraftServer server, java.util.function.Predicate<UUID> isOnline) {
        CompanyData companies = CompanyData.get(server);
        Map<String, Boolean> online = new HashMap<>();
        Data data = Data.get(server);
        for (ServerLevel level : server.getAllLevels()) {
            LongSet want = new LongOpenHashSet();
            Long2ObjectMap<Claim> claims = data.index.get(level.dimension());
            if (claims != null) {
                for (Long2ObjectMap.Entry<Claim> e : claims.long2ObjectEntrySet()) {
                    boolean on = online.computeIfAbsent(e.getValue().company, id -> {
                        Company company = companies.byId(id);
                        if (company == null) return false;
                        for (String member : company.members) {
                            UUID uuid;
                            try {
                                uuid = UUID.fromString(member);
                            } catch (IllegalArgumentException ex) {
                                continue;
                            }
                            if (isOnline.test(uuid)) return true;
                        }
                        return false;
                    });
                    if (on) want.add(e.getLongKey());
                }
            }
            LongSet have = LOADED.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
            for (long packed : new LongOpenHashSet(have)) {
                if (want.contains(packed)) continue;
                level.getChunkSource().removeTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)), TICKET_RADIUS);
                have.remove(packed);
            }
            for (long packed : want) {
                if (!have.add(packed)) continue;
                level.getChunkSource().addTicketWithRadius(TICKET, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)), TICKET_RADIUS);
            }
        }
    }

    /** How many chunks this mod keeps loaded right now. */
    public static int loadedCount() {
        int n = 0;
        for (LongSet set : LOADED.values()) n += set.size();
        return n;
    }

    /** For the self-test: removes every claim held by the company. */
    public static void releaseCompany(MinecraftServer server, String company) {
        Data data = Data.get(server);
        if (data.chunks().values().removeIf(claim -> claim.company.equals(company))) {
            changed(server);
            refreshLoading(server);
        }
    }

    public static int total(MinecraftServer server) {
        return Data.get(server).chunks().size();
    }
}
