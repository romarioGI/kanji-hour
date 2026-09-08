#!/usr/bin/env python3
"""Install/launch the exact candidate; also exercise replacement of the last release."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

from release_ci import APK, PACKAGE, api, digest, require, save

LOGS = Path("build/smoke")


def adb(*args):
    result = subprocess.run(["adb", "-e", *map(str, args)], capture_output=True, text=True,
                            check=True, timeout=120)
    return result.stdout


def launch():
    result = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    require("Status: ok" in result and "Error:" not in result, "Activity did not start: " + result)
    time.sleep(3)
    require(adb("shell", "pidof", PACKAGE).strip(), "App exited after launch")
    crashes = adb("logcat", "-d", "-b", "crash")
    require("Process: " + PACKAGE not in crashes, "App crash recorded in logcat")


def install(apk):
    require("Success" in adb("install", "--no-streaming", "-r", apk), "APK installation failed")
    launch()


def previous(repo, directory):
    release = api(repo, "releases/latest", missing_ok=True)
    if release is None:
        return None
    assets = {a["name"]: a for a in release["assets"]}
    require(APK in assets and "build-info.json" in assets, "Previous release lacks APK/metadata")
    # Asset endpoints need octet-stream even for JSON files; binary only controls decoding.
    info = json.loads(api(repo, f"releases/assets/{assets['build-info.json']['id']}", binary=True,
                          accept="application/octet-stream"))
    content = api(repo, f"releases/assets/{assets[APK]['id']}", binary=True,
                  accept="application/octet-stream")
    require(info["package"] == PACKAGE and digest(content) == info["apk_sha256"], "Previous release checksum mismatch")
    directory.mkdir(parents=True, exist_ok=True)
    (directory / APK).write_bytes(content)
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("build/candidate"))
    args = parser.parse_args()
    LOGS.mkdir(parents=True, exist_ok=True)
    info = json.loads((args.directory / "build-info.json").read_text())
    require(digest((args.directory / APK).read_bytes()) == info["apk_sha256"], "Candidate checksum mismatch")
    report = {"api_level": adb("shell", "getprop", "ro.build.version.sdk").strip(),
              "fresh_install": False, "upgrade": "not run"}
    try:
        adb("logcat", "-c")
        install(args.directory / APK)
        require(f"versionCode={info['version_code']} " in adb("shell", "dumpsys", "package", PACKAGE),
                "Installed versionCode differs from candidate")
        report["fresh_install"] = True
        old_dir = Path("build/previous")
        old = previous(os.environ["GITHUB_REPOSITORY"], old_dir)
        if old is None:
            report["upgrade"] = "skipped: no previous published release"
            print("Upgrade check skipped: this repository has no published release yet.")
        else:
            require(old["version_code"] < info["version_code"], "Candidate is not newer than the latest release")
            adb("uninstall", PACKAGE)
            adb("logcat", "-c")
            install(old_dir / APK)
            # No uninstall or data clear between old and new versions.
            install(args.directory / APK)
            require(f"versionCode={info['version_code']} " in adb("shell", "dumpsys", "package", PACKAGE),
                    "Upgrade did not install the candidate")
            report["upgrade"] = "passed"
    finally:
        save(LOGS / "report.json", report)
        try:
            (LOGS / "logcat.txt").write_text(adb("logcat", "-d", "-t", "1500"), encoding="utf-8")
        except subprocess.SubprocessError:
            pass


if __name__ == "__main__":
    main()
