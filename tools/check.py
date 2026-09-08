#!/usr/bin/env python3
"""Fast repository checks: XML, dictionary, plain JVM and Python tests."""
import csv
from pathlib import Path
import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
PERMISSIONS = {"android.permission." + name for name in (
    "SET_WALLPAPER", "RECEIVE_BOOT_COMPLETED", "SCHEDULE_EXACT_ALARM", "MANAGE_EXTERNAL_STORAGE")}


def check_data(path):
    with path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream, delimiter="\t"))
    if not rows or rows[0] != ["glyph", "on", "kun", "meaning"] or len(rows) < 2:
        raise ValueError("Invalid or empty kanji.tsv")
    seen = set()
    for line, row in enumerate(rows[1:], 2):
        if len(row) != 4 or any(value != value.strip() for value in row):
            raise ValueError(f"kanji.tsv:{line}: expected four trimmed fields")
        glyph, on, kun, meaning = row
        if not re.fullmatch(r"[\u3400-\u9fff]", glyph) or glyph in seen:
            raise ValueError(f"kanji.tsv:{line}: invalid/duplicate glyph")
        if not meaning or not (on or kun):
            raise ValueError(f"kanji.tsv:{line}: missing reading or meaning")
        if not re.search(r"[А-Яа-яЁё]", meaning):
            raise ValueError(f"kanji.tsv:{line}: missing Russian meaning")
        seen.add(glyph)
    return len(seen)


def main():
    source = ROOT / "app/src/main"
    for path in source.rglob("*.xml"):
        ET.parse(path)
    manifest = ET.parse(source / "AndroidManifest.xml").getroot()
    sdk = manifest.find("uses-sdk")
    if (manifest.attrib["package"] != "ru.romariogi.kanjihour" or sdk is None or
            sdk.attrib.get(ANDROID + "minSdkVersion") != "34" or
            sdk.attrib.get(ANDROID + "targetSdkVersion") != "34"):
        raise ValueError("Unexpected package or SDK levels; update build and checks together")
    if manifest.find("application").attrib.get(ANDROID + "debuggable", "false") != "false":
        raise ValueError("Release manifest must not be debuggable")
    if {x.attrib[ANDROID + "name"] for x in manifest.findall("uses-permission")} != PERMISSIONS:
        raise ValueError("Permissions changed; review and update the allow-list deliberately")
    print(f"Dictionary: {check_data(source / 'assets/kanji.tsv')} unique kanji", flush=True)
    # Keep one JVM entry point so new production dependencies and controller doubles
    # are included in both local runs and the release pipeline.
    subprocess.run([sys.executable, str(ROOT / "tools/test.py")], check=True)
    suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"), pattern="test_*.py")
    if not suite.countTestCases():
        raise ValueError("No release-pipeline tests found")
    return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, KeyError, ET.ParseError, subprocess.CalledProcessError) as error:
        print("Checks failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
