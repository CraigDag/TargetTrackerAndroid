# tools: Python test tooling (never shipped)

Synthetic session generator (Phase 2) and validation reports (Phase 4). This code talks to the Kotlin `core` only through files: images in, JSON/CSV out. It never imports the app and the app never imports it.

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r tools/requirements.txt
pytest tools
```

- `rangetools/geometry.py` reads card dimensions from `test_data/card_geometry.json`, which is generated from `CardGeometry` in `core`. Don't hard-code card sizes here.
- `rangetools/groundtruth.py` reads and writes ground-truth files (format in `docs/ground-truth-schema.md`).

## Synthetic session generator (`rangetools/synth`)

Makes fake sessions with known answers, following the session workflow in `docs/SPEC.md`: a baseline photo of the prepared card, then one photo per mark round with new holes and new paint-marker loops.

```sh
cd tools
python -m rangetools.synth generate --preset realistic --seed 42 --sheet   # → out/synthetic/, out/phase2/
python -m rangetools.synth fixed-set                                       # rewrites test_data/synthetic/
python -m rangetools.synth contact-sheet ../test_data/synthetic/hard_mixed_s0303
python -m rangetools.synth generate --preset realistic --seed 7 --scenario edge   # one edge case pushed
python -m rangetools.synth validation-set                                  # the larger Phase 4 set → out/validation/sessions/
```

| File | Job |
| --- | --- |
| `presets.py` | `easy`, `realistic`, `hard`: ranges for the plan, the shooting, the marking and the photo. |
| `plan.py` | Aim points, groups, shots (scatter, flyers, double holes, shots through older holes, hidden shots, one more/fewer than planned) and loops, all in card mm. No drawing. |
| `render.py` | The flat card at 8 px/mm: paper texture, rings and numbers, aim points (red stickers or hand-drawn circles with handwritten numbers), ragged holes, loops. What's behind the card (purple board, shot out in patches to white foam or dirt) is a separate layer, so a hole shows the same thing in every photo. |
| `photo.py` | Pinhole camera with tilt, turn, off-centre framing and paper curl; uneven light, white-balance drift, blur, noise, JPEG. |
| `session.py` | Puts it together and writes images + ground-truth JSON. `FIXED_SET` lists the checked-in sessions. |
| `scenarios.py` | Edge cases laid on top of a preset for validation: `edge` (aim points near the paper edge, so loops run off it), `same_colour` (a re-shot aim point gets the same loop colour again), `on_sticker` (tight groups that shoot the stickers out). |
| `contact.py` | Contact sheet: every photo, plus a copy with the answers drawn on. |
| `style.py` | Colours, including the three backings. |

Every random choice comes from the seed, through separate streams per purpose, so the same preset and seed always give the same session.

## Validation (`rangetools/validate`, Phase 4)

Runs the Kotlin `rangetarget session` command over every session (the checked-in set plus `out/validation/sessions/`) and compares its `session.json` with the ground truth against the targets in `docs/SPEC.md`.

```sh
./gradlew :desktop:installDist                       # from the repo root
cd tools
python -m rangetools.synth validation-set            # once (about 10 minutes)
python -m rangetools.validate run                    # about 70 s per session, 3 at a time
python -m rangetools.validate score --label "after fixes" --before ../out/validation/before/report/summary.json
```

`score` writes `out/validation/report/`: `report.md` (the page), `summary.json` (every number, for comparing runs), `shots.csv` (each true shot: found, error, right group, flagged, edge-case tags), `false_holes.csv`, `flags.csv` (each flag judged right or wrong against the truth), charts, and `failures.jpg` (worst failures cut from the app's own result pictures). `--only <text>` limits a run to sessions whose name contains the text; `RANGETARGET_CLI` points `run` at another build.

How it judges: a found hole and a true shot match when their centres are within 6 mm (closest pairs first). A *clean* hole touches no other hole on the card; the detection target counts clean holes. A found hole matching a shot that went exactly through an older hole (invisible) is not a false hole. False holes are counted per card, over the whole session.
