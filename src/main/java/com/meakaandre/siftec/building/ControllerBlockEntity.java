package com.meakaandre.siftec.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
 */
public class ControllerBlockEntity extends BlockEntity {
    public String buildingId = "";
    public @Nullable BlockPos anchor;
    public Direction facing = Direction.NORTH;
    public boolean flip;
    public String companyId = "";
    public final List<ItemStack> refund = new ArrayList<>();
    /** Set while the building is taken down on purpose, so the controller's own removal does not count as breaking. */
    boolean removing;
    private int ticks;
    private boolean indexed;

    public ControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public @Nullable Placement placement() {
        BuildingDef def = Buildings.def(buildingId);
        if (def == null || anchor == null) return null;
        return new Placement(def, anchor, facing, flip);
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel server) || removing) return;
        if (!indexed) {
            indexed = true;
            Buildings.reindex(server, this);
        }
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
    }
}
