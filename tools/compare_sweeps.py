"""Compare two passes of the same plan, scene by scene.

Typical uses:
  * Determinism: capture the same plan twice with the same shader under two pass
    names (e.g. ``complementary`` and ``complementary_rep``) and compare them. Identical settings
    should give PSNR close to the vanilla noise floor (~70 dB).
  * Sanity check of a vanilla/shader pair: expect low PSNR (the shader changes every pixel) but
    identical image sizes and no missing scenes.

Example:
  python tools/compare_sweeps.py --data-root D:/nrmc_data --world New_World \
      --pass-a complementary --pass-b complementary_rep --csv determinism.csv
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image


@dataclass
class PairResult:
    scene_id: str
    psnr_db: float
    max_diff: int
    mean_diff: float
    changed_fraction: float


def load_rgb(path: Path) -> np.ndarray:
    with Image.open(path) as img:
        return np.asarray(img.convert("RGB"), dtype=np.int16)


def compare(a: np.ndarray, b: np.ndarray) -> tuple[float, int, float, float]:
    diff = np.abs(a - b)
    mse = float(np.mean(diff.astype(np.float64) ** 2))
    psnr = float("inf") if mse == 0.0 else 10.0 * np.log10(255.0**2 / mse)
    changed = float(np.mean(diff.max(axis=2) > 0))
    return psnr, int(diff.max()), float(diff.mean()), changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--world", required=True, help="world_id folder under samples/")
    parser.add_argument("--pass-a", required=True)
    parser.add_argument("--pass-b", required=True)
    parser.add_argument("--csv", type=Path, help="optional per-scene output")
    parser.add_argument("--worst", type=int, default=10, help="how many worst scenes to list")
    args = parser.parse_args()

    samples = args.data_root / "samples" / args.world
    if not samples.is_dir():
        print(f"not found: {samples}", file=sys.stderr)
        return 2

    results: list[PairResult] = []
    only_a = only_b = size_mismatch = 0
    for scene_dir in sorted(p for p in samples.iterdir() if p.is_dir()):
        path_a = scene_dir / f"{args.pass_a}.png"
        path_b = scene_dir / f"{args.pass_b}.png"
        has_a, has_b = path_a.is_file(), path_b.is_file()
        if has_a != has_b:
            only_a += has_a
            only_b += has_b
            continue
        if not has_a:
            continue
        a, b = load_rgb(path_a), load_rgb(path_b)
        if a.shape != b.shape:
            size_mismatch += 1
            print(f"size mismatch {scene_dir.name}: {a.shape} vs {b.shape}", file=sys.stderr)
            continue
        results.append(PairResult(scene_dir.name, *compare(a, b)))

    print(f"pairs compared: {len(results)}  only {args.pass_a}: {only_a}  only {args.pass_b}: {only_b}"
          f"  size mismatch: {size_mismatch}")
    if not results:
        return 1

    finite = np.array([r.psnr_db for r in results if np.isfinite(r.psnr_db)])
    identical = sum(1 for r in results if not np.isfinite(r.psnr_db))
    changed = np.array([r.changed_fraction for r in results]) * 100
    print(f"identical pairs: {identical}")
    if finite.size:
        print(f"PSNR dB  median {np.median(finite):.2f}  p5 {np.percentile(finite, 5):.2f}  min {finite.min():.2f}")
    print(f"changed pixels %  median {np.median(changed):.2f}  p95 {np.percentile(changed, 95):.2f}"
          f"  max {changed.max():.2f}")
    print(f"max channel diff over all pairs: {max(r.max_diff for r in results)}")

    print(f"\nworst {args.worst} scenes by PSNR:")
    for r in sorted(results, key=lambda r: r.psnr_db)[: args.worst]:
        print(f"  {r.scene_id}  PSNR {r.psnr_db:6.2f}  changed {r.changed_fraction * 100:6.2f}%  max {r.max_diff}")

    if args.csv:
        with args.csv.open("w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["scene_id", "psnr_db", "max_diff", "mean_diff", "changed_fraction"])
            for r in results:
                writer.writerow([r.scene_id, f"{r.psnr_db:.4f}", r.max_diff, f"{r.mean_diff:.6f}",
                                 f"{r.changed_fraction:.6f}"])
        print(f"\nwrote {args.csv}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
