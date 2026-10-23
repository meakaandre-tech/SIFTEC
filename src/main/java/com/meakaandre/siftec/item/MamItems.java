package com.meakaandre.siftec.item;

import com.meakaandre.siftec.hub.Locks;
import com.zurrtum.create.AllDataComponents;
import com.zurrtum.create.content.equipment.armor.BacktankBlockEntity;
import com.zurrtum.create.content.equipment.armor.BacktankUtil;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Items the MAM's research unlocks (MAM Research tab): the four inhalers, and the Elytra Backpack. Food is the only
 * other way to heal (Food and Brass tab: no natural regeneration), so an inhaler is a heal to carry.
 */
public final class MamItems {
    /** Health a Protein, Therapeutic and Nutritional Inhaler heal. */
    private static final float PROTEIN_HEAL = 6f, THERAPEUTIC_HEAL = 4f, NUTRITIONAL_HEAL = 14f;
    /** Regeneration a Vitamin and a Nutritional Inhaler give (ticks). */
    private static final int VITAMIN_REGEN_TICKS = 20 * 20, NUTRITIONAL_REGEN_TICKS = 10 * 20;
    /** Width of an item's durability bar in pixels (vanilla's). */
    private static final float BAR_WIDTH = 13f;
    /** Colour of the Elytra Backpack's bar while it shows air (a backtank's). */
    private static final int AIR_BAR_COLOUR = 0xEFEFEF;

    private MamItems() {
    }

    /** What an inhaler does when used. Each is used up. */
    public enum Inhaler {
        /** Alien Megafauna: a quick heal. */
        PROTEIN,
        /** Mycelia: regeneration over time. */
        VITAMIN,
        /** Mycelia: clears every bad effect, and a small heal. */
        THERAPEUTIC,
        /** Nutrients: a big heal, and regeneration. */
        NUTRITIONAL;

        public String key() {
            return "siftec.inhaler." + name().toLowerCase(Locale.ROOT);
        }

        void apply(Player player) {
            switch (this) {
                case PROTEIN -> player.heal(PROTEIN_HEAL);
                case VITAMIN -> player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, VITAMIN_REGEN_TICKS, 1));
                case THERAPEUTIC -> {
                    List<Holder<MobEffect>> bad = new ArrayList<>();
                    for (MobEffectInstance effect : player.getActiveEffects()) {
                        if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) bad.add(effect.getEffect());
                    }
                    for (Holder<MobEffect> effect : bad) player.removeEffect(effect);
                    player.heal(THERAPEUTIC_HEAL);
                }
                case NUTRITIONAL -> {
                    player.heal(NUTRITIONAL_HEAL);
                    player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, NUTRITIONAL_REGEN_TICKS, 0));
                }
            }
        }
    }

    /** An inhaler: use it to take it. */
    public static class InhalerItem extends Item {
        public final Inhaler kind;

        public InhalerItem(Inhaler kind, Properties properties) {
            super(properties);
            this.kind = kind;
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
            if (!Locks.allowed(player, stack)) {
                Locks.tellLocked(server, Locks.lockOf(stack.getItem()));
                return InteractionResult.FAIL;
            }
            kind.apply(player);
            server.sendOverlayMessage(Component.translatable(kind.key()));
            if (!player.hasInfiniteMaterials()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
    }

    /**
     * The Elytra Backpack: an Elytra and a Copper Backtank in one chest item. It glides like an Elytra (its glider
     * component), and it is in Create's pressurized_air_sources tag with Create's backtank air on it, so diving gear and
     * the air-hungry tools draw from it as from a worn backtank. It cannot be placed, so it fills up from a placed
     * backtank: use it on one to take that backtank's air.
     */
    public static class ElytraBackpackItem extends Item {
        public ElytraBackpackItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Player player = context.getPlayer();
            if (player == null || !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof BacktankBlockEntity tank)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
            ItemStack stack = context.getItemInHand();
            int have = BacktankUtil.getAir(stack), max = BacktankUtil.maxAir(stack);
            int take = Math.min(tank.getAirLevel(), max - have);
            if (take <= 0) {
                server.sendOverlayMessage(Component.translatable(tank.getAirLevel() <= 0 ? "siftec.elytra_backpack.empty_tank" : "siftec.elytra_backpack.air", have, max));
                return InteractionResult.FAIL;
            }
            tank.setAirLevel(tank.getAirLevel() - take);
            tank.setChanged();
            stack.set(AllDataComponents.BACKTANK_AIR, have + take);
            server.sendOverlayMessage(Component.translatable("siftec.elytra_backpack.filled", take));
            return InteractionResult.SUCCESS;
        }

        @Override
        public boolean isBarVisible(ItemStack stack) {
            return stack.isDamaged() || BacktankUtil.getAir(stack) < BacktankUtil.maxAir(stack);
        }

        @Override
        public int getBarWidth(ItemStack stack) {
            // the bar shows the air while there is some missing, as a backtank's does, else the Elytra's wear
            if (BacktankUtil.getAir(stack) < BacktankUtil.maxAir(stack)) return Math.round(BAR_WIDTH * BacktankUtil.getAir(stack) / Math.max(1, BacktankUtil.maxAir(stack)));
            return super.getBarWidth(stack);
        }

        @Override
        public int getBarColor(ItemStack stack) {
            return BacktankUtil.getAir(stack) < BacktankUtil.maxAir(stack) ? AIR_BAR_COLOUR : super.getBarColor(stack);
        }
    }
}
