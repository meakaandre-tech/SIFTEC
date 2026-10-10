package com.meakaandre.siftec.workshop;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.util.IntakeStorage;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Workshop's automatic side. Its owner picks one build in the window (right-click); belts, funnels, chutes
 * and hoppers then feed it that build's parts, which it keeps until it has the whole cost, and it builds into an
 * output slot that funnels and hoppers take from. It only builds what its company has unlocked.
 */
public class WorkshopBlockEntity extends BlockEntity {
    private static final Codec<Map<Identifier, Integer>> HELD_CODEC = Codec.unboundedMap(Identifier.CODEC, Codec.INT);

    private @Nullable Identifier target;
    /** The parts taken in for the next build, by item; never more than one build's worth. */
    private final Map<Item, Integer> held = new LinkedHashMap<>();
    public final SimpleContainer output = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            WorkshopBlockEntity.this.setChanged();
        }
    };
    public final IntakeStorage intake = new IntakeStorage(this::wanted, this::accept);
    private final Storage<ItemVariant> storage = new CombinedStorage<>(List.of(intake, FilteringStorage.extractOnlyOf(ContainerStorage.of(output, null))));

    public WorkshopBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** What belts, funnels and hoppers see: the intake for parts, and the output to take from. */
    public Storage<ItemVariant> storage() {
        return storage;
    }

    public @Nullable Identifier target() {
        return target;
    }

    private @Nullable Milestones.Build build() {
        if (target == null) return null;
        for (Milestones.Build build : Milestones.workshop()) if (build.item().equals(target)) return build;
        return null;
    }

    /** True if the company this Workshop belongs to may build the item. */
    public boolean unlocked(Identifier item) {
        Item result = BuiltInRegistries.ITEM.getOptional(item).orElse(Items.AIR);
        if (result == Items.AIR) return false;
        Milestone lock = Locks.lockOf(result);
        if (lock == null) return true;
        Company company = Ownership.of(this);
        return company != null && company.has(lock.id());
    }

    /** How many of this cost's items are already held. Each held item counts toward the first cost it matches. */
    private int paid(Milestones.Build build, Milestone.Cost wanted) {
        Map<Item, Integer> left = new LinkedHashMap<>(held);
        for (Milestone.Cost cost : build.cost()) {
            int n = 0;
            for (Map.Entry<Item, Integer> e : left.entrySet()) {
                if (!cost.matches(new ItemStack(e.getKey()))) continue;
                int take = Math.min(e.getValue(), cost.count() - n);
                n += take;
                e.setValue(e.getValue() - take);
            }
            if (cost == wanted) return n;
        }
        return 0;
    }

    private long wanted(ItemStack stack) {
        Milestones.Build build = build();
        if (build == null || !unlocked(build.item())) return 0;
        for (Milestone.Cost cost : build.cost()) {
            if (cost.present() && cost.matches(stack)) return Math.max(0, cost.count() - paid(build, cost));
        }
        return 0;
    }

    private void accept(ItemStack stack) {
        held.merge(stack.getItem(), stack.getCount(), Integer::sum);
        setChanged();
        tryBuild();
    }

    /** True once every part of the picked build is in. */
    public boolean complete() {
        Milestones.Build build = build();
        if (build == null) return false;
        for (Milestone.Cost cost : build.cost()) if (cost.present() && paid(build, cost) < cost.count()) return false;
        return true;
    }

    /** Builds if every part is in, the company has it unlocked and the output has room. */
    public void tryBuild() {
        Milestones.Build build = build();
        if (build == null || !complete() || !unlocked(build.item())) return;
        ItemStack made = new ItemStack(BuiltInRegistries.ITEM.getValue(build.item()));
        ItemStack out = output.getItem(0);
        if (!out.isEmpty() && (!ItemStack.isSameItemSameComponents(out, made) || out.getCount() >= out.getMaxStackSize())) return;
        for (Milestone.Cost cost : build.cost()) {
            if (!cost.present()) continue;
            int left = cost.count();
            for (Map.Entry<Item, Integer> e : held.entrySet()) {
                if (left <= 0) break;
                if (!cost.matches(new ItemStack(e.getKey()))) continue;
                int take = Math.min(left, e.getValue());
                e.setValue(e.getValue() - take);
                left -= take;
            }
        }
        held.values().removeIf(n -> n <= 0);
        if (out.isEmpty()) output.setItem(0, made);
        else out.grow(1);
        output.setChanged();
    }

    public void serverTick() {
        if (level != null && level.getGameTime() % 10 == 0) tryBuild();
    }

    /** Picks what to build. Parts held for the old build go back to the player. */
    public void pick(@Nullable Identifier item, @Nullable ServerPlayer player) {
        if (item != null && item.equals(target)) return;
        for (ItemStack stack : takeHeld()) {
            if (player != null) player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
            else if (level != null) Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, stack);
        }
        target = item;
        setChanged();
    }

    /** The parts held, as stacks, emptied out of the Workshop. */
    private List<ItemStack> takeHeld() {
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : held.entrySet()) {
            int n = e.getValue();
            while (n > 0) {
                int take = Math.min(n, e.getKey().getDefaultMaxStackSize());
                out.add(new ItemStack(e.getKey(), take));
                n -= take;
            }
        }
        held.clear();
        return out;
    }

    /** How many of an item are held; for the window and the selftest. */
    public int held(Item item) {
        return held.getOrDefault(item, 0);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level == null) return;
        List<ItemStack> drops = takeHeld();
        if (!output.getItem(0).isEmpty()) drops.add(output.removeItemNoUpdate(0));
        for (ItemStack stack : drops) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        if (target != null) view.putString("Target", target.toString());
        Map<Identifier, Integer> saved = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : held.entrySet()) saved.put(BuiltInRegistries.ITEM.getKey(e.getKey()), e.getValue());
        view.store("Held", HELD_CODEC, saved);
        view.store("Output", ItemStack.OPTIONAL_CODEC, output.getItem(0));
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        String t = view.getStringOr("Target", "");
        target = t.isEmpty() ? null : Identifier.tryParse(t);
        held.clear();
        for (Map.Entry<Identifier, Integer> e : view.read("Held", HELD_CODEC).orElse(Map.of()).entrySet()) {
            Item item = BuiltInRegistries.ITEM.getOptional(e.getKey()).orElse(Items.AIR);
            if (item != Items.AIR && e.getValue() > 0) held.put(item, e.getValue());
        }
        output.setItem(0, view.read("Output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
