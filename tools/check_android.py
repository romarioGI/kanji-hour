#!/usr/bin/env python3
"""Compile resources, all app Java and DEX without creating a signing key or release APK."""
import argparse
import os
from pathlib import Path
import shutil
import zipfile
import build_native as build


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=build.default_sdk())
    args = parser.parse_args()
    sdk = args.sdk.expanduser().resolve()
    tools = sdk / "build-tools/35.0.1"
    android = sdk / "platforms/android-35/android.jar"
    stubs = tools / "core-lambda-stubs.jar"
    aapt = tools / "aapt2"
    d8 = tools / "lib/d8.jar"
    for file in (android, stubs, aapt, d8):
        if not file.is_file():
            parser.error("Required SDK file missing: " + str(file))
    java, javac, _ = build.find_java()
    source = build.ROOT / "app/src/main"
    work = build.ROOT / "build/android-check"
    shutil.rmtree(work, ignore_errors=True)
    for directory in ("generated", "classes", "dex"):
        (work / directory).mkdir(parents=True)
    resources = work / "resources.zip"
    build.run([aapt, "compile", "--dir", source / "res", "-o", resources])
    build.run([aapt, "link", "-o", work / "resources.ap_", "--manifest", source / "AndroidManifest.xml",
               "-I", android, "--java", work / "generated", "--auto-add-overlay", "-R", resources,
               "-A", source / "assets"])
    sources = sorted((source / "java").rglob("*.java")) + sorted((work / "generated").rglob("*.java"))
    build.run(javac + ["-encoding", "UTF-8", "-source", "8", "-target", "8", "-bootclasspath",
                      os.pathsep.join((str(stubs), str(android))), "-classpath", android,
                      "-d", work / "classes"] + sources)
    archive = work / "classes.jar"
    with zipfile.ZipFile(archive, "w") as output:
        for file in sorted((work / "classes").rglob("*.class")):
            build.zip_add(output, file.relative_to(work / "classes").as_posix(), file.read_bytes())
    build.run([java, "-Xmx1g", "-cp", d8, "com.android.tools.r8.D8", "--release", "--min-api", "34",
               "--lib", android, "--output", work / "dex", archive])
    print("Android resources, Java and DEX compiled; no signing key or release APK created.")


if __name__ == "__main__":
    main()
