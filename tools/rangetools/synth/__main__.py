"""Command line: python -m rangetools.synth {generate,fixed-set,contact-sheet} ..."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import cv2

from ..paths import REPO_ROOT, SYNTHETIC
from . import presets
from .contact import contact_sheet
from .scenarios import SCENARIOS
from .session import FIXED_SET, VALIDATION_SET, generate, session_id


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="python -m rangetools.synth", description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("generate", help="make one session")
    g.add_argument("--preset", default="realistic", choices=sorted(presets.PRESETS))
    g.add_argument("--seed", type=int, default=1)
    g.add_argument("--aim-style", choices=["sticker", "hand_drawn", "mixed"], default=None)
    g.add_argument("--rounds", type=int, default=None, help="override the preset's number of rounds")
    g.add_argument("--out", type=Path, default=REPO_ROOT / "out" / "synthetic")
    g.add_argument("--scenario", choices=SCENARIOS, default=None, help="push one edge case (see scenarios.py)")
    g.add_argument("--sheet", action="store_true", help="also write a contact sheet")

    v = sub.add_parser("validation-set", help="make the larger Phase 4 validation set (not checked in)")
    v.add_argument("--out", type=Path, default=REPO_ROOT / "out" / "validation" / "sessions")
    v.add_argument("--jobs", type=int, default=4)

    f = sub.add_parser("fixed-set", help=f"regenerate the checked-in sessions in {SYNTHETIC.relative_to(REPO_ROOT)}")
    f.add_argument("--out", type=Path, default=SYNTHETIC)
    f.add_argument("--sheets", type=Path, default=REPO_ROOT / "out" / "phase2")

    c = sub.add_parser("contact-sheet", help="contact sheet for session folders")
    c.add_argument("folders", type=Path, nargs="+")
    c.add_argument("--out", type=Path, default=REPO_ROOT / "out" / "phase2")

    a = ap.parse_args(argv)
    if a.cmd == "generate":
        plan, photos = generate(a.preset, a.seed, a.out, a.aim_style, a.rounds, a.scenario)
        folder = photos[0].image_path.parent
        print(f"wrote {len(photos)} photos to {folder}")
        if a.sheet:
            _sheet(folder, a.out)
    elif a.cmd == "fixed-set":
        for preset, seed, style, scenario in FIXED_SET:
            folder = a.out / session_id(preset, seed, style, scenario)
            if folder.exists():
                shutil.rmtree(folder)
            generate(preset, seed, a.out, style, scenario=scenario)
            print(f"wrote {folder}")
            _sheet(folder, a.sheets)
    elif a.cmd == "validation-set":
        from concurrent.futures import ProcessPoolExecutor
        with ProcessPoolExecutor(a.jobs) as ex:
            for folder in ex.map(_make_validation, [(a.out, *item) for item in VALIDATION_SET]):
                print(f"wrote {folder}")
    else:
        for folder in a.folders:
            _sheet(folder, a.out)


def _make_validation(args) -> Path:
    out, preset, seed, style, scenario = args
    folder = out / session_id(preset, seed, style, scenario)
    if folder.exists():
        shutil.rmtree(folder)
    generate(preset, seed, out, style, scenario=scenario)
    return folder


def _sheet(folder: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"contact_{folder.name}.jpg"
    cv2.imwrite(str(path), contact_sheet(folder), [cv2.IMWRITE_JPEG_QUALITY, 88])
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
