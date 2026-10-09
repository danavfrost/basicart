# Cross-platform fixtures

Each folder here is a complete project folder (`projects/<id>/` layout from
`shared/FORMAT.md` §2). Both apps load every one of them in unit tests and must
reach the same result. `expectations.json` holds the same assertions in
machine-readable form, so test code should read that file rather than hard-coding
values.

- Regenerate: `python3 shared/tools/make_fixtures.py` (deterministic; don't hand-edit).
- Check: `python3 shared/tools/validate_fixtures.py` (stdlib only; exit 0 = pass).
  The validator is **stricter than an app reader**: it also checks writer
  conformance, so it fails non-normalized spans that an app would just repair (§7.9).
- No fixture has `thumb.png`. Apps must cope (regenerate or placeholder).
- Fonts referenced: `inter`, `anton`, `bungee`, `bebas-neue`, plus one deliberately
  unknown id (`no-such-font`).

## Result classes

| `result` | App behaviour (FORMAT.md §10) |
|---|---|
| `ok` | Opens. Load → save → load round-trips to a semantically equal document (§10.6). |
| `newerVersion` | Not opened, **no file modified**, home tile shows the "newer version" message + Delete. |
| `corrupt` | Not opened, **no file modified**, home tile "Can't open this project" + Delete. No crash. |

## Fixtures

| Folder | Tests | Must assert |
|---|---|---|
| `01-all-layer-types` | One layer of each type, white background. | Canvas 1080×1080 `#FFFFFFFF`. Layers bottom→top: `img-photo` (image, box 64×48, scale 16.875 → fills the top 1080×810), `shape-rect` (shape 600×120), `draw-1` (drawing 1080×1080, 1 stroke), `text-title` (text). |
| `02-text-effects` | Every text effect: meme (upper case, outline), outline+shadow (miter join), double outline, glow, linear gradient on a 37.5°-rotated layer, radial gradient + `weight` 700, curve +60 and −45, background box + fixed `boxWidth` 700 + justify + skew 12°, title case + letter spacing + underline + multi-line + **unknown fontId** (falls back to `inter`, id kept on save). | Canvas 1200×1600 `#1E1E2EFF`; 11 text layers in order `t-meme, t-shadow, t-double, t-glow, t-linear, t-radial, t-curve-up, t-curve-down, t-curve-multi, t-box, t-case` (`t-curve-multi`: 3 lines on concentric arcs, §7.6). `t-case` saves back with `fontId: "no-such-font"`. Visual review on both platforms side by side. |
| `03-rich-text-spans` | Rich-text spans (§7.9) incl. an emoji with skin-tone modifier (4 UTF-16 units, one grapheme), an italic layer with an upright+bold span, and weight 900 + B with a span that un-bolds. | 3 text layers `t-mixed, t-italic-layer, t-weight`. Span ranges (UTF-16): `t-mixed` [6,11) bold, [12,15) italic+underline, [16,21) strike, [22,26) bold (the 👋🏽); `t-italic-layer` [14,23); `t-weight` [0,12). Spans carry only fields that differ from the layer (normalized form); they survive round-trip unchanged. |
| `04-flags-shapes-image` | **Transparent background**, hidden layers, locked layers, layer opacity, all 5 shapes (rect + stroke, roundRect, unfilled ellipse, 45° line, double-headed arrow), image with crop + `rotate90:1` + `flipH` + all 4 adjustments + corner radius + border, and an alpha PNG at 315° and 50% opacity. | Canvas 800×600 `#00000000`. 10 layers in order (see `expectations.json`). `img-adjusted` box is **30×40** (crop 40×30, rotated). `shape-hidden` and `t-hidden-locked` have `visible:false` and are absent from renders/exports; `shape-locked` and `t-hidden-locked` are `locked:true` (not tappable on canvas, selectable in panel). Export PNG keeps transparency. |
| `05-drawing-brushes` | All 7 brush types, a pressure stroke, a single-point dot, an eraser crossing every stroke, a scaled (0.5) + rotated (30°) drawing layer whose stroke runs outside its 200×100 box (must be **clipped**), and a drawing layer with **no strokes file** (empty, not an error). | 3 drawing layers `draw-brushes` (512×512, 9 strokes), `draw-transformed` (200×100, 2 strokes, opacity 0.8), `draw-no-file` (512×512, 0 strokes). |
| `06-missing-asset` | Image layer whose `assetRef` file doesn't exist. | Project **opens**. `img-missing` renders the gray placeholder at its 1600×1200 box (scale 0.25) and is saved back with the same `assetRef`. `t-caption` renders normally. |
| `07-future-version` | `formatVersion: 999` with an unknown layer type and transform shape. | Classified `newerVersion` **before** validating anything else; folder bytes unchanged afterwards. |
| `08-corrupt-truncated` | `project.json` cut off mid-file. | `corrupt`; no crash; folder unchanged. |
| `09-corrupt-duplicate-ids` | Valid JSON, two layers with id `dup`. | `corrupt`. |
| `10-corrupt-bad-types` | Valid JSON, `canvas.width` is the string `"1080"` and a layer has no `transform`. | `corrupt` (no type coercion). |
| `11-eraser-masks` | Eraser masks (§9.1) on non-drawing layers: an image with crop + `rotate90:1` + `flipV` (mask in displayed-box space), an ellipse, text whose mask starts outside the layout box (outline/shadow erased too, not clipped), a rect whose `.mask.json` is truncated (→ no mask, not corrupt), and a drawing layer with a stray mask file (→ ignored). | Opens. 5 layers `img-masked` (box **40×48**), `shape-masked`, `shape-bad-mask`, `draw-plain`, `t-masked`. Effective mask stroke counts 2 / 1 / 0 / 0 / 1. Duplicating `img-masked` creates `strokes/<newId>.mask.json` with updated `layerId`. Changing the crop remaps mask points (box→natural→box) in one undo step. |
| `12-mixed-styles` | Character styling spans (§7.9) with color, `fontId`, `weight`, `size`: span colors over a layer **linear gradient**, `anton` + `caveat` runs on an `inter` layer, sizes 110/90/60/32 on mixed lines (per-line height and shared baseline), 🎉 emoji (2 UTF-16 units), underline on a large run, layer outline + shadow (em of the layer 60 px) and `curve: 35`. Second layer: solid fill with one word recolored at weight 900. | 2 text layers `t-styled`, `t-solid`. Span ranges `t-styled` [0,3) [4,8) [9,14) [15,17) [24,35); `t-solid` [6,9). Outline thickness is identical on the 110 px and 32 px runs. Round-trip unchanged. Has `editor-state.json` (§10.7): reopening restores selection `t-styled`, Text tool, Color tab, zoom 1.5× fit centred at (540, 420), text selection [4,8) "SALE". |

## Project packages (`packages/`, FORMAT.md §13)

Import each zip with the app's real importer. `expectations.json` → `packages`.

| Package | Expect |
|---|---|
| `valid-12-mixed-styles.zip` | Imports as a **new** project: new UUID id, name "Mixed styles" (or "Mixed styles (2)" if that name exists), layers identical to fixture 12, editor state restored. Made with `appPlatform: "ios"`. |
| `valid-11-eraser-masks.zip` | Imports. Asset, strokes and masks present; the truncated `.mask.json` still imports (it just means no mask). `__MACOSX/` and `.DS_Store` entries are skipped, not extracted. |
| `evil-paths.zip` | Rejected **before extracting anything**: `../evil.txt`, `/etc/abs.txt` (`unsafePath`); a symlink asset (`symlink`); `PROJECT.json` duplicating `project.json` (`duplicateEntry`); `notes.txt` (`unexpectedEntry`). An app may report just the first reason. |
| `evil-zip-bomb.zip` | Rejected `compressionRatio`: a 20 MiB asset compressed to ~20 KB. |
| `newer-package-version.zip` | Rejected `newerVersion` (`packageVersion: 2`), with the "update the app" message. |
| `not-a-package.zip` | Rejected `notAPackage`: a project folder zipped without `basicart-package.json`. |

For every rejection: no new project tile, no `projects/.import-*` folder left behind,
and existing projects untouched. `validate_fixtures.py` contains a stdlib reference
importer (`inspect_package`) that implements §13.2.

## Layout parity (`layout/cases.json`, FORMAT.md §7.10)

19 text layout cases: kerning pairs (`AVATAR Toyota WAVE`) in sans, bold and serif;
ligatures off; positive and negative letter spacing; wrapping at spaces and hyphens;
long-word breaks; the autoWidth 90 % limit; whitespace and NBSP; justify; the QA
mixed-span repro; mixed sizes across lines; synthesized bold/italic; ß→SS casing;
variable weights; line height; a 3-line curved text. There are no expected numbers here. Each app writes
`layout-results-<platform>.json` and `tools/compare_layout.py` diffs the two (line
ranges exact, everything else ±1 px). The cases are hand-maintained;
`make_fixtures.py` leaves `layout/` alone.

## Suggested unit tests (both platforms)

1. For each folder: classify → compare with `expectations.json` (`result`, canvas
   size + background, layer ids/types/visible/locked in order, derived box sizes,
   span ranges, stroke counts).
2. For each `ok` fixture: load → save to a temp folder → reload → deep-equal the model.
3. For `newerVersion`/`corrupt`: snapshot the folder's file list + bytes before and
   after the load attempt; they must be identical.
4. Render `01`–`06` at 100% to PNG and keep the images as golden files per platform;
   compare Android vs iOS outputs manually at release time (§16.2), aiming for no
   visible difference except font hinting.
