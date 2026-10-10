"""Builds the item list and item pictures Gear Loadouts uses, into app/src/main/assets/items/.

Source: the OSRS Wiki's item tables (oldschool.runescape.wiki, text under CC BY-NC-SA 3.0), read through the
wiki's API: "infobox_item" (every item version: ID, name, picture) and "infobox_bonuses" (which equipment slot
it goes in). The pictures are Jagex's artwork from the wiki, included under Jagex's Fan Content Policy.

Makes:
  items/items.tsv  one line per item ID: ID, name, equipment slot (empty if it isn't worn), picture file(s).
                   Items whose picture changes with the stack size (coins, arrows...) list several pictures,
                   separated by "|"; the number at the end of each name ("Coins 250.png") is the smallest stack
                   it's for.
  items/icons/     every picture named in items.tsv. Characters Windows can't have in a file name are "_"
                   ("Guam in a box?.png" is saved as "Guam in a box_.png").

Run it again after a game update to pick up new items. Pictures already there are skipped; pictures no longer
needed are removed. Takes a few minutes the first time (about 16,000 pictures).

Usage: python3 build_items.py path/to/app/src/main/assets
"""
import html, json, os, re, sys, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

assets = sys.argv[1]
AGENT = 'OSRSBubbleTool item list builder (github.com/sinat50/OSRSBubbleTool)'
API = 'https://oldschool.runescape.wiki/api.php'
PICTURES = 'https://oldschool.runescape.wiki/images/'
# Follows renamed pictures, which the address above doesn't (slower, so only tried second)
RENAMED = 'https://oldschool.runescape.wiki/w/Special:Redirect/file/'
# The slot names the app uses (the same as the DPS tool's), from the wiki's names
SLOTS = {'head': 'head', 'cape': 'cape', 'neck': 'neck', 'ammo': 'ammo', 'weapon': 'weapon', '2h': 'weapon',
         'body': 'body', 'shield': 'shield', 'legs': 'legs', 'hands': 'hands', 'feet': 'feet', 'ring': 'ring'}

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': AGENT})
    return urllib.request.urlopen(req, timeout=60).read()

def table(name, fields):
    """Every row of one of the wiki's tables, 5,000 at a time"""
    rows, offset = [], 0
    while True:
        query = f"bucket('{name}').select({','.join(repr(f) for f in fields)}).limit(5000).offset({offset}).run()"
        part = json.loads(fetch(API + '?' + urllib.parse.urlencode({'action': 'bucket', 'format': 'json', 'query': query})))
        if 'bucket' not in part:
            sys.exit(f'The wiki refused the {name} query: {part}')
        rows += part['bucket']
        offset += len(part['bucket'])
        if len(part['bucket']) < 5000:
            return rows

def clean(text):
    # Web page bits some wiki names carry ("&#39;" for an apostrophe, a "[sic]" note) come out, and tabs and
    # line breaks would break the file's columns
    text = re.sub(r'<sup.*?</sup>', '', str(text))
    text = html.unescape(re.sub(r'<[^>]*>', '', text))
    return ' '.join(text.split())

items = table('infobox_item', ['page_name', 'page_name_sub', 'item_name', 'item_id', 'image'])
bonuses = table('infobox_bonuses', ['page_name', 'page_name_sub', 'equipment_slot'])

# Slot by page and version ("Page#Version"); or by page alone when the page has only one slot for all versions
slot_by_sub, slots_by_page = {}, {}
for b in bonuses:
    slot = SLOTS.get(str(b.get('equipment_slot', '')).strip().lower())
    if not slot:
        continue
    slot_by_sub[b.get('page_name_sub', '')] = slot
    slots_by_page.setdefault(b.get('page_name', ''), set()).add(slot)

# The name each picture is saved under: characters Windows can't have in a file name become "_", and names
# that differ only in capitals ("Unfinished batta (worm)" and "(Worm)", the same picture) share one file,
# since Windows sees them as one
saved_as = {}

def file_name(picture):
    safe = re.sub(r'[\\/:*?"<>|]', '_', picture)
    return saved_as.setdefault(safe.lower(), safe)

lines, pictures = {}, {}
for r in items:
    page = r.get('page_name', '')
    name = clean(r.get('item_name') or page)
    slot = slot_by_sub.get(r.get('page_name_sub', ''))
    if slot is None and len(slots_by_page.get(page, ())) == 1:
        slot = next(iter(slots_by_page[page]))
    files = [clean(p[len('File:'):]) for p in r.get('image', []) if p.startswith('File:')]
    for raw_id in r.get('item_id', []):
        try:
            item_id = int(str(raw_id).strip())
        except ValueError:
            continue   # a few pages hold notes instead of an ID ("beta", "N/A")
        if item_id not in lines:   # the first row wins if two pages claim one ID
            stored = [file_name(f) for f in files]
            pictures.update(zip(stored, files))   # only pictures an item uses are downloaded
            lines[item_id] = '\t'.join([str(item_id), name, slot or '', '|'.join(stored)])

folder = os.path.join(assets, 'items')
icons = os.path.join(folder, 'icons')
os.makedirs(icons, exist_ok=True)
with open(os.path.join(folder, 'items.tsv'), 'w', encoding='utf-8', newline='\n') as out:
    out.write('# OSRS item IDs, names, equipment slots and pictures, from the OSRS Wiki (CC BY-NC-SA 3.0). '
              'Built by tools/items/build_items.py\n')
    for item_id in sorted(lines):
        out.write(lines[item_id] + '\n')
print(len(lines), 'item IDs,', sum(1 for l in lines.values() if l.split('\t')[2]), 'worn items')

wanted = pictures   # saved name: wiki name
for f in os.listdir(icons):
    if f not in wanted:
        os.remove(os.path.join(icons, f))   # no longer needed

def save(item):
    name, picture = item
    path = os.path.join(icons, name)
    if os.path.exists(path):
        return True
    for address in (PICTURES, RENAMED):
        try:
            data = fetch(address + urllib.parse.quote(picture.replace(' ', '_')))
            if data[:8] == b'\x89PNG\r\n\x1a\n':   # only keep real pictures
                open(path, 'wb').write(data)
                return True
        except Exception:
            pass
    print('  could not get', picture)
    return False

with ThreadPoolExecutor(8) as pool:   # gentle on the wiki
    ok = sum(pool.map(save, wanted.items()))
size = sum(os.path.getsize(os.path.join(icons, f)) for f in os.listdir(icons))
print(f'items/icons: {ok} of {len(wanted)} pictures, {size / 1048576:.1f} MB')
