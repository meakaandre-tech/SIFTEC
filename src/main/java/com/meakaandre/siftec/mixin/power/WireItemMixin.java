package com.meakaandre.siftec.mixin.power;

import com.meakaandre.siftec.power.Wires;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.wire.ElectricWireItem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.antarcticgardens.cna.CNADataComponents.BOUND_TO;

/**
 * The pack's wire rules ({@link Wires}) on New Age's wire items: how many wires a building's connector takes, and how
 * long a wire may be (256 blocks tower to tower, 32 between buildings, New Age's own limit otherwise). A wire longer
 * than New Age allows but within the pack's limit is strung here, the same way New Age strings one.
 */
@Mixin(value = ElectricWireItem.class, remap = false)
public abstract class WireItemMixin {
    /** A bound wire lets go once the holder is farther than reach times the square root of this (squared distance). */
    @Unique
    private static final int LET_GO_REACH_SQR_FACTOR = 3;

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void siftec$rules(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        BlockPos bound = stack.get(BOUND_TO);
        if (bound == null || !(level.getBlockEntity(context.getClickedPos()) instanceof AbstractElectricalConnector clicked)) return;
        if (bound.equals(clicked.getBlockPos()) || clicked.isConnected(bound)) return;
        int newAge = Wires.newAgeLength();
        double dist2 = bound.distSqr(clicked.getBlockPos());
        if (level.isClientSide()) {
            // the server knows which connectors are towers: let it decide anything up to a tower's reach
            if (dist2 > (double) newAge * newAge && dist2 <= (double) Wires.TOWER_REACH * Wires.TOWER_REACH) cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        if (!(level.getBlockEntity(bound) instanceof AbstractElectricalConnector from)) return;
        @Nullable Player player = context.getPlayer();
        String why = Wires.refuse(level, from, clicked);
        if (why != null) {
            if (player != null) player.sendOverlayMessage(Component.translatable(why, Wires.maxLength(level, from.getBlockPos(), clicked.getBlockPos())));
            if (why.equals("siftec.power.lines_full")) stack.remove(BOUND_TO);
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if (dist2 > (double) newAge * newAge) {
            stack.remove(BOUND_TO);
            from.connect(clicked, ((ElectricWireItem) (Object) this).getWireType());
            if (player == null || !player.isCreative()) stack.shrink(1);
            if (player != null) {
                player.playSound(SoundEvents.LEAD_TIED, 1.0f, 1.0f);
                player.sendOverlayMessage(Component.translatable("item.create_new_age.wire.message.connected"));
            }
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }

    /** A wire bound to a tower or building connector is carried as far as it could reach before it lets go. */
    @Inject(method = "inventoryTick", at = @At("HEAD"), cancellable = true)
    private void siftec$reach(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, CallbackInfo ci) {
        BlockPos bound = stack.get(BOUND_TO);
        if (slot != EquipmentSlot.MAINHAND || bound == null) return;
        int reach = Wires.reach(level, bound);
        if (reach <= Wires.newAgeLength()) return;
        ci.cancel();
        if (!(level.getBlockEntity(bound) instanceof AbstractElectricalConnector)) {
            stack.remove(BOUND_TO);
            return;
        }
        if (entity.distanceToSqr(bound.getX(), bound.getY(), bound.getZ()) > (double) reach * reach * LET_GO_REACH_SQR_FACTOR) {
            stack.remove(BOUND_TO);
            entity.playSound(SoundEvents.LEAD_UNTIED, 1.0f, 1.0f);
            if (entity instanceof Player player) player.sendOverlayMessage(Component.translatable("item.create_new_age.wire.message.too_far", reach));
        }
    }
}
