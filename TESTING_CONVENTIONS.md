# Testing conventions

Read this before adding or changing tests.

## The one-dispatcher rule

Use the **single** dispatcher exposed by `MainDispatcherRule`. Never create
a separate `TestScope` or `StandardTestDispatcher` in a test.

```kotlin
@get:Rule
val mainRule = MainDispatcherRule()

@Test
fun something() = runTest(mainRule.testDispatcher) {
    val vm = EditorViewModel(/* fakes */, ioDispatcher = mainRule.testDispatcher)
    vm.loadImage(context, uri)
    advanceUntilIdle()
    // assert
}
```

`MainDispatcherRule` installs one `StandardTestDispatcher` as
`Dispatchers.Main` for the test. Pass **that same dispatcher** to the
ViewModel as its `ioDispatcher`, and drive `runTest` with it. One scheduler
means `advanceUntilIdle()` deterministically runs every coroutine —
`viewModelScope` work and `withContext(ioDispatcher)` work alike. Spinning
up a second dispatcher/scope splits the virtual clock and makes tests flaky
and confusing.

Helpers that need to advance the clock take the `TestScope` as receiver so
`advanceUntilIdle()` resolves, e.g. `private fun TestScope.readyViewModel(…)`.

## Where each kind of logic is tested

- **Pure math** (`LayerGeometry`, `LayerSnap`, `calculateInSampleSize`,
  `WallpaperState`, `MemoryReport`, `LayerCodec`) — plain JVM JUnit. No
  Android, no Robolectric, no coroutines. Placement, zoom range, pinch, edge
  resistance, snap-back and the decode budget are pinned down here.
  (`LayerGeometryTest`, `LayerSnapTest`, `BitmapDecodingTest`, …)

- **File storage** (`LayerImageStore`) — plain JVM against a JUnit
  `TemporaryFolder`; it is `java.io` only. (`LayerImageStoreTest`)

- **Real bitmap composition** (`WallpaperFlattener.flatten`) —
  Robolectric with `@RunWith(AndroidJUnit4)`, `@Config(sdk = [36])`, and
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)`. NATIVE mode is **mandatory**:
  without it `Canvas` draws paint nothing and `getPixel` returns 0, so pixel
  assertions are meaningless. (`WallpaperFlattenerRobolectricTest`)

- **State orchestration** (`EditorViewModel`) — plain JVM with hand-written
  fakes. The ViewModel never touches real pixel data: the decoder seam
  returns `DecodedLayer`s around a relaxed-mock `Bitmap`, and placement only
  reads the original size. (`EditorViewModelTest`)

## Mocking

- Use `mockk(relaxed = true)` for framework types (`Bitmap`, `Context`,
  `Uri`) and stub only what a test actually reads. Assert on **state
  transitions and forwarded arguments**, not on bitmap contents — that's the
  Robolectric layer's job.
- The ViewModel's seams (`WallpaperRepository`, `LayerDecoder`,
  `WallpaperExporter`) get small hand-written fakes (recording / failing /
  throwing) rather than mocks, for readable assertions. `LayerImageStore` is
  used for real on a temp folder.

## What every behavior change needs

A test. Geometry or snap change → a `LayerGeometryTest` / `LayerSnapTest`
case. Drawing change → a Robolectric pixel case. State/flow change → an
`EditorViewModelTest` case. Keep pixel math out of the ViewModel and
Composables so it stays in the fast, pure layer.
