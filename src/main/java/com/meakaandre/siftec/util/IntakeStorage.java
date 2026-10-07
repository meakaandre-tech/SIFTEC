package com.meakaandre.siftec.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * A slot that swallows what is put in. Belts and funnels try an insert first and take it back, so
 * nothing counts until the insert is really carried out.
 */
public class IntakeStorage extends SnapshotParticipant<Integer> implements InsertionOnlyStorage<ItemVariant> {
    private final ToLongFunction<ItemStack> room;
    private final Consumer<ItemStack> taken;
    private final List<ItemStack> pending = new ArrayList<>();

    /** @param room how many of this item are wanted right now; @param taken called once items are really in */
    public IntakeStorage(ToLongFunction<ItemStack> room, Consumer<ItemStack> taken) {
        this.room = room;
        this.taken = taken;
    }

    @Override
    public long insert(ItemVariant variant, long maxAmount, TransactionContext transaction) {
        if (variant.isBlank() || maxAmount <= 0) return 0;
        ItemStack one = variant.toStack();
        long free = room.applyAsLong(one);
        for (ItemStack waiting : pending) {
            if (ItemStack.isSameItemSameComponents(waiting, one)) free -= waiting.getCount();
        }
        int amount = (int) Math.min(Math.min(maxAmount, free), 1 << 20);
        if (amount <= 0) return 0;
        updateSnapshots(transaction);
        pending.add(variant.toStack(amount));
        return amount;
    }

    @Override
    protected Integer createSnapshot() {
        return pending.size();
    }

    @Override
    protected void readSnapshot(Integer size) {
        while (pending.size() > size) pending.remove(pending.size() - 1);
    }

    @Override
    protected void onFinalCommit() {
        List<ItemStack> done = new ArrayList<>(pending);
        pending.clear();
        for (ItemStack stack : done) taken.accept(stack);
    }
}
