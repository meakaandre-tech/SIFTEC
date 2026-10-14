package com.meakaandre.siftec.item;

import com.meakaandre.siftec.collect.AncientCities;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Object Scanner: finds the nearest thing of the kind it is set to. Crash sites (their Hard Drives) from the start;
 * Mercer Spheres and Somersloops once their Alien Technology analysis is done; hostile mobs after Hostile Organism
 * Detection; and the wild food the Nutrients tree starts with (sweet berry bushes, apple trees, glow berries) once its
 * first research for each is done. Sneak to switch what it looks for.
 */
public class ObjectScannerItem extends Item {
    /** What the scanner can be set to, in the order sneaking steps through them. */
    public enum Target {
        HARD_DRIVE(Collectible.CRASH_SITE, ""), MERCER_SPHERE(Collectible.MERCER_SPHERE, "object:mercer_sphere"),
        SOMERSLOOP(Collectible.SOMERSLOOP, "object:somersloop"), SWEET_BERRIES(null, "object:sweet_berries"),
        APPLES(null, "object:apples"), GLOW_BERRIES(null, "object:glow_berries"), MOBS(null, "object:mobs");

        public final @Nullable Collectible collectible;
        /** The research the scanner needs for it; empty for none. */
        public final String token;

        Target(@Nullable Collectible collectible, String token) {
            this.collectible = collectible;
            this.token = token;
        }

        public Component label() {
            return Component.translatable("siftec.scanner.target." + name().toLowerCase(java.util.Locale.ROOT));
        }

        public boolean open(Company company, boolean creative) {
            return creative || token.isEmpty() || company.hasToken(token);
        }

        /** The wild plant it looks for: loaded land only, as Satisfactory's scanner does for plants. */
        public @Nullable Predicate<BlockState> plant() {
            return switch (this) {
                case SWEET_BERRIES -> s -> s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(BlockStateProperties.AGE_3) >= 2;
                case APPLES -> s -> (s.is(Blocks.OAK_LEAVES) || s.is(Blocks.DARK_OAK_LEAVES)) && !s.getValue(BlockStateProperties.PERSISTENT);
                case GLOW_BERRIES -> s -> (s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)) && s.getValue(BlockStateProperties.BERRIES);
                default -> null;
            };
        }
    }

    private static final Map<UUID, Target> SELECTED = new ConcurrentHashMap<>();
    private static final int MOB_RANGE = 64, PLANT_CHUNKS = 5;

    public ObjectScannerItem(Properties properties) {
        super(properties);
    }

    /** The targets this company's scanner has, in order. */
    public static List<Target> open(Company company, boolean creative) {
        List<Target> out = new ArrayList<>();
        for (Target t : Target.values()) if (t.open(company, creative)) out.add(t);
        return out;
    }

    @Override
    public InteractionResult use(Level level, Player user, InteractionHand hand) {
        if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        Company company = Companies.of(player);
        List<Target> open = open(company, player.hasInfiniteMaterials());
        Target target = SELECTED.getOrDefault(player.getUUID(), Target.HARD_DRIVE);
        if (!open.contains(target)) target = open.getFirst();
        if (player.isShiftKeyDown()) {
            target = open.get((open.indexOf(target) + 1) % open.size());
            SELECTED.put(player.getUUID(), target);
            player.sendOverlayMessage(Component.translatable("siftec.scanner.selected", target.label()));
            return InteractionResult.SUCCESS;
        }
        player.getCooldowns().addCooldown(player.getItemInHand(hand), 20);
        Component name = target.label();
        if (target == Target.MOBS) {
            Monster nearest = null;
            for (Monster monster : server.getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(MOB_RANGE))) {
                if (nearest == null || monster.distanceToSqr(player) < nearest.distanceToSqr(player)) nearest = monster;
            }
            if (nearest == null) tellNone(player, name);
            else tellFound(player, nearest.getName(), nearest.getBlockX(), nearest.getBlockZ());
            return InteractionResult.SUCCESS;
        }
        Predicate<BlockState> plant = target.plant();
        if (plant != null) {
            BlockPos found = nearestBlock(server, player.blockPosition(), plant, PLANT_CHUNKS);
            if (found == null) tellNone(player, name);
            else tellFound(player, name, found.getX(), found.getZ());
            return InteractionResult.SUCCESS;
        }
        Collectible looking = target.collectible;
        if (looking == null || server.dimension() != Level.OVERWORLD) {
            tellNone(player, name);
            return InteractionResult.SUCCESS;
        }
        java.util.Set<String> collected = java.util.Set.copyOf(company.collected);
        // an Ancient City's Somersloop: found as /locate finds cities (server thread), then weighed against the rest
        BlockPos city = looking == Collectible.SOMERSLOOP ? AncientCities.nearest(server, player.blockPosition(), collected) : null;
        com.meakaandre.siftec.node.Terrain.Border border = com.meakaandre.siftec.node.Terrain.Border.of(server);
        double px = player.getX(), pz = player.getZ();
        // searched on the node map's worker thread (it samples the world generator), answered when done
        com.meakaandre.siftec.node.NodeMap.async(server.getServer(),
            () -> Collectibles.nearest(server, px, pz, looking, collected, 48, border, com.meakaandre.siftec.node.NodeMap.SEARCH_NANOS), found -> {
                if (player.isRemoved()) return;
                int[] best = null;
                if (found != null && found.isPresent()) best = new int[]{found.get().x(), found.get().z()};
                if (city != null && (best == null || dist(px, pz, city.getX(), city.getZ()) < dist(px, pz, best[0], best[1]))) {
                    best = new int[]{city.getX(), city.getZ()};
                }
                if (best == null) tellNone(player, name);
                else tellFound(player, name, best[0], best[1]);
            });
        return InteractionResult.SUCCESS;
    }

    private static double dist(double x, double z, int tx, int tz) {
        return Math.sqrt((tx - x) * (tx - x) + (tz - z) * (tz - z));
    }

    private static void tellNone(ServerPlayer player, Component name) {
        player.sendOverlayMessage(Component.translatable("siftec.scanner.none_object", name));
    }

    private static void tellFound(ServerPlayer player, Component name, int x, int z) {
        int distance = (int) Math.round(dist(player.getX(), player.getZ(), x, z));
        player.sendOverlayMessage(Component.translatable("siftec.scanner.found", name, distance,
            Component.translatable("siftec.direction." + NodeScannerItem.direction(x - player.getX(), z - player.getZ())), x, z));
    }

    /**
     * The nearest block in loaded land within this many chunks that the test passes: whole sections without one are
     * skipped by their palette, so only the few that hold one are looked through.
     */
    public static @Nullable BlockPos nearestBlock(ServerLevel level, BlockPos from, Predicate<BlockState> test, int chunks) {
        int fx = from.getX() >> 4, fz = from.getZ() >> 4;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int cx = fx - chunks; cx <= fx + chunks; cx++) {
            for (int cz = fz - chunks; cz <= fz + chunks; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(test)) continue;
                    int baseY = level.getSectionYFromSectionIndex(i) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!test.test(section.getBlockState(x, y, z))) continue;
                                int wx = (cx << 4) + x, wy = baseY + y, wz = (cz << 4) + z;
                                double d = from.distSqr(new BlockPos(wx, wy, wz));
                                if (d < bestD) {
                                    bestD = d;
                                    best = new BlockPos(wx, wy, wz);
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Forgets every player's choice; called when the server stops. */
    public static void forget() {
        SELECTED.clear();
    }
}
