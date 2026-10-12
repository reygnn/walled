# TODO

- **Device check.** The first version is only JVM/Robolectric-tested. On the
  Pixel 9a check: pinch/drag feel against the launcher, the snap-back
  animation (driven by `delay` frames in the ViewModel, not the display
  clock), and that `setBitmap` lands pixel-exact on home and lock.
- **EXIF orientation.** `BitmapFactory` ignores it, so a camera photo can
  appear rotated. The launcher has the same gap. Fix candidates: read the
  orientation with `ExifInterface` and rotate in `LayerPainter`'s matrix,
  or decode via `ImageDecoder` (applies it, but sizes need re-checking).
- **FAB position** is not persisted (the launcher persisted it as screen
  fractions — `FabPositionStore`).
- **Instrumented parity test** (preview canvas vs. export, on device),
  modelled on the launcher's `WallpaperCompositorParityInstrumentedTest`, if
  the Robolectric pixel test ever proves insufficient.
