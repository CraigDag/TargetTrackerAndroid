import json

import pytest

from rangetools import groundtruth
from rangetools.paths import EXAMPLE_GROUND_TRUTH


def test_reads_the_shared_example_file():
    gt = groundtruth.read(EXAMPLE_GROUND_TRUTH)
    assert gt.round == 1
    assert gt.round_info.distance_m == 12.0
    assert [a.kind for a in gt.aim_points] == ["sticker", "hand_drawn"]
    assert gt.aim_points[0].centre_mm == groundtruth.PointMm(-127.3, 127.3)
    assert len(gt.new_shots()) == 6
    flyer = gt.shots[-1]
    assert flyer.flyer and flyer.group_id is None
    assert gt.shots[4].through_shot_id == "r1g2s1"
    assert [loop.colour for loop in gt.loops] == ["orange", "yellow"]


def test_round_trip_keeps_every_key():
    original = json.loads(EXAMPLE_GROUND_TRUTH.read_text())
    again = json.loads(groundtruth.read(EXAMPLE_GROUND_TRUTH).to_json())
    assert again == original


def test_unknown_keys_are_rejected():
    raw = json.loads(EXAMPLE_GROUND_TRUTH.read_text())
    raw["shots"][0]["surprise"] = 1
    with pytest.raises(ValueError, match="unknown keys"):
        groundtruth.parse(json.dumps(raw))


def test_missing_keys_are_rejected():
    raw = json.loads(EXAMPLE_GROUND_TRUTH.read_text())
    del raw["distortion"]["curl_mm"]
    with pytest.raises(ValueError, match="missing key"):
        groundtruth.parse(json.dumps(raw))


def test_other_schema_versions_are_rejected():
    raw = json.loads(EXAMPLE_GROUND_TRUTH.read_text())
    raw["schema_version"] = 99
    with pytest.raises(ValueError, match="schema version"):
        groundtruth.parse(json.dumps(raw))
