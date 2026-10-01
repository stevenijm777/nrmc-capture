package io.github.stevenijm777.nrmc.capture.plan;

import io.github.stevenijm777.nrmc.capture.io.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ordered list of poses for one world. Every sweep replays the same plan, so all passes of a
 * scene share the exact pose and time of day.
 */
public final class CapturePlan {
    public String schemaVersion = "0.1.0";
    public String name;
    public String worldId;
    public String levelName;
    public long worldSeed;
    public String dimension;
    public String createdAt;
    public Params params = new Params();
    public Stats stats = new Stats();
    public List<Pose> poses = new ArrayList<>();

    public static final class Params {
        /** Requested camera locations. */
        public int count;
        /** Every location rendered at every time of day (see CaptureConfig.allTimesPerPose). */
        public boolean allTimesPerPose;
        /** Locations actually accepted. Poses = locations, or locations x times when all-times. */
        public int locations;
        public int radius;
        public int centerX;
        public int centerZ;
        public long seed;
        public int[] timesOfDay;
        public double feetOffsetMin;
        public double feetOffsetMax;
        public double pitchMean;
        public double pitchStd;
        public double pitchMin;
        public double pitchMax;
        public int maxPerBiome;
        /** Allowed biomes; empty means any. */
        public String[] biomes = {};
        public String heightmap = "MOTION_BLOCKING_NO_LEAVES";
    }

    public static final class Stats {
        public int attempts;
        public int rejectedObstructed;
        public int rejectedOutOfWorld;
        public int rejectedBiomeCap;
        public int rejectedBiomeFilter;
        public Map<String, Integer> perBiome = new TreeMap<>();
    }

    public static Path path(Path dataRoot, String name) {
        return dataRoot.resolve("plans").resolve(name + ".json");
    }

    public static CapturePlan load(Path dataRoot, String name) throws IOException {
        Path file = path(dataRoot, name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("plan not found: " + file);
        }
        CapturePlan plan = Json.read(file, CapturePlan.class);
        if (plan == null || plan.poses == null || plan.poses.isEmpty()) {
            throw new IOException("plan has no poses: " + file);
        }
        return plan;
    }

    public void save(Path dataRoot) throws IOException {
        Json.writeAtomic(path(dataRoot, name), this);
    }
}
