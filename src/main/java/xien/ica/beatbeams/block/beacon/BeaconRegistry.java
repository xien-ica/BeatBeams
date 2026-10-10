package xien.ica.beatbeams.block.beacon;

import net.minecraft.world.level.Level;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-side list of the Music Beacons that are currently loaded and ticking.
 *
 * Each beacon "touches" the registry from its client tick; a beacon that has not ticked for
 * a moment (unloaded chunk, removed block) simply drops out of {@link #loaded}. Nothing is
 * saved and nothing has to be unregistered. Client thread only.
 */
public final class BeaconRegistry {

    private static final long TTL_NANOS = 1_500_000_000L;
    private static final Map<MusicBeaconBlockEntity, Long> SEEN = new WeakHashMap<>();

    private BeaconRegistry() {}

    public static void touch(MusicBeaconBlockEntity be) {
        SEEN.put(be, System.nanoTime());
    }

    /** Beacons in the given level that ticked recently. */
    public static List<MusicBeaconBlockEntity> loaded(Level level) {
        long now = System.nanoTime();
        List<MusicBeaconBlockEntity> out = new ArrayList<>();
        for (Map.Entry<MusicBeaconBlockEntity, Long> e : SEEN.entrySet()) {
            MusicBeaconBlockEntity be = e.getKey();
            if (be == null || be.isRemoved() || be.getLevel() != level) continue;
            if (now - e.getValue() > TTL_NANOS) continue;
            out.add(be);
        }
        return out;
    }
}
