package xien.ica.beatbeams.net;

/**
 * One analysis frame received from the Windows audio analyzer, e.g.:
 * {"timestamp":123456789,"volume":0.73,"bass":0.91,"mid":0.42,
 *  "treble":0.66,"beat":true,"beatStrength":0.88,"bpm":128.4}
 *
 * All fields other than timestamp/beat are already normalized 0.0-1.0 by the
 * analyzer. Minecraft never receives raw audio -- only this compact record.
 *
 * Lives in the common ("main") source set on purpose: it's a plain data
 * record with no Minecraft client-only dependency, so both the block entity
 * (main) and the renderer (client) can share the same type.
 */
public record AudioFrame(
        long timestamp,
        float volume,
        float bass,
        float mid,
        float treble,
        boolean beat,
        float beatStrength,
        float bpm
) {
    public static final AudioFrame SILENCE = new AudioFrame(0L, 0f, 0f, 0f, 0f, false, 0f, 0f);
}
