# Updating each tool's data

Each source the update checker (`tools/update-check/check_updates.py`) reports, and where the routine for
bringing it in is written down. Follow the linked notes rather than working it out from the code. If a tool has
no notes yet, write them (in `docs/<tool>.md`) the first time you update it, and link them here.

| Checker name | Tool | How to update |
| --- | --- | --- |
| `teleports`, `dungeons`, `obstacles` | Teleport Finder | `docs/teleport-finder.md` |
| `quest-helper`, `new-quests` | Quest Helper | `docs/quest-helper.md` (new quests only arrive once the Quest Helper plugin has them) |
| `dps-items`, `item-pictures` | DPS Calculator | `docs/gear-items.md` (DPS section) |
| `puzzle-pictures`, `quest-pictures` | Puzzle Box Solver, Quest Helper | Delete the changed picture from `assets/puzzles/` or `assets/quest_images/`, run `tools/dps/fetch_pictures.py` |
| `new-gear` | Gear Loadouts, DPS Calculator item lists | `docs/gear-items.md` |
| `toa` | ToA Puzzle Helper | `docs/toa-helper.md` (answers in `ToaPuzzles.kt`) |
| `hunter-rumours` | Hunter Rumours | `docs/hunter-rumours.md` |
| `zulrah` | Zulrah Helper | No notes yet. Rotations are hand-written in `ZulrahTool.kt` |
| `farming` | Timers | No notes yet. Crops and growth times are hand-written in `FarmingTimers.kt` (tested by the farming PC tests) |
| (not watched: DroidFish rarely changes) | Chess (Game Room) | `docs/chess.md`, "Updating the engine" |
| `game-updates` | Everything | Read the posts; anything a tool needs goes through that tool's row above |

For any wiki page, `python tools/wiki_diff.py "Page title" OLDID` shows an edit as plain text.

After an update: build, run the PC tests, mark the source with `--checked NAME`, and update the tool's notes if
anything in the routine changed (new step, renamed file, a gotcha you hit).
