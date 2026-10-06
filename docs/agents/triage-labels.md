# Status words

Every file in `.scratch/` has one `Status:` line near the top, using one of these words. The skills' own names for
them are the same.

| Status            | What it means                                                                      |
| ----------------- | ---------------------------------------------------------------------------------- |
| `needs-triage`    | New: the owner hasn't decided yet whether or when to do it                         |
| `needs-info`      | Waiting for the owner to answer a question (the question is in the file)           |
| `ready-for-agent` | Planned clearly enough for Claude to build without more questions                  |
| `ready-for-human` | Only the owner can do it (testing in the game, a decision, signing a release...)   |
| `wontfix`         | Decided against: kept so it isn't suggested again                                  |
| `done`            | Finished                                                                           |

When a skill talks about one of these (for example "mark it ready for an agent"), write that word on the
`Status:` line.
