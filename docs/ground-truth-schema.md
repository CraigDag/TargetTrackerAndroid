# Ground-truth file format (schema version 1)

The synthetic session generator (`tools/`, Python) writes one JSON file next to every image it makes. The Kotlin side reads it with `rangetarget.core.groundtruth.GroundTruth`; the Python side with `rangetools.groundtruth`. Both reject unknown keys, so a change on one side that isn't made on the other fails the tests straight away. An incompatible change bumps `schema_version` in both places and in this document.

A hand-written example lives at `test_data/synthetic/example/round_01.json` and is read by the tests on both sides.

## Conventions

- **Coordinates** are in the card frame, in millimetres: origin at the centre of the 10 ring, +x to the right, +y up, as the shooter faces the card. A point is `{ "x": 12.5, "y": -3.0 }`.
- **Image pixels** have +x right and +y down, origin at the top-left corner.
- **Keys** are snake_case.
- **Colours**: aim-point and loop colours are sRGB strings `"#RRGGBB"`. A loop also carries a colour name (`"yellow"`), which is what "one colour per group per round" is judged on.
- **Each file stands alone.** It lists everything on the card at the moment the photo was taken: all aim points, and all groups, shots and loops from every round so far, each tagged with the `round` that added it. "What's new in this photo" is everything whose `round` equals the file's `round`.
- Card dimensions are not repeated here; they come from `test_data/card_geometry.json`, which is generated from `CardGeometry` in `core`.

## Top level

| Key | Type | Meaning |
| --- | --- | --- |
| `schema_version` | int | Always `1` for this version. |
| `session_id` | string | Name of the session, e.g. `"realistic_seed0042"`. |
| `preset` | string | Generator difficulty preset (`easy`, `realistic`, `hard`) or a free label. |
| `seed` | int | Random seed the session was generated from. |
| `image` | object | The photo this file describes (below). |
| `round` | int | `0` for the baseline photo, then `1`, `2`, … for each mark round. |
| `round_info` | object or null | This round's settings (below); `null` for the baseline. |
| `aim_points` | list | Every aim point on the card. |
| `groups` | list | Every group fired so far. |
| `shots` | list | Every shot fired so far, including ones that can't be seen. |
| `loops` | list | Every marker loop drawn so far. |
| `distortion` | object | How the clean card was turned into this "phone photo". |

## `image`

| Key | Type | Meaning |
| --- | --- | --- |
| `file` | string | Image file name, relative to the JSON file. |
| `width_px`, `height_px` | int | Image size. |

## `round_info`

| Key | Type | Meaning |
| --- | --- | --- |
| `distance_m` | number | Shooting distance this round (usually 12, sometimes 25). |
| `hole_diameter_mm` | number | Diameter of a clean hole this round (set by calibre, e.g. 9.0 or 5.6). |
| `calibre` | string | Free label, e.g. `"9mm"`, `".22LR"`. |

## `aim_points[]`

| Key | Type | Meaning |
| --- | --- | --- |
| `number` | int | The number printed or written on it; also the shooting order. |
| `kind` | `"sticker"` or `"hand_drawn"` | Printed red sticker, or hand-drawn filled circle. |
| `colour` | `"#RRGGBB"` | Fill colour. |
| `centre_mm` | point | Centre of the disc. |
| `diameter_mm` | number | Disc diameter. |

## `groups[]`

| Key | Type | Meaning |
| --- | --- | --- |
| `id` | string | Unique in the session, e.g. `"r1g2"`. |
| `round` | int | The round it was fired in. |
| `aim_point_number` | int or null | Aim point it was fired at; `null` for none. |
| `loop_colour` | string | Colour name of its loop. |
| `expected_shots` | int | From the plan. |
| `fired_shots` | int | Actually fired (can differ from expected). |

## `shots[]`

| Key | Type | Meaning |
| --- | --- | --- |
| `id` | string | Unique in the session, e.g. `"r1g2s3"`. |
| `round` | int | The round it was fired in. |
| `group_id` | string or null | The group it belongs to; `null` if it was left unlooped (fired with a group, so its id starts with that group's id and the group's `fired_shots` counts it, but outside every loop). |
| `centre_mm` | point | True centre of the hole. |
| `diameter_mm` | number | Hole diameter. |
| `flyer` | bool | A deliberate flyer, well outside the group's scatter. |
| `through_shot_id` | string or null | An earlier shot whose hole this one overlaps. |
| `invisible` | bool | Entirely hidden inside an earlier hole, so it can't be seen in the photo (the generator draws nothing for it). |

## `loops[]`

| Key | Type | Meaning |
| --- | --- | --- |
| `group_id` | string | The group it was drawn around. |
| `round` | int | The round it was drawn in. |
| `colour` | string | Colour name, e.g. `"yellow"`. |
| `colour_rgb` | `"#RRGGBB"` | The colour as drawn. |
| `path_mm` | list of points | The loop's centre line, in drawing order. A closed loop may run a little past its start, as hand-drawn loops do; an open one stops short. |
| `closed` | bool | Whether the line closes on itself. |
| `stroke_width_mm` | number | Pen width. |

## `distortion`

The image was made by rendering the flat card in mm, mapping it into the photo with the homography, then applying curl, lighting, blur, noise and JPEG compression. With `curl_mm` = 0 the homography alone is the exact mm-to-pixel mapping; with curl it is the flat-card approximation.

| Key | Type | Meaning |
| --- | --- | --- |
| `homography_mm_to_px` | 9 numbers | Row-major 3×3 matrix taking card mm (x right, y up) to image px (x right, y down). |
| `rotation_deg` | number | In-plane rotation of the card in the photo; positive turns it anticlockwise as seen in the photo. |
| `tilt_x_deg`, `tilt_y_deg` | number | Camera tilt about the card's horizontal and vertical axes. |
| `curl_mm` | number | Paper bend modelled as a cylinder: bulge at the card centre in mm. `0` for flat. |
| `curl_axis_deg` | number | Direction of the bend's axis on the card. |
| `lighting_min` | number | Uneven light: brightness runs from 1.0 to this value across the card. |
| `blur_sigma_px` | number | Gaussian blur in pixels. |
| `noise_sigma` | number | Sensor noise, in 8-bit grey levels. |
| `jpeg_quality` | int | JPEG quality used to save the image (1–100). |

## Files written by the generator

`tools/rangetools/synth` writes one folder per session, named `<preset>_<aim style>_s<seed>` (e.g. `realistic_sticker_s0202`), holding `baseline.jpg` / `baseline.json` (round 0) and `round_01.jpg` / `round_01.json`, … The camera model behind `distortion` is a pinhole camera (focal length 0.75 × the long image side) looking at the card; curl is a cylinder bulge towards the camera. With curl the homography is off by up to a few pixels; the true hole positions in mm are exact either way.
