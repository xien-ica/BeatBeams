package xien.ica.beatbeams.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;

/**
 * Directional beam renderer. Optimized for many beacons:
 * - RenderTypes resolved once and cached (no try/catch per frame).
 * - No heap allocations in submit() (no float[][]).
 * - Geometry is 4 quads × 2 layers = fixed cost per visible beacon.
 */
public class MusicBeaconBeamRenderer implements BlockEntityRenderer<MusicBeaconBlockEntity, MusicBeaconBeamRenderState> {

    private static final Identifier BEAM_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/entity/beacon/beacon_beam.png");

    private static final float INNER_RADIUS = 0.2f;
    private static final float OUTER_RADIUS = 0.25f;

    /** Cached once after first successful resolve. */
    private static RenderType CACHED_SOLID;
    private static RenderType CACHED_GLOW;
    private static boolean TYPES_RESOLVED;

    public MusicBeaconBeamRenderer(BlockEntityRendererProvider.Context context) {
    }

    private static void ensureRenderTypes() {
        if (TYPES_RESOLVED) return;
        try {
            CACHED_SOLID = RenderTypes.beaconBeam(BEAM_TEXTURE, false);
            CACHED_GLOW = RenderTypes.beaconBeam(BEAM_TEXTURE, true);
        } catch (Throwable t) {
            CACHED_SOLID = RenderTypes.entityTranslucentEmissive(BEAM_TEXTURE);
            CACHED_GLOW = CACHED_SOLID;
        }
        TYPES_RESOLVED = true;
    }

    @Override
    public MusicBeaconBeamRenderState createRenderState() {
        return new MusicBeaconBeamRenderState();
    }

    @Override
    public void extractRenderState(MusicBeaconBlockEntity blockEntity, MusicBeaconBeamRenderState state,
                                   float partialTick, Vec3 cameraPos,
                                   @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPos, crumblingOverlay);
        state.pulse = blockEntity.pulseEnvelope();
        state.pulseMinScale = blockEntity.pulseMinScale;
        state.pulsePeakScale = blockEntity.effectivePulsePeakScale();
        state.currentWidth = blockEntity.currentWidth;
        state.beamLength = blockEntity.effectiveBeamLength;
        state.currentBrightness = blockEntity.currentBrightness;
        state.colorRgb = blockEntity.displayColorRgb;
        state.yawDegrees = blockEntity.yawDegrees;
        state.currentYawOffset = blockEntity.currentYawOffset;
        state.currentYawWobble = blockEntity.currentYawWobble;
        state.pitchDegrees = blockEntity.pitchDegrees + blockEntity.currentPitchOffset;
        if (blockEntity.sweepEnabled) {
            // Sweep replaces the normal aim: compass sets the yaw, the sweep angle sets the tilt.
            // Angle 90 = straight up -> tilt 0; angle 0 -> tilt +90 (flat toward the compass point).
            float phase = blockEntity.sweepPhase + blockEntity.sweepRate * (partialTick * 0.05f);
            state.yawDegrees = blockEntity.sweepDir.yawDegrees + blockEntity.yawDegrees;
            state.currentYawOffset = 0f;
            state.currentYawWobble = 0f;
            state.pitchDegrees = blockEntity.sweepTilt(phase);
        }
        state.enabled = blockEntity.isActive();
        if (blockEntity.getLevel() != null) {
            state.gameTime = blockEntity.getLevel().getGameTime();
        }
    }

    @Override
    public void submit(MusicBeaconBeamRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState cameraState) {
        ensureRenderTypes();
        if (!state.enabled) return;

        float widthScale = Mth.lerp(state.pulse, state.pulseMinScale, state.pulsePeakScale);
        float widthFactor = (state.currentWidth / 0.4f) * widthScale;
        float innerR = Math.max(0.02f, INNER_RADIUS * widthFactor);
        float outerR = Math.max(innerR + 0.02f, OUTER_RADIUS * widthFactor);
        float length = state.beamLength;
        float brightness = Mth.clamp(state.currentBrightness, 0f, 1.5f);

        float r = ((state.colorRgb >> 16) & 0xFF) / 255f * brightness;
        float g = ((state.colorRgb >> 8) & 0xFF) / 255f * brightness;
        float b = (state.colorRgb & 0xFF) / 255f * brightness;

        float yaw = state.yawDegrees + state.currentYawOffset + state.currentYawWobble;
        float pitch = state.pitchDegrees;

        float time = (float) Math.floorMod(state.gameTime, 40L);
        float vScroll = Mth.frac(time * 0.2f - (float) Math.floor(time * 0.1f));

        poseStack.pushPose();
        poseStack.translate(0.5, 0.0, 0.5);
        Matrix4f modelMatrix = poseStack.last().pose();
        modelMatrix.rotateY((float) Math.toRadians(yaw));
        modelMatrix.rotateX((float) Math.toRadians(-pitch));

        int light = state.lightCoords;
        float innerAlpha = Mth.clamp(0.95f + 0.05f * state.pulse, 0f, 1f);
        float outerAlpha = Mth.clamp(0.2f + 0.1f * state.pulse, 0f, 0.4f);

        // Capture locals for lambdas (no per-frame object alloc beyond the two submits)
        float fi = innerR, fo = outerR, fl = length, fr = r, fg = g, fb = b, fvs = vScroll;
        int flgt = light;
        float fia = innerAlpha, foa = outerAlpha;

        collector.submitCustomGeometry(poseStack, CACHED_SOLID, (pose, buffer) ->
                drawBeamLayer(buffer, pose.pose(), fi, fl, fr, fg, fb, fia, flgt, fvs));
        collector.submitCustomGeometry(poseStack, CACHED_GLOW, (pose, buffer) ->
                drawBeamLayer(buffer, pose.pose(), fo, fl, fr, fg, fb, foa, flgt, fvs));

        poseStack.popPose();
    }


    /** Vanilla beacon render distance: 16 */
    @Override
    public int getViewDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 32;
    }

    /** Prevent frustum culling from dropping long beams (esp. looking straight up). */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    /**
     * Same idea as vanilla BeaconRenderer: ignore Y so looking up/down does not
     * unload the beam when the block is still horizontally in range.
     */
    @Override
    public boolean shouldRender(MusicBeaconBlockEntity blockEntity, Vec3 cameraPos) {
        Vec3 block = Vec3.atCenterOf(blockEntity.getBlockPos()).multiply(1.0, 0.0, 1.0);
        Vec3 cam = cameraPos.multiply(1.0, 0.0, 1.0);
        return block.closerThan(cam, this.getViewDistance());
    }

    /** Fixed 4-sided tube; no allocations. */
    private static void drawBeamLayer(VertexConsumer buffer, Matrix4f model,
                                      float radius, float length,
                                      float r, float g, float b, float alpha,
                                      int light, float vScroll) {
        float v0 = -1.0f + vScroll;
        float v1 = length + v0;
        float tipA = alpha * 0.3f;

        // Corner offsets in order around Y
        // (r,r), (r,-r), (-r,-r), (-r,r)
        float r0x = radius, r0z = radius;
        float r1x = radius, r1z = -radius;
        float r2x = -radius, r2z = -radius;
        float r3x = -radius, r3z = radius;

        quad(buffer, model, r0x, r0z, r1x, r1z, length, v0, v1, r, g, b, alpha, tipA, light);
        quad(buffer, model, r1x, r1z, r2x, r2z, length, v0, v1, r, g, b, alpha, tipA, light);
        quad(buffer, model, r2x, r2z, r3x, r3z, length, v0, v1, r, g, b, alpha, tipA, light);
        quad(buffer, model, r3x, r3z, r0x, r0z, length, v0, v1, r, g, b, alpha, tipA, light);
    }

    private static void quad(VertexConsumer buffer, Matrix4f model,
                             float x0, float z0, float x1, float z1, float length,
                             float v0, float v1, float r, float g, float b,
                             float alpha, float tipA, int light) {
        vertex(buffer, model, x0, 0, z0, 0f, v0, r, g, b, alpha, light);
        vertex(buffer, model, x1, 0, z1, 1f, v0, r, g, b, alpha, light);
        vertex(buffer, model, x1, length, z1, 1f, v1, r, g, b, tipA, light);
        vertex(buffer, model, x0, length, z0, 0f, v1, r, g, b, tipA, light);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f model, float x, float y, float z,
                               float u, float v, float r, float g, float b, float alpha, int light) {
        buffer.addVertex(model, x, y, z)
                .setColor(r, g, b, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(0, 1, 0);
    }
}