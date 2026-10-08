package com.meakaandre.siftec.claim;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** Claims the 3 by 3 chunks around it for the company of whoever places it. */
public class ClaimMarkerBlock extends Block {
    public ClaimMarkerBlock(Properties properties) {
        // a marker holds land: it cannot be blown up or pushed, or its claim would be left behind
        super(properties.explosionResistance(3600000.0F).pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel server) || !(placer instanceof ServerPlayer player)) return;
        Company company = Companies.of(player);
        Component problem = null;
        int used = Claims.markersUsed(server.getServer(), company), budget = Claims.markerBudget(company);
        int room = Claims.chunkBudget(company) - Claims.chunksUsed(server.getServer(), company);
        if (company.id.isEmpty()) problem = Component.translatable("siftec.claim.nothing");
        else if (used >= budget || room <= 0) problem = Component.translatable("siftec.claim.budget", budget);
        else if (Claims.inSpawnZone(server, pos)) problem = Component.translatableWithFallback("siftec.claim.spawn",
            "Nothing can be claimed within %s blocks of spawn", com.meakaandre.siftec.config.SiftecConfig.spawnFreeRadius);
        else if (Claims.nearForeignHub(server, company, pos)) problem = Component.translatable("siftec.claim.too_close", Claims.HUB_SPACING);
        else if (Claims.claim(server, company, pos, Claims.MARKER_RADIUS, true, room) == 0) problem = Component.translatable("siftec.claim.nothing");
        if (problem != null) {
            // hand the marker back
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            if (!player.hasInfiniteMaterials()) player.getInventory().placeItemBackInInventory(new ItemStack(this), Prediction.SERVER_ONLY);
            player.sendOverlayMessage(problem);
            return;
        }
        player.sendOverlayMessage(Component.translatable("siftec.claim.claimed", used + 1, budget));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) {
            Company company = Companies.of(server);
            server.sendOverlayMessage(Component.translatable("siftec.claim.claimed", Claims.markersUsed(server.level().getServer(), company), Claims.markerBudget(company)));
        }
        return InteractionResult.SUCCESS;
    }
}
