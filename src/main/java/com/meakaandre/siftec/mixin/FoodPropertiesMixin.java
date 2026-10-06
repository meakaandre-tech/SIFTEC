package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.food.Food;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Eating heals and passes on the food's effects (see {@link Food}). */
@Mixin(FoodProperties.class)
public abstract class FoodPropertiesMixin {
    @Inject(method = "onConsume", at = @At("TAIL"))
    private void siftec$eat(Level level, LivingEntity entity, ItemStack stack, Consumable consumable, CallbackInfo ci) {
        Food.onEat(entity, stack, (FoodProperties) (Object) this);
    }
}
