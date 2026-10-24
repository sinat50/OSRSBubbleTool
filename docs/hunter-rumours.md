# Hunter Rumours: updating its data

How to bring in wiki edits after the update checker reports `hunter-rumours`. Last done 24 October 2026.

## Where the data is

Everything is hand-written in `HunterRumours.kt` (no build script), adapted from two wiki pages:

| Wiki page | What the app takes from it | Where in `HunterRumours.kt` |
| --- | --- | --- |
| [Hunters' Rumours](https://oldschool.runescape.wiki/w/Hunters%27_Rumours) | Each creature's Hunter level, which guild hunters give it (the Yes/No columns, in the order Gilman, Cervus, Ornus, Aco, Teco, Wolf), where it lives, how it's caught | `ALL` (one `Rumour` each), `Hunter` |
| [Hunters' Rumours/Strategies](https://oldschool.runescape.wiki/w/Hunters%27_Rumours/Strategies) | What to bring for each hunting method, the teleports to each hunting spot, the always-useful items | `Method`s (`BOX_TRAP`, `NET_TRAP`...), `Place`s (`FELDIP`, `GREAT_CONCH`...) and the places inside `ALL`, `ALWAYS_USEFUL` |

`HunterRumourTool.kt` only shows this data; it doesn't need changing for data edits.

## The routine

1. See each edit as text: `python tools/wiki_diff.py "Hunters' Rumours" OLDID` and the same for
   `"Hunters' Rumours/Strategies"`, with the `oldid=` numbers from the checker's "what changed" links.
2. Ignore wiki-only changes: picture names (`pic=`), spelling, references, table layout, the example inventory
   (`|9 = ...` numbered lines), Leagues columns.
3. For real changes, find the line in `HunterRumours.kt` (search for the creature, item or place) and edit it,
   keeping the short, plain wording used there. Check a new item on its own wiki page if the edit doesn't say
   enough (for example `Net_trap_(item)`).
4. Don't rename a rumour's `name` lightly: the tool saves your current rumour by name, so a rename forgets it.
5. Build, then `python tools/update-check/check_updates.py --checked hunter-rumours`.

## History

- 24 Oct 2026: the game's new Net trap item (7 October update) replaced the rope + small fishing net for
  salamanders and swamp lizards (`NET_TRAP`). The Slayer ring to Wyrmscraig Cavern for goats was already in.
