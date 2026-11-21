import json

import pytest

from rangetools import geometry
from rangetools.paths import CARD_GEOMETRY_JSON


def test_loads_geometry_exported_from_kotlin():
    g = geometry.load()
    assert g.rings == list(range(1, 11))
    assert g.ring_diameters_mm[10] == g.ten_ring_diameter_mm
    assert g.ring_diameters_mm[g.black_outer_ring] == g.black_diameter_mm


def test_ring_spacing_is_consistent():
    g = geometry.load()
    for ring in range(1, 10):
        assert g.ring_diameters_mm[ring] - g.ring_diameters_mm[ring + 1] == g.ring_diameter_step_mm


@pytest.mark.parametrize("radius,ring", [(0, 10), (25, 10), (25.1, 9), (100, 7), (250, 1), (250.1, 0)])
def test_ring_at_matches_kotlin_rule(radius, ring):
    assert geometry.load().ring_at(radius) == ring


def test_unexpected_keys_are_rejected(tmp_path):
    raw = json.loads(CARD_GEOMETRY_JSON.read_text())
    raw["extra"] = 1
    bad = tmp_path / "g.json"
    bad.write_text(json.dumps(raw))
    with pytest.raises(ValueError):
        geometry.load(bad)
