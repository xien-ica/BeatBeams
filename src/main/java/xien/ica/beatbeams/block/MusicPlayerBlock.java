package xien.ica.beatbeams.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Consumer;

/**
 * The Music Player: a mangrove trapdoor (same shape, placement, waterlogging and redstone
 * behaviour) that opens the player screen on right-click instead of swinging open.
 * Sneak + right-click still opens/closes it like a normal trapdoor.
 *
 * The queue and playback live in the client-side TrackPlayer, so the block holds no data of
 * its own and every Music Player controls the same player.
 *
 * Same split-source-set trick as MusicBeaconBlock: main cannot reference the client Screen,
 * so BeatbeamsClient assigns {@link #clientOpenScreen}.
 */
public class MusicPlayerBlock extends TrapDoorBlock {

    public static Consumer<BlockPos> clientOpenScreen = pos -> {};

    public MusicPlayerBlock(Properties settings) {
        super(BlockSetType.MANGROVE, settings);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }
        if (level.isClientSide()) {
            clientOpenScreen.accept(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }
}
