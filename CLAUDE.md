# CLAUDE.md

Project conventions for **walled** — a multi-layer wallpaper composer: stack
images as layers, place each one by drag and pinch, and set the flattened
result as the system home and/or lock screen wallpaper.
Claude Code reads this file automatically at session start. Keep it short
and actionable — not a marketing description (that's `README.md`).

For the working agreement, see `WHY_CLAUDE.md`. For Material 3 rules, see
`MATERIAL3RULES.md`. For test conventions, see `TESTING_CONVENTIONS.md`.

Cross-project conventions (stack baseline, git workflow, test philosophy,
code language) live in `~/.claude/CLAUDE.md` and are loaded automatically.
This file only holds what is specific to this project.

---

## Origin

walled is the wallpaper feature of **Unity-Launcher** (Kolibri + Nyx),
extracted into its own app on 2026-10-12. The launcher keeps its copy
unchanged; nothing is shared at build level. The pure logic was ported
(placement, zoom range, pinch, edge resistance + snap-back, decode budget,
memory report, layer stack); the View/Hilt UI was rebuilt in Compose without
DI. When a launcher behaviour is in question, its sources are the reference:
`Unity-Launcher/common-ui/.../wallpaper/` (`WallpaperLayerPlacement`,
`ZoomableImageView`, `WallpaperFlattener`), `feature-wallpaper/`,
`core/.../wallpaper/`.

Deliberate differences from the launcher:

- **Output is the system wallpaper** (`WallpaperManager.setBitmap`, home /
  lock / both), not an in-app view behind a launcher UI. Hence no scrim, no
  backdrop toggle, no luminance / surface-mode classification, no
  drawer-rebuild or composite-cache machinery.
- **No edit session** (commit/cancel). The app *is* the editor: every
  finished change persists at once.
- **Transforms are per ORIGINAL image pixel** (`LayerTransform.scale` = 1
  draws 1:1). The launcher stored them per decoded-bitmap pixel and needed
  `captureSampleSize` + legacy backfill to survive decode-budget changes;
  walled has no such field and needs no compensation.
- **Export decodes one layer at a time** (frame + one layer peak memory),
  still all-or-nothing.

## Scope

Does: pick images as layers (photo picker), move/zoom the active layer,
select by tap, reorder/remove layers, fit-width / 1:1 / fill presets,
magnetic snapping (edges or center, per axis), memory info, set as home /
lock / both.

Doesn't (needs explicit agreement before building): rotation, per-layer
opacity, PNG export, backup/restore, live wallpaper, EXIF orientation (the
launcher ignored it too — see `TODO.md`).

## Stack particulars

- DataStore Preferences for the layer list (JSON via kotlinx-serialization)
  and the snap settings.
- kotlinx-serialization 1.11.0 (plugin version tracks Kotlin).
- Release builds are **unsigned**; `~/apk/install-aab.sh` signs them with the
  family key.

## Architecture

```
com.github.reygnn.walled/
  WalledApplication       composition root (repository, image store)
  MainActivity            immersive full-screen host
  model/                  WallpaperState / LayerTransform / SnapSettings / MemoryReport
  imaging/                LayerGeometry, LayerSnap (pure), decodeLayer, LayerPainter,
                          WallpaperFlattener (export + WallpaperManager)
  data/                   LayerImageStore (files), WallpaperRepository (DataStore), LayerCodec
  viewmodel/              EditorViewModel (state, gestures, persistence, apply)
  ui/                     EditorScreen, LayerCanvas, EditorFab, CommandsPanel, Dialogs
```

The output **frame** is the display in portrait (`maximumWindowMetrics`).
Layer transforms, gestures and the export are all in frame pixels;
`LayerCanvas` maps its own coordinates to the frame (`FrameFit`).

## Hard rules

1. **One placement, one draw loop.** The preview (`LayerCanvas`) and the
   export (`WallpaperFlattener`) both place with `LayerGeometry.resolve` and
   draw with `LayerPainter`. Never add a second code path for either — the
   preview must stay what gets set.
2. **Save before delete.** A removed layer's image file is deleted only after
   the state without it has been saved (`persistLayers(deleteAfterSave)`).
   Leftovers from a killed process are collected by the orphan GC at start
   (`LayerImageStore.deleteOrphans`, 60 s minimum age).
3. **Export is all-or-nothing.** A layer that cannot be decoded blocks
   "apply" (`unloadable`) — never set a wallpaper with a hole in it.
4. **The state stores file names, never paths or `content://` URIs.**
   Picked images are copied into `filesDir/layers/`.
