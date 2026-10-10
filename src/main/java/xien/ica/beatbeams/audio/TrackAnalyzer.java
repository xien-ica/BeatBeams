package xien.ica.beatbeams.audio;

import xien.ica.beatbeams.net.AudioFrame;

import java.util.Arrays;

/**
 * Offline analysis of a whole track into one {@link AudioFrame} per hop.
 *
 * This is a port of the old Python analyzer (same 2048 window / 512 hop, same
 * band splits, dB normalisation, envelope time constants and onset-flux beat
 * detector) so the beams should feel the same. Differences from the live version:
 *  - windows are centred on the frame time instead of trailing it, so a frame
 *    reflects the sound at its own timestamp (no half-window lag);
 *  - the normalisation reference also has a per-track floor, so quiet passages
 *    do not get inflated to full scale;
 *  - BPM comes from autocorrelation over the whole track and is constant.
 */
public final class TrackAnalyzer {
    public static final int HOP = 512;
    public static final int WINDOW = 2048;

    // Same values as audio_analyzer.py
    private static final double BASS_LO = 20, BASS_HI = 150;
    private static final double MID_LO = 150, MID_HI = 2000;
    private static final double TREBLE_LO = 2000, TREBLE_HI = 12000;

    private static final double BEAT_RISE_TAU = 0.015;
    private static final double BEAT_FLOOR_TAU = 1.0;
    private static final double BEAT_SENSITIVITY = 1.6;
    private static final double BEAT_MIN_FLUX = 0.02;
    private static final double MIN_BEAT_INTERVAL = 0.200;
    private static final double BEAT_LATCH = 0.090;

    // attack, release (seconds)
    private static final double[] TAU_VOLUME = {0.03, 0.18};
    private static final double[] TAU_BASS = {0.015, 0.11};
    private static final double[] TAU_MID = {0.03, 0.14};
    private static final double[] TAU_TREBLE = {0.02, 0.10};

    private static final double REF_DECAY_TAU = 4.0;
    private static final double NORMALIZE_DB_RANGE = 24.0;
    /** The reference never drops below this fraction of the track's own 98th-percentile level. */
    private static final double REF_TRACK_FLOOR = 0.5;

    private static final double MIN_BPM = 70, MAX_BPM = 180;

    public record Result(AudioFrame[] frames, float framesPerSecond, float bpm) {}

    private TrackAnalyzer() {}

    public static Result analyze(Pcm pcm) {
        final int sr = pcm.sampleRate();
        final short[] st = pcm.stereo();
        final int n = pcm.frames();
        final int frameCount = Math.max(1, n / HOP + 1);
        final double dt = HOP / (double) sr;
        final double fps = sr / (double) HOP;
        final double binHz = sr / (double) WINDOW;
        final int half = WINDOW / 2;

        int[] bassBins = bins(BASS_LO, BASS_HI, binHz, half);
        int[] midBins = bins(MID_LO, MID_HI, binHz, half);
        int[] trebBins = bins(TREBLE_LO, TREBLE_HI, binHz, half);
        int fluxHi = Math.max(2, Math.min(half, (int) (8000 / binHz)));

        float[] mono = new float[n];
        for (int i = 0; i < n; i++) {
            mono[i] = (st[2 * i] + st[2 * i + 1]) / 65536f;
        }
        double[] hann = new double[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / WINDOW);
        }

        // ---- Pass 1: raw band levels + a wideband onset envelope (for tempo) ----
        Fft fft = new Fft(WINDOW);
        double[] re = new double[WINDOW];
        double[] im = new double[WINDOW];
        double[] mag = new double[half + 1];
        double[] prevLog = new double[half + 1];
        double[] vol = new double[frameCount];
        double[] bass = new double[frameCount];
        double[] mid = new double[frameCount];
        double[] treb = new double[frameCount];
        double[] flux = new double[frameCount];

        for (int f = 0; f < frameCount; f++) {
            int start = f * HOP - WINDOW / 2;
            double sumSq = 0;
            for (int i = 0; i < WINDOW; i++) {
                int idx = start + i;
                double x = (idx >= 0 && idx < n) ? mono[idx] : 0.0;
                sumSq += x * x;
                re[i] = x * hann[i];
                im[i] = 0.0;
            }
            vol[f] = Math.sqrt(sumSq / WINDOW);
            fft.transform(re, im);
            for (int k = 0; k <= half; k++) {
                mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            }
            bass[f] = bandRms(mag, bassBins);
            mid[f] = bandRms(mag, midBins);
            treb[f] = bandRms(mag, trebBins);

            double fl = 0;
            for (int k = 1; k <= fluxHi; k++) {
                double l = Math.log1p(mag[k]);
                if (f > 0) fl += Math.max(0.0, l - prevLog[k]);
                prevLog[k] = l;
            }
            flux[f] = fl;
        }

        float bpm = estimateBpm(flux, fps);

        // ---- Pass 2: normalise, smooth, detect beats ----
        double floorVol = REF_TRACK_FLOOR * percentile(vol, 0.98);
        double floorBass = REF_TRACK_FLOOR * percentile(bass, 0.98);
        double floorMid = REF_TRACK_FLOOR * percentile(mid, 0.98);
        double floorTreb = REF_TRACK_FLOOR * percentile(treb, 0.98);
        double refVol = 0, refBass = 0, refMid = 0, refTreb = 0;
        double decay = Math.exp(-dt / REF_DECAY_TAU);

        Envelope eVol = new Envelope(TAU_VOLUME);
        Envelope eBass = new Envelope(TAU_BASS);
        Envelope eMid = new Envelope(TAU_MID);
        Envelope eTreb = new Envelope(TAU_TREBLE);
        BeatDetector detector = new BeatDetector();

        AudioFrame[] frames = new AudioFrame[frameCount];
        for (int f = 0; f < frameCount; f++) {
            double t = f * dt;
            refVol = Math.max(Math.max(vol[f], refVol * decay), floorVol);
            refBass = Math.max(Math.max(bass[f], refBass * decay), floorBass);
            refMid = Math.max(Math.max(mid[f], refMid * decay), floorMid);
            refTreb = Math.max(Math.max(treb[f], refTreb * decay), floorTreb);

            double bassTarget = dbNormalize(bass[f], refBass);
            double v = eVol.update(dbNormalize(vol[f], refVol), dt);
            double b = eBass.update(bassTarget, dt);
            double m = eMid.update(dbNormalize(mid[f], refMid), dt);
            double tr = eTreb.update(dbNormalize(treb[f], refTreb), dt);

            if (f == 0) detector.prime(bassTarget);
            detector.process(bassTarget, t, dt);
            frames[f] = new AudioFrame(
                    (long) (t * 1000.0),
                    clamp01(v), clamp01(b), clamp01(m), clamp01(tr),
                    detector.isBeat,
                    (float) detector.strength,
                    bpm);
        }
        return new Result(frames, (float) fps, bpm);
    }

    // ------------------------------------------------------------------ helpers

    private static int[] bins(double lo, double hi, double binHz, int half) {
        int a = Math.max(1, (int) Math.ceil(lo / binHz));
        int b = Math.min(half, (int) Math.floor(hi / binHz));
        if (b < a) b = a;
        return new int[]{a, b};
    }

    /** sqrt(mean(mag^2)) over an inclusive bin range. */
    private static double bandRms(double[] mag, int[] range) {
        double sum = 0;
        for (int k = range[0]; k <= range[1]; k++) sum += mag[k] * mag[k];
        return Math.sqrt(sum / (range[1] - range[0] + 1));
    }

    private static double dbNormalize(double value, double ref) {
        if (ref <= 1e-9 || value <= 0.0) return 0.0;
        double ratio = value / ref;
        if (ratio >= 1.0) return 1.0;
        double db = 20.0 * Math.log10(ratio);
        if (db <= -NORMALIZE_DB_RANGE) return 0.0;
        return 1.0 + db / NORMALIZE_DB_RANGE;
    }

    private static float clamp01(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    private static double percentile(double[] data, double p) {
        double[] copy = Arrays.copyOf(data, data.length);
        Arrays.sort(copy);
        return copy[Math.min(copy.length - 1, (int) (p * copy.length))];
    }

    /** Fast attack / slow release follower driven by real time. */
    private static final class Envelope {
        private final double attack, release;
        private double value;

        Envelope(double[] taus) {
            this.attack = taus[0];
            this.release = taus[1];
        }

        double update(double target, double dt) {
            double tau = target > value ? attack : release;
            double alpha = tau <= 0 ? 1.0 : -Math.expm1(-dt / tau);
            value += (target - value) * alpha;
            return value;
        }
    }

    /** Onset-flux beat detector, same logic as the Python BeatDetector, plus the 90 ms latch. */
    private static final class BeatDetector {
        boolean isBeat;
        double strength;

        private double level, prevLevel, fluxFloor, fluxVar;
        private double lastBeatTime = -1e9, beatUntil = -1e9, lastStrength;

        /** Start from the first frame's level so the detector does not see a fake rise from silence. */
        void prime(double firstLevel) {
            level = firstLevel;
            prevLevel = firstLevel;
        }

        void process(double bassValue, double now, double dt) {
            double riseAlpha = -Math.expm1(-dt / BEAT_RISE_TAU);
            level += (bassValue - level) * riseAlpha;
            double flux = Math.max(0.0, level - prevLevel);
            prevLevel = level;

            double floorAlpha = -Math.expm1(-dt / BEAT_FLOOR_TAU);
            double delta = flux - fluxFloor;
            fluxFloor += delta * floorAlpha;
            fluxVar += (delta * delta - fluxVar) * floorAlpha;

            double std = fluxVar > 0 ? Math.sqrt(fluxVar) : 0.0;
            double threshold = fluxFloor + std * BEAT_SENSITIVITY + BEAT_MIN_FLUX;

            if (flux > threshold && (now - lastBeatTime) > MIN_BEAT_INTERVAL) {
                lastStrength = Math.min(1.0, (flux - threshold) / (threshold + 1e-6));
                lastBeatTime = now;
                beatUntil = now + BEAT_LATCH;
            }
            isBeat = now <= beatUntil;
            strength = isBeat ? lastStrength : 0.0;
        }
    }

    /**
     * Global tempo from the onset envelope: autocorrelation, then a comb score over the
     * first four multiples of each candidate beat period (which is much finer than the
     * single-lag resolution), with a mild prior toward 120 BPM to settle half/double tempo.
     */
    static float estimateBpm(double[] onset, double fps) {
        int n = onset.length;
        if (n < fps * 8) return 0f;

        // Remove the slow local mean so only onsets remain.
        int w = ((int) (fps * 0.5)) | 1;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + onset[i];
        double[] o = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - w / 2);
            int b = Math.min(n, i + w / 2 + 1);
            double mean = (prefix[b] - prefix[a]) / (b - a);
            o[i] = Math.max(0.0, onset[i] - mean);
        }

        int maxLag = (int) (4 * fps * 60.0 / MIN_BPM) + 2;
        if (maxLag >= n / 2) return 0f;
        double[] acf = new double[maxLag + 2];
        for (int lag = 0; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < n; i++) sum += o[i] * o[i + lag];
            acf[lag] = sum / (n - lag);
        }
        if (acf[0] <= 1e-12) return 0f;

        double bestScore = -1, bestBpm = 0;
        for (double bpm = MIN_BPM; bpm <= MAX_BPM; bpm += 0.25) {
            double period = fps * 60.0 / bpm;
            double score = 0;
            for (int k = 1; k <= 4; k++) score += acfAt(acf, k * period, maxLag);
            double octaves = Math.log(bpm / 120.0) / Math.log(2.0);
            score *= Math.exp(-0.5 * (octaves / 0.9) * (octaves / 0.9));
            if (score > bestScore) {
                bestScore = score;
                bestBpm = bpm;
            }
        }
        return (float) (Math.round(bestBpm * 10.0) / 10.0);
    }

    private static double acfAt(double[] acf, double lag, int maxLag) {
        if (lag >= maxLag) return 0.0;
        int i = (int) lag;
        double frac = lag - i;
        return acf[i] * (1.0 - frac) + acf[i + 1] * frac;
    }
}
