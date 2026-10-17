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
    /**
     * Satisfactory 1.0's Dimensional Depot before research: 15 items a minute, and one stack of each item in the cloud.
     * The Upload Upgrade research raises the rate (30, 60, 120, 240 a minute) and the Depot Expansion research the stacks
     * (2 to 5): tokens depot:upload:<items a minute> and depot:stacks:<n>.
     */
    public static final int UPLOAD_PER_MINUTE = 15, CLOUD_STACKS = 1;
    public String companyId = "";
    private int cooldown;
    /** Uploads owed but not yet whole items, in sixtieths of an item: a depot at 15 a minute moves one every 4 seconds. */
    private int owed;
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

    /** Items a minute each of a company's depots uploads: raised by the Upload Upgrade research. */
    public static int uploadPerMinute(Company company) {
        return company.best("depot:upload:", UPLOAD_PER_MINUTE);
    }

    /** How many stacks of one item a company's cloud holds: raised by the Depot Expansion research. */
    public static int cloudStacks(Company company) {
        return company.best("depot:stacks:", CLOUD_STACKS);
    }

    /** The most of one item a company's cloud holds: its stacks of that item. */
    public static int cloudLimit(Company company, net.minecraft.world.item.Item item) {
        return cloudStacks(company) * new net.minecraft.world.item.ItemStack(item).getMaxStackSize();
    }

    /** True once the company has the Manual Depot Uploader research: its members upload from their inventory. */
    public static boolean manual(Company company) {
        return company.hasToken("depot:manual");
    }

    /**
     * Puts as much of a stack into the company's cloud as it has room for, and says how many went. Only plain items go
     * to the cloud: nothing named, enchanted, damaged or filled.
     */
    public static int upload(Company company, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty() || !stack.getComponentsPatch().isEmpty()) return 0;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int room = cloudLimit(company, stack.getItem()) - company.cloud.getOrDefault(id, 0);
        int n = Math.min(room, stack.getCount());
        if (n <= 0) return 0;
        company.cloud.merge(id, n, Integer::sum);
        stack.shrink(n);
        return n;
    }

    public void serverTick() {
        if (++cooldown < 20 || level == null) return;
        cooldown = 0;
        Company company = CompanyData.get(level.getServer()).byId(companyId);
        if (company == null) return;
        moved = false;
        // this second's share of the rate, kept in sixtieths so slow rates add up to whole items
        owed = Math.min(owed + uploadPerMinute(company), 60 * 64);
        int budget = owed / 60;
        if (budget <= 0) return;
        int spent = 0;
        for (Direction side : Direction.values()) {
            BlockPos next = worldPosition.relative(side);
            // a depot uploads from the Item Vault it sits on, nothing else
            if (!level.getBlockState(next).is(com.zurrtum.create.AllBlocks.ITEM_VAULT)) continue;
            // only from containers the depot's company may reach: unclaimed land or its own claim
            com.meakaandre.siftec.claim.Claims.Claim claim = com.meakaandre.siftec.claim.Claims.at(level, next);
            if (claim != null && !Companies.owns(company, claim.company)) continue;
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
                    int room = cloudLimit(company, what.getItem()) - company.cloud.getOrDefault(id, 0);
                    if (room <= 0) continue;
                    int moved = (int) view.extract(what, Math.min(budget, room), tx);
                    if (moved <= 0) continue;
                    company.cloud.merge(id, moved, Integer::sum);
                    budget -= moved;
                    spent += moved;
                    this.moved = true;
                }
                tx.commit();
            }
            if (budget <= 0) break;
        }
        // what was moved is paid off; a depot with nothing to move does not save up a burst for later
        owed = spent > 0 ? owed - spent * 60 : owed % 60;
        if (moved) Companies.save(level.getServer());
    }
}
