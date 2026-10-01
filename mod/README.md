# nrmc-capture: the capture mod

Client-side Fabric mod for Minecraft 1.21.1. It captures vanilla/shader image pairs from the same
camera poses, with the world frozen. Scope: RGB plus depth, one shader, still frames (no sequences).

It uses no mixins and no Iris internals, only:

- game commands (`/tp`, `/time`, `/gamerule`, `/tick`), so the world needs cheats enabled;
- the same main-framebuffer capture as F2 screenshots;
- Iris's public API to switch shaders on and off;
- configuration files (`config/iris.properties`, `shaderpacks/<pack>.txt`, `options.txt`), copied
  into every sweep manifest to record the environment.

## Workflow

0. **Find a zone.** `/nrmc findbiome <biome> [zoneRadius] [searchRadius]`, for example
   `/nrmc findbiome forest`, lists up to 8 non-overlapping zones where that biome covers the largest
   share of the area (default zone radius 384, search radius 4000 around the player). It queries the
   world generator without creating chunks, like `/locate`, and saves the zones in `zones/`.
   `/nrmc goto <n>` teleports you in spectator mode 30 blocks above zone n.
1. `/nrmc plan <name> <n> <radius> [seed]` creates `n` camera locations within `radius` blocks of the
   player. With `alltimes` off (default) each location gets one random time from `timesOfDay`; with
   `alltimes` on, each location is repeated once per time. The result is deterministic for a world,
   a seed and the parameters, and is saved in `plans/<name>.json`.
2. With shaders off: `/nrmc sweep <name> vanilla`.
3. With the shader on: `/nrmc sweep <name> complementary`.

Every sweep repeats exactly the same poses and times. The shader pack is loaded once per sweep, not
once per scene.

During a sweep do not touch the mouse or keyboard and do not minimize the window. If the camera
moves, the mod teleports again. A minimized window renders no frames, and the mod waits instead of
saving a stale image.

If a sweep is interrupted, run the same command again: poses that already have output are skipped.

### Unattended capture

- `/nrmc planbiome <biome> <trainLocations> <testLocations>` finds the biome's zones around the
  player and creates `<biome>_train` in the best zone and `<biome>_test` in the best zone at least
  1500 blocks away. The plans only accept that biome; no teleport needed.
- `/nrmc sweepboth <plan1> <plan2> ...` runs, for each plan, the `vanilla` pass with shaders off and
  the `complementary` pass with shaders on. It switches shaders through Iris's public API
  (`IrisApi.getConfig().setShadersEnabledAndApply`) and waits until `iris.properties` reflects it.
- `/nrmc sweepmany <pass> <plan1> <plan2> ...` runs several plans with the same pass.

These commands are **queued**: type several in a row and they run in order. A plan is loaded when
its turn comes, so `sweepboth` can name plans that an earlier `planbiome` is still creating. If a
step fails (a biome with no zones, for example), the chat says so and the queue moves on.
`/nrmc stop` stops the current sweep and drops the queue; `/nrmc status` shows progress.

After `maxConsecutiveFailures` failed poses in a row the sweep recovers: it frees memory, reloads
chunks, waits `recoveryWaitTicks` and retries. After `recoveryAttempts` recoveries without a capture
it gives up on that sweep and the queue continues. If every pose fails with "terrain not ready", the
game has most likely run out of memory: restart it and run the same commands again.

### Safety check

Before starting, the mod reads `config/iris.properties`. The `vanilla` pass requires shaders off;
any other pass name requires shaders on. If they do not match, the sweep aborts without capturing,
so a shader image never ends up in the vanilla slot.

### Several times of day per location

```
/nrmc times 0 1000 3000 6000 9000 11000 12000
/nrmc alltimes on
/nrmc plan forest_hours 300 384
```

`/nrmc times` shows or sets the times (ticks: 0 sunrise, 6000 noon, 12000 sunset). `/nrmc alltimes`
toggles the mode. Both are saved in `config/nrmc-capture.json` and only affect new plans. Poses of the
same location are consecutive, so each extra time only costs the settle wait (`settleTicks`).

## Build

Requirements: JDK 21 (for example Temurin 21) and Gradle 9.5.1. The simplest route is IntelliJ IDEA
Community, which downloads both:

1. Open the `mod/` folder as a project.
2. *File → Project Structure → SDK*: *Download JDK*, Temurin 21.
3. IntelliJ picks Gradle 9.5.1 from `gradle/wrapper/gradle-wrapper.properties`.
4. In the Gradle panel run `Tasks → build → build`. The first build downloads Minecraft and the
   mappings and takes several minutes.
5. The jar is `mod/build/libs/nrmc-capture-<version>.jar`.

The repository does not include `gradlew`, `gradlew.bat` or `gradle-wrapper.jar`. Generate them with
`Tasks → build setup → wrapper` in IntelliJ (or `gradle wrapper`) and then build from the command line
with `./gradlew build`.

Toolchain: `net.fabricmc.fabric-loom-remap` 1.17-SNAPSHOT, official Mojang mappings, Fabric loader
0.19.5 and Fabric API 0.116.17+1.21.1 (from `FabricMC/fabric-example-mod`, branch `1.21`).

## Install

Copy the jar into the `mods/` folder of your instance and start the game. The first start creates
`config/nrmc-capture.json` with the defaults; replace it with the one in `config/` of this repository.

## Before capturing

1. Single-player world with cheats enabled.
2. Fullscreen off. The mod resizes the window to `targetWidth × targetHeight` (1280×720) at the start
   of every sweep; try it with `/nrmc window`. With Windows display scaling other than 100 % the size
   may not match, and the mod aborts and reports the size it got.
3. In `config/nrmc-capture.json`, set `dataRoot` on a disk with space: about 4 MB per scene (vanilla,
   shader and depth) at 1280×720. A pass takes about 4 s per pose.
4. Shader options: copy the file from `shaderpacks/` of this repository. The mod does not change them,
   it records them. They turn waving off and set `WATER_SPEED_MULT=0.00`; without it water animates
   with `frameTimeCounter` and scenes with water stop being deterministic.

## Configuration (`config/nrmc-capture.json`)

Defaults below are the mod's built-in values; the file in `config/` of this repository is the one
used for the dataset (all seven times of day, pitch from −70 to 89, readiness radius 16).

| Key | Default | What it does |
|---|---|---|
| `dataRoot` | `<game>/nrmc_data` | Dataset root |
| `targetWidth`, `targetHeight` | 1280, 720 | Required framebuffer size; 0 disables the check |
| `timesOfDay` | 1000, 6000, 11000 | Possible times; set with `/nrmc times` |
| `allTimesPerPose` | false | Every location at every time; set with `/nrmc alltimes` |
| `feetOffsetMin/Max` | 0, 6 | Feet height above the ground; the camera is 1.62 blocks higher |
| `pitchMean/Std/Min/Max` | 8, 12, −30, 50 | Camera pitch (positive looks down) |
| `captureDepth` | true | Save `depth.npz` in the `vanilla` pass |
| `planBiomes` | `[]` | Biomes allowed in a plan, e.g. `["minecraft:forest"]`; empty = any. Checked at the camera position |
| `maxPerBiome` | 0 | Maximum poses per biome in a plan; 0 = no limit |
| `readinessRadiusChunks` | 12 | Chunk radius that must be loaded and built before capturing. Must cover the shader's shadow distance |
| `stableTicks` | 10 | Consecutive ticks with complete terrain |
| `settleTicks` | 60 | Wait after the terrain is ready, so the shader converges (3 s) |
| `minSettleFrames` | 30 | Minimum rendered frames within that wait |
| `maxConsecutiveFailures`, `recoveryAttempts`, `recoveryWaitTicks` | 8, 3, 1200 | Recovery after failed poses |

Run `/nrmc reload` after editing the file.

## Validation before a real capture

Make a small plan (20 poses) and capture it twice with the same shader:

```
/nrmc plan pilot 20 128
/nrmc sweep pilot complementary
/nrmc sweep pilot complementary_rep
```

Then, from the repository root:

```bash
python tools/compare_sweeps.py --data-root <dataRoot> --world <world_id> --pass-a complementary --pass-b complementary_rep
```

Expect 65-90 dB with a maximum difference in the single digits. How to read a bad scene:

- A maximum difference near 200 means terrain that was not loaded. Raise `stableTicks` or
  `settleTicks`.
- A low (≤16) but widespread difference means an animation tied to `frameTimeCounter`. Make a
  difference map to find it; in the pilot it was water, fixed with `WATER_SPEED_MULT=0.00`.

## Measured so far

On one machine (RTX 5070 Ti, NVIDIA driver 616.92):

- two captures of the same 20 poses with Complementary: median 81.2 dB, maximum difference 4/255;
- 800-pose forest plan: two passes without failures, about 4 s per pose; determinism in dense forest
  with a median of 97 dB;
- more than 15 000 pairs captured over several nights in forest, plains, desert and snowy plains.

Known issue: after more than ~12 hours in one session, Java ran out of memory (8 GB) and every later
pose failed. Restart the game between long blocks of plans.

## Out of scope

G-buffers other than depth, sequences with motion vectors, switching between different shader
packs, cave and indoor scenes (poses always land on the highest solid block) and weather other than
clear.
