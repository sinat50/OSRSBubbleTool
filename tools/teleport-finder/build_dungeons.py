"""Builds app/src/main/assets/dungeons.txt, the dungeon entrances the Teleport Finder uses when the walking
map has no way into a closed-off cave.

Source: DungeonLocation.java from RuneLite's world map plugin (github.com/runelite/runelite, BSD 2-Clause),
at runelite-client/src/main/java/net/runelite/client/plugins/worldmap/DungeonLocation.java.
Entries on plane 0 are kept (a few are inside caves, like boss rooms: the finder treats them the same way).

Usage: python3 build_dungeons.py path/to/DungeonLocation.java path/to/dungeons.txt
"""
import re, sys

src, out = sys.argv[1], sys.argv[2]
text = open(src, encoding='utf-8').read()
rows = re.findall(r'\w+\("([^"]+)",\s*new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)\)', text)
lines = ['# Dungeon entrances on the surface: x y name. From RuneLite\'s world map (github.com/runelite/runelite), BSD 2-Clause.']
for name, x, y, p in rows:
    if int(p) == 0:
        lines.append(f'{x} {y} {name}')
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print(len(lines) - 1, 'entrances')
