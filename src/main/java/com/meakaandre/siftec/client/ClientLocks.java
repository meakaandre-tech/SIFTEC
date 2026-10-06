package com.meakaandre.siftec.client;

import com.google.gson.JsonObject;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.net.ClientState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/** What the client knows about locks: why a recipe or an item is not available to the player's company yet. */
public final class ClientLocks {
    private static final String SEQUENCE = "sequenced_assembly_";

    private ClientLocks() {
    }

    private static boolean done(String lock) {
        Set<String> done = ClientState.done;
        return done != null && done.contains(lock);
    }

    /** The line to show for a lock the company has not finished, or null if it has. */
    private static @Nullable Component line(@Nullable String lock) {
        if (lock == null || done(lock)) return null;
        if (lock.equals(Locks.DISABLED.id())) return Component.translatable("siftec.lock.disabled");
        if (lock.startsWith("alt_")) return Component.translatable("siftec.lock.alt", Component.translatable("siftec.alt." + lock));
        Milestone m = Milestones.get(lock);
        return m == null ? null : Component.translatable("siftec.lock.item", m.name());
    }

    public static @Nullable Component item(ItemStack stack) {
        Milestone lock = stack.isEmpty() ? null : Locks.lockOf(stack.getItem());
        return lock == null ? null : line(lock.id());
    }

    /**
     * Why this recipe will not run for the player's company, or null if it will. A recipe is locked by its own
     * id when the pack lists it (alternates, the Farmer's Delight conversions), and otherwise by what it makes.
     */
    public static @Nullable Component recipe(Object recipe, List<ItemStack> results) {
        if (recipe instanceof RecipeHolder<?> holder) {
            Identifier id = holder.id().identifier();
            String key = id.toString();
            if (id.getNamespace().equals("create") && id.getPath().startsWith(SEQUENCE)) {
                String rest = id.getPath().substring(SEQUENCE.length());
                int from = rest.indexOf('_'), to = rest.lastIndexOf('_');
                if (from >= 0 && to > from) key = "seq:" + rest.substring(from + 1, to);
            }
            JsonObject locks = Milestones.raw().getAsJsonObject("recipe_locks");
            if (locks.has(key)) return line(locks.get(key).getAsString());
        }
        for (ItemStack result : results) {
            Component why = item(result);
            if (why != null) return why;
        }
        return null;
    }
}
