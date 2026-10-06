package com.meakaandre.siftec.mixin;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Takes out the recipes from other mods that the pack replaces (listed in siftec_content.json). */
@Mixin(RecipeMap.class)
public abstract class RecipeMapMixin {
    @Shadow @Final @Mutable
    private Multimap<RecipeType<?>, RecipeHolder<?>> byType;
    @Shadow @Final @Mutable
    private Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey;

    @Inject(method = "create", at = @At("RETURN"))
    private static void siftec$removeRecipes(CallbackInfoReturnable<RecipeMap> cir) {
        RecipeMapMixin map = (RecipeMapMixin) (Object) cir.getReturnValue();
        ImmutableMultimap.Builder<RecipeType<?>, RecipeHolder<?>> types = ImmutableMultimap.builder();
        ImmutableMap.Builder<ResourceKey<Recipe<?>>, RecipeHolder<?>> keys = ImmutableMap.builder();
        for (Map.Entry<ResourceKey<Recipe<?>>, RecipeHolder<?>> entry : map.byKey.entrySet()) {
            if (Milestones.removedRecipes().contains(entry.getKey().identifier())) continue;
            keys.put(entry);
            types.put(entry.getValue().value().getType(), entry.getValue());
        }
        map.byType = types.build();
        map.byKey = keys.build();
    }

    /** A machine is only offered the recipes its company has unlocked. */
    @Inject(method = "getRecipesFor", at = @At("RETURN"), cancellable = true)
    private void siftec$locked(CallbackInfoReturnable<java.util.stream.Stream<RecipeHolder<?>>> cir) {
        if (com.meakaandre.siftec.owner.Ownership.ticking() == null) return;
        cir.setReturnValue(cir.getReturnValue().filter(holder -> !com.meakaandre.siftec.owner.RecipeLocks.blocked(holder)));
    }
}
