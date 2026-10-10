package xien.ica.beatbeams.net;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Best-effort UDP receiver for analysis frames sent by the Windows audio
 * analyzer companion app. This mod runs client-only ("environment": "client"
 * in fabric.mod.json), so in practice this only ever runs inside a client
 * process -- but the class itself uses no Minecraft-client-only API (just
 * java.net + Gson), so it lives in the common source set and is started
 * from the client entrypoint (BeatbeamsClient) purely as an organizational
 * choice: "what to start" is a client concern, "how the socket works" isn't.
 *
 * Never blocks Minecraft: runs on its own daemon thread, and the mod keeps
 * working normally whether or not the analyzer is present.
 */
public final class AudioLinkClient {

    public static final int PORT = 47301;
    private static final int SOCKET_TIMEOUT_MS = 250;
    private static final long STALE_AFTER_MS = 1500;
    private static final int EXPECTED_PROTOCOL_VERSION = 1;

    public enum State { DISCONNECTED, CONNECTED, TIMED_OUT }

    private static final AudioLinkClient INSTANCE = new AudioLinkClient();

    private final Gson gson = new Gson();
    private final AtomicReference<AudioFrame> latest = new AtomicReference<>(AudioFrame.SILENCE);
    private volatile long lastReceivedAtMs = 0L;
    private volatile boolean running = false;
    private Thread thread;

    private AudioLinkClient() {
    }

    public static AudioLinkClient get() {
        return INSTANCE;
    }

    /** Call once, from the client entrypoint. Safe to call multiple times. */
    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runLoop, "beatbeams-audio-link");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public AudioFrame latest() {
        return latest.get();
    }

    public State state() {
        if (lastReceivedAtMs == 0L) return State.DISCONNECTED;
        long age = System.currentTimeMillis() - lastReceivedAtMs;
        return age > STALE_AFTER_MS ? State.TIMED_OUT : State.CONNECTED;
    }

    private void runLoop() {
        byte[] buffer = new byte[2048];
        try (DatagramSocket socket = new DatagramSocket(PORT)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    handlePacket(packet);
                } catch (SocketTimeoutException ignored) {
                    // Normal: lets us re-check `running` periodically without blocking forever.
                }
            }
        } catch (Exception e) {
            // Port in use, permissions, etc. Don't crash the client -- the mod
            // simply behaves as if the analyzer is never connected.
            running = false;
        }
    }

    private void handlePacket(DatagramPacket packet) {
        String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        try {
            WireFrame wire = gson.fromJson(json, WireFrame.class);
            if (wire == null) return;

            if (wire.protocolVersion != 0 && wire.protocolVersion != EXPECTED_PROTOCOL_VERSION) {
                return; // version mismatch: ignore rather than risk misreading fields
            }
            if (!isFinite(wire.volume) || !isFinite(wire.bass) || !isFinite(wire.mid) || !isFinite(wire.treble)) {
                return; // invalid data
            }

            AudioFrame frame = new AudioFrame(
                    wire.timestamp,
                    clamp01(wire.volume),
                    clamp01(wire.bass),
                    clamp01(wire.mid),
                    clamp01(wire.treble),
                    wire.beat,
                    clamp01(wire.beatStrength),
                    Math.max(0f, wire.bpm)
            );
            latest.set(frame);
            lastReceivedAtMs = System.currentTimeMillis();
        } catch (JsonSyntaxException invalid) {
            // Invalid data: drop the frame, keep the last good one.
        }
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Raw shape matching the analyzer's JSON. `protocolVersion` is optional for forward-compat. */
    private static final class WireFrame {
        long timestamp;
        float volume;
        float bass;
        float mid;
        float treble;
        boolean beat;
        float beatStrength;
        float bpm;
        int protocolVersion;
    }
}
