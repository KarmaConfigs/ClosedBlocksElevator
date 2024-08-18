package com.github.karmadeb.closedblocks.plugin;

import com.github.karmadeb.closedblocks.api.event.plugin.ClosedPluginStateChangedEvent;
import com.github.karmadeb.closedblocks.api.integration.Integration;
import com.github.karmadeb.closedblocks.plugin.event.BlockGriefListener;
import com.github.karmadeb.closedblocks.plugin.event.PlayerMotionListener;
import com.github.karmadeb.closedblocks.plugin.integrations.bukkit.BukkitIntegration;
import com.github.karmadeb.closedblocks.plugin.integrations.itemsadder.ItemsAdderIntegration;
import com.github.karmadeb.closedblocks.plugin.provider.block.ClosedBlockSettings;
import com.github.karmadeb.closedblocks.plugin.provider.block.ElevatorBlock;
import de.tr7zw.changeme.nbtapi.NBT;
import es.karmadev.api.kson.JsonArray;
import es.karmadev.api.kson.JsonInstance;
import es.karmadev.api.kson.JsonNative;
import es.karmadev.api.kson.JsonObject;
import es.karmadev.api.kson.io.JsonReader;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.HandlerList;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class ClosedBlocksPlugin extends JavaPlugin {

    private final ClosedBlocksAPI api = new ClosedBlocksAPI(this);

    private PlayerMotionListener motionListener;
    private BlockGriefListener griefListener;

    @Override
    public void onEnable() {
        ClosedPluginStateChangedEvent event = new ClosedPluginStateChangedEvent(ClosedPluginStateChangedEvent.State.START);
        Bukkit.getPluginManager().callEvent(event);

        getLogger().info("Preparing to load stored blocks data. This may take a while...");
        loadCachedBlocks();
        getLogger().info("Loaded " + api.getBlockStorage().size() + " blocks");

        api.register();

        if (!NBT.preloadApi()) {
            getLogger().severe("Failed to initialize NBT support. ClosedBlocks depends on NBT to work, disabling...");
            getPluginLoader().disablePlugin(this);
            return;
        }

        Integration bukkitIntegration = new BukkitIntegration(this);
        Integration itemsAdderIntegration = new ItemsAdderIntegration(this);

        api.addIntegration(bukkitIntegration);
        api.addIntegration(itemsAdderIntegration);

        motionListener = new PlayerMotionListener(this);
        griefListener = new BlockGriefListener();
        getServer().getPluginManager().registerEvents(motionListener, this);
        getServer().getPluginManager().registerEvents(griefListener, this);
    }

    @Override
    public void onDisable() {
        api.shutdown();

        HandlerList.unregisterAll(motionListener);
        HandlerList.unregisterAll(griefListener);

        ClosedPluginStateChangedEvent event = new ClosedPluginStateChangedEvent(ClosedPluginStateChangedEvent.State.STOP);
        Bukkit.getPluginManager().callEvent(event);
    }

    public Path getDataPath() {
        return this.getDataFolder().toPath();
    }

    private void loadCachedBlocks() {
        Path storageDirectory = getDataPath().resolve("storage");
        if (!Files.exists(storageDirectory))
            return;

        forEachInDirectory(storageDirectory, this::handlePlayerDirectory);
    }

    private void handlePlayerDirectory(final Path file) {
        if (!Files.isDirectory(file))
            return;

        String rawPlayerUUID = file.getFileName().toString();
        UUID playerUUID = fromTrimmed(rawPlayerUUID);
        if (playerUUID == null) return;

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
        forEachInDirectory(file, (worldFolder) -> handleWorldDirectory(worldFolder, player));
    }

    private void handleWorldDirectory(final Path file, final OfflinePlayer player) {
        if (!Files.isDirectory(file))
            return;

        String rawWorldUUID = file.getFileName().toString();
        UUID worldUID = fromTrimmed(rawWorldUUID);
        if (worldUID == null) return;

        World world = Bukkit.getWorld(worldUID);
        if (world == null) return;

        Path worldData = file.resolve("data.json");
        readWorldData(player, world, worldData);
    }

    private void readWorldData(final OfflinePlayer owner, final World world, final Path data) {
        if (!Files.exists(data))
            return;

        try(InputStream stream = Files.newInputStream(data)) {
            JsonInstance jsonInstance = JsonReader.read(stream);
            if (jsonInstance == null || !jsonInstance.isObjectType())
                return;

            JsonObject object = jsonInstance.asObject();
            for (String rawX : object.getKeys(false)) {
                JsonInstance xElement = object.getChild(rawX);
                if (!xElement.isObjectType())
                    continue;

                JsonObject xObject = xElement.asObject();
                try {
                    int x = Integer.parseInt(rawX);
                    handleZAxis(owner, world, x, xObject);
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to read file " + data, ex);
        }
    }

    private void handleZAxis(final OfflinePlayer owner, final World world, final int x, final JsonObject xObject) {
        for (String rawZ : xObject.getKeys(false)) {
            JsonInstance zElement = xObject.getChild(rawZ);
            if (!zElement.isObjectType())
                continue;

            JsonObject zObject = zElement.asObject();
            try {
                int z = Integer.parseInt(rawZ);
                if (zObject.hasChild("elevators")) {
                    JsonInstance elevatorsInstance = zObject.getChild("elevators");
                    if (!elevatorsInstance.isObjectType())
                        continue;

                    JsonObject elevators = elevatorsInstance.asObject();
                    handleYAxisElevators(owner, world, x, z, elevators);
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private void handleYAxisElevators(final OfflinePlayer owner, final World world, final int x, final int z, final JsonObject elevators) {
        List<ElevatorBlock> parsedElevators = new ArrayList<>();

        for (String rawY : elevators.getKeys(false)) {
            JsonInstance yElement = elevators.getChild(rawY);
            if (!yElement.isObjectType())
                continue;

            JsonObject yObject = yElement.asObject();
            String name = getString(yObject, "name");
            String disguise = getString(yObject, "disguise");
            Boolean enabled = getBoolean(yObject, "enabled");
            Boolean particles = getBoolean(yObject, "particles");
            Boolean visible = getBoolean(yObject, "visible");
            JsonArray viewers = getArray(yObject, "viewers");

            if (name == null || disguise == null || enabled == null ||
                    particles == null || visible == null || viewers == null) continue;

            Set<UUID> viewersUUIDs = mapViewers(viewers);
            try {
                int y = Integer.parseInt(rawY);

                ClosedBlockSettings settings = new ClosedBlockSettings(name, disguise, enabled, particles, visible, viewersUUIDs);
                ElevatorBlock block = new ElevatorBlock(owner, world, x, y, z, settings, this);

                parsedElevators.add(block);
            } catch (NumberFormatException ignored) {}
        }
        parsedElevators.sort(Comparator.comparingInt(ElevatorBlock::getY));

        ElevatorBlock previous = null;
        for (int i = 0; i < parsedElevators.size(); i++) {
            ElevatorBlock block = parsedElevators.get(i);
            block.setLevel(i);

            if (previous != null)
                previous.setNext(block);

            block.setPrevious(previous);
            previous = block;

            Block blockAt = world.getBlockAt(block.getX(), block.getY(), block.getZ());
            blockAt.setMetadata("closed_type", new FixedMetadataValue(this, "elevator"));
        }

        api.getBlockStorage().addAll(parsedElevators);
    }

    private Set<UUID> mapViewers(final JsonArray array) {
        Set<UUID> set = new LinkedHashSet<>();

        array.forEach((instance) -> {
            if (instance.isNull()) return;
            String raw = instance.asString();
            if (raw == null) return;

            try {
                UUID id = UUID.fromString(raw);
                set.add(id);
            } catch (Throwable ignored) {}
        });

        return set;
    }

    private String getString(final JsonObject object, final String key) {
        if (!object.hasChild(key))
            return null;

        JsonInstance instance = object.getChild(key);
        if (!instance.isNativeType())
            return null;

        JsonNative nat = instance.asNative();
        if (!nat.isString()) return null;

        return nat.getString();
    }

    private Boolean getBoolean(final JsonObject object, final String key) {
        if (!object.hasChild(key))
            return null;

        JsonInstance instance = object.getChild(key);
        if (!instance.isNativeType())
            return null;

        JsonNative nat = instance.asNative();
        if (!nat.isBoolean()) return null;

        return nat.getBoolean();
    }

    private JsonArray getArray(final JsonObject object, final String key) {
        if (!object.hasChild(key))
            return null;

        JsonInstance instance = object.getChild(key);
        if (!instance.isArrayType())
            return null;

        return instance.asArray();
    }

    private void forEachInDirectory(final Path directory, final Consumer<Path> action) {
        try(Stream<Path> files = Files.list(directory)) {
            files.forEach(action);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to read directory " + directory, ex);
        }
    }

    private UUID fromTrimmed(final String raw) {
        try {
            String formattedUUID = raw.replaceFirst(
                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                    "$1-$2-$3-$4-$5"
            );
            return UUID.fromString(formattedUUID);
        } catch (Throwable ex) {
            return null;
        }
    }
}