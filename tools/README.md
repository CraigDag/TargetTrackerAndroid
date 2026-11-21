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
```

| File | Job |
| --- | --- |
| `presets.py` | `easy`, `realistic`, `hard`: ranges for the plan, the shooting, the marking and the photo. |
| `plan.py` | Aim points, groups, shots (scatter, flyers, double holes, shots through older holes, hidden shots, one more/fewer than planned) and loops, all in card mm. No drawing. |
| `render.py` | The flat card at 8 px/mm: paper texture, rings and numbers, aim points (red stickers or hand-drawn circles with handwritten numbers), ragged holes, loops. What's behind the card (purple board, shot out in patches to white foam or dirt) is a separate layer, so a hole shows the same thing in every photo. |
| `photo.py` | Pinhole camera with tilt, turn, off-centre framing and paper curl; uneven light, white-balance drift, blur, noise, JPEG. |
| `session.py` | Puts it together and writes images + ground-truth JSON. `FIXED_SET` lists the checked-in sessions. |
| `contact.py` | Contact sheet: every photo, plus a copy with the answers drawn on. |
| `style.py` | Colours, including the three backings. |

Every random choice comes from the seed, through separate streams per purpose, so the same preset and seed always give the same session.
