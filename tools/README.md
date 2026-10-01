# Tools

Scripts that build the app's data files. The app doesn't need them to run or build: they're only for
updating the data (for example after a game update) and for showing how each bundled file was made.
Run them on a computer with Python 3, then copy the results into `app/src/main/assets/`.

## Teleport Finder (`teleport-finder/`)

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
| `find_closed_off_areas.py` | `extra_walks.txt` | Joins areas no teleport reaches. Needs numpy and scipy, several GB of memory and tens of minutes. See the notes at the top of the script for the order to run things in. |

`extra_walks.txt` is kept here so the build script works without rerunning the slow analysis.

### Checking a change (`teleport-finder/test/`)

`targets.json` lists 95 places (153 map spots) used to check the Teleport Finder. `CompareRoutes.kt`
runs them against one or more versions of `walks.txt` and prints how many results are guesses and every
place whose top result changed. Build and run instructions are at the top of the file.

## DPS Calculator gear import (`dps/`)

```
git clone https://github.com/weirdgloop/osrs-dps-calc       # GPL-3.0
cd osrs-dps-calc && git checkout <GearIcons.COMMIT>
```

| Script | Makes | Notes |
| --- | --- | --- |
| `build_equipment_json.py` | `dps/equipment.json` | `python3 build_equipment_json.py osrs-dps-calc equipment.json` |

`wiki_icons.txt` lists the item pictures the repository has out of date at the pinned version; the app
downloads those from the OSRS Wiki instead. If you move to a newer version, update `COMMIT` in
`GearIcons.kt` (the app downloads its pictures from that exact version), rebuild `equipment.json`, and check
whether those pictures are still out of date.
