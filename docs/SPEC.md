# Range Target Analysis App — Spec v0.3.3

Updated 2026-11-21

## Purpose and scope

An Android app that turns photos of a marked paper target into per-group training data, by comparing each photo with the previous one from the same card. The shooter's coloured marker loops are the input method. They tell the app which holes belong together; the session plan tells it what each group was for.

First target: the ISSF 25 m precision / 50 m pistol card (Krüger 3100, 55 × 52 cm, 50 mm ten, 25 mm ring spacing). It is used at the club with loaned pistols, usually at 12 m and sometimes 25 m.

**In scope**

- A session plan set before shooting (exercise, groups, rounds per group)
- Photo diffing between mark rounds to find new holes and new ink
- Assigning each new hole to the group loop drawn in the same round
- Per-group and per-session metrics, compared against the plan
- A photo log and training diary kept with every result
- Recognising aim points (printed stickers or hand-drawn circles) and target codes

**Out of scope**

- Measuring time or speed. Cadence is a label the shooter chooses, never a measurement.
- Live scoring from the firing line.

**Why build it.** Existing apps either auto-score a whole card as one session (TargetScan, AimScan, Shooting Target Scorer) or measure groups you tap by hand (Ballistic-X, My Shooting, OnTarget TDS). None of them reads the shooter's own marks, and none separates several drills on one heavily used card.

## Session workflow

A session runs plan → set up card → baseline → (shoot → mark → photo → confirm) × N → results. Each mark round should take about 30 seconds in the app.

1. **Plan.** Pick an exercise template; set groups per round and rounds per group (e.g. 4 × 5).
2. **Set up the card.** Add the target code and aim points: numbered stickers, or hand-drawn numbered circles.
3. **Baseline photo.** Photograph the prepared card before the first shot. The app records the card and every aim point.
4. **Shoot a mark round.** Optionally photograph the ammo box (or type the ammo) and change the position, gun or distance if needed. Then fire the planned groups, in aim-point number order.
5. **Mark.** When the range is called for marking, draw one loose loop per group in that group's colour.
6. **Photo.** Photograph the card on the frame (walk downrange). Behind the card is a purple board, often shot out to white styrofoam or to the dirt behind it, so a hole can show any of the three.
7. **Confirm.** The app shows new holes and new loops, and flags anything it couldn't assign. Tap to fix, or draw: circle holes with a finger to make or redraw a group, and edit or delete any loop (see Marking convention, "Marking in the app").
8. **Tag.** Confirm group labels (pre-filled from the plan) and the round's gun, sight type and distance (carried over from the previous round). Add an optional note.
9. **Repeat 4–8.** The plan can change mid-session; changes are logged.
10. **Results.** Session summary against the plan, with every photo attached.

## Session plan and exercise templates

The goal is set before the first shot. It decides which numbers lead the results, and it gives the app the expected shot counts.

A plan holds:

- **Structure:** groups per mark round and rounds per group (the expected counts).
- **Variable:** what differs between groups, if anything (cadence, grip, hand).
- **Lead metrics:** what the results screen shows first.
- **Target (optional):** e.g. "every 5-shot group inside 40 mm".
- **Session details:** date, notes, starting distance, starting gun (Gun Model + Iron Sight / Red Dot).

| Template | Structure | Variable | Leads with |
| --- | --- | --- | --- |
| Grouping | 4 groups × 5 | none | extreme spread, mean radius |
| Cadence | 4 groups × 5 | declared cadence (slow / medium / fast / rapid) | spread by cadence |
| Centre check | 2 groups × 10 | none | group centre offset |
| Precision series | 1 group × 5, repeated | none | score, consistency across series |
| Free | any | free-text tags | everything |

**Shot calling (optional).** After a group, the shooter taps where they think each shot went, before the photo. The app compares calls with the real holes. Off by default.

## Aim points

Grouping drills start with the card set up with numbered aim points, typically one per 1/8 sector. The numbers set the shooting order; placement and order are the shooter's choice (e.g. NW, NE, SE, SW, N, E, S, W).

The app uses aim points to:

- read each aim point's position and number from the baseline photo, before holes damage it
- expect each group around its aim point, as a second check on loop assignment
- measure group centre offset from the aim point, and compare results by position on the card
- keep the order even when a round is merged or a photo is missed

**Printed aim-point stickers (preferred).** Solid **red** discs with a bold **black** number.
- 12 m: **~30 mm** disc, number ~18 mm tall.
- 25 m: **~60 mm** disc, number ~35 mm tall.
- Sizes to be confirmed with a test strip (20/30/40 mm at 12 m). A thin black outline ring is an option to test.

**Hand-drawn aim points (fallback).** A filled marker circle (~30 mm at 12 m) with a number inside, drawn before the baseline photo. The group fired at it is looped in the same colour.
- The colour links the group to its aim point.
- Circle centres come from the colour fill, accurate to about 1 mm.
- Handwritten numbers are read as a guess, limited to the digits in use (e.g. 1–8). The baseline screen shows the reading for a one-tap confirm or fix.
- With one colour per aim point, a card supports as many aim points as there are reliably distinct colours (about 6, to be tested). With repeated colours, the confirmed number tells them apart.

**Without aim points.** Group size is valid anywhere, but zones on bare paper report size only, because there's no defined aim point.

**Layout guidance (12 m, 8 aim points).** N/E/S/W at ~18 cm from the card centre, diagonals at ~25 cm towards the corners. That gives ~17–18 cm between neighbours, and 12+ aim points fit at 12 m.

## Marking convention (v0.2)

One loose loop per group, one colour per group, each colour used once per mark round. Colours can be reused in later rounds, because the diff only considers ink added in the current round.

1. Draw one loop around the whole group, a few millimetres clear of the holes. Don't ring individual holes.
2. Each loop colour at most once per round. When using hand-drawn aim points, the loop colour matches its aim point.
3. Handwriting is only used for aim-point numbers, which are confirmed in the app. No other written labels.
4. A shot you choose not to loop is flagged as unassigned, never dropped.
5. Avoid loops that overlap within the same round where possible.

**Marking in the app (v0.3.5).** Loops can be drawn on the card with paint, or drawn on the phone instead, or both in one session (round by round, or group by group). With no paint, the shooter photographs the round, the app shows the new holes it found, and the shooter circles each group with a finger and picks its aim point. A loop drawn in the app is taken as exact: no colour reading, no edge or overlap problems. On the confirm screen every loop, painted or drawn, can be redrawn, split, merged or deleted, and single holes moved between groups. Paint still leaves a record on the card itself and is quicker when several groups are shot before walking downrange.

**Loop palette** (must show on beige and, where loops cross it, on black). Bright paint markers. The final palette depends on what markers are available locally.

| Candidates | Avoid |
| --- | --- |
| yellow, orange, lime green, light blue, pink/magenta, white (on black only) | red (reserved for printed aim-point stickers), dark blue, purple, black, dark red |

**Target code.** A printed code sticker (preferred: bold colour + shape plus a small ArUco marker and mm scale bar) or a hand-drawn colour + shape at the top of each fresh card. It identifies the card across photos.

## Image processing

1. **Find the card.** The black aiming disc (200 mm, high contrast) gives a first centre and scale estimate. Ring lines, the paper edges, the target code and any corner markers refine it.
2. **Flatten.** Warp to a top-down view in mm using the known ring geometry. Correct for camera angle and card curl.
3. **Align to the previous photo.** Fine registration on rings and print, to a fraction of a millimetre.
4. **Diff.** Separate what changed into:
    - **new holes:** paper newly missing, showing whatever is behind it at that spot: purple board, white foam (lighter than the paper) or dirt (darker). Look for change in either direction, not just darker.
    - **new ink:** marks drawn this round, split by colour; closed loops vs stray ink
5. **Assign.** Each new hole goes to the new loop that encloses it, cross-checked against the expected aim point. Old loops are ignored.
6. **Check counts** against shots actually fired; resolve or flag mismatches.
7. **Measure.** Hole centres in mm → score, group metrics, angular values.

Approach: classical OpenCV for v0.1, written once in Kotlin in the shared `core` module (see Build plan). A trained hole detector is a later upgrade if ragged or overlapping holes prove hard.

## Validation targets

The analysis core must meet these on synthetic sessions with phone-like distortion (the "realistic" preset), run headlessly on a computer, before Android app work starts:

- hole centre error ≤ 2 mm (95th percentile)
- ≥ 98% of non-overlapping holes detected, with ≤ 1 false hole per card
- ≥ 98% of holes assigned to the correct group

## Edge cases

Rule: never guess silently. Anything ambiguous is flagged for a tap.

| Case | Handling |
| --- | --- |
| New hole inside an old loop | Ignored as a candidate. Only this round's loops count. |
| New hole inside two new loops | Flagged; shooter assigns. Aim-point proximity proposes a default. |
| New hole outside any new loop | Flagged as unassigned: assign, tag as flyer, or ignore. |
| New shot through an existing hole | A partial overlap shows as a grown or reshaped old hole and counts as a new shot. A perfect overlap is invisible: the count check flags the shortfall and suggests likely holes; the shooter confirms. |
| Fewer holes than shots fired | Look for double holes, grown holes and ragged tears; otherwise log a miss or off-paper shot. |
| More holes than expected | Flag (missed earlier hole or a neighbour's shot). |
| Stray ink (numbers, scribbles, others' marks) | New ink that isn't a closed loop is ignored and shown greyed out; the shooter can promote it to a loop. |
| Colour used twice in one round | Warn; shooter separates the groups. |
| Missed photo between rounds | Both rounds are analysed as one combined round; the shooter can split them by hand afterwards. |
| New card mid-session | Recognised by its target code; starts from a fresh baseline. Cards don't carry over between sessions. |
| Holes disappear (patched) | Treated as normal; the patched area is removed from the active state. |
| Card moved between photos | Realigned via rings and code; fails clearly if the card can't be found. |
| Heavy overlap (e.g. 50+ shots on one sticker) | Group outline and centre only; individual shots marked unreliable. Recommend 5–10 shots per group. |
| Glare or poor lighting | Warn and suggest retaking the photo. |

## Data model

- **Session:** date, club, plan (template, structure, variable, target), notes, plan changes (logged), rounds fired
  - **Card:** target code, target type, baseline photo, aim points (number, colour/type, position mm)
    - **Mark round:** round number, distance (default 12 m), Gun Model, sight type (Iron Sight / Red Dot), calibre, ammunition (photo or typed), position (two-handed default; one-handed strong/weak), photo, timestamp, alignment quality, notes
      - **Group:** colour, aim point, labels/tags, expected shot count, shots actually fired, detected count, flags
        - **Shot:** x/y mm from card centre, x/y relative to aim point, score, confidence, called position (optional)

**Guns.** A saved list (Gun Model + sight type, e.g. "CZ Shadow 2 · Iron Sight"). The same model with a different sight is a separate entry. Each gun learns a typical point-of-impact offset over time, so group centres can be reported with the gun's offset removed.

Storage: local on the phone (SQLite + image files), with CSV/JSON export. Optional Nextcloud sync later.

## Metrics and results

| Level | Metrics |
| --- | --- |
| Group | score, extreme spread, mean radius, centre offset from aim point (with direction), vertical/horizontal spread |
| Mark round | groups side by side; overlay on the card photo |
| Session | trend across rounds, comparison by label and by gun, plan vs actual, flags resolved |
| Over time | trends by template, label, Gun Model, sight type, position; each linked to photos |

- **Units:** mm, plus angles (mrad; MOA optional) computed from each round's distance, so 12 m and 25 m results compare directly. Scores on the ISSF card at 12 m are labelled as card scores at that distance.
- **Honest statistics:** a 5-shot group is mostly noise. Show ranges, and only call something a trend when enough groups support it.
- **Views:** card overlay, composite (all groups with one label stacked on their aim points), and a session sheet exportable for a coach.
- **Training diary:** photo, gun, ammo and notes per round; rounds fired and sessions attended per month and year.
- **Scoring:** a setting, starting with whole rings; decimals later. Tracking comes first.

## Stickers

| Sticker | Size | Contents | Purpose |
| --- | --- | --- | --- |
| Aim point | ~30 mm (12 m) / ~60 mm (25 m) | solid red disc, bold black number | exact aim point and shooting order |
| Target code | ~40 mm | colour + shape, small ArUco, mm scale bar | card ID, alignment anchor, print-scale check |
| Corner markers (optional) | ~40 mm × 4 | ArUco | extra alignment |
| Ruler strip | ~100 mm | mm ruler markings | validating measurements |

Print on matte sticker paper at 100% scale, and check against the scale bar. Codes and corner markers go in the card corners, outside the 1-ring.

## Build plan

**Architecture.** The image analysis is written once, in Kotlin with OpenCV, as a plain JVM module (`core`). The same module runs on a computer during development (through a command-line tool, `desktop`) and inside the Android app later, unchanged. There is no separate prototype to port. Python is used only for test tooling that never ships: the synthetic session generator and validation reports.

| Part | Language | Ships in the app? | Job |
| --- | --- | --- | --- |
| `core` | Kotlin + OpenCV | yes | the whole analysis pipeline, flags, metrics, debug drawing |
| `desktop` | Kotlin | no | run `core` on photos and session folders; write annotated images and CSV/JSON |
| `android` | Kotlin + OpenCV Android SDK | yes (it is the app) | screens, camera, storage; calls `core` |
| `tools` | Python | no | synthetic generator with ground truth; validation report and charts |

1. **Analysis core on real + synthetic data, on the computer** (now, while the owner is away from the range). Project set-up; real-photo card detection and hole detection; the synthetic session generator (Python) with ground truth; the full pipeline in `core`; validation against the targets above, run from the desktop tool.
2. **Sticker sheets.** Print-ready PDFs: aim-point test strip and sets, target codes, ruler strip.
3. **First real marked session** (when the owner is back): run the desktop tool on real round photos; revise the convention.
4. **Android app.** Kotlin, reusing `core` unchanged, with the OpenCV Android SDK, offline. Screens: plan, capture (with framing guide), confirm, results, history, guns. Before the screens, a minimal app that runs `core` on a photo on the target phone and shows the debug image, to prove it runs on the phone with acceptable speed.
5. **Later.** Trained hole detector, shot calling, coach sharing, Nextcloud sync, other target types.

**Two builds.** A personal build includes everything, including home dry-fire and ACE VR sessions in the diary. A club build for other members leaves those out.

**Test loop until the app exists:** the owner sends round photos and notes; the desktop tool returns annotated images and stats.

## Decided

- Photos are taken at the card, on the same frame. What shows through a hole varies across the card (purple board, white foam or dirt) but stays put between photos.
- One target phone (current flagship Android); processing on-device.
- Claude writes all code; the owner manages, tests and gives feedback.
- No Python prototype: analysis written once in Kotlin + OpenCV (`core`), tested on a computer, reused unchanged in the app. Python only for test tooling.
- Code lives in a private GitHub repository.
- Scoring starts as whole rings.
- Distance is usually 12 m.

## Open questions

- Loop marker palette (depends on local shops).
- Does the club patch holes between rounds or shooters?
- Other club targets in v1 (e.g. 25 m rapid fire)?
- App delivery: direct APK install or Play Store test track?

## Changelog

- v0.3.5 (2026-11-21): the owner approved marking groups in the app as well as with paint: circle the group on the phone after the photo, and redraw or edit any loop on the confirm screen. Painted loops stay supported; either can be used, mixed within a session. App work (Phase 6); no change to the analysis core.
- v0.3.4 (2026-11-21): Phase 3 (pipeline). `rangetarget session <folder>` runs a whole session (folder layout, `plan.json` and outputs in `docs/session-files.md`). Each photo is fitted to the card, then lined up with the previous photo with a smooth sub-millimetre correction. Lighting is evened out before comparing. A change is a new hole if the paper changed in either direction and the new colour is not vivid; vivid new colour is ink. Loops are read per colour; a loop with a small gap, or closed by a hole, an aim point or the paper edge, still counts (flagged as having a gap). Shots are scored by the hole's edge (a hole touching a ring line takes the higher ring) using the plan's hole size, else the size measured from the photo less 1 mm for the torn rim. The plan (aim point, expected and fired shots, distance, hole size per round) is an input file for now; the phone will collect it on the plan screen. Without a plan, distance is taken as 12 m and counts are not checked (flagged).
- v0.3.3 (2026-11-21): Phase 2. Synthetic session generator in `tools/rangetools/synth` with easy / realistic / hard presets and a fixed set in `test_data/synthetic/`. The owner described the backing: a purple board that is often shot out to white styrofoam or dirt, so "the backing is always the same" now reads "same frame, but what shows through a hole varies across the card". New holes can be lighter than the paper. The generator's presets mix the three backings.
- v0.3.2 (2026-11-21): Phase 1 (real photo). The paper measured about 549 × 519 mm with the card upright, so it is 550 mm wide × 520 mm tall; `CardGeometry` updated. Card finding now goes black disc → ring lines → paper edges, and adds a small smooth bend on top of the flat-paper perspective map, because a card lying on a floor (or slightly curled on a frame) is not flat enough for a plain perspective map (it left up to ~2 mm error). Printed logos that look like holes are listed in `CardGeometry.printedMarks`; holes found there are flagged, not dropped. Vividly coloured stickers are treated as unresolvable clusters (Edge cases: heavy overlap).
- v0.3.1 (2026-11-21): Phase 0 set-up. OpenCV pinned at 4.9.0 for desktop and Android (ArUco checked on both). Ground-truth JSON format written down in `docs/ground-truth-schema.md`. Card paper taken as 520 mm wide × 550 mm tall until checked on a real card; nothing relies on which side is which.
- v0.3 (2026-11-21): dropped the Python prototype; analysis core written once in Kotlin + OpenCV as a shared JVM module, tested on a computer via a desktop CLI and reused unchanged in the Android app; Python kept only for test tooling (synthetic generator, validation reports). Validation targets moved here from `CLAUDE.md`. Build plan rewritten.
- v0.2 (2026-11-21): numbered aim points (red printed stickers or hand-drawn circles) added; handwriting allowed only for confirmed aim-point numbers; red removed from the loop palette; sticker sizes set for 12 m; build order changed to synthetic-first.
- v0.1 (2026-11-21): first draft.
