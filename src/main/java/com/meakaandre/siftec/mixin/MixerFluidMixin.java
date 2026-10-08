package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Boosts;
import com.zurrtum.create.content.kinetics.mixer.MixingRecipe;
import com.zurrtum.create.content.kinetics.mixer.PotionRecipe;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.List;

/** A Mechanical Mixer with a Somersloop doubles the fluids a recipe makes too, not only the items. */
@Mixin(value = {MixingRecipe.class, PotionRecipe.class}, remap = false)
public abstract class MixerFluidMixin {
    @ModifyArg(method = {"matches", "apply"}, index = 1, at = @At(value = "INVOKE",
        target = "Lcom/zurrtum/create/content/processing/basin/BasinInput;acceptOutputs(Ljava/util/List;Ljava/util/List;Z)Z"))
    private List<FluidStack> siftec$doubleFluids(List<FluidStack> fluids) {
        if (fluids.isEmpty() || !Boosts.doubling()) return fluids;
        List<FluidStack> doubled = new ArrayList<>(fluids.size());
        for (FluidStack fluid : fluids) doubled.add(fluid.isEmpty() ? fluid : fluid.copyWithAmount(fluid.getAmount() * 2));
        return doubled;
    }
}
