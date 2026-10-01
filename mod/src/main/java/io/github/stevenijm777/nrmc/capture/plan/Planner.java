package io.github.stevenijm777.nrmc.capture.plan;

import io.github.stevenijm777.nrmc.capture.Job;
import io.github.stevenijm777.nrmc.capture.NrmcCaptureMod;
import io.github.stevenijm777.nrmc.capture.config.CaptureConfig;
import io.github.stevenijm777.nrmc.capture.util.Ulid;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Builds a plan by drawing candidate poses from a seeded RNG and probing each one on the
 * integrated server (one probe in flight at a time, so the game stays responsive).
 * Every attempt consumes the same random draws whether it is accepted or not, which makes the
 * plan a pure function of (world, seed, parameters).
 */
public final class Planner implements Job {
    static final double EYE_HEIGHT = 1.62;

    private record Candidate(int x, int z, double feetOffset, float yaw, float pitch, int timeOfDay) {
    }

    private record Probe(boolean inWorld, int surfaceY, double feetY, boolean clear, String biome) {
    }

    private final Path dataRoot;
    private final CapturePlan plan = new CapturePlan();
    private final Random poseRng;
    private final Random idRng;
    private final long createdAtMillis = System.currentTimeMillis();
    private final int maxAttempts;
    private final int maxPerBiome;
    private final Set<String> allowedBiomes;

    private int locations;
    private CompletableFuture<Probe> pending;
    private Candidate pendingCandidate;
    private boolean done;

    public Planner(CaptureConfig cfg, Path dataRoot, WorldIdentity world, String name,
                   int count, int radius, int centerX, int centerZ, long seed) {
        this(cfg, dataRoot, world, name, count, radius, centerX, centerZ, seed, cfg.planBiomes);
    }

    /** {@code biomes} overrides {@code cfg.planBiomes} (used by /nrmc planbiome). */
    public Planner(CaptureConfig cfg, Path dataRoot, WorldIdentity world, String name,
                   int count, int radius, int centerX, int centerZ, long seed, String[] biomes) {
        this.dataRoot = dataRoot;
        this.poseRng = new Random(seed);
        this.idRng = new Random(seed ^ 0x5DEECE66DL);
        this.maxAttempts = count * cfg.maxAttemptsPerPose;
        this.maxPerBiome = cfg.maxPerBiome;
        this.allowedBiomes = Set.of(biomes);

        plan.name = name;
        plan.worldId = world.worldId();
        plan.levelName = world.levelName();
        plan.worldSeed = world.seed();
        plan.dimension = Level.OVERWORLD.location().toString();
        plan.createdAt = Instant.ofEpochMilli(createdAtMillis).toString();

        CapturePlan.Params p = plan.params;
        p.count = count;
        p.radius = radius;
        p.centerX = centerX;
        p.centerZ = centerZ;
        p.seed = seed;
        p.timesOfDay = cfg.timesOfDay.clone();
        p.allTimesPerPose = cfg.allTimesPerPose;
        p.feetOffsetMin = cfg.feetOffsetMin;
        p.feetOffsetMax = cfg.feetOffsetMax;
        p.pitchMean = cfg.pitchMean;
        p.pitchStd = cfg.pitchStd;
        p.pitchMin = cfg.pitchMin;
        p.pitchMax = cfg.pitchMax;
        p.maxPerBiome = cfg.maxPerBiome;
        p.biomes = biomes.clone();
    }

    @Override
    public String name() {
        return "plan " + plan.name;
    }

    @Override
    public void tick(Minecraft mc) {
        if (done) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "the singleplayer world is no longer running");
            return;
        }
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            Probe probe;
            try {
                probe = pending.join();
            } catch (CompletionException | CancellationException e) {
                finish(false, "probe failed: " + e.getMessage());
                return;
            }
            accept(pendingCandidate, probe);
            pending = null;
            pendingCandidate = null;
        }
        if (locations >= plan.params.count) {
            finish(true, null);
            return;
        }
        if (plan.stats.attempts >= maxAttempts) {
            finish(true, "attempt budget exhausted before reaching the requested count");
            return;
        }
        Candidate candidate = draw();
        plan.stats.attempts++;
        pendingCandidate = candidate;
        pending = server.submit(() -> probe(server.overworld(), candidate));
    }

    private Candidate draw() {
        CapturePlan.Params p = plan.params;
        int x = p.centerX + poseRng.nextInt(2 * p.radius + 1) - p.radius;
        int z = p.centerZ + poseRng.nextInt(2 * p.radius + 1) - p.radius;
        double feetOffset = p.feetOffsetMin + poseRng.nextDouble() * (p.feetOffsetMax - p.feetOffsetMin);
        float yaw = (float) (poseRng.nextDouble() * 360.0 - 180.0);
        float pitch = (float) Mth.clamp(p.pitchMean + poseRng.nextGaussian() * p.pitchStd, p.pitchMin, p.pitchMax);
        // Drawn in both modes, so a seed gives the same locations with and without all-times.
        int timeOfDay = p.timesOfDay[poseRng.nextInt(p.timesOfDay.length)];
        return new Candidate(x, z, feetOffset, yaw, pitch, timeOfDay);
    }

    /** Runs on the server thread. Loads (and if needed generates) the chunk under the candidate. */
    private static Probe probe(ServerLevel level, Candidate c) {
        level.getChunk(c.x() >> 4, c.z() >> 4);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.x(), c.z());
        double feetY = round(surfaceY + c.feetOffset(), 1e4);
        double eyeY = feetY + EYE_HEIGHT;
        if (surfaceY <= level.getMinBuildHeight() || eyeY >= level.getMaxBuildHeight() - 1) {
            return new Probe(false, surfaceY, feetY, false, "unknown");
        }
        BlockPos feet = BlockPos.containing(c.x() + 0.5, feetY, c.z() + 0.5);
        BlockPos eye = BlockPos.containing(c.x() + 0.5, eyeY, c.z() + 0.5);
        boolean clear = level.getBlockState(feet).isAir() && level.getBlockState(eye).isAir();
        String biome = level.getBiome(eye).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
        return new Probe(true, surfaceY, feetY, clear, biome);
    }

    private void accept(Candidate c, Probe probe) {
        CapturePlan.Stats stats = plan.stats;
        if (!probe.inWorld()) {
            stats.rejectedOutOfWorld++;
            return;
        }
        if (!probe.clear()) {
            stats.rejectedObstructed++;
            return;
        }
        if (!allowedBiomes.isEmpty() && !allowedBiomes.contains(probe.biome())) {
            stats.rejectedBiomeFilter++;
            return;
        }
        int inBiome = stats.perBiome.getOrDefault(probe.biome(), 0);
        if (maxPerBiome > 0 && inBiome >= maxPerBiome) {
            stats.rejectedBiomeCap++;
            return;
        }
        stats.perBiome.put(probe.biome(), inBiome + 1);

        int location = locations++;
        int[] times = plan.params.allTimesPerPose ? plan.params.timesOfDay : new int[] {c.timeOfDay()};
        for (int timeOfDay : times) {
            Pose pose = new Pose();
            pose.index = plan.poses.size();
            pose.sceneId = Ulid.of(createdAtMillis + pose.index, idRng);
            pose.location = location;
            pose.x = c.x() + 0.5;
            pose.y = probe.feetY();
            pose.z = c.z() + 0.5;
            // Rounded to the precision used in the /tp command, so the landing check compares equal values.
            pose.yaw = (float) round(c.yaw(), 1e3);
            pose.pitch = (float) round(c.pitch(), 1e3);
            pose.timeOfDay = timeOfDay;
            pose.biome = probe.biome();
            pose.surfaceY = probe.surfaceY();
            plan.poses.add(pose);
        }

        if (locations % 25 == 0) {
            NrmcCaptureMod.notify(status());
        }
    }

    private void finish(boolean save, String note) {
        done = true;
        if (!save || plan.poses.isEmpty()) {
            NrmcCaptureMod.notify("Plan " + plan.name + " not saved" + (note == null ? "" : ": " + note));
            return;
        }
        plan.params.locations = locations;
        try {
            plan.save(dataRoot);
        } catch (IOException e) {
            NrmcCaptureMod.LOG.error("Could not save plan {}", plan.name, e);
            NrmcCaptureMod.notify("Plan " + plan.name + " could not be saved: " + e.getMessage());
            return;
        }
        CapturePlan.Stats s = plan.stats;
        NrmcCaptureMod.notify(String.format(
                "Plan %s saved: %d poses (%d locations x %d times) from %d attempts (obstructed %d, out of world %d, biome filter %d, biome cap %d), biomes %s%s",
                plan.name, plan.poses.size(), locations,
                plan.params.allTimesPerPose ? plan.params.timesOfDay.length : 1, s.attempts, s.rejectedObstructed, s.rejectedOutOfWorld,
                s.rejectedBiomeFilter, s.rejectedBiomeCap, s.perBiome, note == null ? "" : ". Note: " + note));
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void cancel(String reason) {
        if (!done) {
            // A probe already submitted to the server finishes on its own; its result is ignored.
            finish(false, reason);
        }
    }

    @Override
    public String status() {
        return String.format("plan %s: %d/%d locations, %d poses, %d attempts",
                plan.name, locations, plan.params.count, plan.poses.size(), plan.stats.attempts);
    }

    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }
}
