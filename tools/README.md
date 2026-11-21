# tools: Python test tooling (never shipped)

Synthetic session generator (Phase 2) and validation reports (Phase 4). This code talks to the Kotlin `core` only through files: images in, JSON/CSV out. It never imports the app and the app never imports it.

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r tools/requirements.txt
pytest tools
```

- `rangetools/geometry.py` reads card dimensions from `test_data/card_geometry.json`, which is generated from `CardGeometry` in `core`. Don't hard-code card sizes here.
- `rangetools/groundtruth.py` reads and writes ground-truth files (format in `docs/ground-truth-schema.md`).
