package io.github.stevenijm777.nrmc.capture.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.stevenijm777.nrmc.capture.Job;
import io.github.stevenijm777.nrmc.capture.NrmcCaptureMod;
import io.github.stevenijm777.nrmc.capture.config.CaptureConfig;
import io.github.stevenijm777.nrmc.capture.plan.BiomeFinder;
import io.github.stevenijm777.nrmc.capture.plan.CapturePlan;
import io.github.stevenijm777.nrmc.capture.plan.Planner;
import io.github.stevenijm777.nrmc.capture.plan.WorldIdentity;
import io.github.stevenijm777.nrmc.capture.sweep.ShaderToggle;
import io.github.stevenijm777.nrmc.capture.sweep.SweepRunner;
import io.github.stevenijm777.nrmc.capture.sweep.WindowSize;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Client commands:
 * <pre>
 * /nrmc plan &lt;name&gt; &lt;count&gt; &lt;radius&gt; [seed]   count locations around the player's position
 * /nrmc alltimes [on|off]                       every location at every time of day, or one random time
 * /nrmc times [t1 t2 ...]                       show or set the times of day (ticks)
 * /nrmc sweep &lt;plan&gt; &lt;pass&gt;                    capture every pose of a plan
 * /nrmc sweepmany &lt;pass&gt; &lt;plan&gt; [plan ...]      the same pass over several plans, one after another
 * /nrmc planbiome &lt;biome&gt; &lt;train&gt; &lt;test&gt;       find zones and plan &lt;biome&gt;_train / &lt;biome&gt;_test (queued)
 * /nrmc sweepboth &lt;plan&gt; [plan ...]             per plan: shaders off, vanilla, shaders on, complementary (queued)
 * /nrmc findbiome &lt;biome&gt; [zoneRadius] [searchRadius]  zones where a biome dominates
 * /nrmc goto &lt;n&gt;                                teleport above zone n of the last search
 * /nrmc window [width height]                   resize the window (defaults to the config target)
 * /nrmc status | stop | reload
 * </pre>
 */
public final class NrmcCommand {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final int DEFAULT_ZONE_RADIUS = 384;
    private static final int DEFAULT_SEARCH_RADIUS = 4000;
    private static final int GOTO_HEIGHT_ABOVE_SURFACE = 30;

    private NrmcCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("nrmc")
                .executes(NrmcCommand::help)
                .then(literal("status").executes(NrmcCommand::status))
                .then(literal("stop").executes(NrmcCommand::stop))
                .then(literal("reload").executes(NrmcCommand::reload))
                .then(literal("window")
                        .executes(ctx -> window(ctx, false))
                        .then(argument("width", IntegerArgumentType.integer(64, 8192))
                                .then(argument("height", IntegerArgumentType.integer(64, 8192))
                                        .executes(ctx -> window(ctx, true)))))
                .then(literal("alltimes")
                        .executes(NrmcCommand::allTimesStatus)
                        .then(literal("on").executes(ctx -> setAllTimes(ctx, true)))
                        .then(literal("off").executes(ctx -> setAllTimes(ctx, false))))
                .then(literal("times")
                        .executes(NrmcCommand::timesStatus)
                        .then(argument("ticks", StringArgumentType.greedyString())
                                .executes(NrmcCommand::setTimes)))
                .then(literal("findbiome")
                        .then(argument("biome", StringArgumentType.word())
                                .executes(ctx -> findBiome(ctx, DEFAULT_ZONE_RADIUS, DEFAULT_SEARCH_RADIUS))
                                .then(argument("zoneRadius", IntegerArgumentType.integer(64, 4096))
                                        .executes(ctx -> findBiome(ctx,
                                                IntegerArgumentType.getInteger(ctx, "zoneRadius"), DEFAULT_SEARCH_RADIUS))
                                        .then(argument("searchRadius", IntegerArgumentType.integer(256, 20_000))
                                                .executes(ctx -> findBiome(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "zoneRadius"),
                                                        IntegerArgumentType.getInteger(ctx, "searchRadius")))))))
                .then(literal("goto")
                        .then(argument("zone", IntegerArgumentType.integer(1, 64))
                                .executes(NrmcCommand::gotoZone)))
                .then(literal("plan")
                        .then(argument("name", StringArgumentType.word())
                                .then(argument("count", IntegerArgumentType.integer(1, 100_000))
                                        .then(argument("radius", IntegerArgumentType.integer(16, 1_000_000))
                                                .executes(ctx -> plan(ctx, false))
                                                .then(argument("seed", LongArgumentType.longArg())
                                                        .executes(ctx -> plan(ctx, true)))))))
                .then(literal("sweepmany")
                        .then(argument("pass", StringArgumentType.word())
                                .then(argument("plans", StringArgumentType.greedyString())
                                        .executes(NrmcCommand::sweepMany))))
                .then(literal("planbiome")
                        .then(argument("biome", StringArgumentType.word())
                                .then(argument("train", IntegerArgumentType.integer(1, 100_000))
                                        .then(argument("test", IntegerArgumentType.integer(0, 100_000))
                                                .executes(NrmcCommand::planBiome)))))
                .then(literal("sweepboth")
                        .then(argument("plans", StringArgumentType.greedyString())
                                .executes(NrmcCommand::sweepBoth)))
                .then(literal("sweep")
                        .then(argument("plan", StringArgumentType.word())
                                .then(argument("pass", StringArgumentType.word())
                                        .executes(NrmcCommand::sweep)))));
    }

    private static int help(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        source.sendFeedback(Component.literal("/nrmc plan <name> <count> <radius> [seed]   (count = locations)"));
        source.sendFeedback(Component.literal("/nrmc alltimes [on|off]   every location at every time of day"));
        source.sendFeedback(Component.literal("/nrmc times [t1 t2 ...]   e.g. /nrmc times 0 1000 3000 6000 9000 11000 12000"));
        source.sendFeedback(Component.literal("/nrmc sweep <plan> <pass>   (pass 'vanilla' = shaders off)"));
        source.sendFeedback(Component.literal("/nrmc sweepmany <pass> <plan1> <plan2> ...   plans one after another"));
        source.sendFeedback(Component.literal("/nrmc planbiome <biome> <trainLocations> <testLocations>   (queued)"));
        source.sendFeedback(Component.literal("/nrmc sweepboth <plan1> <plan2> ...   vanilla + complementary, switching shaders (queued)"));
        source.sendFeedback(Component.literal("/nrmc findbiome <biome> [zoneRadius] [searchRadius]   e.g. forest"));
        source.sendFeedback(Component.literal("/nrmc goto <n>"));
        source.sendFeedback(Component.literal("/nrmc window [width height]"));
        source.sendFeedback(Component.literal("/nrmc status | stop | reload"));
        source.sendFeedback(Component.literal("Data root: " + NrmcCaptureMod.dataRoot()));
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        Job job = NrmcCaptureMod.currentJob();
        String queued = NrmcCaptureMod.queued() > 0 ? " (+" + NrmcCaptureMod.queued() + " queued)" : "";
        ctx.getSource().sendFeedback(Component.literal((job == null ? "idle" : job.status()) + queued));
        return 1;
    }

    private static int stop(CommandContext<FabricClientCommandSource> ctx) {
        Job job = NrmcCaptureMod.currentJob();
        int dropped = NrmcCaptureMod.clearQueue();
        if (job == null && dropped == 0) {
            return error(ctx, "nothing is running");
        }
        if (job != null) {
            job.cancel("stopped by user");
        }
        if (dropped > 0) {
            ctx.getSource().sendFeedback(Component.literal("Dropped " + dropped + " queued sweep(s)"));
        }
        return 1;
    }

    private static int reload(CommandContext<FabricClientCommandSource> ctx) {
        if (NrmcCaptureMod.currentJob() != null) {
            return error(ctx, "cannot reload while a job is running");
        }
        try {
            NrmcCaptureMod.reloadConfig();
        } catch (IOException | RuntimeException e) {
            return error(ctx, "config not reloaded: " + e.getMessage());
        }
        ctx.getSource().sendFeedback(Component.literal("Reloaded " + NrmcCaptureMod.configPath()));
        return 1;
    }

    private static String timesText(CaptureConfig cfg) {
        return Arrays.toString(cfg.timesOfDay);
    }

    private static int allTimesStatus(CommandContext<FabricClientCommandSource> ctx) {
        CaptureConfig cfg = NrmcCaptureMod.config();
        ctx.getSource().sendFeedback(Component.literal(cfg.allTimesPerPose
                ? "alltimes is ON: each location is captured at " + timesText(cfg)
                        + " (" + cfg.timesOfDay.length + " poses per location)"
                : "alltimes is OFF: each location gets one random time from " + timesText(cfg)));
        return 1;
    }

    private static int setAllTimes(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        if (NrmcCaptureMod.currentJob() instanceof Planner) {
            return error(ctx, "wait for the running plan to finish");
        }
        CaptureConfig cfg = NrmcCaptureMod.config();
        boolean previous = cfg.allTimesPerPose;
        cfg.allTimesPerPose = on;
        try {
            NrmcCaptureMod.saveConfig();
        } catch (IOException | RuntimeException e) {
            cfg.allTimesPerPose = previous;
            return error(ctx, "not saved: " + e.getMessage());
        }
        allTimesStatus(ctx);
        ctx.getSource().sendFeedback(Component.literal("Applies to plans created from now on."));
        return 1;
    }

    private static int timesStatus(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal("timesOfDay: " + timesText(NrmcCaptureMod.config())
                + "   (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight)"));
        return 1;
    }

    private static int setTimes(CommandContext<FabricClientCommandSource> ctx) {
        if (NrmcCaptureMod.currentJob() instanceof Planner) {
            return error(ctx, "wait for the running plan to finish");
        }
        LinkedHashSet<Integer> ticks = new LinkedHashSet<>();
        for (String token : StringArgumentType.getString(ctx, "ticks").split("[\\s,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                ticks.add(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                return error(ctx, "not a tick value: '" + token + "'");
            }
        }
        if (ticks.isEmpty()) {
            return error(ctx, "give at least one time, e.g. /nrmc times 1000 6000 11000");
        }
        CaptureConfig cfg = NrmcCaptureMod.config();
        int[] previous = cfg.timesOfDay;
        cfg.timesOfDay = ticks.stream().mapToInt(Integer::intValue).toArray();
        try {
            NrmcCaptureMod.saveConfig();
        } catch (IOException | RuntimeException e) {
            cfg.timesOfDay = previous;
            return error(ctx, "not saved: " + e.getMessage());
        }
        timesStatus(ctx);
        return 1;
    }

    private static int window(CommandContext<FabricClientCommandSource> ctx, boolean explicit) {
        CaptureConfig cfg = NrmcCaptureMod.config();
        int width = explicit ? IntegerArgumentType.getInteger(ctx, "width") : cfg.targetWidth;
        int height = explicit ? IntegerArgumentType.getInteger(ctx, "height") : cfg.targetHeight;
        if (width <= 0 || height <= 0) {
            return error(ctx, "no target size configured; pass width and height");
        }
        String problem = WindowSize.request(ctx.getSource().getClient(), width, height);
        if (problem != null) {
            return error(ctx, problem);
        }
        ctx.getSource().sendFeedback(Component.literal(
                "Requested " + width + "x" + height + ". Check with /nrmc window again or F3."));
        return 1;
    }

    private static int plan(CommandContext<FabricClientCommandSource> ctx, boolean hasSeed) {
        if (NrmcCaptureMod.currentJob() != null) {
            return error(ctx, "a job is already running: " + NrmcCaptureMod.currentJob().status());
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (!NAME.matcher(name).matches()) {
            return error(ctx, "plan names use lowercase letters, digits and _ only");
        }
        Minecraft mc = ctx.getSource().getClient();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null) {
            return error(ctx, "plans require an open singleplayer world");
        }
        if (!Level.OVERWORLD.equals(mc.level.dimension())) {
            return error(ctx, "plans are generated in the overworld only");
        }
        Path dataRoot = NrmcCaptureMod.dataRoot();
        if (Files.exists(CapturePlan.path(dataRoot, name))) {
            return error(ctx, "plan '" + name + "' already exists in " + dataRoot.resolve("plans"));
        }
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        long seed = hasSeed ? LongArgumentType.getLong(ctx, "seed") : name.hashCode();
        BlockPos center = mc.player.blockPosition();

        CaptureConfig cfg = NrmcCaptureMod.config();
        NrmcCaptureMod.start(new Planner(cfg, dataRoot, WorldIdentity.of(server),
                name, count, radius, center.getX(), center.getZ(), seed));
        String[] biomes = cfg.planBiomes;
        String times = cfg.allTimesPerPose
                ? String.format("each at %s = %d poses", timesText(cfg), count * cfg.timesOfDay.length)
                : "one random time each from " + timesText(cfg);
        ctx.getSource().sendFeedback(Component.literal(String.format(
                "Planning '%s': %d locations within %d blocks of (%d, %d), %s, seed %d, biomes %s",
                name, count, radius, center.getX(), center.getZ(), times, seed,
                biomes.length == 0 ? "any" : String.join(", ", biomes))));
        return 1;
    }

    private static int sweep(CommandContext<FabricClientCommandSource> ctx) {
        if (NrmcCaptureMod.currentJob() != null) {
            return error(ctx, "a job is already running: " + NrmcCaptureMod.currentJob().status());
        }
        String planName = StringArgumentType.getString(ctx, "plan");
        String pass = StringArgumentType.getString(ctx, "pass");
        if (!NAME.matcher(planName).matches() || !NAME.matcher(pass).matches()) {
            return error(ctx, "plan and pass names use lowercase letters, digits and _ only");
        }
        Path dataRoot = NrmcCaptureMod.dataRoot();
        CapturePlan plan;
        try {
            plan = CapturePlan.load(dataRoot, planName);
        } catch (IOException | RuntimeException e) {
            return error(ctx, "cannot load plan: " + e.getMessage());
        }
        NrmcCaptureMod.start(new SweepRunner(NrmcCaptureMod.config(), dataRoot, plan, pass));
        ctx.getSource().sendFeedback(Component.literal(
                "Starting sweep of '" + planName + "' as pass '" + pass + "' (" + plan.poses.size() + " poses)"));
        return 1;
    }

    /** Loads every plan first, so a typo fails now and not hours later. */
    private static int sweepMany(CommandContext<FabricClientCommandSource> ctx) {
        if (NrmcCaptureMod.currentJob() != null || NrmcCaptureMod.queued() > 0) {
            return error(ctx, "a job is already running or queued");
        }
        String pass = StringArgumentType.getString(ctx, "pass");
        if (!NAME.matcher(pass).matches()) {
            return error(ctx, "pass names use lowercase letters, digits and _ only");
        }
        Path dataRoot = NrmcCaptureMod.dataRoot();
        List<CapturePlan> plans = new java.util.ArrayList<>();
        int poses = 0;
        for (String name : StringArgumentType.getString(ctx, "plans").trim().split("[\\s,]+")) {
            if (!NAME.matcher(name).matches()) {
                return error(ctx, "bad plan name '" + name + "'");
            }
            try {
                CapturePlan plan = CapturePlan.load(dataRoot, name);
                plans.add(plan);
                poses += plan.poses.size();
            } catch (IOException | RuntimeException e) {
                return error(ctx, "cannot load plan '" + name + "': " + e.getMessage());
            }
        }
        List<java.util.function.Supplier<Job>> jobs = new java.util.ArrayList<>();
        for (CapturePlan plan : plans) {
            jobs.add(() -> new SweepRunner(NrmcCaptureMod.config(), dataRoot, plan, pass));
        }
        NrmcCaptureMod.enqueue(jobs);
        ctx.getSource().sendFeedback(Component.literal(String.format(
                "Queued pass '%s' over %d plans (%d poses): %s. /nrmc stop cancels the rest.",
                pass, plans.size(), poses, plans.stream().map(p -> p.name).toList())));
        return 1;
    }

    private static final int PLAN_ZONE_RADIUS = 384;
    private static final int MIN_TRAIN_TEST_SEPARATION = 1500;

    /**
     * Queues: a biome search around the player, then a train plan at the best zone and a test plan
     * at the best zone at least MIN_TRAIN_TEST_SEPARATION blocks away, so train and test do not share terrain. Plans are named
     * {@code <biome>_train} and {@code <biome>_test} and accept only that biome.
     */
    private static int planBiome(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = ctx.getSource().getClient();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null) {
            return error(ctx, "requires an open singleplayer world");
        }
        if (!Level.OVERWORLD.equals(mc.level.dimension())) {
            return error(ctx, "runs in the overworld only");
        }
        String raw = StringArgumentType.getString(ctx, "biome");
        ResourceLocation biome = raw.contains(":") ? ResourceLocation.tryParse(raw) : ResourceLocation.withDefaultNamespace(raw);
        if (biome == null || !server.registryAccess().registryOrThrow(Registries.BIOME).containsKey(biome)) {
            return error(ctx, "unknown biome '" + raw + "'");
        }
        String base = biome.getPath().replace('/', '_').replace('.', '_');
        String trainName = base + "_train";
        String testName = base + "_test";
        Path dataRoot = NrmcCaptureMod.dataRoot();
        for (String n : List.of(trainName, testName)) {
            if (!NAME.matcher(n).matches()) {
                return error(ctx, "cannot derive a plan name from '" + biome + "'");
            }
            if (Files.exists(CapturePlan.path(dataRoot, n))) {
                return error(ctx, "plan '" + n + "' already exists");
            }
        }
        int train = IntegerArgumentType.getInteger(ctx, "train");
        int test = IntegerArgumentType.getInteger(ctx, "test");
        BlockPos center = mc.player.blockPosition();
        String worldId = WorldIdentity.of(server).worldId();
        String[] biomes = {biome.toString()};

        List<java.util.function.Supplier<Job>> jobs = new java.util.ArrayList<>();
        jobs.add(() -> new BiomeFinder(dataRoot, worldId, biome, PLAN_ZONE_RADIUS, DEFAULT_SEARCH_RADIUS,
                center.getX(), center.getZ()));
        jobs.add(() -> {
            BiomeFinder.Zone zone = zonesFor(biome).get(0);
            return new Planner(NrmcCaptureMod.config(), dataRoot, currentWorld(), trainName, train,
                    PLAN_ZONE_RADIUS, zone.x(), zone.z(), trainName.hashCode(), biomes);
        });
        if (test > 0) {
            jobs.add(() -> {
                List<BiomeFinder.Zone> zones = zonesFor(biome);
                BiomeFinder.Zone first = zones.get(0);
                BiomeFinder.Zone zone = zones.stream()
                        .filter(z -> Math.hypot(z.x() - first.x(), z.z() - first.z()) >= MIN_TRAIN_TEST_SEPARATION)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("no " + biome + " zone at least "
                                + MIN_TRAIN_TEST_SEPARATION + " blocks from the train zone; " + testName + " skipped"));
                return new Planner(NrmcCaptureMod.config(), dataRoot, currentWorld(), testName, test,
                        PLAN_ZONE_RADIUS, zone.x(), zone.z(), testName.hashCode(), biomes);
            });
        }
        NrmcCaptureMod.enqueue(jobs);
        CaptureConfig cfg = NrmcCaptureMod.config();
        int perLocation = cfg.allTimesPerPose ? cfg.timesOfDay.length : 1;
        ctx.getSource().sendFeedback(Component.literal(String.format(
                "Queued: find %s zones, then plans %s (%d locations) and %s (%d locations), %d pose(s) per location",
                biome, trainName, train, testName, test, perLocation)));
        return 1;
    }

    private static List<BiomeFinder.Zone> zonesFor(ResourceLocation biome) {
        List<BiomeFinder.Zone> zones = BiomeFinder.lastZones();
        if (!biome.equals(BiomeFinder.lastBiome()) || zones.isEmpty()) {
            throw new IllegalStateException("no zones found for " + biome + "; its plans are skipped");
        }
        return zones;
    }

    private static WorldIdentity currentWorld() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("the singleplayer world is not running");
        }
        return WorldIdentity.of(server);
    }

    /**
     * Queues, for each plan: shaders off, the vanilla pass, shaders on, the complementary pass.
     * Plans are loaded when their turn comes, so they may be created by earlier queued jobs.
     */
    private static int sweepBoth(CommandContext<FabricClientCommandSource> ctx) {
        Path dataRoot = NrmcCaptureMod.dataRoot();
        List<String> names = new java.util.ArrayList<>();
        for (String name : StringArgumentType.getString(ctx, "plans").trim().split("[\\s,]+")) {
            if (!NAME.matcher(name).matches()) {
                return error(ctx, "bad plan name '" + name + "'");
            }
            names.add(name);
        }
        List<java.util.function.Supplier<Job>> jobs = new java.util.ArrayList<>();
        for (String name : names) {
            for (String pass : List.of("vanilla", "complementary")) {
                boolean shaders = !pass.equals("vanilla");
                jobs.add(() -> new ShaderToggle(shaders));
                jobs.add(() -> {
                    try {
                        return new SweepRunner(NrmcCaptureMod.config(), dataRoot, CapturePlan.load(dataRoot, name), pass);
                    } catch (IOException e) {
                        throw new IllegalStateException("plan '" + name + "' not available: " + e.getMessage());
                    }
                });
            }
        }
        NrmcCaptureMod.enqueue(jobs);
        ctx.getSource().sendFeedback(Component.literal("Queued vanilla + complementary for " + names
                + " (" + NrmcCaptureMod.queued() + " jobs in queue). /nrmc status shows progress."));
        return 1;
    }

    private static int findBiome(CommandContext<FabricClientCommandSource> ctx, int zoneRadius, int searchRadius) {
        if (NrmcCaptureMod.currentJob() != null) {
            return error(ctx, "a job is already running: " + NrmcCaptureMod.currentJob().status());
        }
        Minecraft mc = ctx.getSource().getClient();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null || mc.level == null) {
            return error(ctx, "biome search requires an open singleplayer world");
        }
        if (!Level.OVERWORLD.equals(mc.level.dimension())) {
            return error(ctx, "biome search runs in the overworld only");
        }
        if (searchRadius <= zoneRadius) {
            return error(ctx, "searchRadius must be larger than zoneRadius");
        }
        String raw = StringArgumentType.getString(ctx, "biome");
        ResourceLocation biome = raw.contains(":") ? ResourceLocation.tryParse(raw) : ResourceLocation.withDefaultNamespace(raw);
        if (biome == null || !server.registryAccess().registryOrThrow(Registries.BIOME).containsKey(biome)) {
            return error(ctx, "unknown biome '" + raw + "' (use ids like forest, birch_forest, taiga)");
        }
        BlockPos center = mc.player.blockPosition();
        NrmcCaptureMod.start(new BiomeFinder(NrmcCaptureMod.dataRoot(), WorldIdentity.of(server).worldId(),
                biome, zoneRadius, searchRadius, center.getX(), center.getZ()));
        ctx.getSource().sendFeedback(Component.literal(String.format(
                "Searching %s zones of radius %d within %d blocks of (%d, %d)...",
                biome, zoneRadius, searchRadius, center.getX(), center.getZ())));
        return 1;
    }

    /** Loads the zone's chunk on the server, then teleports well above the surface in spectator. */
    private static int gotoZone(CommandContext<FabricClientCommandSource> ctx) {
        List<BiomeFinder.Zone> zones = BiomeFinder.lastZones();
        int n = IntegerArgumentType.getInteger(ctx, "zone");
        if (zones.isEmpty()) {
            return error(ctx, "run /nrmc findbiome first");
        }
        if (n > zones.size()) {
            return error(ctx, "only " + zones.size() + " zones in the last search");
        }
        Minecraft mc = ctx.getSource().getClient();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return error(ctx, "requires an open singleplayer world");
        }
        BiomeFinder.Zone zone = zones.get(n - 1);
        server.submit(() -> {
            ServerLevel level = server.overworld();
            level.getChunk(zone.x() >> 4, zone.z() >> 4);
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, zone.x(), zone.z());
        }).thenAcceptAsync(surface -> {
            if (mc.player == null) {
                return;
            }
            mc.player.connection.sendCommand("gamemode spectator");
            mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %d %d %d",
                    zone.x(), surface + GOTO_HEIGHT_ABOVE_SURFACE, zone.z()));
            NrmcCaptureMod.notify(String.format("Zone #%d: x=%d z=%d, surface y=%d, %.0f%% target biome",
                    zone.rank(), zone.x(), zone.z(), surface, zone.fraction() * 100));
        }, mc).exceptionally(e -> {
            NrmcCaptureMod.notify("goto failed: " + e.getMessage());
            return null;
        });
        return 1;
    }

    private static int error(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendError(Component.literal(message));
        return 0;
    }
}
