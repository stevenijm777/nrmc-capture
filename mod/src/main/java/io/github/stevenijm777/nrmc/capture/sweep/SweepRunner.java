package io.github.stevenijm777.nrmc.capture.sweep;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.stevenijm777.nrmc.capture.Job;
import io.github.stevenijm777.nrmc.capture.NrmcCaptureMod;
import io.github.stevenijm777.nrmc.capture.config.CaptureConfig;
import io.github.stevenijm777.nrmc.capture.io.DepthFrame;
import io.github.stevenijm777.nrmc.capture.io.FrameWriter;
import io.github.stevenijm777.nrmc.capture.io.Json;
import io.github.stevenijm777.nrmc.capture.plan.CapturePlan;
import io.github.stevenijm777.nrmc.capture.plan.Pose;
import io.github.stevenijm777.nrmc.capture.plan.WorldIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.Level;

/**
 * Replays a plan and saves one screenshot per pose under the given pass name.
 *
 * <p>Per pose: teleport and set time, wait until the client reports the exact pose, wait until
 * the terrain around the camera is loaded and built, wait {@code settleTicks} (and at least
 * {@code minSettleFrames} rendered frames) for the shader to converge, then read the main
 * framebuffer, the same buffer F2 saves. In the vanilla pass the depth buffer of the next rendered
 * frame is saved too (the scene is static, so both frames show the same geometry). Poses whose
 * output already exists are skipped, so an interrupted sweep resumes by running the same command
 * again.
 */
public final class SweepRunner implements Job {
    public static final String VANILLA_PASS = "vanilla";

    private enum Stage { SETUP, RESIZE, NEXT_POSE, AWAIT_POSE, AWAIT_TERRAIN, SETTLE, CAPTURE, AWAIT_DEPTH, RECOVER, FINISHING, DONE }

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int RESIZE_TIMEOUT_TICKS = 60;
    private static final int DEPTH_TIMEOUT_TICKS = 40;

    private final CaptureConfig cfg;
    private final Path dataRoot;
    private final CapturePlan plan;
    private final String pass;
    private final String sweepId;
    private final boolean captureDepth;
    private final int poseTickLimit;
    private final Map<String, Object> manifest = new LinkedHashMap<>();
    private final List<Map<String, Object>> failures = new ArrayList<>();
    private final FrameWriter writer = new FrameWriter();

    private Stage stage = Stage.SETUP;
    private int index;
    private Pose pose;
    private int stageTicks;
    private int poseTicks;
    private int stableTicks;
    private int retries;
    private int consecutiveFailures;
    private int recoveries;
    private int recoveriesTotal;
    private long settleStartFrames;
    private int captured;
    private int skippedExisting;
    private int frameWidth;
    private int frameHeight;
    private boolean started;
    private boolean optionsTouched;
    private boolean previousHideGui;
    private boolean previousPauseOnLostFocus;
    private boolean frameGatingWarned;
    private boolean stallWarned;
    private String endReason;
    private NativeImage heldImage;
    private Map<String, Object> heldMeta;
    private int depthCaptured;
    private int depthMissing;

    public SweepRunner(CaptureConfig cfg, Path dataRoot, CapturePlan plan, String pass) {
        this.cfg = cfg;
        this.dataRoot = dataRoot;
        this.plan = plan;
        this.pass = pass;
        this.sweepId = plan.name + "__" + pass + "__" + ID_TIME.format(Instant.now());
        this.captureDepth = cfg.captureDepth && VANILLA_PASS.equals(pass);
        this.poseTickLimit = cfg.teleportTimeoutTicks * (cfg.maxTeleportRetries + 1)
                + cfg.chunkTimeoutTicks + cfg.settleTicks * 4 + 200;
    }

    @Override
    public String name() {
        return "sweep " + sweepId;
    }

    @Override
    public void tick(Minecraft mc) {
        if (stage == Stage.DONE) {
            return;
        }
        if (stage == Stage.FINISHING) {
            finishWhenFlushed(mc);
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            abort("the world was closed");
            return;
        }
        switch (stage) {
            case SETUP -> setup(mc, player, level);
            case RESIZE -> awaitResize(mc, player);
            case NEXT_POSE -> nextPose(player);
            case AWAIT_POSE -> awaitPose(player, level);
            case AWAIT_TERRAIN -> awaitTerrain(mc, player, level);
            case SETTLE -> settle(mc, player, level);
            case CAPTURE -> capture(mc, player, level);
            case AWAIT_DEPTH -> awaitDepth(mc, player);
            case RECOVER -> recover(mc);
            default -> {
            }
        }
    }

    private void setup(Minecraft mc, LocalPlayer player, ClientLevel level) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            abort("sweeps require a singleplayer world");
            return;
        }
        WorldIdentity world = WorldIdentity.of(server);
        if (!world.worldId().equals(plan.worldId)) {
            abort("plan belongs to world '" + plan.worldId + "' but '" + world.worldId() + "' is open");
            return;
        }
        if (!Level.OVERWORLD.equals(level.dimension())) {
            abort("sweeps run in the overworld only");
            return;
        }
        if (!player.hasPermissions(2)) {
            abort("cheats must be enabled (the sweep uses /tp, /time, /gamerule and /tick)");
            return;
        }

        Path gameDir = NrmcCaptureMod.gameDir();
        EnvironmentSnapshot.IrisState iris = EnvironmentSnapshot.readIris(gameDir);
        boolean expectShaders = !VANILLA_PASS.equals(pass);
        if (iris.enabled() == null) {
            NrmcCaptureMod.notify("Warning: config/iris.properties not readable; shader on/off is not verified");
        } else if (iris.enabled() != expectShaders) {
            abort(expectShaders
                    ? "pass '" + pass + "' expects shaders ON but Iris reports them OFF"
                    : "pass 'vanilla' expects shaders OFF but Iris reports them ON");
            return;
        }
        if (expectShaders && iris.enabled() != null && iris.shaderPack() == null) {
            abort("shaders are on but no shader pack is selected");
            return;
        }

        manifest.put("schema_version", "0.1.0");
        manifest.put("sweep_id", sweepId);
        manifest.put("status", "running");
        manifest.put("plan", plan.name);
        manifest.put("world_id", plan.worldId);
        manifest.put("pass", pass);
        manifest.put("shaders_expected", expectShaders);
        manifest.put("capture_depth", captureDepth);
        manifest.put("started_at", Instant.now().toString());
        manifest.put("environment", EnvironmentSnapshot.environment());
        manifest.put("iris", iris.toMap());
        manifest.put("shader_pack", EnvironmentSnapshot.shaderPack(gameDir, iris));
        manifest.put("client_options", EnvironmentSnapshot.clientOptions(gameDir));
        manifest.put("capture_config", cfg);

        previousHideGui = mc.options.hideGui;
        previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
        optionsTouched = true;
        mc.options.hideGui = true;
        mc.options.pauseOnLostFocus = false;
        WorldFreezer.apply(player);
        started = true;

        if (cfg.targetWidth > 0 && !WindowSize.matches(mc, cfg.targetWidth, cfg.targetHeight)) {
            String problem = WindowSize.request(mc, cfg.targetWidth, cfg.targetHeight);
            if (problem != null) {
                abort(problem);
                return;
            }
            stage = Stage.RESIZE;
            stageTicks = 0;
            return;
        }
        beginPoses(mc);
    }

    private void awaitResize(Minecraft mc, LocalPlayer player) {
        if (WindowSize.matches(mc, cfg.targetWidth, cfg.targetHeight)) {
            beginPoses(mc);
            return;
        }
        if (++stageTicks > RESIZE_TIMEOUT_TICKS) {
            RenderTarget target = mc.getMainRenderTarget();
            abort("window resize to " + cfg.targetWidth + "x" + cfg.targetHeight + " gave "
                    + target.width + "x" + target.height
                    + " (check Windows display scaling, or set targetWidth/targetHeight to 0)");
        }
    }

    private void beginPoses(Minecraft mc) {
        RenderTarget target = mc.getMainRenderTarget();
        frameWidth = target.width;
        frameHeight = target.height;
        manifest.put("framebuffer", Map.of("width", frameWidth, "height", frameHeight));
        try {
            Json.writeAtomic(sweepPath(), manifest);
        } catch (IOException e) {
            abort("cannot write sweep manifest: " + e.getMessage());
            return;
        }
        NrmcCaptureMod.notify("Sweep " + sweepId + " started: " + plan.poses.size() + " poses at "
                + frameWidth + "x" + frameHeight + ". Do not touch mouse or keyboard, do not minimize.");
        stage = Stage.NEXT_POSE;
    }

    private void nextPose(LocalPlayer player) {
        while (index < plan.poses.size() && outputExists(plan.poses.get(index))) {
            skippedExisting++;
            index++;
        }
        if (index >= plan.poses.size()) {
            stage = Stage.FINISHING;
            return;
        }
        pose = plan.poses.get(index);
        retries = 0;
        poseTicks = 0;
        teleport(player);
    }

    private void teleport(LocalPlayer player) {
        WorldFreezer.teleport(player, pose);
        stage = Stage.AWAIT_POSE;
        stageTicks = 0;
    }

    private void retryTeleport(LocalPlayer player, String reason) {
        if (retries >= cfg.maxTeleportRetries) {
            fail(reason);
            return;
        }
        retries++;
        teleport(player);
    }

    private void awaitPose(LocalPlayer player, ClientLevel level) {
        stageTicks++;
        if (++poseTicks > poseTickLimit) {
            fail("pose time budget exhausted");
            return;
        }
        if (Readiness.poseMatches(player, pose) && Readiness.timeMatches(level, pose.timeOfDay)) {
            stage = Stage.AWAIT_TERRAIN;
            stageTicks = 0;
            stableTicks = 0;
        } else if (stageTicks > cfg.teleportTimeoutTicks) {
            retryTeleport(player, "teleport did not land on the requested pose/time");
        }
    }

    private void awaitTerrain(Minecraft mc, LocalPlayer player, ClientLevel level) {
        stageTicks++;
        if (++poseTicks > poseTickLimit) {
            fail("pose time budget exhausted");
            return;
        }
        if (!Readiness.poseMatches(player, pose)) {
            retryTeleport(player, "camera moved while waiting for terrain");
            return;
        }
        boolean ready = Readiness.terrainReady(mc, level, pose, cfg.readinessRadiusChunks);
        stableTicks = ready ? stableTicks + 1 : 0;
        if (stableTicks >= cfg.stableTicks) {
            stage = Stage.SETTLE;
            stageTicks = 0;
            settleStartFrames = RenderHooks.frames();
        } else if (stageTicks > cfg.chunkTimeoutTicks) {
            fail("terrain not ready after " + stageTicks + " ticks");
        }
    }

    private void settle(Minecraft mc, LocalPlayer player, ClientLevel level) {
        stageTicks++;
        if (++poseTicks > poseTickLimit) {
            fail("pose time budget exhausted");
            return;
        }
        if (!Readiness.poseMatches(player, pose)) {
            retryTeleport(player, "camera moved while settling");
            return;
        }
        if (!Readiness.terrainReady(mc, level, pose, cfg.readinessRadiusChunks)) {
            stage = Stage.AWAIT_TERRAIN;
            stageTicks = 0;
            stableTicks = 0;
            return;
        }
        if (stageTicks < cfg.settleTicks) {
            return;
        }
        if (!RenderHooks.seen()) {
            if (!frameGatingWarned) {
                frameGatingWarned = true;
                NrmcCaptureMod.notify("Warning: world render events not observed; settling on ticks only");
            }
            stage = Stage.CAPTURE;
            return;
        }
        long frames = RenderHooks.frames() - settleStartFrames;
        if (frames >= cfg.minSettleFrames) {
            stage = Stage.CAPTURE;
        } else if (!stallWarned && stageTicks > cfg.settleTicks * 2) {
            stallWarned = true;
            NrmcCaptureMod.notify("Rendering looks stalled (" + frames + " frames in " + stageTicks
                    + " ticks). Is the window minimized?");
        }
    }

    private void capture(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (writer.pending() >= cfg.maxPendingWrites) {
            return;
        }
        if (mc.screen != null) {
            // Chat, pause menu or any other screen would be drawn into the frame. Wait, and settle
            // again once it closes so the captured frame is rendered without it.
            stage = Stage.SETTLE;
            stageTicks = 0;
            settleStartFrames = RenderHooks.frames();
            return;
        }
        if (!Readiness.poseMatches(player, pose)) {
            retryTeleport(player, "camera moved before capture");
            return;
        }
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width != frameWidth || target.height != frameHeight) {
            abort("framebuffer changed from " + frameWidth + "x" + frameHeight + " to "
                    + target.width + "x" + target.height + "; keep the window size fixed during a sweep");
            return;
        }
        NativeImage image = Screenshot.takeScreenshot(target);
        Map<String, Object> meta = captureMeta(player, level);
        if (!captureDepth) {
            submit(image, meta, null);
            return;
        }
        heldImage = image;
        heldMeta = meta;
        RenderHooks.requestDepth();
        stage = Stage.AWAIT_DEPTH;
        stageTicks = 0;
    }

    /** The depth buffer is read at the end of the next rendered world frame, before the GUI clears it. */
    private void awaitDepth(Minecraft mc, LocalPlayer player) {
        stageTicks++;
        if (mc.screen != null || !Readiness.poseMatches(player, pose)) {
            RenderHooks.cancelDepth();
            releaseHeldImage();
            retryTeleport(player, mc.screen != null ? "a screen opened while reading depth" : "camera moved while reading depth");
            return;
        }
        DepthFrame depth = RenderHooks.takeDepth();
        if (depth != null && (depth.width() != frameWidth || depth.height() != frameHeight)) {
            heldMeta.put("depth_error", "depth size " + depth.width() + "x" + depth.height() + " does not match the frame");
            depth = null;
        } else if (depth == null) {
            String error = RenderHooks.depthError();
            if (error == null && stageTicks <= DEPTH_TIMEOUT_TICKS) {
                return;
            }
            RenderHooks.cancelDepth();
            heldMeta.put("depth_error", error != null ? error : "no world frame rendered within " + DEPTH_TIMEOUT_TICKS + " ticks");
        }
        if (depth == null) {
            depthMissing++;
        } else {
            depthCaptured++;
        }
        NativeImage image = heldImage;
        Map<String, Object> meta = heldMeta;
        heldImage = null;
        heldMeta = null;
        submit(image, meta, depth);
    }

    private void submit(NativeImage image, Map<String, Object> meta, DepthFrame depth) {
        Path dir = sceneDir(pose);
        writer.submit(image, dir.resolve(pass + ".png"), dir.resolve(pass + ".json"), meta,
                depth, depth == null ? null : dir.resolve("depth.npz"));
        captured++;
        consecutiveFailures = 0;
        recoveries = 0;
        if (captured % 25 == 0) {
            NrmcCaptureMod.notify(status());
        }
        index++;
        stage = Stage.NEXT_POSE;
    }

    private void releaseHeldImage() {
        if (heldImage != null) {
            heldImage.close();
            heldImage = null;
        }
        heldMeta = null;
    }

    private Map<String, Object> captureMeta(LocalPlayer player, ClientLevel level) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", "0.1.0");
        meta.put("scene_id", pose.sceneId);
        meta.put("pass", pass);
        meta.put("sweep_id", sweepId);
        meta.put("plan", plan.name);
        meta.put("pose_index", pose.index);
        meta.put("world_id", plan.worldId);
        meta.put("file", pass + ".png");
        meta.put("sha256", null);
        meta.put("width", frameWidth);
        meta.put("height", frameHeight);
        meta.put("captured_at", Instant.now().toString());
        meta.put("pose_requested", Map.of(
                "x", pose.x, "y", pose.y, "z", pose.z, "yaw", pose.yaw, "pitch", pose.pitch));
        meta.put("pose_actual", Map.of(
                "x", player.getX(), "y", player.getY(), "z", player.getZ(),
                "yaw", player.getYRot(), "pitch", player.getXRot()));
        meta.put("location", pose.location);
        meta.put("time_of_day", pose.timeOfDay);
        meta.put("time_of_day_actual", Math.floorMod(level.getDayTime(), 24000L));
        meta.put("weather", pose.weather);
        meta.put("biome", pose.biome);
        meta.put("wait", Map.of(
                "pose_ticks", poseTicks,
                "teleport_retries", retries,
                "settle_frames", RenderHooks.frames() - settleStartFrames));
        meta.put("camera", cameraMeta());
        return meta;
    }

    /**
     * Matrices of the last rendered frame, column-major. Minecraft renders relative to the camera:
     * world-to-view = view_matrix * translate(-position), clip = projection_matrix * view.
     */
    private static Map<String, Object> cameraMeta() {
        RenderHooks.CameraSnapshot camera = RenderHooks.lastCamera();
        Map<String, Object> out = new LinkedHashMap<>();
        if (camera == null) {
            out.put("available", false);
            return out;
        }
        out.put("available", true);
        out.put("position", Arrays.stream(camera.position()).boxed().toList());
        out.put("view_matrix", floats(camera.view()));
        out.put("projection_matrix", floats(camera.projection()));
        out.put("matrix_layout", "column_major");
        out.put("reversed_depth", false);
        out.put("frame", camera.frame());
        return out;
    }

    private static List<Float> floats(float[] values) {
        List<Float> out = new ArrayList<>(values.length);
        for (float v : values) {
            out.add(v);
        }
        return out;
    }

    private void fail(String reason) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("scene_id", pose.sceneId);
        failure.put("pose_index", pose.index);
        failure.put("reason", reason);
        failures.add(failure);
        NrmcCaptureMod.LOG.warn("Pose {} ({}) skipped: {}", pose.index, pose.sceneId, reason);
        index++;
        stage = Stage.NEXT_POSE;
        if (++consecutiveFailures < cfg.maxConsecutiveFailures) {
            return;
        }
        if (recoveries >= cfg.recoveryAttempts) {
            // Give up on this sweep only: the queue goes on with the next job, and running the
            // same command later resumes the poses that are still missing.
            abort(consecutiveFailures + " poses in a row failed after " + recoveries + " recoveries (last: "
                    + reason + ")");
            return;
        }
        // Retry the failed streak after a pause: they no longer count as failures.
        index -= consecutiveFailures;
        failures.subList(failures.size() - consecutiveFailures, failures.size()).clear();
        recoveries++;
        recoveriesTotal++;
        NrmcCaptureMod.notify(String.format("Sweep %s: %d poses in a row failed (last: %s). Recovery %d/%d: freeing"
                + " memory, reloading chunks and retrying in %d s", sweepId, consecutiveFailures, reason, recoveries,
                cfg.recoveryAttempts, cfg.recoveryWaitTicks / 20));
        consecutiveFailures = 0;
        stage = Stage.RECOVER;
        stageTicks = 0;
    }

    private void recover(Minecraft mc) {
        if (stageTicks == 0) {
            System.gc();
            mc.levelRenderer.allChanged();
            Runtime rt = Runtime.getRuntime();
            NrmcCaptureMod.LOG.warn("Recovery: heap used {} MB of {} MB", (rt.totalMemory() - rt.freeMemory()) >> 20,
                    rt.maxMemory() >> 20);
        }
        if (++stageTicks >= cfg.recoveryWaitTicks) {
            stage = Stage.NEXT_POSE;
        }
    }

    private void abort(String reason) {
        if (stage == Stage.FINISHING || stage == Stage.DONE) {
            return;
        }
        endReason = reason;
        stage = Stage.FINISHING;
        NrmcCaptureMod.notify("Sweep " + sweepId + " stopping: " + reason);
    }

    private void finishWhenFlushed(Minecraft mc) {
        if (writer.pending() > 0) {
            return;
        }
        releaseHeldImage();
        RenderHooks.cancelDepth();
        writer.close();
        if (optionsTouched) {
            mc.options.hideGui = previousHideGui;
            mc.options.pauseOnLostFocus = previousPauseOnLostFocus;
            optionsTouched = false;
        }
        if (started) {
            Map<String, Object> results = new LinkedHashMap<>();
            results.put("captured", captured);
            results.put("skipped_existing", skippedExisting);
            results.put("failed", failures.size());
            results.put("recoveries", recoveriesTotal);
            results.put("depth_captured", depthCaptured);
            results.put("depth_missing", depthMissing);
            results.put("failures", failures);
            results.put("write_errors", writer.errors());
            manifest.put("finished_at", Instant.now().toString());
            manifest.put("status", endReason == null ? "completed" : "aborted");
            manifest.put("end_reason", endReason);
            manifest.put("results", results);
            try {
                Json.writeAtomic(sweepPath(), manifest);
            } catch (IOException e) {
                NrmcCaptureMod.LOG.error("Could not write sweep manifest {}", sweepPath(), e);
            }
        }
        NrmcCaptureMod.notify(String.format("Sweep %s %s: captured %d, skipped (existing) %d, failed %d, write errors %d",
                sweepId, endReason == null ? "completed" : "aborted", captured, skippedExisting,
                failures.size(), writer.errors().size()));
        stage = Stage.DONE;
    }

    @Override
    public boolean isDone() {
        return stage == Stage.DONE;
    }

    @Override
    public void cancel(String reason) {
        abort(reason);
    }

    @Override
    public String status() {
        return String.format("sweep %s: pose %d/%d, captured %d, skipped %d, failed %d, stage %s",
                sweepId, Math.min(index + 1, plan.poses.size()), plan.poses.size(), captured,
                skippedExisting, failures.size(), stage);
    }

    private Path sweepPath() {
        return dataRoot.resolve("sweeps").resolve(sweepId + ".json");
    }

    private Path sceneDir(Pose p) {
        return dataRoot.resolve("samples").resolve(plan.worldId).resolve(p.sceneId);
    }

    private boolean outputExists(Pose p) {
        return Files.exists(sceneDir(p).resolve(pass + ".json"));
    }
}
