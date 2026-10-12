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
- **Layer stack:** move the active layer up/down, remove it, or remove all.
- **Set wallpaper:** 🏠 home screen, 🔒 lock screen, or 📱 both. The layers
  are rendered at the screen's native resolution.
- **Memory info:** decoded and source size and memory per layer. Large
  photos are downsampled to about 10.5 MP for decoding.

Everything is saved as you go: the layers (as private copies of the picked
images) and the snap settings survive restarts.

## Using it

1. Tap **🛠** → **➕** and pick an image. Repeat for more layers.
2. Drag and pinch to place the active layer; tap another layer to switch.
3. **☰** opens the menu: layer order, removal, snapping, memory.
4. Tap **🛠** → 🏠 / 🔒 / 📱 to set the wallpaper.

## Build

```bash
JAVA_HOME=/snap/android-studio/current/jbr ./gradlew :app:testDebugUnitTest
JAVA_HOME=/snap/android-studio/current/jbr ./gradlew :app:bundleRelease
```

The release AAB is unsigned; install it with `~/apk/install-aab.sh`.

Android 16+ only, dark theme, English and German.
