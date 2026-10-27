"""
Makes SyncTables.kt: what the app needs to read a Bubble Tool Sync code (the RuneLite plugin's QR code).
  - each quest's RuneLite quest ID (the plugin sends quests by ID, the app knows them by name)
  - each achievement diary task's place in the code: one bit of a diary varp, or (Karamja) a varbit

From the project folder, with the Quest Helper plugin and RuneLite's Quest.java downloaded somewhere:
  python tools/quest-helper/sync_tables.py QUEST_HELPER_FOLDER QUEST_JAVA

  QUEST_HELPER_FOLDER  a clone of github.com/Zoinkwiz/quest-helper (git clone --depth 1)
  QUEST_JAVA           runelite-api/src/main/java/net/runelite/api/Quest.java from github.com/runelite/runelite

Run it again whenever quests or diary tasks are added to QuestData.kt. It lists anything it couldn't match:
a quest without an ID shows no ✓/✗ for itself; a task without a bit shows no ✓.
The varp and varbit lists below must stay in the same order as the plugin's SyncData.java (format version 1).
"""
import os, re, sys, importlib.util

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('quest_files', os.path.join(HERE, 'quest_files.py'))
quest_files = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quest_files)
OUT = os.path.join(quest_files.CODE, 'SyncTables.kt')

# In the order the plugin sends them (SyncData.DIARY_VARPS and SyncData.KARAMJA_VARBITS)
VARPS = [f'{area}_ACHIEVEMENT_DIARY{n}' for area in
         ['ARDOUNGE', 'DESERT', 'FALADOR', 'FREMENNIK', 'KANDARIN', 'KOUREND', 'LUMB_DRAY', 'MORYTANIA',
          'VARROCK', 'WESTERN', 'WILDERNESS'] for n in ['', '2']] + ['ATJUN_TASKS_4']
KARAMJA = ('EASY_BANANA EASY_SWING EASY_GOLD EASY_BOAT_SARIM EASY_BOAT_ARDY EASY_CAIRN EASY_FISHING EASY_SEAWEED '
           'EASY_TZHAAR EASY_JOGRE HARD_FIGHTPITS HARD_FIGHTCAVE HARD_OOMLIE HARD_NATURE HARD_KARAMBWAN '
           'HARD_DEATHWING HARD_XBOW HARD_PALM HARD_DURADEL HARD_DRAGON MED_AGILITY MED_VOLCANO MED_CRANDOR MED_CART '
           'MED_CLEANUP MED_SPIDER MED_TOPAZ MED_TEAK MED_MAHOGANY MED_KARAMBWAN MED_MACHETTE MED_GLIDER MED_FARMING '
           'MED_GRAAHK MED_SHILO_VINES MED_SHILO_LAVA MED_SHILO_STAIRS MED_KHAZARD MED_CHARTER').split()
KARAMJA = ['ATJUN_' + k for k in KARAMJA]

# Quest Helper's folder for each of the app's diary regions
FOLDERS = {'Ardougne': 'ardougne', 'Desert': 'desert', 'Falador': 'falador', 'Fremennik': 'fremennik',
           'Kandarin': 'kandarin', 'Karamja': 'karamja', 'Kourend & Kebos': 'kourend',
           'Lumbridge & Draynor': 'lumbridgeanddraynor', 'Morytania': 'morytania', 'Varrock': 'varrock',
           'Western Provinces': 'westernprovinces', 'Wilderness': 'wilderness'}

# Tasks whose panel Quest Helper shows under a combined condition, so the script can't link them by itself.
# Checked by hand in Quest Helper (October 2026): (region, tier, task) -> (varp or varbit name, bit or "done at")
FIXES = {
    ('Ardougne', 'Medium', 'Castle Wars Balloon'): ('ARDOUNGE_ACHIEVEMENT_DIARY', 17),   # notBalloonCW
    ('Ardougne', 'Hard', 'Yanille POH'): ('ARDOUNGE_ACHIEVEMENT_DIARY2', 3),             # notYanPOH
    ('Karamja', 'Hard', 'Eat Oomlie Wrap'): ('ATJUN_HARD_OOMLIE', 1),                    # notEatenWrap (== 0)
    ('Varrock', 'Medium', 'Leave Varrock in a Balloon'): ('VARROCK_ACHIEVEMENT_DIARY', 27),   # notBalloon
}


def fix_code(name, n):
    return f'p{VARPS.index(name)}.{n}' if name in VARPS else f'k{KARAMJA.index(name)}.{n}'


VARP_REQ = re.compile(r'(\w+)\s*=\s*new VarplayerRequirement\(\s*VarPlayerID\.(\w+),\s*false,\s*(\d+)\s*\)')
VARBIT_REQ = re.compile(r'(\w+)\s*=\s*new VarbitRequirement\(\s*VarbitID\.(ATJUN_\w+),\s*(\d+)\s*(,\s*Operation\.LESS_EQUAL\s*)?\)')
PANEL = re.compile(r'(\w+)\s*=\s*new PanelDetails\(\s*"([^"]+)"')
SHOWN_IF = re.compile(r'(\w+)\.setDisplayCondition\(\s*(\w+)\s*\)')
LOCKED_PANEL = re.compile(r'PanelDetails\.lockedPanel\(\s*"([^"]+)",\s*(\w+)')


def simple(name):
    # Names compared without capitals, punctuation or a leading "The"
    name = re.sub(r'^the ', '', name.lower())
    return re.sub(r'[^a-z0-9]', '', name.replace('&', 'and'))


def task_codes(java):
    # Each task's panel title -> its code: "p<varp>.<bit>" (done when the bit is set) or
    # "k<varbit>.<at least>" (Karamja: done when the varbit reaches that number)
    needs = {}
    for var, varp, bit in VARP_REQ.findall(java):
        if varp in VARPS:
            needs[var] = f'p{VARPS.index(varp)}.{bit}'
    for var, varbit, value, less_equal in VARBIT_REQ.findall(java):
        if varbit in KARAMJA:
            # "not done" is "== 0" (done at 1+) or "<= 4" (done at 5+)
            needs[var] = f'k{KARAMJA.index(varbit)}.{int(value) + 1}'
    titles = {}
    panels = dict(PANEL.findall(java))
    for panel, cond in SHOWN_IF.findall(java):
        if panel in panels and cond in needs:
            titles[simple(panels[panel])] = needs[cond]
    for title, cond in LOCKED_PANEL.findall(java):
        if cond in needs:
            titles[simple(title)] = needs[cond]
    return titles


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    helper, quest_java = sys.argv[1], sys.argv[2]
    data = quest_files.load_data()
    problems = []

    # Quest IDs, matched by name
    with open(quest_java, encoding='utf-8') as f:
        runelite = {simple(n): int(i) for i, n in re.findall(r'^\s+[A-Z_0-9]+\((\d+),\s*"([^"]+)"', f.read(), re.M)}
    ids = []
    for q in data['quests']:
        i = runelite.get(simple(q['n']))
        if i is None:
            problems.append(f'quest without a RuneLite ID: {q["n"]}')
        else:
            ids.append((q['n'], i))
    # Recipe for Disaster's 10 parts aren't quests in the app's list, but each gives 1 quest point
    # (QuestBook.questPoints counts them while the whole quest isn't finished)
    with open(quest_java, encoding='utf-8') as f:
        parts = re.findall(r'^\s+[A-Z_0-9]+\((\d+),\s*"(Recipe for Disaster - [^"]+)"', f.read(), re.M)
    if len(parts) != 10:
        problems.append(f'expected 10 Recipe for Disaster parts in Quest.java, found {len(parts)}')
    ids += [(n, int(i)) for i, n in parts]

    # Diary tasks, matched by the task name (the app's names are Quest Helper's panel titles)
    diaries = []
    folder = os.path.join(helper, 'src', 'main', 'java', 'com', 'questhelper', 'helpers', 'achievementdiaries')
    for diary in data['diaries']:
        region = diary['n']
        for tier in diary['t']:
            name = tier['t']
            files = [f for f in os.listdir(os.path.join(folder, FOLDERS[region])) if f.endswith(name + '.java')]
            if len(files) != 1:
                problems.append(f'no Quest Helper file for {region} {name}')
                continue
            with open(os.path.join(folder, FOLDERS[region], files[0]), encoding='utf-8') as f:
                codes = task_codes(f.read())
            row = []
            for task in tier['k']:
                code = codes.get(simple(task['n']))
                fix = FIXES.get((region, name, task['n']))
                if code is None and fix:
                    code = fix_code(*fix)
                elif code is not None and fix:
                    problems.append(f'no longer needs its fix (remove it): {region} {name}: {task["n"]}')
                if code is None:
                    problems.append(f'diary task without a bit: {region} {name}: {task["n"]}')
                row.append(code or '-')
            if len(set(c for c in row if c != '-')) != len([c for c in row if c != '-']):
                problems.append(f'two tasks share a bit: {region} {name}')
            diaries.append((f'{region}|{name}', ' '.join(row)))

    lines = [
        'package com.sinat.osrsbubbletool',
        '',
        '// Made by tools/quest-helper/sync_tables.py: don\'t change by hand (run the script again instead).',
        '// What a Bubble Tool Sync code needs to be read: each quest\'s RuneLite quest ID, and where each diary',
        '// task sits ("p<varp>.<bit>": that bit of that varp is set; "k<varbit>.<n>": that Karamja varbit is at',
        '// least n; "-": not known). Tasks are in the same order as in QuestData.',
        'object SyncTables {',
        '    val QUEST_IDS: Map<String, Int> = mapOf(',
    ]
    lines += [f'        "{quest_files.kotlin(n)}" to {i},' for n, i in ids]
    lines += ['    )', '', '    val DIARY_TASKS: Map<String, String> = mapOf(']
    lines += [f'        "{k}" to "{v}",' for k, v in diaries]
    lines += ['    )', '}', '']
    with open(OUT, 'w', encoding='utf-8', newline='\r\n') as f:
        f.write('\n'.join(lines))

    tasks = sum(len(v.split()) for _, v in diaries)
    known = sum(len([c for c in v.split() if c != '-']) for _, v in diaries)
    print(f'{len(ids) - len(parts)} of {len(data["quests"])} quests have an ID (and {len(parts)} Recipe for Disaster parts); '
          f'{known} of {tasks} diary tasks have a bit')
    for p in problems:
        print('  ' + p)


if __name__ == '__main__':
    main()
