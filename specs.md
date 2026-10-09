# Basic Art — Specification

**Basic Art** is a simple, fast, **professional-quality** image creation and editing app for Android and iOS. Everything happens on the device. It has no ads, no accounts, no internet use, no tracking and no in-app purchases. It costs **$0.99, paid once** in the app stores, and that's it.

The point: art apps charge too much for simple things. Basic Art gives people the simple things, done to a very professional standard, for a rock-bottom one-time price. Quality must make $0.99 feel like a steal.

It is for people who want to make a quick piece of art, a meme, a collage or a photo with text on it, and don't want a slow, bloated, online-only editor. Its main selling point is a **great text tool with good fonts**.

---

## 1. Principles

1. **Fully offline.** The app never touches the network. The Android build does not request the `INTERNET` permission. Fonts, palettes and every other asset are bundled with the app. Nothing is downloaded at runtime.
2. **Nothing predatory.** The app never mentions price, payment or "free" anywhere in its UI: once someone has paid the $0.99 in the store, the app never asks for money or brings it up again. No ads, accounts, sign-in, analytics, telemetry, crash reporting services, in-app purchases, subscriptions, upsells, nag screens or rating prompts.
3. **Fast and stable.** The app opens to the home screen almost instantly, and opening a project or adding a layer feels immediate. Avoiding crashes and data loss matters more than adding features.
4. **Simple, but not too basic.** The UI is clean and uncluttered, with every tool one or two taps away.
5. **Store-safe.** The app ships on Google Play and the Apple App Store as a $0.99 one-time paid app (no IAP, no subscription). It uses only store-compliant APIs and permissions (see §12).

---

## 2. Platforms and codebases

Basic Art is built as **two separate native programs** that implement this same spec.

| | Android | iOS |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose | Swift, SwiftUI (UIKit / Core Graphics where needed) |
| Rendering | Android Canvas / Skia (Compose drawing) | Core Graphics / Core Text (or Metal-backed views where needed) |
| Devices | Phones and tablets | iPhone and iPad |
| Architectures | **64-bit** (arm64-v8a; x86_64 for emulators) **and 32-bit** (armeabi-v7a) builds | arm64 (device), simulator |
| Min OS | Android 8.0 (API 26) suggested | iOS 16 suggested |

- Android build outputs are copied to the repo as `BasicArt.apk` (64-bit) and `BasicArt-32bit.apk` (32-bit). A combined release bundle (AAB) is used for store upload.
- Suggested repo layout: `android/` (Android Studio project), `ios/` (Xcode project), `shared/` (fonts, palettes, sample assets, license files and project-format test fixtures used by both apps), `specs.md`, `README.md`, `PRIVACY.md` and `LICENSE`.
- Both apps must read and write the **same project file format** (§5), so the shared fixtures are tested on both platforms.
- App id / bundle id: `com.halworks.basicart` (to be confirmed before store release). Display name: **Basic Art**.

---

## 3. Home screen

- **App bar:** the "Basic Art" title on the left and a **settings gear** on the right.
- **Body:** a grid of square tiles (2 columns on phone portrait, more on tablets and in landscape).
  - **First tile (top-left): "New"**, with a large **+** icon. Tapping it opens the New Image dialog (§4).
  - **Remaining tiles:** thumbnail previews of saved projects, ordered most recently edited first. Each tile shows the thumbnail, the project name and the last-edited date (small).
  - **Tap a project tile** to open that project in the editor exactly as it was left.
  - **⋮ (three dots) at the top-right corner of each project tile** opens a menu:
    - **Rename**
    - **Duplicate**
    - **Delete project** opens a confirmation dialog: "Delete '<name>'? This removes the project from Basic Art. Images you've already exported are not affected." with **Cancel** and **Delete** buttons.
- **Empty state:** when there are no projects, only the New tile shows, with a short friendly hint.

---

## 4. New image

The New Image dialog or sheet contains:

- **Width × Height** in pixels, as numeric fields. A lock toggle keeps the aspect ratio. Minimum 16 px; maximum 8192 px per side. If the size is very large for the device's memory, the app warns but doesn't block.
- **Presets** (chips): Square 1080×1080 · Portrait 1080×1350 · Story/Phone 1080×1920 · Landscape 1920×1080 · Banner 1500×500 · Meme 1200×1200 · A4 @150dpi 1240×1754 · Custom.
- **Background:** White · Transparent · Color (opens the color picker, §9).
- **Name** (optional; defaults to "Untitled", then "Untitled 2", "Untitled 3"…; the tile already shows the date).
- **"Start from photo"** button: pick an image from the device. The canvas is sized to that image and the photo becomes the bottom layer. This is the fast path for memes and captioned photos.
- **Create** opens the editor.

---

## 5. Projects and storage

- Each project is saved **inside the app's private storage** (Android: app files dir; iOS: Application Support). Projects are never written to the user's shared storage. Exporting (§11) is the only way images leave the app.
- **Autosave:** changes are saved automatically, debounced to about 1 second after the last edit, and also when the app goes to the background and when the editor closes. There is no "Save" button for projects, and a crash or kill must not lose more than a few seconds of work. Writes are atomic: write to a temp file, then rename.
- **Project folder structure:**
  ```
  projects/<uuid>/
    project.json      # document: canvas, layers, properties (versioned)
    assets/           # imported images (original bytes, deduplicated by hash)
    strokes/          # drawing-layer data (vector strokes) and/or rasterized PNGs
    thumb.png         # thumbnail for the home grid (~512 px max side)
  ```
- **`project.json`** includes `formatVersion`, `id`, `name`, `created`, `modified`, `canvas { width, height, background }`, and an ordered `layers[]` (bottom → top). Each layer has `id`, `type`, `name`, `visible`, `locked`, `opacity`, `blendMode` (normal only in v1; reserved field), `transform { x, y, scale, rotation }` and type-specific properties (§6–§8).
- **Format compatibility:** the format is versioned. Newer app versions migrate older files. A file from a newer version that the app can't read shows a clear message instead of crashing.
- A corrupted project shows a "can't open this project" tile with a delete option. It must never crash the home screen.
- The thumbnail regenerates on save.

---

## 6. Editor — layout

- **Top bar:** Back (to home) · project name (tap to rename) · **Undo** · **Redo** · **Layers** toggle · **Export**.
- **Canvas:** centered, with pinch-to-zoom and two-finger pan, and a "fit to screen" double-tap or button. A checkerboard shows transparency.
- **Bottom tool bar** (main tools): **Select/Move** · **Text** · **Image** (add photo) · **Draw** · **Shapes** · **Adjust** (for the selected image layer) · **Canvas** (resize, background).
- **Context panel:** when a tool or layer is active, a compact panel slides up above the tool bar with that tool's options. It can be collapsed so the canvas has room.
- **Tablets / landscape:** the layers panel docks as a side column and tool options can sit in a side panel instead of a bottom sheet. Both docked panels are **collapsible** (Layers button in the top bar or a chevron on the panel edge), slide in/out smoothly with the canvas re-fitting, and remember their collapsed state.
- **Undo/redo:** every edit is undoable, including move, transform, text change, style change, stroke, layer add/delete/reorder, and adjustments. History holds at least 50 steps per editing session.

---

## 7. Layers

**Layer types:** **Image**, **Text**, **Drawing** (brush strokes) and **Shape** (rectangle, rounded rectangle, ellipse, line, arrow; fill and stroke color, stroke width).

**Layers panel** (toggled from the top bar; docked on tablets):
- A compact list of layer **buttons with small thumbnails** and the layer name, with the top layer at the top.
- **Tap** a layer to select it. It **highlights on the canvas** with a selection box and handles.
- **Drag** to reorder (up/down).
- Per-layer quick controls: visibility (eye), lock, and a ⋮ menu with Rename, Duplicate, Delete, Move to top, Move to bottom, and Merge down (optional).

**On the canvas:**
- **Tap** a layer to select it. Tapping where layers overlap selects the topmost; tapping again on the same spot cycles to the next layer beneath.
- **Drag** to move. **Corner handles** scale (Shift-style uniform scale by default; side handles stretch where it makes sense). A **rotate handle** rotates. Two-finger pinch/rotate works on the selected layer.
- Snapping: snap to canvas center and edges, and to 0/45/90° when rotating, with light guides. Can be turned off in Settings.
- **Long-press** a layer to open a context menu: **Move to top · Move up · Move down · Move to bottom · Duplicate · Delete** (plus Edit text for text layers).
- Locked layers can't be selected on the canvas, but can be selected from the layers panel.

---

## 8. Text tool (headline feature)

The text tool is the main reason people will use Basic Art. It must be powerful, look great and stay uncluttered.

### 8.1 Creating and editing text
- With the **Text** tool active, **drag out a box** on the canvas to create a text box of that width, or **tap** to create an auto-width text box at that spot. An auto-width box grows with the text up to the canvas width minus a 5% margin on each side, then wraps; it never runs off the canvas (growth is clamped to stay inside the canvas).
- Typing happens directly on the canvas, with the keyboard up and the canvas scrolled so the text stays visible. A "done" control closes the keyboard.
- Double-tapping an existing text layer edits it. With the Text tool active, tapping an already-selected text layer also starts editing at the tap point (no layer cycling); same-spot cycling to layers beneath only happens with the Select tool.
- Text boxes wrap text to the box width. Box width is resizable with side handles, and font size scales with the corner handles (or optionally the box only; choose one behavior and keep it consistent).
- Multi-line text is supported.

### 8.2 Text options panel
A tabbed panel slides up when a text layer is selected. Each tab is compact, one or two rows of controls, and changes preview live.

| Tab | Controls |
|---|---|
| **Font** | A drill-down font browser: **Groups → Families** (→ extra weights). Level 1 is a list of font groups (e.g. Clean Sans, Book Serif, Meme/Impact, Handwriting, Marker & Brush, Retro/Pixel, Stencil…), each group name rendered in a font typical of that group. Tapping a group shows its families, **each family name rendered in its own font**. Tapping a family applies it. A family that has extra weights (e.g. Light, Black — never Regular or Bold, which the B button covers) shows a small arrow that opens its weights, **each weight name rendered in that weight**. Bold and italic are **not** picked here; they come from the B / I buttons on the Style tab. A back control returns up a level. A search box (searches across all fonts) and a "recently used" row sit at the top. |
| **Style** | Four small toggle buttons **B · I · U · S**, each drawn as a preview of its effect (bold B, italic I, underlined U, struck-through S). **Highlight part of the text and tap a button to apply it to just that selection**; with no selection it applies to the whole text box. Buttons show as active when the selection already has that style. Bold/italic use the family's real bold/italic files when it has them, synthesized otherwise · Alignment (left / center / right / justify) · **Size** slider + numeric · **Letter spacing** · **Line height** · Case: Normal / ALL CAPS / lowercase / Title Case. |
| **Color** | Fill: solid color (palette, §9) or **gradient** (2–3 stops, linear angle or radial) · Text opacity. |
| **Outline** | On/off · color · **thickness** · **style**: Solid, **Double outline** (two colors/thicknesses), **Glow** (soft blurred outline with color + radius) · Join: round / sharp. |
| **Shadow** | On/off · color · opacity · blur · offset distance · angle (or X/Y). |
| **Effects** | **Background box** (highlight behind text: color, opacity, padding, corner radius) · **Curve / arc** text (slider from arc down to straight to arc up) · **Rotation** (any angle; numeric field + slider; also via the on-canvas rotate handle) · Skew/slant (optional) · **Presets** (below). |

**Selection rule (applies to every text tab):** character styling — **font, weight, size, color, bold, italic, underline, strikethrough** — applies to **whatever text is selected**; with no selection (or with the text box selected on the canvas, not in typing mode) it applies to the whole text box; with just a blinking cursor it applies to what you type next. Box-wide effects always apply to the whole box: outline/glow, shadow, background box, gradient fill, curve, rotation, slant, alignment, letter spacing, line height, case and opacity. Controls always show the current state of the selection (mixed values shown as "mixed").

### 8.3 Text presets
One-tap looks that set multiple properties on the selected text box, which the user can then tweak. Presets never show prompts or move anything; they only change the look of the selected text box (if no text box is selected, tapping a preset creates one new text box in that style, just like using the Text tool):
- **Classic Meme:** an Impact-style condensed bold font, white fill, thick black outline, ALL CAPS.
- **Caption bar:** white text on a semi-transparent black background box.
- **Neon:** bright fill with a matching glow.
- **Sticker:** bold fill with a thick white outline and drop shadow.
- **Retro:** gradient fill with a contrasting outline and offset shadow.
- **Handwritten note:** a script font in dark ink color.

### 8.4 Text rendering quality
- Rendering must be crisp at every zoom level and in exports. Text renders as vectors at export resolution and is never upscaled from a screen-size bitmap.
- Outline draws behind the fill so it doesn't eat into the letters.
- What you see in the editor matches the exported image exactly.

### 8.5 Fonts
- **About 220–260 bundled font families** (many with multiple styles), organised into ~16 groups, under free, redistributable licenses (SIL Open Font License or Apache 2.0), covering:
  - **Sans:** clean modern faces in several weights
  - **Serif:** a book-style serif and a bold display serif
  - **Display / Meme:** at least one Impact-style condensed bold face (e.g. Anton), plus tall/condensed and heavy display faces
  - **Handwriting / Script:** casual handwriting, marker and brush script
  - **Monospace:** a typewriter/code face
  - **Fun / Retro:** comic-style, rounded, pixel/retro and stencil-style faces
- Every font's license file ships in the repo (`shared/fonts/<family>/OFL.txt` etc.) and is listed in the app's **Licenses & Credits** screen.
- Fonts are **bundled in the app package. They are never fetched at runtime.**
- (Future/optional: let users import their own `.ttf`/`.otf` files from the device into the app.)

---

## 9. Colors and palettes

The same color picker is used for text, outline, shadow, brush, shapes and background.

- **Preset palettes** (horizontal swatch rows, switchable): Basic, Pastel, Vivid, Earth, Neon, Grayscale and Skin tones.
- **Recent colors** row (last ~12 used).
- **Custom color:** hue/saturation/value picker, plus a HEX field and RGB fields, plus **opacity/alpha**.
- **Eyedropper:** a clearly visible eyedropper button is the first item in every color picker (text, outline, glow, shadow, background box, brush, shapes, canvas background). Tapping it tucks the picker away; a magnifier loupe follows your finger showing the pixels, a crosshair and the live color + hex; lift to pick (it's also added to Recents).
- **Saved colors:** the user can save a custom color to a "My colors" palette, stored on-device in app settings.

---

## 10. Drawing and image editing

### 10.1 Draw tool
- Drawing creates or uses a **Drawing layer**. Starting a drawing with no drawing layer selected creates a new one.
- **Brush types:** Pen (hard round) · Marker (flat, slightly transparent) · Highlighter (transparent, multiply-like look) · Airbrush/Soft (soft-edged) · Calligraphy (angle-dependent width) · Pencil (thin, slightly textured) · **Eraser** (erases from the **currently selected layer, whatever its type** — photo, text, shape or drawing; it never creates a new layer. Erasing is non-destructive: it's stored as a per-layer eraser mask, so it moves with the layer and is undoable. With no layer selected it shows "Select a layer to erase").
- **Brush size** slider (1–200 px at canvas scale) with a live preview dot. **Opacity** slider. Color from the palette (§9).
- Smooth strokes (stroke smoothing/stabilization). Uses stylus pressure when available, optionally.
- Strokes are stored as vector data where practical so they stay sharp. Eraser works correctly either way.

### 10.2 Image layers
- **Add image:** from the device photo library / files (system photo picker). Multiple images can be selected at once, which suits collages. New images are placed centered and scaled to fit within the canvas.
- **Adjust panel** (for the selected image layer): Crop (Freeform / aspect presets) · Rotate 90° · Flip horizontal / vertical · Opacity · Brightness · Contrast · Saturation · Warmth (optional) · Reset adjustments. Adjustments are non-destructive: the original stays in `assets/` and adjustments are stored as parameters.
- Optional: rounded corners / border on image layers (handy for collages).

### 10.3 Canvas
- Resize canvas (anchored, with no scaling of layers) and change the background (white / transparent / color).
- Optional later: simple collage layout templates (2-up, 3-up, grid) that place image slots.

---

## 11. Export

- **Export** button in the editor opens an export sheet:
  - **Format:** **PNG** (supports transparency) · **JPG** (UI always says "JPG", files use `.jpg`; quality slider 50–100, default 90, shown only when JPG is selected; transparency flattened onto white or the chosen background) · **GIF** (single still frame; palette-quantized with dithering; transparency supported) · **WebP** (optional).
  - **Size:** 100% (default, full project resolution) · 50% · 25% · custom long-edge pixels.
  - **File name** (defaults to the project name).
  - Actions: **Save to device** (Android: MediaStore into Pictures/Basic Art; iOS: save to Photos, or Files via the document picker) and **Share…** (system share sheet).
- The export renders from the document model at full resolution. It does not capture the screen.
- A success message shows where the file went. Export failures show a clear message and never crash.
- Exporting never changes or deletes the project.

---

## 11a. Project files (move a project to another device)

- **Export project file:** from the project tile's ⋮ menu on the home screen ("Export project file") and as an option in the editor's Export sheet. It packs the whole editable project — canvas, every layer with all its settings, text styling, imported photos, drawing strokes and eraser masks — into one **.zip** file (format in `shared/FORMAT.md`, "Project package"), **plus the editor state** — which layers are selected, the active tool and text tab, the zoom/position, and any text selection — so it reopens on the other device exactly where you left off. Only the undo history is not included. Save to device (Downloads/Files) or Share.
- **Import project file:** an **Import** action on the home screen (and "Open in Basic Art" from the system Files app / share sheet where the OS allows it). Works across Android and iOS. The imported project always arrives as a new project ("Name (2)" if the name exists), and opens exactly as it was on the other device, with the same layers and selection.
- Import is safe: it rejects damaged or malicious zip files with a clear message, refuses projects from a newer app version with "Update Basic Art to open this project", and never leaves half-imported files behind.
- No new permissions: uses the system file picker / document picker and share sheet.

## 12. Permissions and privacy (store-safe)

- **No network:** the Android manifest has no `INTERNET` permission, and iOS makes no network calls.
- **Importing photos:** Android uses the **system Photo Picker** (no `READ_MEDIA_IMAGES` / storage permission needed; fallback `ACTION_OPEN_DOCUMENT` on older versions). iOS uses **PHPickerViewController** (no photo library permission prompt).
- **Saving exports:** Android uses **MediaStore** (no `WRITE_EXTERNAL_STORAGE` on API 29+; scoped write on older versions only if required) or SAF `ACTION_CREATE_DOCUMENT`. iOS uses add-only Photos permission (`NSPhotoLibraryAddUsageDescription`) or the document picker / share sheet.
- **No** `MANAGE_EXTERNAL_STORAGE` and no broad file access.
- **PRIVACY.md** and an in-app privacy note state that Basic Art collects no data, makes no network connections, and keeps projects only on the device. Uninstalling the app deletes projects; exported images remain.
- **System backups:** projects and settings are included in the user's own OS backups (Android Auto Backup / device transfer; iCloud & computer backups on iOS). Caches and editing proxies are excluded. The app itself still never uses the network.
- Store listing data-safety answer: "No data collected or shared."

---

## 13. Settings

Opened from the gear on the home screen.

- **Clear all project history:** deletes every saved project in one go. It shows a strong confirmation: "Delete ALL projects? This permanently removes every project from Basic Art and **cannot be undone**. Images you've already exported are not affected." The user must tap a red **Delete everything** button. A second confirmation (typing "DELETE" or holding the button) is optional but recommended.
- **Theme:** System / Light / Dark.
- **Snapping** on/off.
- **Default export format** and **JPG quality** (the quality slider only appears when JPG is the default).
- **About Basic Art:** version; "No ads. No accounts. No internet. No in-app purchases. A simple, basic art editing tool."; and a link-free note that it's open source (the GitHub URL `github.com/danavfrost/basicart` is shown as text).
- **Licenses & Credits:** the app's license (MIT) and every bundled font and library license.

---

## 14. UI and visual design

- Clean, modern and uncluttered, with plenty of canvas space. Light and dark themes.
- Large touch targets (≥ 48 dp / 44 pt). Icons have labels or long-press tooltips.
- Panels are compact and collapsible, and never cover the whole canvas on phones.
- Works well in portrait and landscape and on phones and tablets (§6).
- Smooth 60 fps interaction for moving, scaling and drawing on typical projects (up to about 20 layers at 2–4K canvas size).
- Accessibility: content descriptions / VoiceOver labels on all controls, and support for system font scaling in the app UI (not the canvas).

---

## 15. Performance and stability requirements

- Cold start to the home grid in under 1 second on a mid-range device. Thumbnails load lazily.
- Imported photos are downsampled for on-screen editing proxies, while the original is kept for export.
- Memory: large canvases and many images must not crash the app. It degrades gracefully (proxies, warnings) instead.
- No main-thread blocking during save, thumbnail generation or export. Export shows progress and runs in the background.
- No crash should lose work (autosave + atomic writes).

---

## 16. Testing

### 16.1 Automated
- **Unit tests (both platforms):** project model; JSON serialization round-trip; format migration; layer ordering ops (move up/down/top/bottom, drag reorder); undo/redo stack; export encoders (PNG/JPEG/GIF produce valid files with correct dimensions); clear-all and delete-project file cleanup.
- **Cross-platform fixtures:** project files in `shared/fixtures/` that both apps must load and render the same way.
- **UI tests:** home grid (New tile first, project tiles, ⋮ delete with confirm), new-image flow, add text, reorder layers, export.

### 16.2 Manual / emulator testing (required before each release)
Test on:
- **Android emulator-5554** (phone)
- **Android emulator-5556** (tablet)
- **iPhone simulator** (Xcode), plus an iPad simulator

Scenarios:
1. Create new images of several sizes and presets, including transparent and colored backgrounds.
2. "Start from photo" → add top and bottom text boxes by hand with the Classic Meme preset → export JPG.
3. Collage: multiple imported images, move/scale/rotate, reorder via the layers panel (drag) and via long-press (Move to top/bottom).
4. Text: every tab — fonts, bold/italic/underline, outline styles and thicknesses, glow, shadow, background box, gradient, curve, rotation at odd angles, presets.
5. Drawing: each brush type, sizes, colors, eraser.
6. Image adjustments: crop, flip, rotate, brightness/contrast/saturation.
7. Undo/redo across all of the above.
8. Export PNG, JPEG and GIF (and WebP if built); verify the files open and have the correct dimensions and transparency; save to device and share.
9. Leave the editor, return to home, reopen the project, and confirm it's identical. Kill the app mid-edit, reopen, and confirm no meaningful loss.
10. Delete a project from ⋮ (confirm dialog). Clear all project history from Settings (confirm dialog).
11. Rotate the device and check the tablet layout.
12. Screenshots are reviewed for visual quality: clean, aligned, not cluttered.

---

## 17. Release and repository

- Public GitHub repo; **MIT** license for the app code; font licenses included alongside each font.
- **README.md** must cover:
  - **Basic Art — a simple, basic art editing tool.**
  - **No ads · No accounts · No internet · Simple.**
  - **Getting it:** $0.99 on Google Play and the Apple App Store (automatic updates; pay once, never asked again). The source code is free here under MIT, and an APK is posted on the GitHub page from time to time (it may lag behind the store versions). Anyone can build it from source.
  - Why it exists: there wasn't a good, simple, affordable art creation tool. Popular editors have become complex, slow, online-dependent and glitchy. Basic Art is fast, offline, stable and just gets the job done. (Don't name or bash specific competitors.)
  - Feature list (text tool first), screenshots, build instructions for Android (64-bit + 32-bit APKs) and iOS, and the privacy statement.
- **PRIVACY.md** (see §12).
- Git: repo-local identity "Dana Frost <danavfrost@gmail.com>".

---

## 18. Out of scope for v1

- Animated GIF / video
- Cloud sync, accounts, sharing feeds, templates downloaded from the internet
- AI features
- Advanced pro tools (curves, masks, selections, blend modes beyond normal, filters marketplace)
- In-app purchases of any kind

---

## 19. Open decisions

- Final app/bundle id and company name for store listings.
- Text box corner-handle behavior: scale font vs. resize box (§8.1).
- Whether to include WebP export and user-imported fonts in v1.
- Minimum OS versions (suggested Android 8.0 / iOS 16).
