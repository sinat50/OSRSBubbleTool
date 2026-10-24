"""
Read and change the Quest Helper's data files: QuestData.kt (each quest's requirements, for the quest list)
and QuestGuides.kt (each quest's step-by-step guide). Both keep their data as JSON text inside Kotlin strings.
How the JSON is laid out, and how it's made from the Quest Helper plugin's Java: docs/quest-helper.md.

From the project folder:
  python tools/quest-helper/quest_files.py show "Quest name"        print the quest's data and guide as JSON
  python tools/quest-helper/quest_files.py put  quests/file.json    add the quest, or replace it if it's there
  python tools/quest-helper/quest_files.py check                    decode everything, check names match up

The file for "put" holds {"data": {...the QuestData entry...}, "guide": {...the QuestGuides entry...}}.
Either one can be left out to change only the other file. Quests are kept in alphabetical order.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'java', 'com', 'sinat', 'osrsbubbletool')
DATA = os.path.join(CODE, 'QuestData.kt')
GUIDES = os.path.join(CODE, 'QuestGuides.kt')

PIECE = r'"((?:[^"\\]|\\.)*)"'
# One guide in the "when": a single string, or (for long guides) arrayOf(pieces).joinToString("")
GUIDE_LINE = re.compile(r'^        "((?:[^"\\]|\\.)*)" -> (?:arrayOf\(\n(.*?)\n        \)\.joinToString\(""\)|' + PIECE + r')\n',
                        re.S | re.M)
LONGEST = 30000   # longer guides are split into pieces (Kotlin's limit for one string is 65,535 bytes)


def read(path):
    # Line endings become \n while working; write() puts back whatever the file had (Windows uses \r\n)
    with open(path, encoding='utf-8', newline='') as f:
        text = f.read()
    ENDINGS[path] = '\r\n' if '\r\n' in text else '\n'
    return text.replace('\r\n', '\n')


ENDINGS = {}


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text.replace('\n', ENDINGS.get(path, '\n')))


def unkotlin(piece):
    # A Kotlin string's inside back to plain text (only \" \\ and \$ are used)
    return re.sub(r'\\(.)', r'\1', piece)


def kotlin(text):
    return text.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')


def compact(obj):
    return json.dumps(obj, separators=(',', ':'), ensure_ascii=False)


def sort_key(name):
    # QuestGuides.kt: plain alphabetical order
    return name


def data_key(name):
    # QuestData.kt: alphabetical ignoring capitals and a "The " at the start (as the plugin's quest list does)
    return (name[4:] if name.startswith('The ') else name).lower()


# ---- QuestData.kt ----

def data_pieces(text):
    m = re.search(r'(    private val PARTS = arrayOf\(\n)(.*?)(\n    \)\n)', text, re.S)
    return m, re.findall(PIECE, m.group(2))


def load_data():
    text = read(DATA)
    _, pieces = data_pieces(text)
    return json.loads(''.join(unkotlin(p) for p in pieces))


def put_data(entry):
    text = read(DATA)
    m, pieces = data_pieces(text)
    joined = ''.join(unkotlin(p) for p in pieces)
    root = json.loads(joined)
    names = [q['n'] for q in root['quests']]
    new = compact(entry)
    if entry['n'] in names:
        old = compact(root['quests'][names.index(entry['n'])])
        if joined.count(old) != 1:
            sys.exit('Could not find the old entry exactly once in QuestData.kt')
        start, end = joined.index(old), joined.index(old) + len(old)
    else:
        after = [n for n in names if data_key(n) < data_key(entry['n'])]
        if after:
            prev = compact(root['quests'][names.index(after[-1])])
            start = end = joined.index(prev) + len(prev)
            new = ',' + new
        else:
            start = end = joined.index('[') + 1
            new = new + ','
    # Only the piece the change lands in is touched, so the rest of the file stays as it was
    pos, out, done = 0, [], False
    for p in pieces:
        plain = unkotlin(p)
        if not done and pos <= start <= pos + len(plain) and end <= pos + len(plain):
            a, b = start - pos, end - pos
            out += [kotlin(plain[:a] + new), kotlin(plain[b:])] if plain[b:] else [kotlin(plain[:a] + new)]
            done = True
        elif not done and pos <= start < pos + len(plain):
            sys.exit('The change crosses two pieces of QuestData.kt: do it by hand')
        else:
            out.append(p)
        pos += len(plain)
    body = ',\n'.join('        "' + p + '"' for p in out)
    text = text[:m.start(2)] + body + text[m.end(2):]
    write(DATA, text)
    if compact(load_data()['quests'][[q['n'] for q in load_data()['quests']].index(entry['n'])]) != compact(entry):
        sys.exit('QuestData.kt did not read back the same: check it')


# ---- QuestGuides.kt ----

def guide_texts(text):
    out = {}
    for m in GUIDE_LINE.finditer(text):
        pieces = re.findall(PIECE, m.group(2)) if m.group(2) is not None else [m.group(3)]
        out[unkotlin(m.group(1))] = (m, ''.join(unkotlin(p) for p in pieces))
    return out


def guide_kotlin(name, guide):
    s = kotlin(compact(guide))
    if len(s) <= LONGEST:
        return '        "' + kotlin(name) + '" -> "' + s + '"\n'
    plain, parts, cur = compact(guide), [], ''
    for ch in plain:
        cur += ch
        if len(cur) >= LONGEST:
            parts.append(cur); cur = ''
    parts.append(cur)
    body = ',\n'.join('            "' + kotlin(p) + '"' for p in parts if p)
    return '        "' + kotlin(name) + '" -> arrayOf(\n' + body + '\n        ).joinToString("")\n'


def put_guide(guide):
    name = guide['n']
    text = read(GUIDES)
    guides = guide_texts(text)
    line = guide_kotlin(name, guide)
    if name in guides:
        m = guides[name][0]
        text = text[:m.start()] + line + text[m.end():]
    else:
        later = sorted((n for n in guides if sort_key(n) > sort_key(name)), key=sort_key)
        at = guides[later[0]][0].start() if later else text.index('        else -> null')
        text = text[:at] + line + text[at:]
        # The names list: add the name in its place
        m = re.search(r'(    val names: Set<String> = setOf\(\n)(.*?)(\n    \)\n)', text, re.S)
        names = [unkotlin(n) for n in re.findall(PIECE, m.group(2))] + [name]
        names.sort(key=sort_key)
        body = ',\n'.join('        "' + kotlin(n) + '"' for n in names)
        text = text[:m.start(2)] + body + text[m.end(2):]
    write(GUIDES, text)
    if json.loads(guide_texts(read(GUIDES))[name][1]) != guide:
        sys.exit('QuestGuides.kt did not read back the same: check it')


def check():
    data = load_data()
    text = read(GUIDES)
    guides = guide_texts(text)
    m = re.search(r'val names: Set<String> = setOf\(\n(.*?)\n    \)\n', text, re.S)
    names = [unkotlin(n) for n in re.findall(PIECE, m.group(1))]
    for n, (_, t) in guides.items():
        g = json.loads(t)
        if g['n'] != n: print('Guide name differs from its key:', n)
    if sorted(names) != sorted(guides): print('The names list and the guides differ:', set(names) ^ set(guides))
    ours = [q['n'] for q in data['quests']]
    if ours != sorted(ours, key=data_key): print('QuestData quests are out of order')
    if names != sorted(names, key=sort_key): print('QuestGuides names are out of order')
    print(len(ours), 'quests in QuestData,', len(guides), 'guides,', len(data['diaries']), 'diaries')
    print('Quests without a guide:', [n for n in ours if n not in guides])


if __name__ == '__main__':
    if len(sys.argv) < 2 or sys.argv[1] not in ('show', 'put', 'check'):
        sys.exit(__doc__)
    if sys.argv[1] == 'show':
        name = sys.argv[2]
        data = [q for q in load_data()['quests'] if q['n'] == name]
        guide = guide_texts(read(GUIDES)).get(name)
        print(json.dumps({'data': data[0] if data else None, 'guide': json.loads(guide[1]) if guide else None},
                         indent=1, ensure_ascii=False))
    elif sys.argv[1] == 'put':
        with open(sys.argv[2], encoding='utf-8') as f:
            q = json.load(f)
        if 'data' in q: put_data(q['data'])
        if 'guide' in q: put_guide(q['guide'])
        print('Done:', (q.get('data') or q.get('guide'))['n'])
    else:
        check()
