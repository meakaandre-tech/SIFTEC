package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.owner.RecipeLocks;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/** Used by the automated test (/siftec selftest content): fuels, item and recipe locks, and the shared Crafter cache. */
final class ContentSelfTest {
    private ContentSelfTest() {
    }

    static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        StringBuilder fuels = new StringBuilder();
        for (String id : new String[]{"minecraft:coal", "siftec:biomass", "siftec:solid_biofuel", "siftec:compacted_coal", "siftec:petroleum_coke"}) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
            fuels.append(' ').append(id).append('=').append(com.zurrtum.create.foundation.utility.FuelUtil.burnDuration(level, stack));
        }
        report(source, "SELFTEST content fuels:" + fuels);
        StringBuilder locks = new StringBuilder();
        for (String id : new String[]{"cgs:flintlock", "cgs:revolver", "create:cut_granite", "create:deployer", "create:mechanical_crafter", "create:brass_ingot",
            "createdieselgenerators:pumpjack_crank", "farmersdelight:wheat_dough", "create:shaft"}) {
            Milestone lock = Locks.lockOf(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
            locks.append(' ').append(id).append('=').append(lock == null ? "free" : lock.id());
        }
        report(source, "SELFTEST content item locks:" + locks);
        StringBuilder recipes = new StringBuilder();
        for (String id : new String[]{"siftec:mixing/gunpowder_with_coal", "siftec:mixing/lava_from_magma_block", "siftec:mixing/sulfuric_acid",
            "siftec:crafting/deployer", "siftec:compacting/steel_beam"}) {
            var holder = source.getServer().getRecipeManager().getRecipes().stream().filter(h -> h.id().identifier().toString().equals(id)).findFirst();
            recipes.append(' ').append(id).append('=').append(holder.isEmpty() ? "missing" : RecipeLocks.lockOf(source.getServer(), holder.get().value()));
        }
        report(source, "SELFTEST content recipe locks:" + recipes);
        crafterCache(source, level);
        return 1;
    }

    /**
     * Two Crafters, one belonging to a company with nothing done and one to a company with everything done, ask
     * for the same locked recipe (the Deployer). The shared cache must not hand the first one's "nothing" to the second.
     */
    private static void crafterCache(CommandSourceStack source, ServerLevel level) {
        CompanyData data = CompanyData.get(source.getServer());
        Company none = new Company(), all = new Company();
        none.id = "selftest_nothing";
        all.id = "selftest_everything";
        for (Milestone m : Milestones.all()) all.done.add(m.id());
        data.companies().put(none.id, none);
        data.companies().put(all.id, all);
        BlockPos at = BlockPos.containing(source.getPosition()).atY(level.getMaxY() - 8);
        level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
        BlockEntity[] crafters = new BlockEntity[2];
        for (int i = 0; i < 2; i++) {
            level.setBlockAndUpdate(at.east(i), Blocks.CRAFTER.defaultBlockState());
            crafters[i] = level.getBlockEntity(at.east(i));
            if (crafters[i] != null) crafters[i].setAttached(Ownership.OWNER, i == 0 ? none.id : all.id);
        }
        List<ItemStack> grid = new ArrayList<>();
        for (String id : new String[]{"siftec:rotor", "minecraft:air", "minecraft:air", "create:andesite_casing", "minecraft:air", "minecraft:air",
            "siftec:reinforced_iron_plate", "minecraft:air", "minecraft:air"}) {
            grid.add(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))));
        }
        CraftingInput input = CraftingInput.of(3, 3, grid);
        StringBuilder said = new StringBuilder();
        for (int i : new int[]{0, 1, 0}) {
            Ownership.ticking(crafters[i]);
            try {
                said.append(i == 0 ? " nothing done: " : " everything done: ")
                    .append(CrafterBlock.getPotentialResults(level, input).map(h -> h.id().identifier().toString()).orElse("none"));
            } finally {
                Ownership.ticking(null);
            }
        }
        report(source, "SELFTEST content crafter cache:" + said);
        for (int i = 0; i < 2; i++) level.setBlockAndUpdate(at.east(i), Blocks.AIR.defaultBlockState());
        data.companies().remove(none.id);
        data.companies().remove(all.id);
    }

    private static void report(CommandSourceStack source, String text) {
        Siftec.LOGGER.info(text);
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
