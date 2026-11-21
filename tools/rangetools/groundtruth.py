"""Ground-truth files, as documented in docs/ground-truth-schema.md.

Mirrors rangetarget.core.groundtruth.GroundTruth in Kotlin. Loading is strict:
missing or unknown keys raise, so the two sides can't drift apart unnoticed.
"""

from __future__ import annotations

import dataclasses
import json
import typing
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

SCHEMA_VERSION = 1


@dataclass
class PointMm:
    x: float
    y: float


@dataclass
class ImageInfo:
    file: str
    width_px: int
    height_px: int


@dataclass
class RoundInfo:
    distance_m: float
    hole_diameter_mm: float
    calibre: str


@dataclass
class AimPoint:
    number: int
    kind: str  # "sticker" or "hand_drawn"
    colour: str
    centre_mm: PointMm
    diameter_mm: float

    def __post_init__(self) -> None:
        if self.kind not in ("sticker", "hand_drawn"):
            raise ValueError(f"unknown aim point kind {self.kind!r}")


@dataclass
class Group:
    id: str
    round: int
    aim_point_number: Optional[int]
    loop_colour: str
    expected_shots: int
    fired_shots: int


@dataclass
class Shot:
    id: str
    round: int
    group_id: Optional[str]
    centre_mm: PointMm
    diameter_mm: float
    flyer: bool = False
    through_shot_id: Optional[str] = None
    invisible: bool = False


@dataclass
class Loop:
    group_id: str
    round: int
    colour: str
    colour_rgb: str
    path_mm: list[PointMm]
    closed: bool
    stroke_width_mm: float


@dataclass
class Distortion:
    homography_mm_to_px: list[float]
    rotation_deg: float
    tilt_x_deg: float
    tilt_y_deg: float
    curl_mm: float
    curl_axis_deg: float
    lighting_min: float
    blur_sigma_px: float
    noise_sigma: float
    jpeg_quality: int

    def __post_init__(self) -> None:
        if len(self.homography_mm_to_px) != 9:
            raise ValueError("homography_mm_to_px must have 9 numbers")


@dataclass
class GroundTruth:
    schema_version: int
    session_id: str
    preset: str
    seed: int
    image: ImageInfo
    round: int
    round_info: Optional[RoundInfo]
    aim_points: list[AimPoint]
    groups: list[Group]
    shots: list[Shot]
    loops: list[Loop]
    distortion: Distortion

    def __post_init__(self) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise ValueError(
                f"ground truth schema version {self.schema_version} not supported (expected {SCHEMA_VERSION})"
            )

    def new_shots(self) -> list[Shot]:
        """Shots fired in this photo's round."""
        return [s for s in self.shots if s.round == self.round]

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2) + "\n"


def _build(cls: type, data: Any) -> Any:
    """Build a dataclass from plain JSON data, strictly."""
    hints = typing.get_type_hints(cls)
    if not isinstance(data, dict):
        raise ValueError(f"{cls.__name__}: expected an object, got {type(data).__name__}")
    fields = {f.name: f for f in dataclasses.fields(cls)}
    unknown = set(data) - set(fields)
    if unknown:
        raise ValueError(f"{cls.__name__}: unknown keys {sorted(unknown)}")
    kwargs = {}
    for name, f in fields.items():
        if name not in data:
            if f.default is dataclasses.MISSING and f.default_factory is dataclasses.MISSING:
                raise ValueError(f"{cls.__name__}: missing key {name!r}")
            continue
        kwargs[name] = _convert(hints[name], data[name])
    return cls(**kwargs)


def _convert(tp: Any, value: Any) -> Any:
    origin = typing.get_origin(tp)
    if origin is typing.Union:  # Optional[X]
        if value is None:
            return None
        (inner,) = [a for a in typing.get_args(tp) if a is not type(None)]
        return _convert(inner, value)
    if origin is list:
        (inner,) = typing.get_args(tp)
        return [_convert(inner, v) for v in value]
    if dataclasses.is_dataclass(tp):
        return _build(tp, value)
    if tp is float:
        return float(value)
    return value


def parse(text: str) -> GroundTruth:
    return _build(GroundTruth, json.loads(text))


def read(path: Path) -> GroundTruth:
    return parse(Path(path).read_text())


def write(gt: GroundTruth, path: Path) -> None:
    Path(path).write_text(gt.to_json())
