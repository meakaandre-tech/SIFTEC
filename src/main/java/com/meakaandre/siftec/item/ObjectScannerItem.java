package com.meakaandre.siftec.item;

import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Object Scanner: finds the nearest slug or artefact your company has not picked up yet. Sneak to switch what it looks for. */
public class ObjectScannerItem extends Item {
    private static final Map<UUID, Collectible> SELECTED = new ConcurrentHashMap<>();

    public ObjectScannerItem(Properties properties) {
        super(properties);
    }

    private static boolean can(Company company, Collectible type) {
        return type.scannerToken.isEmpty() || company.hasToken(type.scannerToken);
    }

    @Override
    public InteractionResult use(Level level, Player user, InteractionHand hand) {
        if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        Company company = Companies.of(player);
        Collectible type = SELECTED.getOrDefault(player.getUUID(), Collectible.BLUE_POWER_SLUG);
        if (!can(company, type)) type = Collectible.BLUE_POWER_SLUG;
        Component name = Component.translatable("item.siftec." + type.id());
        if (player.isShiftKeyDown()) {
            do {
                type = Collectible.values()[(type.ordinal() + 1) % Collectible.values().length];
            } while (!can(company, type));
            SELECTED.put(player.getUUID(), type);
            player.sendOverlayMessage(Component.translatable("siftec.scanner.selected", Component.translatable("item.siftec." + type.id())));
            return InteractionResult.SUCCESS;
        }
        Optional<Collectibles.Spot> found = Collectibles.nearest(server, player.getX(), player.getZ(), type, company.collected, 48);
        if (found.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("siftec.scanner.none_object", name));
            return InteractionResult.SUCCESS;
        }
        Collectibles.Spot spot = found.get();
        int distance = (int) Math.round(Math.sqrt(Math.pow(spot.x() - player.getX(), 2) + Math.pow(spot.z() - player.getZ(), 2)));
        player.sendOverlayMessage(Component.translatable("siftec.scanner.found", name, distance,
            Component.translatable("siftec.direction." + NodeScannerItem.direction(spot.x() - player.getX(), spot.z() - player.getZ())), spot.x(), spot.z()));
        player.getCooldowns().addCooldown(player.getItemInHand(hand), 20);
        return InteractionResult.SUCCESS;
    }

    /** Forgets every player's choice; called when the server stops. */
    public static void forget() {
        SELECTED.clear();
    }
}
