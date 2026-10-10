package xien.ica.beatbeams.block.beacon;

import java.util.LinkedHashMap;
import java.util.Map;

import xien.ica.beatbeams.block.MusicBeaconBlockEntity;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.AudioSource;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.BeatResponse;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.ColorMode;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.PhaseMode;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.ReactivityFeel;

import static xien.ica.beatbeams.block.MusicBeaconBlockEntity.AudioSource.*;

/** Named looks for the Music Beacon. Add a preset by adding one row to the table. */
public final class BeaconPresets {

    public static final String SWEEP = "Sweep";

    /**
     * One preset. Yaw is always 0. Optional extras are added with the fluent
     * methods below; anything not set falls back to "off".
     */
    public record Preset(
            float pitch, float length, float width, float brightness, int color,
            AudioSource widthSrc, AudioSource rotationSrc, AudioSource brightnessSrc,
            BeatResponse beat, boolean particlesOnTreble, float pulseStrength,
            float spinDegPerSec, float wobbleDeg,
            float rainbowSpeed, float sway, boolean bpmSync) {

        /** Rainbow color cycling at the given hue speed (deg/sec). */
        Preset withRainbow(float speed) {
            return new Preset(pitch, length, width, brightness, color, widthSrc, rotationSrc,
                    brightnessSrc, beat, particlesOnTreble, pulseStrength, spinDegPerSec,
                    wobbleDeg, speed, sway, bpmSync);
        }

        /** Pitch sway in degrees. */
        Preset withSway(float degrees) {
            return new Preset(pitch, length, width, brightness, color, widthSrc, rotationSrc,
                    brightnessSrc, beat, particlesOnTreble, pulseStrength, spinDegPerSec,
                    wobbleDeg, rainbowSpeed, degrees, bpmSync);
        }

        /** Lock spin to detected BPM (1 beat per turn). */
        Preset withBpmSync() {
            return new Preset(pitch, length, width, brightness, color, widthSrc, rotationSrc,
                    brightnessSrc, beat, particlesOnTreble, pulseStrength, spinDegPerSec,
                    wobbleDeg, rainbowSpeed, sway, true);
        }
    }

    /** Column order: pitch, length, width, brightness, color | width/rotation/brightness sources
     *  | beat response, particles on treble, pulse strength | spin deg/s, wobble deg. */
    private static Preset p(float pitch, float length, float width, float brightness, int color,
                            AudioSource w, AudioSource r, AudioSource b,
                            BeatResponse beat, boolean particles, float pulse,
                            float spin, float wobble) {
        return new Preset(pitch, length, width, brightness, color, w, r, b,
                beat, particles, pulse, spin, wobble, 0f, 0f, false);
    }

    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("Default",  p(30, 128, 0.40f, 0.80f, 0x55E0FF, BASS,   MID,    VOLUME, BeatResponse.PULSE, true,  0.55f, 25, 20));
        // Retuned post-smoothing/duck: no pulse+bass width stack; BEAT spin smoothed.
        // Flash only - width comes from BASS.
        PRESETS.put("Party",    p(45,  36, 0.55f, 1.00f, 0xFF55FF, BASS,   BEAT,   VOLUME, BeatResponse.FLASH, true,  0.50f, 42, 25).withRainbow(70f));
        PRESETS.put("Chill",    p(20,  16, 0.30f, 0.60f, 0x55AAFF, VOLUME, MID,    VOLUME, BeatResponse.NONE,  false, 0.30f, 10, 12).withSway(8f));
        PRESETS.put("Bass",     p(60,  24, 0.90f, 1.00f, 0xFFAA00, BASS,   NONE,   BASS,   BeatResponse.FLASH, false, 0.70f,  5, 10));
        PRESETS.put("Vertical", p(90,  64, 0.35f, 0.90f, 0xFFFFFF, BASS,   MID,    VOLUME, BeatResponse.PULSE, true,  0.50f, 20, 15));
        PRESETS.put("Rainbow",  p(50,  40, 0.50f, 1.00f, 0xFFFFFF, BASS,   MID,    VOLUME, BeatResponse.PULSE, true,  0.60f, 30, 20).withRainbow(80f));
        // Retuned: single beat channel - brightness+flash, not width+pulse+flash
        PRESETS.put("Strobe",   p(40,  28, 0.50f, 1.05f, 0xFFFFFF, NONE,   NONE,   BEAT,   BeatResponse.FLASH, true,  0.80f,  0,  0));
        PRESETS.put("Sway",     p(35,  20, 0.40f, 0.85f, 0x66FFAA, VOLUME, MID,    VOLUME, BeatResponse.PULSE, false, 0.40f, 15, 18).withSway(18f));
        PRESETS.put("Laser",    p( 5,  80, 0.15f, 1.30f, 0xFF2255, TREBLE, NONE,   VOLUME, BeatResponse.PULSE, true,  0.75f,  0,  0));
        PRESETS.put("Sky",      p(90, 256, 0.50f, 1.00f, 0x88CCFF, BASS,   NONE,   VOLUME, BeatResponse.PULSE, true,  0.45f,  0,  0));
        PRESETS.put("BPM Sync", p(50,  48, 0.45f, 0.95f, 0xAA66FF, BASS,   VOLUME, VOLUME, BeatResponse.PULSE, true,  0.50f,  0, 15).withBpmSync());
        // Pitch 0 = the sweep is centered on straight up; sweep-specific values are set in apply().
        PRESETS.put(SWEEP,      p( 0, 128, 0.40f, 0.85f, 0x55E0FF, BASS,   NONE,   VOLUME, BeatResponse.PULSE, true,  0.50f,  0,  0));
    }

    private BeaconPresets() {}

    /** Resets the effect toggles, then applies the named preset (unknown names only reset). */
    public static void apply(MusicBeaconBlockEntity be, String name) {
        be.rainbowMode = false;
        be.colorMode = ColorMode.FIXED;
        be.swayStrength = 0f;
        be.rainbowSpeed = 40f;
        be.bpmSync = false;
        be.bpmSyncBeats = 1;
        be.beatResponse = BeatResponse.NONE;
        be.duckOnBeat = 0f;
        be.lengthSource = AudioSource.NONE;
        be.phaseMode = PhaseMode.SYNC;
        be.sweepEnabled = false;
        be.reactivityFeel = ReactivityFeel.BALANCED;

        Preset p = PRESETS.get(name);
        if (p == null) return;

        be.yawDegrees = 0f;
        be.pitchDegrees = p.pitch();
        be.beamLength = p.length();
        be.baseBeamWidth = p.width();
        be.baseBrightness = p.brightness();
        be.colorRgb = p.color();
        be.widthSource = p.widthSrc();
        be.rotationSource = p.rotationSrc();
        be.brightnessSource = p.brightnessSrc();
        be.beatResponse = p.beat();
        be.particlesOnTreble = p.particlesOnTreble();
        be.pulseStrength = p.pulseStrength();
        be.rotationSpeedDegPerSec = p.spinDegPerSec();
        be.rotationAmplitudeDeg = p.wobbleDeg();

        if (p.rainbowSpeed() > 0f) {
            be.rainbowMode = true;
            be.colorMode = ColorMode.RAINBOW;
            be.rainbowSpeed = p.rainbowSpeed();
        }
        be.swayStrength = p.sway();
        be.bpmSync = p.bpmSync();

        if (SWEEP.equals(name)) {
            be.sweepEnabled = true;
            be.sweepDir = MusicBeaconBlockEntity.SweepDir.NORTH;
            be.sweepStyle = MusicBeaconBlockEntity.SweepStyle.SWAY;
            be.sweepFromDeg = 150f;
            be.sweepToDeg = 30f;
            be.sweepSpeed = 0.25f;
            be.sweepBpmSync = false;
            be.sweepBeats = 4;
        }
    }
}