package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.backpack.BackpackContainer;
import net.minecraft.core.NonNullList;
import net.minecraft.util.Prediction;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * The backpack behaves as more inventory rows (server side; the client is sent the result):
 * <ul>
 * <li>items picked up or given fill it once the hotbar and main rows are full; an item that already has a stack
 * in the backpack and none in the inventory tops that stack up first, as vanilla does with any part stack;</li>
 * <li>items handed back (crafting grid on close and the like) go there before they would be dropped;</li>
 * <li>death without keepInventory drops it with the rest of the inventory;</li>
 * <li>{@code /clear} clears and counts it.</li>
 * </ul>
 */
@Mixin(Inventory.class)
public abstract class InventoryBackpackMixin {
    @Shadow
    @Final
    public Player player;

    @Shadow
    public abstract int getSlotWithRemainingSpace(ItemStack stack);

    @Unique
    private boolean siftec$toppedUp;

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void siftec$topUpBackpack(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        siftec$toppedUp = false;
        if (slot != -1 || stack.isEmpty() || !stack.isStackable() || player.level().isClientSide() || player.hasInfiniteMaterials()) return;
        if (getSlotWithRemainingSpace(stack) != -1) return;
        siftec$toppedUp = Backpack.insert(player, stack, false);
        if (stack.isEmpty()) cir.setReturnValue(true);
    }

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"), cancellable = true)
    private void siftec$overflowToBackpack(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        boolean any = siftec$toppedUp;
        siftec$toppedUp = false;
        if (slot != -1 || player.level().isClientSide() || player.hasInfiniteMaterials()) return;
        if (!stack.isEmpty()) any |= Backpack.insert(player, stack, true);
        if (any && !cir.getReturnValueZ()) cir.setReturnValue(true);
    }

    @WrapOperation(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private ItemEntity siftec$backBeforeDropping(Player owner, ItemStack stack, boolean randomly, Prediction prediction, Operation<ItemEntity> original) {
        if (!owner.level().isClientSide()) {
            Backpack.insert(owner, stack, true);
            if (stack.isEmpty()) return null;
        }
        return original.call(owner, stack, randomly, prediction);
    }

    @Inject(method = "dropAll", at = @At("TAIL"))
    private void siftec$dropBackpack(CallbackInfo ci) {
        if (player.level().isClientSide()) return;
        NonNullList<ItemStack> items = player.getAttachedOrCreate(Backpack.CONTENTS).items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            ItemEntity entity = player.createItemStackToDrop(stack, true, false);
            items.set(i, ItemStack.EMPTY);
            if (entity != null) player.level().addFreshEntity(entity);
        }
    }

    @Inject(method = "clearOrCountMatchingItems", at = @At("RETURN"), cancellable = true)
    private void siftec$clearBackpack(Predicate<ItemStack> predicate, boolean countOnly, int max, Container crafting, CallbackInfoReturnable<Integer> cir) {
        if (player.level().isClientSide()) return;
        int count = cir.getReturnValueI();
        count += ContainerHelper.clearOrCountMatchingItems(new BackpackContainer(player), predicate, max - count, countOnly);
        cir.setReturnValue(count);
    }
}
