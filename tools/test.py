#!/usr/bin/env python3
"""Run JVM rules and the real wallpaper controller against test-only platform doubles."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/java/ru/romariogi/kanjihour"


def run(args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def main():
    output = ROOT / "build/tests"
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True)
    production = [SOURCE / (name + ".java") for name in ("HourlySelection", "WallpaperImportPolicy", "RefreshTask", "RefreshCancellation")]
    run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8", "-d", output]
        + production + sorted((ROOT / "tests").glob("*.java")))
    for name in ("HourlySelectionTest", "WallpaperImportPolicyTest", "RefreshTaskTest"):
        run(["java", "-cp", output, name])
    output = ROOT / "build/controller-tests"
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True)
    run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8", "-d", output]
        + [SOURCE / (name + ".java") for name in ("Config", "LockWallpaperController", "WallpaperImportPolicy", "RefreshTask", "RefreshCancellation", "UpdateCoordinator", "RecoveryJobService")]
        + sorted((ROOT / "tests/controller").rglob("*.java")))
    run(["java", "-cp", output, "ru.romariogi.kanjihour.LockWallpaperControllerTest"])
    run(["java", "-cp", output, "ru.romariogi.kanjihour.RecoveryJobCancellationTest"])


if __name__ == "__main__":
    main()
