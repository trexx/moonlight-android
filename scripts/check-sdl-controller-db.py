#!/usr/bin/env python3
"""Compare the vendored SDL controller database against SDL upstream.

app/src/main/jni/moonlight-core/{controller_list,controller_type}.h are copied from
SDL rather than tracked as a submodule. SDL adds controllers continuously, so an
exact-match check would fail permanently and get ignored. Instead this reports how
far behind the copy is and only fails once enough devices have accumulated to make a
refresh worthwhile.

It also reports two things that matter *when* refreshing, because SDL does not only
add entries:

  * Retyped devices - SDL sometimes splits a type (it moved the Xbox Elite pads out
    of XBoxOneController and the DualSense Edge out of PS5Controller). Refreshing
    without handling these silently drops those devices to LI_CTYPE_UNKNOWN.
  * New controller types - may need mapping in guessControllerType() in simplejni.c.

Run with no arguments to check against SDL main.
"""

import re
import sys
import urllib.request

SDL_RAW = "https://raw.githubusercontent.com/libsdl-org/SDL/main/src/joystick/"
VENDORED_DIR = "app/src/main/jni/moonlight-core/"

# Refresh once this many unknown-to-us devices have accumulated upstream.
DEFAULT_THRESHOLD = 25

ENTRY_RE = re.compile(
    r"MAKE_CONTROLLER_ID\(\s*(0x[0-9a-fA-F]+),\s*(0x[0-9a-fA-F]+)\s*\),\s*(k_eControllerType_\w+)"
)
TYPE_RE = re.compile(r"(k_eControllerType_\w+)\s*=")


def parse_entries(text):
    return {
        (m.group(1).lower(), m.group(2).lower()): m.group(3)
        for m in ENTRY_RE.finditer(text)
    }


def fetch(name):
    with urllib.request.urlopen(SDL_RAW + name, timeout=60) as response:
        return response.read().decode("utf-8")


def read(name):
    with open(VENDORED_DIR + name, encoding="utf-8") as handle:
        return handle.read()


def main():
    threshold = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_THRESHOLD

    ours = parse_entries(read("controller_list.h"))
    theirs = parse_entries(fetch("controller_list.h"))
    our_types = set(TYPE_RE.findall(read("controller_type.h")))
    their_types = set(TYPE_RE.findall(fetch("controller_type.h")))

    added = sorted(k for k in theirs if k not in ours)
    retyped = sorted(k for k in theirs if k in ours and theirs[k] != ours[k])
    new_types = sorted(their_types - our_types)

    print(f"vendored devices: {len(ours)}")
    print(f"upstream devices: {len(theirs)}")
    print(f"new upstream devices:  {len(added)} (refresh threshold: {threshold})")
    print(f"retyped upstream:      {len(retyped)}")
    print(f"new controller types:  {len(new_types)}")

    if retyped:
        print("\nDevices SDL has retyped - these must be handled when refreshing,")
        print("or they will silently fall through to LI_CTYPE_UNKNOWN:")
        for vid, pid in retyped:
            print(f"  {vid}:{pid}  {ours[(vid, pid)]} -> {theirs[(vid, pid)]}")

    if new_types:
        print("\nController types added upstream (may need mapping in simplejni.c):")
        for name in new_types:
            print(f"  {name}")

    if len(added) >= threshold:
        print(
            f"\n::error::SDL has {len(added)} controllers this build does not "
            f"recognise. Refresh controller_list.h, controller_type.h and usb_ids.h "
            f"from SDL, then re-check guessControllerType() in simplejni.c against "
            f"the retyped/new types listed above."
        )
        return 1

    print("\nVendored controller database is current enough; no action needed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
