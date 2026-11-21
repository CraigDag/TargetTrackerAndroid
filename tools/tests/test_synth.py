"""Tests for the synthetic session generator (rangetools.synth)."""

from __future__ import annotations

import math
from collections import Counter

import cv2
import numpy as np
import pytest

from rangetools import geometry, groundtruth
from rangetools.paths import SYNTHETIC
from rangetools.synth import presets, style
from rangetools.synth.photo import random_camera
from rangetools.synth.plan import plan_session, point_in_loop
from rangetools.synth.session import FIXED_SET, generate, session_id

GEO = geometry.load()


def _pt(p):
    return np.array([p.x, p.y])


@pytest.mark.parametrize("name", sorted(presets.PRESETS))
def test_plans_are_repeatable_per_seed(name):
    a = plan_session(presets.get(name), 7)
    b = plan_session(presets.get(name), 7)
    c = plan_session(presets.get(name), 8)
    assert [s.centre_mm for r in a.rounds for s in r.shots] == [s.centre_mm for r in b.rounds for s in r.shots]
    assert [s.centre_mm for r in a.rounds for s in r.shots] != [s.centre_mm for r in c.rounds for s in r.shots]


@pytest.mark.parametrize("name,seed", [(n, s) for n in sorted(presets.PRESETS) for s in range(6)])
def test_session_plan_follows_the_marking_convention(name, seed):
    preset = presets.get(name)
    plan = plan_session(preset, seed)
    assert len(plan.rounds) == preset.rounds
    aims = {a.number: a for a in plan.aim_points}
    for rp in plan.rounds:
        # One colour per group per round, from the spec palette.
        colours = [g.loop_colour for g in rp.groups]
        assert len(set(colours)) == len(colours)
        assert set(colours) <= set(style.LOOP_PALETTE)
        for g in rp.groups:
            aim = aims[g.aim_point_number]
            if aim.kind == "hand_drawn":  # loop colour matches its aim point
                assert style.LOOP_PALETTE[g.loop_colour] == aim.colour
            fired = [s for s in rp.shots if s.id.startswith(g.id + "s")]
            assert len(fired) == g.fired_shots
            assert abs(g.fired_shots - g.expected_shots) <= 1
        for loop in rp.loops:
            looped = [s for s in rp.shots if s.group_id == loop.group_id]
            path = np.array([[p.x, p.y] for p in loop.path_mm])
            for s in looped:
                if loop.closed:
                    assert point_in_loop(s.centre_mm, loop), f"{s.id} not inside its loop"
                # The line stays clear of the hole edge.
                gap = np.min(np.linalg.norm(path - _pt(s.centre_mm), axis=1)) - s.diameter_mm / 2 - loop.stroke_width_mm / 2
                assert gap > 0.5, f"loop touches {s.id} (gap {gap:.2f} mm)"
        for s in rp.shots:
            assert abs(s.centre_mm.x) < GEO.paper_width_mm / 2 and abs(s.centre_mm.y) < GEO.paper_height_mm / 2
            if s.group_id is None:  # left unlooped: really outside its loop
                gid = s.id.rsplit("s", 1)[0]
                loop = next(lp for lp in rp.loops if lp.group_id == gid)
                assert s.flyer and not point_in_loop(s.centre_mm, loop)


def test_overlaps_and_hidden_shots_are_recorded_consistently():
    plan = plan_session(presets.get("hard"), 3)
    shots = plan.all_shots(99)
    by_id = {s.id: s for s in shots}
    kinds = Counter()
    for k, s in enumerate(shots):
        if s.through_shot_id is not None:
            host = by_id[s.through_shot_id]
            assert shots.index(host) < k and not host.invisible
            d = np.linalg.norm(_pt(s.centre_mm) - _pt(host.centre_mm))
            assert d < (s.diameter_mm + host.diameter_mm) / 2
            kinds["invisible" if s.invisible else "through"] += 1
        else:
            assert not s.invisible
    assert kinds["through"] > 0


def test_hand_drawn_and_mixed_aim_points():
    hand = plan_session(presets.get("realistic"), 1, "hand_drawn")
    assert {a.kind for a in hand.aim_points} == {"hand_drawn"}
    mixed = plan_session(presets.get("hard"), 1, "mixed")
    assert {a.kind for a in mixed.aim_points} == {"hand_drawn", "sticker"}
    assert all(a.colour == style.STICKER_RED for a in mixed.aim_points if a.kind == "sticker")


def test_camera_keeps_the_card_in_frame_and_inverts_exactly():
    rng = np.random.default_rng(5)
    for _ in range(10):
        cam = random_camera(presets.get("hard"), GEO, rng)
        x = rng.uniform(-270, 270, 200)
        y = rng.uniform(-255, 255, 200)
        u, v = cam.project(x, y)
        assert (u > 0).all() and (u < cam.width).all() and (v > 0).all() and (v < cam.height).all()
        bx, by = cam.unproject(u, v)
        assert np.max(np.hypot(bx - x, by - y)) < 0.01
        # Without curl the homography is the exact map.
        cam.curl_mm = 0.0
        H = cam.homography()
        p = np.c_[x, y, np.ones_like(x)] @ H.T
        fu, fv = cam.project(x, y)
        assert np.max(np.hypot(p[:, 0] / p[:, 2] - fu, p[:, 1] / p[:, 2] - fv)) < 1e-6


def test_generated_photo_shows_holes_where_the_truth_says(tmp_path):
    plan, photos = generate("realistic", 11, tmp_path, rounds=1)
    assert [p.image_path.name for p in photos] == ["baseline.jpg", "round_01.jpg"]
    last = photos[-1]
    gt = groundtruth.read(last.json_path)
    assert gt.round == 1 and len(gt.new_shots()) == sum(g.fired_shots for g in gt.groups)
    img = cv2.imread(str(last.image_path)).astype(float).mean(axis=2)
    base = cv2.imread(str(photos[0].image_path)).astype(float).mean(axis=2)
    assert img.shape == (gt.image.height_px, gt.image.width_px)
    aims = [(_pt(a.centre_mm), a.diameter_mm / 2) for a in gt.aim_points]

    def patch(photo, image, c):
        u, v = photo.camera.project(c[0], c[1])
        return image[int(v) - 2 : int(v) + 3, int(u) - 2 : int(u) + 3].mean()

    checked = 0
    for s in gt.new_shots():
        c = _pt(s.centre_mm)
        if s.invisible or math.hypot(*c) < GEO.black_diameter_mm / 2 + 8:
            continue
        if any(np.linalg.norm(c - a) < ar + 1.5 for a, ar in aims):
            continue  # centre on a red sticker
        before, after = patch(photos[0], base, c), patch(last, img, c)
        # Same spot on the card: beige paper before, darker backing after.
        assert after + 25 < before, f"{s.id}: before {before:.0f}, after {after:.0f}"
        checked += 1
    assert checked >= 5


def test_fixed_set_is_checked_in_and_matches_the_generator():
    for preset, seed, aim_style in FIXED_SET:
        folder = SYNTHETIC / session_id(preset, seed, aim_style)
        files = sorted(folder.glob("*.json"))
        assert files, f"missing {folder}; run python -m rangetools.synth fixed-set"
        last = groundtruth.read(max(files, key=lambda p: groundtruth.read(p).round))
        for f in files:
            assert (folder / groundtruth.read(f).image.file).is_file()
        # The plan (holes, loops) regenerates exactly from the seed.
        plan = plan_session(presets.get(preset), seed, aim_style)
        assert [s for r in plan.rounds for s in r.shots] == last.shots
        assert [lp for r in plan.rounds for lp in r.loops] == last.loops
        assert plan.aim_points == last.aim_points
