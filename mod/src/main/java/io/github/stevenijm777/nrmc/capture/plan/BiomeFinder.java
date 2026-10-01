package io.github.stevenijm777.nrmc.capture.plan;

import io.github.stevenijm777.nrmc.capture.Job;
import io.github.stevenijm777.nrmc.capture.NrmcCaptureMod;
import io.github.stevenijm777.nrmc.capture.io.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Finds areas where one biome dominates, to choose capture zones. Samples the world generator's
 * biome source on a grid (no chunks are generated, same mechanism as /locate), then scores every
 * candidate center by the fraction of samples within {@code zoneRadius} that match, and keeps the
 * best non-overlapping zones. Unlike /locate, this ranks by area, not by distance.
 */
public final class BiomeFinder implements Job {
    public record Zone(int rank, int x, int z, double fraction, int distance) {
    }

    static final int STEP = 32;
    private static final int SAMPLES_PER_TICK = 8000;
    private static final int MAX_ZONES = 8;
    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static List<Zone> lastZones = List.of();
    private static ResourceLocation lastBiome;

    private final Path dataRoot;
    private final String worldId;
    private final ResourceLocation biome;
    private final int zoneRadius;
    private final int searchRadius;
    private final int centerX;
    private final int centerZ;
    private final int size;
    private final boolean[] match;

    private int nextRow;
    private int pendingRow;
    private int pendingRows;
    private int sampleY;
    private CompletableFuture<boolean[]> pending;
    private boolean done;

    public BiomeFinder(Path dataRoot, String worldId, ResourceLocation biome, int zoneRadius, int searchRadius,
                       int centerX, int centerZ) {
        this.dataRoot = dataRoot;
        this.worldId = worldId;
        this.biome = biome;
        this.zoneRadius = zoneRadius;
        this.searchRadius = searchRadius;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.size = 2 * (searchRadius / STEP) + 1;
        this.match = new boolean[size * size];
    }

    /** Zones from the last completed search, used by /nrmc goto. */
    public static List<Zone> lastZones() {
        return lastZones;
    }

    /** Biome of the last completed search. */
    public static ResourceLocation lastBiome() {
        return lastBiome;
    }

    @Override
    public String name() {
        return "findbiome " + biome;
    }

    @Override
    public void tick(Minecraft mc) {
        if (done) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            finish("the singleplayer world is no longer running");
            return;
        }
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            boolean[] rows;
            try {
                rows = pending.join();
            } catch (CompletionException | CancellationException e) {
                finish("biome sampling failed: " + e.getMessage());
                return;
            }
            System.arraycopy(rows, 0, match, pendingRow * size, pendingRows * size);
            pending = null;
        }
        if (nextRow >= size) {
            rank();
            return;
        }
        int rows = Math.min(Math.max(1, SAMPLES_PER_TICK / size), size - nextRow);
        int start = nextRow;
        nextRow += rows;
        pendingRow = start;
        pendingRows = rows;
        pending = server.submit(() -> sampleRows(server.overworld(), start, rows));
    }

    /** Runs on the server thread. Samples above sea level so surface biomes are returned, not caves. */
    private boolean[] sampleRows(ServerLevel level, int startRow, int rows) {
        sampleY = level.getSeaLevel() + 16;
        int quartY = sampleY >> 2;
        int origin = searchRadius / STEP;
        boolean[] out = new boolean[rows * size];
        for (int r = 0; r < rows; r++) {
            int z = centerZ + (startRow + r - origin) * STEP;
            for (int c = 0; c < size; c++) {
                int x = centerX + (c - origin) * STEP;
                out[r * size + c] = level.getUncachedNoiseBiome(x >> 2, quartY, z >> 2).is(biome);
            }
        }
        return out;
    }

    private void rank() {
        int window = zoneRadius / STEP;
        int stride = size + 1;
        int[] sat = new int[stride * stride];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                sat[(i + 1) * stride + j + 1] = (match[i * size + j] ? 1 : 0)
                        + sat[i * stride + j + 1] + sat[(i + 1) * stride + j] - sat[i * stride + j];
            }
        }
        int cells = (2 * window + 1) * (2 * window + 1);
        int origin = searchRadius / STEP;
        List<Zone> candidates = new ArrayList<>();
        for (int i = window; i < size - window; i++) {
            for (int j = window; j < size - window; j++) {
                int i0 = i - window;
                int j0 = j - window;
                int i1 = i + window + 1;
                int j1 = j + window + 1;
                int sum = sat[i1 * stride + j1] - sat[i0 * stride + j1] - sat[i1 * stride + j0] + sat[i0 * stride + j0];
                if (sum == 0) {
                    continue;
                }
                int x = centerX + (j - origin) * STEP;
                int z = centerZ + (i - origin) * STEP;
                int distance = (int) Math.round(Math.hypot(x - centerX, z - centerZ));
                candidates.add(new Zone(0, x, z, (double) sum / cells, distance));
            }
        }
        candidates.sort(Comparator.comparingDouble(Zone::fraction).reversed().thenComparingInt(Zone::distance));

        List<Zone> zones = new ArrayList<>();
        double minSeparation = 2.0 * zoneRadius;
        for (Zone candidate : candidates) {
            boolean overlaps = zones.stream().anyMatch(
                    z -> Math.hypot(z.x() - candidate.x(), z.z() - candidate.z()) < minSeparation);
            if (!overlaps) {
                zones.add(new Zone(zones.size() + 1, candidate.x(), candidate.z(), candidate.fraction(), candidate.distance()));
                if (zones.size() == MAX_ZONES) {
                    break;
                }
            }
        }
        lastZones = List.copyOf(zones);
        lastBiome = biome;
        save(zones);

        if (zones.isEmpty()) {
            finish("no " + biome + " found within " + searchRadius + " blocks");
            return;
        }
        NrmcCaptureMod.notify(String.format("%s zones (radius %d) within %d blocks, best first:",
                biome, zoneRadius, searchRadius));
        for (Zone z : zones) {
            NrmcCaptureMod.notify(String.format("#%d  x=%d z=%d  %.0f%% %s  (%d blocks away)",
                    z.rank(), z.x(), z.z(), z.fraction() * 100, biome.getPath(), z.distance()));
        }
        if (zones.get(0).fraction() < 0.6) {
            NrmcCaptureMod.notify("Best zone is under 60%; try a larger search radius or search from elsewhere.");
        }
        NrmcCaptureMod.notify("Teleport with /nrmc goto <n>");
        done = true;
    }

    private void save(List<Zone> zones) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("biome", biome.toString());
        out.put("world_id", worldId);
        out.put("created_at", Instant.now().toString());
        out.put("center", Map.of("x", centerX, "z", centerZ));
        out.put("search_radius", searchRadius);
        out.put("zone_radius", zoneRadius);
        out.put("grid_step", STEP);
        out.put("sample_y", sampleY);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Zone z : zones) {
            list.add(Map.of("rank", z.rank(), "x", z.x(), "z", z.z(), "fraction", z.fraction(), "distance", z.distance()));
        }
        out.put("zones", list);
        Path file = dataRoot.resolve("zones").resolve(
                biome.getPath() + "__" + worldId + "__" + ID_TIME.format(Instant.now()) + ".json");
        try {
            Json.writeAtomic(file, out);
        } catch (IOException e) {
            NrmcCaptureMod.LOG.warn("Could not save zones to {}", file, e);
        }
    }

    private void finish(String message) {
        done = true;
        NrmcCaptureMod.notify("Biome search: " + message);
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void cancel(String reason) {
        if (!done) {
            finish("cancelled: " + reason);
        }
    }

    @Override
    public String status() {
        return String.format("findbiome %s: %d/%d rows sampled", biome, Math.min(nextRow, size), size);
    }
}
