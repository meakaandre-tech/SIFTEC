package com.meakaandre.siftec.mixin.building;

import com.zurrtum.create.content.processing.basin.BasinOperatingBlockEntity;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The recipe a Press or Mixer over a Basin is running. */
@Mixin(value = BasinOperatingBlockEntity.class, remap = false)
public interface BasinOperatorAccessor {
    @Accessor("currentRecipe")
    Recipe<?> siftec$recipe();
}
