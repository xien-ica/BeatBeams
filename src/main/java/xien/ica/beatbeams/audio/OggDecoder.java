package xien.ica.beatbeams.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** OGG Vorbis via LWJGL's stb_vorbis, which Minecraft already ships. */
final class OggDecoder {
    private OggDecoder() {}

    static Pcm decode(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer mem = MemoryUtil.memAlloc(bytes.length);
        try {
            mem.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer rate = stack.mallocInt(1);
                ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(mem, channels, rate);
                if (pcm == null) throw new IOException("stb_vorbis could not decode this file");
                try {
                    short[] samples = new short[pcm.remaining()];
                    pcm.get(samples);
                    PcmBuilder out = new PcmBuilder(rate.get(0), channels.get(0));
                    out.add(samples, samples.length);
                    return out.build();
                } finally {
                    LibCStdlib.free(pcm);
                }
            }
        } finally {
            MemoryUtil.memFree(mem);
        }
    }
}
