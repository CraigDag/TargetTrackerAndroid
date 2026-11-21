# Prompts for Claude Code

Paste these into Claude Code one phase at a time, from the project folder. Each phase ends with something the owner can look at. Don't move on until they're happy with it.

The analysis code is written once in Kotlin (`core/`) and run on a computer through the `desktop/` command-line tool until the Android app exists. Python in `tools/` is only for making test data and reports. See "How the code is built" in `CLAUDE.md`.

---

## Phase 0: Set up

> Read `CLAUDE.md` and `docs/SPEC.md`. Set up a Gradle (Kotlin DSL) multi-project with the wrapper checked in and versions pinned in `gradle/libs.versions.toml`:
> - `:core`: Kotlin/JVM library depending only on the OpenCV Java API (one pinned OpenCV version, 4.9 or later). No Android or desktop-only APIs. Add a `CardGeometry` object holding the ISSF 25 m precision card geometry in mm (ring diameters, black area, paper size) as the single source of these constants, with unit tests.
> - `:desktop`: a command-line app (`rangetarget`) that uses `core`, loads OpenCV natives on the computer, and has a `version` command plus a smoke-test command that loads a photo, draws the ideal rings on it and writes it to `out/`.
> - Leave a placeholder for `:android` but don't create the app yet.
> - `tools/`: a Python package for test tooling with `requirements.txt` (OpenCV, NumPy, SciPy, scikit-image, matplotlib) and pytest.
> - Write the ground-truth JSON schema (aim points, shots in mm, groups, loops, round, distortion parameters) in `docs/ground-truth-schema.md`, with matching Kotlin data classes in `core`.
> - Make `./gradlew test` and `pytest tools` both pass headlessly, and check that the OpenCV functions `core` will need (including ArUco) are available in both the desktop and the Android OpenCV builds of that version.
> Then give me a short plain-language summary of how you understand the project and anything in the spec that looks inconsistent.

## Phase 1: The real photo

> Using `test_data/real/2026-11-21_card01_final.jpg`, write these stages in `core` and run them with the desktop tool (`rangetarget card <photo>`):
> 1. Find the card. Anchor on the black aiming disc for centre and scale, then refine using the ring lines. Don't rely on paper-vs-floor colour alone: the floor is close in colour to the card (see Findings in `CLAUDE.md`).
> 2. Warp the photo to a top-down image in the card's mm frame (e.g. 4 px/mm) and draw the ideal ISSF rings over it, so I can see how well it lines up.
> 3. Detect holes in the beige area and inside the black. Ignore blue pen ink and printed ring lines and numbers. Mark detected holes on the image, and list how many you found and where the method is unsure.
> 4. Save debug images for every step to `out/phase1/`, and explain the results to me with the images.
> Add unit tests for each stage. There's no ground truth for this photo, so be honest about what looks right and what doesn't. The heavily shot reactive sticker area can be treated as "unresolvable cluster".

## Phase 2: Synthetic session generator (Python test tool)

> In `tools/`, build a generator for fake sessions with known ground truth, following the session workflow in `docs/SPEC.md`. It writes images plus JSON in the schema from Phase 0, and is never part of the app:
> - Render a clean ISSF card at high resolution (print, rings, numbers, beige paper, slight texture).
> - Place aim points, both kinds: red printed stickers with black numbers (~30 mm), and hand-drawn filled circles with handwritten-style numbers.
> - For each mark round, "shoot" groups around aim points (realistic scatter at 12 m; vary skill; include flyers, occasional double holes and a shot through an existing hole), render holes as ragged openings showing a consistent backing colour (~9 mm by default, with the size set per round to allow other calibres), then draw a loose loop per group in that group's colour (hand-drawn look, varying thickness).
> - Output a baseline image plus one image per round, each passed through phone-like distortion: perspective tilt, slight rotation, card curl, uneven lighting, mild blur, noise, JPEG compression.
> - Save JSON ground truth with every image: aim points, shots (mm), group membership, loops, round, and distortion parameters.
> - Include presets: easy, realistic, hard. Generate a small fixed set with fixed seeds into `test_data/synthetic/`.
> - Add a Kotlin test in `core` that reads one generated ground-truth file, to prove both sides agree on the format.
> Show me a contact sheet of one session (baseline + rounds) at each difficulty.

## Phase 3: Pipeline

> Build the analysis pipeline from the spec's Image processing section in `core`, with a desktop command that runs a whole session folder (`rangetarget session <folder>`):
> 1. Register each photo to the card mm frame, then fine-align to the previous photo.
> 2. Find aim points in the baseline (position, colour, number; numbers as a guess with confidence).
> 3. Diff consecutive photos: new holes, and new ink split into closed loops and stray ink.
> 4. Assign new holes to this round's loops, cross-checked with aim points; raise flags per the Edge cases table (never guess silently).
> 5. Check counts against expected/fired; attempt to split double holes; flag shortfalls with suggested candidates.
> 6. Compute metrics per group and session: score (whole rings), extreme spread, mean radius, offset from aim point, horizontal/vertical spread, in mm and mrad (distance per round).
> Output per round: an annotated image (holes coloured by group, loops, aim points, group centres, spread circles, flags) plus CSV/JSON. Add the headless end-to-end test on the fixed synthetic session to `./gradlew test`.

## Phase 4: Validation

> Run the desktop tool over all synthetic sessions, then use a Python script in `tools/` to compare its JSON output with ground truth against the targets in `docs/SPEC.md` (Validation targets): hole position error (median and 95th percentile), detection recall/precision, assignment accuracy, aim-point accuracy, number-reading accuracy, and how often each flag fires (correctly vs falsely). Break results down by difficulty preset and by edge case. Produce a one-page plain-language report for me with a few charts and the worst failures shown as images, plus your recommendation of what to fix or change in the marking convention. Then fix the top problems in `core` and re-run.

## Phase 5: Sticker sheets

> Generate print-ready A4 PDFs at true scale (a Python script in `tools/` is fine, since the sheets are not part of the app): an aim-point test strip (20/30/40 mm red discs with black numbers, with and without a thin black outline), full aim-point sets (1–8 and 1–12 at 30 mm; 1–8 at 60 mm), target code stickers with ArUco, and a ruler strip. Each sheet carries a 100 mm check line to verify print scale. Make sure the pipeline recognises the printed versions, by rendering them into synthetic sessions.

## Phase 6: Android app (only after Phase 4 passes)

> Plan the Android app per the spec: Kotlin, the `core` module reused unchanged, OpenCV Android SDK at the same version as desktop, offline, SQLite + image files. Screens: plan session, set up card / baseline, capture round (framing guide, glare warning), confirm & fix (tap to assign or split), tag round (gun, sight, distance, position, ammo, notes), results, history/diary, guns list. Before writing screens, show me the screen flow as simple mock-ups and the data schema, and tell me how I'll install builds on my phone. The first build should be a minimal app that runs `core` on a photo on the phone and shows the debug image, so we know the shared code works there and how fast it is.

---

## Standing instructions for every phase

- Keep `docs/SPEC.md` updated when decisions change, and add a changelog line.
- Show results with annotated images, not just numbers.
- End each phase with: what works, what doesn't, and at most two decisions you need from me.
