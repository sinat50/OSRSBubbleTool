<p align="center">
  <img src="docs/logo.png" alt="OSRS Bubble Tool logo" width="128">
</p>

<h1 align="center">OSRS Bubble Tool</h1>

<p align="center">
  A floating toolbox for Old School RuneScape on Android.<br>
  Free, no ads, and no accounts.
</p>

---

OSRS Bubble Tool puts a small bubble on top of the game. Tap it to open a tool in a narrow window beside the game, tap again to hide it, and keep playing. It brings some of the most-missed RuneLite conveniences to mobile, without touching the game itself.

- **Tap** the bubble to open or hide the current tool
- **Drag** the bubble to move it anywhere on screen
- **Long-press** the bubble to pick a different tool

Tool windows are two inches wide in landscape, and can be slid left and right with the bar at the top.

## Tools

| Tool | What it does |
| --- | --- |
| **OSRS Wiki** | The wiki in a window over the game, shrunk to fit. Can also open the game's own wiki links (the wiki button) in the bubble instead of your browser. |
| **Puzzle Box Solver** | Finds the clue puzzle box on screen by itself, reads the tiles and shows your next moves on top of it, with "Move 3 of 42" above the puzzle. Choose colour-coded boxes or shrinking dots. Follows your moves as you slide tiles, and re-plans if you make a different move. |
| **Light Box Solver** | Finds the light box by itself, asks you to press each button once to learn what it does, then outlines the buttons that turn every bulb on. Instructions appear right on the light box. |
| **Inventory Setups** | Save pictures of your inventory, equipment, spellbook and rune pouch under a name, so you can check them while gearing up. Each part is found on screen automatically. |
| **XP Calculator** | [oldschool.tools](https://oldschool.tools) calculators, with dropdown menus that work inside the window. |
| **DPS Calculator** | The OSRS Wiki DPS calculator. **Import my gear** finds your equipment tab by itself, recognises your worn equipment and loads it straight into the calculator. It copes with any brightness setting and tells look-alike items apart, such as enchanted and plain bolts. |
| **Shooting Star Tracker** | Live shooting star locations from [07.gg](https://07.gg/trackers/shooting-star). |
| **Zulrah Helper** | A tap-along rotation guide, like the RuneLite plugin: Zulrah's colour and position, where to stand, and which prayer to use, with the possible next phases underneath. |
| **Timers** | Farming and birdhouse timers. Tap **Planted** after a run and get a notification when it's ready. Covers herbs, trees, fruit trees, hardwoods, allotments, hops, seaweed and more. Growth times follow the game's growth ticks, and timers survive closing the app and restarting the phone. |
| **GE Prices** | The OSRS Wiki [Real-time Prices](https://prices.runescape.wiki/osrs/) site. |
| **Quest Helper (Beta)** | Step-by-step guides for 195 quests and miniquests, with the dialogue options to pick, the items for each step and the enemies you'll face. Every quest's requirements are ticked off against your account through [WikiSync](https://oldschool.runescape.wiki/w/RuneScape:WikiSync), including the quests they need in turn. Includes the achievement diaries, and solutions for 50 quest puzzles: drawn maps for the Song of the Elves light puzzles, tap-in solvers for riddles and locks, and trackers for the trial-and-error ones. |
| **Calculator** | A basic calculator that understands OSRS shorthand like 1.5m and 250k. |
| **Notepad** | Write, save and read notes without leaving the game. |

## Battery and privacy

- The screen is only captured when you use a tool that needs it (the Puzzle Box and Light Box Solvers, Inventory Setups and DPS gear import). Capture pauses itself whenever no tool is using it.
- Website tools pause while their window is closed.
- Timers don't keep anything running. Android wakes the app once when a timer is due.
- Screenshots are processed on the phone and thrown away. Only pictures you choose to keep, in Inventory Setups or with **Save picture to phone**, are stored.
- The app has no ads, no analytics and no accounts. The website tools load those sites directly, the same as a browser would.
- If you choose to enter your RuneScape name in the Quest Helper, it's used to read your public WikiSync data. That's the only personal thing the app ever sends.

## Permissions

The app's **Permissions** screen explains each one and has a button to turn it on.

| Permission | Why |
| --- | --- |
| Display over other apps | Shows the bubble and tool windows on top of the game. Required. |
| Notifications | Timer alerts, and the small notification Android requires while the bubble runs. |
| Alarms & reminders | Makes timer alerts arrive on time instead of a few minutes late. |
| Screen capture | Asked by Android the first time you use a tool that reads the screen, once each time you start the bubble. |
| Open wiki links | Optional. Lets the game's wiki button open pages in the bubble. Other apps' wiki links still go to your browser. |

## Building it yourself

1. Install [Android Studio](https://developer.android.com/studio).
2. Clone this repository, or download it with **Code → Download ZIP**.
3. Open the project folder in Android Studio and let Gradle finish syncing.
4. Connect your phone with USB debugging turned on and press **Run ▶**.

## Is it allowed?

The app only shows information and waits for you to tap. It never reads the game's memory, changes the game, or clicks anything for you. Everything it does is something you could do yourself with a second screen and a wiki page.

## Credits

- Item data and icons from the [OSRS Wiki DPS calculator](https://github.com/weirdgloop/osrs-dps-calc).
- Solved puzzle pictures, wiki pages and Real-time Prices from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki).
- Zulrah rotation data and arena layout adapted from the [Zulrah Helper](https://github.com/while-loop/runelite-plugins) RuneLite plugin, © 2020 Anthony Alves and © 2026 Ron Young, used under the BSD 2-Clause License (the full notice is in `ZulrahTool.kt` and on the app's Legal screen).
- Farming growth times based on [RuneLite](https://github.com/runelite/runelite)'s Time Tracking plugin.
- Quest guides, requirements, achievement diary tasks and puzzle solutions adapted from the [Quest Helper](https://github.com/Zoinkwiz/quest-helper) RuneLite plugin, © 2020 Zoinkwiz and the Quest Helper contributors, used under the BSD 2-Clause License (the full notice and every contributor are listed on the app's Legal screen). Some puzzle answers come from the OSRS Wiki's quick guides.
- Quest progress and levels read with [WikiSync](https://oldschool.runescape.wiki/w/RuneScape:WikiSync) by the OSRS Wiki.
- XP calculators by [oldschool.tools](https://oldschool.tools), shooting star data by [07.gg](https://07.gg).

## Legal

Created using intellectual property belonging to Jagex Limited under the terms of Jagex's Fan Content Policy. This content is not endorsed by or affiliated with Jagex.

Old School RuneScape and RuneScape are trademarks of Jagex Limited.
