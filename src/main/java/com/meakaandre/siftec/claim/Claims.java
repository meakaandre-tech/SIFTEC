package com.meakaandre.siftec.claim;

import com.google.gson.Gson;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.Milestones;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Land claims. A HUB claims the 5 by 5 chunks around it and a Claim Marker the 3 by 3 around it.
 * Inside a claim only the company's members can place or break blocks or use anything.
 * Claimed chunks stay loaded while at least one member is online.
 */
public final class Claims {
    public static final int HUB_RADIUS = 2, MARKER_RADIUS = 1, HUB_SPACING = 8;

    /** One claimed chunk: who holds it and which block (HUB or marker) made the claim. */
    public static class Claim {
        public String company = "";
        public long source;
        public boolean marker;
    }

    public static class Data extends SavedData {
        private static final Gson GSON = new Gson();

        private static class Stored {
            /** "dimension|chunk" -> claim */
            Map<String, Claim> chunks = new HashMap<>();
        }

        static final Codec<Data> CODEC = Codec.STRING.xmap(json -> {
            Data data = new Data();
            Stored stored = GSON.fromJson(json, Stored.class);
            if (stored != null) data.stored = stored;
            return data;
        }, data -> GSON.toJson(data.stored));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Siftec.id("claims"), Data::new, CODEC, null);
        private Stored stored = new Stored();

        static Data get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    private Claims() {
    }

    private static String key(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return dimension.identifier() + "|" + ChunkPos.pack(chunkX, chunkZ);
    }

    /** Hands every claim of one company to another. */
    public static void reassign(MinecraftServer server, String from, String to) {
        Data data = Data.get(server);
        for (Claim claim : data.stored.chunks.values()) if (claim.company.equals(from)) claim.company = to;
        data.setDirty();
    }

    public static Claim at(Level level, BlockPos pos) {
        if (level.getServer() == null) return null;
        return Data.get(level.getServer()).stored.chunks.get(key(level.dimension(), pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** True if the player may build and use things here: unclaimed land, their own claim, or creative mode. */
    public static boolean allowed(Player player, Level level, BlockPos pos) {
        if (!(player instanceof ServerPlayer server) || server.hasInfiniteMaterials()) return true;
        Claim claim = at(level, pos);
        return claim == null || claim.company.equals(Companies.of(server).id);
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (allowed(player, level, pos)) return true;
            deny(player, level, pos);
            return false;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel server && (state.getBlock() instanceof HubBlock hub && !hub.gateway || state.getBlock() instanceof ClaimMarkerBlock)) {
                release(server, pos);
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (allowed(player, level, hit.getBlockPos())) return InteractionResult.PASS;
            deny(player, level, hit.getBlockPos());
            return InteractionResult.FAIL;
        });
        ServerPlayerEvents.JOIN.register(player -> refreshLoading(player.level().getServer()));
        ServerPlayerEvents.LEAVE.register(player -> {
            MinecraftServer server = player.level().getServer();
            // the player is still in the list while this runs, so look again on the next tick
            server.execute(() -> refreshLoading(server));
        });
    }

    private static void deny(Player player, Level level, BlockPos pos) {
        if (!(player instanceof ServerPlayer server)) return;
        Claim claim = at(level, pos);
        Company owner = claim == null ? null : CompanyData.get(server.level().getServer()).byId(claim.company);
        server.sendOverlayMessage(Component.translatable("siftec.claim.denied", owner == null ? "?" : owner.name));
    }

    /** How many Claim Markers a company may have: 4 at Tier 0 and 2 more for each tier opened. */
    public static int markerBudget(Company company) {
        int tiers = 0;
        for (int t = 1; t < Milestones.TIERS; t++) if (Milestones.tierOpen(company, t)) tiers = t;
        return 4 + 2 * tiers;
    }

    public static int markersUsed(MinecraftServer server, Company company) {
        java.util.Set<String> sources = new java.util.HashSet<>();
        for (Map.Entry<String, Claim> e : Data.get(server).stored.chunks.entrySet()) {
            if (e.getValue().marker && e.getValue().company.equals(company.id)) sources.add(e.getKey().split("\\|")[0] + e.getValue().source);
        }
        return sources.size();
    }

    /** True if another company's HUB claim is within the spacing distance of this chunk. */
    public static boolean nearForeignHub(ServerLevel level, Company company, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        Map<String, Claim> chunks = Data.get(level.getServer()).stored.chunks;
        for (int dx = -HUB_SPACING; dx <= HUB_SPACING; dx++) {
            for (int dz = -HUB_SPACING; dz <= HUB_SPACING; dz++) {
                Claim claim = chunks.get(key(level.dimension(), cx + dx, cz + dz));
                if (claim != null && !claim.marker && !claim.company.equals(company.id)) return true;
            }
        }
        return false;
    }

    /** Claims every unclaimed chunk within the radius. Returns how many chunks were claimed. */
    public static int claim(ServerLevel level, Company company, BlockPos source, int radius, boolean marker) {
        Map<String, Claim> chunks = Data.get(level.getServer()).stored.chunks;
        int cx = source.getX() >> 4, cz = source.getZ() >> 4, claimed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                String key = key(level.dimension(), cx + dx, cz + dz);
                if (chunks.containsKey(key)) continue;
                Claim claim = new Claim();
                claim.company = company.id;
                claim.source = source.asLong();
                claim.marker = marker;
                chunks.put(key, claim);
                claimed++;
            }
        }
        Data.get(level.getServer()).setDirty();
        refreshLoading(level.getServer());
        return claimed;
    }

    /** Gives up every chunk claimed by the HUB or marker at this position. */
    public static void release(ServerLevel level, BlockPos source) {
        Map<String, Claim> chunks = Data.get(level.getServer()).stored.chunks;
        String prefix = level.dimension().identifier() + "|";
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, Claim> e : chunks.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue().source == source.asLong()) gone.add(e.getKey());
        }
        for (String key : gone) {
            chunks.remove(key);
            long packed = Long.parseLong(key.substring(prefix.length()));
            level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
        }
        Data.get(level.getServer()).setDirty();
    }

    /** Keeps claimed chunks loaded while a member of the company is online, and lets them go when none is. */
    public static void refreshLoading(MinecraftServer server) {
        CompanyData companies = CompanyData.get(server);
        Map<String, Boolean> online = new HashMap<>();
        for (Map.Entry<String, Claim> e : Data.get(server).stored.chunks.entrySet()) {
            boolean on = online.computeIfAbsent(e.getValue().company, id -> {
                Company company = companies.byId(id);
                if (company == null) return false;
                for (String member : company.members) {
                    if (server.getPlayerList().getPlayer(UUID.fromString(member)) != null) return true;
                }
                return false;
            });
            String[] parts = e.getKey().split("\\|");
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.dimension().identifier().toString().equals(parts[0])) continue;
                long packed = Long.parseLong(parts[1]);
                level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), on);
            }
        }
    }
}
