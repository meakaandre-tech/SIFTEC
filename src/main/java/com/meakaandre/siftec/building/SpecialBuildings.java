package com.meakaandre.siftec.building;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.equip.Radar;
import com.meakaandre.siftec.sink.ShopMenu;
import com.meakaandre.siftec.sink.Sink;
import com.meakaandre.siftec.sink.SinkMenu;
import com.meakaandre.siftec.workshop.WorkshopMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Work package 10's buildings: the Equipment Workshop, the AWESOME Sink and Shop, and the Radar Tower. Using one opens
 * its window; the Sink destroys what its input port brings while it has power, for its company's points.
 */
public final class SpecialBuildings {
    private SpecialBuildings() {
    }

    /** Opens the building's window. False for any other building (it says its status instead). */
    static boolean use(ServerPlayer player, ControllerBlockEntity c, BuildingDef def) {
        Company company = Companies.of(player);
        switch (def.logic()) {
            case "workshop" -> WorkshopMenu.open(player, company);
            case "shop" -> ShopMenu.open(player, company);
            case "sink" -> {
                // a Sink is its company's: anyone may look, but points and coupons are the owner's
                Company owner = c.companyId.isEmpty() ? company : com.meakaandre.siftec.company.CompanyData.get(player.level().getServer()).byId(c.companyId);
                SinkMenu.open(player, owner == null ? company : owner, () -> c.powered);
            }
            case "radar" -> Radar.use(player, c);
            default -> {
                return false;
            }
        }
        return true;
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
