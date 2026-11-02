"""Read-only file search stub for the PC Agent (design-stage tool).

Searches ONLY the directory given with --root, for the substring given
with --query. Both arguments are required; there are no defaults.

Safety properties (by construction, not by policy):
- No writes, no deletes, no moves, no renames, no execution, no network.
- No shell, no subprocess. Files are opened read-only.
- Paths resolving outside --root (including via symlinks) are skipped,
  never followed.
- Binary files and credential-looking files are never printed: only
  their path is reported as a match, and only if the filename itself
  matches. Text-file content matches print the file path and the
  matching line, truncated to 200 characters.
- This stub does NOT read browser passwords, tokens or mail stores.
  Do not point --root at a credential directory; the blocklist below
  is a safety net, not permission to do so.

Usage:
    python3 file_search.py --root /path/to/allowed/dir --query report
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Extensions whose contents are never printed, even on a match.
BLOCKED_CONTENT_EXTENSIONS = {
    ".pem", ".key", ".keystore", ".jks", ".p12", ".pfx",
    ".db", ".sqlite", ".sqlite3", ".dat", ".bin",
}

MAX_LINE_CHARS = 200
MAX_RESULTS = 100


def is_within_root(root: Path, candidate: Path) -> bool:
    """True only if candidate resolves inside root (symlinks resolved)."""
    try:
        root_resolved = root.resolve()
        candidate_resolved = candidate.resolve()
    except (OSError, RuntimeError):
        return False
    return candidate_resolved == root_resolved or root_resolved in candidate_resolved.parents


def search(root: Path, query: str) -> list[str]:
    if not root.is_dir():
        raise ValueError(f"--root is not a directory: {root}")
    if not query:
        raise ValueError("--query must not be empty")
    results: list[str] = []
    for path in sorted(root.rglob("*")):
        if len(results) >= MAX_RESULTS:
            break
        if not path.is_file():
            continue
        if not is_within_root(root, path):
            continue
        if query in path.name:
            results.append(str(path))
            continue
        if path.suffix.lower() in BLOCKED_CONTENT_EXTENSIONS:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="strict")
        except (OSError, UnicodeDecodeError):
            continue  # binary or unreadable: skip, never print contents
        for line in text.splitlines():
            if query in line:
                results.append(f"{path}: {line.strip()[:MAX_LINE_CHARS]}")
                break
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only file search (PC Agent stub).")
    parser.add_argument("--root", required=True, help="Allowlisted directory to search (required).")
    parser.add_argument("--query", required=True, help="Substring to search for (required).")
    args = parser.parse_args(argv)
    try:
        results = search(Path(args.root), args.query)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    for line in results:
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
