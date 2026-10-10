package xien.ica.beatbeams.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.AudioSource;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.BeatResponse;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.ColorMode;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.PhaseMode;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.ReactivityFeel;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.SweepDir;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.SweepStyle;
import xien.ica.beatbeams.block.beacon.BeaconPresets;
import xien.ica.beatbeams.block.beacon.BeaconRegistry;
import xien.ica.beatbeams.power.PowerNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Lights Control Panel GUI.
 *
 *  ┌──────────────┬──────────────────────────────┐
 *  │ NETWORKS     │ Editing: <scope>             │
 *  │  All beacons │ Beam|React|Color|Motion|Pre. │
 *  │  Network 1.. │  controls (2 columns)        │
 *  ├──────────────┤                              │
 *  │ BEACONS      │ [Push to network][Push to all]│
 *  │  x y z ..    │ [Refresh][Open editor][Done] │
 *  └──────────────┴──────────────────────────────┘
 *
 * Pick a scope on the left (all beacons, one wire/connector network, or a single beacon).
 * Every control on the right changes only that one setting on every beacon in the scope, so
 * individual differences (color, angle...) survive when you tweak something else network-wide.
 * The values shown come from the first beacon of the scope.
 *
 * Beacons are found through BeaconRegistry (loaded and ticking on this client) and grouped
 * into networks with PowerNetwork.findBeaconsInNetwork. The list is captured when the screen
 * opens; use Refresh after placing or removing beacons.
 */
public class LightsPanelScreen extends Screen {

    private enum Tab { BEAM, REACT, COLOR, MOTION, PRESETS }

    private enum Scope { ALL, NETWORK, BEACON }

    private static final String[][] PRESET_ROWS = {
            {"Default", "Chill", "Vertical", "Sky"},
            {"Party", "Bass", "Strobe", "BPM Sync"},
            {"Rainbow", "Sway", "Laser", BeaconPresets.SWEEP}
    };

    private static final int MARGIN = 8;

    // ---- data ----
    private final List<MusicBeaconBlockEntity> all = new ArrayList<>();
    private final List<List<MusicBeaconBlockEntity>> networks = new ArrayList<>();

    private Scope scope = Scope.ALL;
    private int selNetwork = 0;
    private MusicBeaconBlockEntity selBeacon = null;
    private Tab tab = Tab.BEAM;
    private int netPage = 0;
    private int bcnPage = 0;

    // ---- layout ----
    private int shellX, shellY, shellW, shellH;
    private int leftX, leftY, leftW, leftH;
    private int rightX, rightY, rightW, rightH;
    private int contentX, contentY, contentW, contentBottom;
    private int netHeaderY, bcnHeaderY;
    private int pad = 6;
    private int rowH = 18;
    private int widgetH = 16;

    // grid cursor used while building a tab
    private int gy;
    private int gcol;

    // color tab
    private EditBox hexBox;
    private int previewX, previewY, previewW, previewH;

    public LightsPanelScreen() {
        super(Component.literal("Lights Control Panel"));
        refresh();
    }

    // ------------------------------------------------------------------ data

    /** Re-reads the loaded beacons and regroups them into networks. */
    private void refresh() {
        all.clear();
        networks.clear();
        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        all.addAll(BeaconRegistry.loaded(level));
        all.sort(Comparator
                .comparingInt((MusicBeaconBlockEntity b) -> b.getBlockPos().getX())
                .thenComparingInt(b -> b.getBlockPos().getZ())
                .thenComparingInt(b -> b.getBlockPos().getY()));

        Set<MusicBeaconBlockEntity> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MusicBeaconBlockEntity b : all) {
            if (assigned.contains(b)) continue;
            List<MusicBeaconBlockEntity> group = new ArrayList<>();
            group.add(b);
            assigned.add(b);
            for (MusicBeaconBlockEntity m : PowerNetwork.findBeaconsInNetwork(level, b.getBlockPos())) {
                if (all.contains(m) && assigned.add(m)) group.add(m);
            }
            networks.add(group);
        }

        if (selNetwork >= networks.size()) selNetwork = Math.max(0, networks.size() - 1);
        if (selBeacon != null && !all.contains(selBeacon)) {
            selBeacon = null;
            if (scope == Scope.BEACON) scope = Scope.ALL;
        }
        if (networks.isEmpty() && scope == Scope.NETWORK) scope = Scope.ALL;
    }

    private int networkOf(MusicBeaconBlockEntity be) {
        for (int i = 0; i < networks.size(); i++) {
            if (networks.get(i).contains(be)) return i;
        }
        return -1;
    }

    private List<MusicBeaconBlockEntity> targets() {
        return switch (scope) {
            case ALL -> all;
            case NETWORK -> selNetwork >= 0 && selNetwork < networks.size()
                    ? networks.get(selNetwork) : List.of();
            case BEACON -> selBeacon != null && all.contains(selBeacon) ? List.of(selBeacon) : List.of();
        };
    }

    /** Beacon whose values the controls display. */
    private MusicBeaconBlockEntity ref() {
        List<MusicBeaconBlockEntity> t = targets();
        return t.isEmpty() ? null : t.get(0);
    }

    /** Runs a change on every beacon in the current scope. */
    private void each(java.util.function.Consumer<MusicBeaconBlockEntity> change) {
        for (MusicBeaconBlockEntity t : targets()) {
            change.accept(t);
            t.setChanged();
        }
    }

    private List<MusicBeaconBlockEntity> beaconListShown() {
        if (scope == Scope.ALL) return all;
        return selNetwork >= 0 && selNetwork < networks.size() ? networks.get(selNetwork) : all;
    }

    private static boolean anyActive(List<MusicBeaconBlockEntity> list) {
        for (MusicBeaconBlockEntity b : list) {
            if (b.isActive()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        boolean tiny = this.height < 320 || this.width < 480;
        pad = tiny ? 4 : 6;
        rowH = tiny ? 15 : 18;
        widgetH = rowH - 2;
        int gap = 3;

        shellW = Mth.clamp(this.width - MARGIN * 2, 360, 580);
        shellH = Mth.clamp(this.height - MARGIN * 2, 240, 340);
        shellX = (this.width - shellW) / 2;
        shellY = (this.height - shellH) / 2;

        leftX = shellX + pad;
        leftY = shellY + pad;
        leftW = shellW * 36 / 100;
        leftH = shellH - pad * 2;

        rightX = leftX + leftW + pad;
        rightY = leftY;
        rightW = shellW - leftW - pad * 3;
        rightH = leftH;

        contentX = rightX + pad;
        contentW = rightW - pad * 2;

        int tabY = rightY + 16;
        contentY = tabY + widgetH + 4;
        int fy2 = rightY + rightH - pad - widgetH;
        int fy1 = fy2 - widgetH - 2;
        contentBottom = fy1 - 4;

        buildLeft();

        // tabs
        int tabW = (rightW - pad * 2 - gap * 4) / 5;
        int tx = rightX + pad;
        addTab(tx, tabY, tabW, Tab.BEAM, "Beam");
        addTab(tx + (tabW + gap), tabY, tabW, Tab.REACT, "React");
        addTab(tx + 2 * (tabW + gap), tabY, tabW, Tab.COLOR, "Color");
        addTab(tx + 3 * (tabW + gap), tabY, tabW, Tab.MOTION, "Motion");
        addTab(tx + 4 * (tabW + gap), tabY, tabW, Tab.PRESETS, "Presets");

        previewW = 0;
        hexBox = null;
        MusicBeaconBlockEntity ref = ref();
        if (ref != null) {
            gy = contentY;
            gcol = 0;
            switch (tab) {
                case BEAM -> buildBeamTab(ref);
                case REACT -> buildReactTab(ref);
                case COLOR -> buildColorTab(ref);
                case MOTION -> buildMotionTab(ref);
                case PRESETS -> buildPresetsTab();
            }
        }

        // footer
        int half = (contentW - 4) / 2;
        Button pushNet = Button.builder(Component.literal("Push selected > network"), b -> pushSelected(false))
                .bounds(contentX, fy1, half, widgetH).build();
        pushNet.active = selBeacon != null && networkOf(selBeacon) >= 0;
        addRenderableWidget(pushNet);
        Button pushAll = Button.builder(Component.literal("Push selected > all"), b -> pushSelected(true))
                .bounds(contentX + half + 4, fy1, half, widgetH).build();
        pushAll.active = selBeacon != null;
        addRenderableWidget(pushAll);

        int third = (contentW - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            refresh();
            this.rebuildWidgets();
        }).bounds(contentX, fy2, third, widgetH).build());
        Button open = Button.builder(Component.literal("Open editor"), b -> {
            if (selBeacon != null) Minecraft.getInstance().gui.setScreen(new MusicBeaconScreen(selBeacon));
        }).bounds(contentX + third + 4, fy2, third, widgetH).build();
        open.active = selBeacon != null;
        addRenderableWidget(open);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(contentX + (third + 4) * 2, fy2, third, widgetH).build());
    }

    private void addTab(int x, int y, int w, Tab t, String label) {
        Component text = tab == t
                ? Component.literal(label).withStyle(ChatFormatting.YELLOW)
                : Component.literal(label);
        addRenderableWidget(Button.builder(text, b -> {
            if (tab != t) {
                tab = t;
                this.rebuildWidgets();
            }
        }).bounds(x, y - 0, w, widgetH).build());
    }

    // ------------------------------------------------------------------ left column (scope + lists)

    private void buildLeft() {
        int lx = leftX + pad;
        int lw = leftW - pad * 2;
        int secH = (leftH - pad * 2 - 4) / 2;
        int netRows = Math.max(1, (secH - 12 - rowH * 2) / rowH);
        int bcnRows = Math.max(1, (secH - 12 - rowH) / rowH);

        // ---- networks ----
        netHeaderY = leftY + pad;
        int y = netHeaderY + 12;
        addRow(lx, y, lw, "All beacons (" + all.size() + ")", scope == Scope.ALL, () -> {
            scope = Scope.ALL;
            this.rebuildWidgets();
        });
        y += rowH;

        int netPages = Math.max(1, (networks.size() + netRows - 1) / netRows);
        netPage = Mth.clamp(netPage, 0, netPages - 1);
        int from = netPage * netRows;
        int to = Math.min(networks.size(), from + netRows);
        for (int i = from; i < to; i++) {
            final int idx = i;
            List<MusicBeaconBlockEntity> net = networks.get(i);
            String label = "Network " + (i + 1) + " (" + net.size() + ")" + (anyActive(net) ? "  ON" : "");
            addRow(lx, y, lw, label, scope == Scope.NETWORK && selNetwork == i, () -> {
                scope = Scope.NETWORK;
                selNetwork = idx;
                if (selBeacon != null && networkOf(selBeacon) != idx) selBeacon = null;
                bcnPage = 0;
                this.rebuildWidgets();
            });
            y += rowH;
        }
        addPager(lx, leftY + pad + 12 + rowH + netRows * rowH, lw, netPage, netPages, p -> {
            netPage = p;
            this.rebuildWidgets();
        });

        // ---- beacons ----
        bcnHeaderY = leftY + pad + secH + 4;
        y = bcnHeaderY + 12;
        List<MusicBeaconBlockEntity> shown = beaconListShown();
        int bcnPages = Math.max(1, (shown.size() + bcnRows - 1) / bcnRows);
        bcnPage = Mth.clamp(bcnPage, 0, bcnPages - 1);
        from = bcnPage * bcnRows;
        to = Math.min(shown.size(), from + bcnRows);
        for (int i = from; i < to; i++) {
            MusicBeaconBlockEntity b = shown.get(i);
            BlockPos p = b.getBlockPos();
            String status = !b.enabled ? "  disabled" : (b.isActive() ? "  ON" : "  no power");
            String label = p.getX() + " " + p.getY() + " " + p.getZ() + status;
            addRow(lx, y, lw, label, b == selBeacon, () -> {
                selBeacon = b;
                scope = Scope.BEACON;
                int n = networkOf(b);
                if (n >= 0) selNetwork = n;
                this.rebuildWidgets();
            });
            y += rowH;
        }
        addPager(lx, bcnHeaderY + 12 + bcnRows * rowH, lw, bcnPage, bcnPages, p -> {
            bcnPage = p;
            this.rebuildWidgets();
        });
    }

    private void addRow(int x, int y, int w, String text, boolean selected, Runnable onClick) {
        Component c = selected
                ? Component.literal(text).withStyle(ChatFormatting.YELLOW)
                : Component.literal(text);
        addRenderableWidget(Button.builder(c, b -> onClick.run()).bounds(x, y, w, widgetH).build());
    }

    private void addPager(int x, int y, int w, int page, int pages, java.util.function.IntConsumer onPage) {
        int bw = 18;
        Button prev = Button.builder(Component.literal("<"), b -> onPage.accept(page - 1))
                .bounds(x, y, bw, widgetH).build();
        prev.active = page > 0;
        addRenderableWidget(prev);
        Button mid = Button.builder(Component.literal((page + 1) + " / " + pages), b -> { })
                .bounds(x + bw + 2, y, w - bw * 2 - 4, widgetH).build();
        mid.active = false;
        addRenderableWidget(mid);
        Button next = Button.builder(Component.literal(">"), b -> onPage.accept(page + 1))
                .bounds(x + w - bw, y, bw, widgetH).build();
        next.active = page < pages - 1;
        addRenderableWidget(next);
    }

    // ------------------------------------------------------------------ push full config

    /** Copies the selected beacon's whole config to its network, or to every beacon. */
    private void pushSelected(boolean toAll) {
        if (selBeacon == null) return;
        List<MusicBeaconBlockEntity> dest;
        if (toAll) {
            dest = all;
        } else {
            int n = networkOf(selBeacon);
            if (n < 0) return;
            dest = networks.get(n);
        }
        for (MusicBeaconBlockEntity other : dest) {
            if (other != selBeacon) copyConfig(selBeacon, other);
        }
        this.rebuildWidgets();
    }

    /** Same field list as the beacon screen's "Apply to network" (power/enabled are left alone). */
    private static void copyConfig(MusicBeaconBlockEntity from, MusicBeaconBlockEntity to) {
        to.yawDegrees = from.yawDegrees;
        to.pitchDegrees = from.pitchDegrees;
        to.beamLength = from.beamLength;
        to.collideWithBlocks = from.collideWithBlocks;
        to.baseBeamWidth = from.baseBeamWidth;
        to.baseBrightness = from.baseBrightness;
        to.colorRgb = from.colorRgb;
        to.widthSource = from.widthSource;
        to.rotationSource = from.rotationSource;
        to.brightnessSource = from.brightnessSource;
        to.beatResponse = from.beatResponse;
        to.particlesOnTreble = from.particlesOnTreble;
        to.pulseStrength = from.pulseStrength;
        to.pulseAttackMs = from.pulseAttackMs;
        to.pulseDecayMs = from.pulseDecayMs;
        to.rotationSpeedDegPerSec = from.rotationSpeedDegPerSec;
        to.rotationAmplitudeDeg = from.rotationAmplitudeDeg;
        to.rainbowMode = from.rainbowMode;
        to.rainbowSpeed = from.rainbowSpeed;
        to.swayStrength = from.swayStrength;
        to.bpmSync = from.bpmSync;
        to.bpmSyncBeats = from.bpmSyncBeats;
        to.lengthSource = from.lengthSource;
        to.lengthReactAmount = from.lengthReactAmount;
        to.duckOnBeat = from.duckOnBeat;
        to.reactivityFeel = from.reactivityFeel;
        to.idleMotion = from.idleMotion;
        to.particlesSource = from.particlesSource;
        to.colorMode = from.colorMode;
        to.phaseMode = from.phaseMode;
        to.sweepEnabled = from.sweepEnabled;
        to.sweepDir = from.sweepDir;
        to.sweepStyle = from.sweepStyle;
        to.sweepFromDeg = from.sweepFromDeg;
        to.sweepToDeg = from.sweepToDeg;
        to.sweepSpeed = from.sweepSpeed;
        to.sweepBpmSync = from.sweepBpmSync;
        to.sweepBeats = from.sweepBeats;
        to.displayColorRgb = to.colorRgb;
        to.setChanged();
    }

    // ------------------------------------------------------------------ tabs

    private void buildBeamTab(MusicBeaconBlockEntity r) {
        toggle(cell(), "Enabled", r.enabled, (t, v) -> t.enabled = v);
        toggle(cell(), "Stop at Blocks", r.collideWithBlocks, (t, v) -> t.collideWithBlocks = v);
        slider(cell(), r.baseBrightness, 0.1f, 1.5f, (t, v) -> t.baseBrightness = v,
                v -> String.format("Brightness: %.2f", v));
        slider(cell(), r.baseBeamWidth, 0.05f, 2.0f, (t, v) -> t.baseBeamWidth = v,
                v -> String.format("Width: %.2f", v));
        slider(cell(), r.beamLength, 1f, 256f, (t, v) -> t.beamLength = v,
                v -> String.format("Length: %.0f", v));
        slider(cell(), r.yawDegrees, -180f, 180f, (t, v) -> t.yawDegrees = v,
                v -> String.format("Yaw: %.0f", v));
        slider(cell(), r.pitchDegrees, -90f, 90f, (t, v) -> t.pitchDegrees = v,
                v -> String.format("Pitch: %.0f", v));
        cycle(cell(), "Particles", r.particlesSource, AudioSource.values(), (t, v) -> {
            t.particlesSource = v;
            t.particlesOnTreble = v != AudioSource.NONE;
        });
    }

    private void buildReactTab(MusicBeaconBlockEntity r) {
        cycle(cell(), "Beat", r.beatResponse, BeatResponse.values(), (t, v) -> t.beatResponse = v);
        cycle(cell(), "Feel", r.reactivityFeel, ReactivityFeel.values(), (t, v) -> t.reactivityFeel = v);
        slider(cell(), r.pulseStrength, 0f, 1f, (t, v) -> t.pulseStrength = v,
                v -> String.format("Pulse: %.2f", v));
        slider(cell(), r.duckOnBeat, 0f, 1f, (t, v) -> t.duckOnBeat = v,
                v -> String.format("Duck: %.2f", v));
        cycle(cell(), "Width", r.widthSource, AudioSource.values(), (t, v) -> t.widthSource = v);
        cycle(cell(), "Rotation", r.rotationSource, AudioSource.values(), (t, v) -> t.rotationSource = v);
        cycle(cell(), "Bright", r.brightnessSource, AudioSource.values(), (t, v) -> t.brightnessSource = v);
        cycle(cell(), "Length", r.lengthSource, AudioSource.values(), (t, v) -> t.lengthSource = v);
        slider(cell(), r.lengthReactAmount, 0.05f, 0.4f, (t, v) -> t.lengthReactAmount = v,
                v -> String.format("Len React: %.2f", v));
        toggle(cell(), "BPM Sync", r.bpmSync, (t, v) -> t.bpmSync = v);
    }

    private void buildColorTab(MusicBeaconBlockEntity r) {
        cycle(cell(), "Mode", r.colorMode, ColorMode.values(), (t, v) -> t.colorMode = v);
        slider(cell(), r.rainbowSpeed, 5f, 180f, (t, v) -> t.rainbowSpeed = v,
                v -> String.format("Rainbow: %.0f", v));
        colorSlider(full(), r, 16, "R");
        colorSlider(full(), r, 8, "G");
        colorSlider(full(), r, 0, "B");

        int[] c = full();
        hexBox = new EditBox(this.font, c[0], c[1], c[2] - 56, widgetH, Component.literal("Hex"));
        hexBox.setMaxLength(7);
        hexBox.setValue(String.format("#%06X", r.colorRgb & 0xFFFFFF));
        addRenderableWidget(hexBox);
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyHex())
                .bounds(c[0] + c[2] - 54, c[1], 54, widgetH).build());

        previewX = contentX;
        previewY = gy + 2;
        previewW = contentW;
        previewH = Math.max(0, Math.min(40, contentBottom - previewY));
    }

    private void buildMotionTab(MusicBeaconBlockEntity r) {
        slider(cell(), r.rotationSpeedDegPerSec, 0f, 120f, (t, v) -> t.rotationSpeedDegPerSec = v,
                v -> String.format("Spin: %.0f", v));
        slider(cell(), r.rotationAmplitudeDeg, 0f, 90f, (t, v) -> t.rotationAmplitudeDeg = v,
                v -> String.format("Wobble: %.0f", v));
        slider(cell(), r.swayStrength, 0f, 45f, (t, v) -> t.swayStrength = v,
                v -> String.format("Sway: %.0f", v));
        toggle(cell(), "Idle Motion", r.idleMotion, (t, v) -> t.idleMotion = v);
        cycle(cell(), "Phase", r.phaseMode, PhaseMode.values(), (t, v) -> t.phaseMode = v);
        slider(cell(), (float) r.bpmSyncBeats, 1f, 4f, (t, v) -> t.bpmSyncBeats = Math.max(1, Math.min(4, Math.round(v))),
                v -> "Spin Beats: " + Math.max(1, Math.min(4, Math.round(v))));
        toggle(cell(), "Sweep", r.sweepEnabled, (t, v) -> t.sweepEnabled = v);
        cycle(cell(), "Style", r.sweepStyle, SweepStyle.values(), (t, v) -> t.sweepStyle = v);
        cycle(full(), "Direction", r.sweepDir, SweepDir.values(), d -> d.label, (t, v) -> t.sweepDir = v);
        slider(cell(), r.sweepFromDeg, 0f, 180f, (t, v) -> t.sweepFromDeg = Math.round(v),
                v -> String.format("From: %.0f", v));
        slider(cell(), r.sweepToDeg, 0f, 180f, (t, v) -> t.sweepToDeg = Math.round(v),
                v -> String.format("To: %.0f", v));
        slider(cell(), r.sweepSpeed, 0.05f, 2f, (t, v) -> t.sweepSpeed = v,
                v -> String.format("Speed: %.2f/s", v));
        toggle(cell(), "Sweep BPM", r.sweepBpmSync, (t, v) -> t.sweepBpmSync = v);
        slider(full(), (float) r.sweepBeats, 1f, 8f, (t, v) -> t.sweepBeats = Math.max(1, Math.round(v)),
                v -> "Sweep Beats: " + Math.max(1, Math.round(v)));
    }

    private void buildPresetsTab() {
        int gap = 4;
        int cols = 4;
        int bw = (contentW - gap * (cols - 1)) / cols;
        int y = contentY;
        for (String[] row : PRESET_ROWS) {
            for (int i = 0; i < row.length; i++) {
                final String name = row[i];
                addRenderableWidget(Button.builder(Component.literal(name), b -> {
                    each(t -> t.applyPreset(name));
                    this.rebuildWidgets();
                }).bounds(contentX + i * (bw + gap), y, bw, widgetH).build());
            }
            y += rowH;
        }
    }

    // ------------------------------------------------------------------ widget helpers

    /** Next half-width cell of the two-column grid: {x, y, w}. */
    private int[] cell() {
        int cw = (contentW - 4) / 2;
        int[] r = {contentX + gcol * (cw + 4), gy, cw};
        if (++gcol == 2) {
            gcol = 0;
            gy += rowH;
        }
        return r;
    }

    /** Next full-width row (starts a new row if a half row is open): {x, y, w}. */
    private int[] full() {
        if (gcol != 0) {
            gcol = 0;
            gy += rowH;
        }
        int[] r = {contentX, gy, contentW};
        gy += rowH;
        return r;
    }

    private void slider(int[] c, float current, float min, float max,
                        BiConsumer<MusicBeaconBlockEntity, Float> set, Function<Float, String> labelFn) {
        double initial = Mth.clamp((current - min) / (max - min), 0.0, 1.0);
        addRenderableWidget(new AbstractSliderButton(c[0], c[1], c[2], widgetH,
                Component.literal(labelFn.apply(current)), initial) {
            @Override
            protected void updateMessage() {
                float v = (float) (min + this.value * (max - min));
                setMessage(Component.literal(labelFn.apply(v)));
            }

            @Override
            protected void applyValue() {
                float v = (float) (min + this.value * (max - min));
                each(t -> set.accept(t, v));
            }
        });
    }

    private void toggle(int[] c, String label, boolean current, BiConsumer<MusicBeaconBlockEntity, Boolean> set) {
        addRenderableWidget(CycleButton.onOffBuilder(current)
                .create(c[0], c[1], c[2], widgetH, Component.literal(label),
                        (btn, val) -> each(t -> set.accept(t, val))));
    }

    private <E extends Enum<E>> void cycle(int[] c, String label, E current, E[] values,
                                           BiConsumer<MusicBeaconBlockEntity, E> set) {
        cycle(c, label, current, values, e -> e.name(), set);
    }

    private <E extends Enum<E>> void cycle(int[] c, String label, E current, E[] values,
                                           Function<E, String> name, BiConsumer<MusicBeaconBlockEntity, E> set) {
        addRenderableWidget(CycleButton.builder((E e) -> Component.literal(name.apply(e)), current)
                .withValues(values)
                .create(c[0], c[1], c[2], widgetH, Component.literal(label),
                        (btn, val) -> each(t -> set.accept(t, val))));
    }

    /** One RGB channel: changes just that channel on every beacon in scope. */
    private void colorSlider(int[] c, MusicBeaconBlockEntity r, int shift, String label) {
        slider(c, (r.colorRgb >> shift) & 0xFF, 0f, 255f, (t, v) -> {
            int value = Mth.clamp(Math.round(v), 0, 255);
            t.colorRgb = ((t.colorRgb & ~(0xFF << shift)) | (value << shift)) & 0xFFFFFF;
            t.displayColorRgb = t.colorRgb;
        }, v -> label + ": " + Math.round(v));
    }

    private void applyHex() {
        if (hexBox == null) return;
        String s = hexBox.getValue().trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) return;
        try {
            int rgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            each(t -> {
                t.colorRgb = rgb;
                t.displayColorRgb = rgb;
            });
            this.rebuildWidgets();
        } catch (NumberFormatException ignored) {
            // leave the box as typed
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x28000000, 0x38000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int border = 0xFF4A4A5A;
        int subBg = 0xE0141420;

        drawPanel(graphics, shellX, shellY, shellW, shellH, 0xC00C0C14, border);
        drawPanel(graphics, leftX, leftY, leftW, leftH, subBg, border);
        drawPanel(graphics, rightX, rightY, rightW, rightH, subBg, border);

        graphics.text(this.font, "NETWORKS", leftX + pad, netHeaderY + 1, 0xFF88CCFF, true);
        String bcnTitle = scope == Scope.ALL ? "BEACONS (all)"
                : "BEACONS (network " + (selNetwork + 1) + ")";
        graphics.text(this.font, bcnTitle, leftX + pad, bcnHeaderY + 1, 0xFF88CCFF, true);
        if (all.isEmpty()) {
            graphics.text(this.font, "No beacons loaded nearby", leftX + pad, netHeaderY + 12 + rowH * 2, 0xFFFF7777, false);
        }

        graphics.text(this.font, scopeTitle(), rightX + pad, rightY + 5, 0xFFFFFF55, true);
        List<MusicBeaconBlockEntity> t = targets();
        if (t.size() > 1) {
            String hint = "shows first of " + t.size();
            graphics.text(this.font, hint, rightX + rightW - pad - this.font.width(hint), rightY + 5, 0xFF777777, false);
        }
        if (t.isEmpty()) {
            graphics.text(this.font, "Nothing to edit. Pick a scope on the left.",
                    contentX, contentY + 4, 0xFFAAAAAA, false);
        }

        if (tab == Tab.COLOR && previewW > 0 && previewH > 8) {
            MusicBeaconBlockEntity r = ref();
            if (r != null) {
                int col = r.colorRgb & 0xFFFFFF;
                graphics.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0xFF2A2A35);
                graphics.fill(previewX + 1, previewY + 1, previewX + previewW - 1, previewY + previewH - 1, 0xFF000000 | col);
                String hex = String.format("#%06X", col);
                int tx = previewX + (previewW - this.font.width(hex)) / 2;
                int ty = previewY + (previewH - 8) / 2;
                graphics.text(this.font, hex, tx + 1, ty + 1, 0x88000000, false);
                graphics.text(this.font, hex, tx, ty, 0xFFFFFFFF, false);
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private String scopeTitle() {
        return switch (scope) {
            case ALL -> "Editing: ALL beacons (" + all.size() + ")";
            case NETWORK -> "Editing: Network " + (selNetwork + 1) + " (" + targets().size() + ")";
            case BEACON -> {
                if (selBeacon == null) yield "Editing: -";
                BlockPos p = selBeacon.getBlockPos();
                yield "Editing: beacon " + p.getX() + " " + p.getY() + " " + p.getZ();
            }
        };
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill, int borderColor) {
        graphics.fill(x, y, x + w, y + h, fill);
        graphics.fill(x, y, x + w, y + 1, borderColor);
        graphics.fill(x, y + h - 1, x + w, y + h, borderColor);
        graphics.fill(x, y, x + 1, y + h, borderColor);
        graphics.fill(x + w - 1, y, x + w, y + h, borderColor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
