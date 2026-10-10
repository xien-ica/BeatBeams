package xien.ica.beatbeams.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Picks the right decoder from the file extension. */
public final class TrackDecoder {
    private TrackDecoder() {}

    public static boolean isSupported(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg");
    }

    public static Pcm decode(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return Mp3Decoder.decode(file);
        if (n.endsWith(".ogg")) return OggDecoder.decode(file);
        if (n.endsWith(".wav")) return WavDecoder.decode(file);
        throw new IOException("Unsupported format: " + n);
    }
}
