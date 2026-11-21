"""Locations in the repository the tools read from or write to."""

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TEST_DATA = REPO_ROOT / "test_data"
CARD_GEOMETRY_JSON = TEST_DATA / "card_geometry.json"
SYNTHETIC = TEST_DATA / "synthetic"
EXAMPLE_GROUND_TRUTH = SYNTHETIC / "example" / "round_01.json"
