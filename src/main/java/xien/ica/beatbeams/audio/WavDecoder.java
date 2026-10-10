package xien.ica.beatbeams.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;

/** WAV via the JDK's javax.sound (no extra library). */
final class WavDecoder {
    private WavDecoder() {}

    static Pcm decode(Path file) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat src = in.getFormat();
            int ch = src.getChannels();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(), 16, ch, ch * 2, src.getSampleRate(), false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
                PcmBuilder out = new PcmBuilder((int) target.getSampleRate(), ch);
                byte[] buf = new byte[target.getFrameSize() * 4096];
                short[] shorts = new short[buf.length / 2];
                int n;
                while ((n = pcm.read(buf, 0, buf.length)) > 0) {
                    int count = n / 2;
                    for (int i = 0; i < count; i++) {
                        shorts[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
                    }
                    out.add(shorts, count);
                }
                return out.build();
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported WAV format: " + e.getMessage(), e);
        }
    }
}
