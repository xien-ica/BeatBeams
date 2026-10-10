package xien.ica.beatbeams.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** The list of music files found in the music folder. */
public final class TrackLibrary {
    public record Track(String name, Path path) {}

    private final Path dir;
    private volatile List<Track> tracks = List.of();

    public TrackLibrary(Path dir) {
        this.dir = dir;
    }

    public Path directory() {
        return dir;
    }

    public List<Track> tracks() {
        return tracks;
    }

    /** Re-reads the folder (creating it if needed) and returns the new list. */
    public List<Track> rescan() {
        List<Track> found = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(TrackDecoder::isSupported)
                        .forEach(p -> found.add(new Track(stripExtension(p.getFileName().toString()), p)));
            }
        } catch (IOException e) {
            // leave the list empty; the player reports the folder in its status
        }
        found.sort(Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT)));
        tracks = List.copyOf(found);
        return tracks;
    }

    /** Finds a track by 1-based number, exact name, or partial name (case-insensitive). */
    public Track find(String query) {
        List<Track> list = tracks;
        String q = query.trim();
        if (q.isEmpty()) return null;
        try {
            int index = Integer.parseInt(q);
            if (index >= 1 && index <= list.size()) return list.get(index - 1);
        } catch (NumberFormatException ignored) {
        }
        String lower = q.toLowerCase(Locale.ROOT);
        for (Track t : list) {
            if (t.name().toLowerCase(Locale.ROOT).equals(lower)) return t;
        }
        for (Track t : list) {
            if (t.name().toLowerCase(Locale.ROOT).contains(lower)) return t;
        }
        return null;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
