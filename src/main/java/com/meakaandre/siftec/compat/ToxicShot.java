package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.Siftec;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;

/**
 * Toxic Shot: Toxic Residue packed into a Gunsmithing paper shot. A gun with the Blunderbuss Barrel takes it
 * like ordinary paper shot. It does next to no damage; what it hits gets Nausea and Wither. Works through
 * Create: Gunsmithing and its gun library when they are installed, without needing them to build.
 */
public final class ToxicShot {
    public static final Identifier ID = Siftec.id("toxic_shot");
    private static final int SECONDS = 10;

    private ToxicShot() {
    }

    /** The gun library's ammo type for the shot, or null when the library is not installed. */
    public static Object ammoType() {
        try {
            return Class.forName("com.nukateam.ntgl.common.data.holders.AmmoHolder").getMethod("getType", Identifier.class).invoke(null, ID);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** The id of the ammunition a gun library projectile was fired as, or "" for anything else. */
    private static String ammoOf(Entity projectile) {
        for (Class<?> type = projectile.getClass(); type != null && type != Entity.class; type = type.getSuperclass()) {
            if (!type.getName().startsWith("com.nukateam.")) continue;
            try {
                Field field = type.getDeclaredField("ammoHolder");
                field.setAccessible(true);
                Object holder = field.get(projectile);
                return holder == null ? "" : holder.toString();
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not the class that holds it; try its parent
            }
        }
        return "";
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Entity direct = source.getDirectEntity();
            if (direct != null && direct.getClass().getName().startsWith("com.nukateam.") && ammoOf(direct).equals(ID.toString())) {
                entity.addEffect(new MobEffectInstance(MobEffects.NAUSEA, SECONDS * 20, 0));
                entity.addEffect(new MobEffectInstance(MobEffects.WITHER, SECONDS * 20, 0));
            }
            return true;
        });
    }
}
