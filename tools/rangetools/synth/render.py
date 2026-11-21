"""Draws the flat card, straight on, in millimetres.

The card is kept as two layers at PPM pixels per mm:
- `rgb`: what's printed and drawn on the paper (BGR, uint8),
- `alpha`: where there is paper (255) and where there's a hole (0).
What shows through the holes (the backing board) is a separate, larger layer,
`Backing`, so it lines up between photos. photo.py combines them through the
camera.
"""

from __future__ import annotations

import math

import cv2
import numpy as np

from ..geometry import CardGeometry
from ..groundtruth import AimPoint, Loop, Shot
from . import style
from .style import bgr

PPM = 8.0          # flat card resolution, px per mm
MARGIN_MM = 8.0    # flat layer reaches this far past the paper edge
SHIFT = 4          # sub-pixel bits for OpenCV drawing
ONE = 1 << SHIFT


def _noise_field(rng: np.random.Generator, shape: tuple[int, int], cell_px: int) -> np.ndarray:
    """Smooth random field, roughly in -1..1, varying over about `cell_px` pixels."""
    h, w = shape
    small = rng.normal(0, 1, (max(2, h // cell_px + 2), max(2, w // cell_px + 2))).astype(np.float32)
    big = cv2.resize(small, (w + 2 * cell_px, h + 2 * cell_px), interpolation=cv2.INTER_CUBIC)
    return big[cell_px : cell_px + h, cell_px : cell_px + w] / 1.5


class FlatCard:
    def __init__(self, geo: CardGeometry, seed: int):
        self.geo = geo
        self.half_w = geo.paper_width_mm / 2 + MARGIN_MM
        self.half_h = geo.paper_height_mm / 2 + MARGIN_MM
        self.w = int(round(2 * self.half_w * PPM))
        self.h = int(round(2 * self.half_h * PPM))
        rng = np.random.default_rng([seed, 100])
        self.rgb = np.zeros((self.h, self.w, 3), np.uint8)
        self.alpha = np.zeros((self.h, self.w), np.uint8)
        self._paper(rng)
        self._print()

    # --- coordinates -------------------------------------------------------

    def to_px(self, xy) -> np.ndarray:
        """Card mm (x right, y up) to flat-layer pixels (x right, y down)."""
        xy = np.asarray(xy, dtype=np.float64)
        return np.stack([(xy[..., 0] + self.half_w) * PPM, (self.half_h - xy[..., 1]) * PPM], axis=-1)

    def _fx(self, xy) -> np.ndarray:
        """Fixed-point pixel coords for OpenCV drawing with SHIFT."""
        return np.round(self.to_px(xy) * ONE).astype(np.int32)

    # --- paper and print ---------------------------------------------------

    def _paper(self, rng: np.random.Generator) -> None:
        pw, ph = self.geo.paper_width_mm / 2, self.geo.paper_height_mm / 2
        corners = self._fx([[-pw, ph], [pw, ph], [pw, -ph], [-pw, -ph]])
        cv2.fillPoly(self.alpha, [corners], 255, cv2.LINE_AA, SHIFT)
        base = np.array(bgr(style.PAPER), np.float32)
        mottle = _noise_field(rng, (self.h, self.w), 400) * 4.0
        fibre = rng.normal(0, 2.2, (self.h, self.w)).astype(np.float32)
        tex = mottle + fibre
        self.rgb[:] = np.clip(base[None, None, :] + tex[..., None], 0, 255).astype(np.uint8)

    def _print(self) -> None:
        g = self.geo
        c = self._fx([0.0, 0.0])
        ink, on_black = bgr(style.PRINT_INK), bgr(style.PRINT_ON_BLACK)
        line = max(1, int(round(style.RING_LINE_MM * PPM)))
        for ring in g.rings:
            if ring < g.black_outer_ring:
                r = int(round((g.ring_radius_mm(ring) - style.RING_LINE_MM / 2) * PPM * ONE))
                cv2.circle(self.rgb, tuple(c), r, ink, line, cv2.LINE_AA, SHIFT)
        cv2.circle(self.rgb, tuple(c), int(round(g.black_diameter_mm / 2 * PPM * ONE)),
                   bgr(style.BLACK_AREA), -1, cv2.LINE_AA, SHIFT)
        for ring in g.rings:
            if ring > g.black_outer_ring:
                r = int(round((g.ring_radius_mm(ring) - style.RING_LINE_MM / 2) * PPM * ONE))
                cv2.circle(self.rgb, tuple(c), r, on_black, line, cv2.LINE_AA, SHIFT)
        # Ring numbers on the four axes, in every band from 1 to 9.
        for ring in g.rings:
            if ring + 1 not in g.ring_diameters_mm:
                continue
            mid = (g.ring_radius_mm(ring) + g.ring_radius_mm(ring + 1)) / 2
            colour = on_black if ring >= g.black_outer_ring else ink
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                self._text(str(ring), (dx * mid, dy * mid), style.NUMBER_HEIGHT_MM, 1.3, colour,
                           cv2.FONT_HERSHEY_SIMPLEX)

    def _text(self, text: str, centre_mm, height_mm: float, stroke_mm: float, colour, font,
              angle_deg: float = 0.0, target: np.ndarray | None = None) -> None:
        """Draws text centred on a point, `height_mm` tall, optionally turned."""
        target = self.rgb if target is None else target
        thick = max(1, int(round(stroke_mm * PPM)))
        (tw, th), _ = cv2.getTextSize(text, font, 1.0, thick)
        scale = height_mm * PPM / max(th, 1)
        thick = max(1, int(round(stroke_mm * PPM)))
        (tw, th), base = cv2.getTextSize(text, font, scale, thick)
        pad = thick * 2 + 4
        tile = np.zeros((th + base + 2 * pad, tw + 2 * pad), np.uint8)
        cv2.putText(tile, text, (pad, pad + th), font, scale, 255, thick, cv2.LINE_AA)
        if angle_deg:
            hh, ww = tile.shape
            m = cv2.getRotationMatrix2D((ww / 2, hh / 2), angle_deg, 1.0)
            tile = cv2.warpAffine(tile, m, (ww, hh))
        cx, cy = self.to_px(centre_mm)
        # Centre on the ink, not on the text box.
        ys, xs = np.nonzero(tile > 40)
        if len(xs) == 0:
            return
        ox, oy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
        self._blend(tile.astype(np.float32) / 255, int(round(cx - ox)), int(round(cy - oy)), colour, target)

    def _blend(self, mask: np.ndarray, x0: int, y0: int, colour, target: np.ndarray | None = None) -> None:
        """Paints `colour` through `mask` (0..1) with its top-left at (x0, y0)."""
        target = self.rgb if target is None else target
        h, w = mask.shape
        X0, Y0 = max(0, x0), max(0, y0)
        X1, Y1 = min(target.shape[1], x0 + w), min(target.shape[0], y0 + h)
        if X1 <= X0 or Y1 <= Y0:
            return
        m = mask[Y0 - y0 : Y1 - y0, X0 - x0 : X1 - x0, None]
        roi = target[Y0:Y1, X0:X1].astype(np.float32)
        col = np.array(colour, np.float32)[None, None, :]
        target[Y0:Y1, X0:X1] = np.clip(roi * (1 - m) + col * m, 0, 255).astype(np.uint8)

    def _roi(self, centre_mm, radius_mm: float) -> tuple[int, int, int, int]:
        cx, cy = self.to_px(centre_mm)
        r = radius_mm * PPM
        return int(cx - r), int(cy - r), int(math.ceil(2 * r)) + 1, int(math.ceil(2 * r)) + 1

    def _poly_mask(self, poly_mm: np.ndarray, x0: int, y0: int, w: int, h: int) -> np.ndarray:
        m = np.zeros((h, w), np.uint8)
        pts = np.round((self.to_px(poly_mm) - [x0, y0]) * ONE).astype(np.int32)
        cv2.fillPoly(m, [pts], 255, cv2.LINE_AA, SHIFT)
        return m.astype(np.float32) / 255

    # --- aim points --------------------------------------------------------

    def draw_aim_point(self, aim: AimPoint, seed: int) -> None:
        rng = np.random.default_rng(seed)
        c = np.array([aim.centre_mm.x, aim.centre_mm.y])
        r = aim.diameter_mm / 2
        x0, y0, w, h = self._roi(c, r + 3)
        t = np.linspace(0, 2 * math.pi, 180, endpoint=False)
        if aim.kind == "sticker":
            poly = c + r * np.stack([np.cos(t), np.sin(t)], axis=1)
            mask = self._poly_mask(poly, x0, y0, w, h)
            self._blend(mask, x0, y0, bgr(aim.colour))
            self._text(str(aim.number), c, 18.0, 2.6, bgr(style.STICKER_NUMBER), cv2.FONT_HERSHEY_DUPLEX)
        else:
            wob = np.zeros_like(t)
            for k in range(2, 6):
                wob += rng.normal(0, 0.025) * np.cos(k * t + rng.uniform(0, 2 * math.pi))
            poly = c + (r * (1 + wob))[:, None] * np.stack([np.cos(t), np.sin(t)], axis=1)
            mask = self._poly_mask(poly, x0, y0, w, h)
            # Marker strokes: coverage varies in streaks.
            streak = _noise_field(rng, mask.shape, 12)
            ang = rng.uniform(0, math.pi)
            yy, xx = np.mgrid[0 : mask.shape[0], 0 : mask.shape[1]]
            bands = 0.5 + 0.5 * np.sin((xx * math.cos(ang) + yy * math.sin(ang)) / (2.5 * PPM) * 2 * math.pi)
            coverage = np.clip(0.88 + 0.05 * streak + 0.06 * bands, 0, 1)
            self._blend(mask * coverage, x0, y0, bgr(aim.colour))
            self._text(str(aim.number), c + rng.normal(0, 1.0, 2), rng.uniform(14, 18), rng.uniform(1.6, 2.2),
                       bgr(style.HAND_NUMBER), cv2.FONT_HERSHEY_SCRIPT_SIMPLEX, angle_deg=rng.uniform(-15, 15))

    # --- holes -------------------------------------------------------------

    def cut_hole(self, shot: Shot, seed: int) -> None:
        if shot.invisible:
            return  # hidden inside an earlier hole: nothing changes on the card
        rng = np.random.default_rng(seed)
        c = np.array([shot.centre_mm.x, shot.centre_mm.y])
        r = shot.diameter_mm / 2
        poly = hole_outline(c, r, rng)
        x0, y0, w, h = self._roi(c, r * 1.6 + 2)
        X0, Y0 = max(0, x0), max(0, y0)
        X1, Y1 = min(self.w, x0 + w), min(self.h, y0 + h)
        if X1 <= X0 or Y1 <= Y0:
            return
        hole = self._poly_mask(poly, x0, y0, w, h)
        # Torn fibres: a thin, uneven greyish rim just outside the hole.
        k = max(3, int(round(0.9 * PPM)) | 1)
        grown = cv2.dilate(hole, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
        rim = np.clip(grown - hole, 0, 1)
        rim = cv2.GaussianBlur(rim, (0, 0), 0.3 * PPM) * np.clip(0.5 + 0.4 * _noise_field(rng, rim.shape, 6), 0, 1)
        self._blend(rim * 0.55, x0, y0, bgr(style.HOLE_RIM))
        sub = (slice(Y0 - y0, Y1 - y0), slice(X0 - x0, X1 - x0))
        a = self.alpha[Y0:Y1, X0:X1].astype(np.float32)
        self.alpha[Y0:Y1, X0:X1] = np.clip(a * (1 - hole[sub]), 0, 255).astype(np.uint8)

    # --- loops -------------------------------------------------------------

    def draw_loop(self, loop: Loop, seed: int) -> None:
        rng = np.random.default_rng(seed)
        pts = np.array([[p.x, p.y] for p in loop.path_mm])
        lo, hi = pts.min(axis=0) - 10, pts.max(axis=0) + 10
        tl = self.to_px([lo[0], hi[1]])
        x0, y0 = int(tl[0]), int(tl[1])
        w = int((hi[0] - lo[0]) * PPM) + 2
        h = int((hi[1] - lo[1]) * PPM) + 2
        mask = np.zeros((h, w), np.uint8)
        px = np.round((self.to_px(pts) - [x0, y0]) * ONE).astype(np.int32)
        n = len(px)
        # Pen pressure varies slowly along the line.
        t = np.linspace(0, 1, n)
        width = loop.stroke_width_mm * (1 + 0.18 * np.sin(2 * math.pi * (t * rng.uniform(1.5, 3.5) + rng.uniform())))
        segs = range(n) if loop.closed and n > 2 and not _overshoots(pts) else range(n - 1)
        for i in segs:
            j = (i + 1) % n
            th = max(1, int(round(width[i] * PPM)))
            cv2.line(mask, tuple(px[i]), tuple(px[j]), 255, th, cv2.LINE_AA, SHIFT)
        m = mask.astype(np.float32) / 255
        coverage = np.clip(0.9 + 0.06 * _noise_field(rng, m.shape, 10), 0, 1)
        colour = np.array(bgr(loop.colour_rgb), np.float32) * rng.uniform(0.94, 1.0)
        self._blend(m * coverage, x0, y0, tuple(colour))


def _overshoots(pts: np.ndarray) -> bool:
    """True if a closed loop's path already runs past its start (so no closing segment is needed)."""
    return len(pts) > 4 and np.linalg.norm(pts[-1] - pts[0]) > 3.0


def hole_outline(c: np.ndarray, r: float, rng: np.random.Generator, n: int = 72) -> np.ndarray:
    """A ragged, roughly round hole outline in mm."""
    t = np.linspace(0, 2 * math.pi, n, endpoint=False)
    f = np.ones(n)
    for k in range(2, 6):
        f += rng.normal(0, 0.03) * np.cos(k * t + rng.uniform(0, 2 * math.pi))
    f += rng.normal(0, 0.035, n)                      # fine raggedness
    for _ in range(int(rng.integers(0, 4))):          # a few small tears
        a = rng.uniform(0, 2 * math.pi)
        d = np.angle(np.exp(1j * (t - a)))
        f += rng.uniform(0.08, 0.22) * np.exp(-(d / 0.12) ** 2)
    return c + (r * f)[:, None] * np.stack([np.cos(t), np.sin(t)], axis=1)


class Backing:
    """What's behind the card, in card mm, big enough to fill any photo.

    A purple board, shot out in patches to white styrofoam or, further, to
    dirt (docs/SPEC.md). Patches are fixed for the session, so a hole shows
    the same thing in every photo. `kind_at` says which of the three is at a
    point, for tests and reports.
    """

    PPM = 2.5
    HALF_MM = 800.0
    EDGE_MM = 15.0  # patches stay this far inside the paper edge, so the frame round the card is board

    def __init__(self, seed: int, mix=(1.0, 0.0, 0.0), patch_mm: float = 120.0, uniform: bool = False,
                 card_half_mm: tuple[float, float] = (275.0, 260.0)):
        self.card_half_mm = card_half_mm
        rng = np.random.default_rng([seed, 200])
        n = int(2 * self.HALF_MM * self.PPM)
        w = np.asarray(mix, float) / sum(mix)
        if uniform:
            kinds = np.zeros((n, n), np.uint8)
            kinds[self._behind_card(n)] = int(rng.choice(3, p=w))
        else:
            kinds = self._patches(rng, n, w, patch_mm)
        self.kinds = kinds
        tex = {
            0: _noise_field(rng, (n, n), 150) * 6.0 + rng.normal(0, 2.0, (n, n)),       # painted board
            1: _noise_field(rng, (n, n), 4) * 5.0 + rng.normal(0, 4.0, (n, n)),          # foam beads
            2: _noise_field(rng, (n, n), 20) * 12.0 + rng.normal(0, 5.0, (n, n)),        # dirt
        }
        cols = [style.BACKING_BOARD, style.BACKING_FOAM, style.BACKING_DIRT]
        rgb = np.zeros((n, n, 3), np.float32)
        for k in range(3):
            m = kinds == k
            if m.any():
                rgb[m] = np.array(bgr(cols[k]), np.float32) + tex[k][m][:, None]
        # Soft, dark-ish torn edge where the board stops (a little depth).
        edge = cv2.morphologyEx((kinds == 0).astype(np.uint8), cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8))
        rgb *= (1 - 0.25 * cv2.GaussianBlur(edge.astype(np.float32), (0, 0), 1.0))[..., None]
        self.rgb = np.clip(rgb, 0, 255).astype(np.uint8)

    def _patches(self, rng, n: int, w: np.ndarray, patch_mm: float) -> np.ndarray:
        cell = max(4, int(patch_mm * self.PPM / 2))
        shot = _noise_field(rng, (n, n), cell) + 0.25 * _noise_field(rng, (n, n), max(2, cell // 6))
        deep = _noise_field(rng, (n, n), cell) + 0.25 * _noise_field(rng, (n, n), max(2, cell // 6))
        behind = self._behind_card(n)
        kinds = np.zeros((n, n), np.uint8)
        # Board where `shot` is lowest, then foam, and dirt where the deepest damage is.
        t_board = np.quantile(shot[behind], w[0]) if w[0] > 0 else -np.inf
        out = behind & (shot > t_board)
        kinds[out] = 1
        if w[2] > 0 and out.any():
            share = w[2] / max(w[1] + w[2], 1e-9)
            t_dirt = np.quantile(deep[out], 1 - share)
            kinds[out & (deep > t_dirt)] = 2
        return kinds

    def _behind_card(self, n: int) -> np.ndarray:
        c = np.arange(n) / self.PPM - self.HALF_MM
        hx, hy = self.card_half_mm[0] - self.EDGE_MM, self.card_half_mm[1] - self.EDGE_MM
        return (np.abs(c)[None, :] < hx) & (np.abs(c)[:, None] < hy)

    def to_px(self, x_mm, y_mm):
        return (x_mm + self.HALF_MM) * self.PPM, (self.HALF_MM - y_mm) * self.PPM

    def kind_at(self, x_mm: float, y_mm: float) -> str:
        u, v = self.to_px(x_mm, y_mm)
        return style.BACKINGS[int(self.kinds[int(v), int(u)])]
