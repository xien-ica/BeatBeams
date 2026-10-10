package xien.ica.beatbeams.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import xien.ica.beatbeams.audio.TrackLibrary;
import xien.ica.beatbeams.audio.TrackPlayer;

import java.util.List;

/**
 * Music Player GUI: now-playing strip with transport buttons on top, library (left) and
 * queue (right) below. All state lives in TrackPlayer, so this screen holds only paging.
 *
 * Button labels that depend on player state are updated in place every frame; the lists are
 * rebuilt only when the queue, library or playing entry actually changes.
 */
public class MusicPlayerScreen extends Screen {

    private static final int MARGIN = 8;

    private int shellX, shellY, shellW, shellH;
    private int nowY, nowH;
    private int libX, queueX, panelW, panelY, panelH;
    private int pad = 6;
    private int widgetH = 16;
    private int rowH = 18;
    private int rowsPerPage = 5;

    private int libPage;
    private int queuePage;
    private int lastSignature = Integer.MIN_VALUE;

    private Button playPauseButton;
    private Button loopButton;

    public MusicPlayerScreen() {
        super(Component.literal("Music Player"));
        TrackPlayer.get().library().rescan(); // pick up files added since last time
        int index = TrackPlayer.get().queueIndex();
        if (index >= 0) queuePage = index / 5; // start on the page with the playing entry
    }

    @Override
    protected void init() {
        boolean tiny = this.height < 300 || this.width < 420;
        pad = tiny ? 4 : 6;
        widgetH = tiny ? 14 : 16;
        rowH = widgetH + 2;

        shellW = Mth.clamp(this.width - MARGIN * 2, 200, 540);
        shellH = Mth.clamp(this.height - MARGIN * 2, 180, 250);
        shellX = (this.width - shellW) / 2;
        shellY = (this.height - shellH) / 2;

        // Now-playing strip: title line, progress bar, transport buttons
        nowY = shellY + 20;
        nowH = 12 + 10 + 6 + widgetH + 8;

        panelY = nowY + nowH + 6;
        panelH = shellY + shellH - pad - panelY;
        panelW = (shellW - pad * 3) / 2;
        libX = shellX + pad;
        queueX = libX + panelW + pad;

        // rows fit between header (20) and pager (widgetH + 4)
        rowsPerPage = Math.max(2, (panelH - 20 - (widgetH + 6)) / rowH);

        buildTransport();
        buildLibrary();
        buildQueue();
        lastSignature = signature();
    }

    // ------------------------------------------------------------------ widgets

    private void buildTransport() {
        TrackPlayer player = TrackPlayer.get();
        int y = nowY + 12 + 10 + 6;
        int x = shellX + pad + 4;
        int totalW = shellW - pad * 2 - 8;
        int gap = 4;
        int btnW = (totalW - gap * 4) / 5;

        addRenderableWidget(Button.builder(Component.literal("|<"), b -> player.previous())
                .bounds(x, y, btnW, widgetH).build());
        playPauseButton = Button.builder(Component.literal("Play"), b -> player.playPause())
                .bounds(x + (btnW + gap), y, btnW, widgetH).build();
        addRenderableWidget(playPauseButton);
        addRenderableWidget(Button.builder(Component.literal(">|"), b -> player.next())
                .bounds(x + (btnW + gap) * 2, y, btnW, widgetH).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> player.stop())
                .bounds(x + (btnW + gap) * 3, y, btnW, widgetH).build());
        loopButton = Button.builder(loopLabel(), b -> {
            player.setLoop(!player.loop());
            b.setMessage(loopLabel());
        }).bounds(x + (btnW + gap) * 4, y, btnW, widgetH).build();
        addRenderableWidget(loopButton);
    }

    private void buildLibrary() {
        TrackPlayer player = TrackPlayer.get();
        List<TrackLibrary.Track> tracks = player.library().tracks();
        int x = libX + pad;
        int w = panelW - pad * 2;

        // header buttons (right-aligned): Rescan, Add all
        int hb = Math.max(34, (w - 60) / 2);
        addRenderableWidget(Button.builder(Component.literal("Add all"), b -> {
            player.enqueueAll(tracks);
            this.rebuildWidgets();
        }).bounds(libX + panelW - pad - hb, panelY + 3, hb, widgetH).build());
        addRenderableWidget(Button.builder(Component.literal("Rescan"), b -> {
            player.library().rescan();
            libPage = 0;
            this.rebuildWidgets();
        }).bounds(libX + panelW - pad - hb * 2 - 2, panelY + 3, hb, widgetH).build());

        int maxPage = Math.max(0, (tracks.size() - 1) / rowsPerPage);
        libPage = Mth.clamp(libPage, 0, maxPage);
        int start = libPage * rowsPerPage;
        int end = Math.min(start + rowsPerPage, tracks.size());

        int y = panelY + 22;
        int addW = 18;
        int nameW = w - addW - 2;
        for (int i = start; i < end; i++) {
            final TrackLibrary.Track t = tracks.get(i);
            addRenderableWidget(Button.builder(Component.literal(fit(t.name(), nameW - 8)),
                            b -> player.play(t))
                    .bounds(x, y, nameW, widgetH).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                player.enqueue(t);
                this.rebuildWidgets();
            }).bounds(x + nameW + 2, y, addW, widgetH).build());
            y += rowH;
        }
        buildPager(libX, w, tracks.size(), true);
    }

    private void buildQueue() {
        TrackPlayer player = TrackPlayer.get();
        List<TrackLibrary.Track> queue = player.queue();
        int playing = player.queueIndex();
        int x = queueX + pad;
        int w = panelW - pad * 2;

        int hb = Math.max(34, (w - 60));
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            player.clearQueue();
            queuePage = 0;
            this.rebuildWidgets();
        }).bounds(queueX + panelW - pad - hb, panelY + 3, hb, widgetH).build());

        int maxPage = Math.max(0, (queue.size() - 1) / rowsPerPage);
        queuePage = Mth.clamp(queuePage, 0, maxPage);
        int start = queuePage * rowsPerPage;
        int end = Math.min(start + rowsPerPage, queue.size());

        int y = panelY + 22;
        int delW = 18;
        int nameW = w - delW - 2;
        for (int i = start; i < end; i++) {
            final int index = i;
            boolean isPlaying = i == playing;
            String label = (isPlaying ? "> " : (i + 1) + ". ") + queue.get(i).name();
            Component text = Component.literal(fit(label, nameW - 8));
            if (isPlaying) text = text.copy().withStyle(ChatFormatting.GREEN);
            addRenderableWidget(Button.builder(text, b -> player.playQueueIndex(index))
                    .bounds(x, y, nameW, widgetH).build());
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                player.removeFromQueue(index);
                this.rebuildWidgets();
            }).bounds(x + nameW + 2, y, delW, widgetH).build());
            y += rowH;
        }
        buildPager(queueX, w, queue.size(), false);
    }

    private void buildPager(int panelLeft, int w, int count, boolean library) {
        if (count <= rowsPerPage) return;
        int x = panelLeft + pad;
        int y = panelY + panelH - widgetH - 4;
        int pw = (w - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {
            if (library) libPage = Math.max(0, libPage - 1);
            else queuePage = Math.max(0, queuePage - 1);
            this.rebuildWidgets();
        }).bounds(x, y, pw, widgetH).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {
            int maxPage = Math.max(0, (count - 1) / rowsPerPage);
            if (library) libPage = Math.min(maxPage, libPage + 1);
            else queuePage = Math.min(maxPage, queuePage + 1);
            this.rebuildWidgets();
        }).bounds(x + pw + 4, y, pw, widgetH).build());
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x28000000, 0x38000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Something changed outside the screen (track ended, command used): rebuild the lists.
        if (signature() != lastSignature) {
            this.rebuildWidgets();
        }
        TrackPlayer player = TrackPlayer.get();
        TrackPlayer.State state = player.state();
        playPauseButton.setMessage(Component.literal(state == TrackPlayer.State.PLAYING ? "Pause" : "Play"));

        int border = 0xFF4A4A5A;
        int subBg = 0xE0141420;
        drawPanel(graphics, shellX, shellY, shellW, shellH, 0xC00C0C14, border);
        graphics.text(this.font, "MUSIC PLAYER", shellX + pad + 4, shellY + 7, 0xFFFFD27F, true);
        String folder = player.library().tracks().size() + " track(s)";
        graphics.text(this.font, folder, shellX + shellW - pad - 4 - this.font.width(folder), shellY + 7, 0xFF888888, false);

        // Now playing
        drawPanel(graphics, shellX + pad, nowY, shellW - pad * 2, nowH, subBg, border);
        int tx = shellX + pad + 4;
        String title;
        int titleColor;
        switch (state) {
            case PLAYING -> { title = player.currentName(); titleColor = 0xFF55FF55; }
            case PAUSED -> { title = "Paused: " + player.currentName(); titleColor = 0xFFFFFF55; }
            case LOADING -> { title = "Loading..."; titleColor = 0xFFFFFF55; }
            default -> {
                title = player.statusMessage().isEmpty() ? "Nothing playing" : player.statusMessage();
                titleColor = player.statusMessage().isEmpty() ? 0xFFAAAAAA : 0xFFFF5555;
            }
        }
        int textW = shellW - pad * 2 - 8;
        graphics.text(this.font, fit(title, textW), tx, nowY + 4, titleColor, true);

        double pos = player.positionSeconds();
        double dur = player.durationSeconds();
        int barY = nowY + 4 + 10;
        int barW = textW - 90;
        graphics.fill(tx, barY + 1, tx + barW, barY + 5, 0xFF222222);
        if (dur > 0) {
            int fill = (int) (Mth.clamp((float) (pos / dur), 0f, 1f) * barW);
            if (fill > 0) graphics.fill(tx, barY + 1, tx + fill, barY + 5, 0xFF55AAFF);
        }
        String time = dur > 0 ? fmt(pos) + " / " + fmt(dur) : "-:-- / -:--";
        if (player.currentBpm() > 0) time += "  " + Math.round(player.currentBpm()) + " BPM";
        graphics.text(this.font, time, tx + barW + 6, barY - 1, 0xFFCCCCCC, false);

        // List panels
        drawPanel(graphics, libX, panelY, panelW, panelH, subBg, border);
        drawPanel(graphics, queueX, panelY, panelW, panelH, subBg, border);
        graphics.text(this.font, "LIBRARY", libX + pad, panelY + 7, 0xFF88CCFF, true);
        graphics.text(this.font, "QUEUE (" + player.queue().size() + ")", queueX + pad, panelY + 7, 0xFF88CCFF, true);
        if (player.library().tracks().isEmpty()) {
            graphics.text(this.font, "Put .mp3/.wav/.ogg in", libX + pad, panelY + 26, 0xFF888888, false);
            graphics.text(this.font, "config/beatbeams/music", libX + pad, panelY + 36, 0xFF888888, false);
        }
        if (player.queue().isEmpty()) {
            graphics.text(this.font, "Queue is empty. Use + on a", queueX + pad, panelY + 26, 0xFF888888, false);
            graphics.text(this.font, "track, or Add all.", queueX + pad, panelY + 36, 0xFF888888, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    // ------------------------------------------------------------------ helpers

    private int signature() {
        TrackPlayer p = TrackPlayer.get();
        return 31 * (31 * p.queue().size() + p.queueIndex()) + p.library().tracks().size();
    }

    private Component loopLabel() {
        return Component.literal(TrackPlayer.get().loop() ? "Loop: On" : "Loop: Off");
    }

    /** Trims text with "..." so it fits in the given pixel width. */
    private String fit(String text, int maxPx) {
        if (this.font.width(text) <= maxPx) return text;
        String cut = text;
        while (cut.length() > 1 && this.font.width(cut + "...") > maxPx) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    private static String fmt(double seconds) {
        int s = (int) Math.max(0, seconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
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
