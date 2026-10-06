package com.meakaandre.siftec.backpack;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.net.ClientState;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * The backpack: extra slots under the normal inventory, shown whenever the inventory is open.
 * Every "+3 backpack slots" reward opens three more. The items are saved with the player and are kept on death.
 */
public final class Backpack {
    /** All the slots there can ever be: four rows under the inventory. */
    public static final int SIZE = 36;
    public static final int PER_REWARD = 3;

    /** The saved contents. A plain holder, changed in place. */
    public static final class Contents {
        public final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

        static final Codec<Contents> CODEC = ItemStack.OPTIONAL_CODEC.listOf().xmap(list -> {
            Contents contents = new Contents();
            for (int i = 0; i < list.size() && i < SIZE; i++) contents.items.set(i, list.get(i));
            return contents;
        }, contents -> List.copyOf(contents.items));
    }

    public static final AttachmentType<Contents> CONTENTS = AttachmentRegistry.create(Siftec.id("backpack"),
        builder -> builder.persistent(Contents.CODEC).copyOnDeath().initializer(Contents::new));

    private Backpack() {
    }

    public static void register() {
    }

    /** How many slots are open for this player. */
    public static int unlocked(Player player) {
        if (player.hasInfiniteMaterials()) return SIZE;
        if (player instanceof ServerPlayer server) {
            Company company = Companies.of(server);
            return Math.min(SIZE, company.count("backpack") * PER_REWARD);
        }
        return ClientState.backpackSlots;
    }

    /** The same count, worked out on the client from the progress the server sent. */
    public static int unlocked(Set<String> done) {
        int rewards = 0;
        for (String id : done) {
            Milestone m = Milestones.get(id);
            if (m != null && m.tokens().contains("backpack")) rewards++;
        }
        return Math.min(SIZE, rewards * PER_REWARD);
    }
}
