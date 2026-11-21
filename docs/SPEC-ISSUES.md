# Things in the spec that don't quite fit (v0.3 review)

Found while revising the docs on 2026-11-21. None of these blocks Phase 0 or Phase 1. The first group needs the owner's call at some point; the rest I can settle myself and will note in the changelog.

## Needs the owner's call (eventually)

1. **Colour per aim point vs. colour once per round.** The convention says a hand-drawn aim point's loop must be the same colour as the aim point, and also that each colour is used only once per round. With more aim points than colours (8 aim points, about 6 colours), two aim points share a colour, so you can't shoot both in the same round. Options: limit hand-drawn cards to one aim point per colour, or say that shared-colour aim points must be shot in different rounds.
2. **Missed photo vs. reused colours.** Colours may be reused in later rounds, and a missed photo makes two rounds count as one. If both rounds used yellow, the combined round has two yellow loops, which the rules treat as an error. I'd suggest: the app warns and asks you to split them by tapping.
3. **White loops.** White is listed for "on black only", but a loop goes around a whole group and will often cross from black onto beige, where white is nearly invisible. Suggest dropping white, or using it only for groups fully inside the black.
4. **Orange and pink next to red stickers.** Red is reserved for aim-point stickers, but orange and pink/magenta can look red in poor range light. Worth testing on the sticker test strip before buying markers.
5. **Dry-fire and ACE VR sessions.** The personal build puts them in the diary, but the data model assumes every round has a card and a photo. They need a simpler "diary-only" entry. Low priority.

## I'll settle these myself

6. **Scoring by hole centre vs. hole edge.** The spec scores from hole centres, but ISSF scoring counts the ring a hole's edge touches, which depends on calibre. I'll score by edge using the round's calibre, with centre-based as a fallback when calibre is unknown.
7. **Hole size in synthetic data.** The generator fixed holes at ~9 mm, but calibre varies (a .22 hole is about 5.6 mm). Phase 2 now varies hole size per round.
8. **"≤ 1 false hole per card".** Unclear whether that's per photo or per whole card over many rounds. I'll read it as per round photo, which is the stricter test.
9. **Validation targets lived in CLAUDE.md**, although SPEC.md is meant to be the single source of truth. Moved into SPEC.md in v0.3.
10. **Inner ten (X ring).** Not in the card geometry. Not needed for whole-ring scoring; I'll add it when decimal scoring comes in.
11. **Diagonal aim points at 25 cm from the centre** sit right on the edge of the 1-ring (250 mm radius), so part of a group there may score zero. Fine for grouping drills; the layout guidance should say scores there aren't meaningful.
12. **The real test photo breaks the convention** (dark blue ballpoint, loops touching holes, photographed on the floor not the range frame). That's known and expected; it just means Phase 1 results on it will be rougher than on range photos.
