package xien.ica.beatbeams.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import xien.ica.beatbeams.power.PowerNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Binary power wire. Looks like redstone dust; emits a full-strength signal
 * when POWERED so vanilla lamps/doors/etc. work.
 *
 * The wire is attached to one face of a block (ATTACH = direction of the supporting block):
 * DOWN = floor, UP = ceiling, NORTH/SOUTH/EAST/WEST = wall.
 * It has four arms lying in its own plane (ARM0..ARM3, see {@link #planeDirs}), each NONE / SIDE / UP.
 */
public class WireBlock extends Block implements SimpleWaterloggedBlock {

    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    public static final EnumProperty<Direction> ATTACH = EnumProperty.create("attach", Direction.class);
    public static final EnumProperty<RedstoneSide> ARM0 = EnumProperty.create("arm0", RedstoneSide.class);
    public static final EnumProperty<RedstoneSide> ARM1 = EnumProperty.create("arm1", RedstoneSide.class);
    public static final EnumProperty<RedstoneSide> ARM2 = EnumProperty.create("arm2", RedstoneSide.class);
    public static final EnumProperty<RedstoneSide> ARM3 = EnumProperty.create("arm3", RedstoneSide.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    @SuppressWarnings("unchecked")
    private static final EnumProperty<RedstoneSide>[] ARMS = new EnumProperty[]{ARM0, ARM1, ARM2, ARM3};

    private static final Direction[] PLANE_Y = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Direction[] PLANE_Z = {Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST};
    private static final Direction[] PLANE_X = {Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.NORTH};

    private static final VoxelShape SHAPE_DOWN = Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0);
    private static final VoxelShape SHAPE_UP = Block.box(0.0, 15.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape SHAPE_NORTH = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 1.0);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0.0, 0.0, 15.0, 16.0, 16.0, 16.0);
    private static final VoxelShape SHAPE_WEST = Block.box(0.0, 0.0, 0.0, 1.0, 16.0, 16.0);
    private static final VoxelShape SHAPE_EAST = Block.box(15.0, 0.0, 0.0, 16.0, 16.0, 16.0);

    public WireBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(POWERED, false)
                .setValue(ATTACH, Direction.DOWN)
                .setValue(ARM0, RedstoneSide.NONE)
                .setValue(ARM1, RedstoneSide.NONE)
                .setValue(ARM2, RedstoneSide.NONE)
                .setValue(ARM3, RedstoneSide.NONE)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, ATTACH, ARM0, ARM1, ARM2, ARM3, WATERLOGGED);
    }

    /** The four directions (in order ARM0..ARM3) that lie in the plane of a wire attached to {@code face}. */
    public static Direction[] planeDirs(Direction face) {
        return switch (face.getAxis()) {
            case Y -> PLANE_Y;
            case Z -> PLANE_Z;
            case X -> PLANE_X;
        };
    }

    // ---- connection logic ----

    private static boolean canConnectTo(BlockState st) {
        return st.getBlock() instanceof WireBlock
                || st.getBlock() instanceof ConnectorBlock
                || st.getBlock() instanceof MusicBeaconBlock
                || st.isSignalSource();
    }

    private static boolean wireWithFace(BlockGetter level, BlockPos p, Direction face) {
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof WireBlock && s.getValue(ATTACH) == face;
    }

    private static boolean solid(BlockGetter level, BlockPos p) {
        return level.getBlockState(p).isRedstoneConductor(level, p);
    }

    /** dir = in-plane direction of the wire this link leaves through; climb = draw the wall strip. */
    private record Link(BlockPos pos, Direction dir, boolean climb) {}

    /**
     * Every wire that the wire at {@code x} (attached to face {@code f}) links to.
     * With f = support direction and d = an in-plane direction:
     *  1. straight        : same face, next cell
     *  2. convex edge     : two faces of the same block, wrapping around its edge
     *  3. concave corner  : wire at x-f hugging the wall at x+d (and the reverse of that)
     *  4. over a wall     : wire on top of the wall block at x+d (like redstone dust going up)
     *  5. down a step     : reverse of 4
     * Rules are written so that if A links to B then B links to A.
     */
    private static void collect(BlockGetter level, BlockPos x, Direction f, Consumer<Link> out) {
        Direction back = f.getOpposite();
        for (Direction d : Direction.values()) {
            if (d.getAxis() == f.getAxis()) continue;
            BlockPos xd = x.relative(d);
            BlockPos around = xd.relative(f);
            boolean wall = solid(level, xd);

            // 1. straight
            if (wireWithFace(level, xd, f)) out.accept(new Link(xd, d, false));
            // 2. convex edge
            if (wireWithFace(level, around, d.getOpposite())) out.accept(new Link(around, d, false));
            // 3b. concave corner, other side
            if (wireWithFace(level, xd, d) && solid(level, around)) out.accept(new Link(xd, d, false));

            if (wall) {
                BlockPos over = x.relative(back);
                // 3. concave corner
                if (wireWithFace(level, over, d)) out.accept(new Link(over, d, true));
                // 4. over the wall
                if (!solid(level, over)) {
                    BlockPos top = xd.relative(back);
                    if (wireWithFace(level, top, f)) out.accept(new Link(top, d, true));
                }
            } else if (solid(level, x.relative(f))) {
                // 5. down a step
                if (wireWithFace(level, around, f)) out.accept(new Link(around, d, false));
            }
        }
    }

    /** All wires linked to the wire at {@code pos} (used by PowerNetwork). */
    public static List<BlockPos> linkedWires(BlockGetter level, BlockPos pos) {
        List<BlockPos> result = new ArrayList<>(6);
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof WireBlock)) return result;
        collect(level, pos, st.getValue(ATTACH), l -> result.add(l.pos()));
        return result;
    }

    /** Returns {@code state} with the four arms recomputed for {@code pos}. */
    public static BlockState withConnections(BlockGetter level, BlockState state, BlockPos pos) {
        Direction f = state.getValue(ATTACH);
        Direction[] dirs = planeDirs(f);
        RedstoneSide[] sides = {RedstoneSide.NONE, RedstoneSide.NONE, RedstoneSide.NONE, RedstoneSide.NONE};

        collect(level, pos, f, l -> {
            for (int i = 0; i < 4; i++) {
                if (dirs[i] != l.dir()) continue;
                if (l.climb()) sides[i] = RedstoneSide.UP;
                else if (sides[i] == RedstoneSide.NONE) sides[i] = RedstoneSide.SIDE;
            }
        });

        for (int i = 0; i < 4; i++) {
            if (sides[i] == RedstoneSide.NONE && canConnectTo(level.getBlockState(pos.relative(dirs[i])))) {
                sides[i] = RedstoneSide.SIDE;
            }
            state = state.setValue(ARMS[i], sides[i]);
        }
        return state;
    }

    /** Re-evaluate the wire at p (if any) and write it if connections changed. */
    private static void refresh(Level level, BlockPos p) {
        BlockState cur = level.getBlockState(p);
        if (!(cur.getBlock() instanceof WireBlock)) return;
        BlockState next = withConnections(level, cur, p);
        if (next != cur) level.setBlock(p, next, Block.UPDATE_CLIENTS);
    }

    /** Refresh every wire in the 3x3x3 area around pos (all cells a linked wire can be in). */
    private static void refreshNearby(Level level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    refresh(level, pos.offset(dx, dy, dz));
                }
            }
        }
    }

    // ---- placement / updates ----

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos support = pos.relative(state.getValue(ATTACH));
        return !level.getBlockState(support).getCollisionShape(level, support).isEmpty();
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        FluidState fluid = level.getFluidState(pos);
        BlockState base = defaultBlockState()
                .setValue(ATTACH, ctx.getClickedFace().getOpposite())
                .setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
        if (!base.canSurvive(level, pos)) return null;
        return withConnections(level, base, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks,
                                     BlockPos pos, Direction dir, BlockPos neighborPos,
                                     BlockState neighborState, RandomSource random) {
        if (dir == state.getValue(ATTACH) && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (state.getValue(WATERLOGGED)) {
            ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return withConnections(level, state, pos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(ATTACH)) {
            case DOWN -> SHAPE_DOWN;
            case UP -> SHAPE_UP;
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
        };
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // Weak signal only (like redstone dust) — still enough for lamps
        return 0;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   @Nullable Orientation orientation, boolean movedByPiston) {
        refresh(level, pos);
        PowerNetwork.recomputeAround(level, pos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(state.getBlock())) {
            refreshNearby(level, pos);
            PowerNetwork.recomputeAround(level, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        refreshNearby(level, pos);
        PowerNetwork.recomputeAround(level, pos);
        // Nudge vanilla neighbors so lamps turn off
        for (Direction d : Direction.values()) {
            level.updateNeighborsAt(pos.relative(d), this);
        }
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
}