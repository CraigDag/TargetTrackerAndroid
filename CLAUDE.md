# Range Target Analysis App

An Android app that turns phone photos of a marked paper pistol target into per-group training data. It works by comparing each photo with the previous one from the same card. Full requirements are in `docs/SPEC.md`, and the phased work plan is in `docs/PROMPTS.md`. Read both before starting any phase.

## People and roles

- **The owner** is the product owner and only tester. They do not write code. They manage, test on real range photos, and give feedback.
- **Claude Code** writes all code, tests and docs.
- The owner is not a maths person. Explain results in plain language with pictures (annotated images, simple charts), not formulas. Raise a decision only when it is genuinely theirs; otherwise choose sensibly, say what you chose, and move on.
- The owner's coaching philosophy is to build the athlete's own diagnostic ability. The app should support self-diagnosis, not replace it.

## Context that matters for design

- Club pistol shooting on a shared indoor range. Usually **12 m**, sometimes 25 m.
- Target card: **ISSF 25 m precision / 50 m pistol**, Krüger Best.-Nr. 3100. The paper is **55 × 52 cm** and beige, with rings 1–10 and the black aiming area covering rings 7–10.
  - 10-ring diameter 50 mm; each further ring adds 50 mm of diameter (25 mm of radius). Ring n diameter = 50 + (10 − n) × 50 mm, so the 1-ring is 500 mm and the black (7-ring) is 200 mm.
  - Verify against the ISSF rulebook before relying on tolerances.
- Guns are club loaners (most often a CZ Shadow 2), with iron sights or a red dot. Calibre and ammunition vary.
- Photos are taken by walking downrange to the card. Behind the card is a purple board, often shot out so a hole shows white styrofoam or dirt instead; it varies across the card but stays put between photos, and holes over white foam are lighter than the paper. One target phone (a current flagship Android phone with a good main camera).
- The owner marks groups with coloured paint-marker loops after each mark round. The app diffs consecutive photos to work out which new holes belong to which new loop. See `docs/SPEC.md` for the marking convention.
- Speed and timing are **never measured**. Cadence is a label the shooter declares.

## Current phase

The owner won't be back at the range for some time. Work proceeds on:
1. **One real photo** of a heavily used card: `test_data/real/2026-11-21_card01_final.jpg`. It has about 150 holes, blue ballpoint loops and numbers, and one splatter-style reactive sticker with roughly 55 shots in it. It was photographed flat on a grey concrete floor, not on the range frame.
2. **Synthetic sessions** that you generate, with known ground truth.

Follow the phases in `docs/PROMPTS.md` in order. Don't start the Android app (screens, storage, camera) until the analysis core passes its validation targets on the desktop.

## How the code is built (decided 2026-11-21)

There is **no separate Python prototype**. The image-analysis code is written **once, in Kotlin with OpenCV**, and the same code runs on a computer during development and inside the Android app later. Nothing gets ported or rewritten.

- `core/`: the analysis engine. A plain Kotlin/JVM library: card finding, flattening, alignment, diffing, assignment, metrics, flags, debug-image drawing. It must not use anything Android-only or desktop-only (no `android.*`, no `java.awt` / `javax.imageio`). Image reading/writing goes through OpenCV or a small interface that each platform supplies.
- `desktop/`: a command-line tool that runs `core` on photos and session folders on a computer and writes annotated debug images plus CSV/JSON. This is the test harness, not a product.
- `android/`: the app (later). Depends on `core` unchanged and adds screens, camera, storage.
- `tools/`: **Python, test tooling only, never shipped**: the synthetic session generator and the validation report (charts, contact sheets). It talks to the Kotlin code only through files (images in, JSON/CSV out).

Why: Claude writes all the code, so a prototype-then-port step would mean building the hard part twice and re-validating it after the port. Writing it once in the language the app needs keeps one version to test and fix.

## Findings so far (from a first look at the real photo)

- The image is 1940 × 2576 px. The card fills most of the frame and is turned a quarter to the right: rotate it 90° to the left (anticlockwise) to make it upright (confirmed by the owner). The file has no EXIF orientation tag, so nothing rotates it automatically; use `--rotate left`.
- Sampled colours (BGR): card beige ≈ (158,177,198); floor ≈ (128,140,152); black print ≈ (48,46,46); holes on beige show the floor ≈ (159,174,190); holes in the black show the floor ≈ (154,170,186).
- **The floor is close to the card colour.** A naive HSV threshold plus a convex-hull quad grabbed part of the floor (the quad came out as [[0,0],[1939,346],[1939,2235],[371,2265]]). Better routes: anchor on the black aiming disc (a high-contrast circle of known 200 mm diameter, which gives centre and scale) and the thin ring lines, then fit the paper edges.
- Holes are distinguished more by darker value, ragged edges and shadow than by hue. Blue pen loops cross and touch holes, which is the case the marking convention now forbids, but it is present in this photo.
- Phase 1 results (`rangetarget card <photo> --rotate left`, debug images in `out/phase1/`, summary pictures in `docs/phase1/`): rings fit to about 0.4 mm typical / 0.8 mm (95%) after a gentle bend correction; paper 549 × 519 mm; 120 holes listed plus the sticker (≥ ~54 shots by missing area). In Lab colour, floor is much bluer (b ≈ 123) than the paper (b ≈ 138), which is what separates them; inside the black, holes are either lighter grey or a deep shadow darker than the print. A vertical fold crease runs through the black.
- Phase 3 results (`rangetarget session <folder>`, pictures in `docs/phase3/`): on the realistic synthetic sessions, aim points 8/8 within 1 mm with numbers read 8/8; photos line up to ~0.2 mm; ~94–98% of visible holes found with ~3 false per session; ~87–95% in the right group; typical centre error 0.5–0.9 mm, but merged and grown holes push the 95th percentile to 4–5 mm. Known weak spots: loops at the paper edge, a new loop drawn over an old one of the same colour at the same aim point, new holes on shot-out stickers (easy preset round 3), and false holes along the paper edge on the hard preset.

## Engineering conventions

- **Build:** Gradle (Kotlin DSL) multi-project with the Gradle wrapper checked in: `:core`, `:desktop`, later `:android`. JDK 17. One `gradle/libs.versions.toml` pins every version.
- **OpenCV:** one OpenCV version (4.9 or later, so ArUco is in the main `objdetect` module) used everywhere. `core` compiles against the OpenCV Java API (`org.opencv.*`) only. On desktop the natives come from a bundled-natives artifact (e.g. `org.openpnp:opencv`); on Android from the official OpenCV Android SDK at the same version. Check early that every OpenCV call `core` uses exists in both.
- **Desktop CLI:** `./gradlew :desktop:installDist` produces a `rangetarget` command (e.g. `rangetarget card <photo>`, `rangetarget session <folder>`). Debug output goes to `out/<command>/`.
- **Card geometry:** a single `CardGeometry` object in `core` holds the ISSF card constants in mm (ring diameters, black area, paper size). Nothing else hard-codes them.
- Every pipeline stage writes an annotated debug image, so the owner can see what the code "saw". On the phone the same drawing code feeds the confirm screen.
- Coordinates: a card frame in **millimetres**, origin at the card centre, +x right, +y up (as the shooter faces the card). Store shots in mm; derive angles (mrad, optional MOA) using each round's distance.
- Never guess silently. Anything ambiguous becomes a **flag** with a reason, shown in output.
- **Ground truth:** the Python generator saves a JSON file next to every image (aim points, shots, loops, round, distortion parameters). The JSON schema is written down in `docs/` and the Kotlin side reads it with kotlinx.serialization.
- **Tests:** JUnit 5 for `core` (unit tests per stage) and a headless end-to-end test that runs the whole pipeline on a fixed synthetic session (fixed seeds, checked into `test_data/synthetic/`) and checks the results against ground truth. `./gradlew test` must pass without a phone or emulator. Python tools have their own small pytest suite.
- **Python tools:** Python 3, OpenCV, NumPy, SciPy, scikit-image, matplotlib, in `tools/` with a `requirements.txt`. They never import or are imported by the app.
- **Validation targets** are in `docs/SPEC.md` (section "Validation targets"). The desktop run on synthetic data must meet them before Android work starts.
- Android (later): Kotlin, the same `core` module, OpenCV Android SDK, fully offline and on-device, local SQLite plus image files, CSV/JSON export.

## Communication with the owner

- After each phase, give them: what works, what doesn't, annotated example images, and the one or two decisions you need from them (if any).
- Keep the spec in sync. If a decision changes a requirement, update `docs/SPEC.md` and note the change at the bottom under "Changelog".
- Keep `docs/SPEC.md` as the single source of truth for requirements, rather than restating them in code comments.
