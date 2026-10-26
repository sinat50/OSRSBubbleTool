# Teleport Finder: updating its data

How to rebuild the Teleport Finder's data after the update checker reports `teleports` (Shortest Path),
`dungeons` (RuneLite) or `obstacles` (Golems Don't Die). Last done 26 October 2026 (Shortest Path 0fb23cd,
24 October), in about 10 minutes all told. The scripts are described in `tools/README.md`; this is the whole
routine, in order.

## The files

| File | Made by | From |
| --- | --- | --- |
| `assets/teleports.tsv` | `build_teleports_and_walks.py` | Shortest Path's `transports/*.tsv` teleport files, plus `FIX` (corrected landing spots) and `ADD` (teleports Shortest Path doesn't have) in the script |
| `assets/walks.txt` | `build_teleports_and_walks.py` | Shortest Path's `transports.tsv` and friends (doors, ladders, boats...), plus `CAVES`, `CAVE_BARRIERS`, `GUESSED_WAYS` in the script and the joins in `extra_walks.txt` |
| `assets/collision.bin` | `build_collision.py` | Shortest Path's `collision-map.zip` |
| `assets/dungeons.txt` | `build_dungeons.py` | RuneLite's `DungeonLocation.java` (only when `dungeons` is reported) |
| `tools/teleport-finder/extra_walks.txt` | `find_closed_off_areas.py` | The three files above, plus Golems Don't Die's `obstacles.gz` |

The app reads these with `TeleportData.kt` and `WalkMap.kt`, which have no Android parts (so the route check below
runs them on the PC).

## The routine

Work in the scratchpad (call it `W`), never in the project, and copy results in at the end. Run Python with `-I`.

1. **Download**: `git clone --depth 1` each of github.com/Skretzo/shortest-path and
   github.com/Varzeki/golems-dont-die (and github.com/runelite/runelite only if `dungeons` changed).
   Keep a copy of the current `teleports.tsv`, `walks.txt`, `collision.bin`, `dungeons.txt` in `W/old/`.
2. **Collision map**: `python -I tools/teleport-finder/build_collision.py W/shortest-path/src/main/resources/collision-map.zip W/new/collision.bin`.
   First check `FLAG_COUNT` in Shortest Path's `pathfinder/SplitFlagMap.java` still matches `FLAGS_IN` in the
   script (4 since 9 October 2026).
3. **Build without joins**: `python -I tools/teleport-finder/build_teleports_and_walks.py W/shortest-path W/empty.txt W/nojoin`
   (`empty.txt` = an empty file), then copy `collision.bin` and `dungeons.txt` into `W/nojoin/`.
4. **Look at the teleport changes**: `diff W/old/teleports.tsv W/nojoin/teleports.tsv`. Things to act on:
   - an `ADD` entry that Shortest Path now has itself: it appears twice in the diff (once moved). Remove it from
     `ADD` (the script skips names already there, but the entry is then dead weight).
   - a `FIX` entry for a teleport that was renamed: the fix silently stops applying. Rename it in `FIX`.
   - renamed teleports: `grep` the app's code and `tools/teleport-finder/test/` for the old name.
5. **Joins** (21 seconds, needs numpy and scipy):
   `python -I tools/teleport-finder/find_closed_off_areas.py W/nojoin/collision.bin W/nojoin/walks.txt W/nojoin/teleports.tsv W/golems-dont-die/src/main/resources/obstacles.gz W/new_extra_walks.txt`
6. **Final build**: step 3 again with `W/new_extra_walks.txt` instead of the empty file, into `W/final/`
   (it should print `joins added` with the number from step 5).
7. **Check routes** (below). Fewer guesses and no lost results is what you want; read every changed top result.
8. **Copy in**: `teleports.tsv`, `walks.txt`, `collision.bin` (and `dungeons.txt`) to `app/src/main/assets/`,
   `new_extra_walks.txt` to `tools/teleport-finder/extra_walks.txt`; compare them with `cmp` after a few seconds.
   `walks.txt` always shows a diff of thousands of lines (its numbering shifts): that's normal. Its names are
   the in-game actions ("Attract Light creature"), not places, so check a new transport is in by its
   coordinates: `grep "^x y plane x2 y2 plane2" walks.txt` with the first two columns of Shortest Path's line.
9. Build and run the PC tests, then `--checked teleports` (and `obstacles` / `dungeons`) in the update checker.

## Checking routes (`tools/teleport-finder/test/CompareRoutes.kt`)

There's no `kotlinc` on the owner's computer, but Gradle's cache has the compiler. Use the JDK in
`%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25...\bin\java` (the `java` on the PATH is Java 8, too old).
From `~/.gradle/caches/modules-2/files-2.1/`, the class path is the jars of `kotlin-compiler-embeddable`,
`kotlin-stdlib`, `kotlin-script-runtime`, `kotlin-reflect`, `kotlin-daemon-embeddable` (all one recent
version), `trove4j` 1.0.20200330, `kotlinx-coroutines-core-jvm` and `annotations` 13.0. Use org.json
**20240303** (the 2018 one fails on `keys()`). Separate paths with `;` and give Windows paths (`cygpath -w`).

```
java -cp <compiler jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "<json>;<stdlib>" \
    tools/teleport-finder/test/CompareRoutes.kt app/src/main/java/com/sinat/osrsbubbletool/TeleportData.kt \
    app/src/main/java/com/sinat/osrsbubbletool/WalkMap.kt -d W/compare
java -cp "W/compare;<json>;<stdlib>" CompareRoutesKt tools/teleport-finder/test/targets.json \
    W/final/teleports.tsv W/final/collision.bin W/final/dungeons.txt W/old/walks.txt W/final/walks.txt
```

It prints, for each `walks.txt`, how many of the 153 test spots have no result or a guessed top result, then every
spot whose top result changed. (It uses the new `teleports.tsv` and `collision.bin` for both, so it compares the
walking data only.) October 2026: 25 → 23 guesses; Wise Old Man and Giant Mole got real routes, Corporeal Beast's
top answer became the Games necklace.

## History worth knowing

- 6 Oct 2026: Shortest Path data refreshed, extra_walks rerun (commit 7366986).
- 10 Oct 2026: Shortest Path's collision map went to 4 bits per tile; `build_collision.py` converts it.
- 24 Oct 2026: new transports (Chasm of Tears, Ancient Guthixian Temple), Aldarin house portal, clearer cape
  teleport names; the hand-added Wyrmscraig necklace of passage removed from `ADD` (Shortest Path has it now).
- 26 Oct 2026: Prifddinas city platforms and staircases, Iorwerth Dungeon shortcuts, Trahaearn Mine and Zalcano
  links (walks only: teleports and collision map unchanged); 14 more joins. Iorwerth Dungeon routes much shorter
  (Dark beasts 239 → 116, Greater Nechryael 198 → 75); still 23 guesses.
