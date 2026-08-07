#!/usr/bin/env python3
"""Apply the upstream patches this project carries against its native submodules.

Some fixes we depend on are not merged upstream yet. Rather than forking a submodule
or committing into it, the diff is kept under patches/ and applied to the submodule's
working tree before ndk-build runs. The submodule pointer never moves, so `git diff`
on the parent repo still shows exactly which upstream commit we build against.

The trade-off is that a patched submodule reports as dirty ("modified content") for as
long as the patch is carried. `git submodule update --force` resets it and the next
build re-applies.

Idempotent: already-applied patches are detected and skipped, so this is safe to run on
every build. A patch that no longer applies is a hard error rather than a warning - it
means upstream has moved and the patch needs revisiting, and silently building without
it would reintroduce whatever it fixes.

Run from the repository root, or with no arguments from anywhere - paths are resolved
relative to this file.
"""

import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# submodule path -> patch to apply to it, both relative to the repository root.
PATCHES = {
    "app/src/main/jni/moonlight-core/moonlight-common-c":
        "patches/moonlight-common-c/0001-mbedtls3-cbc-pkcs7-and-iv.patch",
}


def git(repo, *args):
    """Run a git command in `repo`, returning the CompletedProcess without raising."""
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
    )


def apply_patch(submodule, patch):
    repo = REPO_ROOT / submodule
    patch_file = REPO_ROOT / patch

    if not patch_file.is_file():
        sys.exit(f"error: missing patch {patch}")

    if not (repo / ".git").exists():
        sys.exit(
            f"error: {submodule} is not checked out.\n"
            "       Run: git submodule update --init --recursive"
        )

    # Already applied? `--reverse --check` succeeds only if the patch is present.
    if git(repo, "apply", "--reverse", "--check", str(patch_file)).returncode == 0:
        print(f"already applied: {patch}")
        return

    result = git(repo, "apply", str(patch_file))
    if result.returncode != 0:
        sys.exit(
            f"error: {patch} no longer applies to {submodule}.\n"
            "       Upstream has moved. Re-cut the patch against the pinned commit, or\n"
            "       drop it if the fix has landed upstream.\n"
            f"{result.stderr.strip()}"
        )

    print(f"applied: {patch}")


def main():
    for submodule, patch in PATCHES.items():
        apply_patch(submodule, patch)


if __name__ == "__main__":
    main()
