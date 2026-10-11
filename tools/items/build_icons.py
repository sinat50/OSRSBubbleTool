"""Downloads every item's icon as the game draws it, into app/src/main/assets/items/canvas/, for Gear Loadouts.

Source: RuneLite's item icons (static.runelite.net/cache/item/icon/<id>.png), rendered from the game's own files by
RuneLite (github.com/runelite/runelite). The pictures are Jagex's artwork, included under Jagex's Fan Content Policy
like the wiki's. Each is the full 36 x 32 canvas the game draws an item on, with the item exactly where the game puts
it in an inventory space: so the inventory reader can compare an item at its exact place, whatever is behind a
see-through side panel. Noted items come drawn on their bank note.

Run it after build_items.py (it needs items/items.tsv and items/noted.tsv). Icons already there are skipped.
Afterwards the wiki pictures (items/icons/) are only needed for items drawn differently by stack size (coins,
arrows...) and items without an icon: the others are removed, and items/shapes.bin is written again for those kept.

Makes:
  items/canvas/<id>.png  one icon per look: items that look exactly alike (minigame copies...) share the icon of the
                         lowest ID among them
  items/canvas.tsv       item ID, then the ID whose icon it uses (only for items sharing another's icon)
  items/canvas.bin       each icon in brief, for narrowing 20,000 icons down quickly (see InventoryReader): "CNV1",
                         the number of icons (4 bytes), then for each: its ID (4 bytes), its box on the canvas (left,
                         top, right, bottom: 1 byte each), SAMPLES points inside it (x, y, red, green, blue: 1 byte
                         each, from the item's colours, its outline left out), then OUTLINE points on its black outline
                         (x, y: 1 byte each)

Usage: python3 build_icons.py path/to/app/src/main/assets
"""
import hashlib, os, struct, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image   # (pip install pillow)

assets = sys.argv[1]
AGENT = 'OSRSBubbleTool item icon builder (github.com/sinat50/OSRSBubbleTool)'
ICON = 'https://static.runelite.net/cache/item/icon/{}.png'
SAMPLES = 24
OUTLINE = 12

folder = os.path.join(assets, 'items')
canvas = os.path.join(folder, 'canvas')
os.makedirs(canvas, exist_ok=True)

ids = set()
for name in ('items.tsv', 'noted.tsv'):
    for line in open(os.path.join(folder, name), encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        parts = line.rstrip('\n').split('\t')
        for p in (parts[:1] if name == 'items.tsv' else parts[:2]):
            ids.add(int(p))
print(len(ids), 'item IDs (with noted versions)')

# Already shared with another icon in an earlier run: not downloaded again
shared = {}
tsv = os.path.join(folder, 'canvas.tsv')
if os.path.exists(tsv):
    for line in open(tsv, encoding='utf-8'):
        if not line.startswith('#') and line.strip():
            a, b = line.split('\t')
            shared[int(a)] = int(b)

def fetch(item_id):
    path = os.path.join(canvas, f'{item_id}.png')
    if os.path.exists(path) or item_id in shared:
        return True
    try:
        req = urllib.request.Request(ICON.format(item_id), headers={'User-Agent': AGENT})
        data = urllib.request.urlopen(req, timeout=60).read()
    except Exception:
        return False
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        return False
    open(path, 'wb').write(data)
    return True

with ThreadPoolExecutor(6) as pool:   # gentle on RuneLite's server
    ok = sum(pool.map(fetch, sorted(ids)))
print(f'{ok} of {len(ids)} icons there')

# Items that look exactly alike share one icon (the lowest ID's)
first_with = {}
for item_id in sorted(ids):
    path = os.path.join(canvas, f'{item_id}.png')
    if item_id in shared or not os.path.exists(path):
        continue
    im = Image.open(path).convert('RGBA')
    key = hashlib.sha1(im.tobytes()).hexdigest()
    if key in first_with:
        shared[item_id] = first_with[key]
        os.remove(path)
    else:
        first_with[key] = item_id
with open(tsv, 'w', encoding='utf-8', newline='\n') as out:
    out.write('# Item ID, then the ID whose icon (in items/canvas/) it uses, for items that look exactly alike\n')
    for a in sorted(shared):
        out.write(f'{a}\t{shared[a]}\n')

def summary(item_id):
    im = Image.open(os.path.join(canvas, f'{item_id}.png')).convert('RGBA')
    w, h = im.size
    px = im.load()
    solid = [(x, y) for y in range(h) for x in range(w) if px[x, y][3] > 128]
    if not solid:
        return None
    dark = lambda p: sum(px[p][:3]) < 30
    inside = [p for p in solid if not dark(p)]
    outline = [p for p in solid if dark(p)]
    xs = [p[0] for p in solid]; ys = [p[1] for p in solid]
    def spread(points, n):
        # points spread evenly through the list (row by row), so they cover the whole item
        if not points:
            return []
        return [points[int(i * len(points) / n)] for i in range(min(n, len(points)))]
    picks = spread(inside, SAMPLES)
    rim = spread(outline, OUTLINE)
    out = struct.pack('>I', item_id) + bytes([min(xs), min(ys), max(xs), max(ys), len(picks), len(rim)])
    for p in picks:
        out += bytes([p[0], p[1]]) + bytes(px[p][:3])
    for p in rim:
        out += bytes([p[0], p[1]])
    return out

entries = [e for e in (summary(i) for i in sorted(first_with.values())) if e]
with open(os.path.join(folder, 'canvas.bin'), 'wb') as out:
    out.write(b'CNV1' + struct.pack('>I', len(entries)))
    for e in entries:
        out.write(e)
size = sum(os.path.getsize(os.path.join(canvas, f)) for f in os.listdir(canvas))
print(f'items/canvas: {len(first_with)} icons ({len(shared)} items share one), {size / 1048576:.1f} MB; '
      f'canvas.bin {os.path.getsize(os.path.join(folder, "canvas.bin")) // 1024} KB')

# The wiki's pictures still needed: items drawn differently by stack size (several pictures), and items without an icon
has_icon = set(first_with.values()) | set(shared)
keep = set()
for line in open(os.path.join(folder, 'items.tsv'), encoding='utf-8'):
    if line.startswith('#') or not line.strip():
        continue
    parts = line.rstrip('\n').split('\t')
    pictures = [p for p in parts[3].split('|') if p]
    if len(pictures) > 1 or int(parts[0]) not in has_icon:
        keep.update(pictures)
icons = os.path.join(folder, 'icons')
removed = 0
for f in os.listdir(icons):
    if f not in keep:
        os.remove(os.path.join(icons, f))
        removed += 1
print(f'items/icons: {removed} wiki pictures no longer needed removed, {len(os.listdir(icons))} kept')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from item_shapes import write_shapes
write_shapes(folder, keep)
