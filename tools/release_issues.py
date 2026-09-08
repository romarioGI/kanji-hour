#!/usr/bin/env python3
"""Reconcile phone-review issues after release workflow events, without polling."""
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import tempfile
from zoneinfo import ZoneInfo

from release_ci import VERSION_BASE, api, require, unpack, validate_run

ISSUE_MARKER = re.compile(r"<!-- kanji-hour-review:([1-9][0-9]*):([1-9][0-9]*) -->")


def rows(repo, endpoint, key=None):
    separator = "&" if "?" in endpoint else "?"
    for page in range(1, 1001):
        result = api(repo, f"{endpoint}{separator}per_page=100&page={page}")
        batch = result[key] if key else result
        yield from batch
        if len(batch) < 100:
            return
    raise RuntimeError("Pagination limit reached")


def issue_identity(issue):
    matches = ISSUE_MARKER.findall(issue.get("body") or "")
    if issue.get("pull_request") or len(matches) != 1:
        return None
    return tuple(map(int, matches[0]))


def published_releases(repo):
    result = []
    for release in rows(repo, "releases"):
        if release["draft"]:
            continue
        body = release.get("body") or ""
        codes = re.findall(r"<!-- kanji-hour-version-code:([1-9][0-9]*) -->", body)
        candidates = re.findall(r"<!-- kanji-hour-candidate:([1-9][0-9]*):[0-9a-f]{64} -->", body)
        if len(codes) == 1:
            result.append({"code": int(codes[0]), "url": release["html_url"],
                           "run_id": int(candidates[0]) if len(candidates) == 1 else None})
    return sorted(result, key=lambda item: item["code"], reverse=True)


def latest_candidate(repo, now):
    workflow_id = api(repo, "actions/workflows/prepare-release.yml")["id"]
    eligible = []
    for candidate in rows(repo, "actions/workflows/prepare-release.yml/runs?branch=trunk&event=workflow_dispatch",
                          "workflow_runs"):
        try:
            validate_run(candidate, repo, workflow_id)
        except ValueError:
            continue
        eligible.append(candidate)
    if not eligible:
        return None
    candidate = max(eligible, key=lambda item: item["run_number"])
    run_id = candidate["id"]
    status = next((s for s in rows(repo, f"commits/{candidate['head_sha']}/statuses")
                   if s["context"] == f"release/phone/{run_id}"), None)
    result = {"run": candidate, "code": VERSION_BASE + candidate["run_number"],
              "status": status, "artifact": None}
    # A rejected/expired latest candidate must not bring an older issue back.
    if status is None or status["state"] != "pending":
        return result
    artifacts = [a for a in rows(repo, f"actions/runs/{run_id}/artifacts", "artifacts")
                 if a["name"] == f"release-candidate-{run_id}" and not a["expired"]
                 and datetime.fromisoformat(a["expires_at"].replace("Z", "+00:00")) > now]
    require(len(artifacts) <= 1, "Ambiguous candidate artifact")
    if artifacts:
        artifact = artifacts[0]
        require(artifact["workflow_run"]["id"] == run_id
                and artifact["workflow_run"]["head_sha"] == candidate["head_sha"], "Artifact origin mismatch")
        result["artifact"] = artifact
    return result


def new_issue(repo, candidate):
    run, artifact = candidate["run"], candidate["artifact"]
    raw = api(repo, f"actions/artifacts/{artifact['id']}/zip", binary=True)
    # Read validated data only; never execute anything from the candidate archive.
    with tempfile.TemporaryDirectory() as temp:
        info = unpack(raw, artifact.get("digest"), run, repo, Path(temp))
    deadline = datetime.fromisoformat(artifact["expires_at"].replace("Z", "+00:00"))
    deadline_msk = deadline.astimezone(ZoneInfo("Europe/Moscow")).strftime("%d.%m.%Y %H:%M МСК")
    url = f"https://github.com/{repo}"
    run_id = run["id"]
    body = (f"<!-- kanji-hour-review:{run_id}:{info['version_code']} -->\n"
            f"Кандидат **{info['version_name']}**, versionCode **{info['version_code']}**. "
            f"[Сборка]({url}/actions/runs/{run_id}) · коммит `{info['commit']}`.\n\n"
            f"[Скачать APK]({url}/actions/runs/{run_id}/artifacts/{artifact['id']}).\n\n"
            f"**Проверить до {deadline_msk}** ({deadline.strftime('%Y-%m-%d %H:%M UTC')}) — "
            "затем истечёт срок хранения APK.\n\n"
            "На POCO X5 Pro 5G, Android 14 / HyperOS 2:\n\n"
            "- [ ] Установить APK поверх предыдущей версии; проверить сохранение настроек.\n"
            "- [ ] Проверить виджет и экран блокировки: знак, чтения и значение видны целиком.\n"
            "- [ ] Проверить смену кандзи на границе часа с закрытым приложением и после перезагрузки.\n"
            "- [ ] Записать результат через Review release.\n\n"
            f"[Review release]({url}/actions/workflows/review-release.yml), ветка `trunk`:\n\n"
            f"- `candidate_run_id`: `{run_id}`\n"
            "- `decision`: `approve` или `reject`\n"
            f"- `tested_sha256`: `{info['apk_sha256']}` — сверить с файлом рядом с проверенным APK\n"
            "- `phone_report`: модель, версия ОС и результат проверки\n\n"
            "Новый готовый кандидат автоматически заменяет эту задачу. "
            "Закрытие issue не означает приёмку и не публикует APK.\n")
    return api(repo, "issues", {"title": f"Проверить APK {info['version_name']} ({info['version_code']}) до {deadline_msk}",
                                "body": body})


def close_issue(repo, issue, reason, message):
    # Preserve checklist edits. Retry safely if commenting succeeded but closing failed.
    current = api(repo, f"issues/{issue['number']}")
    if current["state"] != "open" or issue_identity(current) != issue_identity(issue):
        return
    marker = "<!-- kanji-hour-review-closure -->"
    comments = rows(repo, f"issues/{issue['number']}/comments")
    if not any(marker in (comment.get("body") or "") for comment in comments):
        api(repo, f"issues/{issue['number']}/comments", {"body": marker + "\n" + message})
    api(repo, f"issues/{issue['number']}", {"state": "closed", "state_reason": reason}, method="PATCH")


def sync(repo, now=None):
    now = now or datetime.now(timezone.utc)
    # Re-read current state for every event; event delivery order is not authoritative.
    issues = [i for i in rows(repo, "issues?state=all") if issue_identity(i)]
    releases = published_releases(repo)
    candidate = latest_candidate(repo, now)
    current = None
    if candidate and candidate["artifact"]:
        run_id, code = candidate["run"]["id"], candidate["code"]
        superseded = any(r["code"] >= code for r in releases) or any(issue_identity(i)[1] > code for i in issues)
        if not superseded:
            matches = [i for i in issues if issue_identity(i) == (run_id, code)]
            # Do not reopen a manually closed task or reset its checklist.
            current = min(matches, key=lambda i: i["number"]) if matches else new_issue(repo, candidate)
    # Create the replacement before closing old tasks, so API failures do not lose the review queue.
    for issue in issues:
        if issue["state"] != "open":
            continue
        run_id, code = issue_identity(issue)
        own_release = next((r for r in releases if r["run_id"] == run_id and r["code"] == code), None)
        newer_release = next((r for r in releases if r["code"] > code), None)
        if own_release:
            close_issue(repo, issue, "completed", f"Кандидат принят и [опубликован]({own_release['url']}).")
        elif newer_release:
            close_issue(repo, issue, "not_planned", f"Устарело: [опубликован более новый релиз]({newer_release['url']}).")
        elif current and code < issue_identity(current)[1]:
            close_issue(repo, issue, "not_planned", f"Устарело: проверка нового кандидата — {current['html_url']}.")
        elif current and issue_identity(issue) == issue_identity(current) and issue['number'] != current['number']:
            close_issue(repo, issue, "not_planned", f"Дубликат задачи: {current['html_url']}.")
        elif candidate and code <= candidate["code"] and candidate["status"] and candidate["status"]["state"] == "failure":
            message = ("Кандидат отклонён при проверке на телефоне." if run_id == candidate['run']['id']
                       else "Устарело: более новый кандидат уже проверен и отклонён.")
            close_issue(repo, issue, "not_planned", f"{message} [Результат проверки]({candidate['status']['target_url']}).")
    print(f"Review issues synchronized; current task: {current['html_url'] if current else 'none'}")


if __name__ == "__main__":
    sync(os.environ["GITHUB_REPOSITORY"])
