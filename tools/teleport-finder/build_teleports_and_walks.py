# Builds the Teleport Finder's data files from the Shortest Path RuneLite plugin
# (github.com/Skretzo/shortest-path, BSD 2-Clause), plus checked additions and corrections.
#   teleports.tsv : every teleport you can use from anywhere (or from any node of a network)
#   walks.txt     : doors, ladders, stairs, cave entrances, shortcuts, boats, portals and levers
#
# Usage: python3 build_teleports_and_walks.py path/to/shortest-path path/to/extra_walks.txt path/to/assets
#   shortest-path: a clone of github.com/Skretzo/shortest-path
#   extra_walks.txt: the joins made by find_closed_off_areas.py (kept in this folder)
#   assets: where teleports.tsv and walks.txt are written (app/src/main/assets)
import os, re, sys, collections

D = os.path.join(sys.argv[1], 'src', 'main', 'resources', 'transports') + os.sep
EXTRA = sys.argv[2]
os.chdir(sys.argv[3])

def read(fn):
    """Rows of a Shortest Path file as dicts, using its real header line (the one with tab-separated
    column names), plus the section comment each row sits under."""
    lines = open(D + fn, encoding='utf-8').read().split('\n')
    header = None
    for l in lines:
        if l.startswith('#') and '\t' in l and l[1:].split('\t')[0].strip() in ('Origin', 'Destination'):
            header = [h.strip() for h in l[1:].split('\t')]
            break
    assert header, fn
    rows, section = [], ''
    for l in lines:
        if not l.strip():
            continue
        if l.startswith('#'):
            if l[1:].split('\t')[0].strip() not in ('Origin', 'Destination'):
                section = l[1:].strip().strip('\t')
            continue
        f = l.split('\t')
        rows.append(({h: (f[i].strip() if i < len(f) else '') for i, h in enumerate(header)}, section))
    return rows

def xyz(s):
    m = re.match(r'^(\d+) (\d+) (\d+)$', s or '')
    return tuple(map(int, m.groups())) if m else None

# ---------------- Teleports ----------------

FILES = [  # file, category, how to use it
    ('teleportation_items.tsv', 'Item', ''),
    ('teleportation_spells.tsv', 'Spell', ''),
    ('teleportation_spells_home.tsv', 'Home teleport', ''),
    ('teleportation_minigames.tsv', 'Minigame teleport', 'Grouping (minigame) teleport'),
    ('quetzal_whistle.tsv', 'Item', ''),
    ('fairy_rings.tsv', 'Fairy ring', 'From any fairy ring'),
    ('spirit_trees.tsv', 'Spirit tree', 'From any spirit tree'),
    ('gnome_gliders.tsv', 'Gnome glider', 'From any gnome glider'),
    ('quetzals.tsv', 'Quetzal', 'From any quetzal landing site'),
    ('charter_ships.tsv', 'Charter ship', 'From a charter ship port'),
    ('magic_carpets.tsv', 'Magic carpet', 'From a magic carpet'),
    ('hot_air_balloons.tsv', 'Hot air balloon', 'From a balloon'),
    ('magic_mushtrees.tsv', 'Magic mushtree', 'From a magic mushtree on Fossil Island'),
    ('minecarts.tsv', 'Minecart', 'From a minecart station'),
    ('canoes.tsv', 'Canoe', 'From a canoe station'),
]
PREFIX = {'Spirit tree': 'Spirit tree: ', 'Gnome glider': 'Gnome glider: ', 'Quetzal': 'Quetzal: ',
          'Charter ship': 'Charter ship: ', 'Magic carpet': 'Magic carpet: ', 'Hot air balloon': 'Balloon: ',
          'Magic mushtree': 'Mushtree: ', 'Minecart': 'Minecart: ', 'Canoe': 'Canoe: '}

out, seen, stats = [], set(), collections.Counter()
for fn, cat, how in FILES:
    for r, section in read(fn):
        dest = xyz(r.get('Destination'))
        name = r.get('Display info', '')
        if not dest or not name:
            continue
        x, y, p = dest
        if cat == 'Fairy ring':
            if '-' in name:
                continue
            name = 'Fairy ring ' + name.replace(' ', '') if len(name) <= 5 else 'Fairy ring: ' + name.title()
        elif cat in PREFIX:
            name = PREFIX[cat] + re.sub(r'^\d+: ', '', name)
        name = re.sub(r'\s+', ' ', name)
        req = '; '.join(v for v in [r.get('Skills', '').replace(';', '; '), r.get('Quests', '').replace(';', '; ')] if v)
        # your house can be in several places, so house teleports can't be ranked
        if 'Teleport to House' in name or 'Tele to POH' in name or 'Your house' in name or name == 'Fairy ring DIQ':
            continue
        if name in seen:
            continue
        seen.add(name)
        stats[cat] += 1
        out.append([name, cat, x, y, p, req, how])

# Corrections: where the game really puts you (checked against crowdsourced landing spots or the walking map)
FIX = {
    # Shortest Path has 2965 4254; players land at the Corporeal Beast cave, 2965 4382 (plane 2)
    'Games necklace: Corporeal Beast': (2965, 4382, 2),
    # the Pyramid Plunder lobby is up on plane 3 (plane 0 there is solid)
    "Pharaoh's sceptre: Jalsavrah": (1934, 4428, 3),
    # "takes players to the entrance of the Sisterhood Sanctuary" (OSRS Wiki): that's on plane 1, 57 tiles from
    # the Nightmare. Plane 0 there is a closed-off pocket.
    "Drakan's medallion: Slepe": (3808, 9700, 1),
}
for row in out:
    if row[0] in FIX:
        row[2], row[3], row[4] = FIX[row[0]]

# Additions the plugin doesn't have yet. Landing tiles from RuneLite's world map
# (github.com/runelite/runelite, BSD 2-Clause), checked to be open tiles on the walking map.
ADD = [
    ['Spider cave teleport', 'Item', 3658, 3403, 0, 'Priest in Peril', 'Scroll. Lands at the Morytania Spider Cave entrance (Araxxor)'],
    ['Colossal wyrm teleport scroll', 'Item', 1641, 2921, 0, 'Children of the Sun', 'Scroll'],
    ['Chasm teleport scroll', 'Item', 1311, 9882, 0, '', 'Scroll. Lands by the Voice of Yama at the bottom of the Chasm of Fire'],
    ["Ghommal's hilt: God Wars Dungeon", 'Item', 2898, 3709, 0, '', 'Combat Achievements reward, 3 a day'],
    ["Ghommal's hilt: Mor Ul Rek", 'Item', 2554, 5130, 0, '', 'Combat Achievements reward'],
    ['Ape Atoll Teleport (Arceuus)', 'Spell', 2771, 9102, 0, '90 Magic', 'Arceuus spellbook. Lands in the Ape Atoll Dungeon'],
    ['Ape Atoll tablet (Arceuus)', 'Item', 2771, 9102, 0, '', 'Lands in the Ape Atoll Dungeon'],
    ['Lumbridge Graveyard Teleport', 'Spell', 3241, 3194, 0, '6 Magic', 'Arceuus spellbook'],
    ['Lumbridge Graveyard tablet', 'Item', 3241, 3194, 0, '', ''],
    ['Slayer ring: Wyrmscraig Cavern', 'Item', 2581, 8633, 0, 'Fallen From Grace', ''],
    ['Necklace of passage: Wyrmscraig', 'Item', 2591, 2221, 0, 'Fallen From Grace', ''],
    ['Ardeaglais teleport', 'Item', 2543, 2216, 0, 'Fallen From Grace', 'Scroll dropped by the Mad Angel'],
    ["Drakan's medallion: Castle Drakan", 'Item', 3557, 3358, 0, '', "Unlock by inspecting the shrine by the castle's front door (during The Blood Moon Rises)"],
    # Not in Shortest Path or RuneLite yet, and the wiki only says "Cam Torum": the spot is estimated, in the
    # middle of the city by the bank. Needs Perilous Moons up to killing the Sulphur Nagua (OSRS Wiki).
    ['Calcified moth', 'Item', 1447, 9566, 1, 'Perilous Moons', 'Crush it. Lands in Cam Torum (exact spot estimated)'],
]
names = {r[0] for r in out}
for a in ADD:
    if a[0] not in names:
        out.append(a)
        stats['added'] += 1

with open('teleports.tsv', 'w', encoding='utf-8') as fo:
    fo.write('# Teleport destinations, from the Shortest Path RuneLite plugin (github.com/Skretzo/shortest-path), BSD 2-Clause.\n')
    fo.write("# Plus newer teleports from RuneLite's world map (github.com/runelite/runelite), BSD 2-Clause.\n")
    fo.write('# name\tcategory\tx\ty\tplane\trequirements\thow\n')
    for r in out:
        fo.write('\t'.join(str(v) for v in r) + '\n')
print('teleports', len(out), dict(stats))

# ---------------- Walks ----------------

def layer(x, y):
    if x >= 4000: return 'S'
    if y < 4000: return 'U'
    if y >= 6400: return 'D'
    return 'S'

WALK_FILES = ['transports.tsv', 'agility_shortcuts.tsv', 'boats.tsv', 'ships.tsv',
              'teleportation_portals.tsv', 'teleportation_levers.tsv']
names_idx, name_list = {}, []
reqs_idx, req_list = {}, []     # what a shortcut, door or boat needs ("70 Agility; Regicide"), numbered from 0
rows, seenw = [], {}

def walk_req(r):
    """Skills and quests a walk needs, leaving out ones everybody has (level 1)."""
    skills = [v.strip() for v in r.get('Skills', '').split(';') if v.strip() and not re.match(r'^1 ', v.strip())]
    quests = [v.strip() for v in r.get('Quests', '').split(';') if v.strip()]
    return '; '.join(skills + quests)
for fn in WALK_FILES:
    for r, section in read(fn):
        a, b = xyz(r.get('Origin')), xyz(r.get('Destination'))
        if not a or not b:
            continue
        what = re.sub(r'\s+\d+$', '', r.get('menuOption menuTarget objectID', '')).strip()
        if fn in ('ships.tsv', 'boats.tsv') and r.get('Display info'):
            what = 'Boat to ' + re.sub(r'^\d+: ', '', r['Display info'])
        dur = r.get('Duration', '')
        ticks = int(dur) if dur.isdigit() else (8 if fn in ('ships.tsv', 'boats.tsv') else 2)
        far = 1 if layer(a[0], a[1]) != layer(b[0], b[1]) or max(abs(a[0] - b[0]), abs(a[1] - b[1])) > 30 else 0
        # your house: every house portal leads to the same inside, so linking them would let the search
        # walk in at one town and out at another
        def in_house(p): return 1800 <= p[0] <= 2100 and 5600 <= p[1] <= 5850
        if in_house(a) or in_house(b) or 'Home Portal' in what or 'Portal Home' in what:
            continue
        req = walk_req(r)
        key = (a, b)
        if key in seenw:
            # the same way twice (e.g. two menu options): keep the one that needs least
            i = seenw[key]
            if req or not rows[i][-1]:
                continue
            rows[i][-1] = ''
            continue
        if what not in names_idx:
            names_idx[what] = len(name_list)
            name_list.append(what)
        seenw[key] = len(rows)
        rows.append([a, b, ticks, far, names_idx[what], req])

# Cave and boss-room entrances the walking map is missing, so these places get measured routes instead of
# estimates. Entrance and arrival tiles checked against the crowdsourced transport data of the RuneScape map
# project (github.com/mejrs/data_osrs). from, to, name, requirements.
CAVES = [
    ((2412, 3060, 0), (2404, 9415, 0), 'Enter Smoky cave', ''),                 # Smoke Devil Dungeon
    ((3226, 6046, 0), (3225, 12445, 0), 'Enter Cave', ''),                      # Iorwerth Dungeon (Prifddinas)
    ((3283, 6059, 0), (3033, 6068, 0), 'Channel Teleport Platform', ''),        # to Zalcano's prison
    ((3033, 6063, 0), (3033, 6059, 0), 'Pass Barrier', 'Song of the Elves'),    # Zalcano
    ((1311, 3807, 0), (1311, 10188, 0), 'Activate Elevator', ''),               # Karuulm Slayer Dungeon
    ((1316, 10213, 0), (1346, 10232, 0), 'Enter Mysterious pipe', '88 Agility'),  # to the Alchemical Hydra
    ((1269, 10175, 0), (1269, 10170, 0), 'Jump Lava gap', ''),                  # Karuulm wyrms
    ((1309, 3573, 0), (1305, 9973, 0), 'Enter Lizardman lair', ''),             # Lizardman Caves
    ((1305, 9957, 0), (1305, 9953, 0), 'Squeeze-through Crevice', ''),
    ((2697, 9436, 0), (2684, 9436, 0), 'Enter Crevice', ''),                    # Brimhaven Dungeon dragons
    ((2637, 9517, 0), (2636, 9510, 2), 'Walk-up Stairs', ''),                   # Brimhaven Dungeon upstairs
    ((1778, 5346, 0), (1778, 5343, 1), 'Climb-up Stairs', ''),                  # Ancient Cavern, mithril dragons
    ((2885, 5334, 2), (2885, 5345, 2), 'Climb-off Ice bridge', '70 Hitpoints'), # God Wars Dungeon, Zamorak side
    # Waterbirth Island Dungeon down to the Dagannoth Kings
    ((2544, 3741, 0), (2545, 10143, 0), 'Enter Cave entrance', ''),
    ((2545, 10143, 0), (1799, 4406, 3), 'Climb-down Iron ladder', ''),
    ((1807, 4405, 3), (1810, 4405, 2), 'Climb-down Ladder', ''),
    ((1822, 4404, 2), (1826, 4404, 3), 'Climb-up Ladder', ''),
    ((1834, 4390, 3), (1834, 4387, 2), 'Climb-down Ladder', ''),
    ((1812, 4394, 2), (1810, 4393, 1), 'Climb-down Ladder', ''),
    ((1799, 4389, 1), (1799, 4385, 2), 'Climb-up Ladder', ''),
    ((1797, 4383, 2), (1797, 4382, 1), 'Climb-down Ladder', ''),
    ((1802, 4370, 1), (1800, 4369, 2), 'Climb-up Ladder', ''),
    ((1825, 4362, 2), (1828, 4362, 1), 'Climb-down Ladder', ''),
    ((1863, 4370, 1), (1863, 4374, 2), 'Climb-up Ladder', ''),
    ((1864, 4387, 2), (1864, 4390, 1), 'Climb-down Ladder', ''),
    ((1889, 4407, 1), (1890, 4409, 0), 'Climb-down Ladder', ''),
    ((1912, 4367, 0), (2900, 4449, 0), "Climb-down Kings' ladder", ''),
    ((1912, 4367, 0), (2900, 4385, 0), "Climb-down Kings' ladder (Slayer)", ''),
]
# Thin barriers inside those caves (a web, a crevice, a gate), joined as estimates
CAVE_BARRIERS = [
    ((1303, 10203, 0), (1301, 10205, 0)),   # Karuulm wyrms
    ((2379, 9451, 0), (2376, 9448, 0)),     # Thermonuclear smoke devil
    ((2925, 5333, 2), (2923, 5331, 2)),     # K'ril Tsutsaroth
]
for a, b, what, req in CAVES:
    if (a, b) in seenw:
        continue
    if what not in names_idx:
        names_idx[what] = len(name_list)
        name_list.append(what)
    far = 1 if layer(a[0], a[1]) != layer(b[0], b[1]) or max(abs(a[0] - b[0]), abs(a[1] - b[1])) > 30 else 0
    seenw[(a, b)] = len(rows)
    rows.append([a, b, 2, far, names_idx[what], req])
# Ways in that aren't in any data we have, placed from RuneLite's world map: estimates
GUESSED_WAYS = [
    ((1439, 9599, 1), (1439, 9602, 0), 'Enter Neypotzli'),   # Moons of Peril, from the north end of Cam Torum
]
for a, b, what in GUESSED_WAYS:
    if what not in names_idx:
        names_idx[what] = len(name_list)
        name_list.append(what)
    seenw[(a, b)] = len(rows)
    rows.append([a, b, 2, 1, names_idx[what], '', 1])
for a, b in CAVE_BARRIERS:
    what = 'Get past the barrier'
    if what not in names_idx:
        names_idx[what] = len(name_list)
        name_list.append(what)
    seenw[(a, b)] = len(rows)
    rows.append([a, b, 6, 0, names_idx[what], '', 1])
print('caves added', len(CAVES), 'barriers', len(CAVE_BARRIERS))

# Joins found by the connected-area analysis (find_closed_off_areas.py): areas the walking map has no way into, joined
# to the nearest reachable area through a known obstacle (from the Golems Don't Die plugin's obstacle
# list, github.com/Varzeki/golems-dont-die, BSD 2-Clause) or across a thin barrier. The barrier ones are
# estimates. One way only: from the reachable side into the closed-off area.
extra = 0
for l in open(EXTRA):
    k, *f = l.split()
    f = list(map(int, f))
    a, b = tuple(f[0:3]), tuple(f[3:6])
    if (a, b) in seenw:
        continue
    what = 'Go through the obstacle' if k == 'O' else 'Get past the barrier'
    if what not in names_idx:
        names_idx[what] = len(name_list)
        name_list.append(what)
    ticks = f[6] if k == 'O' else 4 + f[6]
    seenw[(a, b)] = len(rows)
    rows.append([a, b, ticks, 0, names_idx[what], '', 1 if k == 'T' else 0])
    extra += 1
print('joins added', extra)

lines = []
for row in rows:
    a, b, ticks, far, nm, req = row[:6]
    guess = row[6] if len(row) > 6 else 0
    if req and req not in reqs_idx:
        reqs_idx[req] = len(req_list)
        req_list.append(req)
    ri = reqs_idx[req] if req else -1
    lines.append(f"{a[0]} {a[1]} {a[2]} {b[0]} {b[1]} {b[2]} {ticks} {far} {nm} {ri} {guess}")
rows = lines
with open('walks.txt', 'w', encoding='utf-8') as fo:
    fo.write('# Doors, ladders, stairs, cave entrances, shortcuts, boats, portals and levers, from the Shortest Path\n')
    fo.write('# RuneLite plugin (github.com/Skretzo/shortest-path), BSD 2-Clause.\n')
    fo.write('# "@ name" lines are the names, numbered from 0. Other lines: from x y plane, to x y plane, ticks,\n')
    fo.write('# goes somewhere far or into another area (1/0), name number, requirement number (-1 = none),\n')
    fo.write('# estimate (1/0). "% requirement" lines are the requirements, numbered from 0.\n')
    fo.write('# Plus joins into areas the walking map has no way into, from obstacle locations in the Golems Don\'t Die\n')
    fo.write('# RuneLite plugin (github.com/Varzeki/golems-dont-die), BSD 2-Clause, and cave entrances checked against the\n')
    fo.write('# crowdsourced transport data of the RuneScape map project (github.com/mejrs/data_osrs).\n')
    for n in name_list:
        fo.write('@ ' + n + '\n')
    for q in req_list:
        fo.write('% ' + q + '\n')
    fo.write('\n'.join(rows) + '\n')
print('walks', len(rows), 'names', len(name_list), 'requirements', len(req_list), os.path.getsize('walks.txt'))
