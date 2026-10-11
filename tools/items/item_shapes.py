"""Each item picture's shape and colours in brief, for Gear Loadouts' inventory reading (InventoryReader): the reader
narrows the pictures down to a few dozen with these, then loads only those to compare closely. Used by build_items.py
and build_icons.py.

items/shapes.bin: "SHP1", the number of pictures (4 bytes), then for each picture: its file name (1 byte length, then
UTF-8), width and height (1 byte each), which pixels are part of it (1 bit each, row by row, first pixel in the top
bit), then a 4 x 4 grid over the picture: which squares have any of it in them (2 bytes, first square in the top bit)
and each square's average colour (3 bytes each, red green blue).
"""
import os, struct
from PIL import Image   # (pip install pillow)

def summary(path):
    im = Image.open(path).convert('RGBA')
    w, h = im.size
    if w > 255 or h > 255:
        return None
    px = im.load()
    solid = [[px[x, y][3] > 128 for x in range(w)] for y in range(h)]
    bits = bytearray((w * h + 7) // 8)
    for y in range(h):
        for x in range(w):
            if solid[y][x]:
                i = y * w + x
                bits[i // 8] |= 0x80 >> (i % 8)
    has, colours = 0, bytearray()
    for j in range(4):
        for i in range(4):
            sums, n = [0, 0, 0], 0
            for y in range(j * h // 4, (j + 1) * h // 4):
                for x in range(i * w // 4, (i + 1) * w // 4):
                    if solid[y][x]:
                        r, g, b, _ = px[x, y]
                        sums[0] += r; sums[1] += g; sums[2] += b; n += 1
            if n:
                has |= 0x8000 >> (j * 4 + i)
            colours += bytes(s // n if n else 0 for s in sums)
    return bytes([w, h]) + bytes(bits) + struct.pack('>H', has) + bytes(colours)

def write_shapes(folder, names):
    """Writes items/shapes.bin for the named pictures in items/icons/"""
    icons = os.path.join(folder, 'icons')
    entries = []
    for name in sorted(set(names)):
        path = os.path.join(icons, name)
        if not os.path.exists(path):
            continue
        s = summary(path)
        if s:
            encoded = name.encode('utf-8')
            entries.append(bytes([len(encoded)]) + encoded + s)
    with open(os.path.join(folder, 'shapes.bin'), 'wb') as out:
        out.write(b'SHP1' + struct.pack('>I', len(entries)))
        for e in entries:
            out.write(e)
    print('items/shapes.bin:', len(entries), 'pictures,', os.path.getsize(os.path.join(folder, 'shapes.bin')) // 1024, 'KB')
