package xien.ica.beatbeams.audio;

import java.io.IOException;
import java.util.Arrays;

/** Collects decoder output (any channel count) into stereo 16-bit PCM. */
final class PcmBuilder {
    /** Whole track is held in memory (about 10 MB per minute), so cap the length. */
    static final int MAX_SECONDS = 20 * 60;

    private final int sampleRate;
    private final int channels;
    private short[] data = new short[1 << 20];
    private int size; // in shorts

    PcmBuilder(int sampleRate, int channels) throws IOException {
        if (sampleRate <= 0 || channels < 1) {
            throw new IOException("Bad audio format (rate=" + sampleRate + ", channels=" + channels + ")");
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /** @param len number of shorts in src that are valid (interleaved by channel). */
    void add(short[] src, int len) throws IOException {
        int frames = len / channels;
        if ((size / 2L + frames) > (long) sampleRate * MAX_SECONDS) {
            throw new IOException("Track is longer than " + (MAX_SECONDS / 60) + " minutes");
        }
        if (size + frames * 2 > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, size + frames * 2));
        }
        for (int f = 0; f < frames; f++) {
            short l = src[f * channels];
            short r = channels == 1 ? l : src[f * channels + 1];
            data[size++] = l;
            data[size++] = r;
        }
    }

    int frames() {
        return size / 2;
    }

    Pcm build() throws IOException {
        if (size == 0) throw new IOException("File contains no audio");
        return new Pcm(sampleRate, Arrays.copyOf(data, size));
    }
}
