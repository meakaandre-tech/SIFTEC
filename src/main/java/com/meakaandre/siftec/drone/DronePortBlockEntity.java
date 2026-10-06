package com.meakaandre.siftec.drone;

import com.meakaandre.siftec.equip.Ziplines;
import com.meakaandre.siftec.mixin.BlockDisplayInvoker;
import com.meakaandre.siftec.mixin.DisplayInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A Drone Port with one Cardboard Drone. When packages are waiting and there are fire charges for the trip,
 * the drone carries every waiting package to the chosen port, unloads, and flies home. The trip is worked
 * out by time, so it goes on over land that is not loaded; the drone you see is only drawn where it can be.
 */
public class DronePortBlockEntity extends BlockEntity {
    public static final int IDLE = 0, OUTBOUND = 1, HOMEBOUND = 2;
    /** Blocks per tick, and the fire charges burned per this many blocks of a round trip. */
    private static final double SPEED = 0.8;
    private static final int BLOCKS_PER_CHARGE = 100, GROUND_TICKS = 40, CRUISE = 12;

    public String companyId = "";
    public final PortContainer items = new PortContainer(this::setChanged);
    public boolean hasDrone;
    public @Nullable BlockPos destination;
    public int state = IDLE;
    private int ticks, total;
    private final List<ItemStack> cargo = new ArrayList<>();
    private @Nullable Display shown;

    public DronePortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int chargesNeeded() {
        if (destination == null) return 0;
        return Math.max(1, (int) Math.ceil(Math.sqrt(destination.distSqr(worldPosition)) * 2 / BLOCKS_PER_CHARGE));
    }

    public String statusKey() {
        if (!hasDrone) return "siftec.drone.no_drone";
        if (destination == null) return "siftec.drone.no_destination";
        if (state == OUTBOUND) return ticks > 0 ? "siftec.drone.outbound" : "siftec.drone.waiting";
        if (state == HOMEBOUND) return "siftec.drone.homebound";
        if (items.empty(PortContainer.OUT, PortContainer.FUEL)) return "siftec.drone.idle";
        return items.getItem(PortContainer.FUEL).getCount() < chargesNeeded() ? "siftec.drone.no_fuel" : "siftec.drone.idle";
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel server)) return;
        if (state == IDLE) {
            if (shown != null) hide();
            if (!hasDrone || destination == null || level.getGameTime() % 20 != 0 || items.empty(PortContainer.OUT, PortContainer.FUEL)) return;
            int charges = chargesNeeded();
            if (items.getItem(PortContainer.FUEL).getCount() < charges) return;
            items.removeItem(PortContainer.FUEL, charges);
            for (int i = PortContainer.OUT; i < PortContainer.FUEL; i++) {
                if (!items.getItem(i).isEmpty()) cargo.add(items.removeItemNoUpdate(i));
            }
            total = ticks = (int) (Math.sqrt(destination.distSqr(worldPosition)) / SPEED) + GROUND_TICKS;
            state = OUTBOUND;
            setChanged();
            return;
        }
        if (ticks > 0) ticks--;
        show(server);
        if (ticks > 0) return;
        if (state == HOMEBOUND) {
            // anything that could not be delivered comes back as an arrival
            for (ItemStack stack : cargo) {
                ItemStack left = items.add(stack, PortContainer.IN, PortContainer.SIZE);
                if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.2, worldPosition.getZ() + 0.5, left);
            }
            cargo.clear();
            state = IDLE;
            hide();
            setChanged();
            return;
        }
        // at the far port: unload what fits; wait for room, or for its chunk to load
        if (level.getGameTime() % 20 != 0 || destination == null || !level.isLoaded(destination)) return;
        if (level.getBlockEntity(destination) instanceof DronePortBlockEntity other) {
            for (int i = 0; i < cargo.size(); i++) cargo.set(i, other.items.add(cargo.get(i), PortContainer.IN, PortContainer.SIZE));
            cargo.removeIf(ItemStack::isEmpty);
            if (!cargo.isEmpty()) return;
        }
        state = HOMEBOUND;
        ticks = total;
        setChanged();
    }

    /** Where the drone is on its way, for drawing it. */
    private void show(ServerLevel server) {
        if (destination == null) return;
        double done = total == 0 ? 1 : 1 - ticks / (double) total;
        double t = state == OUTBOUND ? done : 1 - done;
        double climb = Math.min(1, Math.min(t, 1 - t) * 10);
        double x = worldPosition.getX() + (destination.getX() - worldPosition.getX()) * t;
        double y = worldPosition.getY() + 1.2 + (destination.getY() - worldPosition.getY()) * t + CRUISE * climb;
        double z = worldPosition.getZ() + (destination.getZ() - worldPosition.getZ()) * t;
        if (!server.isLoaded(BlockPos.containing(x, y, z))) {
            hide();
            return;
        }
        if (shown == null || shown.isRemoved()) {
            Display.BlockDisplay display = net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY.create(server, EntitySpawnReason.TRIGGERED);
            if (display == null) return;
            ((BlockDisplayInvoker) display).siftec$show(BuiltInRegistries.BLOCK.getOptional(Identifier.parse("create:cardboard_block")).orElse(Blocks.BROWN_WOOL).defaultBlockState());
            ((DisplayInvoker) display).siftec$glide(2);
            display.setPos(x, y, z);
            server.addFreshEntity(display);
            display.addTag(Ziplines.TAG);
            shown = display;
        }
        shown.setPos(x, y, z);
    }

    private void hide() {
        if (shown != null) shown.discard();
        shown = null;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        hide();
    }

    /** Everything the port holds, for when it is broken. */
    public List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>(cargo);
        for (int i = 0; i < PortContainer.SIZE; i++) if (!items.getItem(i).isEmpty()) out.add(items.getItem(i));
        return out;
    }

    @Override
    protected void saveAdditional(ValueOutput view) {
        super.saveAdditional(view);
        view.putString("Company", companyId);
        view.putBoolean("Drone", hasDrone);
        view.putInt("State", state);
        view.putInt("Ticks", ticks);
        view.putInt("Total", total);
        if (destination != null) view.store("Destination", BlockPos.CODEC, destination);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < PortContainer.SIZE; i++) slots.add(items.getItem(i));
        view.store("Items", ItemStack.OPTIONAL_CODEC.listOf(), slots);
        view.store("Cargo", ItemStack.OPTIONAL_CODEC.listOf(), List.copyOf(cargo));
    }

    @Override
    protected void loadAdditional(ValueInput view) {
        super.loadAdditional(view);
        companyId = view.getStringOr("Company", "");
        hasDrone = view.getBooleanOr("Drone", false);
        state = view.getIntOr("State", IDLE);
        ticks = view.getIntOr("Ticks", 0);
        total = view.getIntOr("Total", 0);
        destination = view.read("Destination", BlockPos.CODEC).orElse(null);
        List<ItemStack> slots = view.read("Items", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < PortContainer.SIZE; i++) items.setItem(i, i < slots.size() ? slots.get(i) : ItemStack.EMPTY);
        cargo.clear();
        cargo.addAll(view.read("Cargo", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of()));
        cargo.removeIf(ItemStack::isEmpty);
    }

    /** Gives back the drone as an item, if it is home. */
    public ItemStack takeDrone() {
        if (!hasDrone || state != IDLE) return ItemStack.EMPTY;
        hasDrone = false;
        setChanged();
        return new ItemStack(com.meakaandre.siftec.registry.ModItems.CARDBOARD_DRONE.get());
    }

    public ItemStack fuel() {
        return items.getItem(PortContainer.FUEL);
    }

    public static boolean isFuel(ItemStack stack) {
        return stack.is(Items.FIRE_CHARGE);
    }
}
