package xien.ica.beatbeams.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import xien.ica.beatbeams.Beatbeams;
import xien.ica.beatbeams.power.PowerNetwork;

import java.util.function.Consumer;

/**
 * The physical block a Music Beacon is placed as. All the interesting state
 * (beam direction, mappings, color, etc.) lives in MusicBeaconBlockEntity.
 *
 * Right-click opens the config GUI on the client (Phase 3).
 *
 * NOTE (26.3 port): net.minecraft.block.BlockEntityProvider (Yarn) is now
 * net.minecraft.world.level.block.EntityBlock, and createBlockEntity was
 * renamed to newBlockEntity. Also, as of 26.3 the per-Block Codec system
 * (Block#codec()/#simpleCodec()) was removed from vanilla entirely (it
 * was unused dead weight for the never-shipped data-driven block_type
 * registry), so there's no codec to define or override here anymore.
 * Finally, onUse was split into useWithoutItem/useItemOn; since this
 * block never cares what item (if any) the player is holding,
 * useWithoutItem is the equivalent hook and no longer takes a Hand param.
 *
 * Split source sets: main cannot reference client Screen classes, so the
 * actual open-screen call is wired from BeatbeamsClient into
 * {@link #clientOpenScreen}.
 */
public class MusicBeaconBlock extends Block implements EntityBlock {

    /** Synced visual/logic flag — client reads this so beams work without BE packet lag. */
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");

    /**
     * Client-only hook. Assigned once from BeatbeamsClient so this common
     * block can open the GUI without a main→client compile dependency.
     */
    public static Consumer<BlockPos> clientOpenScreen = pos -> {};

    public MusicBeaconBlock(Properties settings) {
        super(settings);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MusicBeaconBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            clientOpenScreen.accept(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }


    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                    @Nullable Orientation orientation, boolean movedByPiston) {
        // Only recompute for our network or vanilla signal sources — ignore unrelated block noise
        if (neighborBlock == Beatbeams.WIRE_BLOCK
                || neighborBlock == Beatbeams.CONNECTOR_BLOCK
                || neighborBlock == Beatbeams.MUSIC_BEACON_BLOCK
                || neighborBlock.defaultBlockState().isSignalSource()) {
            PowerNetwork.recomputeAround(level, pos);
        }
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
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return validateTicker(type, Beatbeams.MUSIC_BEACON_BLOCK_ENTITY, MusicBeaconBlockEntity::clientTick);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity, E extends BlockEntity> BlockEntityTicker<T> validateTicker(
            BlockEntityType<T> givenType, BlockEntityType<E> expectedType, BlockEntityTicker<? super E> ticker) {
        return expectedType == givenType ? (BlockEntityTicker<T>) ticker : null;
    }
}
