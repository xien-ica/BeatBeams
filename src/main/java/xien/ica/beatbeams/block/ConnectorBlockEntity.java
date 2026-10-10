package xien.ica.beatbeams.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xien.ica.beatbeams.Beatbeams;

/**
 * Holds powered state for a Connector Block. Updated by {@link xien.ica.beatbeams.power.PowerNetwork}
 * on neighbor changes (no per-tick polling).
 */
public class ConnectorBlockEntity extends BlockEntity {

    private boolean powered = false;

    public ConnectorBlockEntity(BlockPos pos, BlockState state) {
        super(Beatbeams.CONNECTOR_BLOCK_ENTITY, pos, state);
    }

    public boolean isPowered() {
        return powered;
    }

    public void setPowered(boolean value) {
        setPoweredSilent(value);
    }

    /** Update powered + visual state without sending neighbor updates. */
    public void setPoweredSilent(boolean value) {
        if (this.powered == value) return;
        this.powered = value;
        setChanged();
        if (level != null) {
            BlockState st = level.getBlockState(worldPosition);
            if (st.hasProperty(ConnectorBlock.POWERED) && st.getValue(ConnectorBlock.POWERED) != value) {
                level.setBlock(worldPosition, st.setValue(ConnectorBlock.POWERED, value), 2);
                level.updateNeighborsAt(worldPosition, Beatbeams.CONNECTOR_BLOCK);
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("Powered", powered);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        powered = input.getBooleanOr("Powered", false);
    }
}
