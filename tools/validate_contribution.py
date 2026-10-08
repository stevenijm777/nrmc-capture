"""Validate a data contribution before it is submitted.

Runs every check a reviewer needs on one or more capture plans and writes a report plus a
pre-filled registry record for ``contributions/``. Nothing is uploaded.

Checks:
  1. completeness  every pose of every plan has the input and target pass (PNG + JSON);
  2. integrity     the PNG sha256 matches its JSON, the frame is 1280x720, depth.npz exists when
                   the JSON points to it;
  3. pose          the camera and time of day landed where the plan asked, weather is clear;
  4. environment   every sweep matches config/expected_environment.json (Minecraft, loader, mod
                   versions, shader pack hash and options, render options, framebuffer), and all
                   sweeps of a pass share one GPU;
  5. determinism   with --repeat-pass, the target pass captured twice is compared (median PSNR):
                   below fail_determinism_db the capture is not repeatable (FAIL); between that
                   and min_determinism_db it is reported but does not block (WARN).

Verdicts: PASS, WARN (worth a look, does not block) and FAIL. A contribution is ready when no
check fails and determinism was measured. Exit code: 0 ready, 1 a check failed, 2 incomplete.

Example:
  python tools/validate_contribution.py --data-root D:/nrmc_data \
      --plan desert_train --plan desert_test --repeat-pass complementary_rep \
      --contributor my-github-user --name desert --out validation/desert
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
from compare_sweeps import compare, load_rgb

REPO = Path(__file__).resolve().parents[1]
STATUS_ORDER = {"PASS": 0, "WARN": 1, "FAIL": 2}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def read_key_values(path: Path, sep: str) -> dict[str, str]:
    """options.txt (``key:value``) or an Iris shader options file (``key=value``)."""
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and sep in line:
            k, v = line.split(sep, 1)
            out[k.strip()] = v.strip()
    return out


def png_size(path: Path) -> tuple[int, int]:
    """Width and height from the PNG header, without decoding the image."""
    with path.open("rb") as f:
        head = f.read(24)
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


def version_tuple(v: str) -> tuple[int, ...]:
    return tuple(int(p) for p in v.split("+")[0].split("-")[0].split(".") if p.isdigit())


def pose_matches(meta: dict[str, Any]) -> bool:
    """Camera within 1e-3 of the plan (yaw modulo 360), time within 2 ticks, clear weather.

    The game can advance one tick between setting the time and the frame (0 is recorded as 1).
    """
    req, act = meta.get("pose_requested") or {}, meta.get("pose_actual") or {}
    for k in ("x", "y", "z", "pitch", "yaw"):
        if k not in req or k not in act:
            return False
        d = abs(req[k] - act[k])
        if k == "yaw":
            d = min(d % 360, 360 - d % 360)
        if d > 1e-3:
            return False
    t, ta = meta.get("time_of_day"), meta.get("time_of_day_actual")
    if t is None or ta is None or min(abs(t - ta) % 24000, 24000 - abs(t - ta) % 24000) > 2:
        return False
    return meta.get("weather") == "clear"


class Report:
    def __init__(self) -> None:
        self.checks: dict[str, dict[str, Any]] = {}

    def add(self, name: str, status: str, summary: str, details: list[str] | None = None) -> None:
        prev = self.checks.get(name)
        if prev and STATUS_ORDER[prev["status"]] > STATUS_ORDER[status]:
            status = prev["status"]
        entry = self.checks.setdefault(name, {"status": status, "summary": [], "details": []})
        entry["status"] = status
        entry["summary"].append(summary)
        entry["details"].extend((details or [])[:50])

    def worst(self) -> str:
        return max((c["status"] for c in self.checks.values()), key=STATUS_ORDER.get, default="PASS")


def check_environment(
    report: Report, sweeps: dict[str, list[dict[str, Any]]], expected: dict[str, Any]
) -> dict[str, Any]:
    shader_opts = read_key_values(REPO / expected["shader_pack"]["options_file"], "=")
    client_ref = read_key_values(REPO / expected["client_options_file"], ":")
    problems, warnings = [], []
    gpus: dict[str, set[str]] = defaultdict(set)
    seen: dict[str, Any] = {}
    for pass_name, items in sweeps.items():
        for s in items:
            tag = s["_file"]
            env = s.get("environment") or {}
            gpus[pass_name].add(f"{env.get('gpu')} | {env.get('opengl')}")
            seen.setdefault("environment", env)
            if env.get("minecraft_version") != expected["minecraft_version"]:
                problems.append(f"{tag}: Minecraft {env.get('minecraft_version')}")
            loader = env.get("loader") or {}
            if loader.get("version") != expected["loader"]["version"]:
                problems.append(f"{tag}: Fabric loader {loader.get('version')}")
            mods = {m["id"]: m["version"] for m in env.get("mods") or []}
            for mod_id, version in expected["mods"].items():
                if mods.get(mod_id) != version:
                    problems.append(f"{tag}: {mod_id} {mods.get(mod_id)} (expected {version})")
            extra = sorted(set(mods) - set(expected["mods"]) - set(expected["optional_mods"]))
            if extra:
                warnings.append(f"{tag}: extra mods {extra}")
            cap = env.get("capture_mod_version") or "0"
            if version_tuple(cap) < version_tuple(expected["min_capture_mod_version"]):
                problems.append(f"{tag}: capture mod {cap} < {expected['min_capture_mod_version']}")
            fb = s.get("framebuffer") or {}
            if (fb.get("width"), fb.get("height")) != (
                expected["framebuffer"]["width"],
                expected["framebuffer"]["height"],
            ):
                problems.append(f"{tag}: framebuffer {fb}")
            client = s.get("client_options") or {}
            for k in expected["render_options"]:
                if client.get(k) != client_ref.get(k):
                    problems.append(f"{tag}: option {k}={client.get(k)!r} (expected {client_ref.get(k)!r})")
            if s.get("shaders_expected"):
                pack = s.get("shader_pack") or {}
                if pack.get("sha256") != expected["shader_pack"]["sha256"]:
                    problems.append(f"{tag}: shader pack {pack.get('id')} sha256 {str(pack.get('sha256'))[:12]}")
                opts = pack.get("options") or {}
                diff = sorted(k for k in set(opts) | set(shader_opts) if opts.get(k) != shader_opts.get(k))
                if diff:
                    problems.append(
                        f"{tag}: shader options differ: "
                        + ", ".join(f"{k}={opts.get(k)!r} (expected {shader_opts.get(k)!r})" for k in diff)
                    )
            res = s.get("results") or {}
            if res.get("write_errors"):
                problems.append(f"{tag}: {len(res['write_errors'])} write errors")
            if s.get("status") != "completed":
                warnings.append(f"{tag}: status {s.get('status')!r} (fine if a later sweep resumed it)")
    for pass_name, g in gpus.items():
        if len(g) > 1:
            problems.append(f"pass {pass_name} was captured on more than one GPU/driver: {sorted(g)}")
    n = sum(len(v) for v in sweeps.values())
    if problems:
        report.add("environment", "FAIL", f"{len(problems)} problems in {n} sweeps", problems)
    elif warnings:
        report.add("environment", "WARN", f"{n} sweeps match the reference, with warnings", warnings)
    else:
        report.add("environment", "PASS", f"{n} sweeps match the reference environment")
    if not sweeps or not any(sweeps.values()):
        report.add("environment", "FAIL", "no sweep manifests found")
    return seen.get("environment", {})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--plan", action="append", required=True, help="plan name (repeatable)")
    parser.add_argument("--input-pass", default="vanilla")
    parser.add_argument("--target-pass", default="complementary")
    parser.add_argument("--repeat-pass", help="second capture of the target pass, for determinism")
    parser.add_argument("--expected", type=Path, default=REPO / "config" / "expected_environment.json")
    parser.add_argument("--hash-every", type=int, default=1, help="verify the hash of every N-th scene")
    parser.add_argument("--contributor", default="your-github-user")
    parser.add_argument("--name", help="short name of the contribution (default: first plan)")
    parser.add_argument("--out", type=Path, required=True, help="folder for the report")
    args = parser.parse_args()

    root: Path = args.data_root
    expected = read_json(args.expected)
    report = Report()
    passes = [args.input_pass, args.target_pass]
    sweeps: dict[str, list[dict[str, Any]]] = defaultdict(list)
    plans_out, files, biomes = [], [], Counter()
    rep_pairs: list[tuple[Path, Path]] = []
    total = complete = 0

    for plan_name in args.plan:
        plan_path = root / "plans" / f"{plan_name}.json"
        if not plan_path.is_file():
            report.add("completeness", "FAIL", f"plan not found: {plan_path}")
            continue
        plan = read_json(plan_path)
        world, params = plan["worldId"], plan.get("params", {})
        for pass_name in passes + ([args.repeat_pass] if args.repeat_pass else []):
            for path in sorted((root / "sweeps").glob(f"{plan_name}__{pass_name}__*.json")):
                s = read_json(path)
                if s.get("plan") == plan_name and s.get("pass") == pass_name:
                    s["_file"] = path.name
                    sweeps[pass_name].append(s)
                    files.append(path)
        missing, bad_hash, bad_size, bad_pose, no_depth = [], [], [], [], []
        n_ok = 0
        for i, pose in enumerate(plan["poses"]):
            total += 1
            scene = root / "samples" / world / pose["sceneId"]
            ok = True
            for pass_name in passes:
                png, meta_path = scene / f"{pass_name}.png", scene / f"{pass_name}.json"
                if not (png.is_file() and meta_path.is_file()):
                    missing.append(f"{pose['sceneId']}/{pass_name}")
                    ok = False
                    continue
                meta = read_json(meta_path)
                files += [png, meta_path]
                if i % args.hash_every == 0 and meta.get("sha256") != sha256(png):
                    bad_hash.append(f"{pose['sceneId']}/{pass_name}")
                    ok = False
                if png_size(png) != (expected["framebuffer"]["width"], expected["framebuffer"]["height"]):
                    bad_size.append(f"{pose['sceneId']}/{pass_name}: {png_size(png)}")
                    ok = False
                if not pose_matches(meta):
                    bad_pose.append(f"{pose['sceneId']}/{pass_name}")
                    ok = False
                if "depth" in meta:
                    depth = scene / meta["depth"]["file"]
                    if depth.is_file():
                        files.append(depth)
                    else:
                        no_depth.append(pose["sceneId"])
            if args.repeat_pass:
                a, b = scene / f"{args.target_pass}.png", scene / f"{args.repeat_pass}.png"
                if a.is_file() and b.is_file():
                    rep_pairs.append((a, b))
                    files.append(b)
            if ok:
                n_ok += 1
                biomes[pose.get("biome")] += 1
        complete += n_ok
        n = len(plan["poses"])
        status = "PASS" if not missing else ("WARN" if len(missing) <= 0.01 * n * len(passes) else "FAIL")
        report.add("completeness", status, f"{plan_name}: {n_ok}/{n} poses complete", missing)
        if bad_hash or bad_size or no_depth:
            report.add(
                "integrity",
                "FAIL",
                f"{plan_name}: {len(bad_hash)} hash, {len(bad_size)} size, {len(no_depth)} depth problems",
                bad_hash + bad_size + [f"{s}: depth.npz missing" for s in no_depth],
            )
        else:
            report.add("integrity", "PASS", f"{plan_name}: hashes, frame size and depth files OK")
        if bad_pose:
            report.add("pose", "FAIL", f"{plan_name}: {len(bad_pose)} frames off the planned pose", bad_pose)
        else:
            report.add("pose", "PASS", f"{plan_name}: every frame landed on its planned pose")
        plans_out.append(
            {
                "name": plan_name,
                "world": world,
                "scenes": n_ok,
                "poses": n,
                "center": [params.get("centerX"), params.get("centerZ")],
                "radius": params.get("radius"),
                "seed": params.get("seed"),
                "world_seed": plan.get("worldSeed"),
                "passes": passes,
            }
        )

    env = check_environment(report, sweeps, expected)
    gpus = {f"{(s.get('environment') or {}).get('gpu')}" for v in sweeps.values() for s in v}

    determinism = None
    if not args.repeat_pass:
        report.add("determinism", "WARN", "not measured: capture a plan twice and pass --repeat-pass")
    elif not rep_pairs:
        report.add("determinism", "FAIL", f"no scene has both {args.target_pass} and {args.repeat_pass}")
    else:
        psnrs = []
        for a, b in rep_pairs:
            p = compare(load_rgb(a), load_rgb(b))[0]
            psnrs.append(min(p, 100.0))
        thr = expected["min_determinism_db"]
        below = float(np.mean(np.array(psnrs) < thr))
        determinism = {
            "scenes": len(psnrs),
            "median_db": float(np.median(psnrs)),
            "min_db": float(np.min(psnrs)),
            "fraction_below_threshold": below,
        }
        fail_thr = expected["fail_determinism_db"]
        ok = determinism["median_db"] >= thr
        report.add(
            "determinism",
            "PASS" if ok else ("WARN" if determinism["median_db"] >= fail_thr else "FAIL"),
            f"{len(psnrs)} scenes, median {determinism['median_db']:.1f} dB, min {determinism['min_db']:.1f} dB "
            f"(expected {thr} dB or more, fails below {fail_thr} dB)",
        )
        if ok and below > 0.05:
            report.add(
                "determinism", "WARN", f"{below:.0%} of the repeated scenes are below {thr} dB (unloaded terrain?)"
            )
        if len(psnrs) < 20:
            report.add("determinism", "WARN", f"only {len(psnrs)} repeated scenes; 20 or more recommended")

    # File list with hashes: the record points to it, so the hosted copy can be verified later.
    args.out.mkdir(parents=True, exist_ok=True)
    listing = args.out / "files.sha256"
    with listing.open("w", encoding="utf-8") as f:
        for p in sorted(set(files)):
            f.write(f"{sha256(p)}  {p.relative_to(root).as_posix()}\n")
    size_gb = sum(p.stat().st_size for p in set(files)) / 1e9

    worst = report.worst()
    ready = worst != "FAIL" and determinism is not None
    name = args.name or args.plan[0]
    record = {
        "contributor": args.contributor,
        "date": datetime.now(timezone.utc).date().isoformat(),
        "world": {
            "name": plans_out[0]["world"] if plans_out else "",
            "seed": str(plans_out[0].get("world_seed") or "") if plans_out else "",
            "zones": [{"plan": p["name"], "center": p["center"], "radius": p["radius"]} for p in plans_out],
            "biomes": sorted(b for b in biomes if b),
        },
        "environment": {
            "gpu": sorted(gpus),
            "opengl": env.get("opengl"),
            "os": env.get("os"),
            "java": env.get("java"),
            "minecraft": env.get("minecraft_version"),
            "fabric_loader": (env.get("loader") or {}).get("version"),
            "capture_mod": env.get("capture_mod_version"),
            "shader": expected["shader_pack"]["id"],
            "shader_sha256_12": expected["shader_pack"]["sha256"][:12],
        },
        "capture": {
            "plans": [{"name": p["name"], "scenes": p["scenes"], "passes": p["passes"]} for p in plans_out],
            "determinism": determinism,
        },
        "validation": {
            "verdict": "ready" if ready else ("failed" if worst == "FAIL" else "incomplete"),
            "checks": {k: v["status"] for k, v in report.checks.items()},
        },
        "data": {"url": "", "sha256_files_list": sha256(listing), "size_gb": round(size_gb, 2)},
        "rights": "I confirm I have the right to share this data.",
    }
    (args.out / "report.json").write_text(
        json.dumps(
            {"plans": plans_out, "scenes_complete": complete, "poses": total, "checks": report.checks}, indent=2
        ),
        encoding="utf-8",
    )
    (args.out / f"{args.contributor}_{name}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")

    print(f"{complete}/{total} poses complete, {len(set(files))} files, {size_gb:.2f} GB")
    for check, c in report.checks.items():
        print(f"[{c['status']:4s}] {check}: " + "; ".join(c["summary"]))
        for d in c["details"][:5]:
            print(f"         {d}")
        if len(c["details"]) > 5:
            print(f"         ... {len(c['details']) - 5} more in report.json")
    print(f"\nverdict: {record['validation']['verdict']}")
    print(f"wrote {args.out / 'report.json'}, {listing} and {args.out / f'{args.contributor}_{name}.json'}")
    return 0 if ready else (1 if worst == "FAIL" else 2)


if __name__ == "__main__":
    sys.exit(main())
