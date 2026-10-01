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
- **Every outside source must be credited** on the Legal screen (`MainActivity.legalScreen`) and in the
  README's Credits. Check the licence first: BSD/MIT/GPL are fine with notices; no licence means look
  things up only, don't copy in bulk.

## Code style

- Colours: `"#3E2C12".toColorInt()`; saved settings: `prefs.edit { putInt(...) }`; web addresses:
  `"...".toUri()` (androidx core-ktx). Android's check (lint) flags the older forms.
- Plain-language comments, matching the existing ones. Text shown on screen is written in the code (the app
  is English-only), not in `strings.xml`.

## Checking your work

- On the owner's PC, build with `gradlew.bat assembleDebug`, and run Android's checks with
  `gradlew.bat lintDebug` (report in `app/build/reports/lint-results-debug.sarif`). The only warnings left
  are 113 "SetTextI18n" (text written in the code), left on purpose; see Open items.
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

- Working on v1.0.6 in branch `v1.0.6` (versionCode 7), not pushed or released yet; `master` is still
  v1.0.5. Changes since v1.0.5: game pictures updated to the DPS calculator's (still included in the app), smarter Teleport Finder, bubble
  remembers its spot, tools set up only when first opened, Puzzle Box re-plan and stutter fixes, solvers
  stop when you leave the game, smaller backups, Notepad moves and resizes, windows stay clear of the status
  bar, code tidy-up, themed app icon. `HANDOFF.md` (from an earlier session) describes the first part and
  can be deleted once v1.0.6 is released.
- Tested on the phone: Notepad moving/resizing, status bar handling (Notepad and Wiki).
- Not yet tested on the phone: the included pictures (Import my gear, a puzzle scan, the Dragon Slayer II map), Teleport Finder
  searches (with and without WikiSync), bubble position after restarting, Puzzle Box re-plan after an
  unplanned move, solvers stopping when you leave the game (single-app sharing), the code tidy-up
  (colours, settings, Zulrah taps, Wing It, wiki links), the themed icon.

## Open items and ideas

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
