"""Card geometry, read from test_data/card_geometry.json.

That file is generated from CardGeometry in the Kotlin core
(`rangetarget geometry --out test_data/card_geometry.json`), which is the only
place the card's dimensions are defined. Nothing here hard-codes them.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .paths import CARD_GEOMETRY_JSON


@dataclass(frozen=True)
class CardGeometry:
    name: str
    ten_ring_diameter_mm: float
    ring_diameter_step_mm: float
    ring_diameters_mm: dict[int, float]
    black_outer_ring: int
    black_diameter_mm: float
    paper_width_mm: float
    paper_height_mm: float

    def ring_radius_mm(self, ring: int) -> float:
        return self.ring_diameters_mm[ring] / 2.0

    @property
    def rings(self) -> list[int]:
        return sorted(self.ring_diameters_mm)

    def ring_at(self, radius_mm: float) -> int:
        """Ring a point at this distance from the centre lies in; 0 outside the 1 ring."""
        for ring in sorted(self.ring_diameters_mm, reverse=True):
            if radius_mm <= self.ring_radius_mm(ring):
                return ring
        return 0


def load(path: Path = CARD_GEOMETRY_JSON) -> CardGeometry:
    raw = json.loads(Path(path).read_text())
    expected = set(CardGeometry.__dataclass_fields__)
    if set(raw) != expected:
        raise ValueError(f"card geometry keys differ from expected: {sorted(set(raw) ^ expected)}")
    raw["ring_diameters_mm"] = {int(k): float(v) for k, v in raw["ring_diameters_mm"].items()}
    return CardGeometry(**raw)
