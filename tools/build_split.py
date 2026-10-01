"""Build the train/test split and the manifest from capture plans.

Every plan of a world writes into the same ``samples/<world_id>/`` folder, so the split cannot
be read from the folder layout: it comes from the plans. Each ``--plan NAME=SPLIT`` assigns all
poses of plan NAME to SPLIT.

Checks, in this order:
  * completeness: every pose has the input and target pass (PNG + JSON), same image size, and
    the PNG hash matches the one in the JSON (skip with --no-hash);
  * spatial leakage: no 512x512 cell holds poses of two splits; reports the minimum
    distance between poses of different splits;
  * capture environment: all sweeps of the same pass were captured with the same GPU,
    shader pack hash, shader options, client options, mod versions and framebuffer size.

A mismatch in the environment or a shared cell is an error (exit code 1): the resulting test
metrics would not mean what they claim. ``--allow-mismatch`` downgrades them to warnings.

Writes ``<data-root>/splits.json`` and ``<data-root>/manifest.jsonl``, or
``splits_<name>.json`` and ``manifest_<name>.jsonl`` with ``--name``, so several datasets can
coexist in one data root.

Example:
  python tools/build_split.py --data-root D:/nrmc_data \
      --plan forest_train=train --plan forest_test=test
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from PIL import Image

# Environment fields that change pixels. A difference in any of them between two sweeps of the
# same pass means the two splits were not rendered by the same function.
ENV_KEYS = ("gpu", "gpu_vendor", "opengl", "minecraft_version", "capture_mod_version", "mods")
# options.txt also stores keybinds, sound volumes and bookkeeping that differ between sessions
# without changing a pixel; only these keys are compared.
RENDER_OPTIONS = (
    "ao",
    "biomeBlendRadius",
    "entityShadows",
    "fov",
    "gamma",
    "graphicsMode",
    "guiScale",
    "mipmapLevels",
    "particles",
    "renderClouds",
    "renderDistance",
    "resourcePacks",
    "simulationDistance",
)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def cell_of(x: float, z: float, size: int) -> str:
    return f"x{math.floor(x / size)}_z{math.floor(z / size)}"


def sweep_fingerprint(sweep: dict[str, Any]) -> dict[str, Any]:
    env = sweep.get("environment") or {}
    pack = sweep.get("shader_pack") or {}
    return {
        **{k: env.get(k) for k in ENV_KEYS},
        "shader_pack": pack.get("id"),
        "shader_pack_sha256": pack.get("sha256"),
        "shader_options": pack.get("options"),
        "client_options": {k: (sweep.get("client_options") or {}).get(k) for k in RENDER_OPTIONS},
        "framebuffer": sweep.get("framebuffer"),
    }


def load_sweeps(data_root: Path, plan: str, pass_name: str) -> list[dict[str, Any]]:
    """All sweep manifests of (plan, pass); a resumed sweep leaves several."""
    out = []
    for path in sorted((data_root / "sweeps").glob(f"{plan}__{pass_name}__*.json")):
        sweep = read_json(path)
        if sweep.get("plan") == plan and sweep.get("pass") == pass_name:
            sweep["_file"] = path.name
            out.append(sweep)
    return out


def diff_fingerprints(fps: dict[str, dict[str, Any]]) -> list[str]:
    """Human-readable list of fields that differ between sweeps."""
    problems = []
    names = list(fps)
    keys = sorted({k for fp in fps.values() for k in fp})
    for key in keys:
        values = {n: fps[n].get(key) for n in names}
        distinct = {json.dumps(v, sort_keys=True) for v in values.values()}
        if len(distinct) <= 1:
            continue
        if isinstance(next(iter(values.values())), dict):
            # Show only the sub-keys that differ (options.txt has hundreds of entries).
            sub = sorted({s for v in values.values() if isinstance(v, dict) for s in v})
            for s in sub:
                vals = {n: (values[n] or {}).get(s) for n in names}
                if len({json.dumps(v) for v in vals.values()}) > 1:
                    problems.append(f"{key}.{s}: " + ", ".join(f"{n}={vals[n]!r}" for n in names))
        else:
            problems.append(f"{key}: " + ", ".join(f"{n}={values[n]!r}" for n in names))
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument(
        "--plan",
        action="append",
        required=True,
        metavar="NAME=SPLIT",
        help="plan name and its split, e.g. forest_train=train (repeatable)",
    )
    parser.add_argument("--input-pass", default="vanilla")
    parser.add_argument("--target-pass", default="complementary")
    parser.add_argument("--cell-size", type=int, default=512)
    parser.add_argument(
        "--name", default="", help="suffix for splits_<name>.json/manifest_<name>.jsonl"
    )
    parser.add_argument("--no-hash", action="store_true", help="skip PNG hash verification")
    parser.add_argument(
        "--allow-mismatch",
        action="store_true",
        help="report environment mismatches and shared cells as warnings",
    )
    args = parser.parse_args()

    root: Path = args.data_root
    plan_split: dict[str, str] = {}
    for item in args.plan:
        name, sep, split = item.partition("=")
        if not sep or not name or not split:
            parser.error(f"--plan expects NAME=SPLIT, got {item!r}")
        plan_split[name] = split

    errors: list[str] = []
    warnings: list[str] = []
    records: list[dict[str, Any]] = []
    cells: dict[str, set[str]] = defaultdict(set)
    positions: dict[str, list[tuple[float, float]]] = defaultdict(list)
    fingerprints: dict[str, dict[str, dict[str, Any]]] = defaultdict(dict)
    passes = (args.input_pass, args.target_pass)

    for plan_name, split in plan_split.items():
        plan_path = root / "plans" / f"{plan_name}.json"
        if not plan_path.is_file():
            print(f"plan not found: {plan_path}", file=sys.stderr)
            return 2
        plan = read_json(plan_path)
        world = plan["worldId"]
        params = plan.get("params", {})
        print(
            f"[{split}] plan {plan_name}: world {world}, {len(plan['poses'])} poses, "
            f"center ({params.get('centerX')}, {params.get('centerZ')}), radius {params.get('radius')}"
        )

        for pass_name in passes:
            sweeps = load_sweeps(root, plan_name, pass_name)
            if not sweeps:
                warnings.append(f"no sweep manifest for {plan_name}/{pass_name}")
            for sweep in sweeps:
                if sweep.get("status") != "completed":
                    warnings.append(
                        f"{sweep['_file']}: status {sweep.get('status')!r} "
                        "(fine if a later sweep resumed it)"
                    )
                fingerprints[pass_name][f"{plan_name}:{sweep['_file']}"] = sweep_fingerprint(sweep)

        missing = mismatched = 0
        for pose in plan["poses"]:
            scene = root / "samples" / world / pose["sceneId"]
            rec: dict[str, Any] = {
                "scene_id": pose["sceneId"],
                "world_id": world,
                "plan": plan_name,
                "pose_index": pose["index"],
                # Camera location (mod >= 0.4.0); scenes of one location differ only in time of day.
                "location": pose.get("location", -1),
                "split": split,
                "cell": cell_of(pose["x"], pose["z"], args.cell_size),
                "x": pose["x"],
                "y": pose["y"],
                "z": pose["z"],
                "yaw": pose["yaw"],
                "pitch": pose["pitch"],
                "biome": pose.get("biome"),
                "time_of_day": pose["timeOfDay"],
                "weather": pose.get("weather", "clear"),
                "passes": {},
            }
            ok = True
            size = None
            for pass_name in passes:
                png, meta_path = scene / f"{pass_name}.png", scene / f"{pass_name}.json"
                if not (png.is_file() and meta_path.is_file()):
                    ok = False
                    break
                meta = read_json(meta_path)
                if not args.no_hash and meta.get("sha256") != sha256(png):
                    errors.append(f"hash mismatch: {png}")
                    ok = False
                    break
                with Image.open(png) as img:
                    this_size = img.size
                if size is not None and this_size != size:
                    ok = False
                    mismatched += 1
                    break
                size = this_size
                rec["passes"][pass_name] = {
                    "path": str(png.relative_to(root).as_posix()),
                    "sha256": meta.get("sha256"),
                }
                if "depth" in meta:
                    rec["depth"] = {
                        "path": str((scene / meta["depth"]["file"]).relative_to(root).as_posix()),
                        "sha256": meta["depth"].get("sha256"),
                    }
            if not ok:
                missing += 1
                continue
            rec["width"], rec["height"] = size
            records.append(rec)
            cells[rec["cell"]].add(split)
            positions[split].append((pose["x"], pose["z"]))
        if missing:
            warnings.append(
                f"{plan_name}: {missing} poses incomplete or unreadable "
                f"({mismatched} with a size mismatch); excluded"
            )

    # a cell belongs to exactly one split.
    shared = sorted(c for c, s in cells.items() if len(s) > 1)
    if shared:
        errors.append(
            f"{len(shared)} cells hold poses of more than one split: {', '.join(shared[:10])}"
        )
    splits = sorted(positions)
    for i, a in enumerate(splits):
        for b in splits[i + 1 :]:
            d = min(math.dist(p, q) for p in positions[a] for q in positions[b])
            print(f"min distance {a} <-> {b}: {d:.0f} blocks")
            if d < args.cell_size:
                warnings.append(f"{a} and {b} have poses {d:.0f} blocks apart (< {args.cell_size})")

    # one machine and one configuration per pass across all splits.
    for pass_name, fps in fingerprints.items():
        for problem in diff_fingerprints(fps):
            errors.append(f"environment differs between {pass_name} sweeps -> {problem}")

    by_split = Counter(r["split"] for r in records)
    print("\nscenes per split: " + ", ".join(f"{s}={n}" for s, n in sorted(by_split.items())))
    for split in sorted(by_split):
        tod = Counter(r["time_of_day"] for r in records if r["split"] == split)
        biomes = Counter(r["biome"] for r in records if r["split"] == split)
        print(f"  {split}: time_of_day {dict(sorted(tod.items()))}")
        print(f"  {split}: biomes {dict(biomes.most_common(5))}")
    gpus = {fp.get("gpu") for fps in fingerprints.values() for fp in fps.values()}
    print(f"GPU(s): {sorted(g for g in gpus if g)}")

    for w in warnings:
        print(f"WARNING: {w}", file=sys.stderr)
    for e in errors:
        print(f"{'WARNING' if args.allow_mismatch else 'ERROR'}: {e}", file=sys.stderr)
    if errors and not args.allow_mismatch:
        print("nothing written; fix the errors or pass --allow-mismatch", file=sys.stderr)
        return 1

    splits_doc = {
        "schema_version": "0.1.0",
        "cell_size": args.cell_size,
        "input_pass": args.input_pass,
        "target_pass": args.target_pass,
        "plans": plan_split,
        "counts": dict(sorted(by_split.items())),
        "scenes": {r["scene_id"]: r["split"] for r in records},
    }
    suffix = f"_{args.name}" if args.name else ""
    splits_path, manifest_path = root / f"splits{suffix}.json", root / f"manifest{suffix}.jsonl"
    splits_path.write_text(json.dumps(splits_doc, indent=2), encoding="utf-8")
    with manifest_path.open("w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    print(f"\nwrote {splits_path} and {manifest_path} ({len(records)} scenes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
