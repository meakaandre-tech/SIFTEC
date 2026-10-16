package com.meakaandre.siftec.client.map;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.map.MapGrid;
import com.meakaandre.siftec.map.MapMarker;
import com.meakaandre.siftec.map.MapPayloads;
import com.meakaandre.siftec.map.MapPoi;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Drawing shared by the minimap and the fullscreen map: a view (a centre in the world, a scale and a rectangle on
 * screen), the terrain tiles under it, and the icons of everything on it.
 */
public final class MapDraw {
    /** Purity rings: impure, normal, pure. */
    private static final int[] PURITY = {0xFFD9534F, 0xFFE8B43C, 0xFF5CB85C};
    private static final Map<NodeType, ItemStack> NODE_ITEMS = new EnumMap<>(NodeType.class);
    public static final int PLAYER = 0xFFFFFFFF, MATE = 0xFF5FD4FF, FOG_BACK = 0xFF2A3440;

    private MapDraw() {
    }

    /** A view of the map: world (cx, cz) at the centre of the screen rectangle, {@code scale} screen pixels a block. */
    public record View(double cx, double cz, double scale, int left, int top, int width, int height) {
        public double sx(double x) {
            return left + width / 2.0 + (x - cx) * scale;
        }

        public double sy(double z) {
            return top + height / 2.0 + (z - cz) * scale;
        }

        public double wx(double sx) {
            return cx + (sx - left - width / 2.0) / scale;
        }

        public double wz(double sy) {
            return cz + (sy - top - height / 2.0) / scale;
        }

        public boolean inside(double sx, double sy, int margin) {
            return sx >= left - margin && sx < left + width + margin && sy >= top - margin && sy < top + height + margin;
        }

        /** The regions the view shows. */
        public List<Long> regions() {
            List<Long> out = new ArrayList<>();
            int x0 = Math.floorDiv((int) Math.floor(wx(left)), MapGrid.REGION_BLOCKS), x1 = Math.floorDiv((int) Math.ceil(wx(left + width)), MapGrid.REGION_BLOCKS);
            int z0 = Math.floorDiv((int) Math.floor(wz(top)), MapGrid.REGION_BLOCKS), z1 = Math.floorDiv((int) Math.ceil(wz(top + height)), MapGrid.REGION_BLOCKS);
            for (int rz = z0; rz <= z1; rz++) for (int rx = x0; rx <= x1; rx++) out.add(MapGrid.key(rx, rz));
            return out;
        }
    }

    /** The fog-coloured ground and every known tile in the view, clipped to it. */
    public static void tiles(GuiGraphicsExtractor g, View v) {
        g.fill(v.left, v.top, v.left + v.width, v.top + v.height, FOG_BACK);
        float size = (float) (MapGrid.REGION_BLOCKS * v.scale) / MapGrid.REGION_PX;
        for (long region : v.regions()) {
            Identifier id = ClientTiles.texture(region);
            if (id == null) continue;
            double x = v.sx((double) MapGrid.keyX(region) * MapGrid.REGION_BLOCKS), y = v.sy((double) MapGrid.keyZ(region) * MapGrid.REGION_BLOCKS);
            g.pose().pushMatrix();
            g.pose().translate((float) x, (float) y);
            g.pose().scale(size, size);
            g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, MapGrid.REGION_PX, MapGrid.REGION_PX, MapGrid.REGION_PX, MapGrid.REGION_PX);
            g.pose().popMatrix();
        }
    }

    // ------------------------------------------------------------------------------------------------ things

    public static int markerColour(MapMarker m) {
        return m.death ? 0xFFE8E8E8 : 0xFF000000 | MapMarker.COLOURS[Math.floorMod(m.colour, MapMarker.COLOURS.length)];
    }

    public static Component markerLabel(MapMarker m) {
        if (m.death) return Component.translatable("siftec.map.death_marker");
        return m.name.isEmpty() ? Component.translatable("siftec.map.marker") : Component.literal(m.name);
    }

    public static Component label(MapPoi poi) {
        if (poi.kind() == MapPoi.Kind.NODE || poi.kind() == MapPoi.Kind.WELL) {
            NodeType type = NodeType.byId(poi.sub());
            if (type != null) return NodeBlock.label(type, Purity.values()[Math.clamp(poi.purity(), 0, 2)]);
        }
        return Component.translatable(poi.kind().key());
    }

    /** The icon glyph and tint of a thing that is not a node (nodes are drawn as their ore). */
    public static int icon(MapPoi poi) {
        return switch (poi.kind()) {
            case NODE -> MapIcons.NODE;
            case WELL -> MapIcons.DROP;
            case GEYSER -> MapIcons.GEYSER;
            case CRASH_SITE -> MapIcons.POD;
            case MERCER_SPHERE -> MapIcons.SPHERE;
            case SOMERSLOOP -> MapIcons.LOOP;
            case HUB -> MapIcons.HUB;
            case GATEWAY -> MapIcons.GATE;
            case RADAR_TOWER -> MapIcons.RADAR;
            case TRAIN_STATION -> MapIcons.TRAIN;
            case TRUCK_STATION -> MapIcons.TRUCK;
            case DRONE_PORT -> MapIcons.DRONE;
        };
    }

    public static int colour(MapPoi poi) {
        return switch (poi.kind()) {
            case NODE, WELL -> PURITY[Math.clamp(poi.purity(), 0, 2)];
            case GEYSER -> 0xFFE6D94A;
            case CRASH_SITE -> 0xFFFF7A3D;
            case MERCER_SPHERE -> 0xFFB98CFF;
            case SOMERSLOOP -> 0xFFFF5FA8;
            case HUB, GATEWAY -> 0xFFFFA530;
            default -> 0xFFF0F0F0;
        };
    }

    private static ItemStack nodeItem(NodeType type) {
        return NODE_ITEMS.computeIfAbsent(type, t -> {
            for (NodeLook look : NodeLook.all()) {
                if (look.type() != t) continue;
                for (String id : new String[]{look.drop(), look.clump(), look.node()}) {
                    if (id.isEmpty()) continue;
                    Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                    if (item != Items.AIR) return new ItemStack(item);
                }
            }
            return ItemStack.EMPTY;
        });
    }

    /** Draws a thing centred on x, y: a node as its ore over a purity ring, anything else as its icon. */
    public static void poi(GuiGraphicsExtractor g, MapPoi poi, int x, int y) {
        NodeType type = poi.kind() == MapPoi.Kind.NODE ? NodeType.byId(poi.sub()) : null;
        ItemStack stack = type == null ? ItemStack.EMPTY : nodeItem(type);
        if (!stack.isEmpty()) {
            MapIcons.centred(g, MapIcons.DISC, x, y, 0xE0202020);
            MapIcons.centred(g, MapIcons.RING, x, y, colour(poi));
            g.pose().pushMatrix();
            g.pose().translate(x - 4.5f, y - 4.5f);
            g.pose().scale(9 / 16f, 9 / 16f);
            g.fakeItem(stack, 0, 0);
            g.pose().popMatrix();
            return;
        }
        if (poi.kind() == MapPoi.Kind.WELL) {
            MapIcons.centred(g, MapIcons.DISC, x, y, 0xE0202020);
            MapIcons.centred(g, MapIcons.RING, x, y, colour(poi));
            MapIcons.centred(g, MapIcons.DROP, x, y, "oil".equals(poi.sub()) ? 0xFF5A5148 : 0xFFBFE6F2);
            return;
        }
        MapIcons.centred(g, icon(poi), x, y, colour(poi));
    }

    public static void marker(GuiGraphicsExtractor g, MapMarker m, int x, int y) {
        MapIcons.centred(g, m.death ? MapIcons.SKULL : m.icon, x, y, markerColour(m));
    }

    /** A company player: an arrow turned the way they face, in the company colour. */
    public static void mate(GuiGraphicsExtractor g, MapPayloads.Mate mate, float x, float y) {
        MapIcons.turned(g, MapIcons.ARROW, x, y, (float) Math.toRadians(mate.yaw() + 180), MATE);
    }

    /** The player themself. */
    public static void self(GuiGraphicsExtractor g, float x, float y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        MapIcons.turned(g, MapIcons.ARROW, x, y, (float) Math.toRadians(mc.player.getYRot() + 180), PLAYER);
    }

    public static String distance(double blocks) {
        return blocks >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000) : Math.round(blocks) + " m";
    }
}
