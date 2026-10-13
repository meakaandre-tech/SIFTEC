package com.meakaandre.siftec.building;

import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A building's controller: which building it is, where and how it was placed, the company that owns it, and what
 * dismantling gives back (the build cost that was paid). Once a second it checks that every block of the building
 * is still there; if one has gone anyway (a command, another mod), the whole building comes down and the refund
 * and contents drop where the controller stood.
 * <p>
 * It also runs the building ({@link BuildingLogic}): its recipe filter, the pace its machines keep, the node it
 * stands on, and the input and output slots its ports reach when the machines inside have none of their own (a
 * miner's ore, the Smelter's ore and ingots).
 */
public class ControllerBlockEntity extends BlockEntity {
    public String buildingId = "";
    public @Nullable BlockPos anchor;
    public Direction facing = Direction.NORTH;
    public boolean flip;
    public String companyId = "";
    public final List<ItemStack> refund = new ArrayList<>();
    /** The recipe filter: what the building makes (empty: anything its machines can). */
    public ItemStack filter = ItemStack.EMPTY;
    /** A node building's node. */
    public @Nullable BlockPos node;
    /** The game time before which the machines may not start their next recipe (Satisfactory's time per recipe). */
    public long nextCraft;
    /** How many recipes the building has started (or items a miner has mined), for the status line and the selftests. */
    public long made;
    /** A miner's progress toward its next item, in items a minute times ticks. */
    public float progress;
    /** What a status line says is wrong right now ("" if nothing), as a lang key. */
    public String problem = "";
    public final SimpleContainer input = slots(), output = slots();
    /** Set while the building is taken down on purpose, so the controller's own removal does not count as breaking. */
    boolean removing;
    private int ticks;
    private boolean indexed;
    private @Nullable Storage<ItemVariant> storage;

    public ControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private SimpleContainer slots() {
        return new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                ControllerBlockEntity.this.setChanged();
            }
        };
    }

    public @Nullable BuildingDef def() {
        return Buildings.def(buildingId);
    }

    public @Nullable Placement placement() {
        BuildingDef def = Buildings.def(buildingId);
        if (def == null || anchor == null) return null;
        return new Placement(def, anchor, facing, flip);
    }

    /**
     * What the ports see when they point at the controller: its input slot takes what the building accepts, its
     * output slot gives what it has made.
     */
    public Storage<ItemVariant> storage() {
        if (storage == null) {
            Storage<ItemVariant> in = new FilteringStorage<>(ContainerStorage.of(input, null)) {
                @Override
                protected boolean canInsert(ItemVariant resource) {
                    return BuildingLogic.accepts(ControllerBlockEntity.this, resource.toStack());
                }

                @Override
                protected boolean canExtract(ItemVariant resource) {
                    return false;
                }
            };
            storage = new CombinedStorage<>(List.of(in, FilteringStorage.extractOnlyOf(ContainerStorage.of(output, null))));
        }
        return storage;
    }

    /** Empties the input and output slots and returns what they held (dismantling gives it back). */
    public List<ItemStack> takeContents() {
        List<ItemStack> out = new ArrayList<>();
        for (SimpleContainer c : new SimpleContainer[]{input, output}) {
            ItemStack stack = c.removeItemNoUpdate(0);
            if (!stack.isEmpty()) out.add(stack);
        }
        setChanged();
        return out;
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel server) || removing) return;
        if (!indexed) {
            indexed = true;
            Buildings.reindex(server, this);
        }
        BuildingLogic.tick(server, this);
        if (++ticks % 20 != 0) return;
        BlockPos missing = Buildings.missingMember(server, this);
        if (missing != null) Buildings.collapse(server, this, missing);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        // the controller itself went without the Build Gun: the rest of the building follows next tick
        if (!removing && level instanceof ServerLevel server) Buildings.controllerGone(server, this);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Building", buildingId);
        if (anchor != null) view.store("Anchor", BlockPos.CODEC, anchor);
        view.putInt("Facing", facing.get2DDataValue());
        view.putBoolean("Flip", flip);
        view.putString("Company", companyId);
        view.store("Refund", ItemStack.OPTIONAL_CODEC.listOf(), new ArrayList<>(refund));
        view.store("Filter", ItemStack.OPTIONAL_CODEC, filter);
        if (node != null) view.store("Node", BlockPos.CODEC, node);
        view.putLong("NextCraft", nextCraft);
        view.putLong("Made", made);
        view.putFloat("Progress", progress);
        view.store("Input", ItemStack.OPTIONAL_CODEC, input.getItem(0));
        view.store("Output", ItemStack.OPTIONAL_CODEC, output.getItem(0));
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        buildingId = view.getStringOr("Building", "");
        anchor = view.read("Anchor", BlockPos.CODEC).orElse(null);
        facing = Direction.from2DDataValue(view.getIntOr("Facing", 2));
        flip = view.getBooleanOr("Flip", false);
        companyId = view.getStringOr("Company", "");
        refund.clear();
        for (ItemStack stack : view.read("Refund", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of())) if (!stack.isEmpty()) refund.add(stack);
        filter = view.read("Filter", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        node = view.read("Node", BlockPos.CODEC).orElse(null);
        nextCraft = view.getLongOr("NextCraft", 0L);
        made = view.getLongOr("Made", 0L);
        progress = view.getFloatOr("Progress", 0f);
        input.setItem(0, view.read("Input", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        output.setItem(0, view.read("Output", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
