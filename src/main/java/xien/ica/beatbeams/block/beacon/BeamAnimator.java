package xien.ica.beatbeams.block.beacon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.AudioSource;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.ColorMode;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.PhaseMode;
import xien.ica.beatbeams.net.AudioFrame;

/**
 * Per-tick animation for one Music Beacon: turns the shared audio frame into the smoothed
 * render state (width, brightness, length, spin, pulse, duck, colors, collision, particles).
 *
 * Reads the beacon's persisted config and writes its public live fields (currentWidth,
 * displayColorRgb, ...), which the renderer reads. The timing/smoothing state that nothing
 * else needs (pulse phase, duck phase, source-transition ticks, ...) is private to this class.
 */
public final class BeamAnimator {

    /** Re-raycast every N ticks instead of every tick - a beam doesn't need to react
     *  to the world at 20Hz, and clip() isn't free across many beacons. */
    private static final int COLLISION_CHECK_INTERVAL = 5;
    /** While a sweep is moving the beam, re-check more often so the clamp keeps up. */
    private static final int SWEEP_COLLISION_INTERVAL = 1;

    private final MusicBeaconBlockEntity be;
    private final BeaconParticles particles = new BeaconParticles();

    private float yawWobblePhase = 0f;
    private AudioSource lastWidthSource = null;
    private AudioSource lastBrightSource = null;
    private AudioSource lastRotSource = null;
    private int sourceTransitionTicks = 0;
    private float duckPhaseMs = -1f;
    private float prevMid = 0f;
    private float prevTreb = 0f;
    private float secondaryPulseMs = -1f;
    private float idlePhase = 0f;
    private float pulsePhaseMs = -1f;
    private float huePhase = 0f;
    private int collisionCheckCooldown = 0;

    public BeamAnimator(MusicBeaconBlockEntity be) {
        this.be = be;
    }

    /** Advances every animation by dt seconds. Order matters (later steps read earlier results). */
    public void tick(Level level, BlockPos pos, AudioFrame frame, float dt) {
        updateOnsets(frame, dt);
        updateDuck(frame, dt);
        updateWidth(frame, dt);
        updateBrightness(frame, dt);
        updateLength(frame, dt);
        updateRotation(frame, dt);
        updatePulse(frame, dt);
        updateEffects(frame, dt);
        updateIdle(frame, dt);
        updateBeamCollision(level, pos);
        spawnParticles(level, pos, frame);
    }

    private static float lerp(float from, float to, float t) {
        if (t >= 1f) return to;
        if (t <= 0f) return from;
        return from + (to - from) * t;
    }

    /** Direction the beam really points: the compass sweep when it is on, otherwise the normal aim. */
    private float aimYaw() {
        if (be.sweepEnabled) return be.sweepDir.yawDegrees + be.yawDegrees;
        return be.yawDegrees + be.currentYawOffset;
    }

    /** Sweep tilt (same rule the renderer uses; Pitch and Sway shift it). */
    private float sweepPitch() {
        return be.sweepTilt(be.sweepPhase);
    }

    private float feelRateScale() {
        return switch (be.reactivityFeel) {
            case SNAPPY -> 1.55f;
            case SMOOTH -> 0.55f;
            default -> 1f;
        };
    }

    /** Slows a smoothing rate during a source switch, then applies the reactivity feel. */
    private float smoothingRate(float rate) {
        if (sourceTransitionTicks > 0) rate *= 0.45f;
        return rate * feelRateScale();
    }

    private float phaseBeatDelayMs() {
        if (be.phaseMode == PhaseMode.SYNC) return 0f;
        BlockPos p = be.getBlockPos();
        int h = p.getX() * 31 + p.getZ() * 17 + p.getY();
        return switch (be.phaseMode) {
            case CASCADE -> Math.floorMod(h, 400); // 0–400ms stagger
            case ALTERNATE -> (Math.floorMod(h, 2) == 0) ? 0f : 180f;
            default -> 0f;
        };
    }

    private float duckAmount() {
        if (duckPhaseMs < 0f || be.duckOnBeat <= 0.01f) return 0f;
        // fast dip ~50ms, recover ~200ms
        float attack = 40f, decay = 200f;
        if (duckPhaseMs <= attack) return be.duckOnBeat * (duckPhaseMs / attack);
        float t = (duckPhaseMs - attack) / decay;
        if (t >= 1f) return 0f;
        return be.duckOnBeat * (1f - t);
    }

    private void updateDuck(AudioFrame frame, float dt) {
        if (be.duckOnBeat <= 0.01f) {
            duckPhaseMs = -1f;
            return;
        }
        if (frame.beat() && duckPhaseMs < 0f) {
            duckPhaseMs = -phaseBeatDelayMs(); // may start negative = delayed
        }
        if (duckPhaseMs >= -phaseBeatDelayMs() - 1f) {
            duckPhaseMs += dt * 1000f;
            if (duckPhaseMs >= 0f && duckPhaseMs >= 40f + 200f) {
                duckPhaseMs = -1f;
            }
        }
    }

    private void updateOnsets(AudioFrame frame, float dt) {
        float mid = frame.mid();
        float treb = frame.treble();
        // simple rising-edge onset (multi-band)
        boolean midOnset = (mid - prevMid) > 0.12f && mid > 0.35f;
        boolean trebOnset = (treb - prevTreb) > 0.14f && treb > 0.3f;
        prevMid = mid;
        prevTreb = treb;
        if ((midOnset || trebOnset) && secondaryPulseMs < 0f) {
            secondaryPulseMs = 0f;
        }
        if (secondaryPulseMs >= 0f) {
            secondaryPulseMs += dt * 1000f;
            if (secondaryPulseMs > 180f) secondaryPulseMs = -1f;
        }
    }

    // NOTE: currently unused (nothing reads the envelope yet); updateOnsets() feeds it.
    private float secondaryEnvelope() {
        if (secondaryPulseMs < 0f) return 0f;
        if (secondaryPulseMs < 30f) return secondaryPulseMs / 30f;
        return 1f - Math.min(1f, (secondaryPulseMs - 30f) / 150f);
    }

    private void updateLength(AudioFrame frame, float dt) {
        float signal = be.lengthSource.sample(frame);
        float target = be.beamLength * (1f + be.lengthReactAmount * (signal - 0.5f) * 2f);
        target = Math.max(4f, Math.min(256f, target));
        float rate = 3f * feelRateScale();
        if (sourceTransitionTicks > 0) rate *= 0.45f;
        be.currentLength = lerp(be.currentLength, target, dt * rate);
        // collision uses beamLength as max; render uses effective from currentLength when no collide
        if (!be.collideWithBlocks) {
            be.effectiveBeamLength = be.currentLength;
        }
    }

    private void updateIdle(AudioFrame frame, float dt) {
        if (!be.idleMotion) {
            return;
        }
        boolean silent = frame.volume() < 0.04f && !frame.beat();
        if (silent) {
            idlePhase += dt * 0.7f;
            // gentle residual motion when no music
            be.currentPitchOffset += (float) (Math.sin(idlePhase) * 0.15f * dt * 8f);
            be.currentYawWobble += (float) (Math.sin(idlePhase * 0.6) * 0.4f * dt * 8f);
        }
    }

    private void updateWidth(AudioFrame frame, float dt) {
        float signal = be.widthSource.sample(frame);
        // Duck continuous width while Pulse owns the spike (stops double-bounce)
        float pulse = pulseEnvelope();
        if (be.wantsPulse() && pulse > 0f) {
            signal *= (1f - 0.85f * pulse);
        }
        float target = be.baseBeamWidth * (0.6f + 1.4f * signal);
        // Source-aware rate: snappier for BASS/BEAT, gentler for VOLUME
        if (lastWidthSource != be.widthSource) {
            lastWidthSource = be.widthSource;
            sourceTransitionTicks = Math.max(sourceTransitionTicks, 8);
        }
        float rate = switch (be.widthSource) {
            case BEAT, BASS -> 12f;
            case TREBLE -> 10f;
            case VOLUME -> 5f;
            default -> 8f;
        };
        rate = smoothingRate(rate);
        be.currentWidth = lerp(be.currentWidth, target, dt * rate);
    }

    private void updateBrightness(AudioFrame frame, float dt) {
        float signal = be.brightnessSource.sample(frame);
        float pulse = pulseEnvelope();
        if (be.wantsPulse() && pulse > 0f) {
            // slight duck so Pulse + brightness don't both slam the same kick
            signal *= (1f - 0.4f * pulse);
        }
        float target = be.baseBrightness * (0.5f + 0.9f * signal);
        target *= (1f - 0.55f * duckAmount());
        if (target > 1.5f) target = 1.5f;
        if (lastBrightSource != be.brightnessSource) {
            lastBrightSource = be.brightnessSource;
            sourceTransitionTicks = Math.max(sourceTransitionTicks, 8);
        }
        float rate = switch (be.brightnessSource) {
            case BEAT, BASS -> 10f;
            case VOLUME -> 4f;
            default -> 6f;
        };
        rate = smoothingRate(rate);
        be.currentBrightness = lerp(be.currentBrightness, target, dt * rate);
    }

    private void updateRotation(AudioFrame frame, float dt) {
        float driver = be.rotationSource.sample(frame);
        if (lastRotSource != be.rotationSource) {
            lastRotSource = be.rotationSource;
            sourceTransitionTicks = 8;
        }
        float rate = switch (be.rotationSource) {
            case BEAT -> 10f;
            case BASS -> 8f;
            case VOLUME -> 4f;
            default -> 6f;
        };
        if (sourceTransitionTicks > 0) {
            rate *= 0.45f; // absorb source-switch discontinuities
            sourceTransitionTicks--;
        }
        be.currentRotationDriver = lerp(be.currentRotationDriver, driver, dt * rate);

        float spinDegPerSec;
        if (be.bpmSync && frame.bpm() > 1f) {
            // 1 full turn per bpmSyncBeats beats
            int beats = Math.max(1, Math.min(8, be.bpmSyncBeats));
            float revsPerSec = (frame.bpm() / 60f) / (float) beats;
            spinDegPerSec = revsPerSec * 360f;
            // still allow mild driver modulation (0.85–1.15) so silence doesn't hard-stop
            spinDegPerSec *= 0.85f + 0.3f * be.currentRotationDriver;
        } else {
            spinDegPerSec = be.rotationSpeedDegPerSec * (0.3f + be.currentRotationDriver);
        }
        be.currentYawOffset += spinDegPerSec * dt;
        if (be.currentYawOffset >= 360f || be.currentYawOffset < 0f) {
            be.currentYawOffset = be.currentYawOffset - (float) Math.floor(be.currentYawOffset / 360f) * 360f;
        }

        if (be.rotationAmplitudeDeg > 0.01f) {
            yawWobblePhase += dt * (2.5f + 3f * be.currentRotationDriver);
            be.currentYawWobble = (float) (Math.sin(yawWobblePhase) * be.rotationAmplitudeDeg * be.currentRotationDriver);
        } else {
            be.currentYawWobble = 0f;
        }
    }

    private void updatePulse(AudioFrame frame, float dt) {
        if (be.wantsPulse() && frame.beat() && pulsePhaseMs < 0f) {
            float delay = phaseBeatDelayMs();
            pulsePhaseMs = delay > 0f ? -delay : 0f;
        }
        if (pulsePhaseMs > -10000f) {
            pulsePhaseMs += dt * 1000f;
            if (pulsePhaseMs >= 0f && pulsePhaseMs >= be.pulseAttackMs + be.pulseDecayMs) {
                pulsePhaseMs = -1f;
            }
        }
    }

    /**
     * Keeps effectiveBeamLength in sync with beamLength, clamping it to the nearest
     * solid block along the beam's current direction when collideWithBlocks is on.
     * Raycast is throttled to COLLISION_CHECK_INTERVAL ticks - direction only drifts
     * via rotationSpeedDegPerSec/sway, so a short lag before the beam "catches up"
     * to a moved block is not noticeable, and it keeps many-beacon scenes cheap.
     */
    private void updateBeamCollision(Level level, BlockPos pos) {
        float maxLen = be.currentLength > 1f ? be.currentLength : be.beamLength;
        if (!be.collideWithBlocks) {
            be.effectiveBeamLength = maxLen;
            return;
        }
        if (collisionCheckCooldown > 0) {
            collisionCheckCooldown--;
            return;
        }
        collisionCheckCooldown = be.sweepEnabled ? SWEEP_COLLISION_INTERVAL : COLLISION_CHECK_INTERVAL;
        float pitch = be.sweepEnabled ? sweepPitch() : be.pitchDegrees + be.currentPitchOffset;
        be.effectiveBeamLength = BeamCollision.trace(level, pos, aimYaw(), pitch, maxLen);
    }

    public float pulseEnvelope() {
        if (pulsePhaseMs < 0f) return 0f;
        if (pulsePhaseMs <= be.pulseAttackMs) {
            return be.pulseAttackMs <= 0f ? 1f : pulsePhaseMs / be.pulseAttackMs;
        }
        float decayT = (pulsePhaseMs - be.pulseAttackMs) / Math.max(1f, be.pulseDecayMs);
        return 1f - Math.min(1f, decayT);
    }

    private void updateEffects(AudioFrame frame, float dt) {
        // Sway: gentle pitch oscillation driven by mid/volume
        if (be.swayStrength > 0.01f) {
            float driver = 0.3f + 0.7f * AudioSource.MID.sample(frame);
            be.currentPitchOffset = (float) (Math.sin(System.nanoTime() * 1e-9 * 2.2) * be.swayStrength * driver);
        } else {
            be.currentPitchOffset = 0f;
        }

        int base = be.colorRgb & 0xFFFFFF;
        if (be.colorMode == ColorMode.RAINBOW || be.rainbowMode) {
            be.colorMode = ColorMode.RAINBOW;
            be.rainbowMode = true;
            huePhase += be.rainbowSpeed * dt;
            if (huePhase >= 360f) huePhase -= 360f;
            be.displayColorRgb = BeaconColors.hsvToRgb(huePhase, 0.85f, 1.0f);
        } else if (be.colorMode == ColorMode.SPECTRAL) {
            be.rainbowMode = false;
            be.displayColorRgb = BeaconColors.spectral(
                    frame.bass(), frame.mid(), frame.treble(), frame.volume());
        } else {
            be.rainbowMode = false;
            be.displayColorRgb = base;
        }

        // Beat flash (phase-delayed)
        boolean flashNow = be.wantsFlash() && frame.beat();
        if (flashNow && phaseBeatDelayMs() > 0f) {
            // simple: skip delayed flash for now if delay; use pulse delay path is harder for one-frame flash
            flashNow = be.phaseMode == PhaseMode.SYNC;
        }
        if (flashNow) {
            be.displayColorRgb = BeaconColors.flash(be.displayColorRgb);
        }
    }

    private void spawnParticles(Level level, BlockPos pos, AudioFrame frame) {
        float pitch = be.sweepEnabled ? sweepPitch() : be.pitchDegrees;
        particles.spawn(level, pos, frame, be.particlesSource, be.particlesOnTreble,
                aimYaw(), pitch, be.beamLength, be.colorRgb);
    }
}