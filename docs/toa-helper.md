# ToA Puzzle Helper: how the screen reading works

Read this before changing `ToaReader.kt`, `ToaPuzzles.kt`, `ToaPuzzleTool.kt`, `ToaMaps.kt` or `HetReader.kt`. Everything here was built from
the owner's phone screenshots and live raids (October 2026), never guessed.

## The files

- `ToaPuzzles` = the rules and answers (from the LlemonDuck Tombs of Amascut plugin, BSD).
- `ToaReader` = screen reading, plus `MatchMemory`, what matching Watch remembers between pictures. It has no
  Android parts, so it runs on the PC; the replay test uses the same `MatchMemory` as the app.
- `ToaPuzzleTool` = the pages, the Watch loops and the map drawn at the bottom of the screen.
- `ToaMaps` = how the maps look: the game's colours (`ToaColours`), the tiles, plates and symbols (`ToaPainter`) and
  the Path of Het map (`HetMapView`, shown what to draw by `HetMapData`).
- `HetReader` = the Path of Het reader (rooms, cracked walls, fixed mirrors and their facing, Het's seal) and
  `HetReader.Memory`, what its Watch remembers between looks; the camera it fits to each picture is a
  `HetReader.Camera`. No Android parts: `HetReaderTest` (screenshots, and
  the memory rules) and `HetReplayTest` (a recorded round, through exactly what the phone runs) use it on the PC.

## Each puzzle

- **Light**: one picture finds the ring of 8 plates (unlit = solid yellow square on purple, lit = pale glow with a
  beam; facing east the squares show as diamonds). The plates are numbered from whichever corner needs the least
  turning, so the map is never turned.
- **Addition**: reads the red number from the chat line "The number N has been hastily chipped into the stone."
  (digit templates typed by the owner into the chat). Only the left 60% of the screen is searched, so a red HP orb
  can't fool it. The symbol layout is the same every raid.
- **Sequence**: one picture finds the 9 tiles, then `capture.sample` checks just those tiles ~12 times a second
  for the 5 flashes. The tiles are found only once, when Watch is tapped, so the camera must stay still until
  they flash (the tip under Watch says so). It waits 20 s for the first flash (`WATCH_FIRST_FLASH_MS`; the owner
  found 2 minutes excessive), with a countdown next to Stop (`sequenceSecondsLeft`), then 5 s for each next one.
- **Obelisk**: tap-in only (the obelisks are small and hard to see from the safe spot).
- **Path of Het (mirrors)**: the page is just Watch and its instructions (owner's choice, October 2026: the room
  pictures, the nine ways to pick from, Read the room, Show and Turn were taken off; the small window has just Rescan
  (Watch afresh) and Done, with a note under them only once Watch has stopped, saying why (October 2026: the
  Stop/Watch, ◀ ▶ through the nine ways and the "Watching room 3: 4 fixed mirrors found" line went, so the map can sit
  at the bottom left beside it, clear of the game's menu when you tap a mirror); the map shows no rings for unseen squares and no colour key). The plugin's nine answers (`ToaPuzzles.HET_ANSWERS`) are kept as
  just their mirrors; `hetBeam` traces the beam from the caster (square 6,10, going west) to the shielded statue
  (squares 12-14, 9-11), and gives back exactly the plugin's lists of squares (checked against all nine;
  `ToaPuzzlesTest` checks one in full and every length). Rooms seen in raids are in `HET_ROOMS`: each one's smooth
  and cracked walls and the way that fits it (`hetMining` counts the cracked squares the beam crosses, -1 if it hits
  a smooth one; the test checks each room's way). (The player used to tap the room or one of
  the nine ways; now Watch reads it.) Only 3 mirrors can be moved (the plugin's rule), so a way with 4 corners needs
  a fixed mirror standing on one, facing the right way: every room's own way has 3 (room 3 used way 4 with a fixed
  mirror until its walls were measured from straight above, which opened way 5; the rooms no longer list fixed
  mirrors of their own, as Watch reads them each raid). Checked: our rooms' ways are what the plugin's
  rules would pick. The room is entered from the east, so you face west walking in (compass in the owner's
  screenshots): the maps start with west at the top (`het_top`, default 3; the Turn button is gone).
  The floor is drawn square for square as in the game (`isHetFloor`, from the Shortest Path collision map, region
  57,82, square 0,0 = game tile 3670,5270): a stepped circle, 21 across the middle, 5 at the ends; used by the
  reader. The map's outline (`HET_OUTLINE`) is traced from the OSRS Wiki's map of the room (map -1, region 57,82, tile
  zoom 3 = 8 px a square; credited on the Legal screen): straight runs and one-square diagonals, the alcoves 3 squares
  north and south with short walls across their mouths, gaps at the doors. The drawing reaches 3 squares past the
  21x21 squares (`HET_DRAW_MARGIN`). Smooth walls are
  light grey, cracked ones dark (owner's choice), fixed mirrors grey with a dark border.
- **Fixed mirrors, read each raid** (October 2026): a different few stand about each raid. Fixed ones sit on a solid
  gold cone; the three movable ones stand in A-frames (two thin legs) and always start on squares 5,1, 5,19 and 19,15.
  The plugin's rule (`HetTileState.conversionScoreTo`): a fixed mirror on a way's straight part, or on its corner
  facing the wrong way, rules the way out; on a corner facing the right way it saves carrying one.
  `hetBestWay(..., seen, facing)` follows that for mirrors whose facing was read; a corner mirror whose facing
  couldn't be read is only used if no other way works (drawn grey, facing the way the corner needs).
  Other seen ones are drawn facing as read, or as plain grey squares.
  How they're found (`HetReader.refine`, `readFixedMirrors`): the main search leaves the floor grid up to a square too
  far up the screen (walls' faces pull it), so a finer fit follows (16 points a square, a floor point hidden when a
  wall stands within 2 squares in front of it); then at each open square a box just above its plate that is mostly
  gold, with no gold beside it at that height (that leaves out the walls' gold trim), marks a mirror base; the
  squares round the A-frames' start are left out. Checked against a RuneLite-known raid
  (`pathofhet/rl_133127_fixed_top.png` straight down with the plugin's tiles, `adb_20261024_133635_known.png` on the
  phone): finds 5,6, 5,13, 19,7 exactly and picks the plugin's way (7); the one at 2,10 hides behind the caster.
  Hidden squares (`Reading.unseen`): an open square counts as seen when at least half its middle shows sand or gold.
  From the doorway the squares just west of the caster (5,10 and 4,10, where every way starts) are always hidden, and
  some behind walls; Watch's looks add up (`Memory`), so walking through fills them in (the north/south views from
  beside the statues see every square on their way). They're no longer drawn on the map.
- **Het Watch** (October 2026): a look once a second (`HET_INTERVAL_MS`) as you walk through. The first is a full
  read; the next follow the camera from the last look (`HetReader.followLook`: a rough search round it on every other
  square, then a close one; about 65 ms on the PC against 150 for a full read, which uses every core), falling back
  to a full read when it loses the room. `HetReader.Memory` adds the looks up: a fixed mirror needs two looks to
  agree, a clear square two (three behind the caster, see below), and a settled square stays settled (so mirrors you carry or place don't count). The way
  is picked again whenever something settles. Stops after 10 minutes, when you leave the game (the map comes off the
  screen and the full window comes back, saying why), close the window or
  turn the phone; Done and ◀ stop it too, and Rescan starts it afresh (it keeps going through the round: see below). The app's window and the map are
  blanked out of each picture (`ReusablePixels.blank`). Tests: `HetReaderTest.followsTheCamera` (a camera nudged by
  a second's walk lands on the same room and mirrors) and `watchSettlesSquares`.
  The camera research is in `.scratch/toa-helper/het-camera-research.md`.
- **Het Watch through a whole round** (rebuilt 25 October 2026 from the owner's recording of one,
  `pathofhet/watch_20261025c_cycle.mp4`): when the beam reaches the statue the walls and mirrors sink while you mine
  the obelisk; if it isn't destroyed a new layout rises, usually with the camera facing north or south. Each look is
  now `HetReader.watchLook` (shared with `HetReplayTest`, so the replay runs exactly what the phone does) and
  `Memory.use` says what it did (`Outcome`). Walls down (`followLook`): round the last camera the room is tried as it
  is and with no walls (`NO_WALLS`), with the caster's and target's slate plinths pinning the camera (`Search(statues)`);
  down when the bare room fits (`EMPTY_FIT`) clearly better (`EMPTY_MARGIN`), the walled room still fits its floor
  (`WALLED_FIT`), a square at the middle of the screen is the size it was at the first read (`focusZoom`, `DOWN_ZOOM`:
  searched freely, a bare room squeezed small fits the floor between walls) and at least `DOWN_WALL_POINTS` wall
  squares show, nearly all floor. `WALLS_DOWN_LOOKS` (4) such looks with no look at the room between (lost looks
  don't count) clear the layout; the next is read afresh once its walls stand (`wallsUp`). Walls down is only told
  round the last camera: an all-round search for it (tried in the code review, 26 October 2026) fit too many views
  with the walls up, even pinned by the statues and the zoom. So a new layout risen while the camera was lost (you
  mined facing north or south) is caught two other ways (both now mostly backups: Het's seal, below, tells when
  you mine): once the room hasn't fitted for 2 looks any room is tried, and another room seen 3 looks in a row at
  about the first read's zoom takes over; and after `GAP_LOOKS` lost looks the room is compared with
  what's settled (`Memory.compare`): `NEW_LAYOUT_LOOKS` (3) looks in a row with a wall settled smooth now cracked
  start afresh (a new layout of the same room; cracked walls mined away read smooth, and fixed mirrors are often
  missed from deep in the room, so neither counts). Cracked walls are voted on look by look (`Memory.cracked`): the
  first look comes as the walls rise and misread four walls in the recording. A wall (or a fixed mirror's facing)
  that later looks change counts as a change, so the map and the way are redone even when no square settles. Watch no longer stops when the way is
  seen (it looks every 2 s then, `HET_SETTLED_MS`, but every second while lost or with the walls down) nor when lost
  (zoomed in close it can't see the room for half a minute); 10 minutes at most (`HET_WATCH_MAX_MS`: a round with
  two or three layouts can take more than 5). Pixel counts alone (stone against floor) couldn't tell walls down from a close view. All three
  recordings replay right (`HetReplayTest`).
- **Het's seal and the mirror behind the caster** (26 October 2026, the owner's recording
  `pathofhet/watch_20261026_cycle.mp4`, Watch's window moved to the bottom left so the game's top left shows): the
  game shows "Het's Seal" with a health bar (119) at the top left; it drops only while you mine the seal (119, 66, 32
  over about 6 s) and stays there when the next layout rises. `HetReader.sealHealth` reads the bar (teal fill
  (0, 206, 166) on a track (0, 31, 31), a fresh hit (0, 150, 119); the longest run of bar colours across a row of the
  top left) and `Memory.use` clears the layout when it shows lower on two looks in a row (a beam or a hit splash can
  misread it once); at nothing on two looks Watch stops ("broken"). A new Watch forgets the seal (`Memory.start`;
  `reset` keeps it, as it's also used to clear a layout mid-round), or a seal broken in an earlier raid would stop
  the next Watch on its first look. The replay caught the mining at once and read the
  new layout 2 s after the walls rose. Every layout seen has one fixed mirror on the beam's line just west of the
  caster (2,10 to 5,10), hidden behind it from the doorway, so its base scored 0.68-0.86 against `MIRROR_BASE` 0.85:
  behind the caster (`behindCaster`) a base from `SHADOW_BASE` 0.6 counts if a mirror's disc shows above it, the
  nearest disc to its square (a mirror further back shows above the nearer squares too); a square there needs 3
  clear looks to settle clear (elsewhere 2: one look settling it clear lost that mirror for good); votes beside a
  square count for it; and only one mirror is kept there (`Memory.fixed`). Found in all four recordings and the
  RuneLite-known raid (2,10); the owner saw the recording's one send the beam south, as read (4,10 south-east).
- **Quick while mining** (26 October 2026, `pathofhet/watch_20261026b.mp4`): on the phone Watch cleared the map 16 s
  after mining began and read the next layout 11 s after it rose, where the replay took 1 s and none: with the room
  lost, a look does up to three full searches, a fraction of a second on the PC and seconds on the phone. So
  `watchLook` checks the seal first and, while it shows lower than `Memory.seal`, stops there (1-2 ms). And with the
  map gone (it hides when the layout is cleared) the wide look favoured the wrong room's camera, nothing fitted with
  it, and the new layout went unread 5 looks running: `read` now tries the runner-up room's camera when the first
  doesn't hold up. `HetReplayTest` blanks the map only while Watch shows a room, as the phone does. Test builds log
  each look's time: `adb logcat -s PathOfHet`.
- **Speed on the phone** (26 October 2026, an on-phone test of `watchLook` on 17 of the recording's pictures, with
  the game running): the debug build Android Studio installs is 7-8 times slower than a release build, so judge
  Watch's speed on a non-debuggable build (`debug { isDebuggable = false }` in `buildTypes` for a moment, signed
  with the debug key so it installs over). Debug / release: following the room 2.3 s / 0.3 s, a full read 7 s / 1 s,
  a look with the room lost up to 12 s / 1.4 s, a seal-only look 3 ms. The PC is about 4 times quicker than the
  release build.
- **Before the walls rise, and zoomed in** (the owner's recording `pathofhet/watch_20261026c.mp4`): from the doorway
  before the room starts, the bare floor can fit a room (more often with the runner-up's camera), and Watch locked on
  it. The seal's bar only shows once the walls rise, so the first look that sees it forgets any room read before
  (`Memory.use`), and the page shows nothing for up to 10 s until it does (`HET_BAR_WAIT_MS`, in case a screen's
  bar can't be read). And zoomed in close while solving, a part of room 2 fitted room 1 three times (focus zoom
  25-28% off the first read's, room 2's own readings 7% at most), moving Watch to room 1: another room now only
  counts at about the first read's zoom (`ROOM_CHANGE_ZOOM` 0.15), which also ended the wrong room as the walls
  sank in `watch_20261026_cycle.mp4`. Starting Watch also clears the last Watch's answer from the map. All six
  recordings replay right, each ending in the right room (their settings are at the top of `HetReplayTest`); the
  owner's live round on the fast build (`watch_20261026d.mp4`) worked throughout.
- **Which way fixed mirrors face** (`HetReader.readFacings`, October 2026): from the disc high above the cone,
  measured from the game's own models (`.scratch/toa-helper/het-mirror-models.md`; only the numbers are in the code,
  no models). The glass (pale blue-green) shows when the mirror faces the camera, the plain brown back when it faces
  away; the gold rim is left out (it's the cone's colour). The biggest glass-or-back patch above the square is
  measured (lean and thinness) and compared with how the two possible facings would look from the game's camera:
  6.6 squares behind the middle of the screen and 15.8 up (`CAMERA_BACK`, `CAMERA_UP`: the desktop game's camera
  at its steepest, and the best fit to the owner's screenshots). The closer one is the answer if it fits within
  `FACING_FIT` and beats the other by `FACING_MARGIN`; else it's left unknown. Checked: the RuneLite-known raid
  (5,6 and 19,7 north-west, 5,13 south-east: `HetReaderTest.readsWhichWayFixedMirrorsFace`); the same mirrors
  read alike in every screenshot of a raid, including from the north side. Watch votes on facing look by look
  (`Memory.facing`): two agreeing looks with none against settle it for good. The owner's first try (October 2026)
  read the mirror at the caster right at the door, then turned it while walking south and jumped to the wrong room;
  so another room now needs `ROOM_CHANGE_LOOKS` (3) looks in a row, and only in the first looks: after that the room
  is kept (`Memory.locked`; `watchKeepsWhatLooksAgreed`). The owner's recorded run (25 October 2026,
  `pathofhet/watch_20261025.mp4`, replayed by `HetReplayTest`) showed why: from deep in the room with the camera
  turned, a full read was sure of room 1 or 3 several looks in a row, and the quick look loses the camera when it's
  turned fast. So once locked, a look that lost the room searches the camera afresh for that room only
  (`read(px, room)`). Single looks from deep in the
  room also misread facings and take placed mirrors for fixed ones; the settling rules keep those out. One mirror
  read on two squares side by side now counts once (the clearer; in `Memory`, the one seen more often), and a square
  next to a mirror a look saw isn't voted clear by that look. The second recording (`watch_20261025b.mp4`, room 3)
  starts before the walls rise: the bare floor read as room 2, sure, so a reading now needs the room's walls standing
  (`wallsUp` at least `MIN_WALLS_UP`: 0.93-1 on every screenshot). It then finds room 3, fixed mirrors 9,2 NW,
  14,17 SW and 18,6 NW, and way 6 (the 14,17 mirror is its corner, already facing right); the mirror the owner
  placed at 3,10 reads north-east, as way 6 asks. Replay: `HetReplayTest` with `HET_REPLAY=<frames folder>` and
  `HET_REPLAY_ROOM` (0-2), run with `--rerun` (Gradle doesn't notice a changed setting). Frames, one a second, are made with `ffmpeg -i <recording>.mp4 -vf fps=1 <folder>\f%03d.png` from the recordings in `C:\Users\Tanis\Documents\Androiddev\OSRSBubbleTool\pathofhet\` (each one's start second and room are at the top of `HetReplayTest.kt`); add `HET_REPLAY_BOTTOM=1` for the 26 October recordings (window at the bottom left), and run `./gradlew.bat --stop` between runs (the Gradle daemon keeps the old settings). Each replay must end in the right room and should catch mining within 1-2 looks. `HetReader.follow` is kept only for `HetReaderTest.followsTheCamera`. Our simple camera puts the disc in the wrong place (too low far away, too high near), so it's
  searched for from 0.9 to 4.5 squares up. Next: more RuneLite-known raids.
- **Checking a Het room's walls**: the best source by far is a straight-down RuneLite screenshot (camera tilted all
  the way down with the Camera plugin's vertical camera, west at the top). Capture the RuneLite window even when it's
  hidden (PowerShell PrintWindow on the "RuneLite" window), line a grid up on the three statues' middles (7.5,
  10.5, 13.5) and the doors, and count squares; read a wall's base from its edge nearest the middle of the screen.
  All three rooms were checked this way (24 October 2026). Then check which way the plugin's rules pick.
- **Adding a Het room**: the owner's screenshots are in `C:\Users\Tanis\Documents\Androiddev\OSRSBubbleTool\pathofhet\`
  (taken walking in, camera facing west, zoomed out). Read the walls off with a perspective grid drawn over the
  picture, every wall one square thick and fitted as a solid block over each picture (a wall's ends only from views where it
  runs across the screen; the owner's square counts in the game beat any photo fit) (how is in
  `.scratch/toa-helper/issues/06-het-layouts.md`; a view from the far side pins the wall ends), then draw every way over it to see which one
  misses the smooth walls. Walls are read to about a square; the mirrors are exact. Rooms so far: 3, which is all
  of them (research in the issue note: the game builds the maze from three templates). Add a new room's screenshot
  to `HetReaderTest`'s list too.
- **Het room reader** (`HetReader.kt`, no Android parts): sorts every other pixel into wall stone (dark slate blue),
  sand, or other (statues, mirrors, the near-black orbs, the player), starts from the middle of the biggest sand
  patches, and searches a simple camera (facing any way round, 8° steps at first, zoomed, tilted, the room's middle
  on screen; any direction so it works from beside the seal after a failed attempt, facing north or south).
  Speed: the wide look checks every other floor square and is shared between the processor cores (`wideSearch`, on
  threads kept for the next look that end by themselves after 30 s without one),
  then two closer looks at every square; about 0.12 s a picture on the PC (was 0.75 s). Stages:
  a wide search, a closer one around the best room's camera (and the runner-up's, if that one doesn't hold up), then
  every room scored with that one camera (only a
  nudge allowed: letting each room find its own camera let a wrong room line a few thin walls up with real ones). Each room scores +1/-1 for each floor square whose
  colour matches its walls, divided by the squares seen; squares just behind a wall (hidden) are skipped. It only
  answers (`sure`) when the best room scores 0.75+ and beats the next by 0.12+. The square just behind a wall counts
  as stone (its top and front cover it). On 11 screenshots (room 2 in three crack patterns): right every time,
  scores 0.84-0.94, margins 0.45+; a camera turned 35° and lower scores 0.60, so it's unsure by design. About half a
  second on the PC, about 3 s on the emulator, 1 s on the owner's phone (7 s in a debug build: see "Speed on the
  phone"). Tested by `HetReaderTest` (uses the pathofhet folder, or
  `HET_SHOTS=<folder>`; skipped without it).
- **Game brightness**: the setting bends every game colour along one curve (255 * (c/255)^power; the owner's
  screenshots at the lowest/middle/highest setting fit it exactly, power 0.74 and 1.55 back to the middle), but not the
  game's side panels and buttons. `brightnessCurve` finds the power from the floor's shade in the middle of the screen
  (5 rounds, within 0.005) and bends the picture back; `floorMiddle` only looks in the middle 18-74% of the width, so
  brightened panels can't pass for sand. The three brightness screenshots (`_b50`, `_bmin`, `_bmax`, same view) all
  read room 1 with the same cracks.
- **Cracked or smooth** (the game's "break sets" change which walls are cracked from raid to raid): with the room's
  camera found, `readCracks` looks up 27 points through each wall (`HetRoom.blocks`) and calls it cracked when more
  than 6% of its stone is near-black (the cracks; lum < 26). Smooth walls showed 0-1%, cracked 20%+, on every
  screenshot, including the three raids with unusual cracks (`HetReaderTest.crackedThatRaid`). The page then draws
  that raid's cracks and works the best way out again with the plugin's rules (`hetBestWay`: at most 3 mirrors to
  carry, fewest to carry, fewest walls to mine, first in the plugin's list); none of the patterns seen so far
  changes a room's way. Brightness alone didn't work from the entrance camera (the room is lit from the middle, so
  far walls look dark whatever kind they are).
- **Testing the reader on the emulator** (no game needed): push a screenshot to `/sdcard/Pictures`, open it with
  `am start -a android.intent.action.VIEW -d file:///sdcard/Pictures/<name>.png -t image/png`, turn the emulator
  sideways (`wm user-rotation lock 1`), start the bubble, open the page, Watch, share the entire screen.
  Put it back upright afterwards (`wm user-rotation lock 0`). If the plugin's `HetSolution.java` changes,
  copy each way's `STATE_MIRROR_MOVABLE` squares and orientations into `HET_ANSWERS` and update the lengths in the test.

## Matching Watch

The camera follows the player, so both 3×3 boards are found again in every picture: 3 looks a second (~130 ms
each on the phone), plus up to 3 quick looks 40 ms apart right after a look that saw a symbol not settled yet
(`MatchMemory.unsure`), so it's caught before the player steps off it. A pair counts as matched after glowing
0.8 s.

Hard-won rules, each found from a real failure:
- The first look needs both boards side by side, and picks the pair with the most yellow on both (the
  see-through inventory over the next room's floor makes a perfect fake board of "hidden-grey" squares).
- After that, each board keeps its identity and direction from the last picture (`orientLike`; players turn the
  camera a lot). If both are lost it searches afresh (`freshPair`). Jumps over 2.5 tiles are bad fits.
- Only boards actually found are read, and symbols only from boards with 8+ of 9 tiles lined up (7 can be a tile
  out of place, which put a wiggle on the wrong tile at close zoom).
- A tile counts as matched only in pairs (`settlePairs`; the orbs by the minimap can look like a glow), or alone
  after glowing 4 s away from the right-hand buttons (a raid's starting pairs, whose partner may be hidden).
- Solo or group raid is decided from the first look (7 or 8 glowing tiles = solo: a solo raid starts with exactly 4
  pairs matched; that limits the symbols to five), and kept when Watch is restarted unless the memory is empty.
  Watch started more than 10 minutes after the last look is a new raid: the memory is cleared (`NEW_RAID_MS`).
- One very clear look is enough to take a symbol, but only for the symbol that look saw (a vote tie used to take the
  list's first symbol on another symbol's clear look).
- The highlighted tile (`active`) is the one that has just started showing a symbol; if none has, the one already
  highlighted stays (another player's revealed tile doesn't take it over).
- Revealed symbols vanish when the player steps on the next tile and are often half hidden by the player, so
  recognition compares only the visible points against 31 samples (`SYMBOL_SAMPLES`, from the matching and
  addition rooms).

**Ideas that made the replays worse and were undone**: searching only near the boards, skipping a board once
found whole, fewer candidate marks, the best pair every frame, the nearest board to last time, and forgetting a
glow not seen for 2 s (the Star pair marked ~19 s later). Check every change against the recordings.

## Testing on the PC (required for any matching change)

`app/src/test/.../ToaReaderTest.kt` is skipped unless pointed at pictures. Always add `--rerun-tasks` (Gradle
otherwise skips the test when only the folder changed):

- `TOA_SHOTS=<folder>` reads every screenshot fresh.
- `TOA_REPLAY=<folder>` replays a recording (files `f_<time>.png`) through the matching reader and the same memory
  rules as the app, printing each frame, every change to the remembered boards (`MEMORY` lines) and the time per
  picture. Compare the `MEMORY` lines before and after a change.
- `TOA_DEBUG_FRAME=n` lists the candidate boards in frame n.

Recordings live outside the project in `C:\Users\Tanis\Documents\Androiddev\toa_recordings\` (never commit them):
- `matching_1\`: 192 pictures, a full solo solve; the replay ends with all nine pairs matched.
- `matching_2\`: 114 pictures, a run that started with the inventory open.
- `screenshots\`: every puzzle room, used with `TOA_SHOTS`.

**Any change to the matching reader must replay both recordings without getting worse.** New recordings go there
too. To record a live solve: while the owner plays, two `adb exec-out screencap -p` loops save ~2.5 pictures a
second; the owner says "start" and "stop".

## On screen

- After a reading (or while watching) the window shrinks to a few buttons (`BubbleService.setWindowCompact`:
  `COMPACT_TOP_LEFT`, or `COMPACT_BOTTOM_LEFT` for addition so the chat stays clear, and for the Path of Het so Het's
  seal's bar at the top left shows), and the puzzle's map is
  drawn alone at the bottom of the screen in a separate untouchable overlay. Both areas are skipped when reading.
  The Path of Het's map sits just right of its small window (`placeHetMap`, run whenever the window's layout
  changes), not in the middle, where the game opens a mirror's menu. Its overlay is measured from the screen's very
  edge (cut-out included, like `GuideWindow`), so the window's place on screen can be used as it is; measuring the
  map's own place instead made it jump back and forth, as it read the old place before a move had finished.
- Each page: its name at the top (not on the shrunk window), a tip line, main buttons, a `?` for instructions, and tap-in controls behind "Solve manually" (for
  the light, sequence and matching puzzles the map itself is behind it too, as the answer shows at the bottom of
  the screen). The
  puzzle list shows a small picture of each board (`puzzleIcon`); the owner likes them as they are.
- **Owner's preferences**: compact windows (few words, one row of buttons, `?` for instructions), maps kept
  straight (snapped to the nearest direction), green for "step here", symbols drawn like the game's (the owner
  checks them against the game and corrected the knives, foot and hand).

## Debugging

Test builds only: pictures a reader couldn't make sense of are saved to the app's `cache/toa_debug` (newest 30,
one per 2 s; `adb shell run-as com.sinat.osrsbubbletool ls cache/toa_debug`), and Watch logs its timings
(`adb logcat -s ToaWatch`; the Path of Het's Watch logs each look's time, `adb logcat -s PathOfHet`). Test builds
are debuggable and run the readers 7-8 times slower than the release (see "Speed on the phone"). Matching Watch costs ≈ 45% of one core (the game itself ≈ 80%) and 20-30 MB.
