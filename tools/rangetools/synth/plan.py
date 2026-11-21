"""What happens in a synthetic session, before anything is drawn.

Places the aim points, fires every group of every round, and draws the loops,
all as numbers in the card's mm frame. The images are rendered from this plan
afterwards (see render.py), so the ground truth and the pictures can't
disagree.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy.spatial import ConvexHull

from .. import geometry
from ..groundtruth import AimPoint, Group, Loop, PointMm, RoundInfo, Shot
from . import scenarios, style
from .presets import Preset

AIM_DIAMETER_MM = 30.0  # aim point size at 12 m (docs/SPEC.md, Aim points)

# Spec layout for 8 aim points at 12 m: N/E/S/W about 180 mm from the centre,
# diagonals about 250 mm towards the corners. Shooting order NW, NE, SE, SW,
# N, E, S, W (the spec's example). Angles in degrees, anticlockwise from +x.
LAYOUT_8 = [(135, 250), (45, 250), (-45, 250), (-135, 250), (90, 180), (0, 180), (-90, 180), (180, 180)]


@dataclass
class RoundPlan:
    round: int
    info: RoundInfo
    groups: list[Group]
    shots: list[Shot]
    loops: list[Loop]


@dataclass
class SessionPlan:
    preset: Preset
    seed: int
    aim_points: list[AimPoint]
    rounds: list[RoundPlan]
    # Random seeds for drawing each item's shape (hole outline, hand wobble),
    # kept out of the ground truth.
    shape_seeds: dict[str, int] = field(default_factory=dict)

    def all_shots(self, up_to_round: int) -> list[Shot]:
        return [s for r in self.rounds if r.round <= up_to_round for s in r.shots]


def _rng(seed: int, *parts: int) -> np.random.Generator:
    """An independent random stream per purpose, so changing one part of the
    generator doesn't reshuffle everything else."""
    return np.random.default_rng([seed, *parts])


def _weighted(rng: np.random.Generator, items, weights):
    w = np.asarray(weights, dtype=float)
    return items[int(rng.choice(len(items), p=w / w.sum()))]


def _pt(v) -> PointMm:
    return PointMm(round(float(v[0]), 3), round(float(v[1]), 3))


def _arr(p: PointMm) -> np.ndarray:
    return np.array([p.x, p.y])


# ---------------------------------------------------------------------------
# Aim points


def place_aim_points(preset: Preset, seed: int, aim_style: str) -> list[AimPoint]:
    rng = _rng(seed, 1)
    n = preset.aim_points
    layout = LAYOUT_8[:n] if n <= 8 else LAYOUT_8 + [(a + 22.5, 215) for a, _ in LAYOUT_8][: n - 8]
    if aim_style == "sticker":
        kinds = ["sticker"] * n
    elif aim_style == "hand_drawn":
        kinds = ["hand_drawn"] * n
    elif aim_style == "mixed":
        kinds = ["sticker" if k % 2 == 0 else "hand_drawn" for k in range(n)]
    else:
        raise ValueError(f"unknown aim style {aim_style!r}")

    colours = _hand_drawn_colours(preset, kinds, rng)
    aims = []
    for k, ((ang, rad), kind) in enumerate(zip(layout, kinds)):
        jitter = 4.0 if kind == "sticker" else 8.0
        a = math.radians(ang)
        c = np.array([rad * math.cos(a), rad * math.sin(a)]) + rng.normal(0, jitter, 2)
        size = AIM_DIAMETER_MM if kind == "sticker" else AIM_DIAMETER_MM * rng.uniform(0.9, 1.15)
        colour = style.STICKER_RED if kind == "sticker" else style.LOOP_PALETTE[colours[k]]
        aims.append(AimPoint(number=k + 1, kind=kind, colour=colour, centre_mm=_pt(c), diameter_mm=round(size, 2)))
    return aims


def _round_windows(preset: Preset) -> list[list[int]]:
    """Aim point indices fired at in each round."""
    n, g = preset.aim_points, preset.groups_per_round
    return [[(r * g + k) % n for k in range(g)] for r in range(preset.rounds)]


def _hand_drawn_colours(preset: Preset, kinds: list[str], rng: np.random.Generator) -> dict[int, str]:
    """Colours for hand-drawn aim points, such that no round needs one colour
    twice (each group's loop matches its aim point's colour)."""
    names = list(style.LOOP_PALETTE)
    rng.shuffle(names)
    hand = [k for k, kind in enumerate(kinds) if kind == "hand_drawn"]
    windows = [[k for k in w if kinds[k] == "hand_drawn"] for w in _round_windows(preset)]
    if preset.groups_per_round > len(names):
        raise ValueError("more groups per round than loop colours")
    chosen: dict[int, str] = {}

    def ok(k: int, c: str) -> bool:
        return all(chosen.get(j) != c for w in windows if k in w for j in w if j != k)

    def solve(i: int) -> bool:
        if i == len(hand):
            return True
        k = hand[i]
        # Prefer the least-used colours so the card stays varied.
        for c in sorted(names, key=lambda c: list(chosen.values()).count(c)):
            if ok(k, c):
                chosen[k] = c
                if solve(i + 1):
                    return True
                del chosen[k]
        return False

    if not solve(0):
        raise ValueError("can't colour the hand-drawn aim points without repeating a colour in a round")
    return chosen


# ---------------------------------------------------------------------------
# Shooting


def plan_session(preset: Preset, seed: int, aim_style: str | None = None, scenario: str | None = None) -> SessionPlan:
    geo = geometry.load()
    scenarios.check(scenario)
    preset = scenarios.adjust_preset(preset, scenario)
    aim_style = aim_style or preset.aim_style
    aims = scenarios.adjust_aim_points(place_aim_points(preset, seed, aim_style), scenario)
    plan = SessionPlan(preset=preset, seed=seed, aim_points=aims, rounds=[])
    for k, a in enumerate(aims):
        plan.shape_seeds[f"aim{a.number}"] = int(_rng(seed, 2, k).integers(2**31))

    srng = _rng(seed, 3)
    skill = srng.uniform(*preset.skill_sigma_mm)
    poi = srng.normal(0, preset.poi_offset_sigma_mm, 2)
    stretch = srng.uniform(*preset.vertical_stretch)
    colour_of = {a.number: name for a in aims for name, hexc in style.LOOP_PALETTE.items()
                 if a.kind == "hand_drawn" and a.colour == hexc}

    windows = _round_windows(preset)
    last_colour: dict[int, str] = {}
    for r, window in enumerate(windows, start=1):
        rng = _rng(seed, 4, r)
        distance = _weighted(rng, [d for d, _ in preset.distances_m], [w for _, w in preset.distances_m])
        cal = _weighted(rng, list(preset.calibres), [c[2] for c in preset.calibres])
        info = RoundInfo(distance_m=float(distance), hole_diameter_mm=float(cal[1]), calibre=cal[0])
        expected = int(rng.choice(preset.shots_per_group))
        scale = distance / 12.0

        # Loop colours: hand-drawn aim points fix theirs, the rest take what's left.
        aims_now = [aims[k] for k in window]
        fixed = {a.number: colour_of[a.number] for a in aims_now if a.number in colour_of}
        if scenarios.keep_colour(scenario):
            for a in aims_now:
                if a.number not in fixed and a.number in last_colour and last_colour[a.number] not in fixed.values():
                    fixed[a.number] = last_colour[a.number]
        free = [c for c in style.LOOP_PALETTE if c not in fixed.values()]
        rng.shuffle(free)
        loop_colours = {a.number: fixed.get(a.number) or free.pop() for a in aims_now}
        last_colour.update(loop_colours)

        rp = RoundPlan(round=r, info=info, groups=[], shots=[], loops=[])
        earlier = plan.all_shots(r - 1)
        for g, aim in enumerate(aims_now, start=1):
            gid = f"r{r}g{g}"
            fired = expected
            if rng.random() < preset.fired_mismatch_prob:
                fired += -1 if rng.random() < 0.7 else 1
            sigma = skill * rng.uniform(1 - preset.group_sigma_jitter, 1 + preset.group_sigma_jitter) * scale
            centre = _arr(aim.centre_mm) + (poi + rng.normal(0, preset.group_offset_sigma_mm, 2)) * scale
            shots = _fire_group(rng, preset, geo, gid, r, centre, sigma, stretch, fired,
                                info.hole_diameter_mm, earlier + rp.shots)
            loop = _draw_loop(rng, preset, gid, r, loop_colours[aim.number], shots, info.hole_diameter_mm)
            _settle_unlooped(rng, preset, shots, loop)
            for s in shots:
                plan.shape_seeds[s.id] = int(rng.integers(2**31))
            plan.shape_seeds[f"loop_{gid}"] = int(rng.integers(2**31))
            rp.groups.append(Group(id=gid, round=r, aim_point_number=aim.number, loop_colour=loop.colour,
                                   expected_shots=expected, fired_shots=fired))
            rp.shots.extend(shots)
            rp.loops.append(loop)
        plan.rounds.append(rp)
    return plan


def _on_paper(geo, p: np.ndarray, r: float) -> bool:
    return abs(p[0]) <= geo.paper_width_mm / 2 - r - 2 and abs(p[1]) <= geo.paper_height_mm / 2 - r - 2


def _fire_group(rng, preset: Preset, geo, gid: str, rnd: int, centre: np.ndarray, sigma: float,
                stretch: float, fired: int, diam: float, earlier: list[Shot]) -> list[Shot]:
    shots: list[Shot] = []
    near = lambda s: np.linalg.norm(_arr(s.centre_mm) - centre) < 3 * sigma  # noqa: E731
    for k in range(1, fired + 1):
        flyer = False
        visible_before = [s for s in earlier + shots if not s.invisible]
        roll = rng.random()
        p = None
        if roll < preset.invisible_prob:
            pool = [s for s in visible_before if near(s) and s.diameter_mm >= diam]
            if pool:
                host = pool[int(rng.integers(len(pool)))]
                p = _arr(host.centre_mm) + rng.normal(0, 0.15, 2)
        elif roll < preset.invisible_prob + preset.through_prob:
            pool = [s for s in visible_before if near(s) and s.round < rnd]
            if pool:
                host = pool[int(rng.integers(len(pool)))]
                p = _offset(rng, host, diam, 0.35, 0.8)
        elif roll < preset.invisible_prob + preset.through_prob + preset.double_prob:
            if shots:
                host = shots[int(rng.integers(len(shots)))]
                p = _offset(rng, host, diam, 0.3, 0.75)
        elif roll < preset.invisible_prob + preset.through_prob + preset.double_prob + preset.flyer_prob:
            a = rng.uniform(0, 2 * math.pi)
            d = rng.uniform(*preset.flyer_sigma_factor) * sigma
            p = centre + d * np.array([math.cos(a), math.sin(a) * stretch])
            flyer = True
        for _ in range(50):
            if p is not None and _on_paper(geo, p, diam / 2):
                break
            p = centre + rng.normal(0, sigma, 2) * np.array([1.0, stretch])
            flyer = False
        p = np.clip(p, [-geo.paper_width_mm / 2 + diam, -geo.paper_height_mm / 2 + diam],
                    [geo.paper_width_mm / 2 - diam, geo.paper_height_mm / 2 - diam])
        shot = Shot(id=f"{gid}s{k}", round=rnd, group_id=gid, centre_mm=_pt(p), diameter_mm=diam, flyer=flyer)
        _mark_overlap(shot, earlier + shots)
        shots.append(shot)
    return shots


def _offset(rng, host: Shot, diam: float, lo: float, hi: float) -> np.ndarray:
    a = rng.uniform(0, 2 * math.pi)
    d = rng.uniform(lo, hi) * (host.diameter_mm + diam) / 2
    return _arr(host.centre_mm) + d * np.array([math.cos(a), math.sin(a)])


INVISIBLE_TOLERANCE_MM = 0.3


def _mark_overlap(shot: Shot, before: list[Shot]) -> None:
    """Record which earlier hole this one overlaps, and whether it is hidden in it."""
    c = _arr(shot.centre_mm)
    r = shot.diameter_mm / 2
    best, best_d = None, math.inf
    for s in before:
        if s.invisible:
            continue
        d = float(np.linalg.norm(c - _arr(s.centre_mm)))
        if d < r + s.diameter_mm / 2 and d < best_d:
            best, best_d = s, d
    if best is not None:
        shot.through_shot_id = best.id
        shot.invisible = best_d + r <= best.diameter_mm / 2 + INVISIBLE_TOLERANCE_MM


# ---------------------------------------------------------------------------
# Loops


def _draw_loop(rng, preset: Preset, gid: str, rnd: int, colour: str, shots: list[Shot], diam: float) -> Loop:
    looped = [s for s in shots if not (s.flyer and rng.random() < preset.unlooped_flyer_prob)]
    for s in shots:
        if s not in looped:
            s.group_id = None  # provisional, settled once the loop exists
    margin = rng.uniform(*preset.loop_margin_mm)
    stroke = rng.uniform(*preset.loop_stroke_mm)
    # Clear the holes by the margin plus half the pen width.
    reach = diam / 2 + margin + stroke / 2
    pts = []
    for s in looped:
        c = _arr(s.centre_mm)
        for a in np.linspace(0, 2 * math.pi, 48, endpoint=False):
            pts.append(c + reach * np.array([math.cos(a), math.sin(a)]))
    pts = np.array(pts)
    hull = pts[ConvexHull(pts).vertices]
    path = _resample_closed(hull, step=2.0)
    centre = path.mean(axis=0)
    n = len(path)

    # Hand-drawn wobble: a few slow bumps, pushed outward only so the line
    # never cuts into the holes.
    t = np.linspace(0, 2 * math.pi, n, endpoint=False)
    bump = np.zeros(n)
    for k in range(2, 6):
        bump += rng.uniform(0, 1) / k * np.cos(k * t + rng.uniform(0, 2 * math.pi))
    bump = (bump - bump.min()) / max(np.ptp(bump), 1e-9) * preset.loop_wobble_mm
    radial = path - centre
    radial /= np.linalg.norm(radial, axis=1, keepdims=True)
    path = path + radial * (bump[:, None] + 0.6)  # +0.6 mm makes up for smoothing corners

    # Start somewhere random, go round either way.
    start = int(rng.integers(n))
    path = np.roll(path, -start, axis=0)
    if rng.random() < 0.5:
        path = path[::-1]

    closed = rng.random() >= preset.open_loop_prob
    if closed:
        # Hand loops usually overshoot the start a little.
        over = int(n * rng.uniform(0.0, 0.06))
        path = np.vstack([path, path[1 : over + 1]]) if over else path
    else:
        gap = int(n * rng.uniform(0.08, 0.16))
        path = path[: n - gap]
    return Loop(group_id=gid, round=rnd, colour=colour, colour_rgb=style.LOOP_PALETTE[colour],
                path_mm=[_pt(p) for p in path], closed=closed, stroke_width_mm=round(stroke, 2))


def _resample_closed(poly: np.ndarray, step: float) -> np.ndarray:
    closed = np.vstack([poly, poly[:1]])
    seg = np.linalg.norm(np.diff(closed, axis=0), axis=1)
    s = np.concatenate([[0], np.cumsum(seg)])
    n = max(12, int(s[-1] / step))
    u = np.linspace(0, s[-1], n, endpoint=False)
    out = np.stack([np.interp(u, s, closed[:, 0]), np.interp(u, s, closed[:, 1])], axis=1)
    # Light smoothing for a hand-drawn curve rather than a polygon.
    k = 3
    pad = np.vstack([out[-k:], out, out[:k]])
    kernel = np.ones(2 * k + 1) / (2 * k + 1)
    return np.stack([np.convolve(pad[:, i], kernel, mode="valid") for i in range(2)], axis=1)


def point_in_loop(p: PointMm, loop: Loop) -> bool:
    """Whether a point is inside the loop's line (open loops are closed with a straight line)."""
    poly = np.array([[q.x, q.y] for q in loop.path_mm])
    x, y = p.x, p.y
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def _settle_unlooped(rng, preset: Preset, shots: list[Shot], loop: Loop) -> None:
    """A flyer chosen to be left out is only 'unlooped' if the loop really misses it."""
    for s in shots:
        if s.group_id is None:
            if point_in_loop(s.centre_mm, loop):
                s.group_id = loop.group_id
