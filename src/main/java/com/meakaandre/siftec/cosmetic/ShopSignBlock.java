package com.meakaandre.siftec.cosmetic;

import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The AWESOME Shop's signs (Signs and billboards): a Label Sign or Display Sign hung on a wall, and the sign at the foot
 * of a billboard's face. Written on as any sign is (use it to write; dye and glow ink colour it), drawn as a Create
 * panel with the text fitted to its face ({@code client.ShopSignRenderer}). A billboard's sign is part of the billboard
 * building and its text fills the whole board.
 */
public class ShopSignBlock extends WallSignBlock {
    /** The face the text fills, in blocks, and where its middle is from the middle of the sign's cell (right, up). */
    public final float faceWidth, faceHeight, faceX, faceY;
    private final double panelBottom, panelTop;

    public ShopSignBlock(float faceWidth, float faceHeight, float faceX, float faceY, double panelBottom, double panelTop, Properties properties) {
        super(WoodType.OAK, properties);
        this.faceWidth = faceWidth;
        this.faceHeight = faceHeight;
        this.faceX = faceX;
        this.faceY = faceY;
        this.panelBottom = panelBottom;
        this.panelTop = panelTop;
    }

    public boolean billboard() {
        return faceWidth > 1;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShopSignBlockEntity(ModBlockEntities.SHOP_SIGN.get(), pos, state);
    }

    /**
     * The sign's ticker, as vanilla signs have: it lets go of a player who stopped editing it (left the editor, logged out,
     * walked away), so nobody else is locked out. Vanilla's own ticker only knows the vanilla sign's block entity type.
     */
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.@org.jspecify.annotations.Nullable BlockEntityTicker<T> getTicker(
        net.minecraft.world.level.Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.SHOP_SIGN.get(), net.minecraft.world.level.block.entity.SignBlockEntity::tick);
    }

    /** A thin panel against the wall behind it. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        double t = 1.5;
        return switch (facing) {
            case NORTH -> Shapes.box(0, panelBottom / 16, (16 - t) / 16, 1, panelTop / 16, 1);
            case EAST -> Shapes.box(0, panelBottom / 16, 0, t / 16, panelTop / 16, 1);
            case WEST -> Shapes.box((16 - t) / 16, panelBottom / 16, 0, 1, panelTop / 16, 1);
            default -> Shapes.box(0, panelBottom / 16, 0, 1, panelTop / 16, t / 16);
        };
    }
}
