package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Work package 14's vehicles: the Tractor, the Truck and the Explorer. Each is one entity ({@link VehicleEntity}) that
 * looks built from Create's blocks, put down by the Build Gun for Satisfactory's cost (the building list's "vehicle"
 * entries) and taken back by its dismantle mode with a full refund and its cargo.
 * <p>
 * The driver's keys come from their client ({@link VehicleInputPayload}): W and S throttle, A and D steer, Space brakes,
 * and V ({@link VehicleRecordPayload}) starts and finishes recording a route for it to drive by itself.
 */
public final class Vehicles {
    private static final Map<VehicleKind, EntityType<VehicleEntity>> TYPES = new EnumMap<>(VehicleKind.class);
    private static final Map<EntityType<?>, VehicleKind> KINDS = new HashMap<>();
    private static final Map<UUID, VehicleInputPayload> INPUTS = new HashMap<>();

    private Vehicles() {
    }

    public static void register() {
        for (VehicleKind kind : VehicleKind.values()) {
            ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Siftec.id(kind.id));
            EntityType<VehicleEntity> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.<VehicleEntity>of(VehicleEntity::new, MobCategory.MISC).noLootTable().fireImmune()
                    .sized(kind.boxWidth, kind.boxHeight).clientTrackingRange(10).updateInterval(1).build(key));
            TYPES.put(kind, type);
            KINDS.put(type, kind);
        }
        PayloadTypeRegistry.serverboundPlay().register(VehicleInputPayload.TYPE, VehicleInputPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(VehicleRecordPayload.TYPE, VehicleRecordPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(VehicleInputPayload.TYPE, (payload, context) ->
            context.server().execute(() -> INPUTS.put(context.player().getUUID(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(VehicleRecordPayload.TYPE, (payload, context) -> context.server().execute(() -> {
            ServerPlayer player = context.player();
            if (player.getVehicle() instanceof VehicleEntity v) v.toggleRecording(player);
        }));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> INPUTS.clear());
    }

    public static EntityType<VehicleEntity> type(VehicleKind kind) {
        return TYPES.get(kind);
    }

    public static Map<VehicleKind, EntityType<VehicleEntity>> types() {
        return TYPES;
    }

    static VehicleKind kindOf(EntityType<?> type) {
        VehicleKind k = KINDS.get(type);
        return k == null ? VehicleKind.TRACTOR : k;
    }

    /** What a driver's client last said its keys were (all up if it never said). */
    static VehicleInputPayload input(Player player) {
        return INPUTS.getOrDefault(player.getUUID(), VehicleInputPayload.NONE);
    }

    /** For the selftests, which drive with no client: a pretend driver's keys. */
    public static void press(UUID driver, VehicleInputPayload keys) {
        INPUTS.put(driver, keys);
    }

    // ------------------------------------------------------------------------------------------------ the Build Gun

    /** The vehicle of a Build Gun entry, or null if it is no vehicle. */
    public static @Nullable VehicleKind kind(BuildingDef def) {
        return def.vehicle() ? VehicleKind.of(def.id()) : null;
    }

    /**
     * Where a vehicle put down at a Build Gun placement stands: its footprint starts at the hologram's front edge and
     * stretches away from the player, so its middle is half its length in; it faces away from the player too.
     */
    public static Vec3 spot(Placement p, VehicleKind kind) {
        double in = kind.length / 2.0;
        return new Vec3(p.anchor().getX() + 0.5 + p.facing().getStepX() * (in - 0.5), p.anchor().getY(),
            p.anchor().getZ() + 0.5 + p.facing().getStepZ() * (in - 0.5));
    }

    /** The blocks its footprint takes up, for the hologram. */
    public static List<BlockPos> cells(Placement p, VehicleKind kind) {
        List<BlockPos> out = new ArrayList<>();
        int half = kind.width / 2;
        for (int x = -half; x <= half; x++)
            for (int z = 0; z < kind.length; z++)
                for (int y = 0; y < kind.height; y++) out.add(p.at(x, y, z));
        return out;
    }

    /** The space it takes up, as an entity, standing at its spot. */
    static AABB box(Vec3 at, VehicleKind kind) {
        double hw = kind.boxWidth / 2.0;
        return new AABB(at.x - hw, at.y, at.z - hw, at.x + hw, at.y + kind.boxHeight, at.z + hw);
    }

    /**
     * Checks putting a vehicle down: the ground is clear for it, and with a company the vehicle is unlocked and the land
     * is not another company's; with a payer, the cost is there. Creative players skip unlocks, land and cost.
     */
    public static Buildings.Check check(Level level, Placement p, @Nullable Company company, @Nullable Container payer, boolean creative) {
        Buildings.Check check = new Buildings.Check(p);
        VehicleKind kind = kind(p.def());
        if (kind == null) {
            check.fail("siftec.build_gun.no_template", p.def().id());
            return check;
        }
        AABB box = box(spot(p, kind), kind);
        if (!level.noCollision(box.deflate(0.05))) check.fail("siftec.vehicle.blocked");
        if (creative) return check;
        if (company != null && Buildings.locked(p.def(), company)) check.fail("siftec.build_gun.locked", Buildings.unlockName(p.def()));
        if (company != null && level instanceof ServerLevel server) {
            Claims.Claim claim = Claims.foreign(server, box, company.id);
            if (claim != null) check.fail("siftec.prefab.claimed", Claims.holder(server, claim));
        }
        if (payer != null) {
            for (Milestone.Cost cost : p.def().cost()) {
                if (!cost.present()) continue;
                int have = 0;
                for (int i = 0; i < payer.getContainerSize(); i++) {
                    ItemStack stack = payer.getItem(i);
                    if (cost.matches(stack)) have += stack.getCount();
                }
                if (have < cost.count()) check.missing.add(new Milestone.Cost(cost.key(), cost.count() - have));
            }
        }
        return check;
    }

    /** Checks and, if all is well, puts the vehicle down, paid from the payer (none in creative). */
    public static Buildings.Check place(ServerLevel level, Placement p, @Nullable Company company, @Nullable Container payer, boolean creative) {
        Buildings.Check check = check(level, p, company, payer, creative);
        if (!check.ok()) return check;
        spawn(level, p, company, creative || payer == null ? List.of() : Buildings.take(payer, p.def().cost()));
        return check;
    }

    /** Puts a vehicle down at a placement with no checks; the refund is what dismantling it gives back. */
    public static @Nullable VehicleEntity spawn(ServerLevel level, Placement p, @Nullable Company company, List<ItemStack> refund) {
        VehicleKind kind = kind(p.def());
        if (kind == null) return null;
        VehicleEntity v = new VehicleEntity(type(kind), level);
        Vec3 at = spot(p, kind);
        v.snapTo(at.x, at.y, at.z, p.facing().toYRot(), 0);
        v.companyId = company == null ? "" : company.id;
        v.refund.addAll(refund);
        level.addFreshEntity(v);
        Siftec.LOGGER.info("SIFTEC: vehicle {} put down at {} facing {}", kind.id, BlockPos.containing(at).toShortString(), p.facing());
        return v;
    }

    /** The vehicle a player's Build Gun aims at (within its reach), or null. */
    public static @Nullable VehicleEntity aimed(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1f).scale(Buildings.RANGE));
        VehicleEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (VehicleEntity v : player.level().getEntitiesOfClass(VehicleEntity.class, new AABB(eye, end).inflate(1))) {
            var hit = v.getBoundingBox().inflate(0.2).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }

    /** Takes a vehicle back: its refund and cargo, and anyone in it gets out. */
    public static List<ItemStack> dismantle(VehicleEntity v) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : v.refund) out.add(stack.copy());
        v.refund.clear();
        for (int i = 0; i < v.cargo.getContainerSize(); i++) {
            ItemStack stack = v.cargo.removeItemNoUpdate(i);
            if (!stack.isEmpty()) out.add(stack);
        }
        v.ejectPassengers();
        v.discard();
        Siftec.LOGGER.info("SIFTEC: vehicle {} taken back; {} stacks given back", v.kind.id, out.size());
        return out;
    }

    /** True if the vehicle is a company's other than this one (creative players count as anyone's). */
    static boolean foreign(Entity vehicle, String ownerId, ServerPlayer player) {
        if (player.hasInfiniteMaterials() || ownerId.isEmpty()) return false;
        Company company = com.meakaandre.siftec.company.Companies.of(player);
        return !com.meakaandre.siftec.company.Companies.owns(company, ownerId);
    }
}
