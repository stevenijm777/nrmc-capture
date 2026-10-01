# Data format

What the mod writes, as implemented in version 0.5.3. All JSON is UTF-8.

## Layout

```
<dataRoot>/
├── zones/<biome>.json                     zones found by /nrmc findbiome
├── plans/<plan>.json                      the poses of a plan
├── sweeps/<plan>__<pass>__<date>.json     one per capture run of a pass: environment and results
└── samples/<world_id>/<scene_id>/
    ├── vanilla.png                        shaders off, 8-bit sRGB
    ├── vanilla.json                       metadata of the vanilla frame
    ├── depth.npz                          depth of the vanilla frame
    ├── complementary.png                  shaders on
    └── complementary.json
```

A **scene** is one camera pose at one time of day in one world. A **pass** is one rendering of it
(`vanilla`, `complementary`, or any other name, such as `complementary_rep` for a determinism
check). The `.json` of a pass is written last: if it exists, the pass is complete.

`scene_id` is a ULID. Scenes of all plans of a world share `samples/<world_id>/`; the split comes
from the plans, not from the folder layout (`tools/build_split.py`).

## Plan (`plans/<plan>.json`)

| Field | Meaning |
|---|---|
| `name`, `worldId`, `levelName`, `worldSeed`, `dimension`, `createdAt` | Identity of the plan and the world |
| `params` | Everything needed to regenerate the plan: `seed`, `locations`, `radius`, `centerX`, `centerZ`, `timesOfDay`, `allTimesPerPose`, pitch and height ranges, allowed `biomes` |
| `stats` | Attempts and rejections (obstructed, biome filter) |
| `poses[]` | `index`, `sceneId`, `location`, `x`, `y`, `z`, `yaw`, `pitch`, `timeOfDay`, `weather`, `biome`, `surfaceY` |

The plan is deterministic for a world, a seed and the parameters. Poses of the same `location`
differ only in time of day and are consecutive.

## Sweep manifest (`sweeps/*.json`)

One per run of a pass over a plan. A resumed run leaves several.

| Field | Meaning |
|---|---|
| `sweep_id`, `plan`, `world_id`, `pass`, `status`, `started_at`, `finished_at`, `end_reason` | Identity and outcome |
| `environment` | Minecraft and loader versions, every mod with its version, GPU, OpenGL driver, Java, OS, capture mod version |
| `iris`, `shader_pack` | Whether shaders were on, the pack id, its sha256 and all its options |
| `client_options` | The whole `options.txt` |
| `capture_config` | The mod settings used |
| `framebuffer` | Width and height |
| `results` | `captured`, `skipped_existing`, `failed` (with reasons), `recoveries`, depth counters, `write_errors` |

## Frame metadata (`<pass>.json`)

| Field | Meaning |
|---|---|
| `scene_id`, `pass`, `sweep_id`, `plan`, `pose_index`, `location`, `world_id` | Identity |
| `file`, `sha256`, `width`, `height`, `captured_at` | The PNG and its hash |
| `pose_requested`, `pose_actual` | Camera position and rotation asked for and obtained |
| `time_of_day`, `time_of_day_actual`, `weather`, `biome` | World state. The game may advance one tick, so 0 is often recorded as 1 |
| `wait` | Frames and ticks waited before capturing, teleport retries |
| `camera` | `position`, `view_matrix`, `projection_matrix` (column-major, see `matrix_layout`), `reversed_depth` |
| `depth` | Only in the vanilla pass: file, sha256, shape and convention |

## Depth (`depth.npz`)

One array, `depth`, float32, 720×1280, origin at the top left. It is the **non-linear** OpenGL
window-space depth in [0, 1], with 1 at the far plane (sky). Linearize it with
`camera.projection_matrix`; `tools/inspect_depth.py` shows how and draws a check image.

## Manifest (`manifest*.jsonl`)

Written by `tools/build_split.py`: one JSON line per complete scene, with `scene_id`, `world_id`,
`plan`, `split`, the 512×512 spatial `cell`, pose, `biome`, `time_of_day` and the relative path and
hash of each pass and of the depth file.
