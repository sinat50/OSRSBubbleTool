"""Builds the item list and item pictures Gear Loadouts uses, into app/src/main/assets/items/.

Source: the OSRS Wiki's item tables (oldschool.runescape.wiki, text under CC BY-NC-SA 3.0), read through the
wiki's API: "infobox_item" (every item version: ID, name, picture) and "infobox_bonuses" (which equipment slot
it goes in). The pictures are Jagex's artwork from the wiki, included under Jagex's Fan Content Policy.

Makes:
  items/items.tsv  one line per item ID: ID, name, equipment slot (empty if it isn't worn), picture file(s),
                   and the wiki page if it isn't the item's name (minigame and quest copies have their own page).
                   Items whose picture changes with the stack size (coins, arrows...) list several pictures,
                   separated by "|"; the number at the end of each name ("Coins 250.png") is the smallest stack
                   it's for.
  items/spellbooks/ the spellbook tab pictures, 0.png to 3.png (standard, Ancient, Lunar, Arceuus).
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
            # the wiki page, when it isn't the item's name: copies of an item from a minigame or quest are on
            # their own page ("Rune pouch (Last Man Standing)") but have the same name in the game
            page_note = clean(page) if clean(page) != name else ''
            lines[item_id] = '\t'.join([str(item_id), name, slot or '', '|'.join(stored), page_note])

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

# The spellbook tab's four pictures (Gear Loadouts shows the loadout's spellbook with them),
# saved as items/spellbooks/0.png to 3.png in RuneLite's order: standard, Ancient, Lunar, Arceuus
books = os.path.join(folder, 'spellbooks')
os.makedirs(books, exist_ok=True)
for number, picture in enumerate(['Standard spellbook.png', 'Ancient spellbook.png', 'Lunar spellbook.png', 'Arceuus spellbook.png']):
    data = fetch(PICTURES + urllib.parse.quote(picture.replace(' ', '_')))
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        sys.exit('Could not get ' + picture)
    open(os.path.join(books, f'{number}.png'), 'wb').write(data)
print('items/spellbooks: 4 pictures')

# Noted items: each item that can be noted, and its noted version's ID (a noted item is its own item in the game,
# drawn on a bank note). From RuneLite's list of the game's item IDs (github.com/runelite/runelite,
# net/runelite/api/gameval/ItemID.java, BSD 2-Clause): its "Cert" list holds the noted IDs, under the same names
# as the items themselves. items/noted.tsv: item ID, noted ID.
RUNELITE_ITEMS = 'https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/gameval/ItemID.java'
source = fetch(RUNELITE_ITEMS).decode('utf-8', 'replace')
cert_at = source.find('class Cert')
if cert_at < 0:
    sys.exit('RuneLite item list: no Cert (noted) section found')
constant = re.compile(r'public static final int (\w+)\s*=\s*(\d+)\s*;')
plain_ids = {n: int(v) for n, v in constant.findall(source[:cert_at])}
cert_part = source[cert_at:].split('public static final class', 1)[0]   # up to the next list
noted_ids = {n: int(v) for n, v in constant.findall(cert_part)}
noted_pairs = sorted((plain_ids[n], v) for n, v in noted_ids.items() if n in plain_ids and plain_ids[n] in lines)
with open(os.path.join(folder, 'noted.tsv'), 'w', encoding='utf-8', newline='\n') as out:
    out.write('# Item ID and its noted version\'s ID, from RuneLite\'s item list (BSD 2-Clause). Built by tools/items/build_items.py\n')
    for plain, noted in noted_pairs:
        out.write(f'{plain}\t{noted}\n')
print('items/noted.tsv:', len(noted_pairs), 'notable items')

# Each picture's shape and colours in brief (items/shapes.bin, see item_shapes.py). build_icons.py, run next, keeps
# only the pictures still needed and writes this again for them.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from item_shapes import write_shapes
write_shapes(folder, wanted)
