# Words and decisions

How the skills use this project's word list and its record of decisions.

## Read these before exploring the code

- **`CONTEXT.md`** at the top of the project: what words mean in this app (for example "tool window", "compact
  window", "Watch", "look").
- **`docs/adr/`**: one short file per big decision and why it was made (numbered `0001-...md`).

Neither exists yet. If they're missing, carry on without them: don't mention it or suggest making them. They're
started only when a word or a decision actually gets settled while planning something (the `domain-modeling`
skill does this).

CLAUDE.md is still the main description of the app and how it works; these files only add to it.

## Use the word list's words

When naming something from the app (in a plan's title, a suggested change, a test's name), use the word as
`CONTEXT.md` defines it, not a different word for the same thing. If the word you need isn't there, either
you're making up a word the app doesn't use (think again), or there's a gap: mention it to the owner so it can be
added.

## Point out clashes with past decisions

If something you suggest goes against a file in `docs/adr/`, say so plainly instead of quietly ignoring it:

> _Goes against decision 0003 (game pictures come with the app), but worth looking at again because..._
