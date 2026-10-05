package com.meakaandre.siftec.command;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Operator commands for testing: {@code /siftec node find <type>} and {@code /siftec node tp <type>}. */
public final class SiftecCommands {
    private SiftecCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("node")
                    .then(Commands.literal("find").then(type().executes(context -> find(context, false))))
                    .then(Commands.literal("tp").then(type().executes(context -> find(context, true)))))
        ));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> type() {
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
        Optional<Node> found = NodeMap.nearest(level, from.x, from.z, type, 64);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("siftec.scanner.none", Component.translatable(type.key())));
            return 0;
        }
        Node node = found.get();
        int distance = (int) Math.round(node.distanceTo(from.x, from.z));
        source.sendSuccess(() -> Component.translatable(
            "siftec.command.found", NodeBlock.label(type, found), node.x(), node.z(), distance
        ), false);
        if (teleport && source.getEntity() instanceof ServerPlayer player) {
            // loading the chunk places the node; stand next to it, above the ground
            level.getChunk(node.x() >> 4, node.z() >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, node.x() + 2, node.z()) + 2;
            player.teleportTo(level, node.x() + 2.5, y, node.z() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        }
        return 1;
    }
}
