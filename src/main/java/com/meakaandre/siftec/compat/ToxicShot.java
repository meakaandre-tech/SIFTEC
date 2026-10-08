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
            java.lang.reflect.Method method = getType();
            return method == null ? null : method.invoke(null, ID);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static volatile java.util.Optional<java.lang.reflect.Method> getType;

    /** The library's AmmoHolder.getType, looked up once; null when the library is not installed. */
    private static java.lang.reflect.Method getType() {
        java.util.Optional<java.lang.reflect.Method> known = getType;
        if (known == null) {
            try {
                known = java.util.Optional.of(Class.forName("com.nukateam.ntgl.common.data.holders.AmmoHolder").getMethod("getType", Identifier.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                known = java.util.Optional.empty();
            }
            getType = known;
        }
        return known.orElse(null);
    }

    /** Per projectile class, the field holding its ammunition (empty if that class has none). */
    private static final java.util.Map<Class<?>, java.util.Optional<Field>> AMMO_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The id of the ammunition a gun library projectile was fired as, or "" for anything else. */
    private static String ammoOf(Entity projectile) {
        java.util.Optional<Field> field = AMMO_FIELDS.computeIfAbsent(projectile.getClass(), ToxicShot::ammoField);
        if (field.isEmpty()) return "";
        try {
            Object holder = field.get().get(projectile);
            return holder == null ? "" : holder.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    private static java.util.Optional<Field> ammoField(Class<?> projectile) {
        for (Class<?> type = projectile; type != null && type != Entity.class; type = type.getSuperclass()) {
            if (!type.getName().startsWith("com.nukateam.")) continue;
            try {
                Field field = type.getDeclaredField("ammoHolder");
                field.setAccessible(true);
                return java.util.Optional.of(field);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // not the class that holds it; try its parent
            }
        }
        return java.util.Optional.empty();
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
