"""Turns the flat card into a "phone photo".

A simple pinhole camera looks at the card from a random pose (distance, tilt,
in-plane turn, off-centre), the paper bulges gently like a cylinder (curl),
then the photo gets uneven light, white-balance drift, blur, sensor noise and
JPEG compression. Every setting is recorded in the ground truth's
`distortion` block.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np

from ..geometry import CardGeometry
from ..groundtruth import Distortion
from .presets import Preset
from .render import PPM as FLAT_PPM
from .render import Backing, FlatCard

FOCAL_SHARE = 0.75   # focal length as a share of the long image side (~26 mm-equivalent main camera)
SHADOW_SHIFT_MM = 0.7
SHADOW_STRENGTH = 0.45


@dataclass
class Camera:
    width: int
    height: int
    focal_px: float
    rotation_deg: float
    tilt_x_deg: float
    tilt_y_deg: float
    t: np.ndarray            # card centre in camera coordinates, mm
    curl_mm: float
    curl_axis_deg: float
    curl_reach_mm: float     # half the paper's extent across the curl axis

    @property
    def R(self) -> np.ndarray:
        a, bx, by = (math.radians(v) for v in (self.rotation_deg, self.tilt_x_deg, self.tilt_y_deg))
        # In-plane turn: positive turns the card anticlockwise as seen in the photo.
        rz = np.array([[math.cos(a), math.sin(a), 0], [-math.sin(a), math.cos(a), 0], [0, 0, 1]])
        rx = np.array([[1, 0, 0], [0, math.cos(bx), -math.sin(bx)], [0, math.sin(bx), math.cos(bx)]])
        ry = np.array([[math.cos(by), 0, math.sin(by)], [0, 1, 0], [-math.sin(by), 0, math.cos(by)]])
        return rz @ rx @ ry

    @property
    def K(self) -> np.ndarray:
        return np.array([[self.focal_px, 0, self.width / 2], [0, self.focal_px, self.height / 2], [0, 0, 1]])

    def homography(self) -> np.ndarray:
        """Flat-card map from card mm (x right, y up) to image px (x right, y down)."""
        R = self.R
        H = self.K @ np.column_stack([R @ [1, 0, 0], R @ [0, -1, 0], self.t])
        return H / H[2, 2]

    def bulge(self, x: np.ndarray, y: np.ndarray) -> np.ndarray:
        """Paper height towards the camera, mm (cylinder along the curl axis)."""
        if self.curl_mm == 0:
            return np.zeros_like(x)
        a = math.radians(self.curl_axis_deg)
        d = -x * math.sin(a) + y * math.cos(a)
        u = np.clip(d / self.curl_reach_mm, -1, 1)
        return self.curl_mm * (1 - u * u)

    def project(self, x, y) -> tuple[np.ndarray, np.ndarray]:
        """Exact card mm → image px, including the curl."""
        x = np.asarray(x, dtype=np.float64)
        y = np.asarray(y, dtype=np.float64)
        z = self.bulge(x, y)
        P = np.stack([x, -y, -z], axis=-1) @ self.R.T + self.t
        return (self.focal_px * P[..., 0] / P[..., 2] + self.width / 2,
                self.focal_px * P[..., 1] / P[..., 2] + self.height / 2)

    def unproject(self, u, v, iterations: int = 4) -> tuple[np.ndarray, np.ndarray]:
        """Image px → card mm, inverting `project` (the flat map, then a few corrections for the curl)."""
        Hi = np.linalg.inv(self.homography())

        def flat_inv(uu, vv):
            w = Hi[2, 0] * uu + Hi[2, 1] * vv + Hi[2, 2]
            return (Hi[0, 0] * uu + Hi[0, 1] * vv + Hi[0, 2]) / w, (Hi[1, 0] * uu + Hi[1, 1] * vv + Hi[1, 2]) / w

        tx, ty = flat_inv(u, v)
        x, y = tx, ty
        if self.curl_mm == 0:
            return x, y
        for _ in range(iterations):
            pu, pv = self.project(x, y)
            ex, ey = flat_inv(pu, pv)
            x, y = x + (tx - ex), y + (ty - ey)
        return x, y


def random_camera(preset: Preset, geo: CardGeometry, rng: np.random.Generator) -> Camera:
    w, h = preset.image_sizes[int(rng.integers(len(preset.image_sizes)))]
    pw, ph = geo.paper_width_mm, geo.paper_height_mm
    corners = np.array([[-pw / 2, ph / 2], [pw / 2, ph / 2], [pw / 2, -ph / 2], [-pw / 2, -ph / 2]])
    fill = rng.uniform(*preset.card_fill)
    for attempt in range(40):
        f = FOCAL_SHARE * max(w, h)
        scale = fill * min(w / pw, h / ph)  # px per mm at the card centre
        z0 = f / scale
        off = rng.uniform(-preset.centre_offset, preset.centre_offset, 2) * [w, h]
        t = np.array([off[0] / scale, off[1] / scale, z0])
        curl_axis = rng.choice([0.0, 90.0]) + rng.normal(0, 8)
        a = math.radians(curl_axis)
        reach = abs(math.sin(a)) * pw / 2 + abs(math.cos(a)) * ph / 2
        cam = Camera(width=w, height=h, focal_px=f,
                     rotation_deg=rng.uniform(-preset.rotation_deg, preset.rotation_deg),
                     tilt_x_deg=rng.uniform(-preset.tilt_deg, preset.tilt_deg),
                     tilt_y_deg=rng.uniform(-preset.tilt_deg, preset.tilt_deg),
                     t=t, curl_mm=rng.uniform(*preset.curl_mm), curl_axis_deg=curl_axis, curl_reach_mm=reach)
        u, v = cam.project(corners[:, 0], corners[:, 1])
        m = 0.015 * min(w, h)
        if u.min() > m and v.min() > m and u.max() < w - m and v.max() < h - m:
            return cam
        fill *= 0.97  # whole card must be in the frame; step back a little and retry
    raise RuntimeError("couldn't fit the card in the frame")


@dataclass
class Look:
    """Per-photo light and image quality."""
    lighting_min: float
    lighting_dir_deg: float
    blur_sigma_px: float
    noise_sigma: float
    jpeg_quality: int
    white_balance: tuple[float, float, float]


def random_look(preset: Preset, rng: np.random.Generator) -> Look:
    wb = tuple(float(1 + rng.uniform(-preset.white_balance, preset.white_balance)) for _ in range(3))
    return Look(lighting_min=float(rng.uniform(*preset.lighting_min)),
                lighting_dir_deg=float(rng.uniform(0, 360)),
                blur_sigma_px=float(rng.uniform(*preset.blur_sigma_px)),
                noise_sigma=float(rng.uniform(*preset.noise_sigma)),
                jpeg_quality=int(rng.integers(preset.jpeg_quality[0], preset.jpeg_quality[1] + 1)),
                white_balance=wb)  # type: ignore[arg-type]


def take_photo(card: FlatCard, backing: Backing, cam: Camera, look: Look, geo: CardGeometry,
               rng: np.random.Generator) -> tuple[bytes, Distortion]:
    w, h = cam.width, cam.height
    # Where each photo pixel lands on the card (mm), on a coarse grid, then smoothed up.
    # Grid samples sit at cell centres (j*step + (step-1)/2), which is what cv2.resize assumes.
    step = 4
    nu, nv = -(-w // step), -(-h // step)
    off = (step - 1) / 2
    gu, gv = np.meshgrid(np.arange(nu) * step + off, np.arange(nv) * step + off)
    mx, my = cam.unproject(gu, gv)
    mx = cv2.resize(mx.astype(np.float32), (nu * step, nv * step), interpolation=cv2.INTER_LINEAR)[:h, :w]
    my = cv2.resize(my.astype(np.float32), (nu * step, nv * step), interpolation=cv2.INTER_LINEAR)[:h, :w]
    mx, my = _check_map(cam, mx, my)

    # Shadow of the paper edge falling on the backing inside each hole.
    a = math.radians(look.lighting_dir_deg)
    sx, sy = SHADOW_SHIFT_MM * FLAT_PPM * math.cos(a), SHADOW_SHIFT_MM * FLAT_PPM * math.sin(a)
    alpha = card.alpha.astype(np.float32) / 255
    shifted = cv2.warpAffine(alpha, np.float32([[1, 0, sx], [0, 1, sy]]), (card.w, card.h))
    shadow = cv2.GaussianBlur(shifted, (0, 0), 0.35 * FLAT_PPM) * (1 - alpha)

    fx = (mx + card.half_w) * FLAT_PPM
    fy = (card.half_h - my) * FLAT_PPM
    paper = cv2.remap(card.rgb, fx, fy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT).astype(np.float32)
    pa = cv2.remap(alpha, fx, fy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)[..., None]
    sh = cv2.remap(shadow, fx, fy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)[..., None]
    bx, by = backing.to_px(mx, my)
    board = cv2.remap(backing.rgb, bx.astype(np.float32), by.astype(np.float32), cv2.INTER_LINEAR,
                      borderMode=cv2.BORDER_REFLECT).astype(np.float32)
    img = pa * paper + (1 - pa) * board * (1 - SHADOW_STRENGTH * sh)
    del paper, board

    # Uneven light: falls across the card in one direction, plus a soft vignette.
    span = math.hypot(geo.paper_width_mm, geo.paper_height_mm) / 2
    t = np.clip(((mx * math.cos(a) + my * math.sin(a)) / span + 1) / 2, 0, 1)
    light = 1 - (1 - look.lighting_min) * t
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    rr = ((xx - w / 2) ** 2 + (yy - h / 2) ** 2) / ((w / 2) ** 2 + (h / 2) ** 2)
    light = light * (1 - 0.08 * rr)
    img *= light[..., None]
    img *= np.array(look.white_balance, np.float32)[None, None, :]

    if look.blur_sigma_px > 0:
        img = cv2.GaussianBlur(img, (0, 0), look.blur_sigma_px)
    img += rng.normal(0, look.noise_sigma, img.shape).astype(np.float32)
    out = np.clip(img, 0, 255).astype(np.uint8)
    ok, buf = cv2.imencode(".jpg", out, [cv2.IMWRITE_JPEG_QUALITY, look.jpeg_quality])
    if not ok:
        raise RuntimeError("JPEG encoding failed")

    H = cam.homography()
    dist = Distortion(
        homography_mm_to_px=[float(v) for v in H.ravel()],
        rotation_deg=round(cam.rotation_deg, 3),
        tilt_x_deg=round(cam.tilt_x_deg, 3),
        tilt_y_deg=round(cam.tilt_y_deg, 3),
        curl_mm=round(cam.curl_mm, 3),
        curl_axis_deg=round(cam.curl_axis_deg, 3),
        lighting_min=round(look.lighting_min, 3),
        blur_sigma_px=round(look.blur_sigma_px, 3),
        noise_sigma=round(look.noise_sigma, 3),
        jpeg_quality=look.jpeg_quality,
    )
    return buf.tobytes(), dist


def _check_map(cam: Camera, mx: np.ndarray, my: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Checks the smoothed-up map against the exact one on a sparse grid; if it
    is off by more than a quarter of a flat pixel, computes it at full size."""
    h, w = mx.shape
    vv, uu = np.mgrid[0:h:97, 0:w:97].astype(np.float64)
    ex, ey = cam.unproject(uu, vv)
    err = np.hypot(mx[0:h:97, 0:w:97] - ex, my[0:h:97, 0:w:97] - ey).max()
    if err * FLAT_PPM < 0.25:
        return mx, my
    vv, uu = np.mgrid[0:h, 0:w].astype(np.float64)
    fx, fy = cam.unproject(uu, vv)
    return fx.astype(np.float32), fy.astype(np.float32)
