"""
Lists equipment (anything with combat bonuses on the OSRS Wiki) that the app doesn't know yet, in two places:
  - Gear Loadouts' item list (app/src/main/assets/items/items.tsv, made by build_items.py)
  - the DPS Calculator's item list (app/src/main/assets/dps/equipment.json, from the wiki's DPS calculator)
Read-only: it changes nothing. How to bring new items in: docs/gear-items.md.
The update checker (tools/update-check) runs this too, as its "new-gear" source.

Usage (from the project folder): python tools/items/check_new_gear.py
"""
import datetime, json, os, sys, urllib.parse, urllib.request

API = 'https://oldschool.runescape.wiki/api.php'
AGENT = 'OSRSBubbleTool update check (github.com/sinat50/OSRSBubbleTool)'
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets')


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': AGENT})
    return urllib.request.urlopen(req, timeout=60).read()


def table(name, fields):
    # Every row of one of the wiki's tables, 5,000 at a time (the same query build_items.py uses)
    rows, offset = [], 0
    while True:
        query = f"bucket('{name}').select({','.join(repr(f) for f in fields)}).limit(5000).offset({offset}).run()"
        part = json.loads(fetch(API + '?' + urllib.parse.urlencode({'action': 'bucket', 'format': 'json', 'query': query})))
        if 'bucket' not in part:
            raise RuntimeError(f'the wiki refused the {name} query: {part}')
        rows += part['bucket']
        offset += len(part['bucket'])
        if len(part['bucket']) < 5000:
            return rows


def released(text):
    # The wiki's release dates look like "20 October 2026" (sometimes with links); None if it can't be read
    text = text.replace('[', '').replace(']', '').strip()
    try:
        return datetime.datetime.strptime(text, '%d %B %Y').date()
    except ValueError:
        return None


def missing_gear():
    """(not in Gear Loadouts, newer than anything in the DPS list): each a list of (ID, name, release date text).
    The DPS list leaves out many cosmetic and duplicate copies on purpose, so only items with a higher ID than
    any it has count as missing there."""
    worn_pages = {r['page_name'] for r in table('infobox_bonuses', ['page_name'])}
    ids = {}
    for r in table('infobox_item', ['page_name', 'item_name', 'item_id', 'release_date']):
        if r.get('page_name') not in worn_pages:
            continue
        for raw in r.get('item_id', []):
            try:
                ids[int(str(raw).strip())] = (r.get('item_name') or r['page_name'], r.get('release_date', ''))
            except ValueError:
                pass
    with open(os.path.join(ASSETS, 'items', 'items.tsv'), encoding='utf-8') as f:
        loadouts = {int(l.split('\t')[0]) for l in f if l[:1].isdigit()}
    with open(os.path.join(ASSETS, 'dps', 'equipment.json'), encoding='utf-8') as f:
        dps = {e['id'] for e in json.load(f)}
    newest_dps = max(dps)
    return ([(i, *ids[i]) for i in sorted(ids) if i not in loadouts],
            [(i, *ids[i]) for i in sorted(ids) if i not in dps and i > newest_dps])


if __name__ == '__main__':
    try:
        loadouts, dps = missing_gear()
    except RuntimeError as e:
        sys.exit(str(e))
    print(f'Not in Gear Loadouts ({len(loadouts)}): fixed by running build_items.py, then build_icons.py')
    for i, name, date in loadouts:
        print(f'  {i}\t{name}\t{date}')
    print(f'\nNewer than anything in the DPS Calculator list ({len(dps)}): arrive when the wiki DPS calculator adds them')
    for i, name, date in dps:
        print(f'  {i}\t{name}\t{date}')
