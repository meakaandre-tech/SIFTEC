package com.meakaandre.siftec.building;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.drone.DroneMenu;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.equip.Radar;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.place.Places;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.sink.ShopMenu;
import com.meakaandre.siftec.sink.Sink;
import com.meakaandre.siftec.sink.SinkMenu;
import com.meakaandre.siftec.util.Chunks;
import com.meakaandre.siftec.workshop.WorkshopMenu;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The special buildings: the Equipment Workshop, the AWESOME Sink and Shop, and the Radar Tower. Using one opens
 * its window; the Sink destroys what its input port brings while it has power, for its company's points. Also the
 * HUB's Craft Station's rule: it turns by hand only ({@link #cutsRotation}).
 */
public final class SpecialBuildings {
    private SpecialBuildings() {
    }

    /**
     * True if the block is one of the HUB's Craft Station machines (its Presses, Mixers, Basins, cogwheel and hand cranks):
     * the real Create machines of a HUB structure, listed in the building index under the HUB's terminal.
     */
    public static boolean craftStation(Level level, BlockPos pos) {
        BlockPos terminal = BuildingIndex.controllerOf(level, pos);
        // never loads a chunk: this is asked while rotation propagates
        return terminal != null && Chunks.blockEntity(level, terminal) instanceof HubBlockEntity hub
            && hub.prefab && !hub.gateway();
    }

    /**
     * The HUB's Craft Station is hand-cranked, as Satisfactory's Craft Bench is worked by hand: rotation crosses between a
     * Craft Station machine and anything outside the station in neither direction, so only its own hand cranks turn it,
     * never a shaft, motor or water wheel brought up to it (and it turns nothing outside either). For the rotation
     * propagator ({@code PropagatorMixin}); always false on the client, where the index is not kept.
     */
    public static boolean cutsRotation(KineticBlockEntity from,
                                       KineticBlockEntity to) {
        Level level = to.getLevel();
        if (level == null || level.isClientSide() || from.getLevel() != level) return false;
        return craftStation(level, from.getBlockPos()) != craftStation(level, to.getBlockPos());
    }

    /** Opens the building's window. False for any other building (it says its status instead). */
    static boolean use(ServerPlayer player, ControllerBlockEntity c, BuildingDef def) {
        Company company = Companies.of(player);
        switch (def.logic()) {
            case "workshop" -> WorkshopMenu.open(player, company, c.getBlockPos());
            case "shop" -> ShopMenu.open(player, company, c.getBlockPos());
            case "sink" -> {
                // a Sink is its company's: anyone may look, but only its members print the owner's points as coupons
                Company owner = c.companyId.isEmpty() ? company : Companies.byId(player.level().getServer(), c.companyId);
                SinkMenu.open(player, owner == null ? company : owner, c.getBlockPos(), mayOperate(player, c), () -> c.powered);
            }
            case "radar" -> Radar.use(player, c);
            case "truck_station" -> TruckStations.use(player, c, def);
            case "drone_port" -> {
                if (!mayOperate(player, c)) player.sendOverlayMessage(Component.translatable("siftec.place.not_yours"));
                else if (droneTerminal(player.level(), c) instanceof DronePortBlockEntity port) DroneMenu.open(player, port, c.getBlockPos());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The Drone Port template's terminal cell (the Drone Port block under its pad), in the Build Gun frame. */
    public static int @Nullable [] terminalCell(BuildingTemplate t) {
        for (BuildingTemplate.Cell cell : t.members) if (cell.state().is(ModBlocks.DRONE_PORT.get())) return new int[]{cell.x(), cell.y(), cell.z()};
        return null;
    }

    /** The placement that puts a Drone Port's terminal at a spot, facing one way. */
    public static Placement forTerminal(BuildingDef def, int[] terminal, BlockPos at, Direction facing) {
        Direction right = facing.getClockWise();
        BlockPos anchor = at.offset(-right.getStepX() * terminal[0] - facing.getStepX() * terminal[2], -terminal[1],
            -right.getStepZ() * terminal[0] - facing.getStepZ() * terminal[2]);
        return new Placement(def, anchor, facing, false);
    }

    /** The Drone Port building's loading terminal (the Drone Port block under its pad), or null. */
    public static @Nullable BlockEntity droneTerminal(Level level, ControllerBlockEntity c) {
        BlockPos at = BuildingLogic.cell(c, ModBlocks.DRONE_PORT.get());
        return at == null ? null : level.getBlockEntity(at);
    }

    /** A Drone Port building put down: its terminal is the company's, and it is on the company's list of ports. */
    static void dronePortPlaced(ServerLevel level, ControllerBlockEntity c) {
        if (!(droneTerminal(level, c) instanceof DronePortBlockEntity port)) return;
        port.companyId = c.companyId;
        port.setChanged();
        BlockPos pos = port.getBlockPos();
        if (!c.companyId.isEmpty()) Places.add(level, pos, Places.DRONE_PORT, c.companyId, "Port " + pos.getX() + ", " + pos.getZ());
    }

    /**
     * True if the player may operate this building (a Truck Station's mode, the Radar Tower's survey): a member of the
     * company that owns it, through any company folded into it. One nobody owns may be used by anyone, and creative
     * players may use anything.
     */
    public static boolean mayOperate(ServerPlayer player, ControllerBlockEntity c) {
        return player.hasInfiniteMaterials() || c.companyId.isEmpty() || Companies.owns(Companies.of(player), c.companyId);
    }

    /** The Sink's tick: while powered, whatever its input port put in is destroyed and paid for. */
    static void sink(ServerLevel level, ControllerBlockEntity c) {
        if (!c.powered || c.companyId.isEmpty()) return;
        ItemStack in = c.input.getItem(0);
        if (in.isEmpty()) return;
        long points = Sink.worth(in);
        c.input.setItem(0, ItemStack.EMPTY);
        c.made += in.getCount();
        Sink.pay(level.getServer(), c.companyId, points);
    }

    /** A player sinks what they hold. */
    static void sinkHeld(ServerPlayer player, ControllerBlockEntity c, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!c.powered) {
            player.sendOverlayMessage(Component.translatable("siftec.sink.no_power"));
            return;
        }
        if (!Sink.accepts(stack)) {
            player.sendOverlayMessage(Component.translatable("siftec.sink.refused"));
            return;
        }
        String to = c.companyId.isEmpty() ? Companies.of(player).id : c.companyId;
        long points = Sink.worth(stack);
        Component name = stack.getHoverName();
        int count = stack.getCount();
        stack.setCount(0);
        c.made += count;
        Sink.pay(player.level().getServer(), to, points);
        player.sendOverlayMessage(Component.translatable("siftec.sink.sunk", Component.literal(count + " ").append(name), points));
    }
}
