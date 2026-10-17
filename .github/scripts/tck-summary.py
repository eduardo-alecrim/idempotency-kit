"""Prints a Markdown table of the store TCK results per database, read from Surefire XML reports.

Usage: tck-summary.py REPORT_DIR...
"""

import pathlib
import sys
import xml.etree.ElementTree as ElementTree

NAMES = {
    "PostgreSql": "PostgreSQL",
    "MySql": "MySQL",
    "MariaDb": "MariaDB",
    "Oracle": "Oracle",
    "SqlServer": "SQL Server",
}


def main(directories):
    results = {}
    for directory in directories:
        for report in sorted(pathlib.Path(directory).glob("TEST-*StoreContractTest.xml")):
            for case in ElementTree.parse(report).getroot().iter("testcase"):
                nested = case.get("classname", "").rpartition("$")[2]
                counts = results.setdefault(NAMES.get(nested, nested), {"passed": 0, "failed": 0, "skipped": 0})
                if case.find("failure") is not None or case.find("error") is not None:
                    counts["failed"] += 1
                elif case.find("skipped") is not None:
                    counts["skipped"] += 1
                else:
                    counts["passed"] += 1

    print("### Store TCK")
    print()
    if not results:
        print("No store TCK reports found.")
        return
    print("| Database | Passed | Failed | Skipped |")
    print("|---|---|---|---|")
    for name in sorted(results):
        counts = results[name]
        print(f"| {name} | {counts['passed']} | {counts['failed']} | {counts['skipped']} |")


if __name__ == "__main__":
    main(sys.argv[1:])
