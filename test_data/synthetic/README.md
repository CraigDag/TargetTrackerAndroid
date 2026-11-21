# Synthetic sessions (fixed set)

Fake range sessions with known answers, made by the Python generator in `tools/rangetools/synth`. Each folder is one session: `baseline.jpg` (the prepared card before shooting) and `round_01.jpg`, `round_02.jpg`, … (one per mark round), each with a ground-truth `.json` next to it in the format of `docs/ground-truth-schema.md`.

| Folder | Preset | Aim points | Photos |
| --- | --- | --- | --- |
| `easy_sticker_s0101` | easy | 4 red stickers | baseline + 3 rounds |
| `realistic_sticker_s0202` | realistic | 8 red stickers | baseline + 4 rounds |
| `realistic_hand_drawn_s0203` | realistic | 8 hand-drawn circles | baseline + 4 rounds |
| `hard_mixed_s0303` | hard | 4 stickers + 4 hand-drawn | baseline + 4 rounds |

The images are checked in (rather than regenerated in tests) because JPEG output can differ slightly between library versions. The plan behind them (holes, loops, aim points) is checked against the generator by `tools/tests/test_synth.py`.

Regenerate with `python -m rangetools.synth fixed-set` (from `tools/`, in the venv). `example/` is the hand-written example file from Phase 0.
