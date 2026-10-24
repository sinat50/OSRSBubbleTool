"""
Shows what changed on an OSRS Wiki page since an older revision, as plain text: "-" lines were removed,
"+" lines added, with [-removed-] and {+added+} marking the exact words inside a changed line.
The update checker prints the old revision in its "what changed" links (the oldid=... number).

Usage (from the project folder): python tools/wiki_diff.py "Page title" OLD_REVISION_NUMBER
Example: python tools/wiki_diff.py "Hunters' Rumours/Strategies" 15326217
"""
import json, re, sys, html, urllib.request, urllib.parse

API = 'https://oldschool.runescape.wiki/api.php'
AGENT = 'OSRSBubbleTool update check (github.com/sinat50/OSRSBubbleTool)'


def get(params):
    url = API + '?' + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={'User-Agent': AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode('utf-8'))


def text(cell):
    cell = re.sub(r'<del[^>]*>(.*?)</del>', r'[-\1-]', cell, flags=re.S)
    cell = re.sub(r'<ins[^>]*>(.*?)</ins>', r'{+\1+}', cell, flags=re.S)
    return html.unescape(re.sub(r'<[^>]+>', '', cell)).strip()


title, oldid = sys.argv[1], sys.argv[2]
d = get(dict(action='compare', fromrev=oldid, totitle=title, prop='diff|rel|user|comment|ids', format='json'))['compare']
print('to revision', d.get('torevid'), 'by', d.get('touser'), '|', d.get('tocomment'))
for row in re.findall(r'<tr>(.*?)</tr>', d['*'], re.S):
    cells = re.findall(r'<td class="([^"]*)"[^>]*>(.*?)</td>', row, re.S)
    for cls, cell in cells:
        if 'lineno' in cls:
            print('@@', text(cell))
        elif 'deletedline' in cls and text(cell):
            print('-', text(cell))
        elif 'addedline' in cls and text(cell):
            print('+', text(cell))
