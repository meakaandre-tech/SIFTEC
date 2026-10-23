package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.sink.Shop;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Part of the claim and state selftest ({@code /siftec selftest state setup}): Build Gun buildings and claims, and the
 * per-company unlocks of buildings and belt speed. Every line starts "SELFTEST state gun" with PASS or FAIL.
 * <p>
 * Two companies, OWN and FOE, each hold one chunk; a third chunk between them is unclaimed. A Storage Container is
 * checked and built in each, across a claim's border, and through the gun itself; then taken down by its own company,
 * refused to the other, and a building nobody owns is taken down only where the land lets the player build.
 * Then every building's unlock is checked per company, and the self-moving belt speed of two companies.
 */
public final class ClaimGunTests {
    public static final String OWN = "selftest_gun_own", FOE = "selftest_gun_foe", FRESH = "selftest_gun_fresh";
    /** The test chunks: FOE at (CX, CZ), unclaimed at CX + 1, OWN at CX + 2. Well away from the other selftests. */
    private static final int CX = 330, CZ = 200, Y = 200;
    private static final String BUILDING = "storage_container";
    private static int passed, failed;

    private ClaimGunTests() {
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST state gun " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static Company company(CompanyData data, String id, String member) {
        Company c = new Company();
        c.id = id;
        c.name = id;
        c.members.add(member);
        c.owner = member;
        data.companies().put(id, c);
        data.setDirty();
        return c;
    }

    private static String reason(Buildings.Check check) {
        return check.ok() ? "fits" : check.reason != null ? check.reason : check.message().getString();
    }

    /** A placement of the test building facing south with its box starting at block (x, z) of the platform. */
    private static Placement at(BuildingDef def, BuildingTemplate t, int x, int z) {
        // facing south the box runs from anchor.x - maxX (right is west) to anchor.x - minX, and anchor.z to anchor.z + depth - 1
        return new Placement(def, new BlockPos(x + t.maxX, Y, z), Direction.SOUTH, false);
    }

    private static void aimAt(FakePlayer fake, BlockPos target) {
        Vec3 eye = fake.getEyePosition(), to = Vec3.atCenterOf(target);
        double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        fake.setYRot(yaw);
        fake.setXRot(pitch);
        fake.yRotO = yaw;
        fake.xRotO = pitch;
        fake.setYHeadRot(yaw);
    }

    private static void giveCost(FakePlayer fake, BuildingDef def) {
        fake.getInventory().clearContent();
        fake.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BUILD_GUN.get()));
        int slot = 1;
        for (Milestone.Cost cost : def.cost()) if (cost.present()) fake.getInventory().setItem(slot++, cost.icon().copyWithCount(cost.count()));
    }

    private static int costItems(FakePlayer fake, BuildingDef def) {
        int n = 0;
        for (Milestone.Cost cost : def.cost()) {
            if (!cost.present()) continue;
            for (int i = 0; i < fake.getInventory().getContainerSize(); i++) if (cost.matches(fake.getInventory().getItem(i))) n += fake.getInventory().getItem(i).getCount();
        }
        return n;
    }

    private static int costTotal(BuildingDef def) {
        int n = 0;
        for (Milestone.Cost cost : def.cost()) if (cost.present()) n += cost.count();
        return n;
    }

    private static @Nullable ControllerBlockEntity controllerOf(ServerLevel level, Placement p, BuildingTemplate t) {
        return level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity c && BUILDING.equals(c.buildingId) ? c : null;
    }

    public static void run(CommandSourceStack source, ServerLevel level, Function<Integer, String> fake) {
        passed = failed = 0;
        CompanyData data = CompanyData.get(level.getServer());
        Company own = company(data, OWN, fake.apply(10)), foe = company(data, FOE, fake.apply(11));
        BuildingDef def = Buildings.def(BUILDING);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (def == null || t == null) {
            expect(source, "the " + BUILDING + " building and its template exist", false, "");
            return;
        }
        own.done.add(def.unlock());
        foe.done.add(def.unlock());
        int x0 = CX << 4, z0 = CZ << 4;
        BuildingTests.platform(level, x0 - 16, z0 - 16, x0 + 63, z0 + 31, Y, 12);
        Claims.claim(level, foe, new BlockPos(x0 + 8, Y, z0 + 8), 0, true, 1);
        Claims.claim(level, own, new BlockPos(x0 + 40, Y, z0 + 8), 0, true, 1);
        int width = t.maxX - t.minX + 1;
        expect(source, "claims set up: FOE holds the first chunk, the next is free, OWN holds the third; the building fits in a chunk",
            Claims.at(level, CX, CZ) != null && Claims.at(level, CX, CZ).company.equals(FOE) && Claims.at(level, CX + 1, CZ) == null
                && Claims.at(level, CX + 2, CZ) != null && Claims.at(level, CX + 2, CZ).company.equals(OWN) && width <= 12 && t.depth <= 12,
            width + " wide, " + t.depth + " deep");

        // ---------------------------------------------------------------- placing: the land under the whole box decides
        Placement inFoe = at(def, t, x0 + 2, z0 + 2), free = at(def, t, x0 + 18, z0 + 2), inOwn = at(def, t, x0 + 34, z0 + 2);
        Placement border = at(def, t, x0 + 16 - width / 2 - 1, z0 + 2);
        expect(source, "placing: OWN may not build on FOE's claim", "siftec.prefab.claimed".equals(Buildings.check(level, inFoe, own, null, false).reason),
            reason(Buildings.check(level, inFoe, own, null, false)));
        expect(source, "placing: FOE may build on its own claim", Buildings.check(level, inFoe, foe, null, false).ok(), reason(Buildings.check(level, inFoe, foe, null, false)));
        expect(source, "placing: anyone may build on unclaimed land", Buildings.check(level, free, own, null, false).ok() && Buildings.check(level, free, foe, null, false).ok(),
            reason(Buildings.check(level, free, own, null, false)));
        AABB borderBox = border.bounds(t);
        expect(source, "placing: a building reaching over FOE's border from free land is refused to OWN",
            ((int) borderBox.minX) >> 4 == CX && ((int) borderBox.maxX - 1) >> 4 == CX + 1 && "siftec.prefab.claimed".equals(Buildings.check(level, border, own, null, false).reason),
            reason(Buildings.check(level, border, own, null, false)) + ", box x " + (int) borderBox.minX + ".." + ((int) borderBox.maxX - 1));
        expect(source, "placing: FOE may not build on OWN's claim, a creative player may", "siftec.prefab.claimed".equals(Buildings.check(level, inOwn, foe, null, false).reason)
            && Buildings.check(level, inOwn, foe, null, true).ok(), reason(Buildings.check(level, inOwn, foe, null, false)));

        // ---------------------------------------------------------------- the gun itself, for a survival player
        FakePlayer gunner = FakePlayer.get(level);
        gunner.setGameMode(GameType.SURVIVAL);
        giveCost(gunner, def);
        BlockPos stand = new BlockPos(x0 + 24, Y, z0 + 14);
        gunner.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        int before = costItems(gunner, def);
        BuildGun.select(gunner, BUILDING, false, inFoe);
        BuildGun.act(gunner, own);
        expect(source, "the gun: OWN's player cannot build on FOE's claim, and keeps the cost", controllerOf(level, inFoe, t) == null && costItems(gunner, def) == before,
            "cost items " + costItems(gunner, def) + " of " + before);
        BuildGun.select(gunner, BUILDING, false, inOwn);
        BuildGun.act(gunner, own);
        ControllerBlockEntity ownBuilding = controllerOf(level, inOwn, t);
        expect(source, "the gun: OWN's player builds on OWN's claim, paying the cost, and the building is OWN's",
            ownBuilding != null && OWN.equals(ownBuilding.companyId) && costItems(gunner, def) == before - costTotal(def),
            ownBuilding == null ? "not built" : "owner " + ownBuilding.companyId + ", cost items left " + costItems(gunner, def));
        // the claim's own use-block check lets the gun through: the gun decides about the land itself
        BlockHitResult foeGround = new BlockHitResult(Vec3.atCenterOf(new BlockPos(x0 + 8, Y - 1, z0 + 8)), Direction.UP, new BlockPos(x0 + 8, Y - 1, z0 + 8), false);
        InteractionResult withGun = UseBlockCallback.EVENT.invoker().interact(gunner, level, InteractionHand.MAIN_HAND, foeGround);
        gunner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        InteractionResult withStick = UseBlockCallback.EVENT.invoker().interact(gunner, level, InteractionHand.MAIN_HAND, foeGround);
        gunner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BUILD_GUN.get()));
        expect(source, "the gun: using it on a block in a foreign claim reaches the gun (the claim refuses other items there)",
            withGun != InteractionResult.FAIL && withStick == InteractionResult.FAIL, "gun " + withGun + ", stick " + withStick);

        // ---------------------------------------------------------------- dismantling
        if (ownBuilding != null) {
            expect(source, "dismantle: FOE may not take down OWN's building", BuildGun.mayDismantle(gunner, foe, ownBuilding) != null, "");
            expect(source, "dismantle: OWN may take down its own building", BuildGun.mayDismantle(gunner, own, ownBuilding) == null, "");
            gunner.getInventory().clearContent();
            gunner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BUILD_GUN.get()));
            aimAt(gunner, ownBuilding.getBlockPos());
            BuildGun.select(gunner, BUILDING, true, null);
            BuildGun.act(gunner, foe);
            boolean standing = controllerOf(level, inOwn, t) != null;
            BuildGun.act(gunner, own);
            boolean gone = controllerOf(level, inOwn, t) == null && !BuildingIndex.isMember(level, inOwn.at(t.controller));
            expect(source, "dismantle: through the gun FOE's player is refused, OWN's takes it down with the full refund",
                standing && gone && costItems(gunner, def) == costTotal(def), "standing after FOE " + standing + ", gone after OWN " + gone + ", refund " + costItems(gunner, def));
        }
        // a building nobody owns (built by a machine, or from before companies) comes down only where the land allows
        Buildings.place(level, inFoe, null, null, true);
        Buildings.place(level, free, null, null, true);
        ControllerBlockEntity nobodyInFoe = controllerOf(level, inFoe, t), nobodyFree = controllerOf(level, free, t);
        expect(source, "dismantle: an unowned building on FOE's claim is refused to OWN and allowed to FOE",
            nobodyInFoe != null && nobodyInFoe.companyId.isEmpty() && BuildGun.mayDismantle(gunner, own, nobodyInFoe) != null && BuildGun.mayDismantle(gunner, foe, nobodyInFoe) == null,
            nobodyInFoe == null ? "not built" : "");
        expect(source, "dismantle: an unowned building on free land may be taken down by anyone",
            nobodyFree != null && BuildGun.mayDismantle(gunner, own, nobodyFree) == null && BuildGun.mayDismantle(gunner, foe, nobodyFree) == null, nobodyFree == null ? "not built" : "");
        gunner.setGameMode(GameType.CREATIVE);
        expect(source, "dismantle: a creative player may take down anything", nobodyInFoe != null && BuildGun.mayDismantle(gunner, own, nobodyInFoe) == null, "");
        gunner.setGameMode(GameType.SURVIVAL);
        if (nobodyInFoe != null) Buildings.dismantle(level, nobodyInFoe);
        if (nobodyFree != null) Buildings.dismantle(level, nobodyFree);
        gunner.getInventory().clearContent();

        // ---------------------------------------------------------------- per-company unlocks of buildings
        Company fresh = company(data, FRESH, fake.apply(12));
        List<String> unknown = new ArrayList<>(), notLocked = new ArrayList<>(), notOpened = new ArrayList<>();
        for (BuildingDef b : Buildings.all()) {
            if (b.dev() || b.unlock().isEmpty()) continue;
            // the Billboard Set's billboards are unlocked in the AWESOME Shop
            boolean shop = b.unlock().startsWith("shop:");
            if (shop ? Shop.entry(b.unlock().substring(5)) == null : Milestones.get(b.unlock()) == null) unknown.add(b.id() + ":" + b.unlock());
            if (!Buildings.locked(b, fresh)) notLocked.add(b.id());
            Company one = new Company();
            one.id = "selftest_gun_probe";
            if (shop) one.shop.add(b.unlock().substring(5));
            else one.done.add(b.unlock());
            if (Buildings.locked(b, one)) notOpened.add(b.id());
        }
        expect(source, "unlocks: every building's unlock is a known milestone, Wormhole phase or MAM node", unknown.isEmpty(), String.join(", ", unknown));
        expect(source, "unlocks: a new company has every building with an unlock locked, and its unlock opens it", notLocked.isEmpty() && notOpened.isEmpty(),
            "not locked " + notLocked + ", not opened " + notOpened);
        expect(source, "unlocks: the Build Gun refuses a locked building to a company that lacks it, though another company has it",
            "siftec.build_gun.locked".equals(Buildings.check(level, free, fresh, null, false).reason) && Buildings.check(level, free, own, null, false).ok(),
            reason(Buildings.check(level, free, fresh, null, false)));
        fresh.done.add(def.unlock());
        expect(source, "unlocks: once the company has the unlock, any member's gun builds it", Buildings.check(level, free, fresh, null, false).ok(),
            reason(Buildings.check(level, free, fresh, null, false)));
        BuildingDef gateway = Buildings.def("wormhole_gateway");
        if (gateway != null) {
            Company noGate = new Company();
            noGate.id = "selftest_gun_probe";
            expect(source, "unlocks: the Wormhole Gateway is locked for a company without HUB Upgrade 6", Buildings.locked(gateway, noGate) && gateway.unlock().equals("hub_upgrade_6"), "");
        }

        // ---------------------------------------------------------------- per-company belt speed
        Company slow = new Company(), fast = new Company(), none = new Company();
        slow.done.add("hub_upgrade_4");
        fast.done.add("hub_upgrade_4");
        fast.done.add("logistics_mk2");
        fast.done.add("logistics_mk3");
        expect(source, "belts: each company's self-moving speed follows its own Logistics milestones (none 0, Logistics 60, Mk.3 270 items/min)",
            SelfMovingBelts.itemsPerMinute(none) == 0 && SelfMovingBelts.itemsPerMinute(slow) == 60 && SelfMovingBelts.itemsPerMinute(fast) == 270
                && SelfMovingBelts.rpm(270) == 108f,
            SelfMovingBelts.itemsPerMinute(none) + "/" + SelfMovingBelts.itemsPerMinute(slow) + "/" + SelfMovingBelts.itemsPerMinute(fast));
        // a machine nobody placed by hand belongs to the claim it stands in; one stamped with a company stays that company's
        BlockPos unowned = new BlockPos(x0 + 40, Y, z0 + 12), stamped = unowned.east();
        level.setBlockAndUpdate(unowned, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(stamped, Blocks.CHEST.defaultBlockState());
        var be1 = level.getBlockEntity(unowned);
        var be2 = level.getBlockEntity(stamped);
        if (be2 != null) be2.setAttached(Ownership.OWNER, FOE);
        Company of1 = be1 == null ? null : Ownership.of(be1), of2 = be2 == null ? null : Ownership.of(be2);
        expect(source, "belts: an unowned belt on OWN's claim runs at OWN's speed, one FOE placed there at FOE's",
            of1 != null && of1.id.equals(OWN) && of2 != null && of2.id.equals(FOE), (of1 == null ? "none" : of1.id) + " / " + (of2 == null ? "none" : of2.id));
        level.setBlockAndUpdate(unowned, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(stamped, Blocks.AIR.defaultBlockState());

        // the platform force-loaded its chunks; let them go so the restart check only sees what claims hold
        for (int cx = (x0 - 16) >> 4; cx <= (x0 + 63) >> 4; cx++)
            for (int cz = (z0 - 16) >> 4; cz <= (z0 + 31) >> 4; cz++) level.setChunkForced(cx, cz, false);
        report(source, (failed == 0 ? "OK" : "FAILED") + ": " + passed + " passed, " + failed + " failed");
    }
}
