"""How things look: colours and print style of the synthetic card.

These are appearance settings for the test images, not card dimensions (those
come only from test_data/card_geometry.json). Colours are given as sRGB hex
strings, which is also how the ground-truth files store them; drawing code
converts them to OpenCV's BGR order with `bgr()`.
"""

from __future__ import annotations

# Paper and print.
PAPER = "#DECBB4"          # beige card, before lighting
PRINT_INK = "#222121"      # ring lines and numbers on the beige
BLACK_AREA = "#1C1A1B"     # the black aiming area
PRINT_ON_BLACK = "#D9C7B0" # ring lines and numbers inside the black (paper showing through)
RING_LINE_MM = 0.6
NUMBER_HEIGHT_MM = 12.0

# What shows through a hole. The real range backing is not known yet; this is a
# mid-dark grey-brown board, so holes look darker than the paper and lighter
# than the black, as on the real test photo. Change it here once known.
BACKING = "#6E655C"

# Torn paper fibres round a hole edge.
HOLE_RIM = "#8F8A84"

# Aim points.
STICKER_RED = "#D7262B"
STICKER_NUMBER = "#141414"
HAND_NUMBER = "#1A1A1A"    # black marker used to write numbers on hand-drawn aim points

# Paint-marker loop colours (docs/SPEC.md "Loop palette"), usable on beige.
# White is in the spec palette for the black only; aim points and groups sit
# on the beige, so it isn't used here.
LOOP_PALETTE: dict[str, str] = {
    "yellow": "#FFE014",
    "orange": "#FF8A12",
    "lime": "#8FDC28",
    "light_blue": "#3FB2FF",
    "pink": "#FF3D9E",
}


def bgr(hex_colour: str) -> tuple[int, int, int]:
    h = hex_colour.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return (b, g, r)
