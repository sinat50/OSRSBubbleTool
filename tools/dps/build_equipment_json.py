"""Builds app/src/main/assets/dps/equipment.json, the item list "Import my gear" recognises.

Source: the OSRS Wiki DPS calculator's repository (github.com/weirdgloop/osrs-dps-calc, GPL-3.0) at the
commit pinned in GearIcons.kt (COMMIT). Clone it and check out that commit first:
    git clone https://github.com/weirdgloop/osrs-dps-calc && cd osrs-dps-calc && git checkout <COMMIT>

Each item keeps id, name, version, slot, and "icon" (its picture's file name in cdn/equipment/). Items whose
picture is missing from the repository are left out. Pictures listed in wiki_icons.txt are marked "wiki": the
repository's copy is out of date, so the app downloads those from the OSRS Wiki instead.

If you move to a newer commit: update COMMIT in GearIcons.kt, rebuild this file, and check whether the
pictures in wiki_icons.txt are still out of date in the repository (compare them with the wiki's).

Usage: python3 build_equipment_json.py path/to/osrs-dps-calc path/to/equipment.json
"""
import json, os, sys

repo, out = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
wiki = {l.strip() for l in open(os.path.join(here, 'wiki_icons.txt'), encoding='utf-8') if l.strip() and not l.startswith('#')}
items = json.load(open(os.path.join(repo, 'cdn', 'json', 'equipment.json'), encoding='utf-8'))
rows = []
for o in items:
    if not os.path.exists(os.path.join(repo, 'cdn', 'equipment', o['image'])):
        continue
    e = {'id': o['id'], 'name': o['name'], 'version': o.get('version', ''), 'slot': o['slot'], 'icon': o['image']}
    if o['image'] in wiki:
        e['wiki'] = True
    rows.append(e)
json.dump(rows, open(out, 'w', encoding='utf-8'), separators=(',', ':'), ensure_ascii=False)
print(len(rows), 'items,', sum(1 for r in rows if r.get('wiki')), 'marked wiki')
