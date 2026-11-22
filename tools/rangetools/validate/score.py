"""Scores one analysed session against its ground truth (Phase 4).

Reads only files: the generator's ground-truth JSON next to each photo, and
the `session.json` the Kotlin `rangetarget session` command wrote. Everything
here is test tooling; nothing is shipped.

Words used below:
- a *true shot* is one the generator fired; it is *visible* unless it went
  exactly through an earlier hole;
- a *found shot* is one the app reported;
- they *match* when their centres are within MATCH_MM (closest pairs first);
- a *clean* true shot doesn't touch any other hole on the card;
- an *edge loop* comes within EDGE_LOOP_MM of the paper edge (or runs off it).
"""

from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np

from .. import geometry
from ..groundtruth import GroundTruth, Loop, Shot
from ..groundtruth import read as read_gt
from ..synth.plan import point_in_loop
from ..synth.scenarios import SCENARIOS

MATCH_MM = 6.0
EDGE_LOOP_MM = 10.0
GEO = geometry.load()


@dataclass
class ShotRow:
    """One true shot, and what the app made of it."""
    session: str
    preset: str
    aim_style: str
    scenario: str
    round: int
    shot_id: str
    group_id: str | None
    x_mm: float
    y_mm: float
    tags: list[str]
    visible: bool
    found: bool = False
    found_id: str | None = None
    error_mm: float | None = None
    right_group: bool | None = None
    given_group: str | None = None
    flagged: bool = False          # the found shot (or its group) carries a flag a person would look at


@dataclass
class FalseRow:
    session: str
    preset: str
    scenario: str
    round: int
    found_id: str
    x_mm: float
    y_mm: float
    group_id: str | None
    flags: list[str]
    near_edge: bool
    on_aim_point: bool


@dataclass
class FlagRow:
    session: str
    preset: str
    scenario: str
    round: int
    code: str
    verdict: str        # "right", "wrong" or "info"
    why: str
    x_mm: float | None
    y_mm: float | None


@dataclass
class LoopRow:
    session: str
    preset: str
    aim_style: str
    scenario: str
    round: int
    group_id: str
    colour: str
    tags: list[str]
    read: bool          # the app found a loop standing for this group
    x_mm: float
    y_mm: float


@dataclass
class AimRow:
    session: str
    preset: str
    aim_style: str
    number: int
    kind: str
    found: bool
    error_mm: float | None
    number_read: int | None
    number_right: bool
    number_flagged: bool


@dataclass
class SessionScore:
    session: str
    preset: str
    aim_style: str
    scenario: str
    rounds: int
    shots: list[ShotRow] = field(default_factory=list)
    false_holes: list[FalseRow] = field(default_factory=list)
    flags: list[FlagRow] = field(default_factory=list)
    aims: list[AimRow] = field(default_factory=list)
    loops: list[LoopRow] = field(default_factory=list)
    seconds: float | None = None

    def to_json(self) -> dict:
        return asdict(self)


def describe(name: str) -> tuple[str, str, str]:
    """(preset, aim style, scenario) from a session folder name like realistic_sticker_edge_s0231."""
    parts = name.split("_")
    preset = parts[0]
    rest = "_".join(parts[1:-1])
    scenario = "plain"
    for sc in SCENARIOS:
        if rest.endswith("_" + sc):
            scenario, rest = sc, rest[: -len(sc) - 1]
    return preset, rest, scenario


# ---------------------------------------------------------------------------
# Geometry helpers


def _xy(p) -> np.ndarray:
    return np.array([p["x"], p["y"]]) if isinstance(p, dict) else np.array([p.x, p.y])


def _edge_gap(path: np.ndarray) -> float:
    hw, hh = GEO.paper_width_mm / 2, GEO.paper_height_mm / 2
    return float(np.min(np.minimum(hw - np.abs(path[:, 0]), hh - np.abs(path[:, 1]))))


def _path(loop: Loop) -> np.ndarray:
    return np.array([[p.x, p.y] for p in loop.path_mm])


def _same_colour_over_old(loop: Loop, older: list[Loop]) -> bool:
    """The loop is drawn over (or right next to) an older loop of the same colour."""
    a = _path(loop)
    for o in older:
        if o.colour != loop.colour:
            continue
        b = _path(o)
        d = np.min(np.linalg.norm(a[:, None, :] - b[None, ::3, :], axis=2))
        if d < 8.0:
            return True
    return False


def _tags(s: Shot, gt: GroundTruth, all_visible: list[Shot]) -> list[str]:
    """Edge cases this true shot belongs to."""
    tags: list[str] = []
    c = _xy(s.centre_mm)
    r = s.diameter_mm / 2
    touching = [o for o in all_visible if o.id != s.id and np.linalg.norm(c - _xy(o.centre_mm)) < r + o.diameter_mm / 2]
    if s.invisible:
        tags.append("hidden_in_old_hole")
    elif s.through_shot_id and any(o.id == s.through_shot_id and o.round < s.round for o in gt.shots):
        tags.append("through_old_hole")
    elif touching:
        tags.append("touching_hole")
    else:
        tags.append("clean")
    if s.flyer:
        tags.append("flyer")
    if s.group_id is None:
        tags.append("not_looped")
    if any(np.linalg.norm(c - _xy(a.centre_mm)) < a.diameter_mm / 2 + r for a in gt.aim_points):
        tags.append("on_aim_point")
    if np.linalg.norm(c) < GEO.black_diameter_mm / 2:
        tags.append("in_black")
    if s.diameter_mm < 7:
        tags.append("small_calibre")
    loop = next((lp for lp in gt.loops if lp.group_id == (s.group_id or _group_of(s.id)) and lp.round == s.round), None)
    if loop is not None:
        if _edge_gap(_path(loop)) < EDGE_LOOP_MM:
            tags.append("edge_loop")
        if _same_colour_over_old(loop, [lp for lp in gt.loops if lp.round < s.round]):
            tags.append("same_colour_over_old_loop")
        if not loop.closed:
            tags.append("open_loop")
    return tags


def _group_of(shot_id: str) -> str:
    return shot_id.rsplit("s", 1)[0]


# ---------------------------------------------------------------------------
# Scoring


ATTENTION_FLAGS = {
    "HOLE_OUTSIDE_LOOPS", "HOLE_IN_TWO_LOOPS", "SPLIT_TO_MATCH_COUNT", "HOLE_GREW", "OVERLAPPING_HOLES",
    "HOLE_UNCERTAIN", "HOLE_ON_PAPER_EDGE", "UNRESOLVABLE_CLUSTER", "LOOP_ON_PAPER_EDGE",
}
GROUP_ATTENTION = {"FEWER_HOLES_THAN_FIRED", "MORE_HOLES_THAN_FIRED", "SPLIT_TO_MATCH_COUNT", "COLOUR_USED_TWICE",
                   "LOOP_NOT_CLOSED", "LOOP_ON_PAPER_EDGE", "SAME_COLOUR_OVER_OLD_LOOP"}


def score_session(source: Path, result: Path) -> SessionScore:
    name = source.name
    preset, style, scenario = describe(name)
    data = json.loads((result / "session.json").read_text())
    truths = {gt.round: gt for gt in (read_gt(p) for p in sorted(source.glob("*.json")))}
    sc = SessionScore(name, preset, style, scenario, rounds=max(truths))
    timing = result / "seconds.txt"
    if timing.exists():
        sc.seconds = float(timing.read_text().strip())

    _score_aims(sc, data["baseline"], truths[0])
    for r in data["rounds"]:
        gt = truths.get(r["round"])
        if gt is not None:
            _score_round(sc, r, gt)
    return sc


def _score_aims(sc: SessionScore, baseline: dict, gt: GroundTruth) -> None:
    found = baseline.get("aim_points", [])
    used: set[int] = set()
    for a in gt.aim_points:
        c = _xy(a.centre_mm)
        best = min(((np.linalg.norm(c - _xy(f["centre_mm"])), i) for i, f in enumerate(found) if i not in used), default=None)
        if best is None or best[0] > a.diameter_mm / 2:
            sc.aims.append(AimRow(sc.session, sc.preset, sc.aim_style, a.number, a.kind, False, None, None, False, False))
            continue
        used.add(best[1])
        f = found[best[1]]
        unsure = any(fl["code"] == "AIM_POINT_NUMBER_UNSURE" for fl in f.get("flags", []))
        sc.aims.append(AimRow(sc.session, sc.preset, sc.aim_style, a.number, a.kind, True, float(best[0]), f.get("number"),
                              f.get("number") == a.number, unsure))
        sc.flags.extend(
            FlagRow(sc.session, sc.preset, sc.scenario, 0, fl["code"], "right" if f.get("number") != a.number else "wrong",
                    "number read wrong" if f.get("number") != a.number else "number was right", a.centre_mm.x, a.centre_mm.y)
            for fl in f.get("flags", []) if fl["code"] == "AIM_POINT_NUMBER_UNSURE")
    for i, f in enumerate(found):
        if i not in used:
            sc.flags.append(FlagRow(sc.session, sc.preset, sc.scenario, 0, "EXTRA_AIM_POINT", "wrong", "found an aim point that isn't there",
                                    f["centre_mm"]["x"], f["centre_mm"]["y"]))


def _score_round(sc: SessionScore, r: dict, gt: GroundTruth) -> None:
    rnd = r["round"]
    truth = [s for s in gt.shots if s.round == rnd]
    visible_all = [s for s in gt.shots if not s.invisible]
    found = r["shots"]
    loops_now = [lp for lp in gt.loops if lp.round == rnd]

    # Match true visible shots with found ones, closest first.
    vis = [s for s in truth if not s.invisible]
    pairs = sorted((float(np.linalg.norm(_xy(t.centre_mm) - _xy(f["centre_mm"]))), i, j)
                   for i, t in enumerate(vis) for j, f in enumerate(found))
    t_of: dict[int, tuple[int, float]] = {}
    f_used: set[int] = set()
    for d, i, j in pairs:
        if d > MATCH_MM:
            break
        if i in t_of or j in f_used:
            continue
        t_of[i] = (j, d)
        f_used.add(j)
    # A shot that went exactly through an older hole can't be seen, but the app may
    # still place it (splitting a hole to match the count): that isn't a false hole.
    hidden = [s for s in truth if s.invisible]
    hidden_found: dict[str, tuple[int, float]] = {}
    for d, i, j in sorted((float(np.linalg.norm(_xy(t.centre_mm) - _xy(f["centre_mm"]))), i, j)
                          for i, t in enumerate(hidden) for j, f in enumerate(found) if j not in f_used):
        if d > MATCH_MM:
            break
        if hidden[i].id in hidden_found or j in f_used:
            continue
        hidden_found[hidden[i].id] = (j, d)
        f_used.add(j)

    # Which true group each found group stands for: same aim point, else most of its matched shots.
    true_by_aim = {g.aim_point_number: g.id for g in gt.groups if g.round == rnd}
    group_map: dict[str, str | None] = {}
    for g in r["groups"]:
        mapped = true_by_aim.get(g.get("aim_point_number"))
        if mapped is None:
            votes: dict[str, int] = {}
            for i, (j, _) in t_of.items():
                if found[j].get("group_id") == g["id"] and vis[i].group_id:
                    votes[vis[i].group_id] = votes.get(vis[i].group_id, 0) + 1
            mapped = max(votes, key=votes.get) if votes else None
        group_map[g["id"]] = mapped
    group_flags = {g["id"]: {fl["code"] for fl in g.get("flags", [])} for g in r["groups"]}
    older = [lp for lp in gt.loops if lp.round < rnd]
    for lp in loops_now:
        path = _path(lp)
        tags = []
        if _edge_gap(path) < EDGE_LOOP_MM:
            tags.append("edge_loop")
        if _same_colour_over_old(lp, older):
            tags.append("same_colour_over_old_loop")
        if not lp.closed:
            tags.append("open_loop")
        if not tags:
            tags.append("ordinary_loop")
        c = path.mean(axis=0)
        sc.loops.append(LoopRow(sc.session, sc.preset, sc.aim_style, sc.scenario, rnd, lp.group_id, lp.colour, tags,
                                lp.group_id in group_map.values(), float(c[0]), float(c[1])))
    round_flag_codes_at = [(fl["code"], fl.get("at_mm")) for fl in r.get("flags", [])]

    for s in truth:
        row = ShotRow(sc.session, sc.preset, sc.aim_style, sc.scenario, rnd, s.id, s.group_id, s.centre_mm.x, s.centre_mm.y,
                      _tags(s, gt, visible_all), visible=not s.invisible)
        if s.id in hidden_found:
            j, d = hidden_found[s.id]
            row.found, row.found_id, row.error_mm, row.given_group = True, found[j]["id"], d, found[j].get("group_id")
            row.right_group = (group_map.get(row.given_group) if row.given_group else None) == s.group_id
        if not s.invisible:
            i = vis.index(s)
            if i in t_of:
                j, d = t_of[i]
                f = found[j]
                given = f.get("group_id")
                row.found, row.found_id, row.error_mm, row.given_group = True, f["id"], d, given
                row.right_group = (group_map.get(given) if given else None) == s.group_id
                row.flagged = bool(set(f.get("flags", [])) & ATTENTION_FLAGS) or \
                    bool(given and group_flags.get(given, set()) & GROUP_ATTENTION)
            else:
                # A miss is "flagged" if the loop it should be in says it is short.
                gid = s.group_id
                fg = [g for g in r["groups"] if group_map.get(g["id"]) == gid]
                row.flagged = any("FEWER_HOLES_THAN_FIRED" in group_flags[g["id"]] for g in fg)
        sc.shots.append(row)

    # False holes.
    for j, f in enumerate(found):
        if j in f_used:
            continue
        c = _xy(f["centre_mm"])
        hw, hh = GEO.paper_width_mm / 2, GEO.paper_height_mm / 2
        near_edge = min(hw - abs(c[0]), hh - abs(c[1])) < 15
        on_aim = any(np.linalg.norm(c - _xy(a.centre_mm)) < a.diameter_mm / 2 + 5 for a in gt.aim_points)
        sc.false_holes.append(FalseRow(sc.session, sc.preset, sc.scenario, rnd, f["id"], float(c[0]), float(c[1]),
                                       f.get("group_id"), f.get("flags", []), near_edge, on_aim))

    _score_flags(sc, r, gt, truth, vis, found, t_of, f_used, group_map, loops_now)


def _score_flags(sc, r, gt, truth, vis, found, t_of, f_used, group_map, loops_now) -> None:
    rnd = r["round"]
    true_of_found = {j: vis[i] for i, (j, _) in t_of.items()}
    shot_by_id = {f["id"]: (j, f) for j, f in enumerate(found)}

    def add(code, verdict, why, at):
        sc.flags.append(FlagRow(sc.session, sc.preset, sc.scenario, rnd, code, verdict, why,
                                at["x"] if at else None, at["y"] if at else None))

    # Shot-level flags (from the shots themselves).
    for j, f in enumerate(found):
        t = true_of_found.get(j)
        for code in f.get("flags", []):
            if t is None:
                add(code, "wrong", "on a false hole", f["centre_mm"])
                continue
            tags = _tags(t, gt, [s for s in gt.shots if not s.invisible])
            if code == "HOLE_OUTSIDE_LOOPS":
                ok = t.group_id is None or not any(point_in_loop(t.centre_mm, lp) for lp in loops_now if lp.group_id == t.group_id and lp.closed)
                add(code, "right" if ok else "wrong", "shot really is outside its loop" if ok else "shot is inside its loop", f["centre_mm"])
            elif code == "HOLE_IN_TWO_LOOPS":
                n = sum(point_in_loop(t.centre_mm, lp) for lp in loops_now)
                add(code, "right" if n >= 2 else "wrong", f"inside {n} true loops", f["centre_mm"])
            elif code in ("OVERLAPPING_HOLES", "HOLE_GREW", "SPLIT_TO_MATCH_COUNT"):
                ok = "clean" not in tags
                add(code, "right" if ok else "wrong", "hole does touch another" if ok else "hole was clean", f["centre_mm"])
            else:
                add(code, "info", "", f["centre_mm"])

    # Group-level flags.
    for g in r["groups"]:
        tg = group_map.get(g["id"])
        true_g = next((x for x in gt.groups if x.id == tg), None)
        for fl in g.get("flags", []):
            code = fl["code"]
            at = fl.get("at_mm")
            if code == "FEWER_HOLES_THAN_FIRED":
                hidden = true_g is not None and (true_g.fired_shots > sum(1 for s in truth if s.group_id == tg and not s.invisible))
                add(code, "right" if hidden else "wrong",
                    "a shot really is hidden or outside the loop" if hidden else "all shots were visible; the app missed one", at)
            elif code == "MORE_HOLES_THAN_FIRED":
                add(code, "wrong", "the app found a hole that isn't in this group", at)
            elif code == "LOOP_NOT_CLOSED":
                lp = next((x for x in loops_now if x.group_id == tg), None)
                ok = lp is not None and not lp.closed
                add(code, "right" if ok else "wrong", "loop really has a gap" if ok else "loop was closed", at)
            elif code in ("LOOP_WITHOUT_AIM_POINT", "COLOUR_USED_TWICE"):
                add(code, "wrong", "every synthetic group has an aim point and its own colour", at)
            elif code == "SPLIT_TO_MATCH_COUNT":
                add(code, "info", "", at)
            else:
                add(code, "info", "", at)

    # Round-level flags (excluding repeats of shot flags).
    for fl in r.get("flags", []):
        code = fl["code"]
        at = fl.get("at_mm")
        if code in ("HOLE_OUTSIDE_LOOPS", "HOLE_IN_TWO_LOOPS"):
            continue  # scored per shot above
        if code in ("STRAY_INK", "COLOUR_USED_TWICE", "ALIGNMENT_POOR", "CARD_NOT_FOUND", "AIM_POINT_TWO_LOOPS"):
            add(code, "wrong", "nothing like that in this synthetic round", at)
        elif code == "LOOP_NOT_CLOSED":
            near = [lp for lp in loops_now if at and np.min(np.linalg.norm(_path(lp) - _xy(at), axis=1)) < 60]
            ok = any(not lp.closed for lp in near)
            add(code, "right" if ok else "wrong", "loop really has a gap" if ok else "loop was closed", at)
        elif code == "LOOP_ON_PAPER_EDGE":
            near = [lp for lp in loops_now if at and np.min(np.linalg.norm(_path(lp) - _xy(at), axis=1)) < 60]
            ok = any(_edge_gap(_path(lp)) < EDGE_LOOP_MM for lp in near)
            add(code, "right" if ok else "wrong", "loop does reach the edge" if ok else "loop was well inside", at)
        elif code == "FEWER_HOLES_THAN_FIRED":
            add(code, "wrong", "a planned group's loop wasn't found", at)
        else:
            add(code, "info", "", at)


def p95(values: list[float]) -> float | None:
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, int(math.ceil(0.95 * len(v))) - 1)]


def median(values: list[float]) -> float | None:
    return float(np.median(values)) if values else None
