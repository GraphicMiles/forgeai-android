#!/usr/bin/env python3
"""Fail the build if the APK is too big once Android has unpacked it.

Download size is not the number that matters — the phone stores the entries
uncompressed, so that is what the budget is measured against.
"""

import sys
import zipfile

MIB = 1024 * 1024


def main() -> int:
    path, limit_mib = sys.argv[1], int(sys.argv[2])

    with zipfile.ZipFile(path) as apk:
        entries = [(info.file_size, info.filename) for info in apk.infolist()]

    unpacked = sum(size for size, _ in entries)
    compressed = sum(info.compress_size for info in zipfile.ZipFile(path).infolist())
    limit = limit_mib * MIB

    print("## APK")
    print()
    print("| Measure | Size |")
    print("| --- | --- |")
    print(f"| Download (compressed) | {compressed / MIB:.2f} MiB |")
    print(f"| Installed (unpacked) | {unpacked / MIB:.2f} MiB |")
    print(f"| Budget | {limit_mib}.00 MiB |")
    print()
    print("### Ten largest entries")
    print()
    print("| Entry | Unpacked |")
    print("| --- | --- |")
    for size, name in sorted(entries, reverse=True)[:10]:
        print(f"| `{name}` | {size / MIB:.2f} MiB |")

    verdict = "over" if unpacked > limit else "within"
    message = f"Unpacked APK is {unpacked / MIB:.2f} MiB, {verdict} the {limit_mib} MiB budget."
    print(message, file=sys.stderr)
    return 1 if unpacked > limit else 0


if __name__ == "__main__":
    sys.exit(main())
