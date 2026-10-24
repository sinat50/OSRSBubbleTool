# Gear Loadouts and DPS Calculator: updating the item lists

The two tools have separate item lists from separate sources. Last done 24 October 2026 (The Fractured Archive gear).

| List | Used for | From | Built by |
| --- | --- | --- | --- |
| `assets/items/items.tsv`, `noted.tsv`, `icons/`, `shapes.bin` | Gear Loadouts: every item's ID, name, slot, wiki picture | OSRS Wiki item tables (API "bucket" queries) | `tools/items/build_items.py` |
| `assets/items/canvas/`, `canvas.tsv`, `canvas.bin` | Gear Loadouts' screen reading, and the DPS tool's Import my gear (which reads gear the same way) | RuneLite's item icons (static.runelite.net) | `tools/items/build_icons.py` |
| `assets/dps/equipment.json`, `dps/icons/` | The DPS tool's own item list: which items the calculator can take | The wiki DPS calculator repository (weirdgloop/osrs-dps-calc) at `GearIcons.COMMIT` | `tools/dps/build_equipment_json.py`, `tools/dps/fetch_pictures.py` |

## Is anything new?

The update checker's `new-gear` source lists equipment on the wiki that either list lacks, grouped by release
date (the same check as `python tools/items/check_new_gear.py`, read-only, about a minute). Gear Loadouts' missing
items always show until they're added. The DPS Calculator's can't be added until the wiki's calculator has them,
so once seen, `--checked new-gear` hides those released before that day (newer gear still shows). The `dps-items`
source says when the calculator's own item list changes.

## Gear Loadouts (any time the check lists items)

1. `python -I tools/items/build_items.py app/src/main/assets` (about 6½ minutes: it fetches all ~15,000 wiki
   pictures again, which the next step trims back to ~1,750).
2. **Right after**, `python -I tools/items/build_icons.py app/src/main/assets` (needs `pip install pillow`).
3. Run `check_new_gear.py` again: the Gear Loadouts list should be empty. RuneLite's icons for brand-new items can
   lag a few days; those items still work with their wiki picture, and a later rerun picks the icons up.
4. Build and run the PC tests (`InventoryReaderTest` and friends use the owner's screenshots, skipped without them).
5. Git shows "LF will be replaced by CRLF" for the `.tsv` files: harmless.

## DPS Calculator (only when the calculator itself has the items)

The DPS tool loads your gear into the wiki's calculator by item ID, so an item the calculator doesn't know can't
be used there, whatever the app knows. Until the calculator adds new gear, Import my gear reads it (through Gear
Loadouts' icons) and says "Read as X, which the calculator doesn't have": that's correct, nothing to fix.

When the update checker reports `dps-items` (the repository's `cdn/json/equipment.json` changed after
`GearIcons.COMMIT`): move `COMMIT` in `GearIcons.kt` to the newest commit, then run `build_equipment_json.py` and
`fetch_pictures.py` as in `tools/README.md`. Commits that only change the calculator's code (most of them) need
nothing: the app shows the live calculator.

## History

- 24 Oct 2026: 30 new items (Ascension crossbows and bolts, Obligator, Rondache, Zorya's tome, Living record
  tiers 1-5, and Fractured Archive quest items). The DPS calculator's item list was last changed 2 September
  2026, so it has none of the October gear (nor the 7 October Imbued ancient capes) yet.
