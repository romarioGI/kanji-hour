#!/usr/bin/env python3
"""Prepare a signed candidate and publish only its manually accepted bytes."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "ru.romariogi.kanjihour"
CERTIFICATE = "9e7aaa58b255e01820667c509475825e247859219fbdd7aa2932ffcc99d76fc3"
VERSION_BASE = 2  # Highest pre-CI versionCode. Never reset this workflow's run counter.
WORKFLOW = ".github/workflows/prepare-release.yml"
APK = "kanji-hour.apk"
FILES = {APK, APK + ".sha256", "build-info.json"}
ANDROID = "{http://schemas.android.com/apk/res/android}"
VERSION = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value):
    require(re.fullmatch(r"[1-9][0-9]{0,19}", str(value)) is not None, "Invalid positive integer")
    return int(value)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def api(repo, endpoint, data=None, method=None, binary=False, missing_ok=False,
        accept="application/vnd.github+json"):
    # Raw response bytes and the endpoint's requested media type are independent.
    args = ["gh", "api", "--method", method or ("POST" if data is not None else "GET"),
            "-H", f"Accept: {accept}",
            "-H", "X-GitHub-Api-Version: 2022-11-28", f"repos/{repo}/{endpoint}"]
    if data is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(data).encode() if data is not None else None,
                            capture_output=True, check=False, timeout=180)
    if result.returncode:
        if missing_ok and b"HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"GitHub API failed: {endpoint}: " + result.stderr.decode(errors="replace"))
    return result.stdout if binary else json.loads(result.stdout or b"null")


def pages(repo, endpoint):
    for page in range(1, 1001):
        rows = api(repo, f"{endpoint}?per_page=100&page={page}")
        yield from rows
        if len(rows) < 100:
            return
    raise RuntimeError("Pagination limit reached; refusing incomplete validation")


def guard(first_attempt=False):
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch", "Manual dispatch required")
    require(os.environ.get("GITHUB_REF") == "refs/heads/trunk", "Run this workflow from trunk only")
    if first_attempt:
        require(os.environ.get("GITHUB_RUN_ATTEMPT") == "1", "Start a NEW candidate; do not re-run one")


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def summary(text):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
        stream.write(text + "\n")


def prepare(sdk, directory):
    guard(first_attempt=True)
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    name = manifest.attrib[ANDROID + "versionName"]
    require(re.fullmatch(VERSION, name), "Invalid versionName")
    code = VERSION_BASE + number(os.environ["GITHUB_RUN_NUMBER"])
    require(int(manifest.attrib[ANDROID + "versionCode"]) < code <= 2100000000, "Invalid versionCode allocation")
    info = {"schema": 1, "repository": os.environ["GITHUB_REPOSITORY"],
            "run_id": number(os.environ["GITHUB_RUN_ID"]), "run_attempt": 1,
            "commit": os.environ["GITHUB_SHA"], "version_name": name, "version_code": code,
            "package": PACKAGE, "min_sdk": 34, "target_sdk": 34, "certificate_sha256": CERTIFICATE,
            "data_sha256": digest((ROOT / "app/src/main/assets/kanji.tsv").read_bytes()),
            "toolchain": {"sdk_platform": "35 r2", "build_tools": "35.0.1",
                          "python": sys.version.split()[0],
                          "java": run(["java", "-version"], capture_output=True, text=True).stderr.strip()}}
    directory.mkdir(parents=True, exist_ok=True)
    run([sys.executable, ROOT / "tools/build_native.py", "--sdk", sdk, "--bootstrap", "--unsigned",
         "--version-code", code, "--output", directory / APK])
    save(directory / "build-info.json", info)


def tool(sdk, name):
    return sdk / "build-tools/35.0.1" / name


def verify_apk(sdk, apk, info):
    signature = run([tool(sdk, "apksigner"), "verify", "--verbose", "--print-certs", apk],
                    capture_output=True, text=True).stdout
    certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature)
    require([x.lower() for x in certificates] == [CERTIFICATE], "Wrong APK signing certificate")
    run([tool(sdk, "zipalign"), "-c", "-P", "16", "4", apk])
    badging = run([tool(sdk, "aapt2"), "dump", "badging", apk], capture_output=True, text=True).stdout
    require(f"name='{PACKAGE}' versionCode='{info['version_code']}' versionName='{info['version_name']}'"
            in badging, "APK package/version mismatch")
    require(re.search(r"^(?:minSdkVersion|sdkVersion):'34'$", badging, re.MULTILINE) is not None
            and re.search(r"^targetSdkVersion:'34'$", badging, re.MULTILINE) is not None,
            "Unexpected SDK levels")
    require("application-debuggable" not in badging, "Release APK is debuggable")
    from check import PERMISSIONS
    require(set(re.findall(r"uses-permission: name='([^']+)'", badging)) == PERMISSIONS,
            "Unexpected APK permissions")
    with zipfile.ZipFile(apk) as archive:
        require(digest(archive.read("assets/kanji.tsv")) == info["data_sha256"], "APK dictionary mismatch")


def sign(sdk, source, directory):
    guard(first_attempt=True)
    info = json.loads((source / "build-info.json").read_text())
    require(info["commit"] == os.environ["GITHUB_SHA"] and info["run_id"] == number(os.environ["GITHUB_RUN_ID"]),
            "Unsigned artifact belongs to a different run")
    key = os.environ.pop("KANJI_SIGNING_KEY_B64", "")
    password = os.environ.pop("KANJI_SIGNING_PASSWORD", "").rstrip("\r\n")
    require(key and password, "Configure KANJI_SIGNING_KEY_B64 and KANJI_SIGNING_PASSWORD repository secrets")
    require("\n" not in password and "\r" not in password, "Signing password must be one line")
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kanji-signing-", dir=os.environ.get("RUNNER_TEMP")) as temp:
        key_path, password_path = Path(temp) / "key.p12", Path(temp) / "password.txt"
        for path, content in ((key_path, base64.b64decode("".join(key.split()), validate=True)),
                              (password_path, (password + "\n").encode())):
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(content)
        run([tool(sdk, "apksigner"), "sign", "--ks", key_path, "--ks-key-alias", "kanji-hour",
             "--ks-pass", "file:" + str(password_path), "--v4-signing-enabled", "false",
             "--out", directory / APK, source / APK])
    verify_apk(sdk, directory / APK, info)
    info["apk_sha256"] = digest((directory / APK).read_bytes())
    (directory / (APK + ".sha256")).write_text(info["apk_sha256"] + "  " + APK + "\n")
    save(directory / "build-info.json", info)


def validate_run(candidate, repo, workflow_id):
    require(candidate["repository"]["full_name"].lower() == repo.lower(), "Foreign repository")
    require(candidate["workflow_id"] == workflow_id and candidate["path"] == WORKFLOW, "Wrong workflow")
    require(candidate["event"] == "workflow_dispatch" and candidate["head_branch"] == "trunk", "Not a trunk candidate")
    require(candidate["status"] == "completed" and candidate["conclusion"] == "success", "Candidate has not passed checks")
    require(candidate["run_attempt"] == 1, "Re-run candidates are not eligible")
    require(re.fullmatch(r"[0-9a-f]{40}", candidate["head_sha"]), "Invalid commit")


def unpack(raw, artifact_digest, candidate, repo, directory):
    require(len(raw) <= 130 * 1024 * 1024, "Oversized artifact")
    require(artifact_digest == "sha256:" + digest(raw), "Artifact ZIP digest mismatch")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = archive.infolist()
        require(len(entries) == len(FILES) and {x.filename for x in entries} == FILES, "Unexpected artifact files")
        require(all(not x.is_dir() and x.file_size <= (128 * 1024 * 1024 if x.filename == APK else 65536)
                    and ((x.external_attr >> 16) & 0o170000) != 0o120000 for x in entries), "Unsafe artifact entry")
        files = {x.filename: archive.read(x) for x in entries}
    info = json.loads(files["build-info.json"])
    require(info["schema"] == 1 and info["repository"].lower() == repo.lower(), "Wrong schema/repository")
    require(info["run_id"] == candidate["id"] and info["run_attempt"] == 1 and info["commit"] == candidate["head_sha"],
            "Candidate provenance mismatch")
    require(type(info["version_code"]) is int and info["version_code"] == VERSION_BASE + candidate["run_number"]
            and info["version_code"] <= 2100000000, "Wrong versionCode")
    require(re.fullmatch(VERSION, info["version_name"]), "Invalid versionName")
    require(info["package"] == PACKAGE and info["certificate_sha256"] == CERTIFICATE
            and info["min_sdk"] == 34 and info["target_sdk"] == 34, "Unexpected APK identity")
    require(info["apk_sha256"] == digest(files[APK]), "APK digest mismatch")
    require(files[APK + ".sha256"].decode() == info["apk_sha256"] + "  " + APK + "\n", "Wrong checksum file")
    directory.mkdir(parents=True, exist_ok=True)
    for name, content in files.items():
        (directory / name).write_bytes(content)
    return info


def set_status(repo, sha, run_id, state, description):
    api(repo, f"statuses/{sha}", {"state": state, "context": f"release/phone/{run_id}",
        "description": description, "target_url": f"https://github.com/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}"})


def ready(directory):
    guard(first_attempt=True)
    info = json.loads((directory / "build-info.json").read_text())
    set_status(info["repository"], info["commit"], info["run_id"], "pending", "APK ready; phone acceptance required")
    summary(f"## Кандидат {info['run_id']} — ещё не релиз\n\n"
            f"Версия: `{info['version_name']}`; versionCode: `{info['version_code']}`.\n\n"
            f"Коммит: `{info['commit']}`.\n\nSHA-256 APK: `{info['apk_sha256']}`.\n\n"
            f"[Скачать APK-кандидат]({os.environ['CANDIDATE_URL']})\n\n"
            "После проверки на телефоне запустите **Review release** из trunk. "
            "Нужны номер кандидата, решение, SHA-256 APK и результат проверки. "
            "Инструкция: `docs/RELEASING.md`. Кандидат хранится 14 дней.")


def marker(info):
    return f"<!-- kanji-hour-candidate:{info['run_id']}:{info['apk_sha256']} -->"


def publishable(releases, info):
    own = None
    for release in releases:
        if release["tag_name"] == "v" + info["version_name"]:
            require(marker(info) in (release.get("body") or ""), "Version belongs to a different candidate")
            own = release
        if not release["draft"] and marker(info) not in (release.get("body") or ""):
            codes = re.findall(r"<!-- kanji-hour-version-code:([0-9]+) -->", release.get("body") or "")
            require(len(codes) == 1, "Existing release has no versionCode metadata; reconcile it first")
            require(int(codes[0]) < info["version_code"], "Cannot publish an older candidate after a newer release")
    return own


def publish(repo, info, directory, report, releases):
    tag = "v" + info["version_name"]
    release = publishable(releases, info)
    ref = api(repo, f"git/ref/tags/{tag}", missing_ok=True)
    if ref:
        require(release is not None and ref["object"]["type"] == "commit" and ref["object"]["sha"] == info["commit"],
                "Existing tag must not be moved/reused")
    if release is None:
        # Draft first: an interruption cannot leave an unowned tag that blocks retries.
        quoted_report = "\n".join("> " + line.replace("<", "&lt;") for line in report.splitlines())
        body = (f"{marker(info)}\n<!-- kanji-hour-version-code:{info['version_code']} -->\n"
                f"Коммит: `{info['commit']}`. Кандидат: `{info['run_id']}`.\n\n"
                f"SHA-256 APK: `{info['apk_sha256']}`.\n\nПроверил: `{os.environ['GITHUB_ACTOR']}`.\n\n" + quoted_report)
        release = api(repo, "releases", {"tag_name": tag, "target_commitish": info["commit"],
            "name": "Кандзи · час " + info["version_name"], "body": body, "draft": True, "prerelease": False})
    if ref is None:
        api(repo, "git/refs", {"ref": "refs/tags/" + tag, "sha": info["commit"]})
    expected = {name: digest((directory / name).read_bytes()) for name in FILES}
    assets = {a["name"]: a for a in api(repo, f"releases/{release['id']}/assets?per_page=100")}
    require(set(assets) <= FILES, "Unexpected release assets")
    for name, checksum in expected.items():
        if name in assets:
            require(digest(api(repo, f"releases/assets/{assets[name]['id']}", binary=True,
                              accept="application/octet-stream")) == checksum,
                    "Existing release asset differs; refusing overwrite")
        else:
            require(release["draft"], "Published release is incomplete; refusing modification")
            run(["gh", "release", "upload", tag, directory / name, "--repo", repo])
    assets = {a["name"]: a for a in api(repo, f"releases/{release['id']}/assets?per_page=100")}
    require(set(assets) == FILES, "Incomplete release upload")
    for name, checksum in expected.items():
        require(digest(api(repo, f"releases/assets/{assets[name]['id']}", binary=True,
                          accept="application/octet-stream")) == checksum,
                "Uploaded release asset mismatch")
    if release["draft"]:
        release = api(repo, f"releases/{release['id']}", {"draft": False, "make_latest": "true"}, method="PATCH")
    return release["html_url"]


def review(directory):
    guard()
    repo = os.environ["GITHUB_REPOSITORY"]
    run_id = number(os.environ["CANDIDATE_RUN_ID"])
    decision = os.environ["DECISION"]
    report = os.environ.get("PHONE_REPORT", "").strip()
    require(decision in ("approve", "reject"), "Choose approve or reject")
    require(10 <= len(report) <= 4000, "Describe phone, OS and result/reason (10–4000 characters)")
    tested_sha = os.environ.get("TESTED_SHA256", "").strip().lower()
    save(ROOT / "build/review/review.json", {"candidate_run_id": run_id, "decision": decision,
        "report": report, "actor": os.environ["GITHUB_ACTOR"], "review_run_id": number(os.environ["GITHUB_RUN_ID"]),
        "tested_sha256": tested_sha})
    candidate = api(repo, f"actions/runs/{run_id}")
    validate_run(candidate, repo, api(repo, "actions/workflows/prepare-release.yml")["id"])
    compared = api(repo, f"compare/{candidate['head_sha']}...{os.environ['GITHUB_SHA']}")
    require(compared["status"] in ("ahead", "identical"), "Candidate is no longer an ancestor of trunk")
    prior = next((s for s in pages(repo, f"commits/{candidate['head_sha']}/statuses")
                  if s["context"] == f"release/phone/{run_id}"), None)
    require(prior is not None, "Candidate was not marked ready for phone acceptance")
    require(prior["state"] != "failure", "Candidate was rejected permanently; prepare a new one")
    releases = list(pages(repo, "releases"))
    if decision == "reject":
        require(prior["state"] != "success" and not any(not r["draft"] and
            f"<!-- kanji-hour-candidate:{run_id}:" in (r.get("body") or "") for r in releases),
            "Already published; rejection cannot unpublish a release")
        set_status(repo, candidate["head_sha"], run_id, "failure", "Rejected on phone; prepare a new candidate")
        summary(f"## Кандидат {run_id} отклонён — релиз не опубликован\n\n" + report)
        raise ValueError("Phone acceptance rejected this candidate")
    require(re.fullmatch(r"[0-9a-f]{64}", tested_sha), "Paste the SHA-256 of the APK you tested")
    artifacts = api(repo, f"actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    matches = [a for a in artifacts if a["name"] == f"release-candidate-{run_id}" and not a["expired"]]
    require(len(matches) == 1, "Candidate artifact is missing, expired or ambiguous; prepare a new one")
    artifact = matches[0]
    require(artifact["workflow_run"]["id"] == run_id and artifact["workflow_run"]["head_sha"] == candidate["head_sha"],
            "Artifact origin mismatch")
    raw = api(repo, f"actions/artifacts/{artifact['id']}/zip", binary=True)
    info = unpack(raw, artifact.get("digest"), candidate, repo, directory)
    require(info["apk_sha256"] == tested_sha, "You approved a different APK checksum")
    # No build, signing or APK rewriting occurs during review/publication.
    url = publish(repo, info, directory, report, releases)
    set_status(repo, info["commit"], run_id, "success", "Phone acceptance passed; exact tested APK published")
    summary(f"## Релиз состоялся\n\n{url}\n\nAPK SHA-256: `{info['apk_sha256']}`")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "sign", "ready", "review"))
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "kanji-sdk")
    parser.add_argument("--source", type=Path, default=ROOT / "build/unsigned")
    parser.add_argument("--directory", type=Path, default=ROOT / "build/candidate")
    args = parser.parse_args()
    try:
        if args.command == "prepare": prepare(args.sdk, args.directory)
        elif args.command == "sign": sign(args.sdk, args.source, args.directory)
        elif args.command == "ready": ready(args.directory)
        else: review(args.directory)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, KeyError, zipfile.BadZipFile) as error:
        print("Release stopped: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
