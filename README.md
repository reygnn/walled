# walled

A small Android app that stacks images as layers into one wallpaper and
sets it directly as the home and/or lock screen — pixel-exact, without the
system picker's crop and zoom.

It is the wallpaper editor of Unity-Launcher, made into its own app.

## What it does

- **Layers:** add any number of images (photo picker). The bottom layer is
  drawn first; transparent areas of an upper layer show the layers below.
  Uncovered areas end up black.
- **Placement:** drag to move the active layer, pinch to zoom it. Tap a layer
  to make it active. Presets: ↔ fit width, 1:1 original size, ⛶ fill the screen.
- **Snapping:** 🧲 magnetic snapping to the screen edges or to the center,
  separately for the horizontal and vertical axis, with a rubber-band
  resistance and a haptic tick at the edge.
- **Fix in place:** 📌 locks the layers against drag and pinch, so a system
  gesture from the screen edge (home, back, switching apps) cannot nudge
  them. Buttons keep working; adding a layer releases the lock.
- **Preview factor:** the home screen enlarges a set wallpaper a little
  (Android's wallpaper zoom; preset to 1.10, which matches a Pixel). Type
  that factor (🔍, 1.00–2.00) and the
  preview enlarges the same way, so it shows what will really be visible.
  Only the preview changes, never the set image.
- **Layer stack:** move the active layer up/down, remove it, or remove all.
- **Set wallpaper:** 🏠 home screen, 🔒 lock screen, or 📱 both. The layers
  are rendered at the screen's native resolution.
- **Memory info:** decoded and source size and memory per layer. Large
  photos are downsampled to about 10.5 MP for decoding.
- **Projects:** save the layers as a JSON project into a folder of your
  choice and open them again later. A project lists each image by file name
  (in the same folder) with its scale and position:

  ```json
  {
      "format": "walled-project",
      "version": 1,
      "frame": { "width": 1080, "height": 2340 },
      "layers": [
          { "file": "ducks.png", "scale": 1.534, "x": 0.0, "y": 58.36 },
          { "file": "seal.png" }
      ]
  }
  ```

  Layers go bottom to top. `x`/`y` are screen pixels, `scale` is screen
  pixels per image pixel (1 = original size). A layer without
  `scale`/`x`/`y` fills the screen. `frame` is the screen the values were
  made for; on a different screen they are scaled to the width and centered
  vertically. Projects can be written or edited by hand.

Everything is saved as you go: the layers (as private copies of the picked
images), the snap settings and the project folder survive restarts.

## Using it

1. Tap **🛠** → **➕** and pick an image. Repeat for more layers.
2. Drag and pinch to place the active layer; tap another layer to switch.
3. **☰** opens the menu: projects, layer order, removal, snapping, memory.
   Choose a project folder once (📁), then open (📂) or save (💾) projects.
4. Tap **🛠** → 🏠 / 🔒 / 📱 to set the wallpaper.

## Build

```bash
JAVA_HOME=/snap/android-studio/current/jbr ./gradlew :app:testDebugUnitTest
JAVA_HOME=/snap/android-studio/current/jbr ./gradlew :app:bundleRelease
```

The release AAB is unsigned; install it with `~/apk/install-aab.sh`.

Android 16+ only, dark theme, English and German.
