package xien.ica.beatbeams.power;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import xien.ica.beatbeams.Beatbeams;
import xien.ica.beatbeams.block.ConnectorBlock;
import xien.ica.beatbeams.block.ConnectorBlockEntity;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;
import xien.ica.beatbeams.block.WireBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Binary custom-power network (performance rewrite).
 *
 * - Deferred queue (no setBlock in neighborChanged)
 * - One BFS per connected wire component per tick
 * - Adjacency discovery for wires/beacons; connectors link by {@link ConnectorBlock#linkedConnectors}
 * - Skip setBlock when power + shape unchanged
 * - Packed long keys / IdentityHashMap pending queue
 */
public final class PowerNetwork {

    public static final int MAX_WIRE_TRAVEL = 100;
    public static final int MAX_CONNECTOR_CHAIN = 64;
    public static final int MAX_NETWORK_WIRES = 1024;
    public static final int MAX_NETWORK_CONNECTORS = 4096;

    /** How far (in blocks) a connector looks along an open side for another connector. Tune here. */
    public static final int CONNECTOR_LINK_RANGE = 4;

    private static final Map<Level, Set<Long>> PENDING = new IdentityHashMap<>();

    private static final Queue<BlockPos> QUEUE = new ArrayDeque<>(64);
    private static final List<BlockPos> WIRE_LIST = new ArrayList<>(64);
    private static final List<BlockPos> CHANGED = new ArrayList<>(16);
    private static final Set<Long> COMPONENT_DONE = new HashSet<>();
    private static final Set<Long> FLOOD_SEEN = new HashSet<>();

    private PowerNetwork() {}

    public static void recomputeAround(Level level, BlockPos origin) {
        if (level == null || origin == null) return;
        synchronized (PENDING) {
            PENDING.computeIfAbsent(level, l -> new HashSet<>()).add(origin.asLong());
        }
    }

    public static void processPending(Level level) {
        if (level == null) return;

        Set<Long> origins;
        synchronized (PENDING) {
            origins = PENDING.remove(level);
        }
        if (origins == null || origins.isEmpty()) return;

        COMPONENT_DONE.clear();

        for (long originLong : origins) {
            if (COMPONENT_DONE.contains(originLong)) continue;
            recomputeComponent(level, BlockPos.of(originLong));
        }

        COMPONENT_DONE.clear();
        WIRE_LIST.clear();
        CHANGED.clear();
        QUEUE.clear();
        FLOOD_SEEN.clear();
    }

    private static void recomputeComponent(Level level, BlockPos origin) {
        WIRE_LIST.clear();
        FLOOD_SEEN.clear();

        Set<Long> connectorKeys = new HashSet<>();
        Set<Long> beaconKeys = new HashSet<>();

        // Flood from the origin and every wire that could be linked to it
        // (6 neighbors + the up/down diagonals used by stepped wires).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    tryFlood(level, origin.offset(dx, dy, dz));
                }
            }
        }

        BlockState originState = level.getBlockState(origin);
        if (originState.is(Beatbeams.CONNECTOR_BLOCK)) {
            connectorKeys.add(origin.asLong());
        } else if (originState.is(Beatbeams.MUSIC_BEACON_BLOCK)) {
            beaconKeys.add(origin.asLong());
        }
        addAdjacentSpecial(level, origin, connectorKeys, beaconKeys);

        // A connector may have just been placed, removed or changed style here. Its old or new
        // partners can be several blocks away and get no neighbor update, so pull them in directly.
        if (!originState.is(Beatbeams.WIRE_BLOCK) && !originState.is(Beatbeams.MUSIC_BEACON_BLOCK)) {
            addRayConnectors(level, origin, connectorKeys);
        }

        // Wires <-> connectors <-> wires: pull the whole connected network in.
        expandThroughConnectors(level, connectorKeys, beaconKeys);

        for (BlockPos w : WIRE_LIST) {
            COMPONENT_DONE.add(w.asLong());
        }
        COMPONENT_DONE.add(origin.asLong());

        Set<Long> wireSet = new HashSet<>(Math.max(16, WIRE_LIST.size() * 2));
        for (BlockPos w : WIRE_LIST) {
            wireSet.add(w.asLong());
        }

        // Vanilla (external) power: computed once
        List<BlockPos> wireSeeds = new ArrayList<>();
        for (BlockPos w : WIRE_LIST) {
            if (isExternallyPowered(level, w)) wireSeeds.add(w);
        }
        Set<Long> connExternal = new HashSet<>();
        for (long ck : connectorKeys) {
            if (isExternallyPowered(level, BlockPos.of(ck))) connExternal.add(ck);
        }

        // Wires and connectors feed each other, so iterate from "all off" until stable.
        // Starting from off means a loop with no real source can never latch on.
        Set<Long> powered = new HashSet<>();
        Set<Long> connOn = new HashSet<>();
        for (int round = 0; round < 64; round++) {
            Set<Long> nextPowered = spreadWirePower(level, wireSet, wireSeeds, connOn);
            Set<Long> nextConn = spreadConnectorPower(level, connectorKeys, connExternal, nextPowered);
            boolean stable = nextPowered.equals(powered) && nextConn.equals(connOn);
            powered = nextPowered;
            connOn = nextConn;
            if (stable) break;
        }

        // Apply wire blockstates
        CHANGED.clear();
        for (BlockPos p : WIRE_LIST) {
            BlockState st = level.getBlockState(p);
            if (!st.is(Beatbeams.WIRE_BLOCK)) continue;
            boolean on = powered.contains(p.asLong());
            BlockState target = WireBlock.withConnections(level, st, p).setValue(WireBlock.POWERED, on);
            if (target == st) continue;
            boolean powerChanged = st.getValue(WireBlock.POWERED) != on;
            level.setBlock(p, target, 2);
            if (powerChanged) {
                CHANGED.add(p);
            }
        }
        for (BlockPos p : CHANGED) {
            level.updateNeighborsAt(p, Beatbeams.WIRE_BLOCK);
        }

        // Connectors
        Map<Long, Boolean> connPower = new HashMap<>(Math.max(4, connectorKeys.size() * 2));
        for (long ck : connectorKeys) {
            connPower.put(ck, connOn.contains(ck));
        }
        for (Map.Entry<Long, Boolean> e : connPower.entrySet()) {
            BlockEntity be = level.getBlockEntity(BlockPos.of(e.getKey()));
            if (be instanceof ConnectorBlockEntity cbe) {
                cbe.setPoweredSilent(e.getValue());
            }
        }

        // Beacons
        for (long bk : beaconKeys) {
            BlockPos bp = BlockPos.of(bk);
            BlockEntity be = level.getBlockEntity(bp);
            if (be instanceof MusicBeaconBlockEntity mbe) {
                mbe.setCircuitPowered(evaluateBeaconPowered(level, bp, powered, connPower));
            }
        }
    }

    private static void tryFlood(Level level, BlockPos p) {
        if (COMPONENT_DONE.contains(p.asLong())) return;
        if (level.getBlockState(p).is(Beatbeams.WIRE_BLOCK)) {
            floodWires(level, p);
        }
    }

    private static void floodWires(Level level, BlockPos start) {
        if (!FLOOD_SEEN.add(start.asLong())) return;
        if (!level.getBlockState(start).is(Beatbeams.WIRE_BLOCK)) return;

        QUEUE.clear();
        QUEUE.add(start);
        WIRE_LIST.add(start);

        while (!QUEUE.isEmpty()) {
            BlockPos cur = QUEUE.poll();
            for (BlockPos n : WireBlock.linkedWires(level, cur)) {
                long nk = n.asLong();
                if (!FLOOD_SEEN.add(nk)) continue;
                if (level.getBlockState(n).is(Beatbeams.WIRE_BLOCK)) {
                    WIRE_LIST.add(n);
                    QUEUE.add(n);
                    if (WIRE_LIST.size() > MAX_NETWORK_WIRES) return;
                }
            }
        }
    }

    private static void addAdjacentSpecial(Level level, BlockPos pos,
                                           Set<Long> connectors, Set<Long> beacons) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState st = level.getBlockState(n);
            if (st.is(Beatbeams.CONNECTOR_BLOCK)) {
                connectors.add(n.asLong());
            } else if (st.is(Beatbeams.MUSIC_BEACON_BLOCK)) {
                beacons.add(n.asLong());
            }
        }
    }

    /** First connector (if any) within link range along each of the 6 directions from {@code origin}. */
    private static void addRayConnectors(Level level, BlockPos origin, Set<Long> connectors) {
        for (Direction d : Direction.values()) {
            BlockPos p = origin;
            for (int i = 1; i <= CONNECTOR_LINK_RANGE; i++) {
                p = p.relative(d);
                if (!level.hasChunkAt(p)) break;
                if (level.getBlockState(p).is(Beatbeams.CONNECTOR_BLOCK)) {
                    connectors.add(p.asLong());
                    break;
                }
            }
        }
    }

    private static boolean isNetworkBlock(BlockState st) {
        return st.is(Beatbeams.WIRE_BLOCK)
                || st.is(Beatbeams.CONNECTOR_BLOCK)
                || st.is(Beatbeams.MUSIC_BEACON_BLOCK);
    }

    /**
     * Vanilla power arriving at {@code pos} from the neighbor in direction {@code d}
     * (levers, buttons, torches, powered solid blocks...). Same idea as Level.getSignal,
     * but ignores our own blocks so wires/connectors can never power themselves.
     */
    private static boolean externalSignal(Level level, BlockPos pos, Direction d) {
        BlockPos n = pos.relative(d);
        BlockState st = level.getBlockState(n);
        if (isNetworkBlock(st)) return false;
        if (st.getSignal(level, n, d) > 0) return true;
        if (!st.isRedstoneConductor(level, n)) return false;
        for (Direction e : Direction.values()) {
            if (e == d.getOpposite()) continue;
            BlockPos m = n.relative(e);
            BlockState ms = level.getBlockState(m);
            if (isNetworkBlock(ms)) continue;
            if (ms.getDirectSignal(level, m, e) > 0) return true;
        }
        return false;
    }

    private static boolean isExternallyPowered(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (externalSignal(level, pos, d)) return true;
        }
        return false;
    }

    /** Wires powered by external seeds plus any wire touching a powered connector. */
    private static Set<Long> spreadWirePower(Level level, Set<Long> wireSet, List<BlockPos> wireSeeds,
                                             Set<Long> connOn) {
        Set<Long> powered = new HashSet<>(Math.max(16, wireSet.size() * 2));
        Map<Long, Integer> dist = new HashMap<>(Math.max(16, wireSet.size()));
        QUEUE.clear();

        for (BlockPos w : wireSeeds) {
            if (powered.add(w.asLong())) {
                dist.put(w.asLong(), 0);
                QUEUE.add(w);
            }
        }
        for (long ck : connOn) {
            BlockPos c = BlockPos.of(ck);
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                long nk = n.asLong();
                if (wireSet.contains(nk) && powered.add(nk)) {
                    dist.put(nk, 0);
                    QUEUE.add(n);
                }
            }
        }

        while (!QUEUE.isEmpty()) {
            BlockPos cur = QUEUE.poll();
            int d = dist.getOrDefault(cur.asLong(), 0);
            if (d >= MAX_WIRE_TRAVEL) continue;
            for (BlockPos next : WireBlock.linkedWires(level, cur)) {
                long nk = next.asLong();
                if (!wireSet.contains(nk) || powered.contains(nk)) continue;
                powered.add(nk);
                dist.put(nk, d + 1);
                QUEUE.add(next);
            }
        }
        return powered;
    }

    /**
     * Connectors powered by vanilla signals or an adjacent powered wire, then spread along
     * connector links (see ConnectorBlock.linkedConnectors).
     */
    private static Set<Long> spreadConnectorPower(Level level, Set<Long> connectorKeys,
                                                  Set<Long> connExternal, Set<Long> poweredWires) {
        Set<Long> direct = new HashSet<>();
        for (long ck : connectorKeys) {
            if (connExternal.contains(ck)) {
                direct.add(ck);
                continue;
            }
            BlockPos c = BlockPos.of(ck);
            for (Direction d : Direction.values()) {
                if (poweredWires.contains(c.relative(d).asLong())) {
                    direct.add(ck);
                    break;
                }
            }
        }

        // Power flows along links, up to MAX_CONNECTOR_CHAIN steps from a directly powered connector.
        Set<Long> on = new HashSet<>(direct);
        Map<Long, Integer> dist = new HashMap<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        for (long ck : direct) {
            dist.put(ck, 0);
            q.add(BlockPos.of(ck));
        }
        while (!q.isEmpty()) {
            BlockPos cur = q.poll();
            int d = dist.get(cur.asLong());
            if (d >= MAX_CONNECTOR_CHAIN) continue;
            for (BlockPos n : ConnectorBlock.linkedConnectors(level, cur)) {
                long nk = n.asLong();
                if (dist.containsKey(nk)) continue;
                if (!connectorKeys.contains(nk)) continue;
                dist.put(nk, d + 1);
                on.add(nk);
                q.add(n);
            }
        }
        return on;
    }

    /**
     * Grows the wire list and connector set until stable:
     * wire -> touching connector -> all linked connectors -> wires touching those -> ...
     */
    private static void expandThroughConnectors(Level level, Set<Long> connectorKeys, Set<Long> beaconKeys) {
        Set<Long> chained = new HashSet<>();
        int wireScan = 0;
        for (int guard = 0; guard < 256; guard++) {
            boolean progress = false;

            while (wireScan < WIRE_LIST.size()) {
                BlockPos w = WIRE_LIST.get(wireScan++);
                addAdjacentSpecial(level, w, connectorKeys, beaconKeys);
                BlockPos a1 = w.above();
                if (level.getBlockState(a1).is(Beatbeams.MUSIC_BEACON_BLOCK)) {
                    beaconKeys.add(a1.asLong());
                }
                BlockPos a2 = a1.above();
                if (level.getBlockState(a2).is(Beatbeams.MUSIC_BEACON_BLOCK)) {
                    beaconKeys.add(a2.asLong());
                }
                progress = true;
            }

            for (long ck : new ArrayList<>(connectorKeys)) {
                if (!chained.add(ck)) continue;
                progress = true;
                ArrayDeque<BlockPos> cq = new ArrayDeque<>();
                cq.add(BlockPos.of(ck));
                while (!cq.isEmpty() && connectorKeys.size() < MAX_NETWORK_CONNECTORS) {
                    BlockPos cur = cq.poll();
                    // A beacon sitting on top of a connector is part of the network
                    BlockPos above = cur.above();
                    if (level.getBlockState(above).is(Beatbeams.MUSIC_BEACON_BLOCK)) {
                        beaconKeys.add(above.asLong());
                    }
                    for (BlockPos n : ConnectorBlock.linkedConnectors(level, cur)) {
                        if (connectorKeys.add(n.asLong())) {
                            chained.add(n.asLong());
                            cq.add(n);
                        }
                    }
                }
            }

            for (long ck : connectorKeys) {
                BlockPos c = BlockPos.of(ck);
                for (Direction d : Direction.values()) {
                    BlockPos n = c.relative(d);
                    if (!FLOOD_SEEN.contains(n.asLong()) && level.getBlockState(n).is(Beatbeams.WIRE_BLOCK)) {
                        floodWires(level, n);
                        progress = true;
                    }
                }
            }

            if (!progress) break;
        }
    }

    private static boolean evaluateBeaconPowered(
            Level level, BlockPos pos,
            Set<Long> poweredWires,
            Map<Long, Boolean> connPower) {
        for (Direction d : Direction.values()) {
            if (isPoweringNeighbor(level, pos.relative(d), d, poweredWires, connPower)) {
                return true;
            }
        }
        BlockPos below = pos.below();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isPoweredWire(level, below.relative(d), poweredWires)) return true;
        }
        return isPoweredWire(level, below, poweredWires);
    }

    private static boolean isPoweredWire(Level level, BlockPos n, Set<Long> poweredWires) {
        BlockState st = level.getBlockState(n);
        if (!st.is(Beatbeams.WIRE_BLOCK)) return false;
        return poweredWires.contains(n.asLong()) || st.getValue(WireBlock.POWERED);
    }

    private static boolean isPoweringNeighbor(
            Level level, BlockPos n, Direction fromBeacon,
            Set<Long> poweredWires,
            Map<Long, Boolean> connPower) {
        BlockState st = level.getBlockState(n);
        if (st.is(Beatbeams.WIRE_BLOCK)) {
            return isPoweredWire(level, n, poweredWires);
        }
        if (st.is(Beatbeams.CONNECTOR_BLOCK)) {
            if (Boolean.TRUE.equals(connPower.get(n.asLong()))) return true;
            BlockEntity be = level.getBlockEntity(n);
            return be instanceof ConnectorBlockEntity cbe && cbe.isPowered();
        }
        if (st.is(Beatbeams.MUSIC_BEACON_BLOCK)) return false;
        return externalSignal(level, n.relative(fromBeacon.getOpposite()), fromBeacon);
    }

    /**
     * Beacons reachable from {@code start} through wires + connectors
     * (same topology used for power). Used for GUI sync groups.
     */
    public static List<MusicBeaconBlockEntity> findBeaconsInNetwork(Level level, BlockPos start) {
        List<MusicBeaconBlockEntity> result = new ArrayList<>();
        if (level == null || start == null) return result;

        Set<Long> seenWire = new HashSet<>();
        Set<Long> seenConn = new HashSet<>();
        Set<Long> seenBeacon = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();

        // Seed from start (beacon or any network block)
        q.add(start.immutable());
        seenBeacon.add(start.asLong());

        // Also seed adjacent network pieces
        for (Direction d : Direction.values()) {
            BlockPos n = start.relative(d);
            BlockState st = level.getBlockState(n);
            if (st.is(Beatbeams.WIRE_BLOCK)) {
                if (seenWire.add(n.asLong())) q.add(n.immutable());
            } else if (st.is(Beatbeams.CONNECTOR_BLOCK)) {
                if (seenConn.add(n.asLong())) q.add(n.immutable());
            } else if (st.is(Beatbeams.MUSIC_BEACON_BLOCK)) {
                if (seenBeacon.add(n.asLong())) q.add(n.immutable());
            }
        }
        // Floor-wire convenience under beacon
        BlockPos below = start.below();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = below.relative(d);
            if (level.getBlockState(n).is(Beatbeams.WIRE_BLOCK) && seenWire.add(n.asLong())) {
                q.add(n.immutable());
            }
        }
        if (level.getBlockState(below).is(Beatbeams.WIRE_BLOCK) && seenWire.add(below.asLong())) {
            q.add(below.immutable());
        }
        if (level.getBlockState(below).is(Beatbeams.CONNECTOR_BLOCK) && seenConn.add(below.asLong())) {
            q.add(below.immutable());
        }

        int guard = 0;
        while (!q.isEmpty() && guard++ < 4096) {
            BlockPos cur = q.poll();
            BlockState st = level.getBlockState(cur);

            if (st.is(Beatbeams.WIRE_BLOCK)) {
                // Touching wires / connectors / beacons
                for (Direction d : Direction.values()) {
                    BlockPos n = cur.relative(d);
                    BlockState ns = level.getBlockState(n);
                    if (ns.is(Beatbeams.WIRE_BLOCK) && seenWire.add(n.asLong())) {
                        q.add(n.immutable());
                    } else if (ns.is(Beatbeams.CONNECTOR_BLOCK) && seenConn.add(n.asLong())) {
                        q.add(n.immutable());
                    } else if (ns.is(Beatbeams.MUSIC_BEACON_BLOCK) && seenBeacon.add(n.asLong())) {
                        q.add(n.immutable());
                    }
                }
                for (BlockPos link : WireBlock.linkedWires(level, cur)) {
                    if (seenWire.add(link.asLong())) {
                        q.add(link.immutable());
                    }
                }
                // Beacon up to 2 above wire (pillar)
                BlockPos a1 = cur.above();
                if (level.getBlockState(a1).is(Beatbeams.MUSIC_BEACON_BLOCK) && seenBeacon.add(a1.asLong())) {
                    q.add(a1.immutable());
                }
                BlockPos a2 = a1.above();
                if (level.getBlockState(a2).is(Beatbeams.MUSIC_BEACON_BLOCK) && seenBeacon.add(a2.asLong())) {
                    q.add(a2.immutable());
                }
            } else if (st.is(Beatbeams.CONNECTOR_BLOCK)) {
                // Other connectors: by link rule (through blocks, pipes only along their axis)
                for (BlockPos link : ConnectorBlock.linkedConnectors(level, cur)) {
                    if (seenConn.add(link.asLong())) {
                        q.add(link.immutable());
                    }
                }
                // Touching wires / beacons
                for (Direction d : Direction.values()) {
                    BlockPos n = cur.relative(d);
                    BlockState ns = level.getBlockState(n);
                    if (ns.is(Beatbeams.WIRE_BLOCK) && seenWire.add(n.asLong())) {
                        q.add(n.immutable());
                    } else if (ns.is(Beatbeams.MUSIC_BEACON_BLOCK) && seenBeacon.add(n.asLong())) {
                        q.add(n.immutable());
                    }
                }
            } else if (st.is(Beatbeams.MUSIC_BEACON_BLOCK)) {
                for (Direction d : Direction.values()) {
                    BlockPos n = cur.relative(d);
                    BlockState ns = level.getBlockState(n);
                    if (ns.is(Beatbeams.WIRE_BLOCK) && seenWire.add(n.asLong())) {
                        q.add(n.immutable());
                    } else if (ns.is(Beatbeams.CONNECTOR_BLOCK) && seenConn.add(n.asLong())) {
                        q.add(n.immutable());
                    } else if (ns.is(Beatbeams.MUSIC_BEACON_BLOCK) && seenBeacon.add(n.asLong())) {
                        q.add(n.immutable());
                    }
                }
            }
        }

        for (long key : seenBeacon) {
            BlockEntity be = level.getBlockEntity(BlockPos.of(key));
            if (be instanceof MusicBeaconBlockEntity mbe) {
                result.add(mbe);
            }
        }
        return result;
    }
}