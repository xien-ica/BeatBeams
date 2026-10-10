package xien.ica.beatbeams.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/**
 * Per-frame snapshot for {@link MusicBeaconBeamRenderer}.
 */
public class MusicBeaconBeamRenderState extends BlockEntityRenderState {
    float pulse;
    float pulseMinScale;
    float pulsePeakScale;
    float currentWidth;
    float beamLength;
    float currentBrightness;
    int colorRgb;
    float yawDegrees;
    float currentYawOffset;
    float currentYawWobble;
    float pitchDegrees;
    long gameTime;
    boolean enabled = true;
}
