package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.prefab.Prefabs;
import com.zurrtum.create.api.contraption.storage.item.MountedItemStorage;
import com.zurrtum.create.content.contraptions.Contraption;
import com.zurrtum.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;

/**
 * A contraption that stops (a bearing, piston, gantry, pulley or cart putting its blocks back) places none of them in a
 * claim other than the one it was put together in, nor on a Build Gun building's block, a HUB or Gateway part or a
 * resource node. Each such block is left out of the world and comes back as its item, with what its storage held, at
 * the contraption's anchor (where it was assembled, so in land its builder could use); nothing is placed over or into
 * the foreign claim.
 */
@Mixin(Contraption.class)
public abstract class ClaimDisassemblyMixin {
    @Inject(method = "addBlocksToWorld", at = @At("HEAD"))
    private void siftec$claim(Level level, StructureTransform transform, CallbackInfo ci) {
        Contraption self = (Contraption) (Object) this;
        if (level.isClientSide() || self.disassembled || self.anchor == null) return;
        BlockPos anchor = self.anchor;
        for (Iterator<Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo>> it = self.getBlocks().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> e = it.next();
            BlockPos target = transform.apply(e.getValue().pos());
            if (!Claims.crosses(level, anchor, target) && !Buildings.protectedAt(level, target)
                && !NodeSites.isSite(level, target)
                && !Prefabs.isPart(level.getBlockState(target))) continue;
            BlockState state = e.getValue().state();
            if (!state.isAir()) {
                ItemStack item = new ItemStack(state.getBlock().asItem());
                if (!item.isEmpty()) Containers.dropItemStack(level, anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5, item);
            }
            try {
                MountedItemStorage storage = self.getStorage().getAllItemStorages().get(e.getKey());
                if (storage != null) Containers.dropContents(level, anchor, storage);
            } catch (IllegalStateException uninitialized) {
                // storage never set up (nothing mounted): nothing held to give back
            }
            it.remove();
        }
    }
}
