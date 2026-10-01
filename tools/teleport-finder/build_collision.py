"""Builds app/src/main/assets/collision.bin, the walking map used by the Teleport Finder.

Source: collision-map.zip from the Shortest Path RuneLite plugin (github.com/Skretzo/shortest-path,
BSD 2-Clause), at src/main/resources/collision-map.zip. It holds one file per 64x64 map region, named
"regionX_regionY", with two bits per tile and plane ("you can step north", "you can step east").

collision.bin is the same data in one file the app can read quickly:
  "OBTC", region count (4 bytes), then for each region: region x (2), region y (2),
  unpacked size (4), packed size (4), and the region's bytes packed with zlib.
All numbers are big-endian.

Usage: python3 build_collision.py path/to/collision-map.zip path/to/collision.bin
"""
import struct, sys, zipfile, zlib

src, out = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src)
names = [n for n in z.namelist() if not n.endswith('/')]
blob = bytearray(b'OBTC' + struct.pack('>I', len(names)))
for n in names:
    rx, ry = map(int, n.split('/')[-1].split('_'))
    raw = z.read(n)
    packed = zlib.compress(raw, 9)
    blob += struct.pack('>HHII', rx, ry, len(raw), len(packed)) + packed
open(out, 'wb').write(blob)
print(len(names), 'regions,', len(blob), 'bytes')
