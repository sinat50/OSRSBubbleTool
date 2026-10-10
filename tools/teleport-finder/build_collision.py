"""Builds app/src/main/assets/collision.bin, the walking map used by the Teleport Finder.

Source: collision-map.zip from the Shortest Path RuneLite plugin (github.com/Skretzo/shortest-path,
BSD 2-Clause), at src/main/resources/collision-map.zip. It holds one file per 64x64 map region, named
"regionX_regionY", with FLAGS_IN bits per tile and plane, lowest bit first: "you can step north",
"you can step east", and (since 9 October 2026) "a wall or door is on the north edge" and "... on the
east edge". The app only needs the first two, so the wall bits are left out.

collision.bin is the same data in one file the app can read quickly:
  "OBTC", region count (4 bytes), then for each region: region x (2), region y (2),
  unpacked size (4), packed size (4), and the region's bytes packed with zlib.
All numbers are big-endian. Each region has two bits per tile ("north", "east"), lowest bit first,
tile by tile (x first, then y, then plane), with any zero bytes at the end left off.

Usage: python3 build_collision.py path/to/collision-map.zip path/to/collision.bin
"""
import struct, sys, zipfile, zlib

# Bits per tile in Shortest Path's file: 4 since its 9 October 2026 version (commit 7324ec3), 2 before.
# If Shortest Path changes this again, the plugin's SplitFlagMap.java (FLAG_COUNT) says the new number.
FLAGS_IN = 4
# For one byte of a 4-bit file (two tiles): their "north" and "east" bits side by side, as 4 bits
KEEP = [(b & 3) | ((b >> 4) & 3) << 2 for b in range(256)]

src, out = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src)
names = [n for n in z.namelist() if not n.endswith('/')]
blob = bytearray(b'OBTC' + struct.pack('>I', 0))   # the region count is filled in at the end
count = 0
for n in names:
    rx, ry = map(int, n.split('/')[-1].split('_'))
    raw = z.read(n)
    if FLAGS_IN == 4:
        # Keep only the "north" and "east" bits of each tile. One byte in holds two tiles (4 bits each),
        # so two bytes in make one byte out (2 bits each).
        if len(raw) % 2:
            raw += b'\0'
        raw = bytes(KEEP[raw[i]] | KEEP[raw[i + 1]] << 4 for i in range(0, len(raw), 2)).rstrip(b'\0')
    elif FLAGS_IN != 2:
        sys.exit('FLAGS_IN must be 2 or 4')
    if not raw.strip(b'\0'):
        # Nothing walkable here (the 4-bit file has regions with only wall bits). Left out: the app
        # treats a region in the file as mapped, and the 2-bit file never had such regions.
        continue
    packed = zlib.compress(raw, 9)
    blob += struct.pack('>HHII', rx, ry, len(raw), len(packed)) + packed
    count += 1
blob[4:8] = struct.pack('>I', count)
open(out, 'wb').write(blob)
print(count, 'regions,', len(blob), 'bytes')
