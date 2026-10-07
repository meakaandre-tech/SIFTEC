package com.meakaandre.siftec.sink;

import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Remembers the Sink's company and lets belts, funnels and hoppers feed it. */
public class SinkBlockEntity extends BlockEntity {
    public String companyId = "";
    public final com.meakaandre.siftec.util.IntakeStorage intake = new com.meakaandre.siftec.util.IntakeStorage(
        stack -> accepts(stack) ? Long.MAX_VALUE : 0, this::take);

    public SinkBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Everything except Toxic Residue, which has to be dealt with properly. */
    public static boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && !stack.is(ModItems.TOXIC_RESIDUE.get());
    }

    /** Points for a whole stack. Items the table does not list are worth one each. */
    public static long worth(ItemStack stack) {
        JsonObject table = Milestones.raw().getAsJsonObject("sink_points");
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return (long) stack.getCount() * (table.has(id) ? table.get(id).getAsInt() : 1);
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Company", companyId);
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        companyId = view.getStringOr("Company", "");
    }

    private void take(ItemStack stack) {
        if (level == null || level.getServer() == null) return;
        Company company = CompanyData.get(level.getServer()).byId(companyId);
        if (company == null) return;
        company.points += worth(stack);
        Companies.save(level.getServer());
    }
}
