package xien.ica.beatbeams;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import xien.ica.beatbeams.block.ConnectorBlock;
import xien.ica.beatbeams.block.ConnectorBlockEntity;
import xien.ica.beatbeams.block.LightsPanelBlock;
import xien.ica.beatbeams.block.MusicBeaconBlock;
import xien.ica.beatbeams.block.MusicBeaconBlockEntity;
import xien.ica.beatbeams.block.MusicPlayerBlock;
import xien.ica.beatbeams.block.WireBlock;
import xien.ica.beatbeams.power.PowerNetwork;

public class Beatbeams implements ModInitializer {

    public static final String MOD_ID = "beatbeams";

    public static Block MUSIC_BEACON_BLOCK;
    public static Item MUSIC_BEACON_ITEM;
    public static BlockEntityType<MusicBeaconBlockEntity> MUSIC_BEACON_BLOCK_ENTITY;

    public static Block WIRE_BLOCK;
    public static Item WIRE_ITEM;

    public static Block CONNECTOR_BLOCK;
    public static Item CONNECTOR_ITEM;
    public static BlockEntityType<ConnectorBlockEntity> CONNECTOR_BLOCK_ENTITY;

    public static Block MUSIC_PLAYER_BLOCK;
    public static Item MUSIC_PLAYER_ITEM;

    public static Block LIGHTS_PANEL_BLOCK;
    public static Item LIGHTS_PANEL_ITEM;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        registerMusicBeacon();
        registerWire();
        registerConnector();
        registerMusicPlayer();
        registerLightsPanel();

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> {
            output.accept(MUSIC_BEACON_ITEM);
            output.accept(WIRE_ITEM);
            output.accept(CONNECTOR_ITEM);
            output.accept(MUSIC_PLAYER_ITEM);
            output.accept(LIGHTS_PANEL_ITEM);
        });

        // Drain power-network queue after neighbor updates settle — never recompute mid-chain.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var level : server.getAllLevels()) {
                PowerNetwork.processPending(level);
            }
        });
    }

    private void registerMusicBeacon() {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id("music_beacon"));
        MUSIC_BEACON_BLOCK = new MusicBeaconBlock(BlockBehaviour.Properties.of()
                .setId(blockKey)
                .mapColor(MapColor.COLOR_LIGHT_BLUE)
                .strength(3.0f, 6.0f)
                .lightLevel(state -> 8)
                .noOcclusion());
        Registry.register(BuiltInRegistries.BLOCK, blockKey, MUSIC_BEACON_BLOCK);

        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("music_beacon"));
        MUSIC_BEACON_ITEM = new BlockItem(MUSIC_BEACON_BLOCK, new Item.Properties().setId(itemKey));
        Registry.register(BuiltInRegistries.ITEM, itemKey, MUSIC_BEACON_ITEM);

        MUSIC_BEACON_BLOCK_ENTITY = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("music_beacon"),
                FabricBlockEntityTypeBuilder.create(MusicBeaconBlockEntity::new, MUSIC_BEACON_BLOCK).build());
    }

    private void registerWire() {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id("wire"));
        WIRE_BLOCK = new WireBlock(BlockBehaviour.Properties.of()
                .setId(blockKey)
                .mapColor(MapColor.COLOR_RED)
                .strength(0.0f)
                .noCollision()
                .lightLevel(state -> state.getValue(WireBlock.POWERED) ? 9 : 0)
                .sound(SoundType.STONE));
        Registry.register(BuiltInRegistries.BLOCK, blockKey, WIRE_BLOCK);

        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("wire"));
        WIRE_ITEM = new BlockItem(WIRE_BLOCK, new Item.Properties().setId(itemKey));
        Registry.register(BuiltInRegistries.ITEM, itemKey, WIRE_ITEM);
    }

    private void registerConnector() {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id("connector"));
        CONNECTOR_BLOCK = new ConnectorBlock(BlockBehaviour.Properties.of()
                .setId(blockKey)
                .mapColor(MapColor.STONE)
                .strength(1.5f, 6.0f)
                .requiresCorrectToolForDrops()
                .sound(SoundType.STONE));
        Registry.register(BuiltInRegistries.BLOCK, blockKey, CONNECTOR_BLOCK);

        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("connector"));
        CONNECTOR_ITEM = new BlockItem(CONNECTOR_BLOCK, new Item.Properties().setId(itemKey));
        Registry.register(BuiltInRegistries.ITEM, itemKey, CONNECTOR_ITEM);

        CONNECTOR_BLOCK_ENTITY = Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("connector"),
                FabricBlockEntityTypeBuilder.create(ConnectorBlockEntity::new, CONNECTOR_BLOCK).build());
    }

    private void registerMusicPlayer() {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id("music_player"));
        MUSIC_PLAYER_BLOCK = new MusicPlayerBlock(
                BlockBehaviour.Properties.ofFullCopy(Blocks.MANGROVE_TRAPDOOR).setId(blockKey));
        Registry.register(BuiltInRegistries.BLOCK, blockKey, MUSIC_PLAYER_BLOCK);

        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("music_player"));
        MUSIC_PLAYER_ITEM = new BlockItem(MUSIC_PLAYER_BLOCK, new Item.Properties().setId(itemKey));
        Registry.register(BuiltInRegistries.ITEM, itemKey, MUSIC_PLAYER_ITEM);
    }

    private void registerLightsPanel() {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id("lights_panel"));
        // Looked up by registry id, so a renamed Blocks.* constant can't break the build.
        // Falls back to the iron trapdoor's properties if the id doesn't exist in this version.
        Block base = BuiltInRegistries.BLOCK
                .getOptional(Identifier.fromNamespaceAndPath("minecraft", "waxed_copper_trapdoor"))
                .orElse(Blocks.IRON_TRAPDOOR);
        LIGHTS_PANEL_BLOCK = new LightsPanelBlock(
                BlockBehaviour.Properties.ofFullCopy(base).setId(blockKey));
        Registry.register(BuiltInRegistries.BLOCK, blockKey, LIGHTS_PANEL_BLOCK);

        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("lights_panel"));
        LIGHTS_PANEL_ITEM = new BlockItem(LIGHTS_PANEL_BLOCK, new Item.Properties().setId(itemKey));
        Registry.register(BuiltInRegistries.ITEM, itemKey, LIGHTS_PANEL_ITEM);
    }
}
