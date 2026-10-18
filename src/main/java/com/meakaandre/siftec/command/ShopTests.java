package com.meakaandre.siftec.command;

import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Paint;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.cosmetic.Banners;
import com.meakaandre.siftec.cosmetic.Emotes;
import com.meakaandre.siftec.cosmetic.GoldenCart;
import com.meakaandre.siftec.cosmetic.Outfits;
import com.meakaandre.siftec.cosmetic.ShopSignBlockEntity;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.sink.Shop;
import com.meakaandre.siftec.sink.ShopMenu;
import com.zurrtum.create.AllBlocks;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /siftec selftest polish}, part 1: the AWESOME Shop's cosmetics. Every entry gives something the pack has; signs
 * hold their text; the Billboard Set unlocks the billboards and their sign; foundations snap to the grid and take
 * materials and floor patterns; holiday sets are seasonal; emotes, helmet skins, suit trims, the company banner, the
 * Golden Cart Assembler and the Boom Box with its tapes all work. All on the polish test site.
 */
final class ShopTests {
    private ShopTests() {
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        PolishTests.expect(source, "1: " + what, ok, detail);
    }

    private static Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id));
    }

    private static Company fresh(ServerLevel level, String id) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = id;
        c.name = "Selftest Shop";
        data.companies().put(id, c);
        return c;
    }

    static void run(CommandSourceStack source, ServerLevel level, int x, int y, int z) {
        Company poor = fresh(level, "selftest_shop_poor"), rich = fresh(level, "selftest_shop_rich");
        for (Shop.Entry e : Shop.entries()) Shop.unlock(rich, e);

        // every entry: something the pack has, shown as itself
        List<String> barriers = new ArrayList<>();
        for (Shop.Entry e : Shop.entries()) if (ShopMenu.icon(e) == Items.BARRIER) barriers.add(e.id());
        expect(source, "every Shop entry gives something the pack has (nothing shows as a barrier)", barriers.isEmpty(), barriers.toString());
        String[] kinds = {"sign", "unlock", "material", "pattern", "emote", "helmet", "trim", "banner", "golden_cart", "boom_box", "tape"};
        List<String> missing = new ArrayList<>();
        for (String k : kinds) if (Shop.entries().stream().noneMatch(e -> e.kind().equals(k))) missing.add(k);
        boolean holidays = Shop.entries().stream().anyMatch(e -> e.category().equals("Holiday sets"));
        expect(source, "the tab sells signs and billboards, materials, floor patterns, holiday sets, emotes, helmet skins, suit trims, the banner designer, the Golden Cart Assembler and the Boom Box with tapes",
            missing.isEmpty() && holidays, "missing " + missing);

        // holiday sets: on sale in their month only; once bought, kept
        Shop.Entry tree = Shop.entry("decor_ficsmas_tree");
        int month = java.time.LocalDate.now().getMonthValue();
        expect(source, "a holiday set (the FICSMAS Tree) is on sale in December only",
            tree != null && tree.months().equals(List.of(12)) && tree.inSeason() == (month == 12) && tree.owned(rich), tree == null ? "missing" : tree.months().toString());

        // signs: a Display Sign on a wall holds its text, as a sign does
        BlockPos wall = new BlockPos(x, y, z), at = wall.south();
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(at, ModBlocks.DISPLAY_SIGN.get().defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        boolean wrote = false;
        if (level.getBlockEntity(at) instanceof ShopSignBlockEntity sign) {
            Component[] lines = {Component.literal("IRON"), Component.literal("PLATES"), Component.empty(), Component.empty()};
            sign.setText(new SignText(List.of(lines), List.of(lines), DyeColor.ORANGE, true), SignTextSlot.FRONT);
            SignText read = sign.getText(SignTextSlot.FRONT);
            wrote = read.getMessages(false).get(1).getString().equals("PLATES") && read.hasGlowingText() && read.getColor() == DyeColor.ORANGE;
        }
        expect(source, "a Display Sign hangs on a wall and holds its text (coloured and glowing)", wrote, level.getBlockState(at).toString());

        // the Billboard Set unlocks the billboards; a billboard's sign stands at the foot of its face
        BuildingDef small = Buildings.def("billboard_small");
        boolean lockedPoor = small != null && Buildings.locked(small, poor), openRich = small != null && !Buildings.locked(small, rich);
        Placement bp = small == null ? null : new Placement(small, new BlockPos(x + 15, y, z), Direction.NORTH, false);
        boolean billboard = bp != null && Buildings.place(level, bp, rich, null, true).ok()
            && level.getBlockState(bp.at(0, 2, 0)).is(ModBlocks.BILLBOARD_SMALL_SIGN.get()) && level.getBlockEntity(bp.at(0, 2, 0)) instanceof ShopSignBlockEntity;
        if (bp != null && level.getBlockEntity(bp.at(0, 2, 0)) instanceof ShopSignBlockEntity board) {
            Component[] lines = {Component.literal("FICSIT"), Component.literal("Inc."), Component.empty(), Component.empty()};
            board.setText(new SignText(List.of(lines), List.of(lines), DyeColor.BLACK, false), SignTextSlot.FRONT);
        }
        expect(source, "the Billboard Set unlocks the billboards (a company without it cannot build them); a billboard's sign is at the foot of its face",
            lockedPoor && openRich && billboard, "locked for one without it " + lockedPoor + ", open with it " + openRich + ", built with its sign " + billboard);

        // foundations: snapped to the 8-block grid, painted with a material and a floor pattern
        BuildingDef f1 = Buildings.def("foundation_1m");
        if (f1 == null) {
            expect(source, "the Foundations tab has its foundations", false, "");
            return;
        }
        Placement snapped = BuildGun.snapToGrid(new Placement(f1, new BlockPos(x + 37, y, z + 13), Direction.NORTH, false));
        BuildingTemplate t = BuildingTemplate.of(f1);
        BlockPos a = snapped.at(t.minX, 0, 0), b = snapped.at(t.maxX, 0, t.depth - 1);
        int cx = Math.min(a.getX(), b.getX()), cz = Math.min(a.getZ(), b.getZ());
        expect(source, "a foundation snaps to the 8-block grid", Math.floorMod(cx, 8) == 0 && Math.floorMod(cz, 8) == 0 && t.maxX - t.minX == 7 && t.depth == 8,
            "corner " + cx + ", " + cz);
        boolean placed = Buildings.place(level, snapped, rich, null, true).ok();
        ControllerBlockEntity c = level.getBlockEntity(snapped.at(t.controller)) instanceof ControllerBlockEntity ce ? ce : null;
        if (!placed || c == null) {
            expect(source, "a foundation goes down", false, "");
            return;
        }
        Buildings.repaint(level, c, Paint.NONE.withMaterial("asphalt").withPattern("solid_line"));
        // the line runs down the middle two columns (across -1 and 0), the material elsewhere
        BlockState line = level.getBlockState(snapped.at(-1, 0, 4)), side = level.getBlockState(snapped.at(-4, 0, 4));
        boolean painted = line.is(block("minecraft:yellow_concrete")) && side.is(block("minecraft:polished_blackstone"));
        Buildings.repaint(level, c, Paint.NONE);
        boolean back = level.getBlockState(snapped.at(-4, 0, 4)).is(block("minecraft:light_gray_concrete")) && level.getBlockState(snapped.at(-1, 0, 4)).is(block("minecraft:light_gray_concrete"));
        expect(source, "the Customizer gives a foundation a material (Asphalt) and a floor pattern (Solid Line), and takes them off again", painted && back,
            "line " + line + ", side " + side + ", back as built " + back);
        BuildingDef roof = Buildings.def("roof_4m");
        Placement rp = roof == null ? null : BuildGun.snapToGrid(new Placement(roof, new BlockPos(x + 60, y, z + 13), Direction.NORTH, false));
        boolean glass = false;
        if (rp != null && Buildings.place(level, rp, rich, null, true).ok() && level.getBlockEntity(rp.at(BuildingTemplate.of(roof).controller)) instanceof ControllerBlockEntity rc) {
            Buildings.repaint(level, rc, Paint.NONE.withMaterial("glass_roof"));
            glass = level.getBlockState(rp.at(-4, 3, 7)).is(Blocks.GLASS) && level.getBlockState(rp.at(-4, 0, 0)).is(Blocks.GLASS);
        }
        expect(source, "a roof rises four blocks over its 8 and takes the Glass Roof material", glass, rp == null ? "no roof" : level.getBlockState(rp.at(-4, 3, 7)).toString());

        // emotes: the Wave is free, the rest are bought
        expect(source, "the Wave emote is free; the others need buying; /emote knows them all",
            Shop.owns(poor, "emote:wave") && !Shop.owns(poor, "emote:dance") && Shop.owns(rich, "emote:dance") && Shop.emotes().size() >= 6 && Emotes.TICKS > 0,
            Shop.emotes().toString());

        // helmet skins and suit trims, on what a player wears
        FakePlayer fake = FakePlayer.get(level);
        fake.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        fake.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        boolean gold = Outfits.helmet(fake, "gold") && fake.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.EQUIPPABLE).assetId()
            .map(k -> k.identifier().getPath().equals("gold")).orElse(false);
        boolean own = Outfits.helmet(fake, "") && fake.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.EQUIPPABLE).assetId()
            .map(k -> k.identifier().getPath().equals("iron")).orElse(false);
        int trimmed = Outfits.trim(fake, "quartz");
        boolean trim = trimmed == 2 && fake.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.TRIM);
        Outfits.trim(fake, "");
        boolean untrimmed = !fake.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.TRIM);
        expect(source, "a helmet skin draws an iron helmet in gold (and back); a suit trim goes on every piece worn (and off)",
            gold && own && trim && untrimmed, "gold " + gold + ", back " + own + ", trimmed " + trimmed + " pieces, taken off " + untrimmed);
        fake.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        fake.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);

        // the company banner: kept by the company, copies named for it
        ItemStack banner = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:orange_banner")));
        rich.banner = Banners.write(level.getServer(), banner);
        ItemStack copy = Banners.read(level.getServer(), rich);
        expect(source, "the banner designer keeps a banner as the company's and hands out copies named for it",
            copy.is(banner.getItem()) && copy.getHoverName().getString().contains("Selftest Shop") && Banners.read(level.getServer(), poor).isEmpty(), copy.toString());

        // the Golden Cart Assembler: a Cart Assembler state, switched on and off, still Create's block
        BlockPos cart = new BlockPos(x, y, z + 20);
        level.setBlock(cart, AllBlocks.CART_ASSEMBLER.defaultBlockState(), Block.UPDATE_ALL);
        boolean plain = GoldenCart.is(level.getBlockState(cart)) && !level.getBlockState(cart).getValue(GoldenCart.GOLDEN);
        boolean golden = GoldenCart.toggle(level, cart) && level.getBlockState(cart).getValue(GoldenCart.GOLDEN) && level.getBlockState(cart).is(AllBlocks.CART_ASSEMBLER);
        expect(source, "a Cart Assembler is plain when placed; the Customizer turns it golden, and it stays a Cart Assembler", plain && golden, level.getBlockState(cart).toString());

        // the Boom Box plays a tape
        BlockPos box = new BlockPos(x + 3, y, z + 20);
        level.setBlock(box, ModBlocks.BOOM_BOX.get().defaultBlockState(), Block.UPDATE_ALL);
        boolean playing = false;
        ItemStack tape = new ItemStack(ModItems.TAPES.get("tape_cat"));
        if (level.getBlockEntity(box) instanceof JukeboxBlockEntity jukebox) {
            jukebox.setTheItem(tape.copy());
            playing = jukebox.getSongPlayer().isPlaying() && level.getBlockState(box).getValue(JukeboxBlock.HAS_RECORD);
        }
        Shop.Entry starter = Shop.entry("tape_cat"), second = Shop.entry("tape_blocks");
        expect(source, "the Boom Box plays a cassette tape; the first tape is free, the others are bought",
            playing && tape.has(DataComponents.JUKEBOX_PLAYABLE) && starter != null && starter.owned(poor) && second != null && !second.owned(poor),
            "playing " + playing + ", " + level.getBlockState(box));
    }
}
