package io.github.stevenijm777.nrmc.capture.config;

import io.github.stevenijm777.nrmc.capture.io.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Capture settings, stored as config/nrmc-capture.json in the game directory.
 * Field names are the JSON keys; keys missing from an older file keep these defaults.
 */
public final class CaptureConfig {
    /** Dataset root. Empty means {@code <gameDir>/nrmc_data}. */
    public String dataRoot = "";

    /** Framebuffer size enforced during sweeps. 0 disables the check. */
    public int targetWidth = 1280;
    public int targetHeight = 720;

    /**
     * Daytime ticks (0-23999). Each location gets one of them at random, or all of them when
     * {@link #allTimesPerPose} is on. Set with /nrmc times.
     */
    public int[] timesOfDay = {1000, 6000, 11000};

    /**
     * Render every location once per entry of {@link #timesOfDay}, as consecutive poses that differ
     * only in the time of day. Off: one random time per location. Toggled with /nrmc alltimes;
     * read when a plan is created and recorded in it.
     */
    public boolean allTimesPerPose = false;

    /** Feet height above the surface, in blocks. The camera sits 1.62 blocks above the feet. */
    public double feetOffsetMin = 0.0;
    public double feetOffsetMax = 6.0;

    /** Pitch in degrees, positive looks down. Drawn from a clipped normal distribution. */
    public double pitchMean = 8.0;
    public double pitchStd = 12.0;
    public double pitchMin = -30.0;
    public double pitchMax = 50.0;

    /**
     * Save the depth buffer (depth.npz) during the vanilla pass. Geometry is identical in every
     * pass, and with shaders off the main render target holds the world depth.
     */
    public boolean captureDepth = true;

    /**
     * Biomes a plan may use, as registry ids (e.g. "minecraft:forest"). Empty accepts any biome.
     * The biome is sampled at the camera position.
     */
    public String[] planBiomes = {};

    /** Maximum accepted poses per biome in one plan. 0 means unlimited. */
    public int maxPerBiome = 0;
    public int maxAttemptsPerPose = 20;
    /**
     * After this many failed poses in a row the sweep pauses to recover: it frees memory, reloads
     * the chunk renderer, waits {@link #recoveryWaitTicks} and retries those poses.
     */
    public int maxConsecutiveFailures = 8;
    /** Recoveries in a row (with no pose captured in between) before the sweep gives up. */
    public int recoveryAttempts = 3;
    public int recoveryWaitTicks = 1200;

    /**
     * Chunk radius around the camera that must be loaded and built before a capture. Must cover
     * the shader's shadow distance (Complementary: 192 blocks = 12 chunks), or distant shadow
     * casters may still be missing when the frame is captured.
     */
    public int readinessRadiusChunks = 12;
    /** Consecutive ticks the terrain must report fully built. */
    public int stableTicks = 10;
    /** Ticks to wait after terrain is ready, to let the shader converge. */
    public int settleTicks = 60;
    /** Rendered frames required during the settle window (guards against a minimized window). */
    public int minSettleFrames = 30;

    public int teleportTimeoutTicks = 100;
    public int chunkTimeoutTicks = 1200;
    public int maxTeleportRetries = 3;
    public int maxPendingWrites = 8;

    public static CaptureConfig loadOrCreate(Path file) throws IOException {
        CaptureConfig config = Files.exists(file) ? Json.read(file, CaptureConfig.class) : null;
        if (config == null) {
            config = new CaptureConfig();
        }
        config.validate();
        // Rewrite so keys added in newer versions show up in the user's file.
        Json.writeAtomic(file, config);
        return config;
    }

    public Path resolveDataRoot(Path gameDir) {
        return dataRoot == null || dataRoot.isBlank() ? gameDir.resolve("nrmc_data") : Path.of(dataRoot);
    }

    public void validate() {
        require(timesOfDay != null && timesOfDay.length > 0, "timesOfDay must not be empty");
        for (int t : timesOfDay) {
            require(t >= 0 && t < 24000, "timesOfDay values must be in [0, 23999]");
        }
        require(targetWidth >= 0 && targetHeight >= 0, "targetWidth/targetHeight must be >= 0");
        require((targetWidth == 0) == (targetHeight == 0), "set both targetWidth and targetHeight, or neither");
        require(feetOffsetMin >= 0 && feetOffsetMax >= feetOffsetMin, "need 0 <= feetOffsetMin <= feetOffsetMax");
        require(pitchStd >= 0, "pitchStd must be >= 0");
        require(pitchMin >= -90 && pitchMax <= 90 && pitchMin < pitchMax, "need -90 <= pitchMin < pitchMax <= 90");
        require(planBiomes != null, "planBiomes must be a list (use [] for any biome)");
        for (String biome : planBiomes) {
            require(biome != null && biome.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"),
                    "planBiomes entries must be ids like minecraft:forest");
        }
        require(maxPerBiome >= 0, "maxPerBiome must be >= 0");
        require(maxAttemptsPerPose >= 1, "maxAttemptsPerPose must be >= 1");
        require(maxConsecutiveFailures >= 1, "maxConsecutiveFailures must be >= 1");
        require(recoveryAttempts >= 0 && recoveryWaitTicks >= 0, "recovery settings must be >= 0");
        require(readinessRadiusChunks >= 0, "readinessRadiusChunks must be >= 0");
        require(stableTicks >= 1 && settleTicks >= 0 && minSettleFrames >= 0, "tick and frame counts must be positive");
        require(teleportTimeoutTicks >= 1 && chunkTimeoutTicks >= 1, "timeouts must be >= 1");
        require(maxTeleportRetries >= 0 && maxPendingWrites >= 1, "invalid retry or queue settings");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("nrmc-capture.json: " + message);
        }
    }
}
