package com.meakaandre.siftec.owner;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.registry.ModItems;
import com.mojang.serialization.Codec;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Power Shards and Somersloops in Create's own machines. Click a Press, Mixer, Saw, Millstone, Deployer, Crushing
 * Wheel, Encased Fan, Mechanical Crafter or Spout with
 * one to slot it in; sneak-click it empty-handed to take them out. Shards make the machine work faster for
 * more stress; a Somersloop doubles what each recipe gives for four times the stress.
 */
public final class Boosts {
    /** Shards slotted in, plus {@link #SLOOP} when a Somersloop is. Saved with the block. */
    public static final AttachmentType<Integer> BOOST = AttachmentRegistry.create(Siftec.id("boost"), builder -> builder.persistent(Codec.INT));
    private static final int SLOOP = 10;
    private static final float[] SPEED = {1f, 1.5f, 2f, 2.5f}, STRESS = {1f, 1.7f, 2.5f, 3.4f};
    private static final Set<String> MACHINES = Set.of("create:mechanical_press", "create:mechanical_mixer", "create:mechanical_saw", "create:millstone", "create:deployer",
        "create:crushing_wheel", "create:encased_fan", "create:mechanical_crafter", "create:spout");
    /** These make their results in a way a Somersloop cannot double, so they take shards only. */
    private static final Set<String> SHARDS_ONLY = Set.of("create:mechanical_crafter", "create:spout");
    private static final String CONTROLLER = "create:crushing_wheel_controller";
    /** The part of an extra tick each boosted machine is still owed. */
    private static final Map<BlockEntity, float[]> OWED = new WeakHashMap<>();
    private static boolean running, looked;
    private static net.minecraft.world.level.block.Block controller, wheelBlock;

    private Boosts() {
    }

    private static int own(BlockEntity be) {
        Integer value = be.getAttached(BOOST);
        return value == null ? 0 : value;
    }

    /**
     * What a machine is boosted by. Crushing is done by an unseen block between the two wheels, which goes by
     * whichever wheel beside it holds more.
     */
    private static int boost(BlockEntity be) {
        int boost = own(be);
        if (boost != 0 || be.getLevel() == null) return boost;
        if (!looked) {
            controller = BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(CONTROLLER)).orElse(null);
            wheelBlock = BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse("create:crushing_wheel")).orElse(null);
            looked = true;
        }
        if (controller == null || be.getBlockState().getBlock() != controller) return 0;
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            BlockEntity wheel = be.getLevel().getBlockEntity(be.getBlockPos().relative(side));
            if (wheel != null && wheel.getBlockState().getBlock() == wheelBlock) {
                int theirs = own(wheel);
                if (theirs % SLOOP > boost % SLOOP || (theirs >= SLOOP && boost < SLOOP)) boost = Math.max(boost % SLOOP, theirs % SLOOP) + (boost >= SLOOP || theirs >= SLOOP ? SLOOP : 0);
            }
        }
        return boost;
    }

    /** Drops what is slotted into a machine that is going away. */
    public static void drop(BlockEntity be) {
        int boost = own(be);
        if (boost == 0 || be.getLevel() == null || be.getLevel().isClientSide()) return;
        BlockPos pos = be.getBlockPos();
        if (boost % SLOOP > 0) Containers.dropItemStack(be.getLevel(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(ModItems.PARTS.get("power_shard"), boost % SLOOP));
        if (boost >= SLOOP) Containers.dropItemStack(be.getLevel(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(ModItems.PARTS.get("somersloop")));
        be.removeAttached(BOOST);
    }

    public static int shards(BlockEntity be) {
        return Math.min(3, boost(be) % SLOOP);
    }

    public static boolean amplified(BlockEntity be) {
        return boost(be) >= SLOOP;
    }

    public static float stressFactor(BlockEntity be) {
        int boost = boost(be);
        return boost == 0 ? 1f : STRESS[Math.min(3, boost % SLOOP)] * (boost >= SLOOP ? 4f : 1f);
    }

    /** True if the machine ticking right now has a Somersloop in it. */
    public static boolean doubling() {
        BlockEntity machine = Ownership.ticking();
        return machine != null && amplified(machine);
    }

    private static void changed(BlockEntity be, int boost) {
        if (boost == 0) be.removeAttached(BOOST);
        else be.setAttached(BOOST, boost);
        be.setChanged();
        if (be instanceof KineticBlockEntity kinetic && kinetic.hasNetwork()) kinetic.getOrCreateNetwork().updateStressFor(kinetic, kinetic.calculateStressApplied());
    }

    /** Runs a boosted machine's tick again, as often as its shards call for. Called right after its ordinary tick. */
    @SuppressWarnings("unchecked")
    public static void extraTicks(BlockEntity be, BlockEntityTicker<?> ticker) {
        if (running || !(be.getLevel() instanceof ServerLevel level)) return;
        int shards = shards(be);
        if (shards == 0 || (be instanceof KineticBlockEntity kinetic && kinetic.getSpeed() == 0)) return;
        float[] owed = OWED.computeIfAbsent(be, k -> new float[1]);
        owed[0] += SPEED[shards] - 1f;
        running = true;
        try {
            while (owed[0] >= 1f && !be.isRemoved()) {
                owed[0] -= 1f;
                ((BlockEntityTicker<BlockEntity>) ticker).tick(level, be.getBlockPos(), be.getBlockState(), be);
            }
        } finally {
            running = false;
        }
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (!MACHINES.contains(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString())) return InteractionResult.PASS;
            ItemStack stack = player.getItemInHand(hand);
            boolean shard = stack.is(ModItems.PARTS.get("power_shard")), loop = stack.is(ModItems.PARTS.get("somersloop"));
            boolean eject = stack.isEmpty() && player.isShiftKeyDown();
            if (!shard && !loop && !eject) return InteractionResult.PASS;
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || (eject && own(be) == 0)) return InteractionResult.PASS;
            // someone else's claim: leave it to the claim check to say no
            if (!com.meakaandre.siftec.claim.Claims.allowed(player, level, pos)) return InteractionResult.PASS;
            boolean shardsOnly = SHARDS_ONLY.contains(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
            if (loop && shardsOnly) {
                if (player instanceof ServerPlayer told) told.sendOverlayMessage(Component.translatable("siftec.boost.shards_only"));
                return InteractionResult.SUCCESS;
            }
            if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
            int boost = own(be), shards = boost % SLOOP;
            boolean amplified = boost >= SLOOP;
            if (eject) {
                if (shards > 0) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, new ItemStack(ModItems.PARTS.get("power_shard"), shards));
                if (amplified) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, new ItemStack(ModItems.PARTS.get("somersloop")));
                changed(be, 0);
                server.sendOverlayMessage(Component.translatable("siftec.boost.status", 0, Component.translatable("siftec.boost.no")));
                return InteractionResult.SUCCESS;
            }
            Company company = Companies.of(server);
            boolean free = server.hasInfiniteMaterials(), both = free || company.hasToken("amplifier:shards");
            if (shard) {
                int slots = free ? 3 : company.best("shards:", 0);
                if (shards >= slots || amplified && !both) {
                    server.sendOverlayMessage(Component.translatable("siftec.boost.no_slot"));
                    return InteractionResult.SUCCESS;
                }
                shards++;
            } else {
                if (amplified || !(free || company.hasToken("amplifier")) || shards > 0 && !both) {
                    server.sendOverlayMessage(Component.translatable("siftec.boost.no_slot"));
                    return InteractionResult.SUCCESS;
                }
                amplified = true;
            }
            if (!free) stack.shrink(1);
            changed(be, shards + (amplified ? SLOOP : 0));
            server.sendOverlayMessage(Component.translatable("siftec.boost.status", shards, Component.translatable(amplified ? "siftec.boost.yes" : "siftec.boost.no")));
            return InteractionResult.SUCCESS;
        });
    }
}
