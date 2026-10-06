package com.meakaandre.siftec.food;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * A pickle, a jam or mead. Eaten, it heals more than plain food and gives its ingredient's effect stronger and
 * for longer. Sneak and use it to throw the jar instead: it bursts like a splash potion with the same effect.
 */
public class PreserveItem extends Item {
    public final Food.Intrinsic intrinsic;

    public PreserveItem(Food.Intrinsic intrinsic, Properties properties) {
        super(properties);
        this.intrinsic = intrinsic;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown() || intrinsic.effect == null) return super.use(level, player, hand);
        if (level instanceof ServerLevel server) {
            ItemStack jar = new ItemStack(Items.SPLASH_POTION);
            jar.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(),
                List.of(new MobEffectInstance(intrinsic.effect, Food.Prep.PRESERVED.duration, Food.Prep.PRESERVED.amplifier)), Optional.empty()));
            Projectile.spawnProjectileFromRotation(ThrownSplashPotion::new, server, jar, player, -20.0f, 0.5f, 1.0f);
            player.getItemInHand(hand).consume(1, player);
        }
        return InteractionResult.SUCCESS;
    }
}
