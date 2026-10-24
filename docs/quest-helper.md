# Quest Helper: how its data is made and changed

Read this before adding or changing a quest, a quest guide or a diary. The rules below were worked out in October
2026 by comparing the app's data with the plugin's Java, quest by quest. The original generator (v1.0.2) was never
saved, so `tools/quest-helper/` is what replaces it.

## The files

| File | What it holds | Made by |
| --- | --- | --- |
| `QuestData.kt` | Every quest's type, difficulty, quest points, requirements and enemies, plus the 12 achievement diaries. Read by `QuestBook` for the quest list and the WikiSync ✓/✗ checks | Generated (JSON split into pieces of about 13,000 characters) |
| `QuestGuides.kt` | The step-by-step guides: a `names` set and a `when` with one JSON string per quest (long ones split with `arrayOf(...).joinToString("")`). Only the opened quest's guide is decoded | Generated |
| `QuestHelperTool.kt` | The tool's pages; `parseGuide` reads a guide's JSON. Also a few **hand-written guides** (`guides` list, e.g. Scrambled!), which win over the generated ones | Hand-written |
| `QuestPuzzles.kt`, `QuestSolvers.kt`, `QuestSolverViews.kt`, `PuzzleDiagramView.kt` | Puzzle answers, linked to a quest by its name and to steps by `section` or `stepText` | Hand-written |
| `QuestBook.kt` | Reads `QuestData`, checks requirements against WikiSync (quest names must match WikiSync's exactly) | Hand-written |

The plugin is github.com/Zoinkwiz/quest-helper (BSD 2-Clause). Each quest is a Java file in
`src/main/java/com/questhelper/helpers/quests/<name>/`; its type and difficulty are in
`questinfo/QuestHelperQuest.java` (quests first, then a `//Miniquests` block).

## Changing the files: use the tool

```
python tools/quest-helper/quest_files.py show "Quest name"                 # the quest's data and guide, as JSON
python tools/quest-helper/quest_files.py put tools/quest-helper/quests/x.json   # add, or replace if already there
python tools/quest-helper/quest_files.py check                             # everything decodes, names and order line up
```

`put` puts quests in the right place (QuestData: alphabetical ignoring capitals and a leading "The ";
QuestGuides: plain alphabetical), touches only the piece of `QuestData.kt` the change lands in (small Git diffs),
keeps the files' Windows line endings, and reads the result back to check it. Never edit the escaped JSON by
hand. Keep each quest's JSON in `tools/quest-helper/quests/<quest-name>.json` with a `"source"` line (plugin
commit and file), so it can be fixed and put again later.

To bring in a new quest: shallow-clone the plugin into the scratchpad (`git clone --depth 1`), read the quest's
Java file, write its JSON by the rules below, `put` it, `check`, build, then update the guide count in README.md
("Step-by-step guides for N quests") and mark the update checker's `quest-helper` / `new-quests` as checked.

## QuestData entry (from the plugin's Java)

```json
{"n":"Crab Quest","t":"P","d":"Intermediate","qp":1,"r":[{"s":"Sailing","l":40},{"s":"Fishing","l":30}],"e":["..."]}
```

- `n`: the quest's name as WikiSync and the wiki spell it.
- `t`: `F` free, `P` members, `M` miniquest (from `QuestHelperQuest`'s `Type` and whether it sits under `//Miniquests`).
- `d`: the `Difficulty`, written like `Novice`, `Intermediate`, `Experienced`, `Master`, `Grandmaster`.
- `qp`: `getQuestPointReward()` (0 or left out for miniquests).
- `r`: `getGeneralRequirements()`, in the plugin's order:
  - `SkillRequirement(Skill.X, 40)` → `{"s":"X","l":40}`; add `"b":1` only when it says it can be boosted
    (`SkillRequirement(skill, level, true)`; the two-argument one can't).
  - `QuestRequirement(QuestHelperQuest.X, FINISHED)` → `{"q":"Quest name"}`; started only → add `"st":1`.
  - Quest points → `{"qp":N}`, combat level → `{"cb":N}`, anything else worth showing → `{"x":"text"}`.
  - **Left out**: free inventory slots, zone/varbit checks, anything only true in-game.
- `e`: `getCombatRequirements()`, word for word. Left out entirely (no key) when there are none.

## QuestGuides entry

```json
{"n":"...","items":[...],"rec":[...],"enemies":[...],"rewards":[...],"sec":[{"n":"Section","st":[{"t":"Step","s":["Dialogue"],"i":["Item"]}],"i":[...],"c":1,"e":[...]}]}
```

Quest-wide lists (always present, `[]` if empty):

- `items`: `getItemRequirements()` names; `rec`: `getItemRecommended()` names (general *recommended*
  requirements like slots or skill levels are left out).
- An item's text is its `ItemRequirement` name, plus ` (N)` when the quantity is over 1 (`"Coins (1000)"`).
  Tooltips are left out. `.highlighted()` / `.equipped()` don't change the name.
- `enemies`: `getCombatRequirements()`.
- `rewards`, in this order: `"N Quest point(s)"`, each `ExperienceReward` as `"10,000 Sailing XP"` (comma
  thousands), each `ItemReward` as `"25 x Name"`, then each `UnlockReward`'s text.

Sections (`sec`) come from `getPanels()`, one per `PanelDetails`, in order:

- `n`: the panel's title. `st`: its steps, in order.
- Each step: `t` = the step's text; `s` = its `addDialogStep(...)` lines (key left out if none); `i` = the names
  of the item requirements passed to the step (left out if none; skill, slot and zone requirements dropped).
  - A `ConditionalStep` uses its own text and own item requirements, or its default (first) step's when it has none.
  - Sub-steps (`addSubSteps`) and `.cutscene()` steps are not listed: only the steps named in the panel.
  - A step that is a whole helper object (like Troubled Tortugans' `RepairTown`) with no text of its own was left out.
- Section `i`: the item requirements given to the `PanelDetails` (free slots dropped; left out if none).
- Section `c` (the "⚔ Combat" / "No combat" line):
  - The quest has **no enemies** → `"c":0` on every section.
  - It has enemies → `"c":1` on sections that fight (a step says kill/defeat/fight one of the enemies, or
    the section needs "Combat gear"), and **no `c` key** on the others (shown as unknown, nothing printed).
- Section `e`: the quest's `enemies` entries this section fights (matched by name, ignoring a leading count and
  the "(level ...)" part, e.g. "2 outlaws (level 22 and level 23)" matches "Kill both outlaws"). Only with `"c":1`.
- `tr` (travel) and `r` (recommended for this section) exist on a few old guides only; not made from the plugin.

## Checking

`./gradlew.bat assembleDebug testDebugUnitTest` (`QuestChecksTest` covers requirement checks). On the phone,
open Quest Helper, search the quest, open its guide and check the sections, dialogue and the WikiSync ✓/✗ ticks.
Quests in `QuestData` without a guide (`check` lists them: The Enchanted Key, Lair of Tarn Razorlor, The Path of
Glouphrie) show requirements only; that's expected.
