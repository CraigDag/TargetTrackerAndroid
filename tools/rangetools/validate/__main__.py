"""Phase 4 validation: python -m rangetools.validate {run,score} ...

  run    runs the Kotlin `rangetarget session` command on every session folder
         (through files only: photos in, session.json out)
  score  compares each session.json with its ground truth and writes the
         report (summary.json, CSVs, charts, worst-failure pictures, report.md)
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from ..paths import REPO_ROOT, SYNTHETIC
from .report import write_report
from .score import score_session

DEFAULT_OUT = REPO_ROOT / "out" / "validation"
# The desktop command; RANGETARGET_CLI points elsewhere (e.g. a build being tried out).
CLI = Path(os.environ.get("RANGETARGET_CLI", REPO_ROOT / "desktop" / "build" / "install" / "rangetarget" / "bin" / "rangetarget"))


def session_folders(roots: list[Path]) -> list[Path]:
    out = []
    for root in roots:
        for d in sorted(root.iterdir()):
            if d.is_dir() and (d / "baseline.jpg").exists():
                out.append(d)
    return out


def _run_one(folder: Path, results: Path) -> str:
    dest = results / folder.name
    dest.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    p = subprocess.run([str(CLI), "session", str(folder), "--out", str(dest)], capture_output=True, text=True)
    (dest / "seconds.txt").write_text(f"{time.time() - t0:.1f}\n")
    (dest / "log.txt").write_text(p.stdout + p.stderr)
    return f"{folder.name}: {'ok' if p.returncode == 0 else 'FAILED'} in {time.time() - t0:.0f} s"


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="python -m rangetools.validate", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    default_roots = [SYNTHETIC, DEFAULT_OUT / "sessions"]
    for name in ("run", "score"):
        p = sub.add_parser(name)
        p.add_argument("--sessions", type=Path, nargs="+", default=default_roots,
                       help="folders holding session folders (default: the checked-in set and out/validation/sessions)")
        p.add_argument("--out", type=Path, default=DEFAULT_OUT)
        p.add_argument("--only", nargs="*", default=None, help="only sessions whose name contains one of these")
        if name == "run":
            p.add_argument("--jobs", type=int, default=3)
        else:
            p.add_argument("--label", default="", help="shown in the report title, e.g. 'before fixes'")
            p.add_argument("--before", type=Path, default=None, help="summary.json of an earlier run, to compare with")
    a = ap.parse_args(argv)

    folders = [f for f in session_folders([r for r in a.sessions if r.exists()])
               if not a.only or any(o in f.name for o in a.only)]
    results = a.out / "results"
    if a.cmd == "run":
        if not CLI.exists():
            raise SystemExit(f"{CLI} not found: run ./gradlew :desktop:installDist first")
        with ThreadPoolExecutor(a.jobs) as ex:
            for line in ex.map(lambda f: _run_one(f, results), folders):
                print(line, flush=True)
    else:
        scores = []
        for f in folders:
            if (results / f.name / "session.json").exists():
                scores.append(score_session(f, results / f.name))
            else:
                print(f"no result for {f.name}; skipped")
        before = json.loads(a.before.read_text()) if a.before else None
        path = write_report(scores, {f.name: f for f in folders}, results, a.out / "report", a.label, before)
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
