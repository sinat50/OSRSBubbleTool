# ToA Puzzle Helper: how the screen reading works

Read this before changing `ToaReader.kt`, `ToaPuzzles.kt` or `ToaPuzzleTool.kt`. Everything here was built from
the owner's phone screenshots and live raids (October 2026), never guessed.

## The files

- `ToaPuzzles` = the rules and answers (from the LlemonDuck Tombs of Amascut plugin, BSD).
- `ToaReader` = screen reading, plus `MatchMemory`, what matching Watch remembers between pictures. It has no
  Android parts, so it runs on the PC; the replay test uses the same `MatchMemory` as the app.
- `ToaPuzzleTool` = the pages, the Watch loops and the map drawn at the bottom of the screen.

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
- Solo or group raid is decided from the first look (7+ glowing tiles = solo, which limits the symbols to five),
  and kept when Watch is restarted unless the memory is empty.
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
  `COMPACT_TOP_LEFT`, or `COMPACT_BOTTOM_LEFT` for addition so the chat stays clear), and the puzzle's map is
  drawn alone at the bottom of the screen in a separate untouchable overlay. Both areas are skipped when reading.
- Each page: a tip line, main buttons, a `?` for instructions, and tap-in controls behind "Solve manually". The
  puzzle list shows a small picture of each board (`puzzleIcon`); the owner likes them as they are.
- **Owner's preferences**: compact windows (few words, one row of buttons, `?` for instructions), maps kept
  straight (snapped to the nearest direction), green for "step here", symbols drawn like the game's (the owner
  checks them against the game and corrected the knives, foot and hand).

## Debugging

Test builds only: pictures a reader couldn't make sense of are saved to the app's `cache/toa_debug` (newest 30,
one per 2 s; `adb shell run-as com.sinat.osrsbubbletool ls cache/toa_debug`), and Watch logs its timings
(`adb logcat -s ToaWatch`). Matching Watch costs ≈ 45% of one core (the game itself ≈ 80%) and 20-30 MB.
