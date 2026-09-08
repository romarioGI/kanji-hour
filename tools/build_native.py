#!/usr/bin/env python3
"""Build the native Java Android app without Gradle or runtime dependencies.

Requires Python 3.9+ and JDK 17+. On Linux x86_64, --bootstrap can fetch the
two pinned official Android SDK archives. See tools/BUILD.md.
"""

import argparse
import hashlib
import os
from pathlib import Path
import platform
import secrets
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parent.parent
SDK_ARCHIVES = (
    ("platform-35_r02.zip", "0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0",
     "platforms", "android-35", "android-35"),
    ("build-tools_r35.0.1_linux.zip", "5993499f3229a021b89f87088c57242aeefaa62316bf3d69da7de40bfd5350f1",
     "build-tools", "android-15", "35.0.1"),
)
ZIP_TIME = (2009, 1, 1, 0, 0, 0)


def run(args, label=None, **kwargs):
    if label:
        print(label, flush=True)
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def default_sdk():
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name)
        if value:
            return Path(value).expanduser()
    adjacent = ROOT.parent / "build-tools" / "android-sdk"
    if adjacent.is_dir():
        return adjacent
    return Path.home() / ".cache" / "kanji-hour" / "android-sdk"


def bootstrap_sdk(sdk):
    print("Android SDK use requires accepting Google's separate terms: https://developer.android.com/studio/terms (see tools/BUILD.md).", flush=True)
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "AMD64"):
        raise RuntimeError("Automatic SDK setup supports Linux x86_64. Install SDK Platform 35 and Build Tools 35.0.1, then pass --sdk.")
    cache = sdk.parent / "archives"
    cache.mkdir(parents=True, exist_ok=True)
    for filename, digest, group, old_name, new_name in SDK_ARCHIVES:
        destination = sdk / group / new_name
        if destination.exists():
            continue
        archive = ROOT.parent / "build-tools" / filename
        if not archive.is_file():
            archive = cache / filename
        if not archive.is_file():
            print("Downloading official Android SDK archive: " + filename, flush=True)
            partial = archive.with_suffix(".download")
            try:
                with urllib.request.urlopen("https://dl.google.com/android/repository/" + filename, timeout=60) as response, partial.open("wb") as output:
                    shutil.copyfileobj(response, output)
                if sha256(partial) != digest:
                    raise RuntimeError("SHA-256 verification failed: " + filename)
                partial.replace(archive)
            finally:
                partial.unlink(missing_ok=True)
        if sha256(archive) != digest:
            raise RuntimeError("SHA-256 verification failed: " + str(archive))
        sdk.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="extract-", dir=sdk) as temporary:
            temp = Path(temporary)
            with zipfile.ZipFile(archive) as source:
                for entry in source.infolist():
                    target = (temp / entry.filename).resolve()
                    if temp.resolve() not in target.parents:
                        raise RuntimeError("Unsafe SDK archive entry")
                    source.extract(entry, temp)
                    mode = (entry.external_attr >> 16) & 0o777
                    if mode:
                        target.chmod(mode)
            destination.parent.mkdir(parents=True, exist_ok=True)
            (temp / old_name).replace(destination)


def find_java():
    java_root = os.environ.get("JAVA_HOME")
    candidate = Path(java_root) / "bin" / "java" if java_root else None
    java = str(candidate) if candidate and candidate.is_file() else shutil.which("java")
    if not java:
        raise RuntimeError("Java is missing. Install JDK 17+.")
    # The environment may omit the javac executable while retaining its module.
    javac = [java, "-m", "jdk.compiler/com.sun.tools.javac.Main"]
    run(javac + ["-version"], "Checking Java compiler")
    keytool = Path(java).resolve().parent / "keytool"
    if not keytool.is_file():
        keytool = shutil.which("keytool")
    return java, javac, keytool


def ensure_key(keytool):
    signing = ROOT / "signing"
    keystore = signing / "development.p12"
    password = signing / "development-password.txt"
    if keystore.is_file():
        if not password.is_file():
            raise RuntimeError("Missing signing/development-password.txt; preserve the original password to update the installed app.")
        return keystore, password
    if keystore.exists() or keystore.is_symlink():
        raise RuntimeError("signing/development.p12 exists but is not a regular key file; restore the original signing files privately.")
    if password.exists() or password.is_symlink():
        raise RuntimeError("A signing password exists without its key. Restore the matching development.p12 from your private backup; the script will not replace or reuse these files to create a different signing identity.")
    if not keytool:
        raise RuntimeError("keytool is required to create the first development signing key.")
    signing.mkdir(parents=True, exist_ok=True)
    # Exclusive creation prevents overwriting an existing password, including
    # one created concurrently. Permissions are private from the first write.
    password_fd = os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(password_fd, "w", encoding="utf-8") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(secrets.token_urlsafe(32) + "\n")
    run([keytool, "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
         "-alias", "kanji-hour", "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "10000", "-dname", "CN=Kanji Hour Personal Development",
         "-storepass:file", password, "-keypass:file", password, "-noprompt"],
        "Creating the persistent personal development signing key", umask=0o077)
    keystore.chmod(0o600)
    return keystore, password


def zip_add(archive, name, data, compression=zipfile.ZIP_DEFLATED):
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.compress_type = compression
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def build(sdk, output, check_only, unsigned_only=False, version_code=None):
    if version_code is not None and not 1 <= version_code <= 2100000000:
        raise RuntimeError("versionCode must be between 1 and 2100000000")
    java, javac, keytool = find_java()
    build_tools = sdk / "build-tools" / "35.0.1"
    android_jar = sdk / "platforms" / "android-35" / "android.jar"
    lambda_stubs = build_tools / "core-lambda-stubs.jar"
    aapt2, zipalign = build_tools / "aapt2", build_tools / "zipalign"
    d8_jar, signer_jar = build_tools / "lib" / "d8.jar", build_tools / "lib" / "apksigner.jar"
    for path in (android_jar, lambda_stubs, aapt2, zipalign, d8_jar, signer_jar):
        if not path.is_file():
            raise RuntimeError("Required SDK file missing: " + str(path) + ". Use --bootstrap or --sdk.")
    run([aapt2, "version"], "Checking Android resource compiler")
    run([java, "-cp", d8_jar, "com.android.tools.r8.D8", "--version"], "Checking dex compiler")
    run([java, "-jar", signer_jar, "version"], "Checking APK signer")
    if check_only:
        print("Toolchain is ready. SDK: " + str(sdk))
        return

    source = ROOT / "app" / "src" / "main"
    manifest = source / "AndroidManifest.xml"
    if not manifest.is_file():
        raise RuntimeError("Missing " + str(manifest))
    work = ROOT / "build" / "native"
    if work.exists():
        shutil.rmtree(work)
    for name in ("generated", "classes", "dex"):
        (work / name).mkdir(parents=True, exist_ok=True)
    resources_zip = work / "compiled-resources.zip"
    resources_apk = work / "resources.ap_"
    run([aapt2, "compile", "--dir", source / "res", "-o", resources_zip], "1/6 Compiling resources")
    link = [aapt2, "link", "-o", resources_apk, "--manifest", manifest,
            "-I", android_jar, "--java", work / "generated", "--min-sdk-version", "34",
            "--target-sdk-version", "34", "--auto-add-overlay", "-R", resources_zip]
    if version_code is not None:
        # AAPT2 otherwise preserves versionCode already present in the manifest.
        link += ["--replace-version", "--version-code", str(version_code)]
    if (source / "assets").is_dir():
        link += ["-A", source / "assets"]
    run(link, "2/6 Linking Android package and generating R.java")
    java_files = sorted((source / "java").rglob("*.java")) + sorted((work / "generated").rglob("*.java"))
    if not java_files:
        raise RuntimeError("No Java source files found")
    # Javac needs complete LambdaMetafactory signatures for Java 8 lambdas.
    # The platform android.jar deliberately strips these; official build-tools
    # supply compile-only stubs. D8 consumes the emitted lambda call sites.
    bootclasspath = os.pathsep.join((str(lambda_stubs), str(android_jar)))
    # Pass each filename as a separate argument; no shell interpretation.
    run(javac + ["-encoding", "UTF-8", "-source", "8", "-target", "8",
                 "-bootclasspath", bootclasspath, "-classpath", android_jar,
                 "-d", work / "classes"] + java_files,
        "3/6 Compiling Java (Java 8 bytecode, Android 35 API)")
    classes_jar = work / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w") as archive:
        for path in sorted((work / "classes").rglob("*.class")):
            zip_add(archive, path.relative_to(work / "classes").as_posix(), path.read_bytes())
    run([java, "-Xmx1g", "-cp", d8_jar, "com.android.tools.r8.D8", "--release",
         "--min-api", "34", "--lib", android_jar, "--output", work / "dex", classes_jar],
        "4/6 Converting Java bytecode to DEX")
    unsigned = work / "unsigned.apk"
    with zipfile.ZipFile(resources_apk) as source_archive, zipfile.ZipFile(unsigned, "w") as destination:
        for entry in sorted(source_archive.infolist(), key=lambda item: item.filename):
            zip_add(destination, entry.filename, source_archive.read(entry.filename), entry.compress_type)
        for dex in sorted((work / "dex").glob("*.dex")):
            zip_add(destination, dex.name, dex.read_bytes())
    aligned = work / "aligned.apk"
    run([zipalign, "-f", "-P", "16", "4", unsigned, aligned], "5/6 Aligning APK")
    output.parent.mkdir(parents=True, exist_ok=True)
    if unsigned_only:
        # CI signs in a separate job; no key is loaded or generated here.
        shutil.copyfile(aligned, output)
        run([zipalign, "-c", "-P", "16", "4", output], "Verifying unsigned package alignment")
        print("Built unsigned APK: " + str(output))
        return
    keystore, password = ensure_key(keytool)
    run([java, "-jar", signer_jar, "sign", "--ks", keystore, "--ks-key-alias", "kanji-hour",
         "--ks-pass", "file:" + str(password),
         "--v4-signing-enabled", "false", "--out", output, aligned], "6/6 Signing APK")
    run([java, "-jar", signer_jar, "verify", "--verbose", "--print-certs", output], "Verifying APK signature")
    run([zipalign, "-c", "-P", "16", "4", output], "Verifying package alignment")
    checksum = output.with_suffix(output.suffix + ".sha256")
    checksum.write_text(sha256(output) + "  " + output.name + "\n", encoding="utf-8")
    print("Built " + str(output))
    print("SHA-256 " + sha256(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=default_sdk(), help="Android SDK root directory")
    parser.add_argument("--bootstrap", action="store_true", help="Install pinned official SDK archives if missing (Linux x86_64)")
    parser.add_argument("--check", action="store_true", help="Check toolchain only; do not compile the app or create a key")
    parser.add_argument("--output", type=Path, default=ROOT / "dist" / "kanji-hour-poco.apk")
    parser.add_argument("--unsigned", action="store_true", help="Build aligned APK without loading or generating a key")
    parser.add_argument("--version-code", type=int, help="Override manifest versionCode for CI candidates")
    args = parser.parse_args()
    sdk = args.sdk.expanduser().resolve()
    try:
        if args.bootstrap:
            bootstrap_sdk(sdk)
        build(sdk, args.output.expanduser().resolve(), args.check, args.unsigned, args.version_code)
    except (RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        print("Build failed: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
