# Tools

Scripts that build the app's data files. The app doesn't need them to run or build: they're only for
updating the data (for example after a game update) and for showing how each bundled file was made.
Run them on a computer with Python 3, then copy the results into `app/src/main/assets/`.

`wiki_diff.py` shows what changed on an OSRS Wiki page since an older revision, as plain text (for the update
checker's wiki edits): `python tools/wiki_diff.py "Page title" OLD_REVISION_NUMBER`.

## Teleport Finder (`teleport-finder/`)

The full update routine, step by step, with the route check: `docs/teleport-finder.md`.

Sources to download first:

```
git clone https://github.com/Skretzo/shortest-path          # teleports, transports, collision map (BSD 2-Clause)
git clone --depth 1 https://github.com/runelite/runelite    # DungeonLocation.java (BSD 2-Clause)
git clone https://github.com/Varzeki/golems-dont-die        # obstacles.gz (BSD 2-Clause)
```

| Script | Makes | Notes |
| --- | --- | --- |
| `build_collision.py` | `collision.bin` | `python3 build_collision.py shortest-path/src/main/resources/collision-map.zip collision.bin` |
| `build_dungeons.py` | `dungeons.txt` | `python3 build_dungeons.py runelite/runelite-client/src/main/java/net/runelite/client/plugins/worldmap/DungeonLocation.java dungeons.txt` |
| `build_teleports_and_walks.py` | `teleports.tsv`, `walks.txt` | `python3 build_teleports_and_walks.py shortest-path extra_walks.txt app/src/main/assets`. Its FIX, ADD, CAVES and GUESSED_WAYS lists hold the hand-checked corrections and additions: add new teleports and cave entrances there. |
| `find_closed_off_areas.py` | `extra_walks.txt` | Joins areas no teleport reaches. Needs numpy and scipy and several GB of memory; about 20 seconds. See the notes at the top of the script for the order to run things in. |

`extra_walks.txt` is kept here so the build script works without rerunning the slow analysis.

### Checking a change (`teleport-finder/test/`)

`targets.json` lists 95 places (153 map spots) used to check the Teleport Finder. `CompareRoutes.kt`
runs them against one or more versions of `walks.txt` and prints how many results are guesses and every
place whose top result changed. Build and run instructions are at the top of the file.

## Gear Loadouts item list (`items/`)

No downloads needed first: the script reads the OSRS Wiki's item tables through the wiki's API.

| Script | Makes | Notes |
| --- | --- | --- |
| `build_items.py` | `items/items.tsv`, `items/noted.tsv`, `items/icons/` | `python3 build_items.py app/src/main/assets` (from the project folder). Every item's ID, name, equipment slot and picture(s) from the OSRS Wiki, and noted IDs from RuneLite's item list. Skips pictures already there. About 8 minutes the first time (15,000 pictures), seconds after. |
| `build_icons.py` | `items/canvas/`, `items/canvas.tsv`, `items/canvas.bin`, `items/shapes.bin` | `python3 build_icons.py app/src/main/assets`, **right after** `build_items.py`. Every item's icon exactly as the game draws it (RuneLite's, 36 x 32, noted ones on their note), for showing items and reading the inventory at each item's exact place. Then removes the wiki pictures no longer needed (keeps stack-size pictures like coins) and writes `shapes.bin` for those kept. About 15 minutes the first time (21,000 icons), seconds after. |

## Quest Helper (`quest-helper/`)

`quest_files.py` shows, adds and replaces quests in `QuestData.kt` and `QuestGuides.kt` (the app's code folder),
from one JSON file per quest kept in `quest-helper/quests/`. Each file is written by hand from the Quest Helper
plugin's Java, following `docs/quest-helper.md`. `python tools/quest-helper/quest_files.py` (from the project
folder) lists its commands.

## DPS Calculator gear import (`dps/`)

```
git clone https://github.com/weirdgloop/osrs-dps-calc       # GPL-3.0
cd osrs-dps-calc && git checkout <GearIcons.COMMIT>
```

| Script | Makes | Notes |
| --- | --- | --- |
| `build_equipment_json.py` | `dps/equipment.json` | `python3 build_equipment_json.py osrs-dps-calc equipment.json` |
| `fetch_pictures.py` | `dps/icons/`, `puzzles/`, `quest_images/` | `python3 fetch_pictures.py app/src/main/assets` (from the project folder). Downloads every game picture the app comes with: item pictures from the repository at `GearIcons.COMMIT` (the "wiki" ones from the OSRS Wiki), and the solved puzzle and quest pictures from the wiki. Skips ones already there and removes ones no longer needed, so run it after rebuilding `equipment.json`. About 30 seconds. |

`wiki_icons.txt` lists the item pictures the repository has out of date at the pinned version;
`fetch_pictures.py` gets those from the OSRS Wiki instead. If you move to a newer version, update `COMMIT` in
`GearIcons.kt`, rebuild `equipment.json`, check whether those pictures are still out of date, then run
`fetch_pictures.py`.
