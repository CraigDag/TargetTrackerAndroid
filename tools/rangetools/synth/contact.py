"""Contact sheets: one picture per session showing every photo, with the
ground truth drawn over a second copy so you can check they agree.

Works from a session folder alone (images + JSON), using each file's
homography to place the answers on the photo.
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from .. import groundtruth
from ..groundtruth import GroundTruth
from .style import LOOP_PALETTE, bgr

THUMB_W = 520
LABEL_H = 64
BG = (245, 245, 245)
TEXT = (30, 30, 30)
UNLOOPED = (40, 40, 230)  # red-ish in BGR


def to_px(gt: GroundTruth, xy: np.ndarray) -> np.ndarray:
    H = np.array(gt.distortion.homography_mm_to_px).reshape(3, 3)
    p = np.c_[xy, np.ones(len(xy))] @ H.T
    return p[:, :2] / p[:, 2:3]


def _photos(folder: Path) -> list[tuple[GroundTruth, np.ndarray]]:
    out = []
    for j in sorted(folder.glob("*.json"), key=lambda p: (p.stem != "baseline", p.stem)):
        gt = groundtruth.read(j)
        img = cv2.imread(str(folder / gt.image.file))
        if img is None:
            raise FileNotFoundError(folder / gt.image.file)
        out.append((gt, img))
    return out


def draw_truth(gt: GroundTruth, img: np.ndarray, only_new: bool = True, scale: float = 1.0) -> np.ndarray:
    """Draws this photo's answers: aim points (white cross + number), new holes
    circled in their loop's colour (red X if left unlooped), loop centre lines."""
    out = img.copy()
    lw = max(1, int(round(2 * scale)))
    for a in gt.aim_points:
        (u, v), = to_px(gt, np.array([[a.centre_mm.x, a.centre_mm.y]]))
        s = 14 * scale
        cv2.line(out, (int(u - s), int(v)), (int(u + s), int(v)), (255, 255, 255), lw, cv2.LINE_AA)
        cv2.line(out, (int(u), int(v - s)), (int(u), int(v + s)), (255, 255, 255), lw, cv2.LINE_AA)
    colour_of = {g.id: bgr(LOOP_PALETTE[g.loop_colour]) for g in gt.groups}
    for lp in gt.loops:
        if only_new and lp.round != gt.round:
            continue
        pts = to_px(gt, np.array([[p.x, p.y] for p in lp.path_mm]))
        cv2.polylines(out, [np.round(pts).astype(np.int32)], lp.closed, (20, 20, 20), max(1, lw // 2), cv2.LINE_AA)
    for s in gt.shots:
        if only_new and s.round != gt.round:
            continue
        (u, v), = to_px(gt, np.array([[s.centre_mm.x, s.centre_mm.y]]))
        H = np.array(gt.distortion.homography_mm_to_px).reshape(3, 3)
        r = s.diameter_mm / 2 * np.sqrt(abs(np.linalg.det(H[:2, :2]))) / (H[2] @ [s.centre_mm.x, s.centre_mm.y, 1])
        r = max(r + 4 * scale, 5)
        if s.group_id is None:
            d = r * 0.9
            cv2.line(out, (int(u - d), int(v - d)), (int(u + d), int(v + d)), UNLOOPED, lw + 1, cv2.LINE_AA)
            cv2.line(out, (int(u - d), int(v + d)), (int(u + d), int(v - d)), UNLOOPED, lw + 1, cv2.LINE_AA)
        else:
            col = colour_of[s.group_id]
            cv2.circle(out, (int(u), int(v)), int(r), (0, 0, 0), lw + 2, cv2.LINE_AA)
            cv2.circle(out, (int(u), int(v)), int(r), col, lw, cv2.LINE_AA)
            if s.invisible:
                cv2.circle(out, (int(u), int(v)), int(r * 1.6), col, lw, cv2.LINE_AA)
    return out


def _label(img: np.ndarray, lines: list[str]) -> np.ndarray:
    pad = np.full((LABEL_H, img.shape[1], 3), BG, np.uint8)
    for i, t in enumerate(lines):
        cv2.putText(pad, t, (8, 26 + 28 * i), cv2.FONT_HERSHEY_SIMPLEX, 0.62, TEXT, 1, cv2.LINE_AA)
    return np.vstack([pad, img])


def _thumb(img: np.ndarray, w: int = THUMB_W, h: int | None = None) -> np.ndarray:
    s = w / img.shape[1]
    t = cv2.resize(img, (w, int(round(img.shape[0] * s))), interpolation=cv2.INTER_AREA)
    if h is not None and t.shape[0] < h:  # portrait/landscape mix: pad to one height
        t = np.vstack([t, np.full((h - t.shape[0], w, 3), BG, np.uint8)])
    return t


def describe(gt: GroundTruth) -> list[str]:
    if gt.round == 0:
        kinds = sorted({a.kind.replace("_", "-") for a in gt.aim_points})
        return ["Baseline (before shooting)", f"{len(gt.aim_points)} aim points: {', '.join(kinds)}"]
    new = gt.new_shots()
    hidden = sum(s.invisible for s in new)
    unlooped = sum(s.group_id is None for s in new)
    ri = gt.round_info
    extra = []
    if hidden:
        extra.append(f"{hidden} hidden")
    if unlooped:
        extra.append(f"{unlooped} unlooped")
    groups = [g for g in gt.groups if g.round == gt.round]
    return [f"Round {gt.round}: {ri.distance_m:g} m, {ri.calibre}",
            f"{len(groups)} groups, {len(new)} shots" + (f" ({', '.join(extra)})" if extra else "")]


def _zoom(gt: GroundTruth, img: np.ndarray, size: int) -> np.ndarray:
    """Full-resolution close-up of the first group of this round, with answers drawn on."""
    gid = next(g.id for g in gt.groups if g.round == gt.round)
    pts = np.array([[s.centre_mm.x, s.centre_mm.y] for s in gt.shots if s.group_id == gid or
                    (s.round == gt.round and s.id.startswith(gid + "s"))])
    lo, hi = pts.min(0) - 22, pts.max(0) + 22
    box = to_px(gt, np.array([[lo[0], lo[1]], [hi[0], hi[1]], [lo[0], hi[1]], [hi[0], lo[1]]]))
    x0, y0 = np.maximum(box.min(0).astype(int), 0)
    x1, y1 = box.max(0).astype(int)
    side = max(x1 - x0, y1 - y0)
    crop = img[y0 : y0 + side, x0 : x0 + side]
    marked = draw_truth(gt, img, only_new=True)[y0 : y0 + side, x0 : x0 + side]
    a = cv2.resize(crop, (size, size), interpolation=cv2.INTER_AREA if side > size else cv2.INTER_CUBIC)
    b = cv2.resize(marked, (size, size), interpolation=cv2.INTER_AREA if side > size else cv2.INTER_CUBIC)
    return np.hstack([a, np.full((size, 8, 3), BG, np.uint8), b])


def contact_sheet(folder: Path) -> np.ndarray:
    photos = _photos(Path(folder))
    first = photos[0][0]
    th = max(int(round(img.shape[0] * THUMB_W / img.shape[1])) for _, img in photos)
    top, bottom = [], []
    for gt, img in photos:
        scale = img.shape[1] / THUMB_W
        top.append(_label(_thumb(img, h=th), describe(gt)))
        marked = draw_truth(gt, img, only_new=True, scale=scale)
        bottom.append(_thumb(marked, h=th))
    gap = lambda h: np.full((h, 10, 3), BG, np.uint8)  # noqa: E731
    row1 = np.hstack([x for t in top for x in (t, gap(t.shape[0]))][:-1])
    row2 = np.hstack([x for t in bottom for x in (t, gap(t.shape[0]))][:-1])
    w = row1.shape[1]
    title = np.full((96, w, 3), BG, np.uint8)
    cv2.putText(title, f"Synthetic session '{first.session_id}' (preset: {first.preset}, seed {first.seed})",
                (10, 38), cv2.FONT_HERSHEY_SIMPLEX, 1.0, TEXT, 2, cv2.LINE_AA)
    cv2.putText(title, "Top: the fake phone photos. Bottom: the known answers for what's new in each photo "
                "(circles = new holes in their loop's colour, double circle = hidden in an older hole, "
                "red X = left outside its loop, thin black line = loop, white cross = aim point).",
                (10, 76), cv2.FONT_HERSHEY_SIMPLEX, 0.55, TEXT, 1, cv2.LINE_AA)
    caption = np.full((40, w, 3), BG, np.uint8)
    cv2.putText(caption, "Close-up at full resolution: one group from the last round, as photographed and with the answers.",
                (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.62, TEXT, 1, cv2.LINE_AA)
    last_gt, last_img = photos[-1]
    zoom = _zoom(last_gt, last_img, size=min(560, (w - 8) // 2)) if last_gt.round > 0 else None
    parts = [title, row1, np.full((14, w, 3), BG, np.uint8), row2]
    if zoom is not None:
        z = np.full((zoom.shape[0], w, 3), BG, np.uint8)
        z[:, 10 : 10 + zoom.shape[1]] = zoom
        parts += [np.full((14, w, 3), BG, np.uint8), caption, z]
    parts.append(np.full((14, w, 3), BG, np.uint8))
    return np.vstack(parts)
