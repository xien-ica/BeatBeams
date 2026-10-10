package xien.ica.beatbeams.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xien.ica.beatbeams.Beatbeams;
import xien.ica.beatbeams.block.beacon.BeamAnimator;
import xien.ica.beatbeams.block.beacon.BeaconRegistry;
import xien.ica.beatbeams.block.beacon.BeaconPresets;
import xien.ica.beatbeams.audio.TrackPlayer;
import xien.ica.beatbeams.net.AudioFrame;

import java.util.function.Consumer;

/**
 * Per-beacon configuration + live, smoothed render state.
 *
 * The per-tick animation lives in {@link BeamAnimator}; this class keeps the config,
 * the public live fields the renderer reads, and persistence.
 *
 * Performance notes (many beacons):
 * - Shared audio frame is read once per tick (no allocations).
 * - dt is clamped so lag spikes don't explode smoothing.
 * - Global particle budget per game tick across ALL beacons.
 * - DustParticleOptions cached per color (no new per spawn).
 * - Particles skipped when far from any player / when budget exhausted.
 */
public class MusicBeaconBlockEntity extends BlockEntity {

    public enum AudioSource {
        NONE, VOLUME, BASS, MID, TREBLE, BEAT;

        /** Reads this source's level (0-1) from an audio frame. */
        public float sample(AudioFrame frame) {
            return switch (this) {
                case NONE -> 0f;
                case VOLUME -> frame.volume();
                case BASS -> frame.bass();
                case MID -> frame.mid();
                case TREBLE -> frame.treble();
                case BEAT -> frame.beat() ? frame.beatStrength() : 0f;
            };
        }
    }

    /** One-shot beat reactions (mutually coordinated — not stacked independently). */
    public enum BeatResponse { NONE, PULSE, FLASH, BOTH }

    public enum ReactivityFeel { SNAPPY, BALANCED, SMOOTH }

    public enum ColorMode { FIXED, RAINBOW, SPECTRAL }

    /** Network-wide timing relative to other beacons. */
    public enum PhaseMode { SYNC, CASCADE, ALTERNATE }

    /**
     * Compass direction of the vertical plane a sweep moves in.
     * yawDegrees = the beam's yaw value that tilts it toward that compass point
     * (yaw 0 tilts north, 90 west, 180 south, 270 east). Flip these if a direction looks mirrored.
     */
    public enum SweepDir {
        NORTH("North (-Z)", 0f), NORTHEAST("Northeast", 315f), EAST("East (+X)", 270f), SOUTHEAST("Southeast", 225f),
        SOUTH("South (+Z)", 180f), SOUTHWEST("Southwest", 135f), WEST("West (-X)", 90f), NORTHWEST("Northwest", 45f);

        public final String label;
        public final float yawDegrees;

        SweepDir(String label, float yawDegrees) {
            this.label = label;
            this.yawDegrees = yawDegrees;
        }
    }

    /** SWAY = back and forth (smooth). SCAN = sweeps From -> To, then jumps back and repeats. */
    public enum SweepStyle { SWAY, SCAN }

    // ---- Persisted configuration ----
    public float yawDegrees = 0f;
    public float pitchDegrees = 30f;
    public float beamLength = 128f;
    /** When true, the beam is length-clamped to the nearest solid block it hits. */
    public boolean collideWithBlocks = false;
    public float baseBeamWidth = 0.4f;
    public int colorRgb = 0x55E0FF;
    public float baseBrightness = 0.8f;

    public AudioSource widthSource = AudioSource.BASS;
    public AudioSource rotationSource = AudioSource.MID;
    public AudioSource brightnessSource = AudioSource.VOLUME;
    public BeatResponse beatResponse = BeatResponse.PULSE;
    public boolean particlesOnTreble = true;
    /** When false, beam/particles are fully off for this beacon (GUI toggle). */
    public boolean enabled = true;
    /**
     * Circuit power from Wire / Connector / lever-button adjacency.
     * Defaults FALSE — beacon is silent until wired (breaking-change default).
     * Not the same as {@link #enabled}: both must be true for the beam to run.
     */
    public boolean circuitPowered = false;

    public float pulseStrength = 0.6f;
    public float pulseAttackMs = 40f;
    public float pulseDecayMs = 260f;
    public float pulseMinScale = 1.0f;
    public float pulseMaxScale = 1.8f;

    public float rotationSpeedDegPerSec = 25f;
    public float rotationAmplitudeDeg = 35f;
    /** Lock spin to detected BPM (1 revolution per bpmSyncBeats beats). */
    public boolean bpmSync = false;
    /** Beats per full 360° turn when bpmSync is on (1, 2, or 4). */
    public int bpmSyncBeats = 1;

    public AudioSource lengthSource = AudioSource.NONE;
    /** Max fractional length change from base (0.05–0.4). */
    public float lengthReactAmount = 0.15f;

    /** 0 = off; dips width/brightness on beat (sidechain). */
    public float duckOnBeat = 0f;

    public ReactivityFeel reactivityFeel = ReactivityFeel.BALANCED;
    public boolean idleMotion = true;
    public AudioSource particlesSource = AudioSource.TREBLE;
    public ColorMode colorMode = ColorMode.FIXED;
    public PhaseMode phaseMode = PhaseMode.SYNC;

    // ---- Sweep (compass sweep between two angles; Yaw/Pitch/Sway then offset the whole sweep, spin is ignored) ----
    public boolean sweepEnabled = false;
    public SweepDir sweepDir = SweepDir.NORTH;
    public SweepStyle sweepStyle = SweepStyle.SWAY;
    /** Angle in the sweep plane: 0 = flat toward sweepDir, 90 = straight up, 180 = flat the opposite way. */
    public float sweepFromDeg = 150f;
    public float sweepToDeg = 30f;
    /** Full sweeps per second (used when BPM sync is off or no BPM is detected). */
    public float sweepSpeed = 0.25f;
    public boolean sweepBpmSync = false;
    /** Beats per full sweep when sweepBpmSync is on. */
    public int sweepBeats = 4;

    // ---- Extra effects (Effects tab) ----
    public boolean rainbowMode = false;
    public float rainbowSpeed = 40f; // degrees hue per second
    public float swayStrength = 0f;  // pitch oscillation degrees
    public float currentPitchOffset = 0f; // live
    public int displayColorRgb = colorRgb; // after rainbow / flash

    // ---- Live render state (client-only, not persisted) ----
    public float currentWidth = baseBeamWidth;
    public float currentBrightness = baseBrightness;
    public float currentYawOffset = 0f;
    /** Smoothed rotation source (avoids BEAT step stutter). */
    public float currentRotationDriver = 0f;
    /** Amplitude wobble degrees applied on top of continuous spin. */
    public float currentYawWobble = 0f;
    /** Length actually used by the renderer: equals beamLength, unless collideWithBlocks
     *  clamps it down to the distance of a solid block hit along the beam's path. */
    public float effectiveBeamLength = beamLength;
    public float currentLength = beamLength;
    /** Sweep progress 0-1 and its speed (cycles/sec); the renderer extrapolates between ticks. */
    public float sweepPhase = 0f;
    public float sweepRate = 0f;

    /**
     * Client-only hook, assigned from BeatbeamsClient. The GUIs edit the CLIENT copy of a beacon;
     * this pushes those edits to the server copy, which is the one that gets saved to disk.
     */
    public static Consumer<MusicBeaconBlockEntity> clientChangedHook = be -> {};

    private long lastTickNanos = -1L;

    private final BeamAnimator animator = new BeamAnimator(this);

    public MusicBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(Beatbeams.MUSIC_BEACON_BLOCK_ENTITY, pos, state);
    }

    public void setCircuitPowered(boolean powered) {
        if (this.circuitPowered == powered) return;
        this.circuitPowered = powered;
        setChanged();
        // Sync via blockstate so the client sees power without a custom BE packet
        if (level != null) {
            BlockState st = level.getBlockState(worldPosition);
            if (st.hasProperty(MusicBeaconBlock.POWERED) && st.getValue(MusicBeaconBlock.POWERED) != powered) {
                // 3 = clients + neighbors (lamps next to beacon are rare; OK)
                level.setBlock(worldPosition, st.setValue(MusicBeaconBlock.POWERED, powered), 2);
            }
        }
    }

    /** Active for rendering/effects when GUI-enabled and circuit-powered. */
    public boolean isActive() {
        if (!enabled) return false;
        if (circuitPowered) return true;
        // Client fallback: trust synced blockstate
        if (level != null) {
            BlockState st = level.getBlockState(worldPosition);
            if (st.hasProperty(MusicBeaconBlock.POWERED) && st.getValue(MusicBeaconBlock.POWERED)) {
                return true;
            }
        }
        return false;
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, MusicBeaconBlockEntity be) {
        BeaconRegistry.touch(be); // lets the Lights Control Panel find this beacon, powered or not
        if (!be.isActive()) {
            be.sweepPhase = 0f; // beacons that power on together start their sweeps together
            return;
        }
        long now = System.nanoTime();
        float dt = be.lastTickNanos < 0 ? 0.05f : (now - be.lastTickNanos) / 1_000_000_000f;
        be.lastTickNanos = now;
        // Cap so a long hitch doesn't overshoot smoothing / pulse
        if (dt > 0.1f) dt = 0.1f;
        if (dt < 0f) dt = 0f;

        AudioFrame frame = TrackPlayer.get().latest(); // SILENCE when nothing is playing

        if (be.sweepEnabled) {
            float rate = be.sweepSpeed;
            float bpm = (float) frame.bpm();
            if (be.sweepBpmSync && bpm > 1f) {
                rate = bpm / 60f / Math.max(1, be.sweepBeats);
            }
            be.sweepRate = rate;
            be.sweepPhase = (be.sweepPhase + rate * dt) % 1f;
        }

        be.animator.tick(level, pos, frame, dt);
    }

    /** Sweep angle (0-180) at a given phase. Phase may exceed 1; it wraps. */
    public float sweepAngleDeg(float phase) {
        float p = phase - (float) Math.floor(phase);
        float t = sweepStyle == SweepStyle.SWAY
                ? 0.5f - 0.5f * (float) Math.cos(p * 2.0 * Math.PI)
                : p;
        return sweepFromDeg + (sweepToDeg - sweepFromDeg) * t;
    }

    /** Beam tilt (from vertical, toward the sweep direction) at a sweep phase. Pitch and Sway shift the whole sweep. */
    public float sweepTilt(float phase) {
        return pitchDegrees + currentPitchOffset + 90f - sweepAngleDeg(phase);
    }

    public boolean wantsPulse() {
        return beatResponse == BeatResponse.PULSE || beatResponse == BeatResponse.BOTH;
    }

    public boolean wantsFlash() {
        return beatResponse == BeatResponse.FLASH || beatResponse == BeatResponse.BOTH;
    }

    /** Peak width scale at full pulse envelope, using Strength between Min and Max. */
    public float effectivePulsePeakScale() {
        float min = pulseMinScale;
        float max = pulseMaxScale;
        if (max < min) max = min;
        return min + (max - min) * Math.max(0f, Math.min(1f, pulseStrength));
    }

    public float pulseEnvelope() {
        return animator.pulseEnvelope();
    }

    public void applyPreset(String name) {
        BeaconPresets.apply(this, name);
        displayColorRgb = colorRgb;
        setChanged();
    }

    /** Runs the client-to-server push whenever a GUI edits this (client-side) beacon. */
    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && level.isClientSide()) {
            clientChangedHook.accept(this);
        }
    }

    /** Sent to the client on chunk load, so the client copy starts from the saved config. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /** Copies every saved setting from another beacon (not the circuit power state). */
    public void copyConfigFrom(MusicBeaconBlockEntity o) {
        yawDegrees = o.yawDegrees;
        pitchDegrees = o.pitchDegrees;
        beamLength = o.beamLength;
        collideWithBlocks = o.collideWithBlocks;
        baseBeamWidth = o.baseBeamWidth;
        colorRgb = o.colorRgb;
        baseBrightness = o.baseBrightness;
        widthSource = o.widthSource;
        rotationSource = o.rotationSource;
        brightnessSource = o.brightnessSource;
        beatResponse = o.beatResponse;
        bpmSync = o.bpmSync;
        bpmSyncBeats = o.bpmSyncBeats;
        particlesOnTreble = o.particlesOnTreble;
        enabled = o.enabled;
        pulseStrength = o.pulseStrength;
        pulseAttackMs = o.pulseAttackMs;
        pulseDecayMs = o.pulseDecayMs;
        rotationSpeedDegPerSec = o.rotationSpeedDegPerSec;
        rotationAmplitudeDeg = o.rotationAmplitudeDeg;
        rainbowMode = o.rainbowMode;
        rainbowSpeed = o.rainbowSpeed;
        swayStrength = o.swayStrength;
        lengthSource = o.lengthSource;
        lengthReactAmount = o.lengthReactAmount;
        duckOnBeat = o.duckOnBeat;
        reactivityFeel = o.reactivityFeel;
        idleMotion = o.idleMotion;
        particlesSource = o.particlesSource;
        colorMode = o.colorMode;
        phaseMode = o.phaseMode;
        sweepEnabled = o.sweepEnabled;
        sweepDir = o.sweepDir;
        sweepStyle = o.sweepStyle;
        sweepFromDeg = o.sweepFromDeg;
        sweepToDeg = o.sweepToDeg;
        sweepSpeed = o.sweepSpeed;
        sweepBpmSync = o.sweepBpmSync;
        sweepBeats = o.sweepBeats;
        displayColorRgb = colorRgb;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putFloat("YawDegrees", yawDegrees);
        output.putFloat("PitchDegrees", pitchDegrees);
        output.putFloat("BeamLength", beamLength);
        output.putBoolean("CollideWithBlocks", collideWithBlocks);
        output.putFloat("BaseBeamWidth", baseBeamWidth);
        output.putInt("ColorRgb", colorRgb);
        output.putFloat("BaseBrightness", baseBrightness);
        output.putString("WidthSource", widthSource.name());
        output.putString("RotationSource", rotationSource.name());
        output.putString("BrightnessSource", brightnessSource.name());
        output.putString("BeatResponse", beatResponse.name());
        output.putBoolean("BpmSync", bpmSync);
        output.putInt("BpmSyncBeats", bpmSyncBeats);
        output.putBoolean("ParticlesOnTreble", particlesOnTreble);
        output.putBoolean("Enabled", enabled);
        output.putBoolean("CircuitPowered", circuitPowered);
        output.putFloat("PulseStrength", pulseStrength);
        output.putFloat("PulseAttackMs", pulseAttackMs);
        output.putFloat("PulseDecayMs", pulseDecayMs);
        output.putFloat("RotationSpeedDegPerSec", rotationSpeedDegPerSec);
        output.putFloat("RotationAmplitudeDeg", rotationAmplitudeDeg);
        output.putBoolean("RainbowMode", rainbowMode);
        output.putFloat("RainbowSpeed", rainbowSpeed);
        output.putFloat("SwayStrength", swayStrength);
        output.putString("LengthSource", lengthSource.name());
        output.putFloat("LengthReactAmount", lengthReactAmount);
        output.putFloat("DuckOnBeat", duckOnBeat);
        output.putString("ReactivityFeel", reactivityFeel.name());
        output.putBoolean("IdleMotion", idleMotion);
        output.putString("ParticlesSource", particlesSource.name());
        output.putString("ColorMode", colorMode.name());
        output.putString("PhaseMode", phaseMode.name());
        output.putBoolean("SweepEnabled", sweepEnabled);
        output.putString("SweepDir", sweepDir.name());
        output.putString("SweepStyle", sweepStyle.name());
        output.putFloat("SweepFromDeg", sweepFromDeg);
        output.putFloat("SweepToDeg", sweepToDeg);
        output.putFloat("SweepSpeed", sweepSpeed);
        output.putBoolean("SweepBpmSync", sweepBpmSync);
        output.putInt("SweepBeats", sweepBeats);

    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        yawDegrees = input.getFloatOr("YawDegrees", 0f);
        pitchDegrees = input.getFloatOr("PitchDegrees", 30f);
        beamLength = input.getFloatOr("BeamLength", 128f);
        collideWithBlocks = input.getBooleanOr("CollideWithBlocks", false);
        effectiveBeamLength = beamLength;
        baseBeamWidth = input.getFloatOr("BaseBeamWidth", 0.4f);
        colorRgb = input.getIntOr("ColorRgb", 0x55E0FF);
        baseBrightness = input.getFloatOr("BaseBrightness", 0.8f);
        widthSource = readEnum(input, "WidthSource", AudioSource.class, AudioSource.BASS);
        rotationSource = readEnum(input, "RotationSource", AudioSource.class, AudioSource.MID);
        brightnessSource = readEnum(input, "BrightnessSource", AudioSource.class, AudioSource.VOLUME);
        if (!input.getStringOr("BeatResponse", "").isEmpty()) {
            beatResponse = readEnum(input, "BeatResponse", BeatResponse.class, BeatResponse.PULSE);
        } else {
            // migrate legacy flags
            boolean p = input.getBooleanOr("PulseOnBeat", true);
            boolean f = input.getBooleanOr("BeatFlash", false);
            if (p && f) beatResponse = BeatResponse.BOTH;
            else if (p) beatResponse = BeatResponse.PULSE;
            else if (f) beatResponse = BeatResponse.FLASH;
            else beatResponse = BeatResponse.NONE;
        }
        bpmSync = input.getBooleanOr("BpmSync", false);
        bpmSyncBeats = input.getIntOr("BpmSyncBeats", 1);
        particlesOnTreble = input.getBooleanOr("ParticlesOnTreble", true);
        enabled = input.getBooleanOr("Enabled", true);
        circuitPowered = input.getBooleanOr("CircuitPowered", false);
        pulseStrength = input.getFloatOr("PulseStrength", 0.6f);
        pulseAttackMs = input.getFloatOr("PulseAttackMs", 40f);
        pulseDecayMs = input.getFloatOr("PulseDecayMs", 260f);
        rotationSpeedDegPerSec = input.getFloatOr("RotationSpeedDegPerSec", 25f);
        rotationAmplitudeDeg = input.getFloatOr("RotationAmplitudeDeg", 35f);
        rainbowMode = input.getBooleanOr("RainbowMode", false);
        rainbowSpeed = input.getFloatOr("RainbowSpeed", 40f);
        swayStrength = input.getFloatOr("SwayStrength", 0f);
        lengthSource = readEnum(input, "LengthSource", AudioSource.class, AudioSource.NONE);
        lengthReactAmount = input.getFloatOr("LengthReactAmount", 0.15f);
        duckOnBeat = input.getFloatOr("DuckOnBeat", 0f);
        reactivityFeel = readEnum(input, "ReactivityFeel", ReactivityFeel.class, ReactivityFeel.BALANCED);
        idleMotion = input.getBooleanOr("IdleMotion", true);
        particlesSource = readEnum(input, "ParticlesSource", AudioSource.class, AudioSource.TREBLE);
        colorMode = readEnum(input, "ColorMode", ColorMode.class,
                rainbowMode ? ColorMode.RAINBOW : ColorMode.FIXED);
        phaseMode = readEnum(input, "PhaseMode", PhaseMode.class, PhaseMode.SYNC);
        sweepEnabled = input.getBooleanOr("SweepEnabled", false);
        sweepDir = readEnum(input, "SweepDir", SweepDir.class, SweepDir.NORTH);
        sweepStyle = readEnum(input, "SweepStyle", SweepStyle.class, SweepStyle.SWAY);
        sweepFromDeg = input.getFloatOr("SweepFromDeg", 150f);
        sweepToDeg = input.getFloatOr("SweepToDeg", 30f);
        sweepSpeed = input.getFloatOr("SweepSpeed", 0.25f);
        sweepBpmSync = input.getBooleanOr("SweepBpmSync", false);
        sweepBeats = input.getIntOr("SweepBeats", 4);
        currentLength = beamLength;

        displayColorRgb = colorRgb;
    }

    private static <E extends Enum<E>> E readEnum(ValueInput input, String key, Class<E> type, E fallback) {
        String name = input.getStringOr(key, "");
        if (name.isEmpty()) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}