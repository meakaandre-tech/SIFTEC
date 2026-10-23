package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Carried;
import com.meakaandre.siftec.blueprint.Blueprints;
import com.meakaandre.siftec.blueprint.DesignerBlockEntity;
import com.meakaandre.siftec.compat.SiftGate;
import com.meakaandre.siftec.mixin.ConnectionAccessor;
import com.meakaandre.siftec.power.Poles;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Worn equipment. Blade Runners, Parachute, Gas Mask and Hazmat Suit work while worn; the Hover Pack lets
 * you fly near a turning Power Pole; the Jetpack burns fuel while the jump key is held in mid-air.
 */
public final class Equipment {
    /** What a charged Xeno-Zapper jolt does (Satisfactory's zapper does little but stun). */
    private static final float ZAPPER_DAMAGE = 4f;
    /** Swing charge the Xeno-Zapper needs to jolt. */
    private static final float ZAPPER_CHARGE = 0.9f;
    /** How long a jolt stuns (ticks), and the slowness level it stuns with. */
    private static final int ZAPPER_STUN_TICKS = 40, ZAPPER_SLOWNESS = 3;
    /** Length of the effects worn equipment keeps topping up (ticks); outlasts the time between top-ups. */
    private static final int WORN_EFFECT_TICKS = 40;
    /** A safe fall's slow falling is renewed once less than this is left (ticks). */
    private static final int SAFE_FALL_RENEW_TICKS = 20;
    /** Worn equipment is checked this often per player (ticks). */
    private static final int WORN_CHECK_TICKS = 10;
    /** Fall distance (blocks) past which a worn Parachute opens. */
    private static final int PARACHUTE_FALL = 3;
    /** Thrust a creative player's jetpack is filled with (ticks). */
    private static final int CREATIVE_JET_TICKS = 1200;
    /** Thrust stops when the client has not repeated itself for this long (ticks); it repeats twice a second. */
    private static final int THRUST_TIMEOUT_TICKS = 15;
    /** How close a turning pole has to be for the Hover Pack. */
    public static final double HOVER_RANGE = 32;
    /** One filter lasts this long while the mask or suit is actually stopping something. */
    private static final int FILTER_TICKS = 20 * 60;
    /** Jetpack fuels: item id -> ticks of thrust. Buckets come back empty. */
    private static final String[][] JET_FUEL = {{"siftec:turbofuel_bucket", "3000"}, {"createdieselgenerators:diesel_bucket", "1200"},
        {"siftec:compacted_coal", "600"}, {"siftec:solid_biofuel", "300"}};

    private static final Map<UUID, Integer> THRUSTING = new HashMap<>();
    /**
     * Players coming down after their flight ended in mid-air (the Hover Pack out of range, a zipline let go): they
     * keep slow falling until they land, are in water, or are flying again. Kept by id, so it also holds for
     * someone who drops off by leaving the game and comes back in the air.
     */
    private static final Set<UUID> SAFE_FALL = new HashSet<>();

    /** Slow falling from now until the player is on the ground (or in water, or flying). */
    public static void safeFall(ServerPlayer player) {
        SAFE_FALL.add(player.getUUID());
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, WORN_EFFECT_TICKS, 0, true, false, false));
    }

    private static void tickSafeFall(MinecraftServer server) {
        for (Iterator<UUID> it = SAFE_FALL.iterator(); it.hasNext(); ) {
            ServerPlayer player = server.getPlayerList().getPlayer(it.next());
            if (player == null) continue;   // offline: still owed a soft landing when they come back
            if (player.onGround() || player.isInWater() || player.getAbilities().flying || player.isPassenger() || player.isSpectator() || !player.isAlive()) {
                it.remove();
                continue;
            }
            player.resetFallDistance();
            if (!player.hasEffect(MobEffects.SLOW_FALLING) || player.getEffect(MobEffects.SLOW_FALLING).getDuration() < SAFE_FALL_RENEW_TICKS) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, WORN_EFFECT_TICKS, 0, true, false, false));
            }
        }
    }
    /** Thrust left in the tank, and when the running filters give out. Kept with the player. */
    private static final AttachmentType<Integer> JET_TICKS = AttachmentRegistry.create(
        Siftec.id("jet_fuel"), builder -> builder.persistent(Codec.INT).copyOnDeath());
    private static final AttachmentType<Long> MASK_UNTIL = AttachmentRegistry.create(
        Siftec.id("mask_filter"), builder -> builder.persistent(Codec.LONG).copyOnDeath());
    private static final AttachmentType<Long> SUIT_UNTIL = AttachmentRegistry.create(
        Siftec.id("suit_filter"), builder -> builder.persistent(Codec.LONG).copyOnDeath());

    private static int jet(ServerPlayer player) {
        Integer left = player.getAttached(JET_TICKS);
        return left == null ? 0 : left;
    }
    /** Other things that let a player fly, such as standing inside a Blueprint Designer. */
    public static final List<Predicate<ServerPlayer>> FLIGHT_ZONES = new ArrayList<>();

    private Equipment() {
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(JetThrustPayload.TYPE, JetThrustPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JetFuelPayload.TYPE, JetFuelPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(JetThrustPayload.TYPE, (payload, context) ->
            context.server().execute(() -> thrust(context.server(), context.player(), payload.on())));
        ServerTickEvents.END_SERVER_TICK.register(Equipment::tick);
        // the Xeno-Zapper: a jolt that hurts more than a fist and stuns (slowness and weakness for a moment); it needs the
        // swing charged, as any weapon does
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(level instanceof ServerLevel server) || player.isSpectator()) return InteractionResult.PASS;
            if (!player.getItemInHand(hand).is(ModItems.XENO_ZAPPER.get()) || !(entity instanceof LivingEntity living)) {
                return InteractionResult.PASS;
            }
            if (player.getAttackStrengthScale(0.5f) < ZAPPER_CHARGE) return InteractionResult.PASS;
            if (living.hurtServer(server, server.damageSources().playerAttack(player), ZAPPER_DAMAGE)) {
                living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ZAPPER_STUN_TICKS, ZAPPER_SLOWNESS));
                living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ZAPPER_STUN_TICKS, 1));
                server.playSound(null, living.blockPosition(), SoundEvents.BEE_STING, SoundSource.PLAYERS, 0.8f, 1.6f);
            }
            return InteractionResult.PASS;
        });
        // carriers and drones are only ever drawn for something in flight; one read back from disk is left over from a restart
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.entityTags().contains(Ziplines.TAG)) level.getServer().execute(entity::discard);
            // The Sift's own rifts are switched off outside The Sift: the wormhole is the only way in
            if (level.dimension() != SiftGate.SIFT && SiftGate.isRift(entity)) level.getServer().execute(entity::discard);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            THRUSTING.clear();
            SAFE_FALL.clear();
            Poles.clear();
            Ziplines.clear();
            Blueprints.clear();
            DesignerBlockEntity.clear();
        });
        FLIGHT_ZONES.add(DesignerBlockEntity::inside);
        FLIGHT_ZONES.add(player -> wearing(player, EquipmentSlot.CHEST, ModItems.HOVER_PACK.get())
            && Poles.near(player.level(), player.getX(), player.getY(), player.getZ(), HOVER_RANGE));
    }

    public static boolean wearing(ServerPlayer player, EquipmentSlot slot, Item item) {
        return player.getItemBySlot(slot).is(item);
    }

    /** Takes one of the item out of what the player carries: the inventory, then the backpack. */
    private static boolean take(ServerPlayer player, Item item) {
        Carried carried = Carried.of(player);
        for (int i = 0; i < carried.getContainerSize(); i++) {
            if (carried.getItem(i).is(item)) {
                carried.removeItem(i, 1);
                return true;
            }
        }
        return false;
    }

    /** True while a filter is running, starting a new one from the inventory when the last has run out. */
    private static boolean filtered(ServerPlayer player, AttachmentType<Long> until, Item filter) {
        if (player.hasInfiniteMaterials()) return true;
        long now = player.level().getGameTime();
        Long end = player.getAttached(until);
        // a filter never has more than its full time left, whatever the clock did
        if (end != null && end > now && end <= now + FILTER_TICKS) return true;
        if (!take(player, filter)) return false;
        player.setAttached(until, now + FILTER_TICKS);
        return true;
    }

    /** Asked by the radiation check: a Hazmat Suit with an Iodine Infused Filter keeps Toxic Residue from hurting. */
    public static boolean shieldsRadiation(ServerPlayer player) {
        return wearing(player, EquipmentSlot.CHEST, ModItems.HAZMAT_SUIT.get()) && filtered(player, SUIT_UNTIL, ModItems.PARTS.get("iodine_infused_filter"));
    }

    // ---- jetpack

    /** Starts a new fill of the jetpack from the first fuel carried (inventory or backpack). False when there is none. */
    public static boolean refuel(ServerPlayer player) {
        if (player.hasInfiniteMaterials()) {
            player.setAttached(JET_TICKS, CREATIVE_JET_TICKS);
            return true;
        }
        for (String[] fuel : JET_FUEL) {
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(fuel[0])).orElse(Items.AIR);
            if (item == Items.AIR || !take(player, item)) continue;
            player.setAttached(JET_TICKS, Integer.parseInt(fuel[1]));
            if (fuel[0].endsWith("_bucket")) player.getInventory().placeItemBackInInventory(new ItemStack(Items.BUCKET), Prediction.SERVER_ONLY);
            return true;
        }
        return false;
    }

    private static void thrust(MinecraftServer server, ServerPlayer player, boolean on) {
        UUID id = player.getUUID();
        if (!on || !wearing(player, EquipmentSlot.CHEST, ModItems.JETPACK.get())) {
            THRUSTING.remove(id);
            return;
        }
        if (jet(player) <= 0 && !refuel(player)) {
            THRUSTING.remove(id);
            ServerPlayNetworking.send(player, new JetFuelPayload(false));
            player.sendOverlayMessage(Component.translatable("siftec.jetpack.empty"));
            return;
        }
        if (!THRUSTING.containsKey(id)) ServerPlayNetworking.send(player, new JetFuelPayload(true));
        THRUSTING.put(id, server.getTickCount());
    }

    private static void tickJetpacks(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, Integer>> it = THRUSTING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            // the client repeats itself twice a second while the key is held; silence means it stopped
            if (player == null || server.getTickCount() - entry.getValue() > THRUST_TIMEOUT_TICKS || !wearing(player, EquipmentSlot.CHEST, ModItems.JETPACK.get())) {
                it.remove();
                continue;
            }
            int left = jet(player) - 1;
            player.setAttached(JET_TICKS, left);
            if (left <= 0 && !refuel(player)) {
                it.remove();
                ServerPlayNetworking.send(player, new JetFuelPayload(false));
                player.sendOverlayMessage(Component.translatable("siftec.jetpack.empty"));
                continue;
            }
            player.resetFallDistance();
            ((ConnectionAccessor) player.connection).siftec$setAboveGroundTickCount(0);
        }
    }

    // ---- everything else

    private static void tick(MinecraftServer server) {
        tickJetpacks(server);
        Ziplines.tick(server);
        tickSafeFall(server);
        Blueprints.tick(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if ((server.getTickCount() + player.getId()) % WORN_CHECK_TICKS != 0) continue;
            if (wearing(player, EquipmentSlot.FEET, ModItems.BLADE_RUNNERS.get())) {
                player.addEffect(new MobEffectInstance(MobEffects.SPEED, WORN_EFFECT_TICKS, 1, true, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, WORN_EFFECT_TICKS, 1, true, false, false));
            }
            if (wearing(player, EquipmentSlot.CHEST, ModItems.PARACHUTE.get()) && !player.onGround() && player.fallDistance > PARACHUTE_FALL) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, WORN_EFFECT_TICKS, 0, true, false, false));
            }
            if (wearing(player, EquipmentSlot.HEAD, ModItems.GAS_MASK.get()) && (player.hasEffect(MobEffects.POISON) || player.hasEffect(MobEffects.NAUSEA))
                && filtered(player, MASK_UNTIL, ModItems.PARTS.get("gas_filter"))) {
                player.removeEffect(MobEffects.POISON);
                player.removeEffect(MobEffects.NAUSEA);
            }
            flight(player);
        }
    }

    /** Gives or takes away the ability to fly. Players in survival have none of their own, so what is here is ours. */
    private static void flight(ServerPlayer player) {
        if (player.hasInfiniteMaterials() || player.isSpectator()) return;
        boolean want = false;
        for (Predicate<ServerPlayer> zone : FLIGHT_ZONES) want |= zone.test(player);
        if (want == player.getAbilities().mayfly) return;
        player.getAbilities().mayfly = want;
        if (!want) {
            // the pack cut out in mid-air: come down gently, all the way down
            player.getAbilities().flying = false;
            if (!player.onGround()) safeFall(player);
        }
        player.onUpdateAbilities();
    }
}
