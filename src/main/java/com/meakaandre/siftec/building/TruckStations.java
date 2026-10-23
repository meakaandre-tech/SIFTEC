package com.meakaandre.siftec.building;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.util.Chunks;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Truck Station: a building with two Item Vaults and a fuel tank. Its dock is the ground just in
 * front of it ({@link #dock}); a vehicle driving a recorded route stops there, and while it stands there the station
 * either loads it from its vaults or unloads it into them (using the terminal switches which) and fills its tank
 * from the station's fuel. Stations say they exist every tick their controller runs, so vehicles can find the ones
 * near them without searching the world.
 */
public final class TruckStations {
    /** How close (blocks, level) a vehicle has to come to a station's dock to count as at it. */
    public static final double DOCK_RANGE = 3.5;

    private static final Map<ResourceKey<Level>, Set<BlockPos>> KNOWN = new HashMap<>();

    private TruckStations() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> KNOWN.clear());
    }

    private static Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id));
    }

    /** The controller's tick: the station is listed for the vehicles. */
    static void tick(ServerLevel level, ControllerBlockEntity c) {
        KNOWN.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(c.getBlockPos().immutable());
    }

    /** The station whose controller stands here, or null if there is none (any more). */
    public static @Nullable ControllerBlockEntity at(Level level, BlockPos pos) {
        if (!(Chunks.blockEntity(level, pos) instanceof ControllerBlockEntity c)) return null;
        BuildingDef def = c.def();
        return def != null && def.logic() == Logic.TRUCK_STATION ? c : null;
    }

    /** Where a vehicle stops at the station: three blocks in front of the middle of its front edge, on the ground. */
    public static @Nullable Vec3 dock(ControllerBlockEntity c) {
        Placement p = c.placement();
        if (p == null) return null;
        BlockPos at = p.at(0, 0, -3);
        return new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    /**
     * True if the station serves this vehicle: a station serves only its own company's vehicles (one folded into another
     * company counts as that company's), and one nobody owns serves nobody's.
     */
    public static boolean serves(ControllerBlockEntity c, VehicleEntity vehicle) {
        if (c.companyId.isEmpty() || vehicle.companyId.isEmpty()) return c.companyId.isEmpty() && vehicle.companyId.isEmpty();
        return Companies.same(c.companyId, vehicle.companyId);
    }

    /** The station of the vehicle's company whose dock is within {@link #DOCK_RANGE} of a spot, nearest first; null if none. */
    public static @Nullable BlockPos near(ServerLevel level, Vec3 spot, VehicleEntity vehicle) {
        Set<BlockPos> known = KNOWN.get(level.dimension());
        if (known == null) return null;
        BlockPos best = null;
        double bestDist = DOCK_RANGE * DOCK_RANGE;
        List<BlockPos> gone = new ArrayList<>();
        for (BlockPos pos : known) {
            if (!level.isLoaded(pos)) continue;
            ControllerBlockEntity c = at(level, pos);
            Vec3 dock = c == null ? null : dock(c);
            if (dock == null) {
                gone.add(pos);
                continue;
            }
            if (Math.abs(dock.y - spot.y) > 2 || !serves(c, vehicle)) continue;
            double dx = dock.x - spot.x, dz = dock.z - spot.z, d = dx * dx + dz * dz;
            if (d <= bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        known.removeAll(gone);
        return best;
    }

    private static List<Storage<ItemVariant>> vaults(ServerLevel level, ControllerBlockEntity c) {
        List<Storage<ItemVariant>> out = new ArrayList<>();
        Set<Storage<ItemVariant>> seen = new HashSet<>();
        for (BlockPos pos : BuildingLogic.cells(c, create("item_vault"))) {
            Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
            if (s != null && seen.add(s)) out.add(s);
        }
        return out;
    }

    /**
     * A vehicle has stopped at the station's dock: moves its cargo (into the vaults when unloading, out of them when
     * loading) and fills its tank from the station's fuel. Returns how many items moved.
     */
    public static long dock(ServerLevel level, BlockPos controller, VehicleEntity vehicle) {
        ControllerBlockEntity c = at(level, controller);
        if (c == null || !serves(c, vehicle)) return 0;
        long moved = 0;
        Storage<ItemVariant> cargo = ContainerStorage.of(vehicle.cargo, null);
        for (Storage<ItemVariant> vault : vaults(level, c)) {
            try (Transaction tx = Transaction.openOuter()) {
                moved += c.unload ? StorageUtil.move(cargo, vault, v -> true, Long.MAX_VALUE, tx)
                    : StorageUtil.move(vault, cargo, v -> true, Long.MAX_VALUE, tx);
                tx.commit();
            }
        }
        refuel(level, c, vehicle);
        c.made++;
        c.setChanged();
        vehicle.cargo.setChanged();
        return moved;
    }

    /** Fills a vehicle's tank from the station's fuel tank, with whichever fuel it holds. */
    static void refuel(ServerLevel level, ControllerBlockEntity c, VehicleEntity vehicle) {
        BlockPos tank = BuildingLogic.cell(c, create("fluid_tank"));
        if (tank == null) return;
        Fluid fluid = ProductionLogic.fluidAt(level, tank);
        double perMb = BuildingPower.fluidMj(PowerRole.FUEL, fluid);
        if (perMb <= 0) return;
        long room = (long) Math.floor((vehicle.kind.tankMj() - vehicle.fuelMj) / perMb);
        if (room <= 0) return;
        long took = ProductionLogic.drain(level, tank, fluid, room);
        vehicle.addFuel(took * perMb);
    }

    /** Using the terminal switches between loading and unloading; only a member of the station's company may. */
    static void use(ServerPlayer player, ControllerBlockEntity c, BuildingDef def) {
        if (!SpecialBuildings.mayOperate(player, c)) {
            player.sendOverlayMessage(Component.translatable("siftec.build_gun.not_yours"));
            return;
        }
        c.unload = !c.unload;
        c.setChanged();
        player.sendOverlayMessage(Component.translatable("siftec.building.station_mode", def.name(),
            Component.translatable(c.unload ? "siftec.building.station_unload" : "siftec.building.station_load")));
    }

    /** The status line: its mode, its fuel and how full its storage is. */
    static void status(Level level, ControllerBlockEntity c, List<Component> parts) {
        parts.add(Component.translatable(c.unload ? "siftec.building.station_unload" : "siftec.building.station_load"));
        BlockPos tank = BuildingLogic.cell(c, create("fluid_tank"));
        if (tank != null) {
            Fluid fluid = ProductionLogic.fluidAt(level, tank);
            long mb = ProductionLogic.mbAt(level, tank, fluid);
            parts.add(Component.translatable("siftec.building.station_fuel", mb / ExtractorLogic.mbPerM3()));
        }
    }
}
