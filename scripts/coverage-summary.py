#!/usr/bin/env python3
"""Summarise a JaCoCo XML report as Markdown for the CI job summary.

The headline percentage is not the interesting number here and probably never will be:
most of this codebase is Activities and a JNI bridge that no JVM test can reach, so the
denominator is dominated by code that is only ever exercised on a device against a real
host. Reporting only the total would make every honest addition look like a rounding
error.

So this leads with the classes that are actually under test and what fraction of each is
covered. That is the number a reviewer can act on: it goes down when someone adds an
uncovered branch to a class that had tests, which is the regression worth catching.

    coverage-summary.py <jacocoTestReport.xml>
"""

import sys
import xml.etree.ElementTree as ET

# Classes below this line count are too small for a percentage to mean much
MIN_LINES_FOR_TABLE = 1


def _counter(element, counter_type):
    """Return (covered, total) for one counter type, or (0, 0) if absent."""
    for counter in element.findall("counter"):
        if counter.get("type") == counter_type:
            covered = int(counter.get("covered"))
            missed = int(counter.get("missed"))
            return covered, covered + missed
    return 0, 0


def _percent(covered, total):
    return (covered / total * 100) if total else 0.0


def _class_name(package_name, class_element):
    name = class_element.get("name")
    prefix = package_name + "/"
    if name.startswith(prefix):
        name = name[len(prefix):]
    return name.replace("/", ".")


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[-1].strip(), file=sys.stderr)
        return 2

    root = ET.parse(sys.argv[1]).getroot()

    lines = ["## Unit test coverage", ""]

    covered_classes = []
    for package in root.findall("package"):
        for class_element in package.findall("class"):
            class_covered, class_total = _counter(class_element, "LINE")
            if class_covered > 0 and class_total >= MIN_LINES_FOR_TABLE:
                branch_covered, branch_total = _counter(class_element, "BRANCH")
                covered_classes.append((
                    _class_name(package.get("name"), class_element),
                    class_covered,
                    class_total,
                    branch_covered,
                    branch_total,
                ))

    if covered_classes:
        covered_classes.sort(key=lambda row: _percent(row[1], row[2]), reverse=True)
        lines.append("### Classes under test")
        lines.append("")
        lines.append("| Class | Lines | Branches |")
        lines.append("| --- | ---: | ---: |")
        for name, covered, total, branch_covered, branch_total in covered_classes:
            branches = (
                f"{_percent(branch_covered, branch_total):.0f}% ({branch_covered}/{branch_total})"
                if branch_total else "n/a"
            )
            lines.append(
                f"| `{name}` | {_percent(covered, total):.0f}% ({covered}/{total}) | {branches} |"
            )
        lines.append("")
    else:
        lines.append("No class has any covered lines. Either the tests did not run, or the")
        lines.append("report was generated against the wrong variant's classes.")
        lines.append("")

    line_covered, line_total = _counter(root, "LINE")
    method_covered, method_total = _counter(root, "METHOD")
    class_covered, class_total = _counter(root, "CLASS")

    lines.append("### Whole project")
    lines.append("")
    lines.append("| | Covered | Total | |")
    lines.append("| --- | ---: | ---: | ---: |")
    lines.append(f"| Lines | {line_covered:,} | {line_total:,} | {_percent(line_covered, line_total):.1f}% |")
    lines.append(f"| Methods | {method_covered:,} | {method_total:,} | {_percent(method_covered, method_total):.1f}% |")
    lines.append(f"| Classes | {class_covered:,} | {class_total:,} | {_percent(class_covered, class_total):.1f}% |")
    lines.append("")
    lines.append(
        "The whole-project figures include the Activities, the decoder renderer and the JNI "
        "bridge, none of which a JVM test can reach. Treat the per-class table above as the "
        "one that should not regress."
    )

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
