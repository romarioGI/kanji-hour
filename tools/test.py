#!/usr/bin/env python3
"""Discover and run all *Test.java mains; Android doubles use a separate classpath."""
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/java/ru/romariogi/kanjihour"
PURE = ("HourlySelection", "WallpaperImportPolicy", "RefreshTask", "RefreshCancellation", "PreviewRequests")
CONTROLLER = ("Config", "LockWallpaperController", "WallpaperImportPolicy", "RefreshTask",
              "RefreshCancellation", "UpdateCoordinator", "RecoveryJobService")


def run(args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT, timeout=120)


def test_classes(sources):
    """A discovered *Test.java is mandatory: a missing main must fail, never skip."""
    classes = []
    for source in sorted(sources):
        if not source.name.endswith("Test.java"):
            continue
        match = re.search(r"(?m)^\s*package\s+([\w$.]+)\s*;", source.read_text(encoding="utf-8"))
        name = (match.group(1) + "." if match else "") + source.stem
        if name in classes:
            raise ValueError("Duplicate JVM test class: " + name)
        classes.append(name)
    if not classes:
        raise ValueError("No *Test.java classes discovered in test group")
    return classes


def suite(output, production, sources):
    classes = test_classes(sources)
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True)
    run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
         "-encoding", "UTF-8", "-d", output]
        + [SOURCE / (name + ".java") for name in production] + sorted(sources))
    for name in classes:
        print("[JVM] " + name, flush=True)
        run(["java", "-ea", "-cp", output, name])


def main():
    tests = ROOT / "tests"
    sources = sorted(tests.rglob("*.java"))
    plain = [path for path in sources if path.relative_to(tests).parts[0] != "controller"]
    controller = sorted((tests / "controller").rglob("*.java"))
    suite(ROOT / "build/tests", PURE, plain)
    suite(ROOT / "build/controller-tests", CONTROLLER, controller)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print("JVM checks failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
