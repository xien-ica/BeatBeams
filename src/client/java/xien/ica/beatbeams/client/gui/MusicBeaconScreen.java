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
import net.minecraft.world.level.block.entity.BlockEntity;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;
import xien.ica.beatbeams.power.PowerNetwork;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.AudioSource;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.BeatResponse;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.PhaseMode;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.ColorMode;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.ReactivityFeel;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.SweepDir;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity.SweepStyle;
import xien.ica.beatbeams.audio.TrackPlayer;
import xien.ica.beatbeams.net.AudioFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Three-panel Music Beacon GUI (centered, responsive):
 *
 *  ┌──────────────┬─────────────────────┐
 *  │ AUDIO ~30%   │                     │
 *  ├──────────────┤   SETTINGS (right)  │
 *  │ MANAGER ~70% │   Beam|Color|Presets│
 *  │ list + on/off│                     │
 *  │              │  [Apply…] if dirty  │
 *  │              │  [Done] [Reset]     │
 *  └──────────────┴─────────────────────┘
 */
public class MusicBeaconScreen extends Screen {

    private enum Tab { BEAM, EFFECTS, COLOR, PRESETS }

    private static final int MARGIN = 8;
    private static final int MIN_TOTAL_W = 420;
    private static final int MAX_TOTAL_W = 600;

    private final MusicBeaconBlockEntity be;
    private Tab tab = Tab.BEAM;
    private boolean dirty;

    private final float startYaw, startPitch, startLength, startWidth, startBrightness;
    private final boolean startCollideWithBlocks;
    private final int startColor;
    private final AudioSource startWidthSrc, startRotSrc, startBrightSrc;
    private final BeatResponse startBeatResponse;
    private final boolean startParticles, startEnabled;
    private final float startPulseStrength, startRotSpeed;

    private int shellX, shellY, shellW, shellH;
    private int leftX, leftW, audioY, audioH, mgrY, mgrH;
    private int rightX, rightW, rightY, rightH;
    private int contentX, contentY, contentW, contentBottom;
    private int rowH = 18;
    private int pad = 6;
    private int tabH = 20;
    private int widgetH = 16;

    private EditBox hexBox;
    private boolean syncingColor;
    private AbstractSliderButton redSlider, greenSlider, blueSlider;
    private int colorPreviewX, colorPreviewY, colorPreviewW, colorPreviewH;

    private final List<MusicBeaconBlockEntity> nearbyBeacons = new ArrayList<>();
    /** Beam-tab section titles drawn in render (x,y,title). */
    private final List<int[]> sectionHeaderPos = new ArrayList<>(); // [x,y] parallel to sectionHeaderText
    private final List<String> sectionHeaderText = new ArrayList<>();
    private int selectedBeaconIndex = 0;
    private int listPage = 0;
    private static final int LIST_PAGE_SIZE = 6;

    public MusicBeaconScreen(MusicBeaconBlockEntity be) {
        super(Component.literal("Music Beacon"));
        this.be = be;
        this.startYaw = be.yawDegrees;
        this.startPitch = be.pitchDegrees;
        this.startLength = be.beamLength;
        this.startCollideWithBlocks = be.collideWithBlocks;
        this.startWidth = be.baseBeamWidth;
        this.startBrightness = be.baseBrightness;
        this.startColor = be.colorRgb;
        this.startWidthSrc = be.widthSource;
        this.startRotSrc = be.rotationSource;
        this.startBrightSrc = be.brightnessSource;
        this.startBeatResponse = be.beatResponse;
        this.startParticles = be.particlesOnTreble;
        this.startEnabled = be.enabled;
        this.startPulseStrength = be.pulseStrength;
        this.startRotSpeed = be.rotationSpeedDegPerSec;
    }

    @Override
    protected void init() {
        sectionHeaderPos.clear();
        sectionHeaderText.clear();
        // Breakpoints: tiny / compact / normal / large (fullscreen)
        boolean tiny = this.height < 360 || this.width < 520;
        boolean compact = !tiny && (this.height < 480 || this.width < 700);
        boolean large = this.width >= 1100 && this.height >= 650;

        pad = tiny ? 4 : (compact ? 5 : (large ? 8 : 6));
        rowH = tiny ? 15 : (compact ? 17 : 19);
        tabH = tiny ? 17 : 20;
        widgetH = tiny ? 14 : 16;
        int gap = tiny ? 2 : 3;
        int bottomMargin = large ? 12 : (compact ? 8 : 10); // keep Done/Reset inside panel

        // Width: balanced, not stretched on fullscreen
        int maxW = large ? 560 : (compact ? 640 : 600);
        maxW = Math.min(maxW, this.width - MARGIN * 2);
        if (tiny) {
            shellW = Mth.clamp(this.width - MARGIN * 2, MIN_TOTAL_W, maxW);
        } else if (large) {
            shellW = Mth.clamp(this.width * 45 / 100, 480, maxW);
        } else {
            shellW = Mth.clamp(this.width * 65 / 100, MIN_TOTAL_W, maxW);
        }

        // Height: fit content, leave air on tall screens (don't stretch empty space)
        int maxH = large ? 380 : (compact ? Math.min(400, this.height - MARGIN * 2) : Math.min(400, this.height - MARGIN * 2));
        if (tiny) maxH = this.height - MARGIN * 2;
        shellH = Mth.clamp(maxH, 220, this.height - MARGIN * 2);
        shellX = (this.width - shellW) / 2;
        shellY = Math.max(MARGIN, (this.height - shellH) / 2);

        // Left ~35%, right rest — slightly narrower manager on large screens
        leftW = large ? shellW * 34 / 100 : shellW * 38 / 100;
        leftX = shellX + pad;
        rightW = shellW - leftW - pad * 3;
        rightX = leftX + leftW + pad;
        rightY = shellY + pad;
        rightH = shellH - pad * 2;

        audioY = shellY + pad;
        audioH = Math.max(48, (shellH - pad * 3) * 28 / 100);
        mgrY = audioY + audioH + pad;
        mgrH = shellH - pad * 2 - audioH - pad;

        contentX = rightX + pad;
        contentY = rightY + tabH + gap + 2;
        contentW = rightW - pad * 2;

        // Always reserve footer strip: Apply row + Done/Reset + bottom margin
        // so widgets never overlap Apply / Done / Reset
        int applyRowSpace = rowH + 4;
        int doneRowSpace = widgetH + 4;
        contentBottom = shellY + shellH - bottomMargin - doneRowSpace - applyRowSpace - 2;

        refreshNearbyBeacons();
        selectedBeaconIndex = Math.max(0, nearbyBeacons.indexOf(be));

        int tabW = (rightW - pad * 2 - gap * 3) / 4;
        int tx = rightX + pad;
        int ty = rightY + 2;
        addTab(tx, ty, tabW, Tab.BEAM, "Beam");
        addTab(tx + tabW + gap, ty, tabW, Tab.EFFECTS, "Effects");
        addTab(tx + 2 * (tabW + gap), ty, tabW, Tab.COLOR, "Color");
        addTab(tx + 3 * (tabW + gap), ty, tabW, Tab.PRESETS, "Presets");

        switch (tab) {
            case BEAM -> buildBeamTab();
            case EFFECTS -> buildEffectsTab();
            case COLOR -> buildColorTab();
            case PRESETS -> buildPresetsTab();
        }

        // Footer always reserved: Apply row + Done/Reset (no rebuild needed when dirty)
        int fyDone = shellY + shellH - bottomMargin - widgetH - 2;
        int fyApply = fyDone - rowH - 4;
        int fw = (contentW - 4) / 2;
        int aw = (contentW - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Apply to network"), b -> applyToAll())
                .bounds(contentX, fyApply, aw, widgetH + 2).build());
        addRenderableWidget(Button.builder(Component.literal("Apply to selected"), b -> applyToSelected())
                .bounds(contentX + aw + 4, fyApply, aw, widgetH + 2).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(contentX, fyDone, fw, widgetH + 2).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> resetToStart())
                .bounds(contentX + fw + 4, fyDone, fw, widgetH + 2).build());

        buildManagerWidgets();
    }

    private void addTab(int x, int y, int w, Tab t, String label) {
        // 5 tabs are narrow, so the active tab is shown in yellow instead of [brackets]
        Component text = tab == t
                ? Component.literal(label).withStyle(ChatFormatting.YELLOW)
                : Component.literal(label);
        addRenderableWidget(Button.builder(text, b -> {
            if (tab != t) {
                tab = t;
                presetSettings = null;
                this.rebuildWidgets();
            }
        }).bounds(x, y, w, tabH - 2).build());
    }

    private void buildManagerWidgets() {
        int x = leftX + pad;
        int y = mgrY + 28;
        int w = leftW - pad * 2;

        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            refreshNearbyBeacons();
            this.rebuildWidgets();
        }).bounds(x, y, w, widgetH).build());
        y += rowH;

        if (nearbyBeacons.isEmpty()) return;

        int maxPage = Math.max(0, (nearbyBeacons.size() - 1) / LIST_PAGE_SIZE);
        if (listPage > maxPage) listPage = maxPage;
        int start = listPage * LIST_PAGE_SIZE;
        int end = Math.min(start + LIST_PAGE_SIZE, nearbyBeacons.size());

        for (int i = start; i < end; i++) {
            final int index = i;
            MusicBeaconBlockEntity mb = nearbyBeacons.get(i);
            BlockPos p = mb.getBlockPos();
            boolean isSelf = mb == be;
            boolean isSel = index == selectedBeaconIndex;
            String prefix = isSelf ? "*" : (isSel ? ">" : " ");
            String label = String.format("%s[%d,%d,%d]", prefix, p.getX(), p.getY(), p.getZ());

            int selectW = Math.max(40, w - 42);
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                selectedBeaconIndex = index;
                this.rebuildWidgets();
            }).bounds(x, y, selectW, widgetH).build());

            String onOff = mb.enabled ? "ON" : "OFF";
            addRenderableWidget(Button.builder(Component.literal(onOff), b -> {
                mb.enabled = !mb.enabled;
                mb.setChanged();
                this.rebuildWidgets();
            }).bounds(x + selectW + 2, y, 40, widgetH).build());

            y += rowH;
        }

        if (nearbyBeacons.size() > LIST_PAGE_SIZE) {
            y += 2;
            int pw = (w - 4) / 2;
            addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {
                if (listPage > 0) {
                    listPage--;
                    this.rebuildWidgets();
                }
            }).bounds(x, y, pw, widgetH).build());
            addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {
                if ((listPage + 1) * LIST_PAGE_SIZE < nearbyBeacons.size()) {
                    listPage++;
                    this.rebuildWidgets();
                }
            }).bounds(x + pw + 4, y, pw, widgetH).build());
        }
    }

    private void buildBeamTab() {
        int x = contentX;
        int y = contentY;
        int w = contentW;
        // headers + controls (tight fit)
        int rows = 14;
        int avail = Math.max(rows * 12, contentBottom - contentY);
        int step = Math.max(13, Math.min(rowH, avail / rows));
        int headerGap = Math.max(2, step / 4);

        // --- AIM ---
        addSectionHeader(x, y, "AIM");
        y += step - 2;

        addSlider(x, y, w, be.yawDegrees, -180f, 180f, v -> {
            be.yawDegrees = v; markDirty();
        }, v -> String.format("Yaw: %.0f", v));
        y += step;

        addSlider(x, y, w, be.pitchDegrees, -90f, 90f, v -> {
            be.pitchDegrees = v; markDirty();
        }, v -> String.format("Pitch: %.0f", v));
        y += step + headerGap;

        // --- SHAPE ---
        addSectionHeader(x, y, "SHAPE");
        y += step - 2;

        addSlider(x, y, w, be.beamLength, 1f, 256f, v -> {
            be.beamLength = v; markDirty();
        }, v -> String.format("Length: %.0f", v));
        y += step;

        addSlider(x, y, w, be.baseBeamWidth, 0.05f, 2.0f, v -> {
            be.baseBeamWidth = v; markDirty();
        }, v -> String.format("Width: %.2f", v));
        y += step;

        addSlider(x, y, w, be.rotationSpeedDegPerSec, 0f, 120f, v -> {
            be.rotationSpeedDegPerSec = v; markDirty();
        }, v -> String.format("Rotation: %.0f", v));
        y += step + headerGap;

        // --- RENDER ---
        addSectionHeader(x, y, "RENDER");
        y += step - 2;

        addSlider(x, y, w, be.baseBrightness, 0.1f, 1.5f, v -> {
            be.baseBrightness = v; markDirty();
        }, v -> String.format("Brightness: %.2f", v));
        y += step;

        addRenderableWidget(CycleButton.onOffBuilder(be.collideWithBlocks)
                .create(x, y, w, widgetH, Component.literal("Stop at Blocks"), (btn, val) -> {
                    be.collideWithBlocks = val; markDirty();
                }));
        y += step + headerGap;

        // --- MUSIC REACTIVITY ---
        addSectionHeader(x, y, "MUSIC REACTIVITY");
        y += step - 2;

        addSourceCycle(x, y, w, "Width Source", be.widthSource, v -> {
            be.widthSource = v; markDirty();
        });
        y += step;

        addSourceCycle(x, y, w, "Rotation Source", be.rotationSource, v -> {
            be.rotationSource = v; markDirty();
        });
        y += step;

        addSourceCycle(x, y, w, "Brightness Source", be.brightnessSource, v -> {
            be.brightnessSource = v; markDirty();
        });
        y += step;

        addSourceCycle(x, y, w, "Length Source", be.lengthSource, v -> {
            be.lengthSource = v; markDirty();
        });
        y += step;

        // Unified beat one-shot: NONE / PULSE / FLASH / BOTH
        addRenderableWidget(
                CycleButton.builder((BeatResponse r) -> Component.literal(r.name()), be.beatResponse)
                        .withValues(BeatResponse.values())
                        .create(x, y, w, widgetH, Component.literal("Beat Response"), (btn, val) -> {
                            be.beatResponse = val; markDirty();
                        }));
    }

    private void buildEffectsTab() {
        int x = contentX;
        int y = contentY;
        int w = contentW;
        // 13 controls + 4 section headers
        int rows = 18;
        int avail = Math.max(rows * 12, contentBottom - contentY);
        int step = Math.max(13, Math.min(rowH, avail / rows));
        int headerGap = Math.max(2, step / 4);

        // --- REACTIVITY ---
        addSectionHeader(x, y, "REACTIVITY");
        y += step - 2;

        addRenderableWidget(
                CycleButton.builder((ReactivityFeel f) -> Component.literal(f.name()), be.reactivityFeel)
                        .withValues(ReactivityFeel.values())
                        .create(x, y, w, widgetH, Component.literal("Feel"), (btn, val) -> {
                            be.reactivityFeel = val; markDirty();
                        }));
        y += step;

        addSlider(x, y, w, be.pulseStrength, 0f, 1f, v -> {
            be.pulseStrength = v; markDirty();
        }, v -> String.format("Pulse Strength: %.2f", v));
        y += step;

        addSlider(x, y, w, be.duckOnBeat, 0f, 1f, v -> {
            be.duckOnBeat = v; markDirty();
        }, v -> String.format("Duck on Beat: %.2f", v));
        y += step;

        addSlider(x, y, w, be.lengthReactAmount, 0.05f, 0.4f, v -> {
            be.lengthReactAmount = v; markDirty();
        }, v -> String.format("Length React: %.2f", v));
        y += step + headerGap;

        // --- MOTION ---
        addSectionHeader(x, y, "MOTION");
        y += step - 2;

        addSlider(x, y, w, be.rotationAmplitudeDeg, 0f, 90f, v -> {
            be.rotationAmplitudeDeg = v; markDirty();
        }, v -> String.format("Rotation Amplitude: %.0f", v));
        y += step;

        addSlider(x, y, w, be.swayStrength, 0f, 45f, v -> {
            be.swayStrength = v; markDirty();
        }, v -> String.format("Sway: %.0f", v));
        y += step;

        addRenderableWidget(CycleButton.onOffBuilder(be.idleMotion)
                .create(x, y, w, widgetH, Component.literal("Idle Motion"), (btn, val) -> {
                    be.idleMotion = val; markDirty();
                }));
        y += step;

        addRenderableWidget(
                CycleButton.builder((PhaseMode m) -> Component.literal(m.name()), be.phaseMode)
                        .withValues(PhaseMode.values())
                        .create(x, y, w, widgetH, Component.literal("Phase Mode"), (btn, val) -> {
                            be.phaseMode = val; markDirty();
                        }));
        y += step + headerGap;

        // --- BPM SYNC ---
        addSectionHeader(x, y, "BPM SYNC");
        y += step - 2;

        addRenderableWidget(CycleButton.onOffBuilder(be.bpmSync)
                .create(x, y, w, widgetH, Component.literal("BPM Sync"), (btn, val) -> {
                    be.bpmSync = val; markDirty();
                }));
        y += step;

        addSlider(x, y, w, (float) be.bpmSyncBeats, 1f, 4f, v -> {
            be.bpmSyncBeats = Math.max(1, Math.min(4, Math.round(v)));
            markDirty();
        }, v -> String.format("Beats / Turn: %.0f", (float) Math.max(1, Math.round(v))));
        y += step + headerGap;

        // --- COLOR & PARTICLES ---
        addSectionHeader(x, y, "COLOR & PARTICLES");
        y += step - 2;

        addSourceCycle(x, y, w, "Particles Source", be.particlesSource, v -> {
            be.particlesSource = v;
            be.particlesOnTreble = v != AudioSource.NONE;
            markDirty();
        });
        y += step;

        addRenderableWidget(
                CycleButton.builder((ColorMode m) -> Component.literal(m.name()), be.colorMode)
                        .withValues(ColorMode.values())
                        .create(x, y, w, widgetH, Component.literal("Color Mode"), (btn, val) -> {
                            be.colorMode = val;
                            be.rainbowMode = (val == ColorMode.RAINBOW);
                            markDirty();
                        }));
        y += step;

        addSlider(x, y, w, be.rainbowSpeed, 5f, 180f, v -> {
            be.rainbowSpeed = v; markDirty();
        }, v -> String.format("Rainbow Speed: %.0f", v));
    }

    private void buildColorTab() {
        int x = contentX;
        int y = contentY;
        int w = contentW;

        int r0 = (be.colorRgb >> 16) & 0xFF;
        int g0 = (be.colorRgb >> 8) & 0xFF;
        int b0 = be.colorRgb & 0xFF;

        redSlider = addColorSlider(x, y, w, r0, 16, "R");
        y += rowH;
        greenSlider = addColorSlider(x, y, w, g0, 8, "G");
        y += rowH;
        blueSlider = addColorSlider(x, y, w, b0, 0, "B");
        y += rowH + 2;

        hexBox = new EditBox(this.font, x, y, w - 56, widgetH, Component.literal("Hex"));
        hexBox.setMaxLength(7);
        hexBox.setValue(String.format("#%06X", be.colorRgb & 0xFFFFFF));
        hexBox.setResponder(this::onHexTyped);
        addRenderableWidget(hexBox);
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applyHexFromBox())
                .bounds(x + w - 54, y, 54, widgetH).build());
        y += rowH + 4;

        // Large live preview sits here (drawn in extractRenderState)
        colorPreviewX = x;
        colorPreviewY = y;
        colorPreviewW = w;
        colorPreviewH = Math.min(48, Math.max(28, contentBottom - y - 4));
    }

    private ColorSlider addColorSlider(int x, int y, int w, int current, int shift, String label) {
        double initial = Mth.clamp(current / 255.0, 0.0, 1.0);
        ColorSlider slider = new ColorSlider(x, y, w, widgetH, label, initial, shift);
        addRenderableWidget(slider);
        return slider;
    }

    private void syncSlidersFromColor() {
        syncingColor = true;
        int r = (be.colorRgb >> 16) & 0xFF;
        int g = (be.colorRgb >> 8) & 0xFF;
        int b = be.colorRgb & 0xFF;
        if (redSlider instanceof ColorSlider cs) cs.setRatio(r / 255.0, "R", r);
        if (greenSlider instanceof ColorSlider cs) cs.setRatio(g / 255.0, "G", g);
        if (blueSlider instanceof ColorSlider cs) cs.setRatio(b / 255.0, "B", b);
        syncingColor = false;
    }

    /** Slider with public setRatio for live hex → slider sync. */
    private final class ColorSlider extends AbstractSliderButton {
        private final String label;
        private final int shift;

        ColorSlider(int x, int y, int w, int h, String label, double initial, int shift) {
            super(x, y, w, h, Component.literal(label + ": " + (int) Math.round(initial * 255)), initial);
            this.label = label;
            this.shift = shift;
        }

        void setRatio(double ratio, String lbl, int display) {
            this.value = Mth.clamp(ratio, 0.0, 1.0);
            setMessage(Component.literal(lbl + ": " + display));
        }

        @Override
        protected void updateMessage() {
            int v = (int) Math.round(this.value * 255.0);
            setMessage(Component.literal(label + ": " + v));
        }

        @Override
        protected void applyValue() {
            if (syncingColor) return;
            int v = (int) Math.round(this.value * 255.0);
            setColorComponent(shift, v);
        }
    }

    private static final String[] PRESET_SECTION_NAMES = {"BASIC", "REACTIVE", "MOTION & COLOR"};
    private static final String[][] PRESET_SECTIONS = {
            {"Default", "Chill", "Vertical", "Sky"},
            {"Party", "Bass", "Strobe", "BPM Sync"},
            {"Rainbow", "Sway", "Laser", "Sweep"}
    };

    /** Preset whose settings page is open (null = the preset list). */
    private String presetSettings = null;
    /** Last preset applied from this screen. */
    private String activePreset = null;

    private void applyPresetNamed(String name) {
        be.applyPreset(name);
        activePreset = name;
        markDirty();
    }

    private void buildPresetsTab() {
        if (presetSettings != null) {
            buildPresetSettings(presetSettings);
            return;
        }
        int x = contentX;
        int y = contentY;
        int w = contentW;
        // 12 presets + 3 section headers
        int rows = 15;
        int avail = Math.max(rows * 12, contentBottom - contentY);
        int step = Math.max(13, Math.min(rowH + 1, avail / rows));
        int headerGap = Math.max(2, step / 4);
        int settingsW = Math.max(48, w / 5);
        int nameW = w - settingsW - 2;

        for (int s = 0; s < PRESET_SECTIONS.length; s++) {
            addSectionHeader(x, y, PRESET_SECTION_NAMES[s]);
            y += step - 2;
            for (String name : PRESET_SECTIONS[s]) {
                Component label = name.equals(activePreset)
                        ? Component.literal(name).withStyle(ChatFormatting.YELLOW)
                        : Component.literal(name);
                addRenderableWidget(Button.builder(label, b -> {
                    applyPresetNamed(name);
                    this.rebuildWidgets();
                }).bounds(x, y, nameW, widgetH).build());
                addRenderableWidget(Button.builder(Component.literal("Settings"), b -> {
                    // Open the page on this preset's values (apply it first if it isn't the active one)
                    if (!name.equals(activePreset)) applyPresetNamed(name);
                    presetSettings = name;
                    this.rebuildWidgets();
                }).bounds(x + nameW + 2, y, settingsW, widgetH).build());
                y += step;
            }
            y += headerGap;
        }
    }

    /** One preset's own controls. Edits go straight onto this beacon, like the other tabs. */
    private void buildPresetSettings(String name) {
        int x = contentX;
        int y = contentY;
        int w = contentW;
        boolean sweep = name.equals("Sweep");
        int rows = 17;
        int avail = Math.max(rows * 12, contentBottom - contentY);
        int step = Math.max(13, Math.min(rowH, avail / rows));
        int headerGap = Math.max(2, step / 4);

        addRenderableWidget(Button.builder(Component.literal("< Back to presets  -  " + name), b -> {
            presetSettings = null;
            this.rebuildWidgets();
        }).bounds(x, y, w, widgetH).build());
        y += step + headerGap;

        if (sweep) {
            addSectionHeader(x, y, "SWEEP");
            y += step - 2;

            addRenderableWidget(CycleButton.onOffBuilder(be.sweepEnabled)
                    .create(x, y, w, widgetH, Component.literal("Sweep"), (btn, val) -> {
                        be.sweepEnabled = val; markDirty();
                    }));
            y += step;

            addRenderableWidget(
                    CycleButton.builder((SweepDir d) -> Component.literal(d.label), be.sweepDir)
                            .withValues(SweepDir.values())
                            .create(x, y, w, widgetH, Component.literal("Direction"), (btn, val) -> {
                                be.sweepDir = val; markDirty();
                            }));
            y += step;

            addRenderableWidget(
                    CycleButton.builder((SweepStyle st) -> Component.literal(st.name()), be.sweepStyle)
                            .withValues(SweepStyle.values())
                            .create(x, y, w, widgetH, Component.literal("Style"), (btn, val) -> {
                                be.sweepStyle = val; markDirty();
                            }));
            y += step;

            addSlider(x, y, w, be.sweepFromDeg, 0f, 180f, v -> {
                be.sweepFromDeg = Math.round(v); markDirty();
            }, v -> String.format("From angle: %.0f", v));
            y += step;

            addSlider(x, y, w, be.sweepToDeg, 0f, 180f, v -> {
                be.sweepToDeg = Math.round(v); markDirty();
            }, v -> String.format("To angle: %.0f", v));
            y += step;

            addSlider(x, y, w, be.sweepSpeed, 0.05f, 2f, v -> {
                be.sweepSpeed = v; markDirty();
            }, v -> String.format("Speed: %.2f /s", v));
            y += step;

            addRenderableWidget(CycleButton.onOffBuilder(be.sweepBpmSync)
                    .create(x, y, w, widgetH, Component.literal("Sweep BPM Sync"), (btn, val) -> {
                        be.sweepBpmSync = val; markDirty();
                    }));
            y += step;

            addSlider(x, y, w, (float) be.sweepBeats, 1f, 8f, v -> {
                be.sweepBeats = Math.max(1, Math.round(v));
                markDirty();
            }, v -> String.format("Beats / Sweep: %.0f", (float) Math.max(1, Math.round(v))));
            y += step + headerGap;

            addSectionHeader(x, y, "AIM (shifts the whole sweep)");
            y += step - 2;

            addSlider(x, y, w, be.yawDegrees, -180f, 180f, v -> {
                be.yawDegrees = v; markDirty();
            }, v -> String.format("Yaw: %.0f", v));
            y += step;

            addSlider(x, y, w, be.pitchDegrees, -90f, 90f, v -> {
                be.pitchDegrees = v; markDirty();
            }, v -> String.format("Pitch: %.0f", v));
            y += step + headerGap;
        } else {
            addSectionHeader(x, y, "AIM");
            y += step - 2;

            addSlider(x, y, w, be.pitchDegrees, -90f, 90f, v -> {
                be.pitchDegrees = v; markDirty();
            }, v -> String.format("Pitch: %.0f", v));
            y += step + headerGap;
        }

        addSectionHeader(x, y, "SHAPE");
        y += step - 2;

        addSlider(x, y, w, be.beamLength, 1f, 256f, v -> {
            be.beamLength = v; markDirty();
        }, v -> String.format("Length: %.0f", v));
        y += step;

        addSlider(x, y, w, be.baseBeamWidth, 0.05f, 2.0f, v -> {
            be.baseBeamWidth = v; markDirty();
        }, v -> String.format("Width: %.2f", v));
        y += step;

        addSlider(x, y, w, be.baseBrightness, 0.1f, 1.5f, v -> {
            be.baseBrightness = v; markDirty();
        }, v -> String.format("Brightness: %.2f", v));
        y += step + headerGap;

        if (sweep) return;

        addSectionHeader(x, y, "MOTION");
        y += step - 2;

        addSlider(x, y, w, be.rotationSpeedDegPerSec, 0f, 120f, v -> {
            be.rotationSpeedDegPerSec = v; markDirty();
        }, v -> String.format("Rotation: %.0f", v));
        y += step;

        addSlider(x, y, w, be.rotationAmplitudeDeg, 0f, 90f, v -> {
            be.rotationAmplitudeDeg = v; markDirty();
        }, v -> String.format("Rotation Amplitude: %.0f", v));
        y += step;

        // Extras only for presets that use them
        if (name.equals("Chill") || name.equals("Sway")) {
            addSlider(x, y, w, be.swayStrength, 0f, 45f, v -> {
                be.swayStrength = v; markDirty();
            }, v -> String.format("Sway: %.0f", v));
            y += step;
        }
        if (name.equals("Party") || name.equals("Rainbow")) {
            addSlider(x, y, w, be.rainbowSpeed, 5f, 180f, v -> {
                be.rainbowSpeed = v; markDirty();
            }, v -> String.format("Rainbow Speed: %.0f", v));
            y += step;
        }
        if (name.equals("BPM Sync")) {
            addRenderableWidget(CycleButton.onOffBuilder(be.bpmSync)
                    .create(x, y, w, widgetH, Component.literal("BPM Sync"), (btn, val) -> {
                        be.bpmSync = val; markDirty();
                    }));
            y += step;
            addSlider(x, y, w, (float) be.bpmSyncBeats, 1f, 4f, v -> {
                be.bpmSyncBeats = Math.max(1, Math.min(4, Math.round(v)));
                markDirty();
            }, v -> String.format("Beats / Turn: %.0f", (float) Math.max(1, Math.round(v))));
            y += step;
        }
        y += headerGap;

        addSectionHeader(x, y, "BEAT");
        y += step - 2;

        addRenderableWidget(
                CycleButton.builder((BeatResponse r) -> Component.literal(r.name()), be.beatResponse)
                        .withValues(BeatResponse.values())
                        .create(x, y, w, widgetH, Component.literal("Beat Response"), (btn, val) -> {
                            be.beatResponse = val; markDirty();
                        }));
        y += step;

        addSlider(x, y, w, be.pulseStrength, 0f, 1f, v -> {
            be.pulseStrength = v; markDirty();
        }, v -> String.format("Pulse Strength: %.2f", v));
    }

    /** Beacons in the same wire/connector network as this one (sync group). */
    private void refreshNearbyBeacons() {
        nearbyBeacons.clear();
        var level = be.getLevel();
        if (level == null) return;
        nearbyBeacons.addAll(PowerNetwork.findBeaconsInNetwork(level, be.getBlockPos()));
        nearbyBeacons.remove(be);
        nearbyBeacons.add(0, be);
    }


    private void copyConfig(MusicBeaconBlockEntity from, MusicBeaconBlockEntity to) {
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
        to.setChanged();
    }

    private void applyToAll() {
        // Sync only within this power-network group (wires + connectors)
        for (MusicBeaconBlockEntity other : nearbyBeacons) {
            if (other != be) copyConfig(be, other);
        }
    }

    private void applyToSelected() {
        if (selectedBeaconIndex < 0 || selectedBeaconIndex >= nearbyBeacons.size()) return;
        MusicBeaconBlockEntity target = nearbyBeacons.get(selectedBeaconIndex);
        if (target != be) copyConfig(be, target);
    }

    private void setColorComponent(int shift, int value) {
        value = Mth.clamp(value, 0, 255);
        int mask = ~(0xFF << shift);
        be.colorRgb = (be.colorRgb & mask) | (value << shift);
        be.colorRgb &= 0xFFFFFF;
        be.setChanged();
        dirty = true;
        syncingColor = true;
        if (hexBox != null) hexBox.setValue(String.format("#%06X", be.colorRgb));
        syncingColor = false;
    }

    private void onHexTyped(String text) {
        if (syncingColor) return;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        // Accept 3-digit shorthand and partial; only apply when 6 hex digits
        if (s.length() != 6) return;
        try {
            int rgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            be.colorRgb = rgb;
            be.setChanged();
            dirty = true;
            syncSlidersFromColor();
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyHexFromBox() {
        if (hexBox == null) return;
        String s = hexBox.getValue().trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            be.colorRgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            be.setChanged();
            dirty = true;
            syncSlidersFromColor();
        } catch (NumberFormatException e) {
            hexBox.setValue(String.format("#%06X", be.colorRgb & 0xFFFFFF));
        }
    }

    private void addSectionHeader(int x, int y, String title) {
        sectionHeaderPos.add(new int[]{x, y});
        sectionHeaderText.add(title);
    }

    private static String friendlySource(AudioSource s) {
        return s.name();
    }

    private void addSourceCycle(int x, int y, int w, String label, AudioSource current,
                                java.util.function.Consumer<AudioSource> onChange) {
        addRenderableWidget(
                CycleButton.builder((AudioSource s) -> Component.literal(friendlySource(s)), current)
                        .withValues(AudioSource.values())
                        .create(x, y, w, widgetH, Component.literal(label), (btn, val) -> onChange.accept(val))
        );
    }

    private void addSlider(int x, int y, int w, float current, float min, float max,
                           java.util.function.Consumer<Float> onChange,
                           java.util.function.Function<Float, String> labelFn) {
        double initial = Mth.clamp((current - min) / (max - min), 0.0, 1.0);
        AbstractSliderButton slider = new AbstractSliderButton(x, y, w, widgetH,
                Component.literal(labelFn.apply(current)), initial) {
            @Override
            protected void updateMessage() {
                float v = (float) (min + this.value * (max - min));
                setMessage(Component.literal(labelFn.apply(v)));
            }

            @Override
            protected void applyValue() {
                float v = (float) (min + this.value * (max - min));
                onChange.accept(v);
            }
        };
        addRenderableWidget(slider);
    }

    private void resetToStart() {
        be.yawDegrees = startYaw;
        be.pitchDegrees = startPitch;
        be.beamLength = startLength;
        be.collideWithBlocks = startCollideWithBlocks;
        be.baseBeamWidth = startWidth;
        be.baseBrightness = startBrightness;
        be.colorRgb = startColor;
        be.widthSource = startWidthSrc;
        be.rotationSource = startRotSrc;
        be.brightnessSource = startBrightSrc;
        be.beatResponse = startBeatResponse;
        be.particlesOnTreble = startParticles;
        be.enabled = startEnabled;
        be.pulseStrength = startPulseStrength;
        be.rotationSpeedDegPerSec = startRotSpeed;
        dirty = false;
        be.setChanged();
        this.rebuildWidgets();
    }

    private void markDirty() {
        be.setChanged();
        dirty = true;
        // Apply buttons are always present — no rebuild needed
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x28000000, 0x38000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int border = 0xFF4A4A5A;
        int subBg = 0xE0141420;

        drawPanel(graphics, shellX, shellY, shellW, shellH, 0xC00C0C14, border);
        drawPanel(graphics, leftX, audioY, leftW, audioH, subBg, border);
        drawAudioStrip(graphics);
        drawPanel(graphics, leftX, mgrY, leftW, mgrH, subBg, border);
        graphics.text(this.font, "BEACONS", leftX + pad, mgrY + 4, 0xFF88CCFF, true);
        // Prefer live isActive() (blockstate-synced); circuitPowered alone can lag on client
        boolean pwrOn = be.isActive() || be.circuitPowered
                || (be.getBlockState().hasProperty(xien.ica.beatbeams.block.MusicBeaconBlock.POWERED)
                && be.getBlockState().getValue(xien.ica.beatbeams.block.MusicBeaconBlock.POWERED));
        String pwr = pwrOn ? "PWR ON" : "PWR OFF";
        int pwrCol = pwrOn ? 0xFF55FF55 : 0xFFFF5555;
        graphics.text(this.font, pwr, leftX + leftW - pad - this.font.width(pwr), mgrY + 4, pwrCol, false);
        graphics.text(this.font, nearbyBeacons.size() + " in network", leftX + pad + 52, mgrY + 4, 0xFFAAAAAA, false);
        graphics.text(this.font, "* this  > selected", leftX + pad, mgrY + 15, 0xFF666666, false);
        drawPanel(graphics, rightX, rightY, rightW, rightH, subBg, border);

        if (tab != Tab.COLOR) {
            for (int i = 0; i < sectionHeaderText.size(); i++) {
                int[] xy = sectionHeaderPos.get(i);
                graphics.text(this.font, sectionHeaderText.get(i), xy[0], xy[1], 0xFF88CCFF, true);
            }
        }
        if (tab == Tab.COLOR && colorPreviewW > 0) {
            int col = be.colorRgb & 0xFFFFFF;
            int px = colorPreviewX;
            int py = colorPreviewY;
            int pw = colorPreviewW;
            int ph = Math.max(24, colorPreviewH);
            // Border
            graphics.fill(px, py, px + pw, py + ph, 0xFF2A2A35);
            graphics.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, 0xFF000000 | col);
            // Checker underlay edge for near-black colors readability
            String hex = String.format("#%06X", col);
            int tx = px + (pw - this.font.width(hex)) / 2;
            int ty = py + (ph - 8) / 2;
            // Shadow text for contrast on any color
            graphics.text(this.font, hex, tx + 1, ty + 1, 0x88000000, false);
            graphics.text(this.font, hex, tx, ty, 0xFFFFFFFF, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawAudioStrip(GuiGraphicsExtractor graphics) {
        int ax = leftX + pad;
        int ay = audioY + 4;
        int line = 10;

        graphics.text(this.font, "AUDIO", ax, ay, 0xFF88CCFF, true);
        TrackPlayer player = TrackPlayer.get();
        AudioFrame frame = player.latest();
        TrackPlayer.State playerState = player.state();
        String status = switch (playerState) {
            case PLAYING -> shorten(player.currentName(), 26);
            case PAUSED -> "Paused";
            case LOADING -> "Loading...";
            case STOPPED -> player.statusMessage().isEmpty() ? "Stopped (/beatbeams play)" : shorten(player.statusMessage(), 26);
        };
        int statusColor = switch (playerState) {
            case PLAYING -> 0xFF55FF55;
            case PAUSED, LOADING -> 0xFFFFFF55;
            case STOPPED -> 0xFFFF5555;
        };
        graphics.text(this.font, status, ax + 42, ay, statusColor, true);
        ay += line;
        graphics.text(this.font,
                String.format("BPM %.0f  Beat %s", frame.bpm(), frame.beat() ? "YES" : "no"),
                ax, ay, 0xFFCCCCCC, true);
        ay += line;

        int barW = leftW - pad * 2 - 2;
        drawMeter(graphics, ax, ay, barW, "Vol", frame.volume());
        ay += 10;
        int third = (barW - 4) / 3;
        drawMeter(graphics, ax, ay, third, "B", frame.bass());
        drawMeter(graphics, ax + third + 2, ay, third, "M", frame.mid());
        drawMeter(graphics, ax + 2 * (third + 2), ay, third, "T", frame.treble());
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill, int borderColor) {
        graphics.fill(x, y, x + w, y + h, fill);
        graphics.fill(x, y, x + w, y + 1, borderColor);
        graphics.fill(x, y + h - 1, x + w, y + h, borderColor);
        graphics.fill(x, y, x + 1, y + h, borderColor);
        graphics.fill(x + w - 1, y, x + w, y + h, borderColor);
    }

    private void drawMeter(GuiGraphicsExtractor graphics, int x, int y, int totalW, String label, float value01) {
        int labelW = this.font.width(label) + 3;
        graphics.text(this.font, label, x, y, 0xFFAAAAAA, false);
        int barX = x + labelW;
        int barW = Math.max(6, totalW - labelW);
        int barH = 7;
        graphics.fill(barX, y, barX + barW, y + barH, 0xFF222222);
        int fill = (int) (Mth.clamp(value01, 0f, 1f) * barW);
        int color = value01 > 0.7f ? 0xFFFF5555 : (value01 > 0.35f ? 0xFFFFAA00 : 0xFF55FF55);
        if (fill > 0) graphics.fill(barX, y, barX + fill, y + barH, color | 0xFF000000);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}