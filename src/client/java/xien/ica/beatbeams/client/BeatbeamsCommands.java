package xien.ica.beatbeams.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import xien.ica.beatbeams.audio.TrackLibrary;
import xien.ica.beatbeams.audio.TrackPlayer;

import java.util.List;

/**
 * /beatbeams list | rescan | play [name or number] | pause | resume | stop | next | prev | sync <ms> | status
 *
 * Client-side commands: nothing is sent to the server. If this file fails to compile after a
 * Minecraft/Fabric update, it is almost certainly the ClientCommands / sendFeedback names.
 */
final class BeatbeamsCommands {
    private BeatbeamsCommands() {}

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("beatbeams")
                        .then(ClientCommands.literal("list").executes(ctx -> {
                            List<TrackLibrary.Track> tracks = player().library().rescan();
                            if (tracks.isEmpty()) {
                                say(ctx.getSource(), "No tracks found. Put .mp3 / .wav / .ogg files in:");
                                say(ctx.getSource(), player().library().directory().toString());
                            } else {
                                for (int i = 0; i < tracks.size(); i++) {
                                    say(ctx.getSource(), (i + 1) + ". " + tracks.get(i).name());
                                }
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("rescan").executes(ctx -> {
                            say(ctx.getSource(), "Found " + player().library().rescan().size() + " track(s).");
                            return 1;
                        }))
                        .then(ClientCommands.literal("play")
                                .executes(ctx -> {
                                    List<TrackLibrary.Track> tracks = player().library().rescan();
                                    if (tracks.isEmpty()) {
                                        say(ctx.getSource(), "No tracks found in " + player().library().directory());
                                        return 0;
                                    }
                                    player().play(tracks.get(0));
                                    say(ctx.getSource(), "Playing " + tracks.get(0).name());
                                    return 1;
                                })
                                .then(ClientCommands.argument("track", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            player().library().rescan();
                                            TrackLibrary.Track t = player().library()
                                                    .find(StringArgumentType.getString(ctx, "track"));
                                            if (t == null) {
                                                say(ctx.getSource(), "No matching track. Try /beatbeams list");
                                                return 0;
                                            }
                                            player().play(t);
                                            say(ctx.getSource(), "Playing " + t.name());
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("pause").executes(ctx -> {
                            player().pause();
                            return 1;
                        }))
                        .then(ClientCommands.literal("resume").executes(ctx -> {
                            player().resume();
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            player().stop();
                            return 1;
                        }))
                        .then(ClientCommands.literal("next").executes(ctx -> {
                            player().library().rescan();
                            player().next();
                            return 1;
                        }))
                        .then(ClientCommands.literal("prev").executes(ctx -> {
                            player().library().rescan();
                            player().previous();
                            return 1;
                        }))
                        .then(ClientCommands.literal("sync")
                                .then(ClientCommands.argument("ms", IntegerArgumentType.integer(-500, 500))
                                        .executes(ctx -> {
                                            player().setSyncOffsetMs(IntegerArgumentType.getInteger(ctx, "ms"));
                                            say(ctx.getSource(), "Visual sync offset: " + player().syncOffsetMs()
                                                    + " ms (positive = beams earlier)");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            TrackPlayer p = player();
                            say(ctx.getSource(), p.state() + (p.currentName().isEmpty() ? "" : ": " + p.currentName())
                                    + String.format("  %.0f / %.0f s  BPM %.1f  sync %d ms",
                                    p.positionSeconds(), p.durationSeconds(), p.currentBpm(), p.syncOffsetMs()));
                            if (!p.statusMessage().isEmpty()) say(ctx.getSource(), p.statusMessage());
                            return 1;
                        }))));
    }

    private static TrackPlayer player() {
        return TrackPlayer.get();
    }

    private static void say(FabricClientCommandSource source, String text) {
        source.sendFeedback(Component.literal(text));
    }
}