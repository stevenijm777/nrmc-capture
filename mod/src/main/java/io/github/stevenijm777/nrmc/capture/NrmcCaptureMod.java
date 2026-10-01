package io.github.stevenijm777.nrmc.capture;

import io.github.stevenijm777.nrmc.capture.command.NrmcCommand;
import io.github.stevenijm777.nrmc.capture.config.CaptureConfig;
import io.github.stevenijm777.nrmc.capture.io.Json;
import io.github.stevenijm777.nrmc.capture.sweep.RenderHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NrmcCaptureMod implements ClientModInitializer {
    public static final String MOD_ID = "nrmc-capture";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private static CaptureConfig config = new CaptureConfig();
    private static Job current;
    /** Jobs to start one after another when the current one ends (/nrmc sweepmany). */
    private static final Deque<Supplier<Job>> queue = new ArrayDeque<>();
    private static int consecutiveErrors;

    @Override
    public void onInitializeClient() {
        try {
            reloadConfig();
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not load {}, using defaults", configPath(), e);
        }
        RenderHooks.register();
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> NrmcCommand.register(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(NrmcCaptureMod::onEndTick);
    }

    private static void onEndTick(Minecraft mc) {
        Job job = current;
        if (job == null) {
            startNextQueued();
            return;
        }
        try {
            job.tick(mc);
            consecutiveErrors = 0;
        } catch (RuntimeException e) {
            LOG.error("Job {} failed", job.name(), e);
            if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                notify("Job " + job.name() + " dropped after repeated errors: " + e);
                current = null;
                return;
            }
            job.cancel("internal error: " + e);
        }
        if (job.isDone()) {
            current = null;
        }
    }

    private static void startNextQueued() {
        Supplier<Job> next = queue.poll();
        if (next == null) {
            return;
        }
        try {
            start(next.get());
            notify("Queue: started " + current.name() + " (" + queue.size() + " more after it)");
        } catch (RuntimeException e) {
            LOG.error("Queued job could not start", e);
            notify("Queue: a job could not start: " + e.getMessage());
        }
    }

    /** Runs the jobs in order, each when the previous one has ended (completed, failed or aborted). */
    public static void enqueue(List<Supplier<Job>> jobs) {
        queue.addAll(jobs);
    }

    public static int queued() {
        return queue.size();
    }

    /** Drops the jobs that have not started yet. Returns how many were dropped. */
    public static int clearQueue() {
        int n = queue.size();
        queue.clear();
        return n;
    }

    public static Job currentJob() {
        return current;
    }

    public static void start(Job job) {
        if (current != null) {
            throw new IllegalStateException("job already running: " + current.name());
        }
        current = job;
        consecutiveErrors = 0;
    }

    public static CaptureConfig config() {
        return config;
    }

    public static void reloadConfig() throws IOException {
        config = CaptureConfig.loadOrCreate(configPath());
    }

    /** Validates and writes the in-memory config, so changes made by commands survive restarts. */
    public static void saveConfig() throws IOException {
        config.validate();
        Json.writeAtomic(configPath(), config);
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("nrmc-capture.json");
    }

    public static Path dataRoot() {
        return config.resolveDataRoot(gameDir());
    }

    /** Logs and echoes to chat. Chat is hidden during sweeps but kept in history. */
    public static void notify(String message) {
        LOG.info(message);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[nrmc] " + message), false);
        }
    }
}
