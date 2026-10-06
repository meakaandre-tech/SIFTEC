package com.meakaandre.siftec.drone;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.place.Places;
import com.meakaandre.siftec.registry.ModBlockEntities;
import com.meakaandre.siftec.registry.ModItems;
import com.zurrtum.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Drone Port: holds one Cardboard Drone, which carries Create packages to another of the company's ports.
 * Click it with a drone, fire charges or a package to load it; click it empty-handed to choose where it flies.
 */
public class DronePortBlock extends Block implements EntityBlock {
    public DronePortBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DronePortBlockEntity(ModBlockEntities.DRONE_PORT.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : (l, p, s, be) -> {
            if (be instanceof DronePortBlockEntity port) port.serverTick();
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof ServerPlayer player && level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof DronePortBlockEntity port) {
            port.companyId = Companies.of(player).id;
            port.setChanged();
            Component custom = stack.get(DataComponents.CUSTOM_NAME);
            Places.add(server, pos, Places.DRONE_PORT, port.companyId, custom != null ? custom.getString() : "Port " + pos.getX() + ", " + pos.getZ());
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        boolean drone = stack.is(ModItems.CARDBOARD_DRONE.get()), fuel = DronePortBlockEntity.isFuel(stack), parcel = PackageItem.isPackage(stack);
        if (!drone && !fuel && !parcel) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(player instanceof ServerPlayer server) || !(level.getBlockEntity(pos) instanceof DronePortBlockEntity port)) return InteractionResult.SUCCESS;
        if (drone) {
            if (port.hasDrone) {
                server.sendOverlayMessage(Component.translatable("siftec.drone.has_drone"));
            } else {
                port.hasDrone = true;
                port.setChanged();
                stack.shrink(1);
                server.sendOverlayMessage(Component.translatable(port.statusKey(), port.chargesNeeded()));
            }
            return InteractionResult.SUCCESS;
        }
        ItemStack left = fuel ? port.items.add(stack, PortContainer.FUEL, PortContainer.FUEL + 1) : port.items.add(stack, PortContainer.OUT, PortContainer.FUEL);
        stack.setCount(left.getCount());
        server.sendOverlayMessage(Component.translatable(port.statusKey(), port.chargesNeeded()));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof DronePortBlockEntity port) {
            if (port.companyId.isEmpty()) {
                // placed by a machine: whoever uses it first takes it on
                port.companyId = Companies.of(server).id;
                port.setChanged();
                Places.add(server.level(), pos, Places.DRONE_PORT, port.companyId, "Port " + pos.getX() + ", " + pos.getZ());
            }
            if (!port.companyId.equals(Companies.of(server).id)) server.sendOverlayMessage(Component.translatable("siftec.place.not_yours"));
            else DroneMenu.open(server, port);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof DronePortBlockEntity port) {
            for (ItemStack stack : port.contents()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            if (port.hasDrone) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(ModItems.CARDBOARD_DRONE.get()));
            port.items.clearContent();
            port.hasDrone = false;
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
