package xien.ica.beatbeams.block.beacon;

/** Stateless color helpers for the Music Beacon beam. All colors are packed 0xRRGGBB. */
public final class BeaconColors {

    private BeaconColors() {}

    /** Hue in degrees (0-360), saturation and value in 0-1. */
    public static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = v - c;
        float r = 0, g = 0, b = 0;
        if (h < 60) { r = c; g = x; }
        else if (h < 120) { r = x; g = c; }
        else if (h < 180) { g = c; b = x; }
        else if (h < 240) { g = x; b = c; }
        else if (h < 300) { r = x; b = c; }
        else { r = c; b = x; }
        int ri = Math.min(255, Math.max(0, (int) ((r + m) * 255)));
        int gi = Math.min(255, Math.max(0, (int) ((g + m) * 255)));
        int bi = Math.min(255, Math.max(0, (int) ((b + m) * 255)));
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Bass -> warm, mid -> greenish, treble -> cool; saturation follows volume. */
    public static int spectral(float bass, float mid, float treble, float volume) {
        float sum = bass + mid + treble + 1e-4f;
        float hue = (bass / sum) * 30f + (mid / sum) * 120f + (treble / sum) * 220f;
        float sat = 0.55f + 0.4f * volume;
        return hsvToRgb(hue, sat, 0.85f + 0.15f * volume);
    }

    /** Brightens each channel by 80 (clamped) for the one-frame beat flash. */
    public static int flash(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 80);
        int g = Math.min(255, ((rgb >> 8) & 0xFF) + 80);
        int b = Math.min(255, (rgb & 0xFF) + 80);
        return (r << 16) | (g << 8) | b;
    }
}