package com.meakaandre.siftec.mixin.power;

import com.meakaandre.siftec.building.BuildingPower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.antarcticgardens.cna.content.nuclear.reactor.fuelacceptor.ReactorFuelAcceptorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A New Age reactor a player builds burns Satisfactory's fuel rods (the pack's {@code create_new_age:nuclear/fuel} tag),
 * and every rod it takes leaves Satisfactory's waste: put into a container next to the fuel acceptor, or dropped on top.
 */
@Mixin(value = ReactorFuelAcceptorBlockEntity.class, remap = false)
public abstract class FuelAcceptorMixin {
    @Unique
    private ItemStack siftec$before = ItemStack.EMPTY;

    @Inject(method = "tick", at = @At("HEAD"))
    private void siftec$count(BlockPos pos, Level world, BlockState state, CallbackInfo ci) {
        siftec$before = ((ReactorFuelAcceptorBlockEntity) (Object) this).container.getItem(0).copy();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void siftec$waste(BlockPos pos, Level world, BlockState state, CallbackInfo ci) {
        if (world.isClientSide() || siftec$before.isEmpty()) return;
        ItemStack now = ((ReactorFuelAcceptorBlockEntity) (Object) this).container.getItem(0);
        int used = ItemStack.isSameItemSameComponents(now, siftec$before) ? siftec$before.getCount() - now.getCount() : now.isEmpty() ? siftec$before.getCount() : 0;
        if (used > 0) BuildingPower.reactorWaste(world, pos, siftec$before, used);
        siftec$before = ItemStack.EMPTY;
    }
}
