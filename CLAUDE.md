# OSRS Bubble Tool: notes for working on this project

A free, ad-free Android app for Old School RuneScape mobile: a floating bubble over the game that opens
small tool windows. Kotlin, Android Views (no Compose), one app module. Package `com.sinat.osrsbubbletool`.
GitHub: sinat50/OSRSBubbleTool. Licence: GPL-3.0-or-later (`LICENSE`; the included wiki content, BSD parts and
game pictures keep their own terms). The README is the user-facing description of every tool.

## At the start of every new chat

Run `python tools/update-check/check_updates.py` (from the project folder) and tell the owner in a few plain
lines what changed upstream (wiki pages, pictures, source projects, new quests, game updates) and what it
would mean for the app. Only report: update nothing unless the owner asks. It reuses its report for 6 hours.
When a change has been dealt with, or the owner says it doesn't matter, mark it with `--checked NAME`. The
`tools/update-check/` folder is the owner's own tool (in `.gitignore`); how to use it is at the top of the script.

## Working with the owner

- The owner has no coding experience. Explain in plain language, give exact click-by-click steps for
  Android Studio (Windows), and write code comments in plain language too.
- **Always ask the owner before sending any input to their phone.** Taps, swipes, key presses or other input
  (`adb shell input`, `monkey`, and so on) and opening the app on their screen (`am start`) each need their OK,
  every time: the app is made for Old School RuneScape, and automated input while the game runs could get the
  owner's account banned. Installing a build needs no OK. A screenshot is fine when they ask for one or agree.
- They test by pressing ▶ in Android Studio on a Samsung phone (SM-S942W), usually in landscape.
- Priorities, in order: battery, storage, accuracy. Nothing runs in the background unless it must.
- The signing key lives in `C:\Users\Tanis\Documents\Androiddev\Keys\`: it stays there, out of the project and
  out of Git. That folder is off limits.
- Update the README when features change and mention it at commit time (no need to show it every time).
- Release notes and commit messages are written for players, not developers. The owner commits in Android
  Studio themselves: finish a change with a suggested commit message. Start every commit message with the version
  being worked on, from the release branch's name (`git branch --show-current`, e.g. `v1.0.8 Zen Garden: ...`), not
  `versionName`, which still shows the last release until it's bumped at release time.
- When committing, remind them that brand-new files sit under "Unversioned Files" in the Commit window and
  are NOT ticked by default (a commit once left out `AssetDownloader.kt` and `GearIcons.kt`).
- Android Studio's commit check ("Analyze code") lists warnings and grammar flags; they don't block a commit.

## Where things are

Code: `app/src/main/java/com/sinat/osrsbubbletool/` (one file per tool, roughly):

| File | What it is |
| --- | --- |
| `BubbleService.kt`, `ScreenBars.kt` | The bubble, its long-press menu, tool windows (move/resize), the `Tool` list (its order is the menu order) and `makeParts()` (the one place each tool is set up). `ScreenBars` = the maths for keeping the bubble and windows clear of the status and navigation bars (see below) |
| `MainActivity.kt` | Main screen, Permissions, Legal (all credits and licence notices) |
| `CaptureManager.kt`, `ScreenCapturer.kt`, `CapturePermissionActivity.kt` | Screen capture: asked once per bubble session; Android 14+ can share just the game |
| `PuzzleBoxTool.kt`, `PuzzleFinder.kt`, `GridFinder.kt`, `TileMatcher.kt`, `PuzzleSolver.kt`, `MoveGuideOverlay.kt`, `PuzzleReferences.kt` | Puzzle Box Solver |
| `LightBoxTool.kt`, `LightBoxReader.kt`, `LightBoxGuideOverlay.kt` | Light Box Solver |
| `GuideWindow.kt`, `ScreenPictures.kt` | Shared: the see-through guide windows both solvers draw in; and working with screen pictures (`forEachPatch`, `cutArea`, `savePictureToPhone`) |
| `ToaPuzzleTool.kt`, `ToaPuzzles.kt`, `ToaReader.kt` | ToA Puzzle Helper (Beta): the five Path of Scabaras puzzles. **Read `docs/toa-helper.md` before changing these.** |
| `DpsTool.kt`, `GearRecognizer.kt`, `GearIcons.kt`, `PanelFinder.kt` | DPS Calculator (wiki web page) and Import my gear |
| `InventorySetupsTool.kt`, `RegionOverlay.kt`, `PuzzleAreaOverlay.kt` | Inventory Setups, and the "set area by hand" frames |
| `TeleportFinderTool.kt`, `TeleportData.kt`, `WalkMap.kt` | Teleport Finder (Beta): wiki lookup, teleport list, walking-route search |
| `QuestHelperTool.kt`, `QuestData.kt`, `QuestGuides.kt`, `QuestBook.kt`, `QuestPuzzles.kt`, `QuestSolvers.kt`, `QuestSolverViews.kt`, `PuzzleDiagramView.kt` | Quest Helper (Beta), from the Quest Helper RuneLite plugin (`PuzzleDiagramView` draws the puzzle answer maps) |
| `WikiSync.kt`, `WikiSyncTool.kt` | WikiSync account data, and the ✓/✗ WikiSync tag other tools show |
| `HunterRumourTool.kt`, `HunterRumours.kt` | Hunter Rumours |
| `ZulrahTool.kt` | Zulrah Helper |
| `FarmingTool.kt`, `FarmingTimers.kt`, `FarmingTimerReceiver.kt` | Timers (one exact alarm per timer, no background work) |
| `GameRoomTool.kt`, `Game2048View.kt`, `FlyerView.kt`, `GameData.kt`, `SandGardenView.kt`, `SandRenderer.kt`, `SandSound.kt`, `SandGardenLayout.kt`, `ZenGardenFile.kt`, `ZenThings.kt`, `ZenPatterns.kt` | Game Room: 2048, Wing It and the Zen Garden. **Read `docs/zen-garden.md` before changing the `Sand*` or `ZenGarden*` files.** |
| `CalculatorTool.kt`, `NotesTool.kt`, `UpdateChecker.kt`, `WikiLinkActivity.kt` | Calculator, Notepad, GitHub update check (at most once an hour), wiki links opening in the bubble |
| `SettingsTool.kt`, `Palette.kt` | Settings (`AppSettings`; the page is shared by the bubble's Settings window and the main screen; the bubble hears changes through `BubbleService.settingsChanged`) and the app's colours, light and dark |

Resources: `app/src/main/res/`: `drawable/ic_bubble.xml` (the bubble), `ic_launcher_bubble*.xml` and
`ic_launcher_monochrome.xml` (app icon, plus its one-colour version for Android 13+ themed icons),
`xml/backup_rules.xml` and `xml/data_extraction_rules.xml` (what Android backs up). There are no layout files:
every screen is built in code.

Data: `app/src/main/assets/` (how to run the scripts: `tools/README.md`)

| File | Built by | From |
| --- | --- | --- |
| `teleports.tsv`, `walks.txt` | `tools/teleport-finder/build_teleports_and_walks.py` | Shortest Path plugin, plus hand-checked fixes and additions in the script |
| `collision.bin` | `tools/teleport-finder/build_collision.py` | Shortest Path's `collision-map.zip` |
| `dungeons.txt` | `tools/teleport-finder/build_dungeons.py` | RuneLite world map `DungeonLocation.java` |
| `entrances.tsv` | (older, kept only as a fallback for straight-line estimates) | |
| `dps/equipment.json` | `tools/dps/build_equipment_json.py` | OSRS Wiki DPS calculator repository |
| `dps/empty_slots.png` | cut from a game screenshot | what empty equipment slots look like |
| `dps/icons/` (4,991 item pictures, 2.6 MB), `puzzles/`, `quest_images/` | `tools/dps/fetch_pictures.py` | DPS calculator repository at `GearIcons.COMMIT`; OSRS Wiki |

On the owner's computer only (in `.gitignore`): `.scratch/` (plans and to-dos, see below), `trailer/` (the app's
trailer and the ToA demo video; how to update them is in `trailer/README.md`), `tools/update-check/`.

`build/`, `.gradle/`, `.idea/` and `.kotlin/` are generated and huge: leave them out of reads and searches.

## How things work (the parts that aren't obvious)

- **Tools** are only created the first time they're opened. Each one gives `makeParts()` a `ToolParts`:
  its window contents, its ◀ action, what to do when its window closes, the phone turns or the bubble closes,
  and `isBusy` (still working with its window closed, like a puzzle guide). A new tool needs its line in the
  `Tool` list and its part in `makeParts()` (the build fails until it has both).
- **Windows**: every tool window can be moved (drag the top bar, `moveWindowTo`) and resized (bottom-right
  corner; double-tap it to reset), except the Light Box and Puzzle Box Solvers, which are small and sit top right.
  Windows share one position; each tool remembers its own size per orientation. The Notepad starts at the top,
  sized to fit above the keyboard. A tool can shrink its window to a few buttons in a corner
  (`setWindowCompact`); it stays shrunk when the phone turns.
- **Status bar and navigation bar**: Android draws them over everything, so a window's bar under them can't be
  grabbed. Overlay windows aren't told when they show or hide (window insets don't work for them), so
  `BubbleService.addBarProbes()` adds two invisible strips that Android keeps clear of the bars, read together by
  `checkBars` (after layout and again after the phone turns): a 1-pixel-wide one top to bottom (its top = status
  bar, its bottom = a navigation bar along the bottom) and a 1-pixel-tall one across the top (its ends = a
  navigation bar down one side, as on the emulator in landscape). While the bars show, windows and the bubble stay
  clear of them (too big windows get smaller for a while), and go back to where you left them (`shownWantX/Y/W/H`)
  when the bars hide, as they do in the game. All the arithmetic is in `ScreenBars` (reading the strips, ignoring
  readings taken mid-turn, placing, dragging, resizing, the bubble), tested on the PC (`ScreenBarsTest`); the
  service holds the current `Bars` and moves the windows. Checked on the emulator (October 2026) sideways and
  upright: bubble and windows stop exactly at the bars.
- **Game pictures come with the app** (owner's decision), in `assets/dps/icons/`, `assets/puzzles/` and
  `assets/quest_images/`. They're Jagex's artwork, included under Jagex's Fan Content Policy; if Jagex objects,
  the fallback is downloading them instead (tried in v1.0.6 test builds: slow, from GitHub or jsDelivr).
  `MainActivity.removeOldDownloads()` deletes those old downloaded copies once. The DPS tool loads all item
  pictures into memory when its window first opens (the owner chose this, so Import my gear is quick).
- **Dark mode**: every screen's colours come from `Palette` (tools import its `PARCHMENT`, `DARK_BROWN`...
  directly), read when a view is built. Switching it makes `BubbleService.rebuildToolWindows()` destroy and
  rebuild all tools (the open window reopens; the Wiki keeps its page); the main screen redraws in its own
  colours. When the phone's own dark mode changes under "Match phone", the rebuild waits until no tool window is
  open and no tool `isBusy` (it would stop a ToA Watch or a puzzle guide mid-use). Pictures drawn like the game
  (ToA maps, puzzle diagrams, Zen Garden, game icons) keep fixed colours (`INK` in `ToaPuzzleTool`). Web pages get
  a dark theme context, so sites with a `prefers-color-scheme` dark look use it; the OSRS Wiki ignores that and
  uses a `theme=dark`/`light` cookie, set by `setWikiTheme` (which remembers if it last set it dark, so it's set
  back to light even if dark mode was turned off while the bubble was closed). Text boxes set their own text and
  hint colours.
- **Tool text size**: tools are built with `BubbleService.toolContext`, a wrapper whose resources have Android's
  font scale multiplied by the setting (so every `textSize` in sp grows; dp sizes and drawn pictures don't).
  Changing it rebuilds the tools like dark mode, 0.7 s after the last − or + tap (`TEXT_SIZE_WAIT_MS`). The web
  text size multiplies each page's own `textZoom`.
- **Backups**: only shared preferences and `files/setups/` (Inventory Setups pictures) are backed up or moved to
  a new phone (Android stops backing up an app entirely past 25 MB).
- **Screen capture** pauses itself 3 seconds after the last use and is asked for once per bubble session. Each
  picture (`grab`) is one bitmap; with single-app sharing it's placed where the game sits on screen. The Puzzle
  Box and Light Box Solvers check the screen every 80 ms while guiding (`capture.sample`, a few pixels, no
  picture made); if only the game is shared and you leave it (`capture.gameHidden`), they stop and take their
  outlines off the screen. A re-plan needs a fresh picture, so it must `grab()` before anything `sample()`s that
  tick. Heavy work (finding the puzzle, reading tiles, recognising gear) runs on background threads; all
  `grab()` calls are on the main thread.
- **Battery**: web pages in hidden windows are paused; Wing It only animates while you're playing; the Zen
  Garden's rules are in `docs/zen-garden.md`.
- **Teleport Finder routes**: `WalkMap` spreads out from the target (reverse Dijkstra over the collision map
  plus `walks.txt`). Walks can carry requirements ("70 Agility; Regicide"); with WikiSync set up, ones you can't
  use are skipped. Closed-off areas fall back to dungeon-entrance estimates ("guess" results). Joins into
  closed-off areas are precomputed (`find_closed_off_areas.py`).
- **Wiki links** from the game open in the bubble's Wiki window via `WikiLinkActivity`, only if the owner turns
  on "Open by default" for the wiki (it can't be auto-verified: not our website).
- **Every outside source must be credited** on the Legal screen (`MainActivity.legalScreen`) and in the README's
  Credits. Check the licence first: BSD/MIT/GPL are fine with notices; with no licence, look things up only
  (copying in bulk needs the author's permission).

## Code style

- Colours: `"#3E2C12".toColorInt()`; saved settings: `prefs.edit { putInt(...) }`; web addresses:
  `"...".toUri()` (androidx core-ktx). Android's check (lint) flags the older forms. The exception: code meant to
  run on the PC (no Android parts, like `ZenThings`) uses the plain-arithmetic `colour("#...")`, `rgb`, `blend` and
  `tone` from `ZenThings.kt`, which give exactly what Android's would.
- Code run for every pixel uses a `fun interface` (see `ToaReader.Skip`), never a Kotlin function type like
  `(Int, Int) -> Boolean`: on Android every call boxes its arguments (a million objects per look, 500 ms instead
  of 85).
- Plain-language comments, matching the existing ones. Text shown on screen is written in the code (the app is
  English-only), not in `strings.xml`.

## Checking your work

- Build with `./gradlew.bat assembleDebug`, and run Android's checks with `./gradlew.bat lintDebug` (report in
  `app/build/reports/lint-results-debug.sarif`). Expected warnings: 95 "SetTextI18n" (text written in the code,
  see `.scratch/lint-text-warnings/`) and 2 "IconXmlAndPng" (the app icon).
- PC tests: `./gradlew.bat testDebugUnitTest`, in `app/src/test/`: the Puzzle Box and Light Box solvers, the ToA
  answers, farming growth times, quest requirement checks, the Zen Garden's save file, placed things and auto rake patterns, and keeping windows clear of the bars (expected answers worked out by hand or from the
  game, never copied from the code); `ToaReaderTest` needs recordings (see `docs/toa-helper.md`). The real
  `org.json` is added for tests only (Android's own isn't on the PC). Code with Android parts gets a pure version
  to test (like `FarmingTimers.finishTime` taking the tick offset as a number). `TeleportData.kt` and `WalkMap.kt` have no Android parts, so routes are
  tested with `tools/teleport-finder/test/` (see its README section).
- Installing: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`
  (same as ▶; it closes the bubble, so the owner taps Start Bubble again). If the phone has a signed release
  build, the install is refused: uninstalling erases the app's data, so it needs the owner's OK.
  `adb shell dumpsys window windows` shows where the app's windows really are on screen.
- Screenshots: `adb exec-out screencap -p` while the owner plays is the fastest way to understand a problem; ask
  first ("screenshot"), or ask them to describe what they see.
- Screen recordings: `adb shell screenrecord --time-limit 0 /sdcard/Movies/<name>.mp4` with the game already
  sideways; stop it with `kill -INT` on the `screenrecord` process (`pkill` hits the wrong one) so the file is
  finished properly, then copy it off and delete it from the phone. In Git Bash, set `MSYS_NO_PATHCONV=1` or phone
  paths get mangled.
- Battery: read the app's processor time from `/proc/<pid>/stat` (fields 14+15, in 1/100 s) at the start and end
  of a minute with the feature on and off, plus `dumpsys meminfo com.sinat.osrsbubbletool`.
- Emulator: "Phone Emulator" (`emulator -avd Phone_Emulator`, Android 16, 2340×1080 like the owner's phone;
  pick it in Android Studio's device list next to ▶). Good for layouts, buttons and crashes; not for speed or
  battery numbers. Keep the game off it (Jagex can flag emulator logins). The "ask before input" rule is about
  the owner's phone.
- When writing files to the owner's computer remotely: write, wait a few seconds, then read the file back and
  compare checksums. Committing immediately after copying has delivered stale files before.
- `/code-review ultra` (started by the owner, billed) reviews a branch's changes against `master`, not the whole
  app. A whole-app review is `/code-review high <files>`, a few tools at a time.

## Releasing

Each release is worked on in its own branch (named like `v1.0.9`), then merged into `master`.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit in Android Studio (check no `.jks` file is in the list, and new files are ticked).
3. Merge the release branch into `master` (Git → Branches → `master` → Checkout, then Git → Merge →
   the release branch) and push.
4. Build → Generate Signed App Bundle or APK → APK → release, with the key from the Keys folder.
5. GitHub → Releases → new release, tag `vX.Y.Z` (the update checker reads the tag), not a pre-release,
   attach `OSRSBubbleTool-X.Y.Z.apk`.

Tags use the "v" form (`1.0.3` is the one exception). A published release's tag stays as it is: renaming it on
GitHub creates the new tag on the latest master (that happened to v1.0.5 and was fixed by force-pushing the tag
back to its commit from Android Studio's terminal, which has the GitHub login). Latest release: v1.0.8 (5 October
2026, versionCode 9).

## Plans, to-dos and things to test

They're files in `.scratch/<topic>/`, listed with their status in `.scratch/README.md` (add new ones there).
How they're laid out: `docs/agents/issue-tracker.md`; status words (needs-triage, needs-info, ready-for-agent,
ready-for-human, wontfix, done): `docs/agents/triage-labels.md`; the word list and decision records
(`CONTEXT.md`, `docs/adr/`, made only when needed): `docs/agents/domain.md`.
