package com.meakaandre.siftec.prefab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Carried;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.compat.SiftGate;
import com.meakaandre.siftec.config.SiftecConfig;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
 * Placing, upgrading and picking up the HUB and the Wormhole Gateway as whole structures, the way Satisfactory's
 * Build Gun places the HUB and the Space Elevator: the item shows the structure where the player looks (green
 * where it fits, red where it does not), and one click puts all of it down. The terminal (the HUB or Wormhole
 * Gateway block) is the structure's controller: it remembers where the structure stands, which way it faces, how
 * far each column's legs reach down and which level of parts is built. Parts come and go as the company's HUB
 * upgrades (or wormhole phases) are done, and picking the structure up gives the item back with everything that
 * was stored in it; the company's progress is the company's, so nothing of it is lost.
 */
public final class Prefabs {
    /** How far the player can aim the structure, like Satisfactory's Build Gun reaching further than a hand. */
    public static final int RANGE = 32;
    /** How many blocks of legs a column may get down to the ground. */
    public static final int MAX_DROP = 6;
    /** Parts are set without telling the blocks around, so a bed's two halves never see each other half-built. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int REMOVE_FLAGS = FLAGS | Block.UPDATE_SUPPRESS_DROPS;

    public enum Kind {
        HUB, GATEWAY;

        public boolean gateway() {
            return this == GATEWAY;
        }

        public Template template() {
            return this == GATEWAY ? Templates.gateway() : Templates.hub();
        }

        public int[] terminal() {
            return this == GATEWAY ? Templates.GATEWAY_TERMINAL : Templates.HUB_TERMINAL;
        }

        public Block block() {
            return this == GATEWAY ? ModBlocks.WORMHOLE_GATEWAY.get() : ModBlocks.HUB.get();
        }

        public String key() {
            return this == GATEWAY ? "block.siftec.wormhole_gateway" : "block.siftec.hub";
        }

        public static Kind of(HubBlock block) {
            return block.gateway ? GATEWAY : HUB;
        }
    }

    /** Where a structure would go: the middle of its front edge, and the way it stretches from there. */
    public record Spot(Kind kind, BlockPos anchor, Direction facing) {
        public BlockPos at(int x, int y, int z) {
            return world(anchor, facing, x, y, z);
        }

        public BlockPos terminal() {
            int[] t = kind.terminal();
            return at(t[0], t[1], t[2]);
        }
    }

    /** What a placement check found. */
    public static final class Check {
        public final Spot spot;
        /** Spots in the way of the structure, or under it with no ground near enough. */
        public final List<BlockPos> blocked = new ArrayList<>();
        public int noGround;
        public int[] legs;
        public @Nullable String reason;
        public Object[] args = new Object[0];

        Check(Spot spot) {
            this.spot = spot;
        }

        public boolean ok() {
            return reason == null && blocked.isEmpty();
        }

        public Component message() {
            if (reason != null) return Component.translatable(reason, args);
            if (noGround > 0 && noGround == blocked.size()) return Component.translatable("siftec.prefab.no_ground", MAX_DROP);
            if (!blocked.isEmpty()) return Component.translatable("siftec.prefab.blocked", blocked.size());
            return Component.translatable("siftec.prefab.hint", Component.translatable(spot.kind.key()));
        }

        public void fail(String reason, Object... args) {
            if (this.reason != null) return;
            this.reason = reason;
            this.args = args;
        }
    }

    private Prefabs() {
    }

    // ------------------------------------------------------------------------------------------------ geometry

    public static BlockPos world(BlockPos anchor, Direction facing, int x, int y, int z) {
        Direction right = facing.getClockWise();
        return new BlockPos(anchor.getX() + right.getStepX() * x + facing.getStepX() * z, anchor.getY() + y,
            anchor.getZ() + right.getStepZ() * x + facing.getStepZ() * z);
    }

    public static Rotation rotation(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static BlockState orient(BlockState state, Direction facing) {
        return state.rotate(rotation(facing));
    }

    /** The structure's whole box in the world, legs left out. */
    public static AABB bounds(Spot spot) {
        Template t = spot.kind.template();
        BlockPos a = spot.at(t.minX, t.minY, t.minZ), b = spot.at(t.maxX, t.maxY, t.maxZ);
        return new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, b.getY() + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    // ------------------------------------------------------------------------------------------------ aiming

    /** Quarter turns the player has given the structure (sneak and use the item), per side, as Satisfactory's rotate key. */
    private static final Map<UUID, Integer> SERVER_TURNS = new HashMap<>(), CLIENT_TURNS = new HashMap<>();

    public static int turn(Player player) {
        Map<UUID, Integer> turns = player.level().isClientSide() ? CLIENT_TURNS : SERVER_TURNS;
        int now = (turns.getOrDefault(player.getUUID(), 0) + 1) & 3;
        turns.put(player.getUUID(), now);
        return now;
    }

    public static int turns(Player player) {
        return (player.level().isClientSide() ? CLIENT_TURNS : SERVER_TURNS).getOrDefault(player.getUUID(), 0);
    }

    public static void forget() {
        SERVER_TURNS.clear();
    }

    /**
     * Where the structure goes for what the player looks at: its front edge on the block aimed at (within
     * {@link #RANGE}), stretching away from the player, turned by the player's quarter turns.
     */
    public static @Nullable Spot aim(Player player, Kind kind) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1f).scale(RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos anchor = hit.getDirection() == Direction.UP ? hit.getBlockPos().above() : hit.getBlockPos().relative(hit.getDirection());
        Direction facing = player.getDirection();
        for (int i = turns(player); i > 0; i--) facing = facing.getClockWise();
        return new Spot(kind, anchor.immutable(), facing);
    }

    // ------------------------------------------------------------------------------------------------ checking

    /**
     * Checks a spot. Everything the structure will ever take up must be free (air, plants, snow, water); every
     * bottom-row column needs ground at most {@link #MAX_DROP} blocks down. With a company (on the server) the land
     * rules are checked as well: not in the spawn area, not on another company's land, a claiming HUB not next to
     * another company's land, and one Wormhole Gateway per company.
     */
    public static Check check(Level level, Spot spot, @Nullable Company company, boolean creative) {
        Check check = new Check(spot);
        Template t = spot.kind.template();
        if (level.isOutsideBuildHeight(spot.anchor.getY() - MAX_DROP) || level.isOutsideBuildHeight(spot.anchor.getY() + t.maxY)) {
            check.fail("siftec.prefab.height");
            return check;
        }
        // legs: each bottom-row column reaches down to the first block that is not free
        int[] legs = new int[t.width() * t.length()];
        for (long[] col : t.support()) {
            int x = (int) col[0], z = (int) col[1];
            int found = -1;
            for (int d = 1; d <= MAX_DROP + 1; d++) {
                BlockPos below = spot.at(x, t.minY - d, z);
                if (!level.isLoaded(below)) break;
                if (!level.getBlockState(below).canBeReplaced()) {
                    found = d - 1;
                    break;
                }
            }
            if (found < 0) {
                check.blocked.add(spot.at(x, t.minY - 1, z));
                check.noGround++;
            } else {
                legs[t.column(x, z)] = found;
            }
        }
        check.legs = legs;
        Set<Long> solid = new HashSet<>();
        for (long key : t.keys()) {
            BlockPos local = BlockPos.of(key);
            BlockPos pos = spot.at(local.getX(), local.getY(), local.getZ());
            if (!level.isLoaded(pos)) {
                check.fail("siftec.prefab.unloaded");
                return check;
            }
            if (!level.getBlockState(pos).canBeReplaced()) check.blocked.add(pos);
            if (t.solidAtAnyLevel(key)) solid.add(pos.asLong());
        }
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds(spot))) {
            AABB box = entity.getBoundingBox();
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1e-4, box.maxY - 1e-4, box.maxZ - 1e-4))) {
                if (solid.contains(pos.asLong())) {
                    check.fail("siftec.prefab.entity");
                    break;
                }
            }
        }
        if (company != null && level instanceof ServerLevel server) land(server, spot, check, company, creative);
        return check;
    }

    private static void land(ServerLevel level, Spot spot, Check check, Company company, boolean creative) {
        Set<Long> chunks = new HashSet<>();
        AABB box = bounds(spot);
        for (int cx = ((int) box.minX) >> 4; cx <= ((int) box.maxX - 1) >> 4; cx++)
            for (int cz = ((int) box.minZ) >> 4; cz <= ((int) box.maxZ - 1) >> 4; cz++) chunks.add(ChunkPos.pack(cx, cz));
        for (long packed : chunks) {
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            if (Claims.inSpawnZone(level, cx, cz)) {
                check.fail("siftec.prefab.spawn", SiftecConfig.spawnFreeRadius);
                return;
            }
            Claims.Claim claim = Claims.at(level, cx, cz);
            if (claim != null && !Companies.same(claim.company, company.id) && !creative) {
                Company owner = CompanyData.get(level.getServer()).byId(claim.company);
                check.fail("siftec.prefab.claimed", owner == null ? "?" : owner.name);
                return;
            }
        }
        BlockPos terminal = spot.terminal();
        if (spot.kind == Kind.HUB) {
            boolean claims = !Claims.hasClaimingHub(level.getServer(), company);
            if (claims && (Claims.nearForeignHub(level, company, terminal) || Claims.nearForeign(level, company, terminal, Claims.HUB_RADIUS + 1, false))) {
                check.fail("siftec.claim.hub_too_close", Claims.HUB_SPACING);
            }
        } else {
            BlockPos existing = gatewayOf(level.getServer(), company);
            if (existing != null) check.fail("siftec.prefab.one_gateway", existing.toShortString());
        }
    }

    /** The company's Wormhole Gateway structure, if it still stands (a stale record is dropped). */
    public static @Nullable BlockPos gatewayOf(MinecraftServer server, Company company) {
        if (company.gateway == null || company.gateway.isEmpty()) return null;
        String[] parts = company.gateway.split("\\|");
        if (parts.length != 2) return null;
        BlockPos pos;
        try {
            pos = BlockPos.of(Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().identifier().toString().equals(parts[0])) continue;
            // a gateway in an unloaded chunk is taken to be there still
            if (!level.isLoaded(pos)) return pos;
            if (level.getBlockEntity(pos) instanceof HubBlockEntity hub && hub.prefab && level.getBlockState(pos).is(ModBlocks.WORMHOLE_GATEWAY.get())) return pos;
        }
        company.gateway = "";
        Companies.save(server);
        return null;
    }

    // ------------------------------------------------------------------------------------------------ levels

    /** HUB: the HUB upgrades done (0 to 6), and 7 while the freighter is docked after upgrade 6. Gateway: the phases done. */
    public static int levelOf(Kind kind, @Nullable Company company, MinecraftServer server) {
        if (company == null) return 0;
        int level = 0;
        if (kind == Kind.GATEWAY) {
            for (int i = 1; i <= Templates.GATEWAY_MAX; i++) if (company.has("phase_" + i)) level = i;
            return level;
        }
        for (int i = 1; i <= 6; i++) if (company.has("hub_upgrade_" + i)) level = i;
        if (level == 6 && company.lockUntil <= server.overworld().getGameTime()) level = 7;
        return level;
    }

    // ------------------------------------------------------------------------------------------------ placing

    /** Checks the spot and, if it is free, puts the whole structure down for the company. The check is returned either way. */
    public static Check place(ServerLevel level, Spot spot, Company company, @Nullable ServerPlayer player) {
        boolean creative = player != null && player.hasInfiniteMaterials();
        Check check = check(level, spot, company, creative);
        if (!check.ok()) return check;
        // the HUB costs Satisfactory's 20 Iron Ore (Raw Iron) and the Gateway the Space Elevator's parts, from the player's
        // inventory; they come back when it is dismantled. Placed by the old Gateway item (creative, tests), it is free.
        boolean byItem = player != null && player.getMainHandItem().getItem() instanceof PrefabItem;
        List<Milestone.Cost> cost = player != null && !creative && !byItem ? cost(spot.kind) : List.of();
        Milestone.Cost missing = player == null ? null : missing(player, cost);
        if (missing != null) {
            check.fail("siftec.prefab.cost", missing.count(), missing.icon().getHoverName());
            return check;
        }
        // paid from the inventory and the backpack alike
        List<ItemStack> paid = player == null ? new ArrayList<>()
            : new ArrayList<>(Buildings.take(Carried.of(player), cost));
        Template t = spot.kind.template();
        int target = levelOf(spot.kind, company, level.getServer());
        // inside space is cleared once, at the start; legs go down to the ground
        for (long key : t.keys()) {
            BlockState want = t.at(key, target);
            if (want == null || !want.isAir()) continue;
            BlockPos local = BlockPos.of(key);
            BlockPos pos = spot.at(local.getX(), local.getY(), local.getZ());
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        BlockState foundation = ModBlocks.PREFAB.get("foundation").get().defaultBlockState();
        for (long[] col : t.support()) {
            int x = (int) col[0], z = (int) col[1];
            for (int d = 1; d <= check.legs[t.column(x, z)]; d++) level.setBlock(spot.at(x, t.minY - d, z), foundation, FLAGS);
        }
        apply(level, spot, -1, target, company.id);
        BlockPos terminal = spot.terminal();
        if (level.getBlockEntity(terminal) instanceof HubBlockEntity hub) {
            hub.prefab = true;
            hub.anchor = spot.anchor;
            hub.facing = spot.facing;
            hub.legs = check.legs;
            hub.builtLevel = target;
            hub.companyId = company.id;
            hub.refund.clear();
            hub.refund.addAll(paid);
            hub.setChanged();
        }
        indexMachines(level, spot, target, terminal, company.id);
        if (spot.kind == Kind.HUB) {
            int chunks = Claims.claimForHub(level, company, terminal);
            if (player != null) {
                if (chunks < 0) {
                    player.sendOverlayMessage(Component.translatableWithFallback("siftec.claim.hub_extra",
                        "HUB placed. Your company's first HUB already holds its land, so this one claims nothing"));
                } else {
                    player.sendOverlayMessage(Component.translatable("siftec.claim.hub", chunks));
                }
            }
        } else {
            company.gateway = level.dimension().identifier() + "|" + terminal.asLong();
            Companies.save(level.getServer());
            if (player != null) player.sendOverlayMessage(Component.translatable("siftec.prefab.placed", Component.translatable(spot.kind.key())));
        }
        Siftec.LOGGER.info("SIFTEC: {} built at {} facing {} (level {}) for {}", spot.kind, terminal.toShortString(), spot.facing, target, company.name);
        return check;
    }

    /**
     * Changes the built parts from one level to another (from -1 when the structure first goes down). Only spots that
     * hold one of the structure's own blocks are taken away; anything a player put where a new part goes is broken
     * and dropped first, as if mined, so nothing is lost.
     */
    static void apply(ServerLevel level, Spot spot, int from, int to, String companyId) {
        Template t = spot.kind.template();
        for (long key : t.keys()) {
            BlockState had = from < 0 ? null : t.at(key, from), want = t.at(key, to);
            if (had == want) continue;
            BlockPos local = BlockPos.of(key);
            BlockPos pos = spot.at(local.getX(), local.getY(), local.getZ());
            BlockState now = level.getBlockState(pos);
            if (want == null || want.isAir()) {
                if (had != null && !had.isAir() && isPart(now)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
                continue;
            }
            BlockState placed = orient(want, spot.facing);
            if (now == placed) continue;
            if (!now.canBeReplaced() && !isPart(now)) level.destroyBlock(pos, true);
            level.setBlock(pos, placed, FLAGS);
            if (level.getBlockEntity(pos) instanceof StorageBoxBlockEntity box) {
                box.companyId = companyId;
                box.setChanged();
            }
        }
    }

    public static boolean isPart(BlockState state) {
        return state.getBlock() instanceof PrefabPart || state.getBlock() instanceof HubBlock;
    }

    /** Builds or takes away parts to match the company's progress. Run by the terminal once a second. */
    public static void refresh(HubBlockEntity hub) {
        if (!hub.prefab || hub.anchor == null || hub.facing == null || !(hub.getLevel() instanceof ServerLevel level)) return;
        if (!(hub.getBlockState().getBlock() instanceof HubBlock block)) return;
        Kind kind = Kind.of(block);
        Company company = CompanyData.get(level.getServer()).byId(hub.companyId);
        int target = levelOf(kind, company, level.getServer());
        if (target == hub.builtLevel) return;
        Spot spot = new Spot(kind, hub.anchor, hub.facing);
        apply(level, spot, hub.builtLevel, target, hub.companyId);
        hub.builtLevel = target;
        hub.setChanged();
        indexMachines(level, spot, target, hub.getBlockPos(), hub.companyId);
    }

    /**
     * The finished Wormhole Gateway's portal: a player of a company that has delivered Phase 5 who walks into it goes to
     * The Sift; anyone else is told it is closed to them. The game goes on as free play after that.
     */
    public static void portal(HubBlockEntity hub) {
        if (!hub.gateway() || !hub.prefab || hub.anchor == null || hub.facing == null || hub.builtLevel < Templates.GATEWAY_MAX) return;
        if (!(hub.getLevel() instanceof ServerLevel level)) return;
        AABB box = bounds(new Spot(Kind.GATEWAY, hub.anchor, hub.facing));
        Direction right = hub.facing.getClockWise();
        for (ServerPlayer player : level.getPlayers(p -> box.contains(p.position()))) {
            // the player's place in the gateway's own frame (x right, y up, z away from the front edge)
            double ox = player.getX() - (hub.anchor.getX() + 0.5), oz = player.getZ() - (hub.anchor.getZ() + 0.5);
            double x = ox * right.getStepX() + oz * right.getStepZ() + 0.5, z = ox * hub.facing.getStepX() + oz * hub.facing.getStepZ() + 0.5;
            double y = player.getY() + 0.9 - hub.anchor.getY();
            if (!Templates.inPortal(x, y, z) || player.isSpectator()) continue;
            if (!player.hasInfiniteMaterials() && !Companies.of(player).has("phase_5")) {
                player.sendOverlayMessage(Component.translatable("siftec.sift.closed"));
                continue;
            }
            SiftGate.enter(player);
        }
    }

    /** What the HUB costs to put down: Satisfactory's, from siftec_content.json ("build_costs", The HUB). */
    public static List<Milestone.Cost> hubCost() {
        return cost(Kind.HUB);
    }

    /**
     * What a structure costs to put down with the Build Gun: Satisfactory's, from siftec_content.json ("build_costs"):
     * The HUB, or the Space Elevator for the Wormhole Gateway. It comes back when the structure is dismantled.
     */
    public static List<Milestone.Cost> cost(Kind kind) {
        String name = kind == Kind.GATEWAY ? "Space Elevator" : "The HUB";
        List<Milestone.Cost> out = new ArrayList<>();
        JsonObject raw = Milestones.raw();
        if (raw == null || !raw.has("build_costs") || !raw.getAsJsonObject("build_costs").has(name)) return out;
        for (JsonElement e : raw.getAsJsonObject("build_costs").getAsJsonArray(name)) {
            out.add(new Milestone.Cost(e.getAsJsonObject().get("item").getAsString(), e.getAsJsonObject().get("count").getAsInt()));
        }
        return out;
    }

    /**
     * A cost the player does not carry enough of (null if they carry it all), as the HUB's 20 Raw Iron is checked. The
     * backpack counts as carried.
     */
    public static Milestone.@Nullable Cost missing(ServerPlayer player, List<Milestone.Cost> cost) {
        for (Milestone.Cost c : cost) {
            if (!c.present()) continue;
            if (c.carried(player.getInventory()) < c.count()) return c;
        }
        return null;
    }

    /**
     * Every player spawns with the Build Gun (it is never crafted): one is given to a player who has none when they
     * join and when they respawn. The first thing they build with it is the HUB.
     */
    public static void giveBuildGun(ServerPlayer player) {
        if (Companies.isFake(player)) return;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() instanceof BuildGunItem) return;
        }
        player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.BUILD_GUN.get()), Prediction.SERVER_ONLY);
        player.sendSystemMessage(Component.translatable("siftec.prefab.build_gun"));
    }

    /**
     * The HUB structure a block belongs to (its terminal, a part of its shell or a machine of its Craft Station), or
     * null. Looks through the HUB terminals in the loaded chunks near the block.
     */
    public static @Nullable HubBlockEntity hubAt(Level level, BlockPos pos) {
        return structureAt(level, pos, Kind.HUB);
    }

    /** The HUB or Wormhole Gateway structure a block belongs to, whichever it is, or null. */
    public static @Nullable HubBlockEntity structureAt(Level level, BlockPos pos) {
        HubBlockEntity hub = structureAt(level, pos, Kind.HUB);
        return hub != null ? hub : structureAt(level, pos, Kind.GATEWAY);
    }

    /**
     * The structure of a kind a block belongs to (its terminal, a part of its shell or a machine of the HUB's Craft
     * Station), or null. Looks through the terminals in the loaded chunks near the block.
     */
    public static @Nullable HubBlockEntity structureAt(Level level, BlockPos pos, Kind kind) {
        boolean gateway = kind == Kind.GATEWAY;
        if (level.getBlockEntity(pos) instanceof HubBlockEntity own) return own.prefab && own.gateway() == gateway ? own : null;
        BlockPos indexed = BuildingIndex.controllerOf(level, pos);
        if (indexed != null && level.getBlockEntity(indexed) instanceof HubBlockEntity hub) return hub.prefab && hub.gateway() == gateway ? hub : null;
        if (!isPart(level.getBlockState(pos))) return null;
        Template t = kind.template();
        int reach = t.width() + t.length();
        for (int cx = (pos.getX() - reach) >> 4; cx <= (pos.getX() + reach) >> 4; cx++) {
            for (int cz = (pos.getZ() - reach) >> 4; cz <= (pos.getZ() + reach) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(be instanceof HubBlockEntity hub) || !hub.prefab || hub.gateway() != gateway || hub.anchor == null || hub.facing == null) continue;
                    if (bounds(new Spot(kind, hub.anchor, hub.facing)).contains(Vec3.atCenterOf(pos))) return hub;
                }
            }
        }
        return null;
    }

    /**
     * The real Create machines in a structure (the HUB's Craft Station) are listed in the building index under its
     * terminal, so they are as safe as its shell, and belong to its company, so they run what it has unlocked.
     */
    static void indexMachines(ServerLevel level, Spot spot, int at, BlockPos terminal, String companyId) {
        BuildingIndex index = BuildingIndex.get(level);
        if (index == null) return;
        Template t = spot.kind.template();
        for (long key : t.keys()) {
            BlockState state = t.at(key, at);
            if (state == null || state.isAir() || isPart(state)) continue;
            BlockPos local = BlockPos.of(key);
            BlockPos pos = spot.at(local.getX(), local.getY(), local.getZ());
            index.put(level, pos, terminal);
            var be = level.getBlockEntity(pos);
            if (be != null && !companyId.isEmpty()) be.setAttached(Ownership.OWNER, companyId);
        }
    }

    // ------------------------------------------------------------------------------------------------ picking up

    /**
     * Takes the structure (or an old single-block terminal) down and returns what it gives back: the HUB or Wormhole
     * Gateway item, everything in the Personal Storage Box and the fuel left in the burners. Containers are emptied
     * before their block goes, so nothing can drop as well. HUB claims are given up at once.
     */
    public static List<ItemStack> dismantle(ServerLevel level, HubBlockEntity hub) {
        List<ItemStack> out = new ArrayList<>();
        BlockPos terminal = hub.getBlockPos().immutable();
        if (!(hub.getBlockState().getBlock() instanceof HubBlock block)) return out;
        Kind kind = Kind.of(block);
        // both are built with the Build Gun: what was paid for the structure comes back
        for (ItemStack paid : hub.refund) out.add(paid.copy());
        hub.refund.clear();
        Company company = CompanyData.get(level.getServer()).byId(hub.companyId);
        if (hub.prefab && hub.anchor != null && hub.facing != null) {
            Spot spot = new Spot(kind, hub.anchor, hub.facing);
            Template t = kind.template();
            List<BlockPos> remove = new ArrayList<>();
            for (long key : t.keys()) {
                BlockState built = t.at(key, hub.builtLevel);
                if (built == null || built.isAir()) continue;
                BlockPos local = BlockPos.of(key);
                BlockPos pos = spot.at(local.getX(), local.getY(), local.getZ());
                if (pos.equals(terminal)) continue;
                if (level.getBlockEntity(pos) instanceof StorageBoxBlockEntity box) out.addAll(box.takeAll());
                if (level.getBlockEntity(pos) instanceof HubBurnerBlockEntity engine && !engine.fuel.getItem(0).isEmpty()) {
                    out.add(engine.fuel.removeItemNoUpdate(0));
                    engine.setChanged();
                }
                BlockState now = level.getBlockState(pos);
                if (isPart(now)) {
                    remove.add(pos);
                } else if (now.getBlock() == built.getBlock()) {
                    // the Craft Station's machines: unlisted, and what their Basins hold comes back
                    BuildingIndex index = BuildingIndex.get(level);
                    if (index != null) index.remove(level, pos, terminal);
                    out.addAll(Buildings.contents(level, pos));
                    remove.add(pos);
                }
            }
            if (hub.legs != null) {
                BlockState foundation = ModBlocks.PREFAB.get("foundation").get().defaultBlockState();
                for (long[] col : t.support()) {
                    int x = (int) col[0], z = (int) col[1], idx = t.column(x, z);
                    if (idx >= hub.legs.length) continue;
                    for (int d = 1; d <= hub.legs[idx]; d++) {
                        BlockPos pos = spot.at(x, t.minY - d, z);
                        if (level.getBlockState(pos) == foundation) remove.add(pos);
                    }
                }
            }
            // whatever a machine still drops as it goes (a Basin's input) is caught and given back too
            AABB box = bounds(spot).inflate(1);
            Set<UUID> before = new HashSet<>();
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) before.add(e.getUUID());
            for (BlockPos pos : remove) level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
                if (before.contains(e.getUUID()) || e.getItem().isEmpty()) continue;
                out.add(e.getItem().copy());
                e.discard();
            }
        }
        hub.prefab = false;
        level.setBlock(terminal, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
        if (kind == Kind.HUB) {
            Claims.release(level, terminal);
        } else if (company != null && company.gateway != null && company.gateway.equals(level.dimension().identifier() + "|" + terminal.asLong())) {
            company.gateway = "";
            Companies.save(level.getServer());
        }
        Siftec.LOGGER.info("SIFTEC: {} at {} picked up; {} stacks given back", kind, terminal.toShortString(), out.size());
        return out;
    }

    /** Hands items to the player (what does not fit drops at their feet), or drops them at a spot. */
    public static void give(ServerLevel level, @Nullable ServerPlayer player, BlockPos at, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (player != null) {
                player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
            } else {
                Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, stack);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ events

    private static final Map<UUID, PreviewPayload> SENT = new HashMap<>();

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(PreviewPayload.TYPE, PreviewPayload.STREAM_CODEC);
        // every player spawns with the Build Gun, and gets another if they have lost theirs when they join or respawn
        ServerPlayerEvents.JOIN.register(Prefabs::giveBuildGun);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> giveBuildGun(newPlayer));
        // a part cannot be mined, not even in creative: the structure is picked up from its terminal
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!isPart(state)) return true;
            if (player instanceof ServerPlayer server) {
                boolean gateway = state.getBlock() instanceof HubBlock hub ? hub.gateway : isGatewayPart(level, pos);
                server.sendOverlayMessage(Component.translatable("siftec.prefab.part", Component.translatable(gateway ? "block.siftec.wormhole_gateway" : "block.siftec.hub")));
            }
            return false;
        });
        // the server checks the land rules for whoever holds a HUB or Gateway, and tells their hologram
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 4 != 0) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ItemStack held = player.getMainHandItem();
                if (!(held.getItem() instanceof PrefabItem item)) {
                    SENT.remove(player.getUUID());
                    continue;
                }
                Spot spot = aim(player, item.kind);
                if (spot == null) continue;
                Check check = check(player.level(), spot, Companies.of(player), player.hasInfiniteMaterials());
                PreviewPayload payload = PreviewPayload.of(check);
                if (payload.equals(SENT.get(player.getUUID()))) continue;
                SENT.put(player.getUUID(), payload);
                ServerPlayNetworking.send(player, payload);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SENT.clear();
            forget();
        });
    }

    private static boolean isGatewayPart(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        for (String style : new String[]{"railway", "coil", "coil_lit", "rift", "portal", "conduit"}) {
            if (state.is(ModBlocks.PREFAB.get(style).get())) return true;
        }
        return false;
    }
}
