#!/usr/bin/env python3
"""Measure APK size and DEX method count, and diff two measurements.

This is the cheap half of performance checking on a pull request. Real streaming latency
cannot be measured on a CI runner - there is no hardware decoder, no host and no network
worth the name, and the APK targets arm64-v8a/armeabi-v7a while the runners are x86_64 -
so this measures what CI *can* see honestly: how big the shipped artifact is and how many
methods it declares.

Both numbers move when the shrinker configuration changes. A ProGuard rule that
accidentally keeps a package, a dependency that pulls in a transitive tree, or shrinking
silently turning off all show up here as a step change, and nowhere else until someone
installs the thing.

Method counts come from the DEX headers rather than from apkanalyzer, which is not
guaranteed to be installed on the runner. method_ids_size sits at offset 0x58 of every
DEX header and counts method *references*, including inherited and framework ones - so it
tracks the 65536-per-DEX ceiling rather than the count of methods actually written here.

    apk-metrics.py measure <apk> [-o out.json]
    apk-metrics.py compare <baseline.json> <current.json>
"""

import argparse
import json
import struct
import sys
import zipfile

# Offset of method_ids_size within a DEX header, and the per-DEX reference ceiling
METHOD_IDS_SIZE_OFFSET = 0x58
DEX_METHOD_LIMIT = 65536

# Report a size change at or above this fraction, so routine noise stays quiet
SIZE_ALERT_FRACTION = 0.02


def measure(apk_path):
    """Return a metrics dict for one APK."""
    with open(apk_path, "rb") as handle:
        apk_bytes = len(handle.read())

    dex_entries = {}
    native_bytes = 0
    with zipfile.ZipFile(apk_path) as archive:
        for info in archive.infolist():
            if info.filename.endswith(".dex"):
                header = archive.read(info.filename)[:METHOD_IDS_SIZE_OFFSET + 4]
                if len(header) < METHOD_IDS_SIZE_OFFSET + 4:
                    raise ValueError(f"{info.filename} is too short to be a DEX file")
                (method_ids,) = struct.unpack_from("<I", header, METHOD_IDS_SIZE_OFFSET)
                dex_entries[info.filename] = method_ids
            elif info.filename.startswith("lib/"):
                # Uncompressed size: what lands on the device, not what ships over the wire
                native_bytes += info.file_size

    return {
        "apk_bytes": apk_bytes,
        "native_bytes": native_bytes,
        "dex_count": len(dex_entries),
        "method_ids": sum(dex_entries.values()),
        "method_ids_per_dex": dict(sorted(dex_entries.items())),
    }


def _format_bytes(count):
    megabytes = count / (1024 * 1024)
    return f"{megabytes:.2f} MiB"


def _format_delta(current, baseline, formatter):
    delta = current - baseline
    if delta == 0:
        return "no change"
    sign = "+" if delta > 0 else "-"
    fraction = (delta / baseline) if baseline else 0
    return f"{sign}{formatter(abs(delta))} ({fraction:+.1%})"


def compare(baseline, current):
    """Render a Markdown comparison. Returns (markdown, should_alert)."""
    rows = []
    alert = False

    for label, key, formatter in (
        ("APK size", "apk_bytes", _format_bytes),
        ("Native libraries", "native_bytes", _format_bytes),
        ("DEX method references", "method_ids", lambda n: f"{n:,}"),
        ("DEX files", "dex_count", lambda n: f"{n:,}"),
    ):
        base_value = baseline.get(key)
        current_value = current.get(key)
        if base_value is None or current_value is None:
            continue

        rows.append(
            f"| {label} | {formatter(base_value)} | {formatter(current_value)} "
            f"| {_format_delta(current_value, base_value, formatter)} |"
        )

        if base_value and abs(current_value - base_value) / base_value >= SIZE_ALERT_FRACTION:
            alert = True

    lines = [
        "| Metric | Baseline (master) | This branch | Change |",
        "| --- | ---: | ---: | ---: |",
        *rows,
    ]

    headroom = DEX_METHOD_LIMIT - max(current["method_ids_per_dex"].values(), default=0)
    lines.append("")
    lines.append(
        f"Largest DEX has {headroom:,} method references of headroom "
        f"before the {DEX_METHOD_LIMIT:,} limit."
    )

    if alert:
        lines.append("")
        lines.append(
            f"> A metric moved by {SIZE_ALERT_FRACTION:.0%} or more. That is usually a "
            "dependency or shrinker configuration change - worth a look, not necessarily wrong."
        )

    return "\n".join(lines), alert


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    measure_parser = subparsers.add_parser("measure", help="measure one APK")
    measure_parser.add_argument("apk")
    measure_parser.add_argument("-o", "--output", help="write JSON here instead of stdout")

    compare_parser = subparsers.add_parser("compare", help="diff two measurements")
    compare_parser.add_argument("baseline")
    compare_parser.add_argument("current")

    args = parser.parse_args()

    if args.command == "measure":
        metrics = measure(args.apk)
        payload = json.dumps(metrics, indent=2)
        if args.output:
            with open(args.output, "w") as handle:
                handle.write(payload + "\n")
            print(f"{args.apk}: {_format_bytes(metrics['apk_bytes'])}, "
                  f"{metrics['method_ids']:,} method references "
                  f"across {metrics['dex_count']} DEX file(s)")
        else:
            print(payload)
        return 0

    with open(args.baseline) as handle:
        baseline = json.load(handle)
    with open(args.current) as handle:
        current = json.load(handle)

    markdown, _ = compare(baseline, current)
    print(markdown)
    # Always exit 0: this is a report, not a gate. Nobody should be blocked from merging
    # because an APK grew, but everybody should be able to see that it did.
    return 0


if __name__ == "__main__":
    sys.exit(main())
