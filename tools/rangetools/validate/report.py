"""Phase 4 report: numbers, charts and worst-failure pictures from the scores.

Writes into the report folder:
  summary.json     every number in the report, for comparing runs
  shots.csv        one row per true shot (found? error? right group? edge-case tags)
  false_holes.csv  holes the app reported that weren't there
  flags.csv        every flag raised, judged right / wrong against the truth
  chart_*.png      the charts
  failures.jpg     the worst failures, cut out of the app's own result pictures
  report.md        the plain-language page
"""

from __future__ import annotations

import csv
import json
from collections import Counter, defaultdict
from dataclasses import asdict
from pathlib import Path

import cv2
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

from .score import MATCH_MM, SessionScore, ShotRow, median, p95  # noqa: E402

# Validation targets (docs/SPEC.md, "Validation targets"); the realistic preset must meet them.
TARGET_P95_MM = 2.0
TARGET_RECALL = 0.98
TARGET_FALSE_PER_CARD = 1.0
TARGET_ASSIGN = 0.98

# Chart colours (dataviz reference palette, light mode).
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"

TAG_WORDS = {
    "clean": "clean hole (touches nothing)",
    "touching_hole": "touches a hole from the same round",
    "through_old_hole": "partly through an older hole",
    "hidden_in_old_hole": "exactly through an older hole (invisible)",
    "flyer": "flyer",
    "not_looped": "left outside the loop on purpose",
    "on_aim_point": "on an aim-point sticker or circle",
    "in_black": "in the black",
    "small_calibre": ".22 (small hole)",
    "edge_loop": "loop within 1 cm of the paper edge",
    "same_colour_over_old_loop": "loop over an old loop of the same colour",
    "open_loop": "loop left open",
}
FLAG_WORDS = {
    "HOLE_OUTSIDE_LOOPS": "hole outside every loop",
    "HOLE_IN_TWO_LOOPS": "hole inside two loops",
    "FEWER_HOLES_THAN_FIRED": "fewer holes than shots fired",
    "MORE_HOLES_THAN_FIRED": "more holes than shots fired",
    "OVERLAPPING_HOLES": "holes overlap",
    "HOLE_GREW": "old hole grew",
    "SPLIT_TO_MATCH_COUNT": "split to match the count",
    "LOOP_NOT_CLOSED": "loop has a gap",
    "LOOP_ON_PAPER_EDGE": "loop closed by the paper edge",
    "SAME_COLOUR_OVER_OLD_LOOP": "loop over an old one of the same colour",
    "STRAY_INK": "stray ink",
    "COLOUR_USED_TWICE": "colour used twice",
    "AIM_POINT_TWO_LOOPS": "two loops at one aim point",
    "AIM_POINT_NUMBER_UNSURE": "aim-point number unsure",
    "LOOP_WITHOUT_AIM_POINT": "loop without aim point",
    "ALIGNMENT_POOR": "photos didn't line up well",
    "EXTRA_AIM_POINT": "aim point that isn't there",
}


# ---------------------------------------------------------------------------
# Numbers


def _block(shots: list[ShotRow], false_n: int, sessions: int) -> dict:
    vis = [s for s in shots if s.visible]
    hidden_placed = sum(1 for s in shots if not s.visible and s.found)
    clean = [s for s in vis if "clean" in s.tags]
    found = [s for s in vis if s.found]
    err = [s.error_mm for s in found]
    err_clean = [s.error_mm for s in found if "clean" in s.tags]
    right = [s for s in found if s.right_group]
    silent_wrong = [s for s in found if not s.right_group and not s.flagged]
    silent_miss = [s for s in vis if not s.found and not s.flagged]
    return {
        "sessions": sessions,
        "true_shots": len(shots),
        "visible": len(vis),
        "found": len(found),
        "hidden": len(shots) - len(vis),
        "hidden_placed": hidden_placed,
        "recall_all": len(found) / len(vis) if vis else None,
        "recall_clean": sum(s.found for s in clean) / len(clean) if clean else None,
        "false_holes": false_n,
        "false_per_card": false_n / sessions if sessions else None,
        "error_median_mm": median(err),
        "error_p95_mm": p95(err),
        "error_p95_clean_mm": p95(err_clean),
        "assign_right": len(right) / len(found) if found else None,
        "silent_wrong_group": len(silent_wrong),
        "silent_missed": len(silent_miss),
    }


def summarise(scores: list[SessionScore]) -> dict:
    out: dict = {"by_preset": {}, "realistic_by_scenario": {}, "realistic_by_tag": {}, "aims": {}, "flags": {}, "timing": {}}
    for preset in ("easy", "realistic", "hard"):
        ss = [s for s in scores if s.preset == preset]
        if ss:
            out["by_preset"][preset] = _block([x for s in ss for x in s.shots], sum(len(s.false_holes) for s in ss), len(ss))
    real = [s for s in scores if s.preset == "realistic"]
    for sc in sorted({s.scenario for s in real}):
        ss = [s for s in real if s.scenario == sc]
        out["realistic_by_scenario"][sc] = _block([x for s in ss for x in s.shots], sum(len(s.false_holes) for s in ss), len(ss))
    for style in sorted({s.aim_style for s in real}):
        ss = [s for s in real if s.aim_style == style]
        out["realistic_by_scenario"]["aim style: " + style] = _block([x for s in ss for x in s.shots], sum(len(s.false_holes) for s in ss), len(ss))
    real_shots = [x for s in real for x in s.shots]
    for tag in TAG_WORDS:
        rows = [x for x in real_shots if tag in x.tags]
        if rows:
            b = _block(rows, 0, 0)
            b.pop("false_holes"); b.pop("false_per_card")
            out["realistic_by_tag"][tag] = b
    for preset in ("easy", "realistic", "hard"):
        aims = [a for s in scores if s.preset == preset for a in s.aims]
        if aims:
            fa = [a for a in aims if a.found]
            out["aims"][preset] = {
                "true": len(aims), "found": len(fa),
                "error_median_mm": median([a.error_mm for a in fa]), "error_max_mm": max((a.error_mm for a in fa), default=None),
                "numbers_right": sum(a.number_right for a in fa),
                "numbers_wrong_unflagged": sum((not a.number_right) and not a.number_flagged for a in fa),
            }
    out["loops"] = {}
    for preset in ("easy", "realistic", "hard"):
        lps = [x for s in scores if s.preset == preset for x in s.loops]
        if not lps:
            continue
        d = {"all": {"loops": len(lps), "read": sum(x.read for x in lps)}}
        for tag in ("ordinary_loop", "edge_loop", "same_colour_over_old_loop", "open_loop"):
            sel = [x for x in lps if tag in x.tags]
            if sel:
                d[tag] = {"loops": len(sel), "read": sum(x.read for x in sel)}
        out["loops"][preset] = d
    flags = defaultdict(Counter)
    for s in scores:
        if s.preset != "realistic":
            continue
        for f in s.flags:
            flags[f.code][f.verdict] += 1
    out["flags"] = {k: dict(v) for k, v in sorted(flags.items())}
    secs = [s.seconds for s in scores if s.seconds]
    out["timing"] = {"median_s_per_session": median(secs), "photos": sum(s.rounds + 1 for s in scores)}
    r = out["by_preset"].get("realistic")
    if r:
        out["targets"] = {
            "error_p95_mm": {"value": r["error_p95_mm"], "target": TARGET_P95_MM, "pass": r["error_p95_mm"] is not None and r["error_p95_mm"] <= TARGET_P95_MM},
            "recall_clean": {"value": r["recall_clean"], "target": TARGET_RECALL, "pass": (r["recall_clean"] or 0) >= TARGET_RECALL},
            "false_per_card": {"value": r["false_per_card"], "target": TARGET_FALSE_PER_CARD, "pass": (r["false_per_card"] or 99) <= TARGET_FALSE_PER_CARD},
            "assign_right": {"value": r["assign_right"], "target": TARGET_ASSIGN, "pass": (r["assign_right"] or 0) >= TARGET_ASSIGN},
        }
    return out


# ---------------------------------------------------------------------------
# Charts


def _style(ax):
    ax.set_facecolor(SURFACE)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(INK2)
    ax.tick_params(colors=INK2, labelsize=9)
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)


def chart_targets(summary: dict, before: dict | None, path: Path) -> None:
    t = summary.get("targets")
    if not t:
        return
    items = [
        ("Clean holes found", 100 * t["recall_clean"]["value"], 100 * TARGET_RECALL, "%", True),
        ("Holes in the right group", 100 * t["assign_right"]["value"], 100 * TARGET_ASSIGN, "%", True),
        ("Position error, 95% of holes within", t["error_p95_mm"]["value"], TARGET_P95_MM, " mm", False),
        ("False holes per card", t["false_per_card"]["value"], TARGET_FALSE_PER_CARD, "", False),
    ]
    bt = (before or {}).get("targets")
    keys = ["recall_clean", "assign_right", "error_p95_mm", "false_per_card"]
    fig, axes = plt.subplots(len(items), 1, figsize=(7.5, 4.6), facecolor=SURFACE)
    for k, (ax, (label, val, target, unit, higher)) in enumerate(zip(axes, items)):
        _style(ax)
        ok = val >= target if higher else val <= target
        lo = 80 if higher else 0
        hi = 100 if higher else max(target * 2.5, val * 1.15, (bt[keys[k]]["value"] * (100 if higher else 1)) * 1.1 if bt else 0)
        if bt:
            bv = bt[keys[k]]["value"] * (100 if higher else 1)
            ax.barh([0], [bv - lo], left=lo, height=0.5, color=GRID, edgecolor=SURFACE)
            ax.text(bv, 0.42, f"before {bv:.1f}{unit}", va="bottom", ha="center", fontsize=8, color=INK2)
        ax.barh([0], [val - lo], left=lo, height=0.28, color=BLUE)
        ax.axvline(target, color=INK, linestyle=(0, (3, 2)), linewidth=1.2)
        ax.text(target, -0.45, f"target {target:g}{unit}", va="top", ha="center", fontsize=8, color=INK)
        ax.set_xlim(lo, hi)
        ax.set_ylim(-0.8, 0.8)
        ax.set_yticks([])
        ax.set_title(f"{label}: {val:.1f}{unit}  {'✓ meets target' if ok else '✗ misses target'}", loc="left", fontsize=10, color=INK)
    fig.suptitle("Realistic sessions against the targets", x=0.01, ha="left", fontsize=12, color=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)


def chart_tags(summary: dict, path: Path) -> None:
    tags = summary["realistic_by_tag"]
    rows = [(TAG_WORDS[t], b) for t, b in tags.items() if t != "hidden_in_old_hole"]
    rows.sort(key=lambda r: (r[1]["found"] and r[1]["recall_all"] * r[1]["assign_right"]) or 0)
    fig, ax = plt.subplots(figsize=(7.5, 0.42 * len(rows) + 1.2), facecolor=SURFACE)
    _style(ax)
    y = np.arange(len(rows))
    found = [100 * (b["recall_all"] or 0) for _, b in rows]
    both = [100 * (b["recall_all"] or 0) * (b["assign_right"] or 0) for _, b in rows]
    ax.barh(y, found, height=0.62, color=GRID, label="found")
    ax.barh(y, both, height=0.36, color=BLUE, label="found and in the right group")
    for k, (_, b) in enumerate(rows):
        ax.text(101, k, f"{both[k]:.0f}%  ({b['visible']} holes)", va="center", fontsize=8, color=INK2)
    ax.axvline(98, color=INK, linestyle=(0, (3, 2)), linewidth=1)
    ax.set_yticks(y, [r[0] for r in rows], fontsize=9, color=INK)
    ax.set_xlim(0, 125)
    ax.set_xticks([0, 25, 50, 75, 100])
    ax.legend(loc="lower right", fontsize=8, frameon=False)
    ax.set_title("Realistic sessions: how each kind of shot fares", loc="left", fontsize=11, color=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)


def chart_errors(scores: list[SessionScore], path: Path) -> None:
    fig, ax = plt.subplots(figsize=(7.5, 2.8), facecolor=SURFACE)
    _style(ax)
    for k, preset in enumerate(("easy", "realistic", "hard")):
        e = np.array([x.error_mm for s in scores if s.preset == preset for x in s.shots if x.found])
        if len(e) == 0:
            continue
        jitter = np.random.default_rng(k).uniform(-0.25, 0.25, len(e))
        ax.scatter(np.minimum(e, MATCH_MM), k + jitter, s=9, color=BLUE, alpha=0.45, linewidths=0)
        q = p95(list(e))
        ax.plot([q, q], [k - 0.35, k + 0.35], color=ORANGE, linewidth=2)
        ax.text(q, k + 0.38, f"95% within {q:.1f} mm", fontsize=8, color=INK, va="bottom", ha="center")
    ax.axvline(TARGET_P95_MM, color=INK, linestyle=(0, (3, 2)), linewidth=1)
    ax.set_yticks([0, 1, 2], ["easy", "realistic", "hard"], color=INK)
    ax.set_xlabel("distance from the true centre (mm); each dot is one hole", color=INK2, fontsize=9)
    ax.set_xlim(0, MATCH_MM)
    ax.set_title("How close each found hole is to where the shot really went", loc="left", fontsize=11, color=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)


def chart_flags(summary: dict, path: Path) -> None:
    fl = {k: v for k, v in summary["flags"].items() if v.get("right", 0) + v.get("wrong", 0) > 0}
    if not fl:
        return
    rows = sorted(fl.items(), key=lambda kv: kv[1].get("right", 0) + kv[1].get("wrong", 0))
    fig, ax = plt.subplots(figsize=(7.5, 0.42 * len(rows) + 1.2), facecolor=SURFACE)
    _style(ax)
    y = np.arange(len(rows))
    right = np.array([v.get("right", 0) for _, v in rows])
    wrong = np.array([v.get("wrong", 0) for _, v in rows])
    ax.barh(y, right, height=0.55, color=BLUE, label="right (the problem was real)", edgecolor=SURFACE, linewidth=2)
    ax.barh(y, wrong, left=right, height=0.55, color=ORANGE, label="false alarm or caused by an app mistake", edgecolor=SURFACE, linewidth=2)
    for k in range(len(rows)):
        ax.text(right[k] + wrong[k] + 0.5, k, f"{right[k]} right, {wrong[k]} wrong", va="center", fontsize=8, color=INK2)
    ax.set_yticks(y, [FLAG_WORDS.get(k, k) for k, _ in rows], fontsize=9, color=INK)
    ax.set_xlim(0, max(right + wrong) * 1.45 + 1)
    ax.legend(loc="lower right", fontsize=8, frameon=False)
    ax.set_title("Realistic sessions: flags raised, and whether they were right", loc="left", fontsize=11, color=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)


# ---------------------------------------------------------------------------
# Worst failures, cut from the app's own result pictures


PPM, LEFT, TOP = 4.0, -290.0, 275.0  # the app's flattened view (ViewBox.DEFAULT)


def _to_px(x, y):
    return int(round((x - LEFT) * PPM)), int(round((TOP - y) * PPM))


def worst_failures(scores: list[SessionScore], results: Path, path: Path, n: int = 8) -> list[str]:
    cands = []
    for s in scores:
        if s.preset == "hard":
            continue
        for x in s.shots:
            if not x.visible:
                continue
            if not x.found:
                cands.append((3 if not x.flagged else 2, s, x.round, x.x_mm, x.y_mm, f"missed {'(flagged)' if x.flagged else '(no flag)'}", x))
            elif not x.right_group:
                cands.append((3 if not x.flagged else 1, s, x.round, x.x_mm, x.y_mm, f"wrong group{' (flagged)' if x.flagged else ', no flag'}", x))
            elif x.error_mm > 3:
                cands.append((1, s, x.round, x.x_mm, x.y_mm, f"centre {x.error_mm:.1f} mm off", x))
        for f in s.false_holes:
            cands.append((2, s, f.round, f.x_mm, f.y_mm, "false hole", None))
    # Spread over sessions and kinds: most serious first, one per spot.
    cands.sort(key=lambda c: -c[0])
    picked, seen = [], set()
    for c in cands:
        key = (c[1].session, c[2], round(c[3] / 40), round(c[4] / 40))
        if key in seen or sum(1 for p in picked if p[1].session == c[1].session) >= 2:
            continue
        seen.add(key)
        picked.append(c)
        if len(picked) == n:
            break
    tiles, captions = [], []
    half = 60  # mm round the spot
    for k, (_, s, rnd, x, y, what, row) in enumerate(picked, start=1):
        img = cv2.imread(str(results / s.session / f"r{rnd:02d}_3_result.jpg"))
        if img is None:
            continue
        cx, cy = _to_px(x, y)
        r = int(half * PPM)
        pad = cv2.copyMakeBorder(img, r, r, r, r, cv2.BORDER_CONSTANT, value=(245, 245, 245))
        tile = pad[cy:cy + 2 * r, cx:cx + 2 * r].copy()
        # The true spot: a black-and-white dashed-looking double ring at the centre.
        cv2.circle(tile, (r, r), int(7 * PPM), (255, 255, 255), 7, cv2.LINE_AA)
        cv2.circle(tile, (r, r), int(7 * PPM), (0, 0, 0), 3, cv2.LINE_AA)
        tile = cv2.resize(tile, (360, 360), interpolation=cv2.INTER_AREA)
        cv2.rectangle(tile, (0, 0), (359, 28), (255, 255, 255), -1)
        cv2.putText(tile, f"{k}. {what}", (6, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (20, 20, 20), 1, cv2.LINE_AA)
        tiles.append(tile)
        tag = ", ".join(TAG_WORDS[t] for t in row.tags if t in TAG_WORDS and t != "clean") if row else ""
        captions.append(f"{k}. **{what}** in `{s.session}` round {rnd} at ({x:.0f}, {y:.0f}) mm" + (f": {tag}" if tag else ""))
    if not tiles:
        return []
    while len(tiles) % 4:
        tiles.append(np.full_like(tiles[0], 245))
    grid = np.vstack([np.hstack(tiles[i:i + 4]) for i in range(0, len(tiles), 4)])
    cv2.imwrite(str(path), grid, [cv2.IMWRITE_JPEG_QUALITY, 88])
    return captions


# ---------------------------------------------------------------------------
# The page


def _pct(v):
    return "–" if v is None else f"{100 * v:.1f}%"


def _mm(v):
    return "–" if v is None else f"{v:.1f} mm"


def _num(v):
    return "–" if v is None else f"{v:.1f}"


def write_report(scores: list[SessionScore], sources: dict[str, Path], results: Path, out: Path,
                 label: str = "", before: dict | None = None) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    summary = summarise(scores)
    summary["label"] = label
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    with (out / "shots.csv").open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["session", "preset", "aim_style", "scenario", "round", "shot", "true_group", "x_mm", "y_mm", "visible", "found",
                    "error_mm", "given_group", "right_group", "flagged", "tags"])
        for s in scores:
            for x in s.shots:
                w.writerow([x.session, x.preset, x.aim_style, x.scenario, x.round, x.shot_id, x.group_id, f"{x.x_mm:.1f}", f"{x.y_mm:.1f}",
                            x.visible, x.found, "" if x.error_mm is None else f"{x.error_mm:.2f}", x.given_group, x.right_group, x.flagged,
                            " ".join(x.tags)])
    for name, rows in (("false_holes", [f for s in scores for f in s.false_holes]), ("flags", [f for s in scores for f in s.flags])):
        with (out / f"{name}.csv").open("w", newline="") as fh:
            if rows:
                w = csv.DictWriter(fh, fieldnames=list(asdict(rows[0])))
                w.writeheader()
                for r in rows:
                    w.writerow(asdict(r))
    chart_targets(summary, before, out / "chart_targets.png")
    chart_tags(summary, out / "chart_kinds_of_shot.png")
    chart_errors(scores, out / "chart_position_error.png")
    chart_flags(summary, out / "chart_flags.png")
    captions = worst_failures(scores, results, out / "failures.jpg")

    bp = summary["by_preset"]
    lines = [f"# Validation report{' (' + label + ')' if label else ''}", ""]
    n_photos = summary["timing"]["photos"]
    lines += [f"{len(scores)} synthetic sessions ({n_photos} photos) run through `rangetarget session` and compared with the known answers.", ""]
    t = summary.get("targets")
    if t:
        passed = sum(v["pass"] for v in t.values())
        lines += [f"**Realistic sessions: {passed} of 4 targets met.**", "", "![targets](chart_targets.png)", ""]
    lines += ["| | easy | realistic | hard |", "| --- | --- | --- | --- |"]

    def row(label, key, fmt):
        return f"| {label} | " + " | ".join(fmt(bp.get(p, {}).get(key)) for p in ("easy", "realistic", "hard")) + " |"
    lines += [row("sessions", "sessions", lambda v: "–" if v is None else str(v)),
              row("clean holes found", "recall_clean", _pct), row("all visible holes found", "recall_all", _pct),
              row("false holes per card", "false_per_card", _num), row("position error, typical", "error_median_mm", _mm),
              row("position error, 95% within", "error_p95_mm", _mm), row("... clean holes only", "error_p95_clean_mm", _mm),
              row("found holes in the right group", "assign_right", _pct),
              row("wrong group with no flag (silent)", "silent_wrong_group", lambda v: "–" if v is None else str(v)),
              row("missed with no flag (silent)", "silent_missed", lambda v: "–" if v is None else str(v)), ""]
    a = summary["aims"]
    lines += ["**Aim points:** " + "; ".join(
        f"{p}: {v['found']} of {v['true']} found, typical {_mm(v['error_median_mm'])} off (worst {_mm(v['error_max_mm'])}), "
        f"numbers right {v['numbers_right']} of {v['found']}" + (f", {v['numbers_wrong_unflagged']} wrong without a flag" if v['numbers_wrong_unflagged'] else "")
        for p, v in a.items()), ""]
    lw = {"all": "all loops", "ordinary_loop": "ordinary", "edge_loop": "within 1 cm of the edge",
          "same_colour_over_old_loop": "over an old loop of the same colour", "open_loop": "left open"}
    lines += ["**Loops read** (a loop the app didn't read leaves its whole group unassigned)", "",
              "| | " + " | ".join(summary["loops"]) + " |", "| --- |" + " --- |" * len(summary["loops"])]
    for tag, word in lw.items():
        cells = []
        for p, d in summary["loops"].items():
            v = d.get(tag)
            cells.append("–" if not v else f"{v['read']} of {v['loops']}")
        lines.append(f"| {word} | " + " | ".join(cells) + " |")
    lines.append("")
    sc = summary["realistic_by_scenario"]
    lines += ["**Realistic sessions by situation**", "", "| | sessions | clean found | false per card | 95% within | right group |", "| --- | --- | --- | --- | --- | --- |"]
    for k, v in sc.items():
        lines.append(f"| {k} | {v['sessions']} | {_pct(v['recall_clean'])} | {_num(v['false_per_card'])} | {_mm(v['error_p95_mm'])} | {_pct(v['assign_right'])} |")
    lines += ["", "![kinds of shot](chart_kinds_of_shot.png)", "", "![position error](chart_position_error.png)", "",
              "![flags](chart_flags.png)", ""]
    if captions:
        lines += ["**Worst failures** (easy and realistic; the black-and-white ring marks where the shot really is)", "", "![failures](failures.jpg)", ""]
        lines += [f"- {c}" for c in captions] + [""]
    secs = summary["timing"]["median_s_per_session"]
    if secs:
        lines += [f"Typical time per session on the test computer: {secs:.0f} s.", ""]
    (out / "report.md").write_text("\n".join(lines))
    return out / "report.md"
