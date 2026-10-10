package xien.ica.beatbeams.block.beacon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.AudioSource;
import xien.ica.beatbeams.net.AudioFrame;

/**
 * Treble-reactive particles along the beam. Global budget + distance cull
 * so dozens of beacons stay cheap. One instance per beacon (it owns the dust cache);
 * the per-tick budget is shared across all instances.
 */
public final class BeaconParticles {

    /** Max dust particles spawned across all Music Beacons in one game tick. */
    private static final int GLOBAL_PARTICLE_BUDGET = 40;
    private static final double PARTICLE_PLAYER_RANGE = 48.0;
    private static final double PARTICLE_PLAYER_RANGE_SQ = PARTICLE_PLAYER_RANGE * PARTICLE_PLAYER_RANGE;

    private static long budgetGameTime = Long.MIN_VALUE;
    private static int budgetRemaining = GLOBAL_PARTICLE_BUDGET;

    /** Reused dust options; rebuilt only when the color changes. */
    private int cachedDustRgb = Integer.MIN_VALUE;
    private DustParticleOptions cachedDust;

    /**
     * @param source     what drives the particles; NONE falls back to treble
     * @param onTreble   if false and source is NONE, particles are off
     * @param yawDeg     total yaw of the beam (config + live spin offset)
     * @param pitchDeg   tilt from vertical, same as the beam (0 = straight up, 90 = horizontal)
     */
    public void spawn(Level level, BlockPos pos, AudioFrame frame, AudioSource source,
                      boolean onTreble, float yawDeg, float pitchDeg, float beamLength, int colorRgb) {
        if (source == AudioSource.NONE && !onTreble) return;
        float treb = source == AudioSource.NONE ? frame.treble() : source.sample(frame);
        if (treb < 0.28f) return;

        resetBudgetIfNeeded(level.getGameTime());
        if (budgetRemaining <= 0) return;

        double ox = pos.getX() + 0.5;
        double oy = pos.getY() + 0.5;
        double oz = pos.getZ() + 0.5;
        if (!anyPlayerNearby(level, ox, oy, oz)) return;

        RandomSource rng = level.getRandom();
        float chance = treb * treb;
        if (rng.nextFloat() > chance) return;

        int count = 1 + (int) (treb * 2.5f); // 1..3
        if (count > budgetRemaining) count = budgetRemaining;
        budgetRemaining -= count;

        float yawRad = (float) Math.toRadians(yawDeg);
        float pitchRad = (float) Math.toRadians(pitchDeg);
        // Same rule as MusicBeaconBeamRenderer / BeamCollision, so particles sit on the beam.
        float sinP = (float) Math.sin(pitchRad);
        float dx = (float) (-sinP * Math.sin(yawRad));
        float dy = (float) Math.cos(pitchRad);
        float dz = (float) (-sinP * Math.cos(yawRad));

        DustParticleOptions dust = dustFor(colorRgb);
        float span = Math.min(beamLength, 8f);
        boolean strongTreb = treb > 0.65f;
        boolean onBeat = frame.beat();

        for (int i = 0; i < count; i++) {
            float t = rng.nextFloat() * span;
            float jx = (rng.nextFloat() - 0.5f) * 0.3f;
            float jy = (rng.nextFloat() - 0.5f) * 0.3f;
            float jz = (rng.nextFloat() - 0.5f) * 0.3f;
            double px = ox + dx * t + jx;
            double py = oy + dy * t + jy;
            double pz = oz + dz * t + jz;
            double vx = dx * 0.05 + jx * 0.05;
            double vy = dy * 0.05 + jy * 0.05;
            double vz = dz * 0.05 + jz * 0.05;

            level.addParticle(dust, px, py, pz, vx, vy, vz);
            if (strongTreb && rng.nextFloat() < 0.25f) {
                level.addParticle(ParticleTypes.END_ROD, px, py, pz, vx * 2, vy * 2, vz * 2);
            }
            if (onBeat && i == 0 && rng.nextFloat() < 0.35f) {
                level.addParticle(ParticleTypes.NOTE, px, py, pz, 0, 0, 0);
            }
        }
    }

    private DustParticleOptions dustFor(int colorRgb) {
        if (cachedDust == null || cachedDustRgb != colorRgb) {
            cachedDustRgb = colorRgb;
            cachedDust = new DustParticleOptions(0xFF000000 | (colorRgb & 0xFFFFFF), 1.0f);
        }
        return cachedDust;
    }

    private static void resetBudgetIfNeeded(long gameTime) {
        if (gameTime != budgetGameTime) {
            budgetGameTime = gameTime;
            budgetRemaining = GLOBAL_PARTICLE_BUDGET;
        }
    }

    private static boolean anyPlayerNearby(Level level, double x, double y, double z) {
        // Avoid client-only APIs; works on client Level via common player list
        for (var player : level.players()) {
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= PARTICLE_PLAYER_RANGE_SQ) {
                return true;
            }
        }
        return false;
    }
}