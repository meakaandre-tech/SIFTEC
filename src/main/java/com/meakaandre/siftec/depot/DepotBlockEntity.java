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
    /** Whether the last upload moved anything, so the company is only saved when it changed. */
    private boolean moved;

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

    /** Takes up to a stack of one item out of a company's cloud. Empty if there is none. */
    public static net.minecraft.world.item.ItemStack withdraw(Company company, String id) {
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(id)).orElse(net.minecraft.world.item.Items.AIR);
        int have = company.cloud.getOrDefault(id, 0);
        int take = Math.min(have, new net.minecraft.world.item.ItemStack(item).getMaxStackSize());
        if (item == net.minecraft.world.item.Items.AIR || take <= 0) return net.minecraft.world.item.ItemStack.EMPTY;
        if (have - take <= 0) company.cloud.remove(id);
        else company.cloud.put(id, have - take);
        return new net.minecraft.world.item.ItemStack(item, take);
    }

    /** Items uploaded each second for a company: doubled by the Upload Upgrade research. */
    public static int uploadPerSecond(Company company) {
        return UPLOAD_PER_SECOND * (company.hasToken("depot:upload") ? 2 : 1);
    }

    /** The most of one item a company's cloud holds: doubled by the Depot Expansion research. */
    public static int cloudLimit(Company company) {
        return CLOUD_LIMIT * (company.hasToken("depot:expansion") ? 2 : 1);
    }

    public void serverTick() {
        if (++cooldown < 20 || level == null) return;
        cooldown = 0;
        Company company = CompanyData.get(level.getServer()).byId(companyId);
        if (company == null) return;
        moved = false;
        int budget = uploadPerSecond(company);
        int limit = cloudLimit(company);
        for (Direction side : Direction.values()) {
            BlockPos next = worldPosition.relative(side);
            // a depot uploads from the Item Vault it sits on, nothing else
            if (!level.getBlockState(next).is(com.zurrtum.create.AllBlocks.ITEM_VAULT)) continue;
            // only from containers the depot's company may reach: unclaimed land or its own claim
            com.meakaandre.siftec.claim.Claims.Claim claim = com.meakaandre.siftec.claim.Claims.at(level, next);
            if (claim != null && !claim.company.equals(company.id)) continue;
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
                    this.moved = true;
                }
                tx.commit();
            }
            if (budget <= 0) break;
        }
        if (moved) Companies.save(level.getServer());
    }
}
