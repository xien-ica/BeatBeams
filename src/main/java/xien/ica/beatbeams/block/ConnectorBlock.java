package xien.ica.beatbeams.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import xien.ica.beatbeams.power.PowerNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * Power relay. Two looks:
 *  - POST: vertical cinnabar-brick post, links out of all 6 sides.
 *  - PIPE: a pipe along AXIS, links only out of its two ends.
 *
 * Link rule (shared with PowerNetwork): from a connector, look along an open side up to
 * PowerNetwork.CONNECTOR_LINK_RANGE blocks. The first connector found is the partner
 * (whatever blocks lie in between are ignored). The link exists only if that partner
 * also opens toward us. The rule is symmetric: A links B <=> B links A.
 *
 * Wires touching any side of a connector still connect to it (handled by PowerNetwork).
 */
public class ConnectorBlock extends Block implements EntityBlock {

    public enum Style implements StringRepresentable {
        POST("post"),
        PIPE("pipe");

        private final String name;

        Style(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final EnumProperty<Style> STYLE = EnumProperty.create("style", Style.class);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    private static final VoxelShape POST = Block.box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0);
    private static final VoxelShape ARM_NORTH = Block.box(5.0, 0.0, 0.0, 11.0, 16.0, 4.0);
    private static final VoxelShape ARM_SOUTH = Block.box(5.0, 0.0, 12.0, 11.0, 16.0, 16.0);
    private static final VoxelShape ARM_WEST = Block.box(0.0, 0.0, 5.0, 4.0, 16.0, 11.0);
    private static final VoxelShape ARM_EAST = Block.box(12.0, 0.0, 5.0, 16.0, 16.0, 11.0);

    // Pipe = thin tube + a wider collar at each end
    private static final VoxelShape PIPE_Y = Shapes.or(
            Block.box(5.0, 0.0, 5.0, 11.0, 16.0, 11.0),
            Block.box(4.0, 0.0, 4.0, 12.0, 2.0, 12.0),
            Block.box(4.0, 14.0, 4.0, 12.0, 16.0, 12.0));
    private static final VoxelShape PIPE_X = Shapes.or(
            Block.box(0.0, 5.0, 5.0, 16.0, 11.0, 11.0),
            Block.box(0.0, 4.0, 4.0, 2.0, 12.0, 12.0),
            Block.box(14.0, 4.0, 4.0, 16.0, 12.0, 12.0));
    private static final VoxelShape PIPE_Z = Shapes.or(
            Block.box(5.0, 5.0, 0.0, 11.0, 11.0, 16.0),
            Block.box(4.0, 4.0, 0.0, 12.0, 12.0, 2.0),
            Block.box(4.0, 4.0, 14.0, 12.0, 12.0, 16.0));

    public ConnectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(POWERED, false)
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(EAST, false)
                .setValue(WEST, false)
                .setValue(STYLE, Style.POST)
                .setValue(AXIS, Direction.Axis.Y));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, NORTH, SOUTH, EAST, WEST, STYLE, AXIS);
    }

    // ---- link rule (used by PowerNetwork too) ----

    /** Does this connector state link out of its {@code d} side? POST: always. PIPE: only along its axis. */
    public static boolean opensTo(BlockState st, Direction d) {
        if (st.getValue(STYLE) == Style.POST) return true;
        return d.getAxis() == st.getValue(AXIS);
    }

    /**
     * The connector that the connector {@code st} at {@code pos} links to through its {@code d} side,
     * or null. Ignores whatever is between them. The first connector on the line decides:
     * if it does not open back toward us, there is no link (it ends the line).
     */
    @Nullable
    public static BlockPos linkTarget(BlockGetter level, BlockPos pos, BlockState st, Direction d) {
        if (!opensTo(st, d)) return null;
        BlockPos.MutableBlockPos p = pos.mutable();
        for (int i = 1; i <= PowerNetwork.CONNECTOR_LINK_RANGE; i++) {
            p.move(d);
            if (level instanceof LevelReader lr && !lr.hasChunkAt(p)) return null;
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof ConnectorBlock) {
                return opensTo(s, d.getOpposite()) ? p.immutable() : null;
            }
        }
        return null;
    }

    /** All connectors linked to the connector at {@code pos}. */
    public static List<BlockPos> linkedConnectors(BlockGetter level, BlockPos pos) {
        List<BlockPos> result = new ArrayList<>(6);
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof ConnectorBlock)) return result;
        for (Direction d : Direction.values()) {
            BlockPos t = linkTarget(level, pos, st, d);
            if (t != null) result.add(t);
        }
        return result;
    }

    // ---- visuals (post arms) ----

    /** Post arm toward a directly adjacent connector that links with us. Pipes never have arms. */
    private static boolean armTo(BlockGetter level, BlockState state, BlockPos pos, Direction d) {
        if (state.getValue(STYLE) != Style.POST) return false;
        BlockState n = level.getBlockState(pos.relative(d));
        return n.getBlock() instanceof ConnectorBlock && opensTo(n, d.getOpposite());
    }

    private static BlockState withArms(BlockGetter level, BlockState state, BlockPos pos) {
        return state
                .setValue(NORTH, armTo(level, state, pos, Direction.NORTH))
                .setValue(SOUTH, armTo(level, state, pos, Direction.SOUTH))
                .setValue(EAST, armTo(level, state, pos, Direction.EAST))
                .setValue(WEST, armTo(level, state, pos, Direction.WEST));
    }

    // ---- placement / interaction ----

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Placed as a POST; the clicked face sets the axis it will use if toggled to a PIPE (like a log).
        BlockState base = defaultBlockState().setValue(AXIS, ctx.getClickedFace().getAxis());
        return withArms(ctx.getLevel(), base, ctx.getClickedPos());
    }

    /** Empty hand (both hands) toggles POST <-> PIPE. With an item in hand, normal placing still works. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        Style next = state.getValue(STYLE) == Style.POST ? Style.PIPE : Style.POST;
        BlockState toggled = withArms(level, state.setValue(STYLE, next), pos);
        level.setBlock(pos, toggled, Block.UPDATE_ALL);
        // Links change with the style, and the block itself sends no "placed" event for a state change.
        PowerNetwork.recomputeAround(level, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(STYLE) == Style.PIPE) {
            return switch (state.getValue(AXIS)) {
                case X -> PIPE_X;
                case Y -> PIPE_Y;
                case Z -> PIPE_Z;
            };
        }
        VoxelShape shape = POST;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, ARM_NORTH);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, ARM_SOUTH);
        if (state.getValue(EAST)) shape = Shapes.or(shape, ARM_EAST);
        if (state.getValue(WEST)) shape = Shapes.or(shape, ARM_WEST);
        return shape;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConnectorBlockEntity(pos, state);
    }

    // ---- vanilla signal output ----

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
        if (direction == Direction.DOWN && state.getValue(POWERED)) return 15;
        return 0;
    }

    // ---- updates ----

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   @Nullable Orientation orientation, boolean movedByPiston) {
        BlockState shaped = withArms(level, state, pos);
        if (shaped != state) {
            level.setBlock(pos, shaped, 2); // clients only — no update storm
        }
        PowerNetwork.recomputeAround(level, pos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(state.getBlock())) {
            PowerNetwork.recomputeAround(level, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        PowerNetwork.recomputeAround(level, pos);
        for (Direction d : Direction.values()) {
            level.updateNeighborsAt(pos.relative(d), this);
        }
    }
}