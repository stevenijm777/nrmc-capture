package io.github.stevenijm777.nrmc.capture.sweep;

import io.github.stevenijm777.nrmc.capture.plan.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Checks that decide when a pose can be captured. */
public final class Readiness {
    private static final double POSITION_TOLERANCE = 1e-3;
    private static final float ANGLE_TOLERANCE = 0.05f;
    private static final long TIME_TOLERANCE = 2;

    private Readiness() {
    }

    public static boolean poseMatches(LocalPlayer player, Pose pose) {
        return Math.abs(player.getX() - pose.x) < POSITION_TOLERANCE
                && Math.abs(player.getY() - pose.y) < POSITION_TOLERANCE
                && Math.abs(player.getZ() - pose.z) < POSITION_TOLERANCE
                && Math.abs(Mth.wrapDegrees(player.getYRot() - pose.yaw)) < ANGLE_TOLERANCE
                && Math.abs(player.getXRot() - pose.pitch) < ANGLE_TOLERANCE;
    }

    public static boolean timeMatches(ClientLevel level, int timeOfDay) {
        return Math.abs(Math.floorMod(level.getDayTime(), 24000L) - timeOfDay) <= TIME_TOLERANCE;
    }

    /**
     * All chunks within {@code radius} of the camera (a disc, like the shadow distance and like the
     * server's chunk tracking since 1.20.2) are loaded and the section builder is idle. A square
     * would ask for corner chunks the server never sends once the radius nears the render
     * distance. Sodium overrides the vanilla "all sections rendered" check (verified in game).
     */
    public static boolean terrainReady(Minecraft mc, ClientLevel level, Pose pose, int radius) {
        int r = Math.max(0, Math.min(radius, mc.options.renderDistance().get() - 1));
        int cx = Mth.floor(pose.x) >> 4;
        int cz = Mth.floor(pose.z) >> 4;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                if (!level.getChunkSource().hasChunk(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return mc.levelRenderer.hasRenderedAllSections();
    }
}
