# NRMC Capture

A tool to capture **deterministic paired screenshots** of Minecraft: the same camera pose rendered
without shaders (vanilla) and with a shader pack, with the world frozen. The pairs are meant for
training and evaluating *neural rendering* models that learn the look of a shader, for example to
remaster recorded gameplay.

Independent project, **work in progress**.

## What is included

| Folder | Contents |
|---|---|
| `mod/` | Client-side Fabric mod for Minecraft 1.21.1. Plans camera poses, freezes the world and captures the vanilla/shader pairs plus depth. See [`mod/README.md`](mod/README.md) for the commands. |
| `config/` | Reference configuration: the mod settings, Minecraft video options, Sodium options and the expected capture environment. |
| `shaderpacks/` | The shader options used for the dataset (`.zip.txt`). **Not** the shader pack itself. |
| `tools/` | Python scripts: contribution validator, train/test split without spatial leakage, sweep and video-export comparison, depth inspection. |
| `docs/` | Environment requirements and the data format. |
| `contributions/` | Registry of data contributions. |

## What is not included

- Third-party shader packs: download them from the official source (see [requirements](docs/requirements.md)).
- Game files or Mojang assets.
- Dataset images or model weights. They live outside git; see [CONTRIBUTING.md](CONTRIBUTING.md).

## Quick start

1. Install the exact environment in [`docs/requirements.md`](docs/requirements.md): Minecraft 1.21.1,
   Fabric, the mods and the shader, each at the listed version.
2. Copy the files from `config/` and `shaderpacks/` into your instance, as that guide describes.
3. Build the mod (`mod/README.md`) and put the jar in the `mods/` folder.
4. Run the validation in `mod/README.md` before a long capture, then
   `python tools/validate_contribution.py` on the result.

Python tools need Python 3.11+ and `pip install -r requirements.txt`. Run them from the
repository root.

## Contributing data

The goal is for more people to capture new biomes and worlds with the same environment. The
process, what has to be validated and what is still open are in [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Code under the [MIT License](LICENSE).

The shader packs used have their own licenses and are not redistributed here. Complementary
Reimagined is governed by the *Complementary License Agreement*; read it before publishing results.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
