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
 * The Lights Control Panel: a waxed copper trapdoor (same shape, placement, waterlogging and
 * redstone behaviour) that opens the lights panel screen on right-click instead of swinging open.
 * Sneak + right-click still opens/closes it like a normal trapdoor.
 *
 * The block holds no data. The screen finds every loaded Music Beacon through BeaconRegistry
 * and edits them directly, per network, for all beacons, or one at a time.
 *
 * Same split-source-set trick as MusicPlayerBlock: BeatbeamsClient assigns {@link #clientOpenScreen}.
 */
public class LightsPanelBlock extends TrapDoorBlock {

    public static Consumer<BlockPos> clientOpenScreen = pos -> {};

    public LightsPanelBlock(Properties settings) {
        super(BlockSetType.COPPER, settings);
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
