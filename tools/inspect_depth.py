"""Check a captured depth buffer against its vanilla frame and derive auxiliary buffers.

For one scene directory (containing vanilla.png, vanilla.json and depth.npz) this:
  * linearizes the depth with the projection matrix stored in vanilla.json,
  * reconstructs view-space positions and derives surface normals from them (Minecraft geometry
    is mostly axis-aligned cubes, so depth-derived normals are close to exact),
  * reports the sky fraction and the depth range,
  * writes <scene>/depth_check.png: the frame, linear depth, normals and an edge overlay, used to
    confirm visually that depth and RGB are pixel-aligned.

Example:
  python tools/inspect_depth.py D:/nrmc_data/samples/<world>/<scene_id>
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def load_camera(meta_path: Path) -> tuple[np.ndarray, np.ndarray]:
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    camera = meta.get("camera") or {}
    if not camera.get("available"):
        raise SystemExit(f"{meta_path} has no camera matrices (captured with mod < 0.2.0?)")
    # Stored column-major (JOML); reshape to rows.
    projection = np.asarray(camera["projection_matrix"], dtype=np.float64).reshape(4, 4).T
    view = np.asarray(camera["view_matrix"], dtype=np.float64).reshape(4, 4).T
    return projection, view


def view_positions(depth: np.ndarray, projection: np.ndarray) -> np.ndarray:
    """Unproject window-space depth to view-space points (OpenGL: camera looks down -z)."""
    h, w = depth.shape
    xs = (np.arange(w) + 0.5) / w * 2.0 - 1.0
    ys = 1.0 - (np.arange(h) + 0.5) / h * 2.0  # row 0 is the top of the image
    ndc_x, ndc_y = np.meshgrid(xs, ys)
    ndc = np.stack([ndc_x, ndc_y, depth * 2.0 - 1.0, np.ones_like(depth)], axis=-1)
    points = ndc @ np.linalg.inv(projection).T
    return points[..., :3] / points[..., 3:4]


def normals_from_positions(p: np.ndarray) -> np.ndarray:
    dx = np.zeros_like(p)
    dy = np.zeros_like(p)
    dx[:, 1:-1] = p[:, 2:] - p[:, :-2]
    dy[1:-1, :] = p[2:, :] - p[:-2, :]
    n = np.cross(dy, dx)
    norm = np.linalg.norm(n, axis=-1, keepdims=True)
    return np.where(norm > 0, n / np.maximum(norm, 1e-12), 0.0)


def to_u8(x: np.ndarray) -> np.ndarray:
    return (np.clip(x, 0.0, 1.0) * 255).astype(np.uint8)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scene_dir", type=Path)
    args = parser.parse_args()

    scene = args.scene_dir
    depth = np.load(scene / "depth.npz")["depth"].astype(np.float64)
    rgb = np.asarray(Image.open(scene / "vanilla.png").convert("RGB"))
    if depth.shape != rgb.shape[:2]:
        print(f"shape mismatch: depth {depth.shape} vs rgb {rgb.shape[:2]}", file=sys.stderr)
        return 1
    projection, _ = load_camera(scene / "vanilla.json")

    sky = depth >= 1.0
    positions = view_positions(depth, projection)
    distance = -positions[..., 2]
    geometry = ~sky
    print(f"size {depth.shape[1]}x{depth.shape[0]}  sky {sky.mean() * 100:.1f}%")
    if geometry.any():
        d = distance[geometry]
        print(f"view distance (blocks): min {d.min():.2f}  median {np.median(d):.2f}  max {d.max():.2f}")

    normals = normals_from_positions(positions)
    normals[sky] = 0.0
    # Fraction of geometry pixels whose normal is within 5 degrees of a view-space axis would need the
    # view rotation; instead report how many are well defined (non-degenerate).
    defined = np.linalg.norm(normals, axis=-1) > 0.5
    print(f"pixels with a defined normal: {defined[geometry].mean() * 100:.1f}% of geometry")

    log_d = np.log1p(np.where(geometry, distance, 0.0))
    depth_vis = np.where(geometry, 1.0 - log_d / max(log_d.max(), 1e-6), 0.0)
    normal_vis = np.where(defined[..., None], normals * 0.5 + 0.5, 0.0)

    # Depth discontinuities drawn over the RGB frame: misalignment shows up as edges off the geometry.
    grad = np.zeros_like(log_d)
    grad[:, 1:] += np.abs(np.diff(log_d, axis=1))
    grad[1:, :] += np.abs(np.diff(log_d, axis=0))
    edges = grad > 0.05
    overlay = rgb.copy()
    overlay[edges] = [255, 0, 255]

    top = np.concatenate([rgb, overlay], axis=1)
    bottom = np.concatenate([np.repeat(to_u8(depth_vis)[..., None], 3, axis=-1), to_u8(normal_vis)], axis=1)
    out = scene / "depth_check.png"
    Image.fromarray(np.concatenate([top, bottom], axis=0)).save(out)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
