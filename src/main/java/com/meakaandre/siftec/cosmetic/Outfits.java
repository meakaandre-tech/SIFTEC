package com.meakaandre.siftec.cosmetic;

import com.meakaandre.siftec.sink.Shop;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;

import java.util.Optional;

/**
 * Helmet skins and suit trims (AWESOME Shop): the Customizer used in the air puts them on what the player wears. A
 * helmet skin changes how the worn helmet is drawn (another helmet's look); a suit trim is a trim on every armour piece
 * worn that takes one. "" takes them off again.
 */
public final class Outfits {
    private static final EquipmentSlot[] SUIT = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private Outfits() {
    }

    /** Draws the worn helmet as a skin ("" for its own look); false if no helmet is worn. */
    public static boolean helmet(ServerPlayer player, String skin) {
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        Equippable own = helmet.getPrototype().get(DataComponents.EQUIPPABLE);
        if (helmet.isEmpty() || own == null) return false;
        String asset = Shop.helmets().get(skin);
        if (asset == null) {
            helmet.set(DataComponents.EQUIPPABLE, own);
            return true;
        }
        Optional<ResourceKey<EquipmentAsset>> look = Optional.of(ResourceKey.create(EquipmentAssets.ROOT_ID, Identifier.parse(asset)));
        helmet.set(DataComponents.EQUIPPABLE, new Equippable(own.slot(), own.equipSound(), look, own.cameraOverlay(), own.allowedEntities(), own.dispensable(),
            own.swappable(), own.damageOnHurt(), own.equipOnInteract(), own.canBeSheared(), own.shearingSound()));
        return true;
    }

    /** Puts a suit trim on every armour piece worn that takes one ("" takes it off); how many pieces it went on. */
    public static int trim(ServerPlayer player, String trim) {
        String[] t = Shop.trims().get(trim);
        ArmorTrim armorTrim = null;
        if (t != null) {
            var access = player.level().registryAccess();
            Optional<Holder.Reference<TrimPattern>> pattern = access.lookupOrThrow(Registries.TRIM_PATTERN).get(ResourceKey.create(Registries.TRIM_PATTERN, Identifier.parse(t[0])));
            Optional<Holder.Reference<TrimMaterial>> material = access.lookupOrThrow(Registries.TRIM_MATERIAL).get(ResourceKey.create(Registries.TRIM_MATERIAL, Identifier.parse(t[1])));
            if (pattern.isEmpty() || material.isEmpty()) return 0;
            armorTrim = new ArmorTrim(material.get(), pattern.get());
        }
        int n = 0;
        for (EquipmentSlot slot : SUIT) {
            ItemStack piece = player.getItemBySlot(slot);
            if (piece.isEmpty() || !piece.is(ItemTags.TRIMMABLE_ARMOR)) continue;
            if (armorTrim == null) piece.remove(DataComponents.TRIM);
            else piece.set(DataComponents.TRIM, armorTrim);
            n++;
        }
        return n;
    }
}
