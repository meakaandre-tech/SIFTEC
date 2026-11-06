package com.meakaandre.siftec.belt;

import com.meakaandre.siftec.Siftec;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import com.zurrtum.create.content.logistics.funnel.BeltFunnelBlock;
import com.zurrtum.create.content.logistics.funnel.FunnelBlock;
import com.zurrtum.create.content.logistics.funnel.FunnelItem;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlock;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlockEntity;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelItem;
import com.zurrtum.create.content.equipment.wrench.IWrenchable;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Funnels and tunnels built into a belt block. A belt block can hold one of Create's own belt funnels or tunnels: its
 * real block state and an instance of Create's own block entity for it, made at the cell above the belt (where Create
 * puts a stacked one) but never put into the world. The belt saves it, sends it to clients, ticks it and drops it.
 * <p>
 * Create's code finds a belt's funnels and tunnels by asking the level for the block above each belt block, and a
 * funnel or tunnel finds its belt (and the outputs beside it) below itself. So instead of copying that code, a
 * <i>stacked view</i> answers those questions with the built-in attachment, but only while Create's code for a belt is
 * running ({@link #run}): the belt's tick, the attachment's tick, the belt's neighbour updates, the wrench and the
 * client's flap packets. Inside the view Create sees exactly a stacked funnel or tunnel, so its splits, filters, modes
 * and flaps are its own; outside it the real block above the belt (air, or a ceiling) is untouched. What Create writes
 * into that cell changes the attachment instead (see the level mixin).
 * <p>
 * One deliberate difference: a built-in funnel feeds the block beside the belt at the belt's own height, so its
 * inventory target is one block lower than a stacked funnel's.
 */
public final class BeltAttachments {
    /** What the belt mixin adds to every belt block. */
    public interface Host {
        @Nullable SmartBlockEntity siftec$attachment();

        void siftec$attachment(@Nullable SmartBlockEntity attachment);
    }

    /** What the mixin adds to Create's block entities: the belt a built-in one lives in (null for every real one). */
    public interface Hosted {
        @Nullable BeltBlockEntity siftec$host();

        void siftec$host(@Nullable BeltBlockEntity host);
    }

    /** Create's protected save and load, which the attachment is written and read through. */
    public interface Saving {
        void siftec$write(ValueOutput view, boolean clientPacket);

        void siftec$read(ValueInput view, boolean clientPacket);
    }

    /** Threads anywhere in the stacked view; the level's hooks look no further while this is 0. */
    private static final AtomicInteger OPEN = new AtomicInteger();
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    /** Where item drops from a built-in attachment's cell land instead (its belt block), while one is being removed. */
    private static final ThreadLocal<BlockPos[]> DROPS = new ThreadLocal<>();
    private static final String STATE = "SiftecAttachment", DATA = "SiftecAttachmentData";
    /**
     * Belt blocks whose stacked funnel or tunnel is left where it is, not taken in: only for the selftests' side-by-side
     * comparison of a built-in attachment with Create's stacked one.
     */
    public static final Set<BlockPos> KEEP_STACKED = ConcurrentHashMap.newKeySet();

    private BeltAttachments() {
    }

    // ------------------------------------------------------------------------------------------------ the view

    /** Runs Create's code with the stacked view open on this thread. */
    public static void run(Runnable run) {
        open();
        try {
            run.run();
        } finally {
            close();
        }
    }

    public static <T> T call(Supplier<T> call) {
        open();
        try {
            return call.get();
        } finally {
            close();
        }
    }

    private static void open() {
        DEPTH.get()[0]++;
        OPEN.incrementAndGet();
    }

    private static void close() {
        DEPTH.get()[0]--;
        OPEN.decrementAndGet();
    }

    /** True while this thread is in the stacked view (one plain field read when no thread is). */
    public static boolean viewing() {
        return OPEN.getPlain() > 0 && DEPTH.get()[0] > 0;
    }

    /** The belt whose built-in attachment stands in for the cell {@code slot} (the cell above it), or null. */
    public static @Nullable BeltBlockEntity hostAt(Level level, BlockPos slot) {
        BlockPos below = slot.below();
        if (!level.hasChunkAt(below)) return null;
        LevelChunk chunk = level.getChunkAt(below);
        BlockEntity be = chunk.getBlockEntity(below);
        return be instanceof BeltBlockEntity belt && of(belt) != null ? belt : null;
    }

    // ------------------------------------------------------------------------------------------------ what it is

    /** A block that can be built into a belt: one of Create's belt funnels or tunnels. */
    public static boolean fits(BlockState state) {
        return state.getBlock() instanceof BeltFunnelBlock || state.getBlock() instanceof BeltTunnelBlock;
    }

    /** An item that clips into a belt: a funnel or a tunnel. */
    public static boolean clips(ItemStack stack) {
        return stack.getItem() instanceof FunnelItem || stack.getItem() instanceof BeltTunnelItem;
    }

    /** A belt block that can hold an attachment: flat or sloped (not vertical or sideways), not encased. */
    public static boolean takes(BlockState belt) {
        if (!belt.hasProperty(BeltBlock.SLOPE) || belt.getValue(BeltBlock.CASING)) return false;
        BeltSlope slope = belt.getValue(BeltBlock.SLOPE);
        return slope == BeltSlope.HORIZONTAL || slope == BeltSlope.UPWARD || slope == BeltSlope.DOWNWARD;
    }

    public static @Nullable SmartBlockEntity of(BeltBlockEntity belt) {
        return ((Host) belt).siftec$attachment();
    }

    public static @Nullable BlockState stateOf(BeltBlockEntity belt) {
        SmartBlockEntity a = of(belt);
        return a == null ? null : a.getBlockState();
    }

    /** The belt a block entity is built into, or null for a real one. */
    public static @Nullable BeltBlockEntity hostOf(@Nullable BlockEntity be) {
        return be instanceof Hosted h ? h.siftec$host() : null;
    }

    /** The cell a stacked attachment would stand in: where the built-in one's block entity says it is. */
    public static BlockPos slot(BeltBlockEntity belt) {
        return belt.getBlockPos().above();
    }

    // ------------------------------------------------------------------------------------------------ keeping it

    /** Makes the belt's attachment this state: changes the one there (Create's own state change) or makes a new one. */
    public static void setState(BeltBlockEntity belt, BlockState state) {
        SmartBlockEntity a = of(belt);
        if (a != null && a.getType().isValid(state)) {
            a.setBlockState(state);
        } else {
            make(belt, state);
        }
        changed(belt);
    }

    /** A new attachment of this state in the belt (whatever was there goes, with nothing dropped). */
    private static @Nullable SmartBlockEntity make(BeltBlockEntity belt, BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock block) || !(block.newBlockEntity(slot(belt), state) instanceof SmartBlockEntity be)) {
            ((Host) belt).siftec$attachment(null);
            return null;
        }
        ((Hosted) be).siftec$host(belt);
        if (belt.getLevel() != null) be.setLevel(belt.getLevel());
        ((Host) belt).siftec$attachment(be);
        return be;
    }

    /** Saves the belt's attachment with the belt (and in the belt's packets to clients). */
    public static void write(BeltBlockEntity belt, ValueOutput view, boolean clientPacket) {
        SmartBlockEntity a = of(belt);
        if (a == null) return;
        view.store(STATE, BlockState.CODEC, a.getBlockState());
        ((Saving) a).siftec$write(view.child(DATA), clientPacket);
    }

    /** Loads the belt's attachment (none in the data: there is none, so one the client still shows goes). */
    public static void read(BeltBlockEntity belt, ValueInput view, boolean clientPacket) {
        BlockState state = view.read(STATE, BlockState.CODEC).orElse(null);
        if (state == null || !fits(state)) {
            ((Host) belt).siftec$attachment(null);
            return;
        }
        SmartBlockEntity a = of(belt);
        if (a == null || !a.getType().isValid(state)) a = make(belt, state);
        else a.setBlockState(state);
        if (a != null) ((Saving) a).siftec$read(view.childOrEmpty(DATA), clientPacket);
    }

    private static void changed(BeltBlockEntity belt) {
        belt.setChanged();
        belt.sendData();
    }

    // ------------------------------------------------------------------------------------------------ ticking

    /**
     * Called inside the belt's tick (the view open): the attachment's own tick (a funnel looks for its inventory again
     * every 64 ticks by itself, so no neighbour update needs to reach it), and on the server any funnel or tunnel that
     * got into the block above the belt taken into it (only built-in ones are allowed).
     */
    public static void tick(BeltBlockEntity belt) {
        Level level = belt.getLevel();
        if (level == null) return;
        if (!level.isClientSide() && !belt.isVirtual()) absorb(belt, level);
        SmartBlockEntity a = of(belt);
        if (a == null) return;
        if (a.getLevel() != level) a.setLevel(level);
        a.tick();
    }

    /**
     * A funnel or tunnel in the block above the belt (put there by any means, or saved there by an older world) is
     * taken into the belt as its built-in attachment, with its settings; if the belt already has one, it pops off.
     */
    private static void absorb(BeltBlockEntity belt, Level level) {
        BlockPos above = slot(belt);
        if (viewing()) {
            // read the real block, not the view
            close();
            try {
                absorbReal(belt, level, above);
            } finally {
                open();
            }
        } else {
            absorbReal(belt, level, above);
        }
    }

    private static void absorbReal(BeltBlockEntity belt, Level level, BlockPos above) {
        BlockState real = level.getBlockState(above);
        if (!fits(real) || KEEP_STACKED.contains(belt.getBlockPos())) return;
        BlockState beltState = belt.getBlockState();
        // Create puts a casing on a belt under a tunnel (its tunnels need one); a built-in tunnel does not, so it comes off
        if (beltState.getValue(BeltBlock.CASING) && real.getBlock() instanceof BeltTunnelBlock) {
            belt.setCasingType(BeltBlockEntity.CasingType.NONE);
            beltState = level.getBlockState(belt.getBlockPos());
        }
        if (of(belt) != null || !takes(beltState)) {
            level.destroyBlock(above, true);
            return;
        }
        CompoundTagCopy copy = CompoundTagCopy.of(level, level.getBlockEntity(above));
        // the real one goes without its side effects (its filter and contents move into the belt with it)
        level.removeBlockEntity(above);
        level.setBlock(above, real.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        SmartBlockEntity a = make(belt, real);
        if (a != null && copy != null) copy.into(level, a);
        changed(belt);
        if (real.getBlock() instanceof BeltTunnelBlock) refreshTunnels(level, belt.getBlockPos());
    }

    /** A real block entity's saved data, to load into the built-in one. */
    private record CompoundTagCopy(net.minecraft.nbt.CompoundTag tag) {
        static @Nullable CompoundTagCopy of(Level level, @Nullable BlockEntity be) {
            if (!(be instanceof SmartBlockEntity smart)) return null;
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            ((Saving) smart).siftec$write(out, false);
            return new CompoundTagCopy(out.buildResult());
        }

        void into(Level level, SmartBlockEntity be) {
            ((Saving) be).siftec$read(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), false);
        }
    }

    /** Create's tunnel connection update for the built-in tunnels on this belt block and the belt blocks beside it. */
    public static void refreshTunnels(Level level, BlockPos beltPos) {
        if (level.isClientSide()) return;
        run(() -> {
            for (BlockPos p : new BlockPos[]{beltPos, beltPos.north(), beltPos.south(), beltPos.east(), beltPos.west()}) {
                BlockPos slot = p.above();
                BlockState state = level.getBlockState(slot);
                if (!(state.getBlock() instanceof BeltTunnelBlock tunnel) || hostAt(level, slot) == null) continue;
                tunnel.updateTunnel(level, slot);
                if (level.getBlockEntity(slot) instanceof BeltTunnelBlockEntity be) be.updateTunnelConnections();
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ placing

    /**
     * A funnel or tunnel used on a belt block (from the belt's use mixin): it clips into the belt, set as Create would
     * place it on top. Null to leave the click alone (not a funnel or tunnel, or not a belt that takes one).
     */
    public static @Nullable InteractionResult place(ItemStack stack, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!clips(stack) || !(level.getBlockEntity(pos) instanceof BeltBlockEntity belt)) return null;
        if (!takes(belt.getBlockState())) return InteractionResult.FAIL;
        // one attachment per belt block; never a stacked one above it
        if (of(belt) != null) return InteractionResult.FAIL;
        BlockState state;
        try {
            state = placement(stack, level, pos, player, hand, hit);
        } catch (RuntimeException e) {
            com.meakaandre.siftec.Siftec.LOGGER.warn("SIFTEC: could not clip {} into the belt at {}", stack, pos, e);
            return InteractionResult.FAIL;
        }
        if (state == null) return InteractionResult.FAIL;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        SmartBlockEntity a = make(belt, state);
        if (a == null) return InteractionResult.FAIL;
        changed(belt);
        if (state.getBlock() instanceof BeltTunnelBlock) refreshTunnels(level, pos);
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1, 1);
        if (!player.hasInfiniteMaterials()) stack.shrink(1);
        return InteractionResult.SUCCESS;
    }

    /** The state a funnel or tunnel takes clipped into this belt block, or null if this belt block cannot take it. */
    public static @Nullable BlockState placement(ItemStack stack, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        BlockPos slot = pos.above();
        if (stack.getItem() instanceof FunnelItem item && item.getBlock() instanceof FunnelBlock funnel) {
            // a funnel faces the side clicked, or the clicking player when the top was clicked (as Create places one)
            Direction facing = hit.getDirection().getAxis().isHorizontal() ? hit.getDirection() : player.getDirection().getOpposite();
            BlockState base = funnel.defaultBlockState();
            // Create's funnel item turns a funnel facing this way into its belt funnel (the default faces down, which has none)
            if (base.hasProperty(BlockStateProperties.FACING)) base = base.setValue(BlockStateProperties.FACING, facing);
            BlockState belt = funnel.getEquivalentBeltFunnel(level, slot, base);
            if (!(belt.getBlock() instanceof BeltFunnelBlock)) return null;
            BlockState state = belt.setValue(BeltFunnelBlock.HORIZONTAL_FACING, facing)
                .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.getShapeForPosition(level, slot, facing, !player.isShiftKeyDown()));
            return BeltFunnelBlock.isOnValidBelt(state, level, slot) ? state : null;
        }
        if (stack.getItem() instanceof BeltTunnelItem item && item.getBlock() instanceof BeltTunnelBlock tunnel) {
            if (!tunnel.isValidPositionForPlacement(tunnel.defaultBlockState(), level, slot)) return null;
            BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, new BlockHitResult(Vec3.atCenterOf(slot), Direction.UP, pos, false));
            return call(() -> tunnel.getStateForPlacement(context));
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ taking it off

    /**
     * The wrench used on a belt block that has an attachment (from the wrench mixin): sneaking takes the attachment off
     * as its item, the belt staying; otherwise the attachment is wrenched as Create's own block would be. Null for a
     * belt with no attachment, which Create's wrench handles as before.
     */
    public static @Nullable InteractionResult wrench(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null || !(level.getBlockEntity(context.getClickedPos()) instanceof BeltBlockEntity belt)) return null;
        SmartBlockEntity a = of(belt);
        if (a == null) return null;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockState state = a.getBlockState();
        if (player.isShiftKeyDown()) {
            remove(belt, player);
            level.playSound(null, belt.getBlockPos(), state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1, 1);
            return InteractionResult.SUCCESS;
        }
        if (!(state.getBlock() instanceof IWrenchable wrenchable)) return InteractionResult.PASS;
        BlockHitResult hit = new BlockHitResult(context.getClickLocation().add(0, 1, 0), context.getClickedFace(), slot(belt), false);
        InteractionResult result = call(() -> wrenchable.onWrenched(state, new UseOnContext(player, context.getHand(), hit)));
        changed(belt);
        return result;
    }

    /**
     * Takes the attachment out of the belt with its drops (the funnel or tunnel item, a filter, anything it held): into
     * the player's inventory when a survival player takes it off, else onto the belt block.
     */
    public static void remove(BeltBlockEntity belt, @Nullable Player player) {
        SmartBlockEntity a = of(belt);
        Level level = belt.getLevel();
        if (a == null || !(level instanceof ServerLevel server)) return;
        BlockState state = a.getBlockState();
        BlockPos pos = belt.getBlockPos();
        List<ItemStack> drops = Block.getDrops(state, server, slot(belt), a);
        ((Host) belt).siftec$attachment(null);
        for (ItemStack drop : drops) {
            if (player != null && !player.hasInfiniteMaterials()) player.getInventory().placeItemBackInInventory(drop, Prediction.SERVER_ONLY);
            else if (player == null) Block.popResource(level, pos, drop);
        }
        // what the attachment held (a filter, a brass tunnel's stack) drops as Create drops it, on the belt block
        BlockPos[] before = DROPS.get();
        DROPS.set(new BlockPos[]{slot(belt), pos});
        try {
            run(a::destroy);
        } catch (RuntimeException e) {
            Siftec.LOGGER.warn("SIFTEC: a built-in {} at {} could not drop what it held", state.getBlock(), pos, e);
        } finally {
            DROPS.set(before);
        }
        changed(belt);
        if (state.getBlock() instanceof BeltTunnelBlock) refreshTunnels(level, pos);
    }

    /** For the item-entity hook: where a drop from this cell lands instead, or null. */
    public static @Nullable BlockPos dropRedirect(BlockPos from) {
        BlockPos[] r = DROPS.get();
        return r != null && r[0].equals(from) ? r[1] : null;
    }

    // ------------------------------------------------------------------------------------------------ the level's hooks

    /**
     * Create wrote a block into a built-in attachment's cell (in the view): a funnel or tunnel changes the attachment;
     * anything else (a funnel turning into a plain one off a belt, or air) takes it off, dropping it unless it is air.
     */
    public static void written(BeltBlockEntity belt, BlockState state) {
        if (fits(state)) {
            setState(belt, state);
            return;
        }
        if (state.isAir()) {
            ((Host) belt).siftec$attachment(null);
            changed(belt);
            return;
        }
        remove(belt, null);
    }

    /** Create destroyed a built-in attachment's cell (in the view): it comes off, dropping onto the belt if asked. */
    public static void destroyed(BeltBlockEntity belt, boolean drop) {
        if (drop) {
            remove(belt, null);
        } else {
            ((Host) belt).siftec$attachment(null);
            changed(belt);
        }
    }

    /** The belt block is removed for good (broken, or taken by a contraption): its attachment drops with it. */
    public static void beltRemoved(BeltBlockEntity belt) {
        if (of(belt) != null) remove(belt, null);
    }
}
