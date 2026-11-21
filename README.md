# Target Tracker (Android)

Turns phone photos of a marked ISSF pistol target into per-group training data, by comparing each photo with the previous one from the same card.

- `CLAUDE.md`: project context and engineering conventions
- `docs/SPEC.md`: requirements (single source of truth)
- `docs/PROMPTS.md`: phased work plan
- `docs/SPEC-ISSUES.md`: open inconsistencies found in review

Layout: `core/` (Kotlin + OpenCV analysis, shared), `desktop/` (command-line test harness), `android/` (app, later), `tools/` (Python test tooling only), `test_data/`.
