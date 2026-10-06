package com.meakaandre.siftec.depot;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class DepotBlockEntity extends BlockEntity {
    /** Items uploaded per second, and the most of one item the cloud holds, before research. */
    public static final int UPLOAD_PER_SECOND = 8, CLOUD_LIMIT = 320;
    public String companyId = "";
    private int cooldown;

    public DepotBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
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

    public void serverTick() {
        if (++cooldown < 20 || level == null) return;
        cooldown = 0;
        Company company = CompanyData.get(level.getServer()).byId(companyId);
        if (company == null) return;
        int budget = UPLOAD_PER_SECOND * (company.hasToken("depot:upload") ? 2 : 1);
        int limit = CLOUD_LIMIT * (company.hasToken("depot:expansion") ? 2 : 1);
        for (Direction side : Direction.values()) {
            BlockPos next = worldPosition.relative(side);
            if (level.getBlockEntity(next) instanceof DepotBlockEntity) continue;
            Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, next, side.getOpposite());
            if (storage == null || !storage.supportsExtraction()) continue;
            try (Transaction tx = Transaction.openOuter()) {
                for (StorageView<ItemVariant> view : storage) {
                    if (budget <= 0) break;
                    if (view.isResourceBlank()) continue;
                    ItemVariant what = view.getResource();
                    // only plain items go to the cloud: nothing named, enchanted, damaged or filled
                    if (!what.getComponentsPatch().isEmpty()) continue;
                    String id = BuiltInRegistries.ITEM.getKey(what.getItem()).toString();
                    int room = limit - company.cloud.getOrDefault(id, 0);
                    if (room <= 0) continue;
                    int moved = (int) view.extract(what, Math.min(budget, room), tx);
                    if (moved <= 0) continue;
                    company.cloud.merge(id, moved, Integer::sum);
                    budget -= moved;
                }
                tx.commit();
            }
            if (budget <= 0) break;
        }
        Companies.save(level.getServer());
    }
}
