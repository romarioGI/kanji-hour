# Native Android build

The app uses Android framework Java APIs and has no third-party runtime dependencies. The script replaces Gradle with the official resource compiler, Java compiler, D8, zipalign and apksigner.

## Requirements

- Python 3.9 or later.
- JDK 17 or later, including `jdk.compiler` and `keytool`. The script invokes the compiler through `java -m jdk.compiler/com.sun.tools.javac.Main`, so a separate `javac` executable is unnecessary.
- Android SDK Platform 35 revision 2 and Build Tools 35.0.1.
- Linux x86_64 for automatic SDK installation. For other hosts, install the corresponding SDK tools manually and pass `--sdk` (the script expects Unix-style tool names).

Before downloading or using the SDK, review and accept the [Android SDK License Agreement](https://developer.android.com/studio/terms) separately, along with any applicable component terms. Use Google's installation/license-acceptance process for your environment. This project's license does not license the SDK, JDK, or Python. `--bootstrap` only downloads and extracts pinned archives; it does not grant a license, record acceptance, or accept terms on your behalf. If you do not accept the SDK terms, do not run the SDK commands below.

## Commands

From the project directory:

```bash
# After reviewing and accepting SDK terms, install/check the pinned tools.
python3 tools/build_native.py --bootstrap --check

# Compile, align, sign, and verify the APK.
python3 tools/build_native.py --bootstrap

# Alternatively, use an SDK that is already installed.
python3 tools/build_native.py --sdk /absolute/path/to/android-sdk
```

The script checks `ANDROID_SDK_ROOT`, then `ANDROID_HOME`, then an adjacent `../build-tools/android-sdk`, then `~/.cache/kanji-hour/android-sdk`. `--sdk` overrides this choice. It never invokes a shell to interpret source paths or passwords.

Outputs:

- `dist/kanji-hour-poco.apk`: installable APK, minimum Android 14 (API 34), target API 34, compiled against API 35.
- `dist/kanji-hour-poco.apk.sha256`: SHA-256 checksum.
- `build/native/`: disposable intermediate files.

Java sources compile as Java 8 bytecode before DEX conversion. ZIP timestamps and order are normalized. Reuse the same SDK, Java compiler version, project source and signing key when comparing repeated builds; the first creation of a signing key is intentionally random.

## Pinned SDK downloads

Downloads are verified before extraction. Android SDK binaries remain in the SDK/cache location and do not need to be included with the project.

| Official Google archive | SHA-256 |
| --- | --- |
| `https://dl.google.com/android/repository/platform-35_r02.zip` | `0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0` |
| `https://dl.google.com/android/repository/build-tools_r35.0.1_linux.zip` | `5993499f3229a021b89f87088c57242aeefaa62316bf3d69da7de40bfd5350f1` |

## Installing and updating

Transfer `dist/kanji-hour-poco.apk` to the phone and open it. If Android asks, allow this particular file manager to install apps. Or use Android platform tools from a computer:

```bash
adb install -r dist/kanji-hour-poco.apk
```

Keep `signing/development.p12` and its matching `signing/development-password.txt` backed up privately, outside Git and shared archives. The repository contains no signing key or password. A clean collaborator checkout creates a new local signing identity; its APK cannot update the existing personal installation. Future in-place updates must be signed privately with the original key. Existing signing files are preserved, and an incomplete pair causes a clear error rather than replacement; see [signing instructions](../signing/README.md).
