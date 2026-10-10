package com.meakaandre.siftec.mam;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.util.IntakeStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Lets belts, funnels and chutes deliver research parts to a MAM: they go to the node the company picked for
 * automatic delivery (a right-click in the MAM window), and its research starts once it is paid for and the MAM is free.
 */
public class MamBlockEntity extends BlockEntity {
    public final IntakeStorage intake = new IntakeStorage(this::wanted, this::accept);

    public MamBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The picked node, if parts may still go to it: not done, not running, and nothing it needs is missing. */
    private @Nullable Milestone picked(@Nullable Company company) {
        if (company == null || company.mamPick == null || company.mamPick.isEmpty()) return null;
        Milestone m = Milestones.get(company.mamPick);
        if (m == null || company.has(m.id()) || company.research.equals(m.id()) || Milestones.blocker(company, m) != null) return null;
        return m;
    }

    private long wanted(ItemStack stack) {
        Company company = Ownership.of(this);
        Milestone m = picked(company);
        if (m == null) return 0;
        for (Milestone.Cost cost : m.cost()) {
            if (cost.present() && cost.matches(stack)) return Math.max(0, company.needed(m, cost));
        }
        return 0;
    }

    private void accept(ItemStack stack) {
        if (level == null || level.getServer() == null) return;
        Company company = Ownership.of(this);
        Milestone m = picked(company);
        if (m == null) return;
        for (Milestone.Cost cost : m.cost()) {
            if (!cost.present() || !cost.matches(stack)) continue;
            company.pay(m, cost, Math.min(stack.getCount(), company.needed(m, cost)));
            break;
        }
        start(level.getServer(), company, m);
        Companies.save(level.getServer());
    }

    /** Starts the picked node's research once it is paid for and nothing else is running. */
    private void start(MinecraftServer server, Company company, Milestone m) {
        if (!company.fullyPaid(m) || !company.research.isEmpty()) return;
        company.research = m.id();
        company.researchEnd = server.overworld().getGameTime() + m.seconds() * 20L;
        Companies.tell(server, company, Component.translatable("siftec.mam.started", m.name()).withStyle(ChatFormatting.GOLD));
        Companies.save(server);
    }

    public void serverTick() {
        if (level == null || level.getServer() == null || level.getGameTime() % 20 != 0) return;
        Company company = Ownership.of(this);
        Milestone m = picked(company);
        if (m != null) start(level.getServer(), company, m);
    }
}
