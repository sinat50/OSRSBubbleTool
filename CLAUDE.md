# OSRS Bubble Tool: notes for working on this project

A free, ad-free Android app for Old School RuneScape mobile: a floating bubble over the game that opens
small tool windows. Kotlin, Android Views (no Compose), one app module. Package `com.sinat.osrsbubbletool`.
GitHub: sinat50/OSRSBubbleTool. The README is the user-facing description of every tool.

## Working with the owner

- The owner has no coding experience. Explain in plain language, give exact click-by-click steps for
  Android Studio (Windows), and write code comments in plain language too.
- They test by pressing ▶ in Android Studio on a Samsung phone (SM-S942W), usually in landscape. The phone
  is often plugged into the PC, so builds can be installed and checked directly (see Checking your work).
- Priorities, in order: battery, storage, accuracy. Nothing runs in the background unless it must.
- The signing key lives in `C:\Users\Tanis\Documents\Androiddev\Keys\` and must never be uploaded, moved
  into the project or committed. Don't ask for access to that folder.
- Don't send the README every time; update it when features change and mention it at commit time.
- Release notes and commit messages are written for players, not developers. The owner commits in Android
  Studio themselves: finish a change with a suggested commit message.
- When committing, remind them that brand-new files sit under "Unversioned Files" in the Commit window and
  are NOT ticked by default (a commit once left out `AssetDownloader.kt` and `GearIcons.kt`). `HANDOFF.md`
  (notes between work sessions) is in `.gitignore`, so it never shows up there.
- Android Studio's commit check ("Analyze code") lists warnings; they don't block a commit.

## Where things are

Code: `app/src/main/java/com/sinat/osrsbubbletool/` (one file per tool, roughly):

| File | What it is |
| --- | --- |
| `BubbleService.kt` | The bubble, its long-press menu, tool windows (move/resize), the `Tool` list (its order is the menu order), `makeParts()` (the one place each tool is set up), and keeping windows clear of the status bar |
| `MainActivity.kt` | Main screen, Permissions, Legal (all credits and licence notices) |
| `CaptureManager.kt`, `ScreenCapturer.kt`, `CapturePermissionActivity.kt` | Screen capture: asked once per bubble session; Android 14+ can share just the game |
| `PuzzleBoxTool.kt`, `PuzzleFinder.kt`, `GridFinder.kt`, `TileMatcher.kt`, `PuzzleSolver.kt`, `MoveGuideOverlay.kt`, `PuzzleReferences.kt` | Puzzle Box Solver |
| `LightBoxTool.kt`, `LightBoxReader.kt`, `LightBoxGuideOverlay.kt` | Light Box Solver |
| `ToaPuzzleTool.kt`, `ToaPuzzles.kt`, `ToaReader.kt` | ToA Puzzle Helper (Beta): the five Path of Scabaras puzzles. `ToaPuzzles` = rules and answers (from the LlemonDuck Tombs of Amascut plugin, BSD); `ToaReader` = screen reading, plus `MatchMemory`, what matching Watch remembers between pictures (no Android parts, testable on the PC; the replay test uses the same `MatchMemory`); `ToaPuzzleTool` = the pages, Watch loops and the map drawn at the bottom of the screen. See "ToA Puzzle Helper" below |
| `DpsTool.kt`, `GearRecognizer.kt`, `GearIcons.kt`, `PanelFinder.kt` | DPS Calculator (wiki web page) and Import my gear |
| `InventorySetupsTool.kt`, `RegionOverlay.kt`, `PuzzleAreaOverlay.kt` | Inventory Setups, and the "set area by hand" frames |
| `TeleportFinderTool.kt`, `TeleportData.kt`, `WalkMap.kt` | Teleport Finder (Beta): wiki lookup, teleport list, walking-route search |
| `QuestHelperTool.kt`, `QuestData.kt`, `QuestGuides.kt`, `QuestBook.kt`, `QuestPuzzles.kt`, `QuestSolvers.kt`, `QuestSolverViews.kt`, `PuzzleDiagramView.kt` | Quest Helper (Beta), from the Quest Helper RuneLite plugin (`PuzzleDiagramView` draws the puzzle answer maps) |
| `WikiSync.kt`, `WikiSyncTool.kt` | WikiSync account data, and the ✓/✗ WikiSync tag other tools show |
| `HunterRumourTool.kt`, `HunterRumours.kt` | Hunter Rumours |
| `ZulrahTool.kt` | Zulrah Helper |
| `FarmingTool.kt`, `FarmingTimers.kt`, `FarmingTimerReceiver.kt` | Timers (one exact alarm per timer, no background work) |
| `GameRoomTool.kt`, `Game2048View.kt`, `FlyerView.kt`, `GameData.kt` | Game Room: 2048 and Wing It |
| `CalculatorTool.kt`, `NotesTool.kt`, `UpdateChecker.kt`, `WikiLinkActivity.kt` | Calculator, Notepad, GitHub update check, wiki links opening in the bubble |

Resources: `app/src/main/res/`: `drawable/ic_bubble.xml` (the bubble), `ic_launcher_bubble*.xml` and
`ic_launcher_monochrome.xml` (app icon, plus its one-colour version for Android 13+ themed icons),
`xml/backup_rules.xml` and `xml/data_extraction_rules.xml` (what Android backs up, see below). There are no
layout files: every screen is built in code.

Data: `app/src/main/assets/`

| File | Built by | From |
| --- | --- | --- |
| `teleports.tsv`, `walks.txt` | `tools/teleport-finder/build_teleports_and_walks.py` | Shortest Path plugin, plus hand-checked fixes and additions in the script |
| `collision.bin` | `tools/teleport-finder/build_collision.py` | Shortest Path's `collision-map.zip` |
| `dungeons.txt` | `tools/teleport-finder/build_dungeons.py` | RuneLite world map `DungeonLocation.java` |
| `entrances.tsv` | (older, kept only as a fallback for straight-line estimates) | |
| `dps/equipment.json` | `tools/dps/build_equipment_json.py` | OSRS Wiki DPS calculator repository |
| `dps/empty_slots.png` | cut from a game screenshot | what empty equipment slots look like |
| `dps/icons/` (4,991 item pictures, 2.6 MB), `puzzles/`, `quest_images/` | `tools/dps/fetch_pictures.py` | DPS calculator repository at `GearIcons.COMMIT`; OSRS Wiki |

See `tools/README.md` for how to run the scripts.

`trailer/` (at the top of the project, and in `.gitignore`, so it's only on the owner's computer, not on
GitHub) holds the finished trailer, its opening and closing cards as clips and stills (`trailer/out/`), and
the source for all of it. To update the cards for new features, edit `trailer/splash.json` and follow
`trailer/README.md`. The closing card lists all 18 tools from the README (no "Also:"); the full 36 s trailer
in `trailer/full-trailer/` still has the old list and says "17 tools". The PC has no Node.js or ffmpeg: the
cards were recorded with a Python port of `render.js` (Playwright for Python plus imageio-ffmpeg in a
throwaway environment). `trailer/toa-demo/` holds a 62 s ToA Puzzle Helper demo (`toa-demo.mp4`, made from
`raw/toa_demo.mp4`, a phone screen recording, by `edit.py` and `captions.py`: cuts, speed-ups with "3× speed"
labels, captions, the owner's name in the chat smeared, then `outro-wide.mp4`, the closing card at the phone's
2340×1080). Phone screen recordings: `adb shell screenrecord --time-limit 0 /sdcard/Movies/<name>.mp4` with
the game already sideways; stop it with `kill -INT` on the `screenrecord` process (`pkill` hits the wrong one)
so the file is finished properly, then copy it off and delete it from the phone. In Git Bash, set
`MSYS_NO_PATHCONV=1` or phone paths get mangled.

Don't read or search `build/`, `.gradle/`, `.idea/` or `.kotlin/`: they're generated and huge.

## How things work (the parts that aren't obvious)

- **Tools** are only created the first time they're opened. Each one gives `makeParts()` a `ToolParts`:
  its window contents, its ◀ action, and what to do when its window closes, the phone turns, or the
  bubble closes. A new tool needs its line in the `Tool` list and its part in `makeParts()` (the build
  fails until it has both).
- **Windows**: every tool window can be moved (drag the top bar) and resized (bottom-right corner;
  double-tap it to reset), except the Light Box and Puzzle Box Solvers, which are small and sit top right.
  Windows share one position; each tool remembers its own size per orientation. The Notepad starts at
  the top, sized to fit above the keyboard.
- **Status bar**: Android draws it over everything, so a window's bar under it can't be grabbed. Overlay
  windows aren't told when it shows or hides (window insets don't work for them), so
  `BubbleService.addStatusProbe()` adds an invisible 1-pixel strip that Android pushes below the status bar;
  its top is how far down the status bar reaches. While it shows, windows and the bubble move below it (too
  tall windows get shorter for a while); they go back to where you left them when it hides, as it does
  in the game. Tested and working on the owner's phone.
- **Game pictures come with the app** (owner's decision, October 2026), in `assets/dps/icons/`,
  `assets/puzzles/` and `assets/quest_images/`, made by `tools/dps/fetch_pictures.py`. They're Jagex's
  artwork, included under Jagex's Fan Content Policy; if Jagex objects, the fallback is downloading them
  again. History: v1.0.6 test builds downloaded them instead (first a 170 MB ZIP of the whole DPS calculator
  repository, almost all monster pictures; then each picture separately: GitHub was ~30 s at 48 at once,
  jsDelivr 2.5-5 min until its caches warm). osrsbox-db's per-slot files were checked too: frozen since
  2021, only 67% of the items. `MainActivity.removeOldDownloads()` deletes those downloaded copies once.
  The DPS tool loads all item pictures into memory when its window first opens (the owner chose to keep
  this, so Import my gear is quick).
- **Backups**: only shared preferences and `files/setups/` (Inventory Setups pictures) are backed up or
  moved to a new phone (Android stops backing up an app entirely past 25 MB).
- **Screen capture** pauses itself 3 seconds after the last use and is asked for once per bubble session.
  With single-app sharing, pictures are placed where the game sits on screen. The Puzzle Box and Light Box
  Solvers check the screen every 80 ms while guiding (`capture.sample`, a few pixels, no picture made);
  if only the game is shared and you leave it (`capture.gameHidden`), they stop and take their outlines off
  the screen. A re-plan needs a fresh picture, so it must `grab()` before anything `sample()`s that tick.
  Heavy work (finding the puzzle, reading tiles, recognising gear) runs on background threads.
- **Battery**: web pages in hidden windows are paused; Wing It only animates while you're playing; nothing
  allocates objects in `onDraw`.
- **Teleport Finder routes**: `WalkMap` spreads out from the target (reverse Dijkstra over the collision
  map plus `walks.txt`). Walks can carry requirements ("70 Agility; Regicide"); with WikiSync set up, ones
  you can't use are skipped. Closed-off areas fall back to dungeon-entrance estimates ("guess" results).
  Joins into closed-off areas are precomputed (`find_closed_off_areas.py`).
- **Wiki links** from the game open in the bubble's Wiki window via `WikiLinkActivity`, only if the owner
  turns on "Open by default" for the wiki (it can't be auto-verified: not our website).
- **ToA Puzzle Helper** (built October 2026 with the owner testing live in raids; all reading was built from
  the owner's phone screenshots, never guessed):
  - Light: one picture, finds the ring of 8 plates (unlit = solid yellow square on purple, lit = pale glow with
    a beam; facing east the squares show as diamonds). Addition: reads the red number from the chat line "The
    number N has been hastily chipped into the stone." (digit templates typed by the owner into the chat; only
    the left 60% of the screen is searched, so a red HP orb can't fool it). The symbol layout is fixed every
    raid. Sequence: one picture finds the 9 tiles, then `capture.sample` checks just those tiles ~12×/s for
    the 5 flashes. The tiles are found only once, when Watch is tapped, so the camera must stay still until they
    flash (the page's tip under Watch says so). It waits 20 s for the first flash (`WATCH_FIRST_FLASH_MS`; the
    owner tried 2 minutes and found it excessive), with a seconds countdown next to Stop in the shrunk window
    (`sequenceSecondsLeft`), then 5 s for each next one. Obelisk: tap-in only (obelisks are small and hard to see from the safe spot).
  - Matching ("Watch"): the camera follows the player, so both 3×3 boards are found again in every picture,
    3 looks a second (~130 ms each on the phone), plus up to 3 quick looks in a row (40 ms apart) right after a
    look that saw a symbol not settled yet (`MatchMemory.unsure`), so it's caught before the player steps off it.
    A pair counts as matched after glowing 0.8 s (was 1.5 s; replays unchanged apart from marking sooner). Hard-won rules, each found from real failures:
    first look needs both boards side by side, and picks the pair with the most yellow on both (the
    see-through inventory over the next room's floor makes a perfect fake board of "hidden-grey" squares);
    after that each board keeps its identity and direction from the last picture (`orientLike`; players turn
    the camera a lot); if both are lost it searches afresh (`freshPair`); jumps over 2.5 tiles are bad fits;
    only boards actually found are read, and symbols only from boards with 8+ of 9 tiles lined up (7 can be a
    tile out of place, which put a wiggle on the wrong tile at close zoom); a tile counts as matched only in
    pairs (`settlePairs`; the orbs by the minimap can look like a glow), or alone after glowing 4 s away from
    the right-hand UI (a raid's starting pairs, whose partner may be hidden). Revealed symbols vanish when the
    player steps on the next tile and are often half hidden by the player, so recognition compares only the
    visible points against 31 samples (`SYMBOL_SAMPLES`, from the matching and addition rooms).
  - Several "obvious" improvements made the recorded replays worse and were undone: searching only near the
    boards, skipping a board once found whole, fewer candidate marks, best pair every frame, nearest board to
    last time. Always check a change against the recordings (see Checking your work).
  - Answers on screen: after a reading (or while watching) the window shrinks to a few buttons
    (`BubbleService.setWindowCompact`: `COMPACT_TOP_LEFT`, or `COMPACT_BOTTOM_LEFT` for the addition puzzle so
    the chat stays clear) and the puzzle's map is drawn alone at the bottom of the screen in a separate
    untouchable overlay. Both areas are skipped when reading. Each page: a tip line, main buttons, a `?` for
    instructions, and tap-in controls behind "Solve manually". The list of puzzles shows a small picture of
    each board (`puzzleIcon`, drawn with the same helpers as the maps, part-way through the puzzle); the owner
    liked them as they are.
  - Per-pixel code must not use Kotlin function types like `(Int, Int) -> Boolean`: on Android every call
    boxes its arguments (a million objects per look, 500 ms instead of 85). `ToaReader.Skip` is a
    `fun interface` for that reason.
  - Test builds only (debuggable): pictures a reader couldn't make sense of are saved to the app's
    `cache/toa_debug` (newest 30, one per 2 s), and Watch logs its timings (`adb logcat -s ToaWatch`).
- **Every outside source must be credited** on the Legal screen (`MainActivity.legalScreen`) and in the
  README's Credits. Check the licence first: BSD/MIT/GPL are fine with notices; no licence means look
  things up only, don't copy in bulk.

## Code style

- Colours: `"#3E2C12".toColorInt()`; saved settings: `prefs.edit { putInt(...) }`; web addresses:
  `"...".toUri()` (androidx core-ktx). Android's check (lint) flags the older forms.
- No Kotlin function types (`(Int) -> Boolean`) in code run for every pixel: they box on Android. Use a
  `fun interface` (see `ToaReader.Skip`).
- Plain-language comments, matching the existing ones. Text shown on screen is written in the code (the app
  is English-only), not in `strings.xml`.

## Checking your work

- On the owner's PC, build with `gradlew.bat assembleDebug`, and run Android's checks with
  `gradlew.bat lintDebug` (report in `app/build/reports/lint-results-debug.sarif`). The only warnings left
  are 92 "SetTextI18n" (text written in the code), left on purpose; see Open items.
- ToA screen reading is tested on the PC with `app/src/test/.../ToaReaderTest.kt` (skipped unless pointed at
  pictures): `TOA_SHOTS=<folder>` reads every screenshot fresh; `TOA_REPLAY=<folder>` replays a recording
  (files `f_<time>.png`) through the matching reader and the same memory rules as the app, printing each
  frame, every change to the remembered boards (`MEMORY` lines) and the time per picture; `TOA_DEBUG_FRAME=n`
  lists the candidate boards in frame n. Run with `--rerun-tasks`: Gradle otherwise skips the test when only
  the folder changed. The pictures are never part of the project.
- Recording a live solve: while the owner plays, two `adb exec-out screencap -p` loops save ~2.5 pictures a
  second until told to stop (the owner says "start" and "stop"). Recordings and screenshots are kept outside
  the project in `C:\Users\Tanis\Documents\Androiddev\toa_recordings\` (never commit them; the `Keys` folder
  next to it is off limits): `matching_1\` (192 pictures, a full solo solve: the replay should end with all
  nine pairs matched), `matching_2\` (114 pictures, a later run that started with the inventory open) and
  `screenshots\` (every puzzle room, used with TOA_SHOTS). Any change to the matching reader must replay both
  recordings without getting worse. Put new recordings there too.
- Battery check: read the app's processor time from `/proc/<pid>/stat` (fields 14+15, in 1/100 s) at the start
  and end of a minute with the feature on and off, plus `dumpsys meminfo com.sinat.osrsbubbletool`.
  October 2026: matching Watch ≈ 45% of one core (the game itself ≈ 80%), about 20-30 MB extra memory.
- The owner is happy to be asked for screenshots ("screenshot") or to describe what they see; taking one with
  `adb exec-out screencap -p` while they play is the fastest way to understand a problem.
- Phone plugged in: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r
  app\build\outputs\apk\debug\app-debug.apk` installs the build (same as ▶; it closes the bubble, so the
  owner has to tap Start Bubble again), and `adb shell dumpsys window windows` shows where the app's
  windows really are on screen.
- `TeleportData.kt` and `WalkMap.kt` have no Android parts, so routes can be tested on a computer with
  `tools/teleport-finder/test/` (see its README section).
- When writing files to the owner's computer remotely: write, wait a few seconds, then read the file back
  and compare checksums. Committing immediately after copying has delivered stale files before.
- `/code-review ultra` (started by the owner, billed) reviews a branch's changes against `master`, not the
  whole app.

## Releasing

Each release is worked on in its own branch (named like `v1.0.6`), then merged into `master`.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit in Android Studio (check no `.jks` file is in the list, and new files are ticked).
3. Merge the release branch into `master` (Git → Branches → `master` → Checkout, then Git → Merge →
   the release branch) and push.
4. Build → Generate Signed App Bundle or APK → APK → release, with the key from the Keys folder.
5. GitHub → Releases → new release, tag `vX.Y.Z` (the update checker reads the tag), not a pre-release,
   attach `OSRSBubbleTool-X.Y.Z.apk`.

## Current state (1 October 2026)

- v1.0.6 released on 1 October 2026 (tag `v1.0.6`, versionCode 7, merged into `master`). Changes since v1.0.5:
  smarter Teleport Finder, bubble remembers its spot, tools set up only when first opened, Puzzle Box re-plan
  and stutter fixes, solvers stop when you leave the game, smaller backups, Notepad moves and resizes,
  windows stay clear of the status bar, DPS calculator item pictures and puzzle/quest pictures included,
  code tidy-up, themed app icon. `HANDOFF.md` described the start of this release and can be deleted.
- Release tags: `v1.0.0`-`v1.0.6` use the "v" form except `1.0.3`. Never rename a published release's tag on
  GitHub: it creates the new tag on the latest master (that happened to v1.0.5 and was fixed by
  force-pushing the tag back to its commit, from Android Studio's terminal, which has the GitHub login).
- Tested on the phone: Notepad moving/resizing, status bar handling (Notepad and Wiki).
- Not yet tested on the phone (released anyway): the included pictures (Import my gear, a puzzle scan, the Dragon Slayer II map), Teleport Finder
  searches (with and without WikiSync), bubble position after restarting, Puzzle Box re-plan after an
  unplanned move, solvers stopping when you leave the game (single-app sharing), the code tidy-up
  (colours, settings, Zulrah taps, Wing It, wiki links), the themed icon.

## In progress: v1.0.7 (branch `v1.0.7`, October 2026)

- New ToA Puzzle Helper (Beta), committed in stages ("New ToA Puzzle Helper", "ToA Puzzle Helper reads the
  screen", then the matching/battery work). Credits for the LlemonDuck plugin are on the Legal screen and in
  the README. `versionCode`/`versionName` not bumped yet.
- Tested live on the phone: light reading, addition number, sequence watching, matching Watch (many runs),
  the map at the bottom with the shrunk window, the redrawn symbols.
- 2 October 2026 (not committed yet when written): matching Watch made quicker (quick looks after an unsettled
  symbol, pairs green after 0.8 s); sequence Watch's 20 s countdown next to Stop and the "keep the camera still"
  tip. Replays of both recordings unchanged apart from pairs marked ~0.7 s sooner. Seen in one live raid (the
  demo recording): matching finished with all nine pairs and the sequence watch numbered all five flashes.
- Not yet tested live: the quick looks' effect on missed symbols (the owner's complaint: waiting on a tile or
  going back to it), the light/addition/sequence answers-at-the-bottom layout in a real raid, the addition
  number with digits other than 3 and 0, group raids (no starting pairs; line/crook/hand/bird samples come
  from the addition room only), the release build's speed.
- Owner's preferences for these tools: compact windows (few words, one row of buttons, `?` for instructions),
  maps kept straight (snapped to the nearest direction), green for "step here", symbols drawn like the game's
  (the owner checks them against the game and has corrected the knives, foot and hand).

## Open items and ideas

- ToA ideas: reading the obelisk puzzle; tracing the remaining symbol drawings from the owner's close-up
  addition-room screenshot; a matching symbol is sometimes missed when no clear view of its board is had.

- "SetTextI18n" warnings: ask the owner whether to switch that check off (recommended for an English-only
  app) or move the text into `strings.xml`.
- Licence: the app has no LICENSE file yet. GPL-3.0 is the likely choice (the DPS item list is GPL-3.0,
  and it's needed for F-Droid).
- F-Droid: needs the licence, an update-check switch for F-Droid builds, fastlane store listing files
  and screenshots. The included game pictures, `empty_slots.png` and the wiki text would likely get the
  "Non-Free Assets" label.
- RuneScape map project data (github.com/mejrs/data_osrs) has no licence: ask the author before using it
  in bulk. It was recorded before Varlamore, so it has nothing there.
- Teleport Finder: Mount Karuulm's inner levels, Giant Mole, Corporeal Beast, Wilderness GWD, brine rats,
  Isle of Souls and Waterfall dungeons, Evil Chicken's Lair and Bryophyta are still estimates. The
  calcified moth's landing spot and the Neypotzli entrance are estimated (ask the owner what's nearby).
- Planned: Expedition board (semi-idle game for the Game Room; decide check-in length, ending, art,
  notifications), AFK timer (tap-based logout countdown first, then optional screen watchers), world map
  tool (RuneLite world map plugin data, BSD; map pictures from the wiki or drawn from game files like
  dennisdev/rs-map-viewer, BSD; minimap matching for "you are here").
