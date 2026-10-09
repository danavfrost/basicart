# Basic Art — Project Format v1

This is the binding contract between the Android and iOS apps. Both apps read and
write exactly this format, and both must pass every fixture in `shared/fixtures/`.
The machine-readable schema is `shared/project.schema.json`. Where the schema and
this document disagree, this document wins; report the mismatch.

Keywords: **MUST** / **MUST NOT** are hard requirements; **SHOULD** is strongly
recommended; **MAY** is optional.

---

## 1. Conventions used everywhere

| Thing | Rule |
|---|---|
| JSON | UTF-8, no BOM. Writers SHOULD pretty-print (2-space indent). Key order is not significant. |
| Numbers | JSON numbers. Writers round non-integers to at most 4 decimal places (stroke points: 2). |
| Booleans | JSON `true` / `false`. Never `0`/`1`. |
| Colors | String `"#RRGGBBAA"`: sRGB, **straight (non-premultiplied)** alpha, 8 hex digits. Writers emit **uppercase**. Readers accept upper or lower case, and also accept `"#RRGGBB"` (alpha = `FF`). Anything else is invalid. |
| Angles | Degrees. **Positive = clockwise on screen** (the y axis points down). |
| `px` | Canvas pixels when the layer's `transform.scale` is 1 ("local px"). |
| `em` | A multiple of the text layer's `fontSize`. Text effect sizes are stored in em so they scale with the text (see §7.2). |
| Ids | Strings matching `^[A-Za-z0-9_-]{1,64}$`. Apps generate **lowercase UUID v4** (`3f2a…-…`). Fixtures use readable ids. Layer ids are unique within a project. |
| Timestamps | ISO 8601 UTC strings with `Z`, e.g. `"2026-10-08T14:03:22Z"` or `"2026-10-08T14:03:22.512Z"`. |
| Unknown keys | Readers MUST ignore unknown keys (no error). Writers need not preserve them. |
| Missing optional keys | Readers apply the default given in this document. Writers always write **every** field (full documents, no omission), so defaults only matter for robustness. |

### 1.1 Color math and compositing

- All blending, gradients and opacity happen in **non-linear sRGB** (the platform
  default on both Android Canvas and Core Graphics). Do not linearize.
- Gradients interpolate **non-premultiplied** R, G, B, A components linearly
  between stops.
- Every layer is rendered as an **isolated group** (offscreen layer), then
  composited onto the canvas with source-over at `layer.opacity`. So a
  semi-transparent text layer does not show its outline through its fill.

### 1.2 Blur

Every "blur" / "glow radius" value `r` in this format means a Gaussian blur with
standard deviation **σ = r / 2** (in the same units as `r`, converted to pixels at
render scale). Platform conversion is the implementer's job, for example:
- Android `BlurMaskFilter(radius)`: Skia uses σ ≈ 0.57735·radius + 0.5, so `radius = (σ − 0.5) / 0.57735` (σ ≥ 0.5; for σ < 0.5 draw unblurred). Or use `RenderEffect`/a blur shader with σ directly.
- iOS `CIGaussianBlur.inputRadius` is σ. `CGContext.setShadow(blur:)` is **not** σ (blur ≈ 2σ) and its offset ignores the CTM; prefer an explicit blur pass.

---

## 2. Folder layout

```
projects/<projectId>/
  project.json          # the document (this spec). REQUIRED.
  assets/<sha256>.<ext> # imported images, original bytes, never modified
  strokes/<layerId>.json# vector strokes for each drawing layer
  strokes/<layerId>.mask.json # eraser mask of an image/text/shape layer (§9.1)
  thumb.png             # home-grid thumbnail (optional, regenerable)
  editor-state.json     # where the user left off (optional, §10.7); written on autosave
  *.tmp                 # in-progress atomic writes; ignored and deleted on open
```

- `projects/` lives in app-private storage (Android `filesDir/projects`, iOS
  `Application Support/projects`).
- The folder name is the project id. If `project.json.id` differs from the folder
  name, the **folder name wins** (and the next save writes the folder name).
- Readers MUST ignore unknown files and folders.

### 2.1 Assets

- File name = lowercase hex **SHA-256 of the exact file bytes** + extension from the
  detected content type: `.png`, `.jpg`, `.webp`, `.gif` (first frame only),
  `.heic`. Example: `assets/9f86d081…0a08.jpg`.
- Identical imports deduplicate to one file. Asset files are write-once: never
  modified after the rename that creates them.
- iOS SHOULD convert HEIC/HEIF imports to JPEG (quality 0.95) before hashing so
  projects stay portable; Android readers that can't decode HEIC treat it as a
  missing asset (§10.3).
- An asset with no referencing layer (including in the undo history) MAY be deleted
  when the editor closes, never while it is open.

---

## 3. project.json — top level

```json
{
  "formatVersion": 1,
  "id": "b3c1…",
  "name": "Untitled 2026-10-08 14:03",
  "created": "2026-10-08T14:03:22Z",
  "modified": "2026-10-08T14:09:51Z",
  "generator": "Basic Art Android 1.0.0",
  "canvas": { "width": 1080, "height": 1080, "background": "#FFFFFFFF" },
  "layers": [ … bottom → top … ]
}
```

| Field | Type | Req | Default | Rules |
|---|---|---|---|---|
| `formatVersion` | int | yes | — | `1` for this spec. See §10.1. |
| `id` | id string | yes | — | Equals folder name (§2). |
| `name` | string | no | `"Untitled"` | 1–100 chars after trimming. |
| `created` | timestamp | no | file mtime | Never changes after creation. |
| `modified` | timestamp | no | file mtime | Updated on every save. Home grid sorts by this, newest first. |
| `generator` | string | no | `""` | Free text for debugging, e.g. `"Basic Art iOS 1.0.0"`. |
| `canvas.width` | int | yes | — | 16–8192. |
| `canvas.height` | int | yes | — | 16–8192. |
| `canvas.background` | color | no | `"#FFFFFFFF"` | White = `#FFFFFFFF`; Transparent = `#00000000`; any color incl. partial alpha. |
| `layers` | array | yes | — | Ordered **bottom → top** (index 0 is drawn first). May be empty. |

---

## 4. Coordinate system and transform

**Canvas space:** origin at the **top-left** of the canvas, x right, y **down**,
units = canvas pixels. The canvas is `(0,0)–(canvas.width, canvas.height)`.

**Layer box:** every layer has a box of size `w × h` local px (how `w`,`h` are
defined depends on the type; see the table). **Local space** has its origin at the
**top-left of the box**, x right, y down; the box is `(0,0)–(w,h)`.

**Transform** (`layer.transform`):

| Field | Type | Default | Rules |
|---|---|---|---|
| `x` | number | `canvas.width/2` | Canvas x of the **box center**. Any value (can be off-canvas). |
| `y` | number | `canvas.height/2` | Canvas y of the box center. |
| `scale` | number | `1` | Uniform scale, 0.01–100. |
| `rotation` | number | `0` | Degrees clockwise about the box center. Writers normalize to `[0, 360)`. |

Mapping a local point `p` to canvas space:

```
c  = (w/2, h/2)
q  = scale · (p − c)
canvas = (x, y) + ( q.x·cosθ − q.y·sinθ ,  q.x·sinθ + q.y·cosθ )     θ = rotation in radians
```

(With y pointing down this rotates clockwise.) Equivalent drawing order on both
platforms: `translate(x, y) → rotate(θ) → scale(s, s) → translate(−w/2, −h/2)`,
then draw the layer content in local space.

There is **no non-uniform scale** in the transform. "Side handles stretch" is
implemented by changing the type's own size fields (`width`/`height` for shapes,
`boxWidth` for text), never the transform.

**Layer box size by type:**

| Type | `w × h` |
|---|---|
| image | crop size, with width/height swapped when `rotate90` is odd (§6). Derived, not stored. |
| text | measured layout box (§7.3). Derived, not stored. |
| drawing | `width × height` (stored). |
| shape | `width × height` (stored). For `line`/`arrow`, `height` is ignored (§8). |

Content MAY extend outside the box (text outline/shadow/glow, shape stroke).
Only drawing layers are clipped to their box. Eraser masks (§9.1) are never clipped.

---

## 5. Common layer fields

```json
{ "id": "…", "type": "image|text|drawing|shape", "name": "Photo 1",
  "visible": true, "locked": false, "opacity": 1, "blendMode": "normal",
  "transform": { "x": 540, "y": 540, "scale": 1, "rotation": 0 },
  … type-specific fields … }
```

| Field | Type | Req | Default | Rules |
|---|---|---|---|---|
| `id` | id string | yes | — | Unique in project. |
| `type` | enum | yes | — | `image`, `text`, `drawing`, `shape`. Any other value: see §10.2. |
| `name` | string | no | type name | ≤ 100 chars. Shown in the layers panel. |
| `visible` | bool | no | `true` | Hidden layers are not drawn on canvas, thumbnail or export. |
| `locked` | bool | no | `false` | No rendering effect. Locked layers can't be picked on the canvas. |
| `opacity` | number | no | `1` | 0–1. Group opacity (§1.1). For text this is the "Text opacity" control. |
| `blendMode` | string | no | `"normal"` | Only `"normal"` in v1. Readers treat any other value as `"normal"`. |
| `transform` | object | yes | — | §4. |

Type-specific fields are flat on the layer object (not nested under a key).

---

## 6. Image layer

| Field | Type | Req | Default | Rules |
|---|---|---|---|---|
| `assetRef` | string | yes | — | File name inside `assets/`, e.g. `"9f86…0a08.jpg"`. Pattern `^[0-9a-f]{64}\.(png|jpg|webp|gif|heic)$`. |
| `naturalWidth` | int | yes | — | Pixel width of the asset **after applying EXIF orientation**. ≥ 1. |
| `naturalHeight` | int | yes | — | Same, height. |
| `crop` | object | no | full image | `{x, y, width, height}` in natural (oriented, unrotated, unflipped) pixels. `x,y ≥ 0`, `width,height ≥ 1`, `x+width ≤ naturalWidth`, `y+height ≤ naturalHeight`. Numbers (fractions allowed). |
| `rotate90` | int | no | `0` | 0–3 quarter turns **clockwise**. |
| `flipH` | bool | no | `false` | Mirror left↔right **in the displayed orientation**. |
| `flipV` | bool | no | `false` | Mirror top↔bottom in the displayed orientation. |
| `adjust` | object | no | all 0 | `{brightness, contrast, saturation, warmth}`, each a number −100..100. |
| `cornerRadius` | number | no | `0` | Local px, ≥ 0. Clamped at render time to `min(w,h)/2`. |
| `border` | object | no | disabled | `{enabled: bool, width: number ≥ 0 (local px), color}`. Default `{false, 0, "#FFFFFFFF"}`. |

**Pipeline** (source pixels → box), in this exact order:
1. Decode the asset and apply its EXIF orientation. Result is `naturalWidth × naturalHeight`.
2. Crop to `crop`.
3. Rotate by `rotate90 × 90°` clockwise.
4. Flip: `flipH` then `flipV`.
5. Apply `adjust` (below) per pixel.
6. Draw stretched to exactly fill the box `(0,0)–(w,h)` with high-quality filtering, clipped to a rounded rect of `cornerRadius`.
7. If `border.enabled && border.width > 0`: stroke the same rounded-rect path, **inside** the box (stroke centered on a path inset by `width/2`, with radius `max(0, cornerRadius − width/2)`). The border does not change the box size.

UI rule for Rotate 90°: when the user rotates, increment `rotate90` (mod 4) **and swap
`flipH` with `flipV`** (rotation and a single flip don't commute; this keeps the
visible result correct). Crop is edited in displayed orientation in the UI but
stored in natural space; convert both ways.

Changing `crop`, `rotate90`, `flipH` or `flipV` remaps the layer's eraser mask in the
same undoable edit (§9.1).

Editing proxies (downsampled decodes) are allowed on screen as long as geometry is
identical; export always uses the full-resolution asset.

**Adjustment math** — exact, both platforms. Work on straight-alpha sRGB components
`r, g, b ∈ [0,1]`; alpha is untouched. With `B = brightness/100`, `C = contrast/100`,
`S = saturation/100`, `W = warmth/100`, apply in this order **without intermediate
clamping**, then clamp each channel to `[0,1]` once at the end:

```
1. saturation: L = 0.2126 r + 0.7152 g + 0.0722 b;  ch = L + (ch − L)·(1 + S)
2. contrast:   ch = (ch − 0.5)·(1 + C) + 0.5
3. brightness: ch = ch + 0.4·B
4. warmth:     r = r + 0.1·W;   b = b − 0.1·W        (g unchanged)
5. clamp to [0,1]
```

All four steps are affine, so implement them as **one composed 4×5 color matrix**
(Android `ColorMatrix` — remember its offset column is in 0–255 units; iOS
`CIColorMatrix` followed by `CIColorClamp`, applied to un-premultiplied values).
"Reset adjustments" sets all four to 0.

---

## 7. Text layer

### 7.1 Fields

| Field | Type | Default | Range / rules |
|---|---|---|---|
| `text` | string | **required** | As typed (case transform is display-only). `\n` = line break. May be empty (apps delete empty text layers when editing ends). |
| `fontId` | string | `"inter"` | An `id` from `shared/fonts/fonts.json`. Unknown id → render with `"inter"`, keep the stored id. |
| `fontSize` | number | `64` | px, 4–2000. The em size. |
| `weight` | int | `400` | 100–900. Base weight of the family (the "regular" chosen in the font picker). §7.5. |
| `bold` | bool | `false` | Layer-level style for text not covered by a span. §7.5. |
| `italic` | bool | `false` | Layer-level. §7.5. |
| `underline` | bool | `false` | Layer-level. |
| `strike` | bool | `false` | Layer-level strikethrough. |
| `spans` | array | `[]` | Per-range character styling (B/I/U/S, color, font, weight, size). §7.9. |
| `align` | enum | `"center"` | `left`, `center`, `right`, `justify`. |
| `letterSpacing` | number | `0` | em of each grapheme's own size, −0.5..2. Added after every grapheme except the last on each line (§7.3 step 4). |
| `lineHeight` | number | `1.2` | Multiplier, 0.5..4. Line pitch = `lineHeight × fontSize`. |
| `textCase` | enum | `"none"` | `none`, `upper`, `lower`, `title`. |
| `autoWidth` | bool | `true` | `true`: the box follows the text; wraps only beyond `0.9 × canvas.width` (§7.3 step 5). `false`: wrap to `boxWidth`. |
| `boxWidth` | number | `0` | px. Used only when `autoWidth` is false; then MUST be ≥ 1. |
| `fill` | object | solid black | §7.4. |
| `outline` | object | disabled | §7.4. |
| `shadow` | object | disabled | §7.4. |
| `backgroundBox` | object | disabled | §7.4. |
| `curve` | number | `0` | −100..100. §7.6. |
| `skew` | number | `0` | Degrees, −45..45. §7.7. |

**Decided — corner handles scale the font size** (closes spec §19 open decision).
Dragging a text layer's corner handle, or pinching it, multiplies `fontSize` (and
`boxWidth` when `autoWidth` is false, and every span `size`) by the gesture factor and leaves
`transform.scale` at `1`. Because outline/shadow/padding are stored in em, they scale
along automatically. Side handles change `boxWidth` only (and set `autoWidth = false`). The eraser mask
follows corner/pinch scaling (§9.1) and ignores everything else.
Writers keep `transform.scale = 1` for text layers; readers MUST still honour any
`scale` value they find.

### 7.2 Why em

Every text effect length (`outline.width`, `shadow.blur`, `backgroundBox.padding`, …)
is a multiple of `fontSize`. The UI may display them as px (`em × fontSize`) but
stores em. Presets (`shared/presets/text-presets.json`) are therefore size-independent.
These em values are always relative to the **layer** `fontSize`, even over spans with
another `size`, so effects look uniform across the layer. Exceptions, which use the
**run's** size: `letterSpacing`, underline/strike metrics and synthesized bold/italic.

### 7.3 Layout (defines the box `w × h`) — fully determined

Both platforms MUST produce the same line breaks and, within ±1 px, the same line
widths, baselines and box size (contract: §7.10). Use no platform text-layout
defaults beyond what is stated here. Compute in 32-bit float or better and **round
nothing** during layout.

**Terms.** *Grapheme* = extended grapheme cluster (UAX #29). *Whitespace grapheme* =
U+0020, U+0009, U+1680, U+2000–U+2006, U+2008–U+200A, U+205F or U+3000. NBSP U+00A0,
U+2007 and U+202F are **not** whitespace (they never break). *Newline* = `\n`; readers
treat `\r\n` and lone `\r` as `\n`, and writers store `\n`. `z_g` = a grapheme's
effective size (§7.9).

1. **Case.** Apply `textCase` per grapheme (root locale): `upper`/`lower` map it;
   `title` lowercases every grapheme, then uppercases the first grapheme after
   start-of-text, whitespace or newline.
2. **Paragraphs.** Split at newlines. The newline belongs to no line.
3. **Shaping runs.** A run is a maximal sequence of graphemes in one paragraph with
   the same resolved font file (§7.5), the same `wght` value, the same size and the same
   synthesized-bold/italic flags. Color, underline and strike do **not** split runs.
   Shape each run on its own, left-to-right, with OpenType features **`kern` on,
   `liga` off, `clig` off, `dlig` off, `calt` on** (the shaper's defaults otherwise).
   There is no kerning across a run boundary.
   - Variable fonts: when `shared/fonts/metrics.json` lists a `wght` axis for the file,
     set `wght` = the chosen file entry's `weight`, clamped to the axis range, **before**
     shaping or measuring. Files without the axis get no variation settings.
     Every other axis (e.g. `opsz`, `wdth`, `slnt`) stays at the font's default value —
     **no automatic optical sizing** (iOS: disable CoreText's automatic `opsz` by setting
     the axis explicitly to its default / `kCTFontOpticalSizeAttribute = "none"`).
   - Android: `Paint.setFontFeatureSettings("'liga' 0, 'clig' 0, 'dlig' 0")`, kerning
     left at its default (on), `isLinearText = true`, advances from `getRunAdvance` over
     the whole run.
   - iOS: font attribute `kCTLigatureAttributeName = 0`. **Never set
     `kCTKernAttributeName` or tracking**: the presence of a kern attribute, even 0,
     turns the font's kerning off. Letter spacing and synthesized bold are added by
     the app when positioning (step 4), not through attributes.
4. **Advances.** Grapheme advance `a_g` = the sum of its glyphs' shaped advances
   (unhinted, fractional; a kerning adjustment counts toward the **left** grapheme of
   the pair) + `0.04·z_g` if its run is synthesized bold. Pitch `p_g = a_g + ls_g`,
   with `ls_g = letterSpacing × z_g`, except that the last grapheme of a line gets no
   `ls`. **Line width** = Σ `p_g` from the line's first grapheme through its last
   **non-whitespace** grapheme. Trailing whitespace is not counted; leading whitespace is.
5. **Wrapping.** Maximum line width `M` = `boxWidth` when `autoWidth` is false;
   **`0.9 × canvas.width / transform.scale`** when `autoWidth` is true (a 5 % canvas
   margin on each side, a new rule). Break opportunities (a small subset of UAX #14):
   - (a) between a whitespace grapheme and a following non-whitespace grapheme;
   - (b) after `-` U+002D, `‐` U+2010 or `–` U+2013 when the grapheme before it is not
     whitespace and the one after it is a letter or digit (General Category L* / N*).

   Greedy, per paragraph: from line start `s`, take the **furthest** opportunity `o`
   with `width(s, o) ≤ M + 0.01`. Whitespace before a break stays at the end of the
   earlier line (it never starts a line). If no opportunity fits (the first word is
   wider than `M`), break that word between graphemes, taking as many as fit, at least
   1 grapheme per line. Leading whitespace of a paragraph is kept. An empty paragraph
   is one empty line.
6. **Box.** `w = autoWidth ? max(line widths) : boxWidth` (with autoWidth, `w ≤ M`
   unless one grapheme is wider). Minimum `w` = 1. There is no padding or inset of any kind.
7. **Vertical metrics.** For each run, `A = hhea.ascender / unitsPerEm × z` and
   `D = −hhea.descender / unitsPerEm × z`, read from **`shared/fonts/metrics.json`** for
   the run's **metrics file**: the file §7.5 step 2 picks for the run's family at its
   `weight`, non-italic, not bold. `hhea.lineGap`, OS/2 typo/win and USE_TYPO_METRICS
   are **ignored**. Do **not** use `CTFontGetAscent`/`Descent` or `Paint.getFontMetrics`.
   Per line `i`, over all its graphemes including whitespace: `S_i` = largest `z`;
   `A_i`, `D_i` = largest `A`, `D`. An empty line uses the layer style (`fontSize`,
   layer font). `LH_i = lineHeight × S_i`; `top_0 = 0`, `top_{i+1} = top_i + LH_i`;
   baseline `y_i = top_i + LH_i/2 + (A_i − D_i)/2`; `h = Σ LH_i`.
8. **Horizontal.** Line start `x_i`: `left`/`justify` 0; `right` `w − width_i`;
   `center` `(w − width_i)/2`. `justify` (autoWidth or not) on every line except the
   last of each paragraph: a *gap* is a maximal whitespace run between two
   non-whitespace graphemes on the line; each gap gets `(w − width_i) / gaps` extra.
   A line with no gaps is left-aligned. Grapheme `g` sits at `x_i` + Σ of earlier
   pitches (+ gap extras).
9. **Decorations.** Underline and strike are drawn **per run** at the run's size `z`:
   underline thickness `0.06·z`, centred `0.12·z` below the line baseline; strike
   thickness `0.06·z`, centred `0.30·z` above it. Each covers the run's advance, and
   adjacent decorated runs meet with no gaps. They are part of the text **shape**
   (outline, shadow and fill apply) and take the run's fill color.

Glyphs missing from the chosen font use the platform's system fallback. That is not
guaranteed to match across platforms and is excluded from the parity contract (emoji
included).

### 7.4 Fill, outline, shadow, background box

**Fill** — one of:
```json
{ "type": "solid",  "color": "#FFFFFFFF" }
{ "type": "linear", "angle": 90, "stops": [ {"offset": 0, "color": "…"}, {"offset": 1, "color": "…"} ] }
{ "type": "radial", "stops": [ … ] }
```
- `stops`: 2–3 entries, `offset` 0..1, sorted ascending (readers sort if not).
- A span `color` (§7.9) overrides the layer fill (solid **or** gradient) for its
  graphemes; elsewhere the layer fill applies.
- Gradient geometry is relative to the layout box `w × h` (§7.3) in local space, and
  covers the whole text block (not per glyph).
- `linear`: `angle` in degrees, 0 = left→right, 90 = top→bottom (clockwise).
  Direction `d = (cos a, sin a)`; length `L = |w·cos a| + |h·sin a|`; start =
  `(w/2, h/2) − d·L/2`, end = `(w/2, h/2) + d·L/2`. Beyond the ends: clamp (pad).
- `radial`: center `(w/2, h/2)`, radius `sqrt(w² + h²)/2` (farthest corner). Pad beyond.

**Outline**
```json
{ "enabled": false, "style": "solid", "color": "#000000FF", "width": 0.06,
  "color2": "#FFFFFFFF", "width2": 0.06, "glowRadius": 0.3, "join": "round" }
```
| Field | Default | Rules |
|---|---|---|
| `enabled` | `false` | |
| `style` | `"solid"` | `solid`, `double`, `glow`. |
| `color` | `"#000000FF"` | Solid/double inner ring color; glow color. |
| `width` | `0.06` | em, 0..0.5. Visible thickness **outside** the glyph edge. Implement as a centered stroke of `2·width` drawn **behind** the fill. |
| `color2` | `"#FFFFFFFF"` | `double` only: outer ring color. |
| `width2` | `0.06` | em, 0..0.5. `double` only: outer ring thickness beyond the inner ring. Implement as centered stroke `2·(width + width2)`. |
| `glowRadius` | `0.3` | em, 0..2. `glow` only: blur radius (σ = r/2, §1.2). |
| `join` | `"round"` | `round` or `miter` (UI label "Sharp"; miter limit 4). Applies to all outline strokes. |

`glow`: the text shape filled **and** stroked with `2·width` in `color` (width 0 =
just the glyphs), then Gaussian-blurred with σ = `glowRadius/2`. No crisp outline is
drawn in glow style.

**Shadow**
```json
{ "enabled": false, "color": "#00000080", "blur": 0.1, "offsetX": 0.05, "offsetY": 0.05 }
```
- `color` includes the shadow opacity (UI opacity slider edits the alpha).
- `blur`: em, 0..2 (σ = blur/2). `offsetX`/`offsetY`: em, −2..2, in **local**
  (pre-rotation) space, so the shadow rotates with the layer. The UI may offer
  angle/distance; convert to X/Y.
- Shape: the alpha silhouette of everything drawn in steps 3–5 below (outline/glow +
  fill), tinted with `color` (`color.alpha × silhouette alpha`), blurred, offset.

**Background box**
```json
{ "enabled": false, "color": "#00000099", "padding": 0.25, "cornerRadius": 0.15 }
```
- Rect = layout box `(0,0)–(w,h)` outset by `padding` em on all sides, rounded by
  `cornerRadius` em (clamped to half the smaller side). Opacity is the color alpha.
- **Not drawn when `curve ≠ 0`** (UI disables the control). No shadow on the box.

### 7.5 Weight, bold and italic: font file selection

Resolved **per run** (§7.9) with the run's effective `fontId`, `weight` = W, `bold`
and `italic`, against that font's `files` (§11).

1. **Italic pool:** files with `italic == true` if italic is wanted and any exist;
   otherwise the non-italic files (and italic will be synthesized, step 4).
2. **Not bold:** the file in the pool whose weight is nearest W (ties → lighter).
3. **Bold** (the B toggle means "the family's bold", not "W + 300"): target
   `T = max(700, W)`. Candidates = pool files with weight ≥ 600 **and** ≥ W. Pick the
   one nearest T (ties → heavier). If there are no candidates, use the step-2 file and
   **synthesize bold**. So Regular (400) + B → 700 (or 800/900 if 700 is missing);
   Black (900) + B → 900 (never lighter than the base).
4. **Synthesized italic** (no italic file in step 1): horizontal shear of 12° (top
   leans right) per glyph about its baseline origin.
   **Synthesized bold:** an extra stroke of `0.04 em` in the fill paint around the
   glyphs; it counts as part of the glyph shape (outline widths are measured from the
   synthesized edge, and it widens each glyph's advance by `0.04 em`).

If a run's `fontId` is unknown, that run resolves against `inter` (the stored id is
kept). Unknown weight values are clamped to 100–900. Synthesized bold/italic use the
run's size.

### 7.6 Curve (exact geometry)

`curve = 0` → straight text. Otherwise, with layout done straight (§7.3) in a box
`w × h`:

```
φ    = min(|curve| / 100 · 2π, 0.97 · 2π)   // requested sweep, capped at 349.2°
Wmax = max line width                        // if Wmax == 0, draw straight
R    = max(Wmax / φ, h)                      // h = layout box height (§7.3)
yRef = h / 2
```

The cap applies to `φ` before `R` is computed, for both signs (the sign of `curve`
only picks up/down below). Because `R ≥ h`, short or tall text at strong curves
sweeps **less** than `φ` (actual sweep of the widest line = `Wmax / R`), which keeps
glyphs from folding into a knot. Compute in 32-bit float or better.

Mixed sizes need nothing extra: `Wmax` and `h` come from the mixed-size layout, and
each glyph uses its own line baseline for `py`.

Each glyph (and each decoration segment, cut per glyph) is placed as a **rigid body**
— glyph outlines are not warped. Its anchor is the midpoint of its advance on its
line's baseline: `(px, py)` in straight-layout local coordinates, where `py = y_i` is
the baseline of its line `i` (§7.3) and `px` already includes alignment and justify
offsets. Alignment is therefore kept along the arc as an angle about `w/2`.

**Concentric lines, shared scale.** Every line sits on its own arc around the same
center. Its angles use the **first line's** arc-length scale, so inner and outer lines
neither bunch up nor spread apart:

```
ρ(b)  = curve > 0 ? R − (b − yRef) : R + (b − yRef)   // arc radius of a baseline b
r0    = max(ρ(y_0), 0.001)                            // y_0 = baseline of line 0
R_i   = R · max(ρ(y_i), 0.001) / r0                   // angular divisor for line i
a     = (px − w/2) / max(R_i, width_i / (0.97 · 2π))  // width_i = line i width (§7.3)
```

"First line" = **line 0, the top line in straight layout, for both signs**: the
outermost arc for `curve > 0` and the innermost for `curve < 0`. The
`width_i / (0.97·2π)` floor stops any one line from sweeping more than 349.2°. For a
single line (`i = 0`) this reduces exactly to `a = (px − w/2) / R`, because
`R ≥ Wmax/φ ≥ width_0/(0.97·2π)`.

- `curve > 0` (arc **up**, ∩, "rainbow"): circle center `C = (w/2, yRef + R)`,
  `r = max(0, R − (py − yRef))`, anchor → `C + r·(sin a, −cos a)`, glyph rotated by `+a`.
- `curve < 0` (arc **down**, ∪, "smile"): `C = (w/2, yRef − R)`,
  `r = max(0, R + (py − yRef))`, anchor → `C + r·(sin a, cos a)`, glyph rotated by `−a`.

At `a = 0` the anchor stays put, so the box center (`transform.x, y`) is unchanged.
The layer box remains the straight `w × h`; curved glyphs may extend outside it (the
selection outline SHOULD use the bounds of the placed glyphs). Gradients use the
straight box. (`r` is clamped at 0 for very tall multi-line text on a tight curve.)

### 7.7 Skew

Horizontal shear of the whole rendered text group (including the background box)
about the box center, applied in local space before the layer transform:
`x' = x − tan(skew)·(y − h/2)`, `y' = y`. Positive skew leans the top to the right.

### 7.8 Text render order (inside the layer's isolated group)

1. Background box (if enabled and `curve == 0`).
2. Shadow (silhouette of steps 3–5).
3. `double`: outer ring (`color2`, stroke `2·(width + width2)`); `glow`: the glow.
4. `solid`/`double`: outline ring (`color`, stroke `2·width`).
5. Fill (solid or gradient), including synthesized bold and underline/strike.

Steps 3–4 always sit behind 5, so outlines never eat into letters. Skew (§7.7) and
the layer transform apply to all steps; then layer `opacity` applies to the group.

Text renders as vector paths at output resolution (never a scaled screen bitmap).

### 7.9 Character styling spans

Character styling applies to the selection; with no selection it applies to the
whole layer. Per-range values live in `spans`:

```json
"fontId": "inter", "fontSize": 64, "bold": false, "fill": {"type": "solid", "color": "#000000FF"},
"spans": [
  { "start": 6,  "end": 11, "bold": true, "color": "#E53935FF" },
  { "start": 12, "end": 15, "fontId": "caveat", "weight": 400, "size": 96 }
]
```

| Span field | Type | Meaning |
|---|---|---|
| `start`, `end` | int | **Required.** UTF-16 range (below). |
| `bold`, `italic`, `underline`, `strike` | bool | Overrides the layer flag. |
| `color` | color | Solid fill for these graphemes; overrides the layer fill (solid or gradient). |
| `fontId` | string | Family for these graphemes (§7.5, fallback per run). |
| `weight` | int 100–900 | Base weight for these graphemes. |
| `size` | number 4–2000 | Font size in px for these graphemes. |

**Absent field = inherit the layer value** (for `color`: the layer fill). The
**effective style** of a grapheme = the layer values overridden by the fields of the
span covering it. A **run** = a maximal sequence of graphemes on one line with
identical effective style. Layer-wide only (never in spans): `fill` gradients,
outline/glow, shadow, background box, curve, skew, rotation, `align`,
`letterSpacing`, `lineHeight`, `textCase`, opacity.

**Offsets are UTF-16 code units** into the stored `text` (before `textCase`),
`start` inclusive, `end` exclusive. Native on both platforms: Kotlin
`String`/`CharSequence` indices; on iOS `NSString`/`NSRange` or `String.utf16`
(`String.Index(utf16Offset:in:)`), **not** `String.count`/Character offsets. Example:
`"I ❤️ you"`: `❤️` is 2 units (U+2764 U+FE0F), so "you" is `start 5, end 8`.

**Validity** (readers repair, never "corrupt"): `0 ≤ start < end ≤ text.length`
(UTF-16); sorted by `start`; non-overlapping; `start`/`end` on extended-grapheme-
cluster boundaries (never inside a surrogate pair, ZWJ/emoji-modifier or combining
sequence). Repairs: snap a bad boundary outward to the enclosing grapheme
boundaries; clamp out-of-range offsets; overlaps: the **later span (array order) wins the
overlapping range as a whole span**: the earlier span is cut back (or split) so it no
longer covers that range, and its fields are **not** merged field-by-field into the
later one (a field absent from the later span means "inherit the layer value" there,
not the earlier span's value); then normalize.

**Normalized form** (deterministic; writers MUST emit it, readers normalize after
load and after every edit):
1. Sort by `start`.
2. In each span, **delete every field whose value equals the layer's current value**
   (`color` equal to a solid layer fill color counts as equal; against a gradient
   fill, `color` is always kept). Compare colors as uppercase `#RRGGBBAA`, numbers
   exactly.
3. Drop spans with no style fields left, and empty spans.
4. Merge spans that touch (`a.end == b.start`) and have identical style fields.

**Editing rules** (then normalize):
- **Set a value on a selection** `[s, e)` (snapped to grapheme boundaries): split
  spans at `s` and `e`, cover gaps with new empty spans, set the field on every span
  in `[s, e)`. B/I/U/S toggles: the new value is `false` if every grapheme in the range
  is effectively on, else `true`.
- **No selection (or the whole text)**: set the layer field and **delete that field
  from every span**. For B/I/U/S the new value is computed as above over the whole text.
- **Fill:** a solid color on a partial selection → span `color`. Choosing a fill with no
  selection or whole text (solid or gradient) → layer `fill`, and `color` is removed
  from all spans. A gradient can't be applied to a partial selection (UI applies it to
  the whole layer).
- **Font on a selection:** set `fontId` and `weight` (= the new family's file nearest
  the range's current effective weight).
- **Insert** `k` units at `p`: spans with `start ≥ p` shift by `k`; the inserted text
  takes the style of the grapheme just before `p` (if `p == 0`, the one just after).
  So a span with `start < p ≤ end` grows (`end += k`); when `p == 0` a span with
  `start == 0` grows. Platform "typing attributes" set with a collapsed cursor may
  restyle the inserted range.
- **Delete** `[s, e)`: offsets inside collapse to `s`; offsets ≥ `e` shift by
  `−(e − s)`. **Replace** = delete then insert.
- **Corner/pinch scale** by k: `fontSize`, `boxWidth` and every span `size` × k
  (rounded to 4 dp), plus the mask (§9.1).
- `textCase` does not change offsets. When casing changes a grapheme's length
  (`ß` → `SS`), the output keeps its source grapheme's style.

**Rendering per run:** font file per §7.5 with the run's `fontId`/`weight`/`bold`/
`italic`; glyphs at the run's size; fill = span `color` if present, else the layer
fill (a gradient keeps its layer-box geometry across runs); decorations per §7.3
step 7. Outline, glow, shadow and background box are layer-wide and measured in em
of the **layer** `fontSize` (§7.2).

### 7.10 Layout parity contract

`shared/fixtures/layout/cases.json` lists layout test cases. Each case is a partial
text layer (missing fields take §7.1 defaults) plus `canvasWidth`. Each app has a
debug/test entry point that lays out every case with its real engine and writes
`layout-results-<android|ios>.json`:

```json
{ "platform": "android", "appVersion": "1.0.0", "casesVersion": 1,
  "cases": [ { "id": "kern-inter", "w": 812.53, "h": 115.2,
     "lines": [ { "start": 0, "end": 18, "x": 0, "width": 812.53,
                  "top": 0, "height": 115.2, "baseline": 89.3 } ] } ] }
```

- `start`/`end` are the UTF-16 range of the graphemes on the line (the newline is
  excluded; trailing whitespace is included).
- All other numbers are local px, unrounded.
- `python3 shared/tools/compare_layout.py a.json b.json` checks that line count and
  `start`/`end` match exactly, and that `w`, `h`, `x`, `width`, `top`, `height` and
  `baseline` match within **±1 px**.
- Release gate: Android vs iOS must pass. CI on each platform can also compare its
  output with a committed golden from the other platform.

## 8. Shape layer

| Field | Type | Default | Rules |
|---|---|---|---|
| `shape` | enum | **required** | `rect`, `roundRect`, `ellipse`, `line`, `arrow`. |
| `width` | number | **required** | Local px ≥ 1. For line/arrow: the length. |
| `height` | number | **required** | Local px ≥ 1. Ignored for line/arrow (writers store `max(stroke.width, 1)`). |
| `fill` | object | `{enabled:true, color:"#3478F6FF"}` | `{enabled, color}`. Ignored for line/arrow. |
| `stroke` | object | `{enabled:false, color:"#000000FF", width:8, join:"miter"}` | `{enabled, color, width (local px, 0..500), join: round|miter}`. Line/arrow always draw the stroke (treat `enabled` as true). |
| `cornerRadius` | number | `24` | `roundRect` only. Local px, clamped to `min(w,h)/2`. |
| `arrowHeads` | enum | `"end"` | `arrow` only: `end`, `start`, `both`. |

Geometry in local space:
- `rect` / `roundRect`: `(0,0)–(w,h)`. `ellipse`: inscribed in `(0,0)–(w,h)`.
- Fill first, then stroke **centered** on the geometry (half the stroke lies outside the box). Miter limit 4.
- `line`: from `(0, h/2)` to `(w, h/2)`, round caps.
- `arrow`: the same line plus a filled isosceles-triangle head in `stroke.color` at
  each requested end. `headLen = max(12, 4·stroke.width)`, clamped to `w` (one head)
  or `w/2` (both); `headWidth = 0.9·headLen`. The tip is at the line end; the shaft
  (butt caps) stops at the head's base.

Direction comes from `transform.rotation` (an arrow pointing down-right at 45° has
`rotation = 45`).

---

## 9. Drawing layer

| Field | Type | Req | Rules |
|---|---|---|---|
| `width` | int | yes | Box width, local px, 1–8192. New layers use the canvas size. |
| `height` | int | yes | Box height. |

A new drawing layer is created with `width/height = canvas size`, `transform =
{x: W/2, y: H/2, scale: 1, rotation: 0}`, so local coordinates equal canvas
coordinates. The layer is **clipped to its box**. Apps MAY grow a box (adjusting
`width/height`, `transform` and offsetting all points) when the user draws outside it.

Strokes live in **`strokes/<layer.id>.json`** (path is by convention, not stored). A
missing file = empty layer (not an error).

```json
{
  "formatVersion": 1,
  "layerId": "draw-1",
  "strokes": [
    { "brush": "pen", "size": 12, "color": "#E53935FF", "opacity": 1,
      "points": [10.5, 20, 30, 40.25, 55, 60],
      "pressure": [0.4, 0.7, 0.9] }
  ]
}
```

| Stroke field | Type | Default | Rules |
|---|---|---|---|
| `brush` | enum | **required** | `pen`, `marker`, `highlighter`, `airbrush`, `calligraphy`, `pencil`, `eraser`. |
| `size` | number | `12` | Local px, 1–200. |
| `color` | color | `"#000000FF"` | Ignored for `eraser`. |
| `opacity` | number | `1` | 0–1. For `eraser`: erase strength. |
| `points` | number[] | **required** | Flat `[x0,y0,x1,y1,…]` in local px (box top-left origin), ≥ 1 point (even length ≥ 2). Stored **after** smoothing/stabilization; renderers do not re-smooth beyond the path rule below. |
| `pressure` | number[] | absent | Optional; one value 0..1 per point. Absent = constant full width. |

**Path rule** (all brushes): 1 point → a dot (disc whose diameter is the brush's
table width, e.g. `size × 0.6` for pencil, times the pressure factor). Otherwise
`moveTo(p0)`; for `i = 1 … n−2`: `quadTo(p_i, midpoint(p_i, p_{i+1}))`; then
`lineTo(p_{n−1})`. With pressure, width at a point = `size × (0.25 + 0.75·p)`;
render variable width as consecutive segments of the averaged endpoint width with
round caps (or an equivalent outline).

**Per-stroke opacity:** each stroke is rasterized as a unit at full alpha, then
composited at its effective opacity, so a stroke never darkens where it overlaps itself.

| Brush | Width | Caps/joins | Effective alpha | Notes |
|---|---|---|---|---|
| `pen` | size | round/round | opacity | Hard edge. |
| `marker` | size | square/round | opacity × 0.85 | |
| `highlighter` | size | butt/round | opacity × 0.40 | Normal blend (no multiply in v1). |
| `airbrush` | size × 0.5 | round/round | opacity | Then blur σ = size × 0.25. |
| `calligraphy` | `size × max(0.15, |sin(β − 45°)|)` per segment, β = segment direction | flat nib | opacity | Draw each segment as the quad swept by a nib of that width at 45°. |
| `pencil` | size × 0.6 | round/round | opacity × 0.85 | Grain texture, §9.2. |
| `eraser` | pen geometry | round/round | opacity | Composited with **destination-out** into the layer buffer: erases earlier strokes of this layer only. |

Strokes render in array order into a transparent buffer the size of the box; that
buffer is the layer's content.

### 9.1 Eraser masks (image, text and shape layers)

The Eraser always erases the **selected** layer, whatever its type, and never
creates a layer. One rule per type:
- **Drawing layer:** eraser strokes stay **inline** in `strokes/<id>.json` (order
  matters: paint drawn after an erase must show). Drawing layers never have a mask
  file; if one exists it is ignored.
- **Image, text, shape:** eraser strokes go to **`strokes/<layerId>.mask.json`**:
  same format as a strokes file (§9) with every stroke `brush: "eraser"`
  (`size`, `opacity` = strength, `points`, optional `pressure`; `color` ignored).
  Points are in the layer's **local space** (box top-left origin), so the mask moves,
  rotates and scales with the transform. No file = no mask.

**Rendering:** content (incl. text effects, image border) is drawn into the layer's
isolated group → mask strokes applied in order with **destination-out** (pen
geometry, §9 path rule) → group composited at `opacity`. The mask is **not clipped**
to the box (outlines/shadows outside the text layout box can be erased).
Hit-testing and selection bounds ignore the mask.

**Keeping the mask attached to content** (each is part of the same undoable edit):
- **Image** — the mask lives in the displayed box space (after crop/rotate/flip). On
  any change to `crop`/`rotate90`/`flipH`/`flipV`, remap every point
  box(old) → natural pixels → box(new). Box px = natural px (1:1), so this is exact
  and `size` is unchanged; points falling outside the new box are kept (un-cropping
  brings the erase back). No confirm dialog, no clearing.
- **Text** — corner/pinch scaling by factor k (§7.1) also maps every point
  `p → c_new + k·(p − c_old)` (c_old / c_new = the box center in local coordinates before / after the gesture) and multiplies `size` by k.
  Text edits, `boxWidth`, style and effect changes leave the mask untouched.
- **Shape** — `width`/`height` changes leave the mask untouched; `transform.scale`
  changes need nothing (local space).

**Duplicate** copies the mask file to `strokes/<newId>.mask.json` (`layerId` updated).
**Delete** removes it (on editor close, like other unreferenced files). **Merge down**
in v1 is only offered for two drawing layers with equal `transform` and the upper at
`opacity` 1: the upper's strokes (eraser included) are appended to the lower's. For
any other pair the menu item is disabled.

### 9.2 Pencil grain (bit-exact)

A fixed 32×32 alpha tile `G[y][x]` (values 0–255), generated once:

```
seed = 1                                   // 31-bit LCG (C "rand"-style constants)
for i in 0 ..< 1024:                       // row-major: x = i % 32, y = i / 32
    seed = (seed * 1103515245 + 12345) mod 2^31     // use 64-bit ints, or uint32 then & 0x7FFFFFFF
    v    = (seed >> 16) & 0xFF
    G[i] = (11475 + 55 * v) / 100          // INTEGER division → 114..255 (≈ 0.45–1.0)
```
Check: first 8 values `223 184 185 173 156 252 239 252`; SHA-256 of the 1024 bytes
`84e46aa5abb160be583d2f609760f24f6964dd7dde7af5b556cf6ad3fa8b9bc1`.

**Mapping:** the tile repeats in the drawing layer's **local space**, anchored at
local `(0,0)`, one tile cell = 1×1 local px, **nearest-neighbour** sampling (no
filtering; Android: `filterBitmap = false`; iOS: `interpolationQuality = .none` /
`CGImage` pattern with `.none`). It is therefore resolution-independent: on-screen
zoom and export scale the grain with the layer exactly like the stroke geometry.

**Combining:** inside the stroke's own buffer, after drawing the stroke at full alpha,
multiply its alpha by `G/255` (destination-in). Then composite the buffer at the
effective alpha `opacity × 0.85` (§9 per-stroke opacity). Final pixel alpha =
coverage × `G/255` × `opacity` × 0.85.

---

## 10. Reading, errors, writing

### 10.1 Versioning (§5 "Format compatibility")

- `formatVersion` is a positive integer. This spec = **1**. Each app has
  `SUPPORTED = 1`.
- **Additive, optional fields with defaults do NOT bump the version** (old readers
  ignore them). Anything that changes meaning, removes/renames a field or adds a layer
  type bumps it.
- `formatVersion < SUPPORTED`: migrate in memory, one version step at a time
  (`migrate_N_to_N+1` functions, pure JSON → JSON). On the first save after a
  migration, the app first copies the original to `project.json.v<N>.bak`, then writes
  the current version. **No migrations exist in v1.** Versions `< 1` are corrupt.
- `formatVersion > SUPPORTED`: **do not open, do not modify any file.** The home tile
  shows the thumbnail (if any) and the name (if readable) with the message "This
  project was made with a newer version of Basic Art. Update the app to open it."
  The ⋮ menu still offers Delete.

### 10.2 Corrupt projects

A project is **corrupt** if `project.json` is missing, isn't valid JSON, isn't an
object, lacks a required field, has a wrong type, an out-of-range canvas size, a
duplicate layer id, or (in a v1 file) an unknown layer `type`, shape or brush. The
home grid shows a "Can't open this project" tile with Delete. It MUST NOT crash the
home screen or the app, and MUST NOT modify the folder. Values merely out of range
elsewhere (opacity 1.3, fontSize 9000) are **clamped**, not corrupt.

Check order: parse JSON → read `formatVersion` (if it's an int > SUPPORTED → "newer
version", even if the rest wouldn't validate) → validate → migrate → load.

### 10.3 Missing referenced files

- Missing/undecodable asset: the project still opens. The image layer renders a
  neutral placeholder (light gray `#D0D0D0FF` fill with a diagonal cross) at its box
  size; the layer keeps its `assetRef` and is saved unchanged.
- Missing/invalid mask file (`.mask.json`), or one with a non-eraser stroke: no mask;
  not an error (rename to `.mask.json.corrupt` on next save).
- Missing/invalid strokes file: the drawing layer is empty; it is not an error. (An
  invalid strokes file SHOULD be renamed `strokes/<id>.json.corrupt` on next save.)

### 10.4 Atomic writes

Save order for one autosave:
1. Write new assets: `assets/<hash>.<ext>.tmp` → fsync → rename (skip if it exists).
2. Write changed strokes and masks: `strokes/<id>.json.tmp` / `<id>.mask.json.tmp` → fsync → rename.
3. Write `project.json.tmp` → fsync → **rename over `project.json`**, then update `modified`.
4. Thumbnail (§10.5), last, lower priority.

Never write in place. On open, delete leftover `*.tmp`. Strokes files for layers no
longer in the document (and not in undo history) MAY be deleted when the editor
closes. Saves run off the main thread; at most one save per project in flight
(coalesce).

### 10.5 Thumbnail

`thumb.png`: the full canvas rendered from the model (same renderer as export),
scaled to fit **512 px on the longest side** (never upscaled; aspect preserved),
PNG with alpha (transparent backgrounds stay transparent; the home tile draws the
checkerboard). Written atomically (`thumb.png.tmp` → rename) after the project.json
save. It is a cache: missing or stale thumbnails are regenerated, and fixtures need
not include one.

### 10.6 Round-trip

Load → save without edits MUST produce a document that is semantically equal to the
input after defaults are applied (same ids, order, values within rounding). Both apps
test this against every valid fixture.

### 10.7 Editor state (`editor-state.json`)

Optional and separate from the document, so the project reopens where the user left
off, both locally and after a package import (§13). It is written atomically on every
autosave (after `project.json`) and when the editor closes. Changing it does **not**
update `modified`. Undo history is never stored.

```json
{ "selectedLayerIds": ["t-styled"], "activeTool": "text", "textTab": "color",
  "view": { "zoom": 1.5, "centerX": 540, "centerY": 420 },
  "textSelection": { "layerId": "t-styled", "start": 4, "end": 8 } }
```

| Field | Type | Meaning |
|---|---|---|
| `selectedLayerIds` | id[] | Selected layers. Unknown ids are dropped. |
| `activeTool` | enum | `select`, `text`, `image`, `draw`, `shapes`, `adjust`, `canvas`. |
| `textTab` | enum | Last text-options tab: `font`, `style`, `color`, `outline`, `shadow`, `effects`. |
| `view.zoom` | number | Zoom as a **multiple of fit-to-screen** (1 = fit), so it carries across screen sizes. Valid range 0.1–32. |
| `view.centerX`, `view.centerY` | number | Canvas px at the center of the viewport. |
| `textSelection` | object | `{layerId, start, end}`: a text-editing selection or caret (`start == end`), in UTF-16 offsets (§7.9). Restored only when the layer is a text layer and the offsets are valid grapheme boundaries within its text. Opening it does not raise the keyboard. |

Every field is optional. A missing, unknown, invalid or out-of-range value is ignored
on its own (falling back to: no selection, the Select tool, the Font tab, fit to
screen). If the restored view would not show any part of the canvas, re-fit. A missing
or invalid file is never an error and never affects opening or importing.

---

## 11. fonts.json (shared/fonts/fonts.json)

Paths are relative to `shared/fonts/`. Apps bundle the whole folder.

```json
{
  "formatVersion": 1,
  "groups": [
    { "id": "sans-modern", "name": "Modern Sans", "sampleFontId": "inter" }
  ],
  "fonts": [
    {
      "id": "inter",
      "family": "Inter",
      "category": "sans",
      "group": "sans-modern",
      "files": [
        { "weight": 400, "italic": false, "styleName": "Regular", "path": "inter/Inter-Regular.ttf" },
        { "weight": 700, "italic": false, "styleName": "Bold",    "path": "inter/Inter-Bold.ttf" }
      ],
      "license": { "name": "SIL Open Font License 1.1", "file": "inter/OFL.txt" },
      "copyright": "Copyright 2020 The Inter Project Authors",
      "author": "Rasmus Andersson"
    }
  ]
}
```

| Field | Type | Rules |
|---|---|---|
| `groups[]` | array | Fine-grained picker groups (~16), shown in file order. |
| `groups[].id` | string | `^[a-z0-9-]+$`, unique. |
| `groups[].name` | string | Display name of the group. |
| `groups[].sampleFontId` | string | A font `id` used to render the group's header/chip. Must exist. |
| `id` | string | `^[a-z0-9-]+$`, unique, **stable forever** (it is stored in projects). |
| `family` | string | Display name, rendered in its own font in the picker. |
| `category` | enum | `sans`, `serif`, `display`, `handwriting`, `mono`, `fun`. UI labels: Sans, Serif, Display/Meme, Handwriting/Script, Monospace, Fun/Retro. |
| `group` | string | A `groups[].id`. Must exist. |
| `files[]` | array ≥ 1 | `weight` int 100–900, `italic` bool, `styleName` string (e.g. "SemiBold Italic"; shown in the weight picker), `path` to `.ttf`/`.otf`. Unique `(weight, italic)` per font. Variable fonts: one entry per named instance (paths may repeat; apps set the `wght` axis). |
| `license.name` | string | e.g. `"SIL Open Font License 1.1"`, `"Apache License 2.0"`. |
| `license.file` | string | Path to the license text, shown in Licenses & Credits. |
| `copyright` | string | Copyright line from the license / font metadata. |
| `author` | string | Designer or foundry. |

**`shared/fonts/metrics.json`** (generated by `shared/tools/font_metrics.py` from each
file's `head`/`hhea`/`fvar`; never hand-edited; re-run whenever fonts change):
`{formatVersion: 1, files: {"<path>": {unitsPerEm, ascender, descender, lineGap,
wght: [min, default, max] | null}}}`. It is the only source of vertical metrics
(§7.3 step 7) and of "is this file variable" (§7.3 step 3). Apps bundle it.

Fonts are listed in file order within each group. A text layer stores `fontId` +
`weight`; the weight picker lists the non-italic files' `styleName`s; choosing one
sets `weight`. When the user switches family, set `weight` to the new family's file
nearest the old weight. Planned ids presets rely on: `inter`, `anton`, `bebas-neue`,
`caveat`, `permanent-marker`, `bungee`.

## 12. Other shared data

- `shared/palettes/palettes.json`: `{formatVersion: 1, palettes: [{id, name, colors: [color…]}]}`, shown in file order.
- `shared/presets/text-presets.json`: `{formatVersion: 1, presets: [{id, name, props}]}`.
  `props` is a partial text layer (§7.1 keys only). **Applying a preset:** reset
  every text **style** field to its §7.1 default, then set each key in `props`
  (object values like `outline` replace the whole object; missing sub-fields take
  defaults), then delete `color`, `fontId` and `weight` from every span (span B/I/U/S
  and `size` are kept) and re-normalize. Never touched by a preset: `text`,
  `fontSize`, `autoWidth`, `boxWidth`, common
  layer fields (§5). A preset that sets `fontId` without `weight` sets `weight` to 400
  (the reset default).
- `shared/fixtures/`: see `shared/fixtures/README.md` and `expectations.json`.

---

## 13. Project package (move a project between devices)

A project can be exported as one file and imported on any device, Android or iOS,
then edited further.

### 13.1 Container

- **Decided: a plain ZIP named `<project name>.zip`** (MIME `application/zip`, UTI
  `public.zip-archive`). The owner asked for "a zip", and a plain `.zip` opens and
  shares cleanly on both OSes. A package is detected by its **manifest**, not by its
  file name. File name: the project name with `/ \ : * ? " < > |` and control
  characters replaced by `_`, trimmed, at most 80 characters, plus `.zip`. An empty
  result becomes `Basic Art project.zip`.
- Entries use **Deflate** or **Store**. Names are UTF-8 (general-purpose flag bit 11)
  and use `/` separators. No encryption. Use ZIP64 only when needed; readers MUST
  support it.
- Entry set (paths relative to the zip root; **nothing else**):

| Entry | Req | Notes |
|---|---|---|
| `basicart-package.json` | yes | Manifest. Written first. |
| `project.json` | yes | §3, written from the current saved model. Flush autosave first. |
| `assets/<sha256>.<ext>` | as referenced | Only assets referenced by a layer. |
| `strokes/<layerId>.json` | as present | Drawing layers. |
| `strokes/<layerId>.mask.json` | as present | Masks of image/text/shape layers (§9.1). |
| `thumb.png` | no | |
| `editor-state.json` | no | §10.7: selection, tool, tab, view, text selection. Invalid → ignored, never an import failure. |
| `assets/`, `strokes/` | no | Directory entries are allowed. |

Never included: undo history, caches, editing proxies,
`*.tmp`, `*.corrupt`, `*.bak`, or files of layers that no longer exist.

**Manifest** `basicart-package.json`:
```json
{ "package": "basicart-project", "packageVersion": 1, "formatVersion": 1,
  "appPlatform": "android", "appVersion": "1.0.0", "exported": "2026-10-09T10:00:00Z" }
```
- `package` must be exactly `"basicart-project"`.
- `packageVersion` is an int. This spec is 1; it covers the container rules in this section.
- `formatVersion` must equal `project.json`'s `formatVersion`.
- `appPlatform` is `"android"` or `"ios"`. `appVersion` is a string. `exported` is a timestamp (§1).

### 13.2 Import

Entry points: Android ACTION_VIEW/ACTION_SEND for `application/zip` and
`application/octet-stream`, plus an "Import project" item. iOS: the document picker
and "Open in" for `public.zip-archive`. Steps, in order. **Any failure means nothing
is left behind** and a clear message is shown.

1. Copy the incoming stream to a temp file in the cache directory. Open it as a ZIP;
   if it can't be opened → `corruptZip`.
2. **Check the central directory before extracting anything.** Reject the import
   (reason codes in brackets) if:
   - any name is absolute (`/…`, `\…`, `C:`), contains a `..` segment or `\`, has an empty segment, or contains NUL [`unsafePath`];
   - any entry is a symlink (Unix mode `S_IFLNK` in the external attributes) [`symlink`];
   - any name appears twice, compared case-insensitively after removing a trailing `/` [`duplicateEntry`];
   - any entry is outside the table in §13.1 (assets must match `^assets/[0-9a-f]{64}\.(png|jpg|webp|gif|heic)$`; strokes must match `^strokes/[A-Za-z0-9_-]{1,64}(\.mask)?\.json$`) [`unexpectedEntry`]. Exception: `__MACOSX/…` and `.DS_Store` are silently skipped;
   - any entry is encrypted [`encrypted`];
   - there are more than **10 000** entries [`tooManyEntries`];
   - declared total uncompressed size exceeds **1 GiB**, or any single entry exceeds **256 MiB** [`tooLarge`];
   - any entry has declared uncompressed size ≥ **1 MiB** and uncompressed ÷ compressed exceeds **100** [`compressionRatio`].
3. Read and check the manifest. If it is missing or `package` is wrong → `notAPackage`.
   If `packageVersion > 1` or `formatVersion > SUPPORTED` → `newerVersion` ("This
   project was made with a newer version of Basic Art. Update the app to import it.").
4. Extract into `projects/.import-<random>/` (the same volume as `projects/`, so the
   final rename is atomic). **Count actual bytes while inflating** and abort with
   `tooLarge` or `compressionRatio` if the real output breaks the limits in step 2.
   Declared sizes are not trusted.
5. Classify `project.json` per §10. Newer → `newerVersion`; corrupt → `corruptProject`.
   Missing or invalid assets, strokes or masks are **not** errors: the §10.3 placeholder
   and empty-layer behaviour applies. An asset whose bytes don't hash to its name is
   deleted, so it renders as missing.
6. Rewrite the copy: `id` = a fresh lowercase UUID v4 (always a **new** project, even
   if the same project already exists); `modified` = now. If the name is already used
   by another project, it becomes `"<name> (2)"`, or `(3)`, … (the first free one).
   Layer ids, `created`, `editor-state.json` and everything else stay unchanged.
7. Regenerate `thumb.png` if it's missing, then **rename** `projects/.import-<random>/` →
   `projects/<newId>/`. Delete the temp zip.

On app start, delete any leftover `projects/.import-*` folders. The home grid ignores
folders whose names start with `.`.

### 13.3 Export

Run off the main thread. Write the zip to the cache directory, then hand it to the
share sheet or "Save to Files" / SAF `ACTION_CREATE_DOCUMENT`. Exporting never changes
the project. The writer must produce a package that passes its own §13.2 import, and
both apps' packages must import on the other platform.
