"""Difficulty presets for the synthetic session generator.

Each preset is a set of ranges. A session draws its own values from these
ranges with its seed, so two sessions of the same preset differ but stay
within the same difficulty. Lengths are in millimetres on the card, angles in
degrees, image sizes in pixels.
"""

from __future__ import annotations

from dataclasses import dataclass, field

Range = tuple[float, float]


@dataclass(frozen=True)
class Preset:
    name: str

    # --- Session plan -------------------------------------------------------
    aim_points: int                 # how many aim points on the card
    aim_style: str                  # "sticker", "hand_drawn" or "mixed"
    groups_per_round: int
    rounds: int
    shots_per_group: tuple[int, ...]  # choices, picked per round

    # --- Shooting -----------------------------------------------------------
    # Spread of one shot around the group centre at 12 m (one standard
    # deviation, mm). Drawn per session (the shooter's skill) and then varied
    # a little per group. Scaled with distance for 25 m rounds.
    skill_sigma_mm: Range
    group_sigma_jitter: float        # per-group multiplier range is 1 ± this
    poi_offset_sigma_mm: float       # shooter/gun point-of-impact offset (per session)
    group_offset_sigma_mm: float     # extra wander of each group's centre
    vertical_stretch: Range          # groups a bit taller than wide (multiplier on y)
    flyer_prob: float                # per shot
    flyer_sigma_factor: Range        # flyer distance, in group sigmas
    double_prob: float               # per shot: deliberately overlap a hole from the same group
    through_prob: float              # per shot: go through a hole from an earlier round
    invisible_prob: float            # per shot: exactly through an earlier hole (can't be seen)
    fired_mismatch_prob: float       # per group: fired one fewer or one more than planned
    unlooped_flyer_prob: float       # per flyer: left outside the loop
    distances_m: tuple[tuple[float, float], ...]   # (distance, weight)
    calibres: tuple[tuple[str, float, float], ...]  # (label, hole diameter mm, weight)

    # --- Marking ------------------------------------------------------------
    loop_margin_mm: Range            # gap between holes and the loop line
    loop_wobble_mm: float            # hand-drawn wobble, outward only
    loop_stroke_mm: Range            # paint marker width
    open_loop_prob: float            # loop not quite closed

    # --- Backing (what shows through the holes; see docs/SPEC.md) ----------
    # Share of the area behind the card that is purple board, white foam and
    # dirt. With backing_uniform one of the three, picked by the seed, fills it.
    backing_mix: tuple[float, float, float]
    backing_patch_mm: float          # typical size of a shot-out patch
    backing_uniform: bool

    # --- Phone photo --------------------------------------------------------
    image_sizes: tuple[tuple[int, int], ...]   # (width, height) choices
    card_fill: Range                 # card size as a share of the frame
    centre_offset: float             # card centre wander, as a share of the frame
    rotation_deg: float              # in-plane turn, ±
    tilt_deg: float                  # camera tilt about each card axis, ±
    curl_mm: Range                   # paper bulge at the centre
    lighting_min: Range              # brightness falls from 1.0 to this across the card
    blur_sigma_px: Range
    noise_sigma: Range
    jpeg_quality: tuple[int, int]
    white_balance: float             # per-channel gain wander, ±

    notes: str = field(default="", compare=False)


EASY = Preset(
    name="easy",
    aim_points=4, aim_style="sticker", groups_per_round=2, rounds=3, shots_per_group=(5,),
    skill_sigma_mm=(5.0, 8.0), group_sigma_jitter=0.1, poi_offset_sigma_mm=6.0,
    group_offset_sigma_mm=3.0, vertical_stretch=(1.0, 1.1),
    flyer_prob=0.0, flyer_sigma_factor=(3.0, 4.0), double_prob=0.0, through_prob=0.0,
    invisible_prob=0.0, fired_mismatch_prob=0.0, unlooped_flyer_prob=0.0,
    distances_m=((12.0, 1.0),), calibres=(("9mm", 9.0, 1.0),),
    loop_margin_mm=(6.0, 9.0), loop_wobble_mm=1.5, loop_stroke_mm=(3.0, 3.5), open_loop_prob=0.0,
    backing_mix=(1.0, 1.0, 1.0), backing_patch_mm=150.0, backing_uniform=True,
    image_sizes=((3200, 2400),), card_fill=(0.85, 0.9), centre_offset=0.02,
    rotation_deg=2.0, tilt_deg=3.0, curl_mm=(0.0, 1.0), lighting_min=(0.9, 0.97),
    blur_sigma_px=(0.3, 0.6), noise_sigma=(1.0, 2.0), jpeg_quality=(92, 95), white_balance=0.01,
    notes="Square-on photos, even light, tight groups well apart, no awkward shots, one backing behind the whole card.",
)

REALISTIC = Preset(
    name="realistic",
    aim_points=8, aim_style="sticker", groups_per_round=4, rounds=4, shots_per_group=(5,),
    skill_sigma_mm=(7.0, 14.0), group_sigma_jitter=0.25, poi_offset_sigma_mm=12.0,
    group_offset_sigma_mm=6.0, vertical_stretch=(1.0, 1.35),
    flyer_prob=0.04, flyer_sigma_factor=(3.0, 4.5), double_prob=0.04, through_prob=0.04,
    invisible_prob=0.01, fired_mismatch_prob=0.05, unlooped_flyer_prob=0.25,
    distances_m=((12.0, 0.85), (25.0, 0.15)),
    calibres=(("9mm", 9.0, 0.8), (".22LR", 5.6, 0.2)),
    loop_margin_mm=(3.0, 8.0), loop_wobble_mm=3.0, loop_stroke_mm=(2.0, 4.0), open_loop_prob=0.03,
    backing_mix=(0.3, 0.45, 0.25), backing_patch_mm=120.0, backing_uniform=False,
    image_sizes=((3200, 2400),), card_fill=(0.78, 0.9), centre_offset=0.04,
    rotation_deg=8.0, tilt_deg=10.0, curl_mm=(0.0, 4.0), lighting_min=(0.7, 0.92),
    blur_sigma_px=(0.5, 1.2), noise_sigma=(2.0, 4.0), jpeg_quality=(82, 92), white_balance=0.04,
    notes="What a careful phone photo at the range should look like. Validation targets apply here.",
)

HARD = Preset(
    name="hard",
    aim_points=8, aim_style="mixed", groups_per_round=5, rounds=4, shots_per_group=(5, 5, 10),
    skill_sigma_mm=(10.0, 20.0), group_sigma_jitter=0.35, poi_offset_sigma_mm=18.0,
    group_offset_sigma_mm=10.0, vertical_stretch=(1.0, 1.6),
    flyer_prob=0.08, flyer_sigma_factor=(3.0, 5.0), double_prob=0.08, through_prob=0.08,
    invisible_prob=0.03, fired_mismatch_prob=0.15, unlooped_flyer_prob=0.5,
    distances_m=((12.0, 0.7), (25.0, 0.3)),
    calibres=(("9mm", 9.0, 0.55), (".22LR", 5.6, 0.25), (".45ACP", 11.4, 0.2)),
    loop_margin_mm=(1.5, 7.0), loop_wobble_mm=4.0, loop_stroke_mm=(1.5, 4.5), open_loop_prob=0.15,
    backing_mix=(0.25, 0.4, 0.35), backing_patch_mm=50.0, backing_uniform=False,
    image_sizes=((3200, 2400), (2400, 3200)), card_fill=(0.7, 0.92), centre_offset=0.06,
    rotation_deg=20.0, tilt_deg=18.0, curl_mm=(2.0, 8.0), lighting_min=(0.5, 0.8),
    blur_sigma_px=(1.0, 2.0), noise_sigma=(4.0, 7.0), jpeg_quality=(70, 85), white_balance=0.08,
    notes="Hurried photos, poor light, big overlapping groups, flyers, open loops, mixed calibres, small backing patches so neighbouring holes differ.",
)

PRESETS: dict[str, Preset] = {p.name: p for p in (EASY, REALISTIC, HARD)}


def get(name: str) -> Preset:
    try:
        return PRESETS[name]
    except KeyError:
        raise ValueError(f"unknown preset {name!r}; choose from {sorted(PRESETS)}") from None
