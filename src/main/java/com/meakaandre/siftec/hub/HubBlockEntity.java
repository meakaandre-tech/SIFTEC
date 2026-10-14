package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.prefab.Prefabs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The HUB Terminal or the Wormhole Gateway's terminal. It remembers which company it belongs to and, for a
 * structure placed whole (see {@link Prefabs}), where the structure stands, which way it faces, its legs and the
 * level of parts built. An old single-block HUB or Gateway has none of that and keeps working as a plain terminal.
 * Belts, funnels and hoppers can deliver parts: anything pushed in goes toward the milestone the company last clicked.
 */
public class HubBlockEntity extends BlockEntity {
    public String companyId = "";
    /** True for a terminal that stands in a placed structure. */
    public boolean prefab;
    public @Nullable BlockPos anchor;
    public @Nullable Direction facing;
    public int[] legs;
    public int builtLevel;
    /** What putting the HUB down cost (its 20 Raw Iron): given back when it is picked up. */
    public final java.util.List<ItemStack> refund = new java.util.ArrayList<>();
    private int clock;
    /** Ticks run, for the self-test. */
    public long ticked;

    /** Lets belts, funnels and hoppers deliver; it only takes parts the active milestone still needs. */
    public final com.meakaandre.siftec.util.IntakeStorage intake = new com.meakaandre.siftec.util.IntakeStorage(this::wanted, this::accept);

    public HubBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /**
     * Once a second: builds or takes away the structure's parts to match the company's progress. A finished Wormhole
     * Gateway also looks, twice a second, for players walking into its portal.
     */
    public void serverTick() {
        ticked++;
        if (ticked % 10 == 0) Prefabs.portal(this);
        if (++clock < 20) return;
        clock = 0;
        Prefabs.refresh(this);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Company", companyId);
        if (prefab && anchor != null && facing != null) {
            view.putBoolean("Prefab", true);
            view.store("Anchor", BlockPos.CODEC, anchor);
            view.putInt("Facing", facing.get2DDataValue());
            view.putInt("Level", builtLevel);
            if (legs != null) view.putIntArray("Legs", legs);
        }
        if (!refund.isEmpty()) view.store("Refund", ItemStack.OPTIONAL_CODEC.listOf(), new java.util.ArrayList<>(refund));
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        companyId = view.getStringOr("Company", "");
        // an old HUB's building box ("BoxMin", "BoxMax") is simply no longer read
        prefab = view.getBooleanOr("Prefab", false);
        anchor = view.read("Anchor", BlockPos.CODEC).orElse(null);
        facing = Direction.from2DDataValue(view.getIntOr("Facing", 2));
        builtLevel = view.getIntOr("Level", 0);
        legs = view.getIntArray("Legs").orElse(null);
        refund.clear();
        for (ItemStack stack : view.read("Refund", ItemStack.OPTIONAL_CODEC.listOf()).orElse(java.util.List.of())) if (!stack.isEmpty()) refund.add(stack);
        if (anchor == null) prefab = false;
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
        return m;
    }

    private long wanted(ItemStack stack) {
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

    /** True for the Wormhole Gateway's terminal, false for the HUB's. */
    public boolean gateway() {
        return getBlockState().getBlock() instanceof HubBlock hub && hub.gateway;
    }
}
