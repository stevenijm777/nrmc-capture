package io.github.stevenijm777.nrmc.capture.plan;

import java.nio.file.Path;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/** Identifies the open singleplayer world by its save folder name. */
public record WorldIdentity(String worldId, String levelName, long seed, Path worldDir) {

    public static WorldIdentity of(IntegratedServer server) {
        Path dir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String folder = dir.getFileName().toString();
        return new WorldIdentity(
                folder.replaceAll("[^A-Za-z0-9_.-]", "_"),
                server.getWorldData().getLevelName(),
                server.overworld().getSeed(),
                dir);
    }
}
