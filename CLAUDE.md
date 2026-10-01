# OSRS Bubble Tool: notes for working on this project

A free, ad-free Android app for Old School RuneScape mobile: a floating bubble over the game that opens
small tool windows. Kotlin, Android Views (no Compose), one app module. Package `com.sinat.osrsbubbletool`.
GitHub: sinat50/OSRSBubbleTool. The README is the user-facing description of every tool.

## Working with the owner

- The owner has no coding experience. Explain in plain language, give exact click-by-click steps for
  Android Studio (Windows), and write code comments in plain language too.
- They test by pressing ▶ in Android Studio on a Samsung phone, usually in landscape.
- Priorities, in order: battery, storage, accuracy. Nothing runs in the background unless it must.
- The signing key lives in `C:\Users\Tanis\Documents\Androiddev\Keys\` and must never be uploaded, moved
  into the project or committed. Don't ask for access to that folder.
- Don't send the README every time; update it when features change and mention it at commit time.
- Release notes and commit messages are written for players, not developers.

## Where things are

Code: `app/src/main/java/com/sinat/osrsbubbletool/` (one file per tool, roughly):

| File | What it is |
| --- | --- |
| `BubbleService.kt` | The bubble, its long-press menu, tool windows (move/resize), the `Tool` list (its order is the menu order), and `makeParts()`, the one place each tool is set up (tools are only created when first opened) |
| `MainActivity.kt` | Main screen, Permissions, Legal (all credits and licence notices), first-launch picture download pop-up |
| `CaptureManager.kt`, `ScreenCapturer.kt`, `CapturePermissionActivity.kt` | Screen capture: asked once per bubble session; Android 14+ can share just the game |
| `PuzzleBoxTool.kt`, `PuzzleFinder.kt`, `GridFinder.kt`, `TileMatcher.kt`, `PuzzleSolver.kt`, `MoveGuideOverlay.kt`, `PuzzleReferences.kt` | Puzzle Box Solver |
| `LightBoxTool.kt`, `LightBoxReader.kt`, `LightBoxGuideOverlay.kt` | Light Box Solver |
| `DpsTool.kt`, `GearRecognizer.kt`, `GearIcons.kt`, `PanelFinder.kt` | DPS Calculator (wiki web page) and Import my gear |
| `AssetDownloader.kt` | One-time download of the game pictures the tools need (see below) |
| `InventorySetupsTool.kt`, `RegionOverlay.kt`, `PuzzleAreaOverlay.kt` | Inventory Setups, and the "set area by hand" frames |
| `TeleportFinderTool.kt`, `TeleportData.kt`, `WalkMap.kt` | Teleport Finder (Beta): wiki lookup, teleport list, walking-route search |
| `QuestHelperTool.kt`, `QuestData.kt`, `QuestGuides.kt`, `QuestBook.kt`, `QuestPuzzles.kt`, `QuestSolvers.kt`, `QuestSolverViews.kt`, `PuzzleDiagramView.kt` | Quest Helper (Beta), from the Quest Helper RuneLite plugin (`PuzzleDiagramView` draws the puzzle answer maps) |
| `WikiSync.kt`, `WikiSyncTool.kt` | WikiSync account data, and the ✓/✗ WikiSync tag other tools show |
| `HunterRumourTool.kt`, `HunterRumours.kt` | Hunter Rumours |
| `ZulrahTool.kt` | Zulrah Helper |
| `FarmingTool.kt`, `FarmingTimers.kt`, `FarmingTimerReceiver.kt` | Timers (one exact alarm per timer, no background work) |
| `GameRoomTool.kt`, `Game2048View.kt`, `FlyerView.kt`, `GameData.kt` | Game Room: 2048 and Wing It |
| `CalculatorTool.kt`, `NotesTool.kt`, `UpdateChecker.kt`, `WikiLinkActivity.kt` | Calculator, Notepad, GitHub update check, wiki links opening in the bubble |

Data: `app/src/main/assets/`

| File | Built by | From |
| --- | --- | --- |
| `teleports.tsv`, `walks.txt` | `tools/teleport-finder/build_teleports_and_walks.py` | Shortest Path plugin, plus hand-checked fixes and additions in the script |
| `collision.bin` | `tools/teleport-finder/build_collision.py` | Shortest Path's `collision-map.zip` |
| `dungeons.txt` | `tools/teleport-finder/build_dungeons.py` | RuneLite world map `DungeonLocation.java` |
| `entrances.tsv` | (older, kept only as a fallback for straight-line estimates) | |
| `dps/equipment.json` | `tools/dps/build_equipment_json.py` | OSRS Wiki DPS calculator repository |
| `dps/empty_slots.png` | cut from a game screenshot | what empty equipment slots look like |

See `tools/README.md` for how to run the scripts.

Don't read or search `build/`, `.gradle/`, `.idea/` or `.kotlin/`: they're generated and huge.

## How things work (the parts that aren't obvious)

- **Game pictures aren't bundled** (they're Jagex's artwork; keeps the app small and F-Droid-friendly).
  `AssetDownloader` gets them once: 101 pictures from the OSRS Wiki, then the DPS calculator repository as
  one ZIP (about 170 MB, pinned to `GearIcons.COMMIT`), unpacks the item pictures, and deletes the ZIP.
  Kept in `filesDir/dps_icons/` and `filesDir/puzzles/`.
- **Teleport Finder routes**: `WalkMap` spreads out from the target (reverse Dijkstra over the collision
  map plus `walks.txt`). Walks can carry requirements ("70 Agility; Regicide"); with WikiSync set up, ones
  you can't use are skipped. Closed-off areas fall back to dungeon-entrance estimates ("guess" results).
  Joins into closed-off areas are precomputed (`find_closed_off_areas.py`).
- **Screen capture** pauses itself after a few seconds without use and is asked for once per bubble
  session. With single-app sharing, pictures are placed where the game sits on screen.
- **Every outside source must be credited** on the Legal screen (`MainActivity.legalScreen`) and in the
  README's Credits. Check the licence first: BSD/MIT/GPL are fine with notices; no licence means look
  things up only, don't copy in bulk.

## Checking your work

- `TeleportData.kt` and `WalkMap.kt` have no Android parts, so routes can be tested on a computer with
  `tools/teleport-finder/test/` (see its README section).
- When writing files to the owner's computer remotely: write, wait a few seconds, then read the file back
  and compare checksums. Committing immediately after copying has delivered stale files before.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit and push in Android Studio (check no `.jks` file is in the list).
3. Build → Generate Signed App Bundle or APK → APK → release, with the key from the Keys folder.
4. GitHub → Releases → new release, tag `vX.Y.Z` (the update checker reads the tag), not a pre-release,
   attach `OSRSBubbleTool-X.Y.Z.apk`.

## Open items and ideas

- Licence: the app has no LICENSE file yet. GPL-3.0 is the likely choice (the DPS item list is GPL-3.0,
  and it's needed for F-Droid).
- F-Droid: needs the licence, an update-check switch for F-Droid builds, fastlane store listing files
  and screenshots. Reviewers may ask about `empty_slots.png` and the wiki text (Non-Free Assets).
- RuneScape map project data (github.com/mejrs/data_osrs) has no licence: ask the author before using it
  in bulk. It was recorded before Varlamore, so it has nothing there.
- Teleport Finder: Mount Karuulm's inner levels, Giant Mole, Corporeal Beast, Wilderness GWD, brine rats,
  Isle of Souls and Waterfall dungeons, Evil Chicken's Lair and Bryophyta are still estimates. The
  calcified moth's landing spot and the Neypotzli entrance are estimated (ask the owner what's nearby).
- Planned: Expedition board (semi-idle game for the Game Room; decide check-in length, ending, art,
  notifications), AFK timer (tap-based logout countdown first, then optional screen watchers), world map
  tool (RuneLite world map plugin data, BSD; map pictures from the wiki or drawn from game files like
  dennisdev/rs-map-viewer, BSD; minimap matching for "you are here").
