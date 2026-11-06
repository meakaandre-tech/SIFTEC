package com.meakaandre.siftec.command;

import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.HubMenu;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.mam.MamBlock;
import com.meakaandre.siftec.mam.MamMenu;
import com.meakaandre.siftec.mapapi.SiftecMap;
import com.meakaandre.siftec.sink.Shop;
import com.meakaandre.siftec.sink.ShopKind;
import com.meakaandre.siftec.sink.ShopMenu;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.UUID;

/**
 * Creative ignores every unlock ({@code /siftec selftest creativeunlocks}, lines "SELFTEST creative-unlocks"): a creative
 * player in a company that has unlocked nothing is never stopped by a milestone, MAM research or Shop unlock, survival
 * is stopped as before, and nothing a creative player does unlocks anything for the company. The checks here are the
 * server's: the Map (Field Research), the Gateway's way to The Sift (Wormhole Phase 5), the Shop (a creative player takes,
 * never buys for the company for free) and the MAM (research for the company needs the company's own Field Research and
 * found tree). The backpack's tier and upgrade slots are {@code /siftec selftest tb creative}'s.
 * <p>
 * {@code ... menu} / {@code ... menu done} ask every client to check its build menu in creative after the server sent a
 * partial unlock list ({@link MenuCheck}; logged by the client as "SELFTEST creative-unlocks-client ...").
 */
public final class CreativeUnlockTests {
    private static final TestReport REPORT = new TestReport("SELFTEST creative-unlocks ");
    private static final String COMPANY = "selftest_creative_unlocks";
    private static final UUID MEMBER = UUID.fromString("5e1f7e57-c0de-4c4e-a7e0-000000c4ea71");

    private CreativeUnlockTests() {
    }

    /** Server to client: check the build menu as a creative player sees it; with {@code done}, close it and log the totals. */
    public record MenuCheck(boolean done) implements CustomPacketPayload {
        public static final Type<MenuCheck> TYPE = new Type<>(Siftec.id("selftest_creative_menu_check"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MenuCheck> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeBoolean(p.done), buf -> new MenuCheck(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(MenuCheck.TYPE, MenuCheck.STREAM_CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("creativeunlocks").executes(context -> run(context.getSource()))
                    .then(Commands.literal("menu").executes(context -> menu(context.getSource(), false))
                        .then(Commands.literal("done").executes(context -> menu(context.getSource(), true))))))));
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    /** The test company, made afresh with nothing done, bought or found; the fake player is its only member. */
    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = COMPANY;
        c.name = COMPANY;
        c.members.add(MEMBER.toString());
        c.owner = MEMBER.toString();
        data.companies().put(COMPANY, c);
        data.setDirty();
        return c;
    }

    private static FakePlayer member(ServerLevel level) {
        FakePlayer fake = FakePlayer.get(level, new GameProfile(MEMBER, "selftest_creative"));
        fake.getInventory().clearContent();
        fake.setShiftKeyDown(false);
        return fake;
    }

    /** Nothing in the company changed: nothing done, bought, found or being researched. */
    private static String untouched(Company c) {
        return c.done.isEmpty() && c.shop.isEmpty() && c.trees.isEmpty() && c.research.isEmpty() ? "" : "done " + c.done + ", shop " + c.shop + ", trees " + c.trees + ", research " + c.research;
    }

    private static int run(CommandSourceStack source) {
        REPORT.reset();
        ServerLevel level = source.getServer().overworld();
        REPORT.attempt(source, "creative unlocks", () -> checks(source, level));
        CompanyData.get(source.getServer()).companies().remove(COMPANY);
        REPORT.report(source, "done: " + REPORT.totals());
        return 1;
    }

    private static void checks(CommandSourceStack source, ServerLevel level) {
        Company company = company(level);
        FakePlayer fake = member(level);
        try {
            // the Map: Field Research gives it to survival players; a creative player always has it
            fake.setGameMode(GameType.SURVIVAL);
            boolean survivalMap = SiftecMap.UNLOCKS.unlocked(fake, MapFeature.TERRAIN) || SiftecMap.UNLOCKS.unlocked(fake, MapFeature.FULLSCREEN);
            fake.setGameMode(GameType.CREATIVE);
            boolean creativeMap = SiftecMap.UNLOCKS.unlocked(fake, MapFeature.TERRAIN) && SiftecMap.UNLOCKS.unlocked(fake, MapFeature.FULLSCREEN);
            expect(source, "map: a creative player has the terrain and the fullscreen map without Field Research", creativeMap, "");
            expect(source, "map: a survival player still needs Field Research for it", !survivalMap, "");

            // the Wormhole Gateway's button to The Sift
            fake.setGameMode(GameType.SURVIVAL);
            boolean survivalSift = HubMenu.maySift(fake, company);
            fake.setGameMode(GameType.CREATIVE);
            boolean creativeSift = HubMenu.maySift(fake, company);
            expect(source, "gateway: a creative player may go to The Sift before Wormhole Phase 5", creativeSift, "");
            expect(source, "gateway: a survival player still needs Wormhole Phase 5", !survivalSift, "");

            // the Shop: a creative player has every unlock, so a click takes it and never buys it for the company free
            Shop.Entry unlock = null;
            for (Shop.Entry e : Shop.entries()) {
                if (e.kind() == ShopKind.UNLOCK && e.coupons() > 0) {
                    unlock = e;
                    break;
                }
            }
            if (unlock == null) {
                expect(source, "shop: the Shop has a building unlock that costs coupons", false, "");
            } else {
                fake.setGameMode(GameType.CREATIVE);
                boolean creativeTakes = ShopMenu.takes(fake, company, unlock);
                Component refused = ShopMenu.buyRefusal(fake, unlock);
                expect(source, "shop: a creative player is not stopped by a Shop unlock (" + unlock.id() + ": a click takes it, not buys it)", creativeTakes, "");
                expect(source, "shop: a creative player without coupons cannot buy it for the company free", refused != null, refused == null ? "free" : refused.getString());
                fake.setGameMode(GameType.SURVIVAL);
                boolean survivalTakes = ShopMenu.takes(fake, company, unlock);
                Component poor = ShopMenu.buyRefusal(fake, unlock);
                expect(source, "shop: a survival player still has to buy it, with coupons", !survivalTakes && poor != null, poor == null ? "free" : poor.getString());
            }

            // the MAM: a creative player sees every tree, but research for the company needs the company's own
            // Field Research and found tree
            Milestones.Tree tree = Milestones.trees().isEmpty() ? null : Milestones.trees().getFirst();
            if (tree == null) {
                expect(source, "mam: there is a research tree", false, "");
            } else {
                fake.setGameMode(GameType.CREATIVE);
                boolean creativeResearch = MamMenu.mayResearch(fake, company, tree);
                expect(source, "mam: a creative player does not start research for a company without Field Research and the tree (" + tree.id() + ")",
                    !creativeResearch, "");
                company.done.add(MamBlock.FIELD_RESEARCH);
                company.trees.add(tree.id());
                fake.setGameMode(GameType.SURVIVAL);
                boolean survivalResearch = MamMenu.mayResearch(fake, company, tree);
                company.done.remove(MamBlock.FIELD_RESEARCH);
                company.trees.remove(tree.id());
                expect(source, "mam: with Field Research done and the tree found, research starts as before", survivalResearch, "");
            }

            String left = untouched(company);
            expect(source, "nothing a creative player did unlocked anything for the company", left.isEmpty(), left);
        } finally {
            fake.setGameMode(GameType.SURVIVAL);
            fake.getInventory().clearContent();
        }
    }

    private static int menu(CommandSourceStack source, boolean done) {
        int sent = 0;
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new MenuCheck(done));
            sent++;
        }
        REPORT.report(source, "build menu check" + (done ? " (done)" : "") + " asked of " + sent + " players");
        return 1;
    }
}
