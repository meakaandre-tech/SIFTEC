package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.PowerRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

/** A vehicle's storage window, and its tank filled from a bucket of fuel. */
final class VehicleCargo {
    /** mB in a bucket, as a fuel's megajoules are given per mB. */
    private static final int MB_PER_BUCKET = 1000;

    private VehicleCargo() {
    }

    static void openCargo(VehicleEntity vehicle, ServerPlayer player) {
        MenuType<ChestMenu> type = switch (vehicle.kind.rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ChestMenu(type, id, inventory, vehicle.cargo,
            vehicle.kind.rows) {
            @Override
            public boolean stillValid(Player who) {
                return vehicle.mayKeepCargoOpen(who);
            }
        }, vehicle.getName()));
    }

    /** A bucket of a fuel the Fuel Generator burns goes into the tank, and the empty bucket comes back. */
    static boolean refuel(VehicleEntity vehicle, ServerPlayer player, InteractionHand hand, ItemStack held) {
        if (held.isEmpty()) return false;
        for (String id : BuildingPower.fluidFuels(PowerRole.FUEL)) {
            Fluid fluid = BuiltInRegistries.FLUID.getValue(Identifier.parse(id));
            if (fluid.getBucket() == Items.AIR || !held.is(fluid.getBucket())) continue;
            double mj = BuildingPower.fluidMj(PowerRole.FUEL, fluid) * MB_PER_BUCKET;
            if (vehicle.fuelMj + mj > vehicle.kind.tankMj() + 1) {
                player.sendOverlayMessage(Component.translatable("siftec.vehicle.full", vehicle.getName()));
                return true;
            }
            vehicle.addFuel(mj);
            if (!player.hasInfiniteMaterials())
                player.setItemInHand(hand, ItemUtils.createFilledResult(held, player, new ItemStack(Items.BUCKET)));
            player.sendOverlayMessage(Component.translatable("siftec.vehicle.fuelled", vehicle.getName(),
                vehicle.fuelPercent()));
            return true;
        }
        return false;
    }
}
