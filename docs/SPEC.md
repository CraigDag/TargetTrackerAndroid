# Range Target Analysis App — Spec v0.4.0

Updated 2026-11-24

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
5. **Mark.** When the range is called for marking, draw one loose loop per group in that group's colour, or skip the paint and circle the groups in the app after the photo.
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

**Shot calling (optional; the owner decided on 2026-11-25 to leave it out of the app for now).** After a group, the shooter taps where they think each shot went, before the photo. The app compares calls with the real holes. Off by default.

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
6. Try to keep loops at least 1 cm inside the paper edge. This is a request, not a rule: the app must still read a loop that runs to the edge, and flags it if it can't.
7. If the same aim point is shot again after a marking, use a different loop colour from the last loop there. (Without a marking in between, the shooter moves to another aim point anyway, so groups don't pile up unmarked.)

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

How they are measured (Phase 4, `python -m rangetools.validate`, see `tools/README.md`): a found hole matches a true shot when their centres are within 6 mm. "Non-overlapping" means the hole touches no other hole on the card. "Per card" means over the whole session on that card, not per photo. Assignment counts every found hole that matched a true shot. Holes that overlap others are not in the detection target, but the report shows how many are found, and every one missed must be flagged (a group short of its shot count).

## Edge cases

Rule: never guess silently. Anything ambiguous is flagged for a tap.

| Case | Handling |
| --- | --- |
| New hole inside an old loop | Ignored as a candidate. Only this round's loops count. |
| New hole inside two new loops | Flagged; shooter assigns. Aim-point proximity proposes a default. |
| New hole outside any new loop | Flagged as unassigned: assign, tag as flyer, or ignore. |
| New shot through an existing hole | A partial overlap shows as a grown or reshaped old hole and counts as a new shot. A perfect overlap is invisible: the count check flags the shortfall and suggests likely holes; the shooter confirms. |
| Fewer holes than shots fired | Look for double holes, grown holes and ragged tears. A merged hole is split only when the extra hole clearly explains missing paper on its own; a crescent next to an old hole is never split. Otherwise flag the shortfall with the likeliest spots (a big or odd-shaped hole, older holes inside the loop); the shooter confirms, or logs a miss or off-paper shot. |
| More holes than shots fired, inside one loop | First try reading a merged hole as fewer holes (flagged); if still too many, flag. |
| Loop runs to or off the paper edge | The paper edge closes it; flagged so the shooter checks its holes. |
| Loop left open (gap wider than a few mm) | If its ink follows most of the outline it would have when closed, it is closed with a straight line and flagged as having a gap. |
| New loop drawn over an old loop of the same colour | Where the two run together the new loop shows no change; the old ink there counts as part of the new loop. |
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

**Kit.** Three saved lists, edited under Menu → My kit and picked on the plan screen. Only the name is required; every other field can be filled in later.

- **Gun:** name, calibre, sight type (Iron Sight / Red Dot / other), photo, club number if it is a club gun. The same model with a different sight is a separate entry. Later, each gun learns a typical point-of-impact offset, so group centres can be reported with the gun's offset removed.
- **Ammo:** brand, product name, calibre, bullet weight (grains), bullet type (full metal jacket, lead round nose, wadcutter, other), lot number, box photo. Field list proposed by Claude; to be checked once real use shows what matters.
- **Target:** name, type, photo. v1 measures only the ISSF 25 m precision card; other targets are stored with their photos for later.

**Edits.** Each mark round keeps a list of edits made after the fact in Markup (loop redrawn, loop removed, hole marked as flyer, with time) on top of the original analysis. The photo is never altered. Results are always computed from the latest version; any edit can be undone, or the round put back to the original.

**Distance** is any value in metres (12 m and 25 m as quick picks, or custom), stored per round and used for every angular value.

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

## App v1 (first test build)

Agreed with the owner on 2026-11-21 from a clickable mock-up and the owner's own sketch. The goal is a basic working app that club members can try. Dark mode is the default, with light as an option in Settings.

**Home:** a greeting with the shooter's name (asked once on first launch; no accounts), a large **View stats** tile, and two buttons: **New session** and **Markup**. A menu (☰) holds My kit, Settings (theme, name, default distance, units), Export everything, About.

**New session:** quick plan (drill template, distance, gun and ammo from My kit) → blank-card photo (in-app camera with framing corners, a level that shows when the phone is square on, tap to focus; the phone's own camera app as a fallback; then the photo check with retake advice) → card check (rings and aim points shown; tap to add a missing aim point; the card centre is always an aim point) → round brief → round photo → circle groups with a finger (the app proposes the nearest aim point; holes outside any loop are flagged) → round results → next round or finish → session summary.

**Round results:** per group, group size (mm and mrad), centre offset from the aim point in words ("6 mm low, 4 mm left") and, for groups aimed at the card centre, the score. A one-line round observation phrased as a question, to support self-diagnosis.

**View stats:** average group size over time, average group size by gun and by ammo (from My kit, as at the default distance; fewer than 5 groups is labelled "too few to tell"), a **Patterns** button, and the session list; tap a session for its summary and photos.

**Patterns** (from View stats, for this session, the last 5 sessions or all, optionally one gun; also from a session summary). Two pictures and a few notes:
- *Shots around each group's middle:* every group laid on top of the others, middle to middle, so the shape of the spread shows.
- *Group middles around the aim point:* one dot per group, with their average.
- *Notes,* each a plain sentence plus a question (the same coaching approach as the round observations): whether shots spread side to side, up and down, along a slant or evenly; whether groups land off the aim point on average and mostly on the same side; whether group middles move around more than shots within a group; shots well away from the rest of their group. Notes appear only from about 12 shots in 3 or more groups.
- Groups shot at different distances are put on one scale by angle and shown as millimetres at the default distance.

**Photo check.** After the card is found in any photo (blank card or round), the app measures how good the photo is for finding holes and, if needed, shows plain advice and a **Retake photo** button before the groups are circled. Checks: blur (how far the black disc's edge is smeared), card too small in the photo, steep angle, part of the card in shadow, glare, and far more new holes than shots planned (usually a change of light or angle between photos). The limits are set so every careful synthetic photo and the real test photo pass (details in `docs/photo-check.md`). The check never changes the analysis; the shooter can carry on regardless.

**Hidden shots.** When a circled group has fewer holes than the shots planned for it, the circle screen draws dashed yellow rings inside that loop where the missing shot is most likely to be: older holes (a shot can go straight through one and leave no new mark) and new holes big enough to be two touching shots. It says so in a sentence and leaves the call to the shooter; with **Add missed hole**, a tap on a ring puts the shot at that hole's middle. Nothing is added by itself. On 4 synthetic sessions every one of 14 missed shots was within 6 mm of a ring.

**Round notes.** Each round's results screen has a free-text note ("What did you feel on the shots? What will you try next round?"), saved with the round and shown on the session summary.

**Markup:** pick a photo **by session**, or **from device** (a gallery photo, which adds a session by hand, labelled as such; a lone photo has nothing to diff against, so every hole is a candidate and the shooter circles only the groups to measure). Then redraw, remove or add loops and mark flyers, with the edit history above.

**Export:** a session as a picture to share and as CSV; a full backup under Export everything.

**Deferred past v1:** reading painted loops from the photo (Phase 4 passed on synthetic sessions; the owner decided on 2026-11-21 to switch it on only after one real marked range session confirms the numbers), per-gun offsets, position and cadence labels, shot calling, target-code stickers and multiple cards per session, comparison views by label and the coach sheet, decimal scores, MOA, Nextcloud sync, measuring other target types, and the VR range idea.

**Carrying on.** After each photo the analysis saves what it needs to continue (the flattened photo, the holes so far and a small file) in the session folder, so if the phone closes the app between rounds the next round starts straight away instead of re-reading every earlier photo. Only the latest is kept; it is left out of backups.

**Processing** is entirely on the phone and offline. Nothing leaves the phone unless the shooter exports it.

**Delivery:** the owner installs test builds directly (APK sideload). Club members get the app later through a Google Play closed/internal test track, using the owner's existing Google Play developer account.

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
4. **Android app.** Kotlin, reusing `core` unchanged, with the OpenCV Android SDK, offline. Screens as in "App v1" above. Before the screens, a minimal app that runs `core` on a photo on the target phone and shows the debug image, to prove it runs on the phone with acceptable speed.
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
- Distance is usually 12 m; any custom distance can be entered.
- App v1 scope and screens as in "App v1" (2026-11-21). Dark mode by default.
- All image processing on the phone, offline.
- Test builds are sideloaded; club members later via a Google Play test track on the owner's existing developer account.

## Open questions

- Loop marker palette (depends on local shops).
- Does the club patch holes between rounds or shooters?
- Other club targets in v1 (e.g. 25 m rapid fire)?

## Changelog

- v0.4.0 (2026-11-24): improvements on their own branch (PR #8), chosen by Claude on the owner's open brief. Reading a photo is about 3× faster (two slow steps rewritten; results unchanged and the validation targets still met). New **Photo check** with retake advice, new **Patterns** screen and group size by gun and by ammo on View stats (pulled forward from "deferred past v1"), and a short **How it works** guide for first-time users. Desktop tool gains `rangetarget photocheck`. Also: an in-app camera (framing corners, level, tap to focus; the camera app stays as a fallback), carrying a session on from saved state after the app is closed, and matching sharpness between two photos before comparing them (hurried-photo false holes down about 11%; careful photos unchanged), and the printed ring numbers on the four axes used as extra anchors when lining photos up, so paper that bends differently between photos no longer shows the numbers as new holes (hurried-photo false holes 548 → 334 over 4 sessions in all; careful photos unchanged, all four validation targets still met; `docs/v0.4/validation_summary.json`). And **Hidden shots**: when a circled group is short of holes, dashed rings show where the missing shot may be. And **Round notes** on each round's results. (Calling groups before the photo was tried on the branch and dropped by the owner on 2026-11-25.)
- v0.3.9 (2026-11-22): first sideload build of App v1 (`android/`, PR #7). Choices made for speed, all easy to change later: sessions are stored as JSON files beside their photos instead of SQLite; photos come from the phone's own camera app (no in-app framing guide yet); kit has no photos yet; the circle screen also lets the shooter add an aim point, mark a found hole as not a hole, and add a missed hole (kept in the round's edit history). A built-in sample session (synthetic photos) lets the app be tried away from the range. Test APKs are published as pre-releases on GitHub; an emulator check in CI runs the sample session and screenshots the screens (about 18 s for the blank card and 30–47 s per round photo on the CI emulator; a real phone should be faster).
- v0.3.8 (2026-11-21): Phase 4 (validation, report in `docs/phase4/`). On 18 realistic synthetic sessions the core now meets all four targets: 99.7% of clean holes found, 0.4 false holes per card, 99.7% of found holes in the right group, 95% of centres within 1.9 mm. How the targets are measured is written under "Validation targets". Edge cases table: loops at the paper edge, loops left open and new loops drawn over an old loop of the same colour are read (flagged where unsure); a short group is no longer made up by splitting a hole unless the evidence is strong, and is flagged with the likeliest spots instead; a group with too many holes first tries reading a merged hole as fewer. The marking convention is unchanged: rules 6 and 7 stay requests. The owner chose to keep v1 on finger-circling and switch on painted-loop reading after one real marked session. Still weak: overlapping holes (about 80% found, all flagged) and hurried photos (the hard preset), which should get a retake warning.
- v0.3.7 (2026-11-21): App v1 agreed with the owner from a clickable mock-up and the owner's sketch: home with View stats, New session and Markup; Markup edits past photos (or adds one from the device as a hand-entered session) with a stored edit history and recomputed results; My kit lists for guns, ammo and targets; custom distance; dark mode by default; all processing on the phone. Delivery decided: sideload for testing, Google Play test track for club members. Data model gains Kit, Edits and per-round distance in metres.
- v0.3.6 (2026-11-21): marking convention gains two soft rules from the owner: try to keep loops 1 cm inside the paper edge (not guaranteed, so the app must still cope with edge loops), and change colour when re-shooting an aim point after a marking. Phase 4 keeps edge loops and same-colour re-use as cases to handle, not as convention errors.
- v0.3.5 (2026-11-21): the owner approved marking groups in the app as well as with paint: circle the group on the phone after the photo, and redraw or edit any loop on the confirm screen. Painted loops stay supported; either can be used, mixed within a session. App work (Phase 6); no change to the analysis core.
- v0.3.4 (2026-11-21): Phase 3 (pipeline). `rangetarget session <folder>` runs a whole session (folder layout, `plan.json` and outputs in `docs/session-files.md`). Each photo is fitted to the card, then lined up with the previous photo with a smooth sub-millimetre correction. Lighting is evened out before comparing. A change is a new hole if the paper changed in either direction and the new colour is not vivid; vivid new colour is ink. Loops are read per colour; a loop with a small gap, or closed by a hole, an aim point or the paper edge, still counts (flagged as having a gap). Shots are scored by the hole's edge (a hole touching a ring line takes the higher ring) using the plan's hole size, else the size measured from the photo less 1 mm for the torn rim. The plan (aim point, expected and fired shots, distance, hole size per round) is an input file for now; the phone will collect it on the plan screen. Without a plan, distance is taken as 12 m and counts are not checked (flagged).
- v0.3.3 (2026-11-21): Phase 2. Synthetic session generator in `tools/rangetools/synth` with easy / realistic / hard presets and a fixed set in `test_data/synthetic/`. The owner described the backing: a purple board that is often shot out to white styrofoam or dirt, so "the backing is always the same" now reads "same frame, but what shows through a hole varies across the card". New holes can be lighter than the paper. The generator's presets mix the three backings.
- v0.3.2 (2026-11-21): Phase 1 (real photo). The paper measured about 549 × 519 mm with the card upright, so it is 550 mm wide × 520 mm tall; `CardGeometry` updated. Card finding now goes black disc → ring lines → paper edges, and adds a small smooth bend on top of the flat-paper perspective map, because a card lying on a floor (or slightly curled on a frame) is not flat enough for a plain perspective map (it left up to ~2 mm error). Printed logos that look like holes are listed in `CardGeometry.printedMarks`; holes found there are flagged, not dropped. Vividly coloured stickers are treated as unresolvable clusters (Edge cases: heavy overlap).
- v0.3.1 (2026-11-21): Phase 0 set-up. OpenCV pinned at 4.9.0 for desktop and Android (ArUco checked on both). Ground-truth JSON format written down in `docs/ground-truth-schema.md`. Card paper taken as 520 mm wide × 550 mm tall until checked on a real card; nothing relies on which side is which.
- v0.3 (2026-11-21): dropped the Python prototype; analysis core written once in Kotlin + OpenCV as a shared JVM module, tested on a computer via a desktop CLI and reused unchanged in the Android app; Python kept only for test tooling (synthetic generator, validation reports). Validation targets moved here from `CLAUDE.md`. Build plan rewritten.
- v0.2 (2026-11-21): numbered aim points (red printed stickers or hand-drawn circles) added; handwriting allowed only for confirmed aim-point numbers; red removed from the loop palette; sticker sizes set for 12 m; build order changed to synthetic-first.
- v0.1 (2026-11-21): first draft.
