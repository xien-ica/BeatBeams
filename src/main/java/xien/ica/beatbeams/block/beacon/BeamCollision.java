package xien.ica.beatbeams.block.beacon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Raycasts a Music Beacon's beam and reports how far it can extend. */
public final class BeamCollision {

    /** Blocks the raycast start point must clear past the beacon's own bounding
     *  box before testing for hits - sqrt(3)/2 (corner-to-center of a unit cube)
     *  plus a small margin. */
    private static final float SELF_CLEARANCE = 0.9f;
    /** Cap on skip-through re-clips per check (glass/bars chains), so a long row
     *  of glass panes or a grazing edge hit can't loop unbounded. */
    private static final int MAX_COLLISION_STEPS = 24;

    private BeamCollision() {}

    /**
     * Returns the effective beam length: the distance from the beacon's center to the
     * first blocking block along the beam, or {@code maxLen} if the path is clear.
     *
     * The direction must mirror MusicBeaconBeamRenderer's actual mesh rotation (a beam
     * starting straight up, then rotateY(yaw) then rotateX(-pitch), so pitch is measured
     * from vertical: 0 deg = straight up, 90 deg = horizontal).
     */
    public static float trace(Level level, BlockPos pos, float yawDeg, float pitchDeg, float maxLen) {
        float yawRad = (float) Math.toRadians(yawDeg);
        float pitchRad = (float) Math.toRadians(pitchDeg);
        float sinP = (float) Math.sin(pitchRad);
        double dx = -sinP * Math.sin(yawRad);
        double dy = Math.cos(pitchRad);
        double dz = -sinP * Math.cos(yawRad);

        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        // The beacon block itself has a solid collision box, and the beam originates
        // from that block's own center - a ray starting there immediately re-hits its
        // own block at ~0 distance, collapsing the beam to nothing. Push the ray's
        // start out past the beacon's own bounding box first so it only ever tests
        // blocks the beam is actually passing through.
        if (maxLen <= SELF_CLEARANCE) {
            return maxLen;
        }
        Vec3 from = center.add(dx * SELF_CLEARANCE, dy * SELF_CLEARANCE, dz * SELF_CLEARANCE);
        Vec3 to = center.add(dx * maxLen, dy * maxLen, dz * maxLen);
        double toDistSq = to.distanceToSqr(center);

        // Re-clip repeatedly, stepping past any block the beam should shine through
        // (glass/panes/iron bars) instead of stopping there.
        Vec3 rayFrom = from;
        Vec3 blockingHit = null;
        for (int step = 0; step < MAX_COLLISION_STEPS; step++) {
            BlockHitResult hit = level.clip(new ClipContext(rayFrom, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.BLOCK) {
                break; // clear path all the way to `to`
            }
            if (!isBeamPassable(level.getBlockState(hit.getBlockPos()))) {
                blockingHit = hit.getLocation();
                break;
            }
            // Passable (glass/bars): nudge just past this hit point and keep tracing.
            Vec3 nudged = hit.getLocation().add(dx * 0.05, dy * 0.05, dz * 0.05);
            if (nudged.distanceToSqr(center) >= toDistSq) break; // reached the end
            rayFrom = nudged;
        }

        // Distance measured from the beacon's center (not the shifted 'from'), since
        // that's the coordinate space the beam mesh/effectiveBeamLength is drawn in.
        return blockingHit != null
                ? (float) Math.sqrt(blockingHit.distanceToSqr(center))
                : maxLen;
    }

    /** Blocks the beam should shine through rather than stop at: all glass variants
     *  (regular/stained/tinted, blocks and panes) and iron bars. Matched by registry
     *  name rather than class, since it also transparently covers pane-shaped glass
     *  and any future glass-family blocks without needing exact class references. */
    private static boolean isBeamPassable(BlockState state) {
        if (state.isAir()) return true;
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();
        return path.contains("glass") || path.equals("iron_bars");
    }
}