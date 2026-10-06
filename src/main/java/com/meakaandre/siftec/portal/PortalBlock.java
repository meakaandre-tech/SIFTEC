package com.meakaandre.siftec.portal;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.place.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Main Portal and Satellite Portal: a company's members step between any two of its portals. Satellites only
 * work while the company has a Main Portal somewhere.
 */
public class PortalBlock extends Block {
    public final boolean main;

    public PortalBlock(boolean main, Properties properties) {
        super(properties);
        this.main = main;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof ServerPlayer player && level instanceof ServerLevel server) {
            Component custom = stack.get(DataComponents.CUSTOM_NAME);
            String name = custom != null ? custom.getString() : (main ? "Main Portal " : "Portal ") + pos.getX() + ", " + pos.getZ();
            Places.add(server, pos, main ? Places.MAIN_PORTAL : Places.SATELLITE_PORTAL, Companies.of(player).id, name);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player user, BlockHitResult hit) {
        if (!(user instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        Company company = Companies.of(player);
        boolean mine = false, hasMain = false;
        for (Places.Place place : Places.of(player.level().getServer(), company.id)) {
            mine |= place.portal() && Places.same(place, level, pos);
            hasMain |= place.kind.equals(Places.MAIN_PORTAL);
        }
        if (!mine) player.sendOverlayMessage(Component.translatable("siftec.place.not_yours"));
        else if (!hasMain) player.sendOverlayMessage(Component.translatable("siftec.portal.no_main"));
        else PortalMenu.open(player, company, pos);
        return InteractionResult.SUCCESS;
    }
}
