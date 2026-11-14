# Real product fixtures

Drop trimmed copies of REAL `/next` or `/player` responses here (one file per format, e.g.
`next_merchandise_shelf.json`). `RealProductFixturesTest` parses every `*.json` in this folder and
requires at least one product with a safe link from each. Trim the file to the product subtree plus
its ancestors; remove tracking params, visitor data and anything personal first.

Capture with a DEBUG build: open the video, then pull
`Android/data/<applicationId>/files/flow-debug/` (see the dump notes in the PR description).
