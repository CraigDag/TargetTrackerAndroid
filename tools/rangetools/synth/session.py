"""Generates a whole synthetic session: baseline photo plus one photo per round,
each with its ground-truth JSON next to it."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .. import geometry
from ..groundtruth import SCHEMA_VERSION, GroundTruth, ImageInfo
from ..groundtruth import write as write_gt
from . import presets as presets_mod
from .photo import Camera, random_camera, random_look, take_photo
from .plan import SessionPlan, plan_session
from .render import Backing, FlatCard

# The larger set used for validation (Phase 4): (preset, seed, aim style, scenario).
# Generated into out/, not checked in; regenerate with `validation-set`. The
# validation also runs the checked-in FIXED_SET below (which includes an edge session).
VALIDATION_SET: list[tuple[str, int, str, str | None]] = (
    [("easy", s, "sticker", None) for s in (111, 112, 113)]
    + [("realistic", s, "sticker", None) for s in (211, 212, 213, 214)]
    + [("realistic", s, "hand_drawn", None) for s in (221, 222, 223, 224)]
    + [("realistic", 231, "sticker", "edge"), ("realistic", 232, "sticker", "edge"), ("realistic", 234, "hand_drawn", "edge")]
    + [("realistic", s, "sticker", "same_colour") for s in (241, 242)]
    + [("realistic", s, "sticker", "on_sticker") for s in (251, 252)]
    + [("hard", s, "mixed", None) for s in (311, 312, 313)]
)

# The small fixed set checked into test_data/synthetic/ (preset, seed, aim style, scenario).
FIXED_SET: list[tuple[str, int, str, str | None]] = [
    ("easy", 101, "sticker", None),
    ("realistic", 202, "sticker", None),
    ("realistic", 203, "hand_drawn", None),
    ("hard", 303, "mixed", None),
    ("realistic", 233, "hand_drawn", "edge"),
]


@dataclass
class Photo:
    round: int
    backing: Backing
    image_path: Path
    json_path: Path
    truth: GroundTruth
    camera: Camera


def session_id(preset: str, seed: int, aim_style: str, scenario: str | None = None) -> str:
    extra = f"_{scenario}" if scenario else ""
    return f"{preset}_{aim_style}{extra}_s{seed:04d}"


def generate(preset_name: str, seed: int, out_root: Path, aim_style: str | None = None,
             rounds: int | None = None, scenario: str | None = None) -> tuple[SessionPlan, list[Photo]]:
    preset = presets_mod.get(preset_name)
    if rounds is not None:
        from dataclasses import replace
        preset = replace(preset, rounds=rounds)
    aim_style = aim_style or preset.aim_style
    geo = geometry.load()
    plan = plan_session(preset, seed, aim_style, scenario)
    preset = plan.preset
    sid = session_id(preset.name, seed, aim_style, scenario)
    folder = Path(out_root) / sid
    folder.mkdir(parents=True, exist_ok=True)

    card = FlatCard(geo, seed)
    backing = Backing(seed, preset.backing_mix, preset.backing_patch_mm, preset.backing_uniform,
                      (geo.paper_width_mm / 2, geo.paper_height_mm / 2))
    for aim in plan.aim_points:
        card.draw_aim_point(aim, plan.shape_seeds[f"aim{aim.number}"])

    photos: list[Photo] = []
    for r in range(0, preset.rounds + 1):
        rp = plan.rounds[r - 1] if r > 0 else None
        if rp is not None:
            for shot in rp.shots:
                card.cut_hole(shot, plan.shape_seeds[shot.id])
            for loop in rp.loops:
                card.draw_loop(loop, plan.shape_seeds[f"loop_{loop.group_id}"])
        cam = random_camera(preset, geo, np.random.default_rng([seed, 5, r]))
        look = random_look(preset, np.random.default_rng([seed, 6, r]))
        jpeg, dist = take_photo(card, backing, cam, look, geo, np.random.default_rng([seed, 7, r]))
        name = "baseline" if r == 0 else f"round_{r:02d}"
        img_path = folder / f"{name}.jpg"
        img_path.write_bytes(jpeg)
        done = [x for x in plan.rounds if x.round <= r]
        gt = GroundTruth(
            schema_version=SCHEMA_VERSION,
            session_id=sid,
            preset=preset.name,
            seed=seed,
            image=ImageInfo(file=img_path.name, width_px=cam.width, height_px=cam.height),
            round=r,
            round_info=rp.info if rp else None,
            aim_points=plan.aim_points,
            groups=[g for x in done for g in x.groups],
            shots=[s for x in done for s in x.shots],
            loops=[lp for x in done for lp in x.loops],
            distortion=dist,
        )
        json_path = folder / f"{name}.json"
        write_gt(gt, json_path)
        photos.append(Photo(r, backing, img_path, json_path, gt, cam))
    return plan, photos
