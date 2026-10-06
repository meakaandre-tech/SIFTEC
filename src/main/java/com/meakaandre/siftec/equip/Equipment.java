package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.mixin.ConnectionAccessor;
import com.meakaandre.siftec.power.Poles;
import com.meakaandre.siftec.registry.ModItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Worn equipment. Blade Runners, Parachute, Gas Mask and Hazmat Suit work while worn; the Hover Pack lets
 * you fly near a turning Power Pole; the Jetpack burns fuel while the jump key is held in mid-air.
 */
public final class Equipment {
    /** How close a turning pole has to be for the Hover Pack. */
    public static final double HOVER_RANGE = 32;
    /** One filter lasts this long while the mask or suit is actually stopping something. */
    private static final int FILTER_TICKS = 20 * 60;
    /** Jetpack fuels: item id -> ticks of thrust. Buckets come back empty. */
    private static final String[][] JET_FUEL = {{"siftec:turbofuel_bucket", "3000"}, {"createdieselgenerators:diesel_bucket", "1200"},
        {"siftec:compacted_coal", "600"}, {"siftec:solid_biofuel", "300"}};

    private static final Map<UUID, Integer> THRUSTING = new HashMap<>();
    private static final Map<UUID, Integer> JET_TICKS = new HashMap<>();
    private static final Map<UUID, Long> MASK_UNTIL = new HashMap<>(), SUIT_UNTIL = new HashMap<>();
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
        // carriers and drones are only ever drawn for something in flight; one read back from disk is left over from a restart
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.entityTags().contains(Ziplines.TAG)) level.getServer().execute(entity::discard);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            THRUSTING.clear();
            JET_TICKS.clear();
            MASK_UNTIL.clear();
            SUIT_UNTIL.clear();
            Poles.clear();
            Ziplines.clear();
            com.meakaandre.siftec.blueprint.DesignerBlockEntity.clear();
        });
        FLIGHT_ZONES.add(com.meakaandre.siftec.blueprint.DesignerBlockEntity::inside);
        FLIGHT_ZONES.add(player -> wearing(player, EquipmentSlot.CHEST, ModItems.HOVER_PACK.get())
            && Poles.near(player.level(), player.getX(), player.getY(), player.getZ(), HOVER_RANGE));
    }

    public static boolean wearing(ServerPlayer player, EquipmentSlot slot, Item item) {
        return player.getItemBySlot(slot).is(item);
    }

    /** Takes one of the item out of the player's inventory. */
    private static boolean take(ServerPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                inventory.removeItem(i, 1);
                return true;
            }
        }
        return false;
    }

    /** True while a filter is running, starting a new one from the inventory when the last has run out. */
    private static boolean filtered(ServerPlayer player, Map<UUID, Long> until, Item filter) {
        if (player.hasInfiniteMaterials()) return true;
        long now = player.level().getGameTime();
        if (until.getOrDefault(player.getUUID(), 0L) > now) return true;
        if (!take(player, filter)) return false;
        until.put(player.getUUID(), now + FILTER_TICKS);
        return true;
    }

    /** Asked by the radiation check: a Hazmat Suit with an Iodine Infused Filter keeps Toxic Residue from hurting. */
    public static boolean shieldsRadiation(ServerPlayer player) {
        return wearing(player, EquipmentSlot.CHEST, ModItems.HAZMAT_SUIT.get()) && filtered(player, SUIT_UNTIL, ModItems.PARTS.get("iodine_infused_filter"));
    }

    // ---- jetpack

    private static boolean refuel(ServerPlayer player) {
        if (player.hasInfiniteMaterials()) {
            JET_TICKS.put(player.getUUID(), 1200);
            return true;
        }
        for (String[] fuel : JET_FUEL) {
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(fuel[0])).orElse(Items.AIR);
            if (item == Items.AIR || !take(player, item)) continue;
            JET_TICKS.put(player.getUUID(), Integer.parseInt(fuel[1]));
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
        if (JET_TICKS.getOrDefault(id, 0) <= 0 && !refuel(player)) {
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
            if (player == null || server.getTickCount() - entry.getValue() > 15 || !wearing(player, EquipmentSlot.CHEST, ModItems.JETPACK.get())) {
                it.remove();
                continue;
            }
            int left = JET_TICKS.getOrDefault(entry.getKey(), 0) - 1;
            JET_TICKS.put(entry.getKey(), left);
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
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if ((server.getTickCount() + player.getId()) % 10 != 0) continue;
            if (wearing(player, EquipmentSlot.FEET, ModItems.BLADE_RUNNERS.get())) {
                player.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 1, true, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 40, 1, true, false, false));
            }
            if (wearing(player, EquipmentSlot.CHEST, ModItems.PARACHUTE.get()) && !player.onGround() && player.fallDistance > 3) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 40, 0, true, false, false));
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
            // the pack cut out in mid-air: come down gently
            player.getAbilities().flying = false;
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0, true, false, false));
        }
        player.onUpdateAbilities();
    }
}
