# Environment requirements

Pairs are only comparable across people if the environment is **identical**. Download every
component from its official source; this repository does not redistribute them. The machine-readable
version of this list is [`config/expected_environment.json`](../config/expected_environment.json),
which `tools/validate_contribution.py` checks.

## Base

| Component | Version | Where |
|---|---|---|
| Minecraft Java Edition | **1.21.1** | Official Mojang launcher |
| Fabric Loader | **0.19.5** | <https://fabricmc.net/use/installer/> |
| Java | 21 (bundled with the launcher and CurseForge; Temurin 21 to build the mod) | <https://adoptium.net/> |
| Game memory | 8 GB minimum; 12 GB for captures longer than ~8 hours | Launcher setting |
| Capture resolution | 1280×720 (the mod sets it) | — |

## Mods (`mods/` folder)

Search for each one by name on CurseForge or Modrinth and download **exactly this version**. File
IDs are CurseForge's. The first 12 characters of the sha256 let you check the download.

| Mod | Version | CurseForge file ID | sha256 (first 12) |
|---|---|---|---|
| Fabric API | `0.116.17+1.21.1` | 8786256 | `79ac44b40780` |
| Iris Shaders | **`1.8.14-beta.1+mc1.21.1`** | 8242801 | `0ceb694040b4` |
| Sodium | `0.8.13+mc1.21.1` | 8756581 | `3d43c14985a4` |
| Sodium Extra | `0.9.4+mc1.21.1` | 8892310 | `42c36c7cb308` |
| Mod Menu | `11.0.4` | 7808443 | `a88e0d67c82b` |
| Text Placeholder API | `2.4.2+1.21` | 6131327 | `c0187ee29952` |
| NRMC Capture | this repository (`mod/`), 0.5.3 or later | build it | — |

**Iris** is a beta. On CurseForge, enable "show beta versions". Iris 1.8.8 does not work: it crashes
with this Sodium. File page: <https://www.curseforge.com/minecraft/mc-mods/irisshaders/files/8242801>.

**Do not install:** OptiFine, Distant Horizons or resource packs. Any other mod shows up as a
warning in the validator.

## Shader (`shaderpacks/` folder)

| Shader | Version | CurseForge file ID | sha256 (first 12) |
|---|---|---|---|
| Complementary Reimagined | r5.9.3 | 8884654 | `fed6c879e732` |

Download it from the official source (CurseForge shaders tab, or Modrinth). Its license does not
allow redistribution, so it is not in this repository.

## Configuration files from this repository

Copy them with the game closed. Paths are relative to your instance folder.

| In this repository | Goes to the instance | What it does |
|---|---|---|
| `shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt` | `shaderpacks/` | Shader options: maximum quality, TAA on, waving and water animation off |
| `config/minecraft-options.txt` | `options.txt` | FOV, render distance 18, mipmap 4, brightness, entity shadows |
| `config/nrmc-capture.json` | `config/nrmc-capture.json` | Mod settings: times of day, camera pitch range, waits |
| `config/sodium-options.json` | `config/` | Sodium options |
| `config/sodium-extra-options.json` | `config/` | Turns off clouds, weather, particles and texture animations |
| `config/sodium-extra.properties` | `config/` | Sodium Extra settings |

In `config/nrmc-capture.json`, set `dataRoot` to a folder with free space (about 4 MB per scene).
Empty means the default folder inside the game directory.

## Check the installation

1. The game starts with Iris and Sodium without errors and the shader shows up in the shader menu.
2. `/nrmc status` answers in the chat.
3. Capture a small plan twice and compare it (`mod/README.md`, "Validation before a real capture"):
   the two captures should agree to 60 dB or more (median PSNR). Below 40 dB the capture is not
   repeatable: an animation or temporal effect of the shader is still on.

## Before a long capture

- Windows display scaling at 100 %, sleep disabled, Windows Update paused.
- Close everything that uses the GPU: shader frames depend on it.
- Do not use the computer while it captures: poses teleport the player and moving the mouse
  interrupts them.
- Restart the game every 6-8 hours of capture. After long sessions Java can run out of memory and
  every later pose fails with "terrain not ready".
