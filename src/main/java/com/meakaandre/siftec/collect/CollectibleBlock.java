package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Mercer Sphere, Somersloop or crash site pod in the world. It stays where it is: each company can collect it once,
 * gets the item when it does, and finds the MAM tree it starts open from then on. A pod first asks for what it wants
 * (parts carried, or a powered grid wired to its connector).
 */
public class CollectibleBlock extends Block {
    private static final VoxelShape SPHERE = Block.box(3, 3, 3, 13, 13, 13), SLOOP = Block.box(3, 0, 3, 13, 9, 13), POD = Block.box(0, 0, 0, 16, 16, 16);
    public final Collectible type;

    public CollectibleBlock(Collectible type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (type) {
            case MERCER_SPHERE -> SPHERE;
            case SOMERSLOOP -> SLOOP;
            case CRASH_SITE -> POD;
        };
    }

    /** The key a company remembers it by: where it is, x and z. */
    public static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getZ();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) tryCollect(server, pos, type);
        return InteractionResult.SUCCESS;
    }

    /** A player tries to collect the one at this spot. True if the company got it now. */
    public static boolean tryCollect(ServerPlayer server, BlockPos pos, Collectible type) {
        Company company = Companies.of(server);
        String key = key(pos);
        if (company.collected.contains(key)) {
            server.sendOverlayMessage(Component.translatable("siftec.collect.already"));
            return false;
        }
        if (type == Collectible.CRASH_SITE && !server.hasInfiniteMaterials()) {
            // some pods open freely; the rest want parts or power, and want more the further they landed from spawn
            CrashSites.Requirement need = CrashSites.requirement(server.level(), pos);
            switch (need.kind()) {
                case PARTS -> {
                    var cost = need.cost();
                    if (cost != null && cost.carried(server.getInventory()) < cost.count()) {
                        server.sendOverlayMessage(Component.translatable("siftec.crash.needs", cost.count(), cost.label()));
                        return false;
                    }
                    if (cost != null) cost.take(server.getInventory(), cost.count());
                }
                case POWER -> {
                    CrashSites.placeConnector(server.level(), pos);
                    if (!CrashSites.powered(server.level(), pos, need.mw())) {
                        server.sendOverlayMessage(Component.translatable("siftec.crash.needs_power", need.mw(),
                            Power.fmt(Power.su((double) need.mw())), Power.fmt(CrashSites.spareSu(server.level(), pos))));
                        return false;
                    }
                }
                case FREE -> {
                }
            }
        }
        collect(server, company, key, type);
        if (type == Collectible.CRASH_SITE && CrashSites.hasSomersloop(server.level(), pos)) {
            server.getInventory().placeItemBackInInventory(new ItemStack(ModItems.PARTS.get("somersloop")), Prediction.SERVER_ONLY);
            server.sendSystemMessage(Component.translatable("siftec.crash.somersloop"));
            Collectibles.startTree(server, company, Collectible.SOMERSLOOP);
        }
        return true;
    }

    /** The company collects it: remembered, the item given, its MAM tree started. */
    static void collect(ServerPlayer player, Company company, String key, Collectible type) {
        company.collected.add(key);
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Siftec.id(type.itemId())));
        player.sendOverlayMessage(Component.translatable("siftec.collect.got", stack.getItemName()));
        player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
        Collectibles.startTree(player, company, type);
        Companies.save(player.level().getServer());
    }
}
