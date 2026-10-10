package xien.ica.beatbeams.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** MP3 via JLayer (LGPL). */
final class Mp3Decoder {
    private Mp3Decoder() {}

    static Pcm decode(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            Bitstream bits = new Bitstream(in);
            Decoder decoder = new Decoder();
            PcmBuilder out = null;
            try {
                Header header;
                while ((header = bits.readFrame()) != null) {
                    SampleBuffer buf = (SampleBuffer) decoder.decodeFrame(header, bits);
                    if (out == null) {
                        out = new PcmBuilder(buf.getSampleFrequency(), buf.getChannelCount());
                    }
                    out.add(buf.getBuffer(), buf.getBufferLength());
                    bits.closeFrame();
                }
            } catch (BitstreamException | DecoderException e) {
                // A corrupt tail is common; keep what decoded if there is a useful amount.
                if (out == null || out.frames() == 0) {
                    throw new IOException("MP3 decode failed: " + e.getMessage(), e);
                }
            } finally {
                try {
                    bits.close();
                } catch (BitstreamException ignored) {
                }
            }
            if (out == null) throw new IOException("MP3 contains no audio frames");
            return out.build();
        }
    }
}
