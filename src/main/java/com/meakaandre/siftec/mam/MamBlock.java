package com.meakaandre.siftec.mam;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The MAM: the research block. It belongs to the company of whoever placed it, and only that company's members use it,
 * once the company has done Field Research (the milestone that unlocks the MAM, as in Satisfactory). A MAM nobody owns
 * (placed by a machine, or from before MAMs had owners) is taken on by the first company that may use it.
 */
public class MamBlock extends Block implements EntityBlock {
    /** The milestone a company needs before its MAM does anything. */
    public static final String FIELD_RESEARCH = "field_research";

    public MamBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MamBlockEntity(ModBlockEntities.MAM.get(), pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof MamBlockEntity mam) {
            mam.companyId = Companies.of(player).id;
            mam.setChanged();
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) {
            Component refused = use(server, pos);
            if (refused != null) server.sendOverlayMessage(refused);
        }
        return InteractionResult.SUCCESS;
    }

    /** Opens the MAM at {@code pos} for the player, or says why not (null when it opened). */
    public static @Nullable Component use(ServerPlayer player, BlockPos pos) {
        Component refused = refusal(player, pos);
        if (refused != null) return refused;
        Company company = Companies.of(player);
        if (player.level().getBlockEntity(pos) instanceof MamBlockEntity mam && mam.companyId.isEmpty()) {
            mam.companyId = company.id;
            mam.setChanged();
        }
        MamMenu.open(player, company, pos);
        return null;
    }

    /**
     * Why this player may not use the MAM at {@code pos}, or null if they may: it belongs to another company, or their
     * company has not done Field Research. Creative players may always use it.
     */
    public static @Nullable Component refusal(ServerPlayer player, BlockPos pos) {
        if (player.hasInfiniteMaterials()) return null;
        Company mine = Companies.of(player);
        String owner = player.level().getBlockEntity(pos) instanceof MamBlockEntity mam ? mam.companyId : "";
        if (!owner.isEmpty() && !Companies.owns(mine, owner)) {
            Company holder = Companies.byId(player.level().getServer(), owner);
            return Component.translatable("siftec.hub.not_yours", holder == null ? "?" : holder.name);
        }
        if (!mine.has(FIELD_RESEARCH)) {
            Milestone field = Milestones.get(FIELD_RESEARCH);
            return Component.translatable("siftec.lock.item", field == null ? Component.literal("Field Research") : field.name());
        }
        return null;
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(Facing.FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return Facing.place(super.getStateForPlacement(context), context);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return Facing.rotate(state, rotation);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return Facing.mirror(state, mirror);
    }
}
