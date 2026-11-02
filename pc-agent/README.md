# PC Agent — design only, plus one read-only CLI stub

## Status

Design document only, except for `file_search.py`, a tiny read-only
command-line stub described below. **No PC app has been built, no PC OS
has been decided, nothing has been tested on a PC.** Do not present
this node as implemented.

## Role (in order of safety)

1. **Read-only first:** find files, read file names/content the user
   explicitly allows, summarise local documents, search the browser
   history the user explicitly shares. This is the only PC behaviour
   in scope for a first version.
2. **Write operations later, Tier-3 only:** create/edit/move a file,
   change a setting, or run a browser action only after a confirmation
   on the primary device UI (phone), with the exact path/action shown.
   Write operations are not in `file_search.py` and must not be added
   to it without a separate design decision.
3. **Long tasks:** the PC is the right node for multi-step work that
   needs a big screen and a keyboard; the cloud splits such work into
   envelopes, one task_id per task, and the PC reports status back
   (`pending` → `confirmed` → `executing` → `completed`/`cancelled`).

## The stub: `file_search.py`

- Searches **only** the directory given on the command line
  (`--root`), and only for a filename/content substring given with
  `--query`. Both are required; there are no defaults.
- Refuses to search outside the allowlisted root: any candidate path
  that resolves outside `--root` (including via symlinks) is skipped,
  never followed and never reported as a match.
- **Read-only by construction:** it opens files for reading only,
  writes nothing, deletes nothing, moves nothing, and executes nothing.
  It contains no shell, no subprocess, no network and no credential
  handling.
- It does not read browser passwords, tokens, mail stores or any
  other credential material; if a match happens to be inside such a
  file, the stub only prints the file path, never the file content
  for binary or credential-looking files (see the code's extension
  blocklist — it is a safety net, not a guarantee, so do not point
  it at a credential directory in the first place).

Usage:

```bash
python3 file_search.py --root /path/to/allowed/dir --query report
```

## What the PC node does NOT do

- No bypassing OS permissions, no silent installs, no reading browser
  passwords or tokens, no keylogging, no screen control without an
  explicit, per-action confirmation.
- No free-form shell: a future PC executor, if ever built, must take
  envelopes only, with the same allowlist + tier rules as the phone.

## Open decisions for this node

- PC OS: Windows / macOS / Linux — undecided.
- PC model and whether it is the user's existing computer: undecided.
- Browser automation scope (if any): undecided; read-only search first.
