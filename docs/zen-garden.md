# Zen Garden: how it works

Read this before changing `SandGardenView.kt`, `SandRenderer.kt`, `SandSound.kt` or `SandGardenLayout.kt`.

## The parts

- **The sand** is a height grid: 1.5 squares per dp (`SandGardenView.RES`; every size in squares is written as
  dp × RES). It's redrawn only where the rake touched it; big changes (placing something, Undo) are redrawn a
  band of rows per frame (`redrawAll`).
- **Placed things** (stones, bonsai, lanterns, flowers) are baked into the sand's own surface (`bake`: height,
  colour and soft outline per square), so the sun, lantern and firefly light and shadows treat them like sand.
  Everything about a placed thing comes from its seed, so it looks the same every time.
- **Smooth drawing** (`SandRenderer`, Android 13+): `shade` hands it each square's values (brightness, slopes,
  height, placed things); two graphics-chip programs blend them and colour each screen pixel, the lanterns' light
  included (ridge shadows and all). The sand and each lantern's light are kept in 64-square tiles, redrawn only
  where the sand changed. Older phones, and the garden's Battery saver setting, use the garden's own
  square-by-square colours and `relight`.
- **Sound** (`SandSound`): the raking sound is made in code, filtered noise whose tone was fitted to the owner's
  recording `tools/sandsound.mp3`. Its thread runs only while raking.
- **Zen mode** (`SandGardenLayout`): `ZenScreen` is the tray in its wooden frame (`SandGardenView.drawFrame`), with
  a button carved into each corner block: Exit top left, the arrow bottom left that opens the row of round
  `ZenIcon`s, and move/resize on the right. The window has no top bar or background
  (`BubbleService.setGamesFrameless`): its move and resize corners become invisible touch areas on the carved
  corners (`pinGamesCorners`), and the window shrinks to the tray (`fitGamesWindowToTray`). The garden scales to
  fit the window unless "Scale garden to window" is off; the tray sits in the window's top-left corner, so
  resizing keeps that corner still.
- **Automatic** rakes rings only around stones and lanterns, with one stick (a two-stick version was tried and
  dropped).
- The frame's wood is `res/drawable-nodpi/zen_wood.jpg` (Poly Haven, CC0, credited). `tools/gardenbox.png` is the
  owner's frame sketch (not committed).

## Battery and smoothness rules

- Nothing slow runs on the main thread mid-stroke: the lantern light and saving both made strokes stutter until
  moved off it (the light onto the graphics chip, saving onto a background thread). Only the squares a press can
  touch are visited. Nothing allocates objects in `onDraw`.
- The light is fixed: a slider only changes a wash and the lantern strength.
- While a lantern is out and the garden is on screen, it redraws ~25 times a second for the flame flicker (only
  each lantern's ready-made light picture changes brightness; nothing is recalculated). The auto rake and the
  fireflies each run ~30 frames a second, only while switched on and on screen; a glowing firefly relights only
  its own patch. This idle redrawing costs about a quarter of a core: worth trimming one day.

## Measuring

- Test builds log, once a second while the garden is busy, the main thread's time per step
  (`adb logcat -s ZenTiming`: rake, shade, light, draw).
- The game's frame rate while the garden runs: `dumpsys SurfaceFlinger --timestats -enable -clear`, wait,
  `--timestats -dump` (the game's layer is `SurfaceView[com.jagex.oldscape.android...]`). The garden's last ~120
  frames: `dumpsys gfxinfo com.sinat.osrsbubbletool framestats`. Graphics chip load:
  `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`.
- Baseline (October 2026, smooth drawing): the game at 60-62 fps with no dropped frames (garden idle, Auto or
  raking); the garden ~2 ms of graphics chip per frame; raking ~25% of the main thread. The phone's screen runs at
  120 Hz, so the garden draws ~118 frames a second while raking.
- A fair before/after test (5 October 2026): restore the owner's garden (4 lanterns, fireflies, dark, small
  window) from a backup before each run, then 30 s of scripted back-and-forth strokes (`adb shell input swipe`:
  only with the owner's OK and the game closed). Results then: raking 81% of a core, graphics chip 32% busy, ~2%
  late frames, slowest 1% at 11-12 ms; idle 10.6% and 3-4%. Scripted strokes are much heavier than raking by
  hand, so compare only runs done the same way.
