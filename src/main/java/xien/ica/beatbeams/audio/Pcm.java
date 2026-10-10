package xien.ica.beatbeams.audio;

/** Decoded audio: 16-bit signed samples, always interleaved stereo (L,R,L,R...). */
public record Pcm(int sampleRate, short[] stereo) {
    public int frames() {
        return stereo.length / 2;
    }

    public double durationSeconds() {
        return frames() / (double) sampleRate;
    }
}
