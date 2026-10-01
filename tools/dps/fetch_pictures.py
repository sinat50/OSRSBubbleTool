"""Downloads the game pictures the app comes with, into app/src/main/assets/.

They're Jagex's artwork, included under Jagex's Fan Content Policy (see the app's Legal screen):
- dps/icons/: every item picture "Import my gear" compares your equipment with, one per "icon" in
  dps/equipment.json, from the OSRS Wiki DPS calculator's repository (github.com/weirdgloop/osrs-dps-calc)
  at the commit pinned in GearIcons.kt (COMMIT). Ones marked "wiki" (out of date in the repository) come
  from the OSRS Wiki instead, or the repository's copy if the wiki doesn't have them.
- puzzles/: the solved puzzle box pictures for the Puzzle Box Solver, from the OSRS Wiki.
- quest_images/: pictures the Quest Helper shows with a puzzle's answer, from the OSRS Wiki.

Run it again after rebuilding equipment.json (new items) or adding a picture to PUZZLES or QUEST_IMAGES
below (and in PuzzleReferences.kt / QuestPuzzles.kt). Pictures already there are skipped; pictures no longer
needed are removed.

Usage: python3 fetch_pictures.py path/to/app/src/main/assets
"""
import json, os, re, sys, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

assets = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
gear_icons = open(os.path.join(here, '..', '..', 'app', 'src', 'main', 'java', 'com', 'sinat', 'osrsbubbletool', 'GearIcons.kt'), encoding='utf-8').read()
COMMIT = re.search(r'COMMIT = "([0-9a-f]{40})"', gear_icons).group(1)

WIKI = 'https://oldschool.runescape.wiki/images/'
GITHUB = f'https://raw.githubusercontent.com/weirdgloop/osrs-dps-calc/{COMMIT}/cdn/equipment/'
JSDELIVR = f'https://cdn.jsdelivr.net/gh/weirdgloop/osrs-dps-calc@{COMMIT}/cdn/equipment/'
PUZZLES = ['Castle_puzzle_solved.png', 'Tree_puzzle_solved.png', 'Troll_puzzle_solved.png', 'Zulrah_puzzle_solved.png',
           'Cerberus_puzzle_solved.png', 'Gnome_child_puzzle_solved.png', 'Theatre_of_Blood_puzzle_solved.png']
QUEST_IMAGES = ['Dragon_Slayer_II_map_puzzle_solution.png']
AGENT = 'OSRSBubbleTool picture builder (github.com/sinat50/OSRSBubbleTool)'

def q(name):
    return urllib.parse.quote(name)

def get(url):
    try:
        req = urllib.request.Request(url, headers={'User-Agent': AGENT})
        data = urllib.request.urlopen(req, timeout=30).read()
        return data if data[:8] == b'\x89PNG\r\n\x1a\n' else None   # only keep real pictures
    except Exception:
        return None

def save(folder, name, urls):
    path = os.path.join(assets, folder, name.replace('/', '_'))
    if os.path.exists(path):
        return True
    for url in urls:
        data = get(url)
        if data:
            open(path, 'wb').write(data)
            return True
    print('  could not get', name)
    return False

def build(folder, wanted, threads):
    os.makedirs(os.path.join(assets, folder), exist_ok=True)
    keep = {n.replace('/', '_') for n in wanted}
    for f in os.listdir(os.path.join(assets, folder)):
        if f not in keep:
            os.remove(os.path.join(assets, folder, f))   # no longer needed
    with ThreadPoolExecutor(threads) as pool:
        ok = sum(pool.map(lambda kv: save(folder, kv[0], kv[1]), wanted.items()))
    size = sum(os.path.getsize(os.path.join(assets, folder, f)) for f in os.listdir(os.path.join(assets, folder)))
    print(f'{folder}: {ok} of {len(wanted)} pictures, {size / 1048576:.1f} MB')

items = json.load(open(os.path.join(assets, 'dps', 'equipment.json'), encoding='utf-8'))
from_wiki = {}
for o in items:
    from_wiki[o['icon']] = from_wiki.get(o['icon'], False) or o.get('wiki', False)
icons = {}
for name, wiki in from_wiki.items():
    repo = [GITHUB + q(name), JSDELIVR + q(name)]
    icons[name] = ([WIKI + q(name.replace(' ', '_'))] if wiki else []) + repo

build('dps/icons', icons, 32)
build('puzzles', {n: [WIKI + n] for n in PUZZLES}, 4)
build('quest_images', {n: [WIKI + n] for n in QUEST_IMAGES}, 4)
