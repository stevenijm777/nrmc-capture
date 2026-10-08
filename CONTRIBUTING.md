# Contributing

Code contributions (the mod, the tools, the docs) are welcome as regular pull requests.

This file is mostly about **data**: capturing new biomes or worlds with the same environment.
Status: **proposal**. There is no place to host images yet (see "Open questions").

## How a data contribution works

Every contributor captures with the **same environment** ([requirements](docs/requirements.md)). What
goes into this repository is a **record and a validation report**, never images.

1. Install the exact environment and check the hashes.
2. Pick a biome or a world that is not in the registry (`contributions/`). Note the seed and the zone.
3. Create the plans and capture them (`mod/README.md`). For example:
   ```
   /nrmc planbiome desert 160 40
   /nrmc sweepboth desert_train desert_test
   ```
4. Measure determinism: capture one plan a second time under another pass name, with the shader on.
   A small plan is enough (20 poses or more):
   ```
   /nrmc sweep desert_test complementary_rep
   ```
5. Validate:
   ```bash
   python tools/validate_contribution.py --data-root <dataRoot> \
       --plan desert_train --plan desert_test --repeat-pass complementary_rep \
       --contributor <github-user> --name desert --out validation/desert
   ```
   It checks completeness, file hashes, frame size, depth files, that every frame landed on its
   planned pose and time, the full capture environment against
   [`config/expected_environment.json`](config/expected_environment.json), and determinism. The
   verdict must be `ready`. Determinism only blocks when the two captures clearly disagree (median
   below 40 dB); between 40 and 60 dB it is a warning, reported in the record.
6. Host the data outside git (for example Hugging Face Datasets or Zenodo), including the
   `files.sha256` list the validator wrote.
7. Open a pull request that adds the record the validator wrote
   (`validation/desert/<github-user>_desert.json`) to `contributions/`, with `data.url` filled in.

## What reviewers look at

- The validator verdict and its report.
- **GPU and driver.** The GPU changes the shader's pixels: NVIDIA and AMD do not render the same frame.
  A contribution from another GPU family is kept as a separate subset, not mixed in without measuring.
- That the zones do not overlap with existing contributions.
- A statement that you have the right to share the data (the `rights` field).

## Decided by the maintainer

- **The test set is fixed.** If everyone brought their own test split there would be leakage between
  biomes. Contributions go in as training data, or as the test set of a complete new world.
- **World zones.** Two people using the same world can overlap. Every record lists seed and zones.

## Open questions

- **Hosting images of a third-party shader** needs permission from its author (Complementary
  Development) and a check of Mojang's usage guidelines. Until then only records and validation
  reports are collected.
- License of the contributed data.
