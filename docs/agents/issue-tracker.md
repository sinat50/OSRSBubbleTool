# Plans and to-dos (the issue tracker)

Plans and to-dos are plain text files in `.scratch/` at the top of the project. The folder is in `.gitignore`, so it
stays on the owner's computer only (not on GitHub, not backed up by Git). `.scratch/README.md` lists what's in it.

## How the files are laid out

- One folder per feature or topic: `.scratch/<topic>/`, e.g. `.scratch/afk-alert/`.
- The plan for it is `.scratch/<topic>/spec.md`.
- If the plan is split into separate jobs, each job is its own file: `.scratch/<topic>/issues/<NN>-<name>.md`,
  numbered `01`, `02`... (three digits, `001`, if a topic will ever have more than 99).
- Every file has a `Status:` line near the top (see `triage-labels.md` for the allowed words). When work is
  finished, set `Status: done`.
- Notes added later go at the bottom under `## Notes`, newest last, each starting with its date.

## When a skill says "publish to the issue tracker"

Create the file under `.scratch/<topic>/` (make the folder if needed) and add a line for it to `.scratch/README.md`.

## When a skill says "fetch the relevant ticket"

Read the file at the path given. The owner will normally name the topic or the file.

## Which jobs are ready to work on

A job is ready when its status is `ready-for-agent` and every file named on its `Blocked by:` line (if it has one)
is `done`. Never pick up a job marked `needs-info`, `needs-triage`, `ready-for-human`, `wontfix` or `done`.
If several are ready, take the lowest number first.

## Agents working in a separate copy of the project

`.scratch/` isn't in Git, so a helper working in its own copy of the project (a "worktree") can't see it. Give
it the plan's text directly in its instructions, and write any results back into the files in the main project
folder yourself.
