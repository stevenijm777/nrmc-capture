"""Compare two exports of the same replay, frame by frame.

Each input is a folder of PNG frames (sorted by name) or a video file. Typical uses:
  * Determinism: export the same Flashback recording twice with the same shader. Identical
    exports give PSNR = inf; the static-capture noise floor was ~70 dB for vanilla.
  * Frame offset: two exports that start one frame apart look non-deterministic. ``--max-shift``
    tries shifting B against A and reports the shift with the best median PSNR before comparing.

Lossy video (mp4) adds compression noise that differs between encodes, so use PNG sequences to
decide determinism; video is accepted only for a rough look.

Example:
  python tools/compare_exports.py exports/vanilla_a exports/vanilla_b --csv v08_vanilla.csv
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import numpy as np
from compare_sweeps import compare, load_rgb

VIDEO_SUFFIXES = {".mp4", ".mkv", ".webm", ".mov", ".avi"}


def load_frames(path: Path) -> list[np.ndarray]:
    if path.is_dir():
        files = sorted(path.glob("*.png"))
        if not files:
            raise SystemExit(f"no PNG frames in {path}")
        return [load_rgb(f) for f in files]
    if path.suffix.lower() in VIDEO_SUFFIXES:
        import cv2

        cap = cv2.VideoCapture(str(path))
        frames = []
        while True:
            ok, bgr = cap.read()
            if not ok:
                break
            frames.append(bgr[:, :, ::-1].astype(np.int16))
        cap.release()
        if not frames:
            raise SystemExit(f"could not decode {path}")
        return frames
    raise SystemExit(f"{path} is neither a folder of PNGs nor a video")


def best_shift(a: list[np.ndarray], b: list[np.ndarray], max_shift: int, probe: int = 30) -> int:
    """Shift s such that a[i] matches b[i + s] best.

    Judged on `probe` frames spread over the whole clip (the first frames are often static, so
    every shift looks alike there). Near-ties go to the smallest |s|.
    """
    scores = {}
    for s in range(-max_shift, max_shift + 1):
        spread = np.linspace(0, len(a) - 1, min(probe, len(a))).astype(int)
        idx = [i for i in spread if 0 <= i + s < len(b)]
        if idx:
            scores[s] = float(np.median([min(compare(a[i], b[i + s])[0], 200.0) for i in idx]))
    return max(scores, key=lambda s: (round(scores[s], 1), -abs(s)))


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("export_a", type=Path)
    parser.add_argument("export_b", type=Path)
    parser.add_argument("--max-shift", type=int, default=3, help="frame offsets to try (0 = none)")
    parser.add_argument("--csv", type=Path, help="optional per-frame output")
    parser.add_argument("--worst", type=int, default=10, help="how many worst frames to list")
    args = parser.parse_args()

    a, b = load_frames(args.export_a), load_frames(args.export_b)
    for name, frames in (("A", a), ("B", b)):
        print(f"{name}: {len(frames)} frames {frames[0].shape[1]}x{frames[0].shape[0]}")
    if a[0].shape != b[0].shape:
        print("ERROR: different frame size", file=sys.stderr)
        return 1

    shift = best_shift(a, b, args.max_shift) if args.max_shift > 0 else 0
    if shift:
        print(f"B is offset by {shift:+d} frame(s); comparing a[i] with b[i{shift:+d}]")
    rows = []
    for i in range(len(a)):
        if 0 <= i + shift < len(b):
            psnr, max_diff, mean_diff, changed = compare(a[i], b[i + shift])
            rows.append((i, psnr, max_diff, mean_diff, changed))

    psnrs = np.array([r[1] for r in rows])
    identical = int(np.sum(np.isinf(psnrs)))
    finite = psnrs[np.isfinite(psnrs)]
    print(f"compared {len(rows)} frames: {identical} identical ({identical / len(rows):.1%})")
    if finite.size:
        changed_median = np.median([r[4] for r in rows if np.isfinite(r[1])])
        print(
            f"non-identical frames: PSNR median {np.median(finite):.2f} dB, "
            f"min {finite.min():.2f} dB, changed pixels median {changed_median:.2%}, "
            f"max diff {max(r[2] for r in rows)}"
        )
        print(f"worst {args.worst} frames (index, PSNR, max diff, changed):")
        for i, psnr, max_diff, _, changed in sorted(rows, key=lambda r: r[1])[: args.worst]:
            print(f"  {i:5d}  {psnr:6.2f} dB  {max_diff:3d}  {changed:.2%}")

    if args.csv:
        with args.csv.open("w", newline="") as fh:
            writer = csv.writer(fh)
            writer.writerow(["frame", "psnr_db", "max_diff", "mean_diff", "changed_fraction"])
            writer.writerows(rows)
        print(f"wrote {args.csv}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
