"""Edge-case scenarios for validation (Phase 4), laid on top of a preset.

Each scenario pushes one awkward case that the plain presets produce only
now and then, so the validation report has enough of them to measure:

- ``edge``: three aim points sit close to the paper edge, so their loops run
  up to or off the paper (docs/SPEC.md, Marking convention rule 6 is a
  request, not a promise).
- ``same_colour``: when an aim point is shot again in a later round, its new
  loop uses the same colour as the last loop there (what hand-drawn aim
  points always do, and what rule 7 asks shooters to avoid with stickers).
- ``on_sticker``: tight groups centred on the aim points, so the stickers get
  shot out and later holes land on already shot-out stickers.
"""

from __future__ import annotations

from dataclasses import replace

from ..groundtruth import AimPoint, PointMm
from .presets import Preset

SCENARIOS = ("edge", "same_colour", "on_sticker")

# Aim point number -> new centre (mm) for "edge": about 25-35 mm from the paper
# edge (the paper is 550 x 520 mm), so a group's loop reaches the edge.
_EDGE_PLACES = {5: (0.0, 228.0), 8: (-243.0, 0.0), 2: (232.0, 212.0)}


def check(name: str | None) -> None:
    if name is not None and name not in SCENARIOS:
        raise ValueError(f"unknown scenario {name!r}; choose from {list(SCENARIOS)}")


def adjust_preset(preset: Preset, scenario: str | None) -> Preset:
    if scenario == "on_sticker":
        return replace(preset, skill_sigma_mm=(4.0, 6.5), poi_offset_sigma_mm=1.5, group_offset_sigma_mm=1.5,
                       vertical_stretch=(1.0, 1.1), flyer_prob=0.0, distances_m=((12.0, 1.0),))
    return preset


def adjust_aim_points(aims: list[AimPoint], scenario: str | None) -> list[AimPoint]:
    if scenario != "edge":
        return aims
    out = []
    for a in aims:
        if a.number in _EDGE_PLACES:
            x, y = _EDGE_PLACES[a.number]
            a = replace(a, centre_mm=PointMm(x, y))
        out.append(a)
    return out


def keep_colour(scenario: str | None) -> bool:
    """Whether a re-shot aim point reuses the colour of its last loop."""
    return scenario == "same_colour"


def distance_to_edge_mm(x: float, y: float, half_w: float, half_h: float) -> float:
    return min(half_w - abs(x), half_h - abs(y))

