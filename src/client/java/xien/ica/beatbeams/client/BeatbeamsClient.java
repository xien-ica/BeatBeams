package xien.ica.beatbeams.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sounds.SoundSource;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import xien.ica.beatbeams.Beatbeams;
import xien.ica.beatbeams.block.LightsPanelBlock;
import xien.ica.beatbeams.block.MusicBeaconBlock;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;
import xien.ica.beatbeams.block.MusicPlayerBlock;
import xien.ica.beatbeams.block.WireBlock;
import xien.ica.beatbeams.client.gui.LightsPanelScreen;
import xien.ica.beatbeams.client.gui.MusicBeaconScreen;
import xien.ica.beatbeams.client.gui.MusicPlayerScreen;
import xien.ica.beatbeams.client.render.MusicBeaconBeamRenderer;
import xien.ica.beatbeams.audio.TrackPlayer;

import java.util.List;

public class BeatbeamsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(Beatbeams.MUSIC_BEACON_BLOCK_ENTITY, MusicBeaconBeamRenderer::new);

        // Music folder: <game dir>/config/beatbeams/music
        TrackPlayer.get().init(FabricLoader.getInstance().getConfigDir().resolve("beatbeams/music"));
        BeatbeamsCommands.register();

        // Follow Minecraft's sliders: Master x "Jukebox/Note Blocks". (Not the Music slider, so you can
        // mute vanilla background music without also muting this.) The playback thread never touches
        // Minecraft objects; we just push the number in once per tick.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> TrackPlayer.get().setOutputVolume(
                mc.options.getSoundSourceVolume(SoundSource.MASTER)
                        * mc.options.getSoundSourceVolume(SoundSource.RECORDS)));

        // Wire tint: black when off, bright red when on (redstone-dust style)
        BlockColorRegistry.register(List.of(new BlockTintSource() {
            @Override
            public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                return state.getValue(WireBlock.POWERED) ? 0xFFFF0000 : 0xFF000000;
            }

            @Override
            public int color(BlockState state) {
                return state.getValue(WireBlock.POWERED) ? 0xFFFF0000 : 0xFF000000;
            }
        }), Beatbeams.WIRE_BLOCK);

        MusicBeaconBlock.clientOpenScreen = pos -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (mc.level.getBlockEntity(pos) instanceof MusicBeaconBlockEntity be) {
                mc.gui.setScreen(new MusicBeaconScreen(be));
            }
        };

        MusicPlayerBlock.clientOpenScreen = pos -> Minecraft.getInstance().gui.setScreen(new MusicPlayerScreen());

        // GUIs edit the client copy of a beacon. Push each edit to the integrated server's copy,
        // because that copy is what gets saved to disk (and sent back on the next chunk load).
        MusicBeaconBlockEntity.clientChangedHook = be -> {
            Minecraft mc = Minecraft.getInstance();
            MinecraftServer server = mc.getSingleplayerServer();
            Level clientLevel = be.getLevel();
            if (server == null || clientLevel == null) return;
            ResourceKey<Level> dimension = clientLevel.dimension();
            BlockPos pos = be.getBlockPos();
            server.execute(() -> {
                ServerLevel serverLevel = server.getLevel(dimension);
                if (serverLevel != null && serverLevel.getBlockEntity(pos) instanceof MusicBeaconBlockEntity saved) {
                    saved.copyConfigFrom(be);
                    saved.setChanged();
                }
            });
        };

        LightsPanelBlock.clientOpenScreen = pos -> Minecraft.getInstance().gui.setScreen(new LightsPanelScreen());
    }
}