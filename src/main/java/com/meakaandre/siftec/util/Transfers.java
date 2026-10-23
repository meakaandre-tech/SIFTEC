package com.meakaandre.siftec.util;

import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Moving things into and out of a Fabric {@link Storage} (one transaction each) or a {@link Container}. */
public final class Transfers {
    private Transfers() {
    }

    /** Inserts up to {@code amount} and commits; returns how much went in. */
    public static <T> long insert(Storage<T> storage, T resource, long amount) {
        return insert(storage, resource, amount, false);
    }

    /** Inserts up to {@code amount}, committed unless {@code simulate}; returns how much went (or would go) in. */
    public static <T> long insert(Storage<T> storage, T resource, long amount, boolean simulate) {
        try (Transaction tx = Transaction.openOuter()) {
            long put = storage.insert(resource, amount, tx);
            if (!simulate) tx.commit();
            return put;
        }
    }

    /** Inserts {@code amount}, committed only if all of it went in; returns how much the storage took. */
    public static <T> long insertAll(Storage<T> storage, T resource, long amount) {
        try (Transaction tx = Transaction.openOuter()) {
            long put = storage.insert(resource, amount, tx);
            if (put == amount) tx.commit();
            return put;
        }
    }

    /** Extracts up to {@code amount} and commits; returns how much came out. */
    public static <T> long extract(Storage<T> storage, T resource, long amount) {
        try (Transaction tx = Transaction.openOuter()) {
            long took = storage.extract(resource, amount, tx);
            tx.commit();
            return took;
        }
    }

    /** Empties the container's first {@code size} slots (no update), adding what was not empty to {@code out}. */
    public static void takeAll(Container container, int size, List<ItemStack> out) {
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.removeItemNoUpdate(i);
            if (!stack.isEmpty()) out.add(stack);
        }
    }
}
