"""Synthetic session generator (Phase 2). Test tooling only, never shipped.

Makes fake range sessions with known answers: a baseline photo of a prepared
card, then one photo per mark round with new holes and new paint-marker loops,
each "taken" with phone-like distortion and saved with a ground-truth JSON
file (docs/ground-truth-schema.md).

    python -m rangetools.synth generate --preset realistic --seed 42 --out out/synthetic
    python -m rangetools.synth fixed-set          # rewrites test_data/synthetic/
    python -m rangetools.synth contact-sheet <session folder>...
"""
