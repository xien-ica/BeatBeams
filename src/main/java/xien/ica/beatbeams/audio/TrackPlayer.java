package xien.ica.beatbeams.audio;

import xien.ica.beatbeams.net.AudioFrame;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays tracks from the music folder globally and exposes the analysis frame that
 * matches the current playback position.
 *
 * Playback goes straight to a javax.sound SourceDataLine, so it uses no Minecraft
 * sound-engine internals. The line's own frame counter is the clock, so the beams
 * stay locked to what is actually being played. Nothing here touches Minecraft
 * classes: the volume is pushed in by the client via {@link #setOutputVolume(float)}.
 */
public final class TrackPlayer {
    public enum State { STOPPED, LOADING, PLAYING, PAUSED }

    private record Loaded(TrackLibrary.Track track, Pcm pcm, TrackAnalyzer.Result analysis) {}

    private static final TrackPlayer INSTANCE = new TrackPlayer();

    public static TrackPlayer get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final AtomicInteger generation = new AtomicInteger();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beatbeams-loader");
        t.setDaemon(true);
        return t;
    });

    private TrackLibrary library;
    private volatile State state = State.STOPPED;
    private volatile Loaded current;
    private volatile SourceDataLine line;
    private volatile boolean paused;
    private volatile float outputVolume = 1f;
    private volatile int syncOffsetMs = 0;
    private volatile String message = "";

    // ---- queue (copy-on-write list; mutate only while holding queueLock) ----
    private final Object queueLock = new Object();
    private volatile List<TrackLibrary.Track> queue = List.of();
    /** Index in {@link #queue} of the entry being played, or -1 if none / it was removed. */
    private volatile int queueIndex = -1;
    /** When on, the queue wraps around at both ends; when off, it stops after the last track. */
    private volatile boolean loop = true;

    private TrackPlayer() {}

    // ---------------------------------------------------------------- setup

    public synchronized void init(Path musicDir) {
        if (library == null) {
            library = new TrackLibrary(musicDir);
            library.rescan();
        }
    }

    public TrackLibrary library() {
        return library;
    }

    /** 0..1, already multiplied by whatever Minecraft volume sliders you want to respect. */
    public void setOutputVolume(float v) {
        outputVolume = Math.max(0f, Math.min(1f, v));
    }

    /** Positive = visuals run ahead of the sound, negative = behind. Tune if beams feel early/late. */
    public void setSyncOffsetMs(int ms) {
        syncOffsetMs = Math.max(-500, Math.min(500, ms));
    }

    public int syncOffsetMs() {
        return syncOffsetMs;
    }

    // ------------------------------------------------------------- queries

    public State state() {
        return state;
    }

    public String statusMessage() {
        return message;
    }

    public String currentName() {
        Loaded l = current;
        return l == null ? "" : l.track.name();
    }

    public float currentBpm() {
        Loaded l = current;
        return l == null ? 0f : l.analysis.bpm();
    }

    public double positionSeconds() {
        Loaded l = current;
        SourceDataLine ln = line;
        if (l == null || ln == null) return 0;
        return ln.getLongFramePosition() / (double) l.pcm.sampleRate();
    }

    public double durationSeconds() {
        Loaded l = current;
        return l == null ? 0 : l.pcm.durationSeconds();
    }

    /** The analysis frame for right now, or silence when nothing is playing. */
    public AudioFrame latest() {
        Loaded l = current;
        SourceDataLine ln = line;
        if (l == null || ln == null || state != State.PLAYING) return AudioFrame.SILENCE;
        double sec = ln.getLongFramePosition() / (double) l.pcm.sampleRate() + syncOffsetMs / 1000.0;
        int i = (int) Math.round(sec * l.analysis.framesPerSecond());
        AudioFrame[] frames = l.analysis.frames();
        if (i < 0) return AudioFrame.SILENCE;
        if (i >= frames.length) return AudioFrame.SILENCE;
        return frames[i];
    }

    // ------------------------------------------------------------ controls

    // ---------------------------------------------------------------- queue

    public List<TrackLibrary.Track> queue() {
        return queue;
    }

    public int queueIndex() {
        return queueIndex;
    }

    public boolean loop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public void enqueue(TrackLibrary.Track track) {
        synchronized (queueLock) {
            List<TrackLibrary.Track> l = new ArrayList<>(queue);
            l.add(track);
            queue = List.copyOf(l);
        }
    }

    public void enqueueAll(List<TrackLibrary.Track> tracks) {
        synchronized (queueLock) {
            List<TrackLibrary.Track> l = new ArrayList<>(queue);
            l.addAll(tracks);
            queue = List.copyOf(l);
        }
    }

    /** Removing the entry that is playing lets it finish; Next then continues from where it was. */
    public void removeFromQueue(int index) {
        synchronized (queueLock) {
            if (index < 0 || index >= queue.size()) return;
            List<TrackLibrary.Track> l = new ArrayList<>(queue);
            l.remove(index);
            queue = List.copyOf(l);
            if (index < queueIndex) {
                queueIndex--;
            } else if (index == queueIndex) {
                queueIndex = index - 1;
            }
        }
    }

    /** Empties the queue. Whatever is playing keeps playing and then stops. */
    public void clearQueue() {
        synchronized (queueLock) {
            queue = List.of();
            queueIndex = -1;
        }
    }

    // ------------------------------------------------------------ controls

    /** Play now: jumps to the track if it is queued, otherwise appends it to the queue and plays it. */
    public void play(TrackLibrary.Track track) {
        synchronized (queueLock) {
            int i = queue.indexOf(track);
            if (i < 0) {
                enqueue(track);
                i = queue.size() - 1;
            }
            queueIndex = i;
        }
        startTrack(track);
    }

    public void playQueueIndex(int index) {
        TrackLibrary.Track target;
        synchronized (queueLock) {
            if (index < 0 || index >= queue.size()) return;
            queueIndex = index;
            target = queue.get(index);
        }
        startTrack(target);
    }

    /** Play button: pauses when playing, resumes when paused, otherwise starts the queue. */
    public void playPause() {
        switch (state) {
            case PLAYING -> pause();
            case PAUSED -> resume();
            case STOPPED -> {
                int i = queueIndex;
                if (i >= 0 && i < queue.size()) {
                    playQueueIndex(i);
                } else {
                    step(1, false);
                }
            }
            case LOADING -> { }
        }
    }

    public void pause() {
        if (state != State.PLAYING) return;
        SourceDataLine ln = line;
        synchronized (lock) {
            paused = true;
            state = State.PAUSED;
        }
        if (ln != null) ln.stop();
    }

    public void resume() {
        if (state != State.PAUSED) return;
        SourceDataLine ln = line;
        if (ln != null) ln.start();
        synchronized (lock) {
            paused = false;
            state = State.PLAYING;
            lock.notifyAll();
        }
    }

    public void stop() {
        generation.incrementAndGet();
        stopLine();
        current = null;
        state = State.STOPPED;
        message = "";
    }

    public void next() {
        step(1, false);
    }

    /** Restarts the track if it has played for more than 3 seconds, otherwise goes to the previous one. */
    public void previous() {
        step(-1, false);
    }

    private void step(int delta, boolean natural) {
        TrackLibrary.Track target = null;
        boolean stopAfter = false;
        String msg = null;
        synchronized (queueLock) {
            if (queue.isEmpty() && library != null) {
                queue = library.tracks(); // empty queue + Next: fall back to the whole library
                queueIndex = -1;
            }
            int n = queue.size();
            if (n == 0) {
                msg = library == null ? "" : "No tracks in " + library.directory();
                stopAfter = natural;
            } else if (delta < 0 && !natural && queueIndex >= 0 && queueIndex < n
                    && (state == State.PLAYING || state == State.PAUSED) && positionSeconds() > 3.0) {
                target = queue.get(queueIndex); // restart current
            } else {
                int idx = queueIndex + delta;
                if (idx >= n) {
                    if (loop) {
                        idx = 0;
                    } else {
                        idx = -1;
                        queueIndex = -1; // finished: Play starts again from the top
                        stopAfter = true;
                    }
                } else if (idx < 0) {
                    idx = loop ? n - 1 : 0;
                }
                if (idx >= 0) {
                    queueIndex = idx;
                    target = queue.get(idx);
                }
            }
        }
        if (target != null) {
            startTrack(target);
        } else if (stopAfter) {
            stop();
        }
        if (msg != null && !msg.isEmpty()) message = msg;
    }

    // ------------------------------------------------------------ internals

    private void startTrack(TrackLibrary.Track track) {
        int gen = generation.incrementAndGet();
        stopLine();
        current = null;
        state = State.LOADING;
        message = "Loading " + track.name() + "...";
        loader.submit(() -> load(gen, track));
    }

    private void stopLine() {
        SourceDataLine ln;
        synchronized (lock) {
            ln = line;
            line = null;
            paused = false;
            lock.notifyAll();
        }
        if (ln != null) {
            try {
                ln.stop();
                ln.flush();
                ln.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void load(int gen, TrackLibrary.Track track) {
        try {
            Pcm pcm = TrackDecoder.decode(track.path());
            if (gen != generation.get()) return;
            TrackAnalyzer.Result analysis = TrackAnalyzer.analyze(pcm);
            if (gen != generation.get()) return;
            startPlayback(gen, new Loaded(track, pcm, analysis));
        } catch (Throwable e) {
            if (gen != generation.get()) return;
            state = State.STOPPED;
            message = "Could not play " + track.name() + ": " + e.getMessage();
        }
    }

    private void startPlayback(int gen, Loaded loaded) throws Exception {
        int rate = loaded.pcm.sampleRate();
        AudioFormat fmt = new AudioFormat(rate, 16, 2, true, false);
        SourceDataLine ln = AudioSystem.getSourceDataLine(fmt);
        int bufferBytes = (rate / 8) * 4; // about 125 ms
        ln.open(fmt, bufferBytes);

        synchronized (lock) {
            if (gen != generation.get()) {
                ln.close();
                return;
            }
            line = ln;
            current = loaded;
            paused = false;
            state = State.PLAYING;
            message = "";
        }
        ln.start();

        Thread t = new Thread(() -> playbackLoop(gen, loaded, ln), "beatbeams-playback");
        t.setDaemon(true);
        t.start();
    }

    private void playbackLoop(int gen, Loaded loaded, SourceDataLine ln) {
        short[] samples = loaded.pcm.stereo();
        int chunkFrames = Math.max(256, loaded.pcm.sampleRate() / 50); // 20 ms
        byte[] out = new byte[chunkFrames * 4];
        float gain = outputVolume;
        int pos = 0; // in shorts
        try {
            while (pos < samples.length) {
                synchronized (lock) {
                    while (paused && gen == generation.get()) lock.wait(100);
                }
                if (gen != generation.get()) return;

                int frames = Math.min(chunkFrames, (samples.length - pos) / 2);
                float target = outputVolume;
                for (int f = 0; f < frames; f++) {
                    float g = gain + (target - gain) * (f + 1) / frames; // ramp: no clicks on slider moves
                    for (int c = 0; c < 2; c++) {
                        int v = Math.round(samples[pos + f * 2 + c] * g);
                        out[f * 4 + c * 2] = (byte) v;
                        out[f * 4 + c * 2 + 1] = (byte) (v >> 8);
                    }
                }
                gain = target;
                pos += frames * 2;
                ln.write(out, 0, frames * 4);
            }
            if (gen == generation.get()) ln.drain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            return; // line was closed underneath us by stop()/play()
        } finally {
            try {
                ln.close();
            } catch (RuntimeException ignored) {
            }
        }

        if (gen == generation.get()) {
            step(1, true); // reached the end of the track on its own
        }
    }
}
