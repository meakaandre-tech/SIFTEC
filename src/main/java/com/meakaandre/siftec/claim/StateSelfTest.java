package com.meakaandre.siftec.claim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.place.Places;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.save.JsonSavedData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Used by the automated test ({@code /siftec selftest state setup|check|verify}): saves far bigger than the old
 * 64 KB limit, claims given up when their block goes, explosions, the HUB budget, the spawn area, chunk loading
 * and kicking. {@code verify} runs after the restart and removes everything the test made.
 */
public final class StateSelfTest {
    private static final String BIG = "selftest_big", OTHER = "selftest_other", BUDGET = "selftest_budget", LOAD = "selftest_load", KICK = "selftest_kick";
    private static final String FAKE_PREFIX = "5e1f7e57-0000-4000-8000-";
    private static final int BIG_COLLECTED = 4000, BIG_CLAIMS = 1500, BIG_PLACES = 600;
    /** The test area, well away from spawn. */
    private static final int AREA = 3000;
    private static BlockPos markerA, markerB, dirtB, hub;

    private StateSelfTest() {
    }

    private static String fake(int n) {
        return UUID.fromString(FAKE_PREFIX + String.format("%012d", n)).toString();
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("state")
                    .then(Commands.literal("setup").executes(context -> setup(context.getSource())))
                    .then(Commands.literal("check").executes(context -> check(context.getSource())))
                    .then(Commands.literal("verify").executes(context -> verify(context.getSource())))
                    .then(Commands.literal("cleanup").executes(context -> {
                        report(context.getSource(), "SELFTEST state cleanup: removed " + cleanup(context.getSource().getServer()) + " test companies");
                        return 1;
                    }))))
        ));
    }

    private static Company company(CompanyData data, String id, String... members) {
        Company company = new Company();
        company.id = id;
        company.name = id;
        for (String m : members) company.members.add(m);
        if (members.length > 0) company.owner = members[0];
        data.companies().put(id, company);
        data.setDirty();
        return company;
    }

    private static BlockPos ground(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    private static int setup(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        codecTests(source, level);

        // 1. big saves: written at shutdown, read back by "verify" after the restart
        CompanyData data = CompanyData.get(server);
        Company big = company(data, BIG, fake(0));
        for (int i = 0; i < BIG_COLLECTED; i++) big.collected.add((1_000_000 + i) + "," + (2_000_000 + i));
        Claims.Data claims = Claims.Data.get(server);
        BlockPos farSource = new BlockPos(1_600_000, 64, 1_600_000);
        for (int i = 0; i < BIG_CLAIMS; i++) {
            Claims.Claim claim = new Claims.Claim();
            claim.company = BIG;
            claim.source = farSource.asLong();
            claim.marker = i % 2 == 0;
            claims.chunks().put(Claims.key(Level.OVERWORLD, 100_000 + i % 50, 100_000 + i / 50), claim);
        }
        Claims.changed(server);
        for (int i = 0; i < BIG_PLACES; i++) {
            Places.add(level, new BlockPos(1_500_000 + i, 70, 1_500_000), Places.DRONE_PORT, BIG, "Selftest drone port number " + i);
        }
        Companies.save(server);
        int companyChars = JsonSavedData.GSON.toJson(data.stored).length();
        int claimChars = JsonSavedData.GSON.toJson(claims.stored).length();
        report(source, "SELFTEST state save: companies " + companyChars + " chars, claims " + claimChars + " chars as JSON (the old one-string limit was 65535)");

        // 2. claims follow their block: replaced (as /setblock does) and blown up
        Company other = company(data, OTHER, fake(1));
        markerA = ground(level, AREA, AREA);
        level.setBlockAndUpdate(markerA, ModBlocks.CLAIM_MARKER.get().defaultBlockState());
        int a = Claims.claim(level, other, markerA, Claims.MARKER_RADIUS, true, 9);
        level.setBlockAndUpdate(markerA, Blocks.AIR.defaultBlockState());
        markerB = ground(level, AREA + 96, AREA);
        level.setBlockAndUpdate(markerB, ModBlocks.CLAIM_MARKER.get().defaultBlockState());
        int b = Claims.claim(level, other, markerB, Claims.MARKER_RADIUS, true, 9);
        dirtB = markerB.east(2);
        level.setBlockAndUpdate(dirtB, Blocks.DIRT.defaultBlockState());
        level.explode(null, markerB.getX() + 0.5, markerB.getY() + 1.0, markerB.getZ() + 0.5, 4f, Level.ExplosionInteraction.TNT);
        report(source, "SELFTEST state claims setup: marker A claimed " + a + ", marker B claimed " + b);

        // 3. the HUB budget: one claiming HUB per company, HUB chunks count against the company's chunks
        Company budget = company(data, BUDGET, fake(5));
        hub = ground(level, AREA + 400, AREA);
        level.setBlockAndUpdate(hub, ModBlocks.HUB.get().defaultBlockState());
        int first = Claims.claimForHub(level, budget, hub);
        BlockPos hub2 = ground(level, AREA + 600, AREA);
        int second = Claims.claimForHub(level, budget, hub2);
        int markers = 0;
        for (int i = 0; i < 10; i++) {
            int room = Claims.chunkBudget(budget) - Claims.chunksUsed(server, budget);
            if (room <= 0) break;
            markers += Claims.claim(level, budget, new BlockPos(AREA + 400 + 48 * (i + 2), 70, AREA + 800), Claims.MARKER_RADIUS, true, room) > 0 ? 1 : 0;
        }
        boolean nearOther = Claims.nearForeign(level, budget, markerB.east(40), Claims.HUB_RADIUS + 1, false);
        report(source, "SELFTEST state budget: first HUB claimed " + first + ", second HUB " + (second < 0 ? "claims nothing" : "claimed " + second)
            + "; then " + markers + " markers until the budget of " + Claims.chunkBudget(budget) + " chunks was used (" + Claims.chunksUsed(server, budget)
            + "); HUB next to another company's land refused " + nearOther);

        // 4. the spawn area
        BlockPos spawn = level.getRespawnData().pos();
        int atSpawn = Claims.claim(level, other, spawn, Claims.MARKER_RADIUS, true, 9);
        report(source, "SELFTEST state spawn: radius " + com.meakaandre.siftec.config.SiftecConfig.spawnFreeRadius + ", spawn in zone " + Claims.inSpawnZone(level, spawn)
            + ", 300 blocks out in zone " + Claims.inSpawnZone(level, spawn.offset(300, 0, 300)) + ", a marker at spawn claimed " + atSpawn);

        // 5. chunk loading: there are no players in the test, so who is online is given
        Company load = company(data, LOAD, fake(2));
        for (int i = 0; i < 4; i++) {
            Claims.Claim claim = new Claims.Claim();
            claim.company = LOAD;
            claim.source = new BlockPos(AREA + 1200, 70, AREA).asLong();
            claims.chunks().put(Claims.key(Level.OVERWORLD, ((AREA + 1200) >> 4) + i, AREA >> 4), claim);
        }
        Claims.changed(server);
        String member = load.members.iterator().next();
        Claims.refreshLoading(server, uuid -> uuid.toString().equals(member));
        int online = Claims.loadedCount();
        Claims.refreshLoading(server, uuid -> false);
        int afterLeave = Claims.loadedCount();
        Claims.refreshLoading(server);
        report(source, "SELFTEST state loading: member online " + online + " chunks loaded; after the last member left " + afterLeave
            + "; with the real player list " + Claims.loadedCount());

        // 6. kicking, the multiplier memory and scaled payments
        Company kick = company(data, KICK, fake(3), fake(4));
        boolean byMember = Companies.kick(server, kick, fake(4), fake(3));
        boolean byFounder = Companies.kick(server, kick, fake(3), fake(4));
        float multiplier = kick.costMultiplier();
        boolean cooling = kick.coolingDown(fake(4));
        kick.paid.put("selftest", new java.util.HashMap<>(Map.of("minecraft:iron_ingot", 15)));
        kick.paidAt = 2.0f;
        kick.leftAt.clear();
        kick.syncPaid();
        report(source, "SELFTEST state kick: by a member " + byMember + ", by the founder " + byFounder + "; members " + kick.members.size()
            + "; multiplier right after " + multiplier + "; may rejoin " + !cooling + "; 15 parts paid at x2.0 become " + kick.paid.get("selftest").get("minecraft:iron_ingot")
            + " at x" + kick.costMultiplier());
        return 1;
    }

    private static void codecTests(CommandSourceStack source, ServerLevel level) {
        // companies far over 64 KB: NBT tree there and back
        CompanyData data = new CompanyData();
        Company c = new Company();
        c.id = "codec";
        for (int i = 0; i < 5000; i++) c.collected.add("x" + i + ",z" + i);
        c.paid.put("m", new java.util.HashMap<>(Map.of("minecraft:iron_ingot", 7)));
        c.lockUntil = 123_456_789_012L;
        data.companies().put(c.id, c);
        Tag tree = CompanyData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        CompanyData back = CompanyData.CODEC.parse(NbtOps.INSTANCE, tree).getOrThrow();
        Company cb = back.companies().get("codec");
        boolean treeOk = !back.broken() && cb != null && cb.collected.size() == 5000 && cb.lockUntil == 123_456_789_012L && cb.paid.get("m").get("minecraft:iron_ingot") == 7;
        // the old format: one JSON string
        String json = JsonSavedData.GSON.toJson(data.stored);
        CompanyData legacy = CompanyData.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf(json)).getOrThrow();
        Company cl = legacy.companies().get("codec");
        boolean legacyOk = !legacy.broken() && cl != null && cl.collected.size() == 5000;
        // an unreadable file: marked broken and never written back
        CompanyData broken = CompanyData.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf("{\"companies\": {oops")).getOrThrow();
        broken.setDirty();
        boolean brokenOk = broken.broken() && !broken.isDirty();
        // claims: the marker flag (a boolean, a byte in NBT) and a long that does not fit in a double
        Claims.Data claims = new Claims.Data();
        Claims.Claim claim = new Claims.Claim();
        claim.company = "x";
        claim.marker = true;
        claim.source = new BlockPos(-29_999_000, -60, 29_999_000).asLong();
        claims.chunks().put("minecraft:overworld|5", claim);
        Claims.Data claimsBack = Claims.Data.CODEC.parse(NbtOps.INSTANCE, Claims.Data.CODEC.encodeStart(NbtOps.INSTANCE, claims).getOrThrow()).getOrThrow();
        Claims.Claim cb2 = claimsBack.chunks().get("minecraft:overworld|5");
        boolean claimOk = cb2 != null && cb2.marker && cb2.source == claim.source;
        report(source, "SELFTEST state codec: tree round trip " + treeOk + " (" + json.length() + " chars as JSON); old string format loads " + legacyOk
            + "; unreadable file kept and not overwritten " + brokenOk + "; claim flags and positions " + claimOk);

        // the backpack keeps slot positions and survives one unreadable item
        RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Backpack.Contents contents = new Backpack.Contents();
        contents.items.set(0, new ItemStack(Items.IRON_INGOT, 5));
        contents.items.set(7, new ItemStack(Items.DIAMOND, 1));
        contents.items.set(35, new ItemStack(Items.STONE, 64));
        Backpack.Contents again = Backpack.Contents.CODEC.parse(ops, Backpack.Contents.CODEC.encodeStart(ops, contents).getOrThrow()).getOrThrow();
        boolean slotsOk = again.items.get(0).is(Items.IRON_INGOT) && again.items.get(0).getCount() == 5 && again.items.get(7).is(Items.DIAMOND)
            && again.items.get(35).getCount() == 64 && again.items.get(1).isEmpty();
        ListTag list = new ListTag();
        list.add(entry(1, "nosuchmod:thing", 1));
        list.add(entry(4, "minecraft:apple", 2));
        Backpack.Contents partial = Backpack.Contents.CODEC.parse(ops, list).getOrThrow();
        boolean partialOk = partial.items.get(1).isEmpty() && partial.items.get(4).is(Items.APPLE) && partial.items.get(4).getCount() == 2;
        ListTag old = new ListTag();
        old.add(new CompoundTag());
        CompoundTag oldItem = new CompoundTag();
        oldItem.putString("id", "minecraft:bread");
        oldItem.putInt("count", 3);
        old.add(oldItem);
        Backpack.Contents oldFormat = Backpack.Contents.CODEC.parse(ops, old).getOrThrow();
        boolean oldOk = oldFormat.items.get(0).isEmpty() && oldFormat.items.get(1).is(Items.BREAD);
        report(source, "SELFTEST state backpack codec: slots kept " + slotsOk + "; unreadable item skipped, others in place " + partialOk + "; old list format loads " + oldOk);
    }

    private static CompoundTag entry(int slot, String id, int count) {
        CompoundTag item = new CompoundTag();
        item.putString("id", id);
        item.putInt("count", count);
        CompoundTag out = new CompoundTag();
        out.putInt("Slot", slot);
        out.put("Item", item);
        return out;
    }

    private static int check(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        if (markerA == null) {
            report(source, "SELFTEST state check: run setup first");
            return 0;
        }
        boolean aGone = Claims.at(level, markerA) == null;
        boolean bKept = level.getBlockState(markerB).is(ModBlocks.CLAIM_MARKER.get());
        boolean bClaim = Claims.at(level, markerB) != null;
        boolean dirt = level.getBlockState(dirtB).is(Blocks.DIRT);
        level.setBlockAndUpdate(hub, Blocks.AIR.defaultBlockState());
        report(source, "SELFTEST state check: marker replaced by setblock released its claim " + aGone + "; after an explosion the marker is still there " + bKept
            + ", its claim " + bClaim + ", a block in the claim " + dirt);
        source.getServer().execute(() -> report(source, "SELFTEST state check: HUB removed released its claim " + (Claims.at(level, hub) == null)));
        return 1;
    }

    private static int verify(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        CompanyData data = CompanyData.get(server);
        Company big = data.byId(BIG);
        int claims = 0;
        for (Claims.Claim claim : Claims.Data.get(server).chunks().values()) if (claim.company.equals(BIG)) claims++;
        int places = Places.count(server, BIG);
        boolean ok = big != null && big.collected.size() == BIG_COLLECTED && claims == BIG_CLAIMS && places == BIG_PLACES;
        report(source, "SELFTEST state save after restart: " + (ok ? "OK" : "FAILED") + " (company " + (big == null ? "missing" : big.collected.size() + " collected")
            + ", claims " + claims + ", places " + places + "; broken " + data.broken() + "/" + Claims.Data.get(server).broken() + ")");
        int vanillaForced = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (long packed : level.getForceLoadedChunks()) {
                if (Claims.at(level, ChunkPos.getX(packed), ChunkPos.getZ(packed)) != null) vanillaForced++;
            }
        }
        report(source, "SELFTEST state loading after restart: chunks held by claims " + Claims.loadedCount() + ", claimed chunks force-loaded the vanilla way " + vanillaForced);
        report(source, "SELFTEST state cleanup: removed " + cleanup(server) + " test companies");
        return 1;
    }

    /** Removes every company the self-tests made, with its claims and places. */
    public static int cleanup(MinecraftServer server) {
        CompanyData data = CompanyData.get(server);
        List<String> gone = new ArrayList<>();
        for (Company company : data.companies().values()) {
            boolean test = company.id.startsWith("selftest_");
            if (!test && !company.members.isEmpty()) {
                test = true;
                for (String m : company.members) if (!m.startsWith(FAKE_PREFIX)) test = false;
            }
            if (test) gone.add(company.id);
        }
        for (String id : gone) {
            data.companies().remove(id);
            Claims.releaseCompany(server, id);
            Places.removeCompany(server, id);
        }
        Companies.save(server);
        return gone.size();
    }

    private static void report(CommandSourceStack source, String text) {
        Siftec.LOGGER.info(text);
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
