package com.meakaandre.siftec.building;

import com.meakaandre.siftec.blueprint.DesignerBlockEntity;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.equip.Ziplines;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.util.Chunks;
import com.zurrtum.create.AllItems;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.jspecify.annotations.Nullable;

/** What keeps a building whole: no mining, wrenching or changing its blocks by hand, and the checks the mixins ask. */
public final class BuildingProtection {
    /** How far {@link #within} looks out from a spot for the building round it. */
    private static final int WITHIN_REACH = 32;

    private BuildingProtection() {
    }

    /** Registers the protections against changing a building's blocks (from {@link Buildings#register}). */
    static void register() {
        // no block of a building can be mined, not even in creative: the Build Gun takes the building down
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            BlockPos controller = BuildingIndex.controllerOf(level, pos);
            if (controller == null) return true;
            if (player instanceof ServerPlayer server) {
                boolean hub = level.getBlockState(controller).getBlock() instanceof HubBlock;
                server.sendOverlayMessage(Component.translatable(hub ? "siftec.prefab.part" : "siftec.build_gun.part", nameAt(level, controller)));
            }
            return false;
        });
        // nor scraped, stripped or waxed: that would change the block and bring the building down
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !changesBlock(player.getItemInHand(hand))) return InteractionResult.PASS;
            if (!shellChangeRefused(level, player, hit.getBlockPos())) return InteractionResult.PASS;
            Claims.resync(player, level, hit.getBlockPos(), hit.getDirection());
            return InteractionResult.FAIL;
        });
        // nor wrenched off or turned; a wrench on a Power Pole or Tower rides its wires instead (the Zipline research)
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !player.getItemInHand(hand).is(AllItems.WRENCH)) return InteractionResult.PASS;
            BlockPos controller = BuildingIndex.controllerOf(level, hit.getBlockPos());
            if (controller == null) return InteractionResult.PASS;
            if (player instanceof ServerPlayer rider && level.getBlockEntity(controller) instanceof ControllerBlockEntity c && c.def() != null
                && c.def().power() == PowerRole.POLE) {
                BlockPos connector = connectorOf(level, c);
                if (!player.hasInfiniteMaterials() && !Companies.of(rider).hasToken("zipline")) {
                    rider.sendOverlayMessage(Component.translatable("siftec.zipline.locked"));
                } else if (connector == null || !Ziplines.start(rider, connector)) {
                    rider.sendOverlayMessage(Component.translatable("siftec.zipline.no_line"));
                }
                return InteractionResult.SUCCESS;
            }
            if (player instanceof ServerPlayer server) server.sendOverlayMessage(Component.translatable("siftec.build_gun.part", nameAt(level, controller)));
            return InteractionResult.FAIL;
        });
    }

    /** The wire connector of a building (a pole's or tower's top), or null. */
    public static @Nullable BlockPos connectorOf(Level level, ControllerBlockEntity c) {
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return null;
        for (BuildingTemplate.Cell cell : t.members) {
            if (level.getBlockEntity(p.at(cell)) instanceof AbstractElectricalConnector) return p.at(cell);
        }
        return null;
    }

    static Component nameAt(Level level, BlockPos controller) {
        if (level.getBlockEntity(controller) instanceof ControllerBlockEntity c && Buildings.def(c.buildingId) != null) return Buildings.def(c.buildingId).name();
        // the HUB's Craft Station is listed under the HUB's terminal
        if (level.getBlockState(controller).getBlock() instanceof HubBlock) return Component.translatable("block.siftec.hub");
        return Component.translatable("siftec.build_gun.building");
    }

    /**
     * True (and the player told why) if a block of a Build Gun building may not be changed by hand: an axe scraping or
     * stripping it, wax going on or off, a casing applied to it. Changing one would bring the building down; its shell
     * changes only with the Build Gun and the Customizer. The controller and the ports are left to their own use.
     */
    public static boolean shellChangeRefused(Level level, @Nullable Player player, BlockPos pos) {
        BlockPos controller = BuildingIndex.controllerOf(level, pos);
        if (controller == null || pos.equals(controller) || level.getBlockState(pos).getBlock() instanceof PortBlock) return false;
        if (player instanceof ServerPlayer server) server.sendOverlayMessage(Component.translatable("siftec.build_gun.part", nameAt(level, controller)));
        return true;
    }

    /** Items whose use on a block changes that block (an axe strips or scrapes, honeycomb waxes, a shovel or hoe turns soil). */
    static boolean changesBlock(ItemStack stack) {
        return stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
            || stack.is(Items.HONEYCOMB);
    }

    /**
     * True if the spot is a block of a Build Gun building or lies inside one's space: its room, a doorway, the air its
     * template keeps free (those are no blocks of it, so {@link #protectedAt} does not see them). Looks out along the six
     * directions for the first block of a building and checks that building's box.
     */
    public static boolean within(Level level, BlockPos pos) {
        if (BuildingIndex.isMember(level, pos)) return true;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            for (int i = 1; i <= WITHIN_REACH; i++) {
                at.setWithOffset(pos, d.getStepX() * i, d.getStepY() * i, d.getStepZ() * i);
                BlockPos controller = BuildingIndex.controllerOf(level, at);
                if (controller == null) continue;
                // a Blueprint Designer's volume is no part of it: whatever is built there is the players' own
                if (Chunks.blockEntity(level, controller) instanceof ControllerBlockEntity c
                    && BuildGun.bounds(c).contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) && !inOpenSpace(c, pos)) return true;
                break;
            }
        }
        return false;
    }

    /** True if the spot is in the open space of a building that is no part of it (a Blueprint Designer's volume). */
    static boolean inOpenSpace(ControllerBlockEntity c, BlockPos pos) {
        AABB open = DesignerBlockEntity.openSpace(c);
        return open != null && open.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** For the protections in mixins: true if the block belongs to a building (always false on the client). */
    public static boolean protectedAt(Level level, BlockPos pos) {
        return BuildingIndex.isMember(level, pos);
    }

    /**
     * True if something at {@code from} (a funnel, a hopper) may not reach the inventory at {@code target}: the target is
     * a block of a building other than one of its ports, and {@code from} is not part of that same building. A building
     * is fed and emptied only at its ports, as in Satisfactory.
     */
    public static boolean sealed(Level level, BlockPos from, BlockPos target) {
        BlockPos building = BuildingIndex.controllerOf(level, target);
        // Build Gun buildings only: the HUB's hand-cranked Craft Station is listed under its terminal and stays open
        if (building == null || level.getBlockState(target).getBlock() instanceof PortBlock || !(level.getBlockEntity(building) instanceof ControllerBlockEntity)) return false;
        return !building.equals(BuildingIndex.controllerOf(level, from));
    }

    /**
     * Whether a Deployer standing at {@code deployer} may act on the block at {@code target}: never on a building's
     * block, unless the Deployer is part of that same building (the Manufacturer's line works on its own Depots).
     */
    public static boolean deployerMayAct(Level level, BlockPos deployer, BlockPos target) {
        BlockPos building = BuildingIndex.controllerOf(level, target);
        if (building == null) return true;
        return building.equals(BuildingIndex.controllerOf(level, deployer));
    }
}
