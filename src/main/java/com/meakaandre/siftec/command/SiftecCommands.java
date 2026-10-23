package com.meakaandre.siftec.command;

import com.meakaandre.siftec.SelfTestHooks;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Set;

/** Operator commands for testing: {@code /siftec node find <type>} and {@code /siftec node tp <type>}. */
public final class SiftecCommands {
    /** How far /siftec node looks, in the node map's cells of 128 blocks. */
    private static final int SEARCH_CELLS = 64;
    /** Blocks above and below a cave node searched for a floor to stand on. */
    private static final int CAVE_FLOOR_SEARCH = 32;
    private SiftecCommands() {
    }

    public static void register() {
        SelfTestHooks.run(SelfTestHooks.Stage.COMMANDS);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("node")
                    .then(Commands.literal("find").then(type().executes(context -> find(context, false))))
                    .then(Commands.literal("tp").then(type().executes(context -> find(context, true)))))
        ));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> type() {
        return Commands.argument("type", StringArgumentType.word()).suggests((context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(NodeType.values()).map(NodeType::id), builder));
    }

    private static int find(CommandContext<CommandSourceStack> context, boolean teleport) {
        CommandSourceStack source = context.getSource();
        NodeType type = NodeType.byId(StringArgumentType.getString(context, "type"));
        if (type == null) {
            source.sendFailure(Component.translatable("siftec.command.unknown_type"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Vec3 from = source.getPosition();
        // searched on the node map's worker thread; the answer comes when it is done
        NodeMap.nearestAsync(level, from.x, from.z, type, SEARCH_CELLS, found -> {
            if (found.isEmpty()) {
                source.sendFailure(Component.translatable("siftec.scanner.none", Component.translatable(type.key())));
                return;
            }
            Node node = found.get();
            int distance = (int) Math.round(node.distanceTo(from.x, from.z));
            source.sendSuccess(() -> Component.translatable(
                "siftec.command.found", NodeBlock.label(type, found), node.x(), node.z(), distance
            ), false);
            if (teleport && source.getEntity() instanceof ServerPlayer player) {
                // loading the chunk places the node; stand next to it, on the ground
                level.getChunk(node.x() >> 4, node.z() >> 4);
                for (int[] spot : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {0, 0}}) {
                    int x = node.x() + spot[0], z = node.z() + spot[1];
                    int y = node.y() != Node.SURFACE ? caveFloor(level, x, node.y(), z) : level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    if (y <= level.getMinY() + 1) continue;
                    player.teleportTo(level, x + 0.5, y + (node.y() != Node.SURFACE ? 1 : 0), z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
                    return;
                }
                // no ground anywhere next to it: do not drop the player into the void
                source.sendFailure(Component.literal("No ground next to that node to stand on"));
            }
        });
        return 1;
    }

    /** The floor near a cave height with room to stand above it, or the world's bottom if there is none. */
    private static int caveFloor(ServerLevel level, int x, int around, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int d = 0; d <= CAVE_FLOOR_SEARCH; d++) {
            for (int y : new int[]{around - d, around + d}) {
                if (!level.getBlockState(pos.set(x, y, z)).isAir() && level.getBlockState(pos.set(x, y + 1, z)).isAir() && level.getBlockState(pos.set(x, y + 2, z)).isAir()) return y;
            }
        }
        return level.getMinY();
    }
}
