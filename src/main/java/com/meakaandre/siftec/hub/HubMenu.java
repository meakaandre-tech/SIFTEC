package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * The HUB and Wormhole Gateway window, drawn on the client by {@code client.HubScreen} in the style of the old
 * Tweaker HUB screen: tier tabs, the tier's milestones, the chosen one's costs, and the HUB status.
 * <p>
 * The menu has no slots. The screen's buttons arrive as vanilla menu button clicks (which the game only passes on
 * for the player's open menu, and only while {@link #stillValid} holds); every one is decoded, bounds-checked and
 * judged here by the same rules as before. What the screen shows is sent as a {@link HubViewPayload} whenever it
 * changes.
 */
public class HubMenu extends AbstractContainerMenu {
    public static MenuType<HubMenu> TYPE;

    /** Button actions. */
    public static final int SELECT = 1, PAY = 2, RESCAN = 3, SIFT = 4;
    /** The "tier" that stands for the wormhole phases in a button id. */
    public static final int PHASES = Milestones.TIERS;
    private static final double REACH = 8.0;

    private final @Nullable ServerPlayer player;
    private final @Nullable Company company;
    private final @Nullable BlockPos pos;
    private final boolean gateway;
    private byte[] sent;
    private int ticks;
    /** The client's copy of what to show; null until the server has sent it. */
    public @Nullable HubView view;

    public static void register() {
        TYPE = Registry.register(BuiltInRegistries.MENU, Siftec.id("hub"), new MenuType<>(HubMenu::new, FeatureFlags.VANILLA_SET));
        PayloadTypeRegistry.clientboundPlay().register(HubViewPayload.TYPE, HubViewPayload.STREAM_CODEC);
    }

    /** The client's menu. */
    public HubMenu(int id, Inventory inventory) {
        super(TYPE, id);
        this.player = null;
        this.company = null;
        this.pos = null;
        this.gateway = false;
    }

    private HubMenu(int id, ServerPlayer player, Company company, BlockPos pos, boolean gateway) {
        super(TYPE, id);
        this.player = player;
        this.company = company;
        this.pos = pos;
        this.gateway = gateway;
    }

    public static void open(ServerPlayer player, Company company, BlockPos pos, boolean gateway) {
        Component title = Component.translatable(gateway ? "siftec.gateway.title" : "siftec.hub.title", company.name);
        BlockPos at = pos.immutable();
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new HubMenu(id, player, company, at, gateway), title));
        if (player.containerMenu instanceof HubMenu menu) menu.sync(true);
    }

    // ---- button ids: action << 12 | tier << 6 | index

    public static int button(int action, int tier, int index) {
        return action << 12 | tier << 6 | index;
    }

    /** The milestone a button id names, or null if it names none (out of range, unknown tier, garbage). */
    public static @Nullable Milestone milestone(int id) {
        if (id < 0) return null;
        int tier = id >> 6 & 63, index = id & 63;
        if (tier > PHASES) return null;
        List<Milestone> list = tier == PHASES ? Milestones.phases() : Milestones.tier(tier);
        return index < list.size() ? list.get(index) : null;
    }

    public static int action(int id) {
        return id < 0 ? 0 : id >>> 12;
    }

    // ---- server side

    private @Nullable HubBlockEntity hub() {
        if (player == null || pos == null || !(player.level() instanceof ServerLevel level) || !level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof HubBlockEntity hub ? hub : null;
    }

    private long lockTicks() {
        return Math.max(0, company.lockUntil - player.level().getServer().overworld().getGameTime());
    }

    private boolean exempt() {
        return gateway || player.hasInfiniteMaterials();
    }

    private int built() {
        if (exempt()) return Milestones.TIERS;
        HubBlockEntity hub = hub();
        return hub == null ? -1 : hub.builtTier();
    }

    /** Why this player cannot pay into this milestone here and now, or null when they can. */
    private @Nullable Component refusal(Milestone m) {
        if (company.has(m.id())) return Component.translatable("siftec.hub.done");
        if (m.isPhase() != gateway) return Component.translatable(m.isPhase() ? "siftec.hub.at_gateway" : "siftec.hub.at_hub");
        Milestone blocker = Milestones.blocker(company, m);
        if (blocker != null) return Component.translatable("siftec.hub.needs", blocker.name());
        if (!m.isPhase() && lockTicks() > 0) return Component.translatable("siftec.hub.busy", clock(lockTicks()));
        if (!m.isPhase() && m.tier() > built()) return Component.translatable("siftec.building.needed", m.tier());
        return null;
    }

    public static String clock(long ticks) {
        long seconds = (ticks + 19) / 20;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    @Override
    public boolean clickMenuButton(Player who, int id) {
        if (player == null || who != player || company == null) return false;
        int action = action(id);
        MinecraftServer server = player.level().getServer();
        switch (action) {
            case SELECT, PAY -> {
                Milestone m = milestone(id);
                if (m == null) return false;
                Component why = refusal(m);
                if (why != null) {
                    player.sendOverlayMessage(why);
                    sync(true);
                    return false;
                }
                company.active = m.id();
                if (action == PAY) {
                    int delivered = deliver(player, company, m);
                    Companies.save(server);
                    if (company.fullyPaid(m)) {
                        Companies.complete(server, company, m, player.getName());
                    } else {
                        player.sendOverlayMessage(delivered > 0
                            ? Component.translatable("siftec.hub.delivered", delivered)
                            : Component.translatable("siftec.hub.nothing"));
                    }
                } else {
                    Companies.save(server);
                    player.sendOverlayMessage(Component.translatable("siftec.hub.now_active", m.name()));
                }
            }
            case RESCAN -> {
                HubBlockEntity hub = hub();
                if (gateway || hub == null) return false;
                if (hub.measure() == null) player.sendOverlayMessage(Component.translatable("siftec.planner.how"));
            }
            case SIFT -> {
                if (!gateway || !company.has("phase_5")) return false;
                player.closeContainer();
                com.meakaandre.siftec.compat.SiftGate.enter(player);
                return true;
            }
            default -> {
                return false;
            }
        }
        sync(true);
        return true;
    }

    /** Takes what the milestone still needs out of the player's inventory and backpack, and books it. */
    public static int deliver(ServerPlayer player, Company company, Milestone m) {
        int total = 0;
        for (Milestone.Cost cost : m.cost()) {
            if (!cost.present()) continue;
            int took = cost.take(player.getInventory(), company.needed(m, cost));
            company.pay(m, cost, took);
            total += took;
        }
        return total;
    }

    /** Sends the view if it changed (or always, when {@code force}). */
    private void sync(boolean force) {
        if (player == null || company == null) return;
        HubBlockEntity hub = gateway ? null : hub();
        int built = built();
        HubView v = HubView.of(company, gateway, built, exempt(), lockTicks(), hub == null ? null : hub.lastResult());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        v.write(buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        if (!force && Arrays.equals(bytes, sent)) return;
        sent = bytes;
        ServerPlayNetworking.send(player, new HubViewPayload(containerId, v));
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        // payments by belt or by other members, the lock timer and the building all change while the window is open
        if (player != null && ++ticks % 10 == 0) sync(false);
    }

    // the menu has no slots: nothing can be put in, taken out or shift-clicked
    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player who) {
        if (player == null) return true;
        if (who != player || company == null || pos == null || player.isRemoved()) return false;
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > REACH * REACH) return false;
        HubBlockEntity hub = hub();
        if (hub == null || !(hub.getBlockState().getBlock() instanceof HubBlock block) || block.gateway != gateway) return false;
        // the block must still be this company's, and the player still in it
        if (!company.id.equals(hub.companyId) || !company.members.contains(player.getUUID().toString())) return false;
        return CompanyData.get(player.level().getServer()).byId(company.id) == company;
    }
}
