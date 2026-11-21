"""Tests for the validation scorer (rangetools.validate) and edge-case scenarios."""

from __future__ import annotations

import json
from dataclasses import asdict

from rangetools.groundtruth import read
from rangetools.paths import SYNTHETIC
from rangetools.synth import presets, scenarios
from rangetools.synth.plan import plan_session
from rangetools.validate.score import describe, score_session

SESSION = SYNTHETIC / "realistic_sticker_s0202"


def test_folder_names_give_preset_style_and_scenario():
    assert describe("realistic_sticker_s0202") == ("realistic", "sticker", "plain")
    assert describe("realistic_hand_drawn_edge_s0233") == ("realistic", "hand_drawn", "edge")
    assert describe("realistic_sticker_same_colour_s0241") == ("realistic", "sticker", "same_colour")


def test_edge_scenario_puts_loops_at_the_paper_edge():
    plan = plan_session(presets.get("realistic"), 5, "sticker", "edge")
    near = [lp for r in plan.rounds for lp in r.loops
            if min(275 - abs(p.x) for p in lp.path_mm) < 10 or min(260 - abs(p.y) for p in lp.path_mm) < 10]
    assert near


def test_same_colour_scenario_reuses_the_last_colour_at_an_aim_point():
    plan = plan_session(presets.get("realistic"), 5, "sticker", "same_colour")
    by_aim: dict[int, list[str]] = {}
    for r in plan.rounds:
        for g in r.groups:
            by_aim.setdefault(g.aim_point_number, []).append(g.loop_colour)
    assert any(len(c) > 1 and len(set(c)) == 1 for c in by_aim.values())


def test_unknown_scenario_is_refused():
    try:
        scenarios.check("nope")
    except ValueError:
        return
    raise AssertionError("expected ValueError")


def _perfect_result(tmp_path):
    """A session.json that reports exactly the truth, as the app would if it were perfect."""
    rounds = []
    for f in sorted(SESSION.glob("round_*.json")):
        gt = read(f)
        shots = [s for s in gt.shots if s.round == gt.round and not s.invisible]
        groups = [g for g in gt.groups if g.round == gt.round]
        rounds.append({
            "round": gt.round,
            "shots": [{"id": s.id, "group_id": s.group_id, "centre_mm": asdict(s.centre_mm), "flags": []} for s in shots],
            "groups": [{"id": g.id, "aim_point_number": g.aim_point_number, "flags": []} for g in groups],
            "flags": [],
        })
    base = read(SESSION / "baseline.json")
    data = {"baseline": {"aim_points": [{"centre_mm": asdict(a.centre_mm), "number": a.number, "flags": []} for a in base.aim_points]},
            "rounds": rounds}
    out = tmp_path / SESSION.name
    out.mkdir()
    (out / "session.json").write_text(json.dumps(data))
    return out


def test_a_perfect_result_scores_perfectly(tmp_path):
    sc = score_session(SESSION, _perfect_result(tmp_path))
    visible = [s for s in sc.shots if s.visible]
    assert visible and all(s.found and s.right_group and s.error_mm == 0 for s in visible)
    assert sc.false_holes == []
    assert all(a.found and a.number_right for a in sc.aims)
    assert all(lp.read for lp in sc.loops)


def test_a_moved_hole_is_a_miss_plus_a_false_hole(tmp_path):
    out = _perfect_result(tmp_path)
    data = json.loads((out / "session.json").read_text())
    data["rounds"][0]["shots"][0]["centre_mm"]["x"] += 20
    (out / "session.json").write_text(json.dumps(data))
    sc = score_session(SESSION, out)
    assert sum(not s.found for s in sc.shots if s.visible) == 1
    assert len(sc.false_holes) == 1
