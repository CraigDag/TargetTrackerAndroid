# Target Tracker (Android)

Turns phone photos of a marked ISSF pistol target into per-group training data, by comparing each photo with the previous one from the same card.

- `CLAUDE.md`: project context and engineering conventions
- `docs/SPEC.md`: requirements (single source of truth)
- `docs/PROMPTS.md`: phased work plan
- `docs/SPEC-ISSUES.md`: open inconsistencies found in review
- `docs/ground-truth-schema.md`: the JSON format the synthetic generator writes

## Layout

| Folder | What | Ships in the app? |
| --- | --- | --- |
| `core/` | Kotlin + OpenCV analysis engine (plain JVM, shared with Android) | yes |
| `desktop/` | `rangetarget` command-line test harness | no |
| `android/` | the app (placeholder until Phase 6) | yes |
| `tools/` | Python test tooling: synthetic sessions, validation reports | no |
| `test_data/` | real and synthetic test images, card geometry for the tools | no |

## Build and test

Needs JDK 17 or newer; Gradle comes with the wrapper. Python 3.10+ for the tools.

```sh
./gradlew test                      # Kotlin unit tests, headless
./gradlew :desktop:installDist      # builds desktop/build/install/rangetarget/bin/rangetarget
desktop/build/install/rangetarget/bin/rangetarget version
desktop/build/install/rangetarget/bin/rangetarget smoke test_data/real/2026-11-21_card01_final.jpg --rotate left   # writes out/smoke/

pip install -r tools/requirements.txt
pytest tools                        # Python tool tests
```

OpenCV is pinned once in `gradle/libs.versions.toml` (4.9.0). `core` compiles against the OpenCV Java API only; the desktop tool supplies natives from the `org.openpnp:opencv` bundle, and the Android app will use the official OpenCV Android SDK at the same version. A core test reads both builds and fails if `core` calls (or plans to call, see `core/src/test/resources/opencv-planned-api.txt`) anything the Android SDK lacks.

Card dimensions live only in `CardGeometry` (`core`). The Python tools read them from `test_data/card_geometry.json`; regenerate it with `rangetarget geometry --out test_data/card_geometry.json` (a test fails if it drifts).
