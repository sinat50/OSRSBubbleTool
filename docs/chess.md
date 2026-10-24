# Chess (Game Room): how it works

Read this before changing chess. Built October 2026.

## The files

| File | What it is |
| --- | --- |
| `app/src/main/java/chess/` (16 Java files) | The CuckooChess engine by Peter Österlund (GPL 3 or later), copied **unchanged** from github.com/peterosterlund2/droidfish (`CuckooChessEngine/src/main/java/chess/`, commit 06948d5, February 2024). Only the engine files: not its `ComputerPlayer`, `Game`, `Player` or screens. Android's code check skips this folder (`app/lint.xml`), so it stays as the author wrote it |
| `app/src/main/resources/` | The engine's data, read with `getResourceAsStream`: `kpk.bitbase` and `krkp.winmasks` (endgame tables, copied from the same folder) and `book.bin` (the opening book, 5.8 KB, built from DroidFish's `CuckooChessEngine/src/main/book.txt` with `buildSrc`'s `chess.Book.main2`, as DroidFish's own build does) |
| `ChessGame.kt` | No Android parts. `ChessGame`: the rules on top of the engine (legal moves, play, undo, checkmate, stalemate, and the draws: threefold repetition, 50 moves, not enough pieces), and saving a game as its moves (`"e2e4 e7e5 e1g1"`). `ChessEngine`: the six levels and `Thinker`, which finds a move |
| `ChessView.kt` | `ChessView` (the board: drawing, taps, the waiting move), `ChessPieces` (the pieces as simple shapes), `PieceButton` (a round button with a piece, for promotions), `ChessScreen` (the board in its frame, see below) |
| `GameRoomTool.kt`, "Chess" section | The screen: the status, Play / Cancel, the controls (New game, Undo, Level, Options, Help), the cards for those, running the engine on a background thread, saving |
| `ChessGameTest.kt` | PC tests: move counts from the start (20, 400, 8,902), fool's mate, Loyd's stalemate, the draw rules, castling / en passant / promotion and saving, undo, the strength table, a mate in one at Max, every level answering in time, stopping |

## How a turn goes

- You tap a piece (`ChessView.selected`), then a square. `onPicked` gets the move (or four, for a promotion).
  With "Confirm each move" on (`chess_confirm`, default on) the move is drawn on the board as if made
  (`pending`), the top bar asks "Knight g1 to f3?" and Play / Cancel show in the bottom bar. Only Play plays it;
  tapping the board lets it go. A promotion always asks (four round piece buttons). Drags never move pieces.
  Nothing moves when a move waits: the bars are always there.
- After your move: `startThinking()` makes a `ChessEngine.Job` on the main thread and runs `Thinker.think` on one
  background thread (a single-thread executor, so two searches never share the engine's memory). The `Thinker` is
  made, used and let go only on that thread (`thinker`; `letThinkerGo` queues letting it go behind any move still
  being thought about). The answer is played on the main thread only if the job wasn't stopped and nothing changed
  (`chessVersion` goes up on New and Undo). If the engine fails (it comes back with no move, or throws anything,
  caught as `Throwable` so it can't take the app down), a random allowed move is played so the game never sticks
  on "Thinking...".
- `Job.stop()` ends a search within a few milliseconds (tested on the PC and the emulator); a job stopped before it
  starts never searches. New, Undo and the tool being destroyed (`destroy`, also on a dark mode / text size
  rebuild) stop it. The engine reads its time limit without cross-thread care (its code is kept unchanged), so in
  theory a phone could only notice the stop at the normal time limit (at most 6 s at Max); the late move is thrown
  away either way.
- `ChessGame.legalMoves()` works the allowed moves out once per position (the board asks on every tap and redraw)
  and gives the engine its move list back.
- Undo takes back moves until it's your turn again (your move and the computer's reply).

## The frame (like the Zen Garden's zen mode)

`ChessScreen` shows the board with no window around it, in a plain wooden frame drawn in code (flat colours, no
texture): thin down the sides (`SIDE_DP`), with a bar along the top and bottom (`BAR_DP`). A button sits in each
corner, as in the Zen Garden: Exit top left, move top right, resize bottom right, and bottom left an arrow that
opens the controls (round `ZenIcon`s and buttons in a `FlowRow`) over the bottom of the board. It uses the Game
Room's frameless window, the same as the garden (`BubbleService.setGamesFrameless`, `pinGamesCorners`,
`fitGamesWindowToTray`; see `docs/zen-garden.md`): the window's own move and resize corners become invisible touch
triangles reaching `CORNER_DP x 1.1` (about 43 dp) in from each corner. That's why the bars are 38 dp deep: side
plus bar must be more than that, or the corners would cover the board's corner squares. The board is the biggest
square the window allows, the frame sits in the window's top-left corner, and the window shrinks to it.
Checked on the emulator (October 2026), upright and sideways.

## Levels and battery

`ChessEngine.LEVELS`: Beginner, Easy (the default), Medium, Hard, Expert, Max. Each has a rating on CuckooChess's own
scale, turned into its 0-1000 strength with the table from DroidFish's `CuckooChess/src/main/java/uci/EngineControl.java`
(`ELO_TO_STRENGTH`), plus a thinking time (0.6 to 3 s; the search may take up to twice that on a hard move) and
whether it uses the opening book (not the two easiest: book moves would make them play the opening too well).
Like CuckooChess's own "limit strength", ratings under 1350 look at 10,000 positions a second at most and the
others 100,000 (Max: no limit); the engine sleeps in between, so weak levels hardly use the processor. Nothing
runs except on the computer's turn. The engine's position table (about 2 MB) is let go when you leave the chess
screen. Its fixed tables (about 3 MB: the pawn table, the opening book, the endgame tables) are made the first
time it plays and stay until the bubble closes, because they're built into its unchanged code.

## Saved settings (the `game_room` preferences)

`chess_moves` (the game), `chess_white` (you play white), `chess_level` (0-5), `chess_confirm`, `chess_hints`
(dots where a piece can go), `chess_coords` (letters and numbers). A save that doesn't replay (a bad move in it)
starts a new game.

## Updating the engine

DroidFish rarely changes (last commit February 2024) and the update checker doesn't watch it. To update: clone
github.com/peterosterlund2/droidfish, copy the same 16 files from `CuckooChessEngine/src/main/java/chess/` over
`app/src/main/java/chess/` unchanged, the two tables from `CuckooChessEngine/src/main/resources/`, rebuild
`book.bin` if `book.txt` changed (compile `buildSrc/src/main/java/chess/*.java` and call `chess.Book.main2(book.txt,
book.bin)`), check `EngineControl.java`'s rating table against `ELO_TO_STRENGTH`, then run `ChessGameTest`. Keep
the credit on the Legal screen and in the README.
