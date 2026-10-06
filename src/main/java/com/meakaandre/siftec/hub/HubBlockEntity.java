package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Remembers which company the block belongs to, and lets belts, funnels and hoppers deliver parts:
 * anything pushed in goes toward the milestone the company last clicked.
 */
public class HubBlockEntity extends BlockEntity {
    public String companyId = "";
    /** The corners of the HUB building, marked with the planner; null until it has been. */
    public net.minecraft.core.BlockPos boxMin, boxMax;
    private int builtTier = -1;
    private long builtAt = Long.MIN_VALUE;

    /** Measures the building again. Null if no box has been marked. */
    public HubBuilding.Result measure() {
        if (boxMin == null || boxMax == null || !(level instanceof net.minecraft.server.level.ServerLevel server)) return null;
        Company company = company();
        HubBuilding.Result result = HubBuilding.scan(server, worldPosition, boxMin, boxMax, company == null ? 1 : company.members.size());
        builtTier = result.builtTier();
        builtAt = level.getGameTime();
        return result;
    }

    /** The highest tier the building is good for, or -1. Measured again every ten seconds at most. */
    public int builtTier() {
        if (level != null && level.getGameTime() - builtAt > 200) {
            if (measure() == null) {
                builtTier = -1;
                builtAt = level.getGameTime();
            }
        }
        return builtTier;
    }
    public final Intake intake = new Intake();

    public HubBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Company", companyId);
        if (boxMin != null && boxMax != null) {
            view.store("BoxMin", net.minecraft.core.BlockPos.CODEC, boxMin);
            view.store("BoxMax", net.minecraft.core.BlockPos.CODEC, boxMax);
        }
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        companyId = view.getStringOr("Company", "");
        boxMin = view.read("BoxMin", net.minecraft.core.BlockPos.CODEC).orElse(null);
        boxMax = view.read("BoxMax", net.minecraft.core.BlockPos.CODEC).orElse(null);
    }

    private @Nullable Company company() {
        if (level == null || level.getServer() == null) return null;
        return CompanyData.get(level.getServer()).byId(companyId);
    }

    /** The milestone deliveries go to, if it can take anything right now. */
    private @Nullable Milestone target(Company company) {
        Milestone m = Milestones.get(company.active);
        if (m == null || company.has(m.id()) || Milestones.blocker(company, m) != null) return null;
        boolean gateway = getBlockState().getBlock() instanceof HubBlock hub && hub.gateway;
        if (m.isPhase() != gateway) return null;
        if (!m.isPhase() && company.lockUntil > level.getServer().overworld().getGameTime()) return null;
        if (!m.isPhase() && m.tier() > builtTier()) return null;
        return m;
    }

    private int wanted(ItemStack stack) {
        Company company = company();
        if (company == null) return 0;
        Milestone m = target(company);
        if (m == null) return 0;
        for (Milestone.Cost cost : m.cost()) {
            if (cost.matches(stack)) return company.needed(m, cost);
        }
        return 0;
    }

    private void accept(ItemStack stack) {
        Company company = company();
        if (company == null) return;
        Milestone m = target(company);
        if (m == null) return;
        MinecraftServer server = level.getServer();
        for (Milestone.Cost cost : m.cost()) {
            if (!cost.matches(stack)) continue;
            company.pay(m, cost, Math.min(stack.getCount(), company.needed(m, cost)));
            Companies.save(server);
            if (company.fullyPaid(m)) Companies.complete(server, company, m, Component.literal(company.name));
            return;
        }
    }

    /** A slot that swallows what is put in. It only takes parts the active milestone still needs. */
    public class Intake implements WorldlyContainer {
        private static final int[] SLOTS = {0};

        @Override
        public int[] getSlotsForFace(Direction side) {
            return SLOTS;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
            return wanted(stack) > 0;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return wanted(stack) > 0;
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
            return false;
        }

        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return Math.max(1, Math.min(stack.getMaxStackSize(), wanted(stack)));
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (!stack.isEmpty()) accept(stack);
        }

        @Override
        public void setChanged() {
            HubBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
        }
    }
}
