# Сборка

Python 3.9+, JDK 17+ (`jdk.compiler`, `keytool`), Android SDK Platform 35 r2
и Build Tools 35.0.1. Сборка использует `aapt2`, Java-компилятор, D8, `zipalign`
и `apksigner`, без Gradle.

## Команды

Из корня проекта, с установленным SDK:

```sh
python3 tools/build_native.py --sdk /path/to/android-sdk
```

На Linux x86_64 SDK можно скачать автоматически:

```sh
python3 tools/build_native.py --bootstrap --check  # установить и проверить инструменты
python3 tools/build_native.py --bootstrap          # собрать и подписать APK
```

Для SDK действуют [условия Google](https://developer.android.com/studio/terms).
Примите их через установщик Google; `--bootstrap` только скачивает и распаковывает архивы.
На других системах установите SDK отдельно; сценарий ожидает Unix-имена исполняемых файлов.

Без `--sdk` поиск идёт по `ANDROID_SDK_ROOT`, `ANDROID_HOME`,
`../build-tools/android-sdk`, затем `~/.cache/kanji-hour/android-sdk`.
`--check` проверяет инструменты без сборки и создания ключа.

## Результат

- `dist/kanji-hour-poco.apk` и файл контрольной суммы `.apk.sha256`.
- `build/native/` — временные файлы сборки.

Установка через Android Platform Tools:

```sh
adb install -r dist/kanji-hour-poco.apk
```

Для обновлений сохраняйте исходный [ключ подписи](../signing/README.md).
Для сравнения повторных сборок используйте одинаковые исходники, SDK, JDK и ключ.

## Архивы SDK

SHA-256 проверяется перед распаковкой. Архивы и SDK хранятся вне репозитория.

| Архив Google | SHA-256 |
| --- | --- |
| [Platform 35 r2](https://dl.google.com/android/repository/platform-35_r02.zip) | `0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0` |
| [Build Tools 35.0.1, Linux](https://dl.google.com/android/repository/build-tools_r35.0.1_linux.zip) | `5993499f3229a021b89f87088c57242aeefaa62316bf3d69da7de40bfd5350f1` |
