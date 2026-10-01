package io.github.stevenijm777.nrmc.capture.sweep;

import io.github.stevenijm777.nrmc.capture.plan.Pose;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;

/**
 * Freezes the server-side simulation through regular commands (requires cheats).
 * This does not freeze the client render clock, so shader effects driven by frameTimeCounter
 * keep animating; the shader options must turn them off.
 */
public final class WorldFreezer {
    private static final List<String> SETUP_COMMANDS = List.of(
            "gamemode spectator",
            "gamerule sendCommandFeedback false",
            "gamerule doDaylightCycle false",
            "gamerule doWeatherCycle false",
            "gamerule randomTickSpeed 0",
            "gamerule doMobSpawning false",
            "gamerule doPatrolSpawning false",
            "gamerule doTraderSpawning false",
            "gamerule doInsomnia false",
            "gamerule doFireTick false",
            "weather clear",
            "tick freeze");

    private WorldFreezer() {
    }

    public static void apply(LocalPlayer player) {
        for (String command : SETUP_COMMANDS) {
            player.connection.sendCommand(command);
        }
    }

    public static void teleport(LocalPlayer player, Pose pose) {
        player.connection.sendCommand("time set " + pose.timeOfDay);
        // Locale.ROOT: the decimal separator must be a dot regardless of the system locale.
        player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.5f %.5f %.5f %.3f %.3f",
                pose.x, pose.y, pose.z, pose.yaw, pose.pitch));
    }
}
