# Session folders and outputs (`rangetarget session`)

```sh
rangetarget session <folder> [--plan plan.json] [--out <dir>] [--no-truth]
```

## Input folder

| File | What |
| --- | --- |
| `baseline.jpg` | The card before shooting (aim points on, no new holes). Required. |
| `round_01.jpg`, `round_02.jpg`, ... | One photo after each mark round, in order. `round1.jpg` also works. |
| `plan.json` | Optional. What was planned or fired (below). `--plan` points elsewhere. |
| `*.json` next to a photo | Ground truth, synthetic sessions only (`docs/ground-truth-schema.md`). |

Where the plan comes from, in order: `--plan`, then `plan.json` in the folder, then (test only) the ground truth files. With none, every round is taken as 12 m, hole size is measured from the photo, and shot counts are not checked (flag `NO_PLAN`).

## plan.json

Field names are snake_case. Everything except `round` and `expected_shots` is optional.

```json
{
  "source": "typed by the shooter",
  "rounds": [
    {
      "round": 1,
      "distance_m": 12.0,
      "hole_diameter_mm": 9.0,
      "calibre": "9mm",
      "groups": [
        { "aim_point_number": 1, "expected_shots": 5 },
        { "aim_point_number": 2, "expected_shots": 5, "fired_shots": 4 },
        { "colour": "orange", "expected_shots": 5 }
      ]
    }
  ]
}
```

- `aim_point_number` links a group to the number read off an aim point in the baseline. `colour` (a loop colour name: yellow, orange, lime, green, light_blue, dark_blue, pink, purple, white) is used only for a group with no aim point.
- `hole_diameter_mm` is the clean bullet hole size, used for scoring by the hole's edge. If missing, the visible hole size is measured from the photo (a little larger, because of the torn rim) and 1 mm is taken off.

## Outputs (in `out/session/<folder>/` unless `--out`)

| File | What |
| --- | --- |
| `r00_0_card.jpg` | Baseline: card found, rings drawn on the photo. |
| `r00_2_aim_points.jpg` | Baseline: aim points found, with the number read and how sure. |
| `rNN_0_card.jpg` | Round NN: card found in the photo. |
| `rNN_1_alignment.jpg` | How far each patch had to move to line up with the previous photo (arrows ×40). |
| `rNN_2_changes.jpg` | What changed: new holes (red), new ink (its own colour), loops outlined. |
| `rNN_3_result.jpg` | The result: holes coloured by group, loop outlines, group centre (cross), mean-radius circle, extreme spread (dashed line), unassigned holes ringed red, numbered flags explained in the band at the bottom. |
| `session.json` | Everything: baseline, per round alignment, ink marks, shots, groups with metrics and flags, session summary. Schema: `SessionResult` in `core/src/main/kotlin/rangetarget/core/session/SessionModel.kt`. |
| `shots.csv` | One row per new hole: round, shot id, group, aim point, x/y mm (card centre, +y up), offset from aim, score, source (clean / merged / grown_hole), confidence, flags. |
| `groups.csv` | One row per group: colour, aim point, distance, fired, found, score, extreme spread, mean radius, H/V spread, offset (mm and words), spreads in mrad, flags. |
| `truth_check.txt` | Synthetic sessions only: how the result compares with the known answers. |
