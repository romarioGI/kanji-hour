"""Exercise release issue reconciliation against an in-memory GitHub API."""
import copy
from datetime import datetime, timezone
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import release_ci as release
import release_issues as issues
from test_release_ci import INFO, REPO, RUN, candidate_zip

NOW = datetime(2026, 9, 8, 15, 0, tzinfo=timezone.utc)
EXPIRY = "2026-09-22T08:19:00Z"


class GitHub:
    """Paginate responses, apply writes, and record API activity."""

    def __init__(self):
        self.runs = []
        self.statuses = {}
        self.artifacts = {}
        self.archives = {}
        self.issues = []
        self.comments = {}
        self.releases = []
        self.reads = []
        self.writes = []
        self.fail_create = False
        self.fail_close = False

    def candidate(self, run_id=123, run_number=10, state="pending", **changes):
        run = dict(copy.deepcopy(RUN), id=run_id, run_number=run_number,
                   head_sha=f"{run_id:040x}", **changes)
        self.runs.append(run)
        info = dict(INFO, run_id=run_id, commit=run["head_sha"],
                    version_code=release.VERSION_BASE + run_number)
        raw = candidate_zip(info)
        artifact = {"id": 10000 + run_id, "name": f"release-candidate-{run_id}",
                    "expired": False, "expires_at": EXPIRY,
                    "workflow_run": {"id": run_id, "head_sha": run["head_sha"]},
                    "digest": "sha256:" + release.digest(raw)}
        self.archives[artifact["id"]] = raw
        self.artifacts[run_id] = [artifact]
        self.statuses[run["head_sha"]] = [] if state is None else [
            {"context": f"release/phone/{run_id}", "state": state,
             "target_url": f"https://github.com/{REPO}/actions/runs/{20000 + run_id}"}]
        return run, artifact

    def task(self, number=9, run_id=123, code=12, state="open", **changes):
        row = {"number": number, "state": state, "title": "Existing review",
               "body": f"<!-- kanji-hour-review:{run_id}:{code} -->\n- [x] Already tested\nMy notes",
               "html_url": f"https://github.com/{REPO}/issues/{number}"}
        row.update(changes)
        self.issues.append(row)
        return row

    def published(self, run_id=123, code=12, draft=False):
        row = {"id": len(self.releases) + 1, "draft": draft,
               "body": f"<!-- kanji-hour-candidate:{run_id}:{'f' * 64} -->\n"
                       f"<!-- kanji-hour-version-code:{code} -->",
               "html_url": f"https://github.com/{REPO}/releases/tag/v{code}"}
        self.releases.append(row)
        return row

    def api(self, repo, endpoint, data=None, method=None, **kwargs):
        assert repo == REPO
        method = method or ("POST" if data is not None else "GET")
        path = urlsplit(endpoint).path
        if method != "GET":
            self.writes.append((method, endpoint, copy.deepcopy(data)))
            if path == "issues" and method == "POST":
                if self.fail_create:
                    raise RuntimeError("Issue creation unavailable")
                return copy.deepcopy(self.task(number=max((i["number"] for i in self.issues), default=9) + 1,
                                               **data))
            if path.endswith("/comments"):
                number = int(path.split("/")[1])
                self.comments.setdefault(number, []).append(copy.deepcopy(data))
                return copy.deepcopy(data)
            if path.startswith("issues/") and method == "PATCH":
                if self.fail_close:
                    self.fail_close = False
                    raise RuntimeError("Closing unavailable")
                number = int(path.split("/")[1])
                row = next(i for i in self.issues if i["number"] == number)
                row.update(data)
                return copy.deepcopy(row)
            raise AssertionError((method, endpoint, data))
        self.reads.append(endpoint)
        if path == "actions/workflows/prepare-release.yml":
            return {"id": RUN["workflow_id"]}
        if path.startswith("actions/artifacts/") and path.endswith("/zip"):
            assert kwargs.get("binary")
            return self.archives[int(path.split("/")[2])]
        if path.startswith("issues/") and not path.endswith("/comments"):
            return copy.deepcopy(next(i for i in self.issues if i["number"] == int(path.split("/")[1])))
        key = None
        if path == "actions/workflows/prepare-release.yml/runs":
            values, key = self.runs, "workflow_runs"
        elif path.startswith("actions/runs/") and path.endswith("/artifacts"):
            values, key = self.artifacts[int(path.split("/")[2])], "artifacts"
        elif path.startswith("commits/"):
            values = self.statuses[path.split("/")[1]]
        elif path == "issues":
            values = self.issues
        elif path.endswith("/comments"):
            values = self.comments.get(int(path.split("/")[1]), [])
        elif path == "releases":
            values = self.releases
        else:
            raise AssertionError(endpoint)
        params = parse_qs(urlsplit(endpoint).query)
        page = int(params["page"][0])
        size = int(params["per_page"][0])
        rows = copy.deepcopy(values[(page - 1) * size:page * size])
        return {key: rows} if key else rows


class ReleaseIssueTests(unittest.TestCase):
    def setUp(self):
        self.github = GitHub()
        self.addCleanup(patch.stopall)
        patch.object(issues, "api", side_effect=self.github.api).start()
        patch("sys.stdout", new=io.StringIO()).start()

    def sync(self):
        issues.sync(REPO, NOW)

    def test_create_verified_candidate_with_actual_artifact_expiry(self):
        self.github.candidate()
        self.sync()
        task, = self.github.issues
        self.assertEqual(issues.issue_identity(task), (123, 12))
        self.assertIn("22.09.2026 11:19 МСК", task["body"])
        self.assertIn("2026-09-22 08:19 UTC", task["body"])
        self.assertIn("actions/runs/123/artifacts/10123", task["body"])
        self.assertIn(INFO["apk_sha256"], task["body"])
        self.assertIn("`candidate_run_id`: `123`", task["body"])
        self.assertEqual(self.github.writes[0][0:2], ("POST", "issues"))

    def test_new_success_supersedes_old_task_only_after_creation(self):
        self.github.candidate()
        old = self.github.task()
        self.github.candidate(124, 11)
        self.sync()
        self.assertEqual(old["state"], "closed")
        self.assertEqual(old["state_reason"], "not_planned")
        self.assertEqual(issues.issue_identity(self.github.issues[-1]), (124, 13))
        self.assertEqual(self.github.writes[0][0:2], ("POST", "issues"))

    def test_failed_cancelled_and_in_progress_runs_do_not_supersede(self):
        self.github.candidate()
        original = self.github.task()
        for index, changes in enumerate(({"conclusion": "failure"}, {"conclusion": "cancelled"},
                                         {"status": "in_progress", "conclusion": None},
                                         {"run_attempt": 2}), start=1):
            self.github.candidate(123 + index, 10 + index, **changes)
        self.sync()
        self.assertEqual(original["state"], "open")
        self.assertEqual(self.github.writes, [])

    def test_repeat_and_out_of_order_runs_preserve_checklist_without_duplicates(self):
        self.github.candidate(125, 12)
        self.github.candidate(123, 10)
        self.github.candidate(124, 11)
        self.sync()
        task, = self.github.issues
        self.assertEqual(issues.issue_identity(task), (125, 14))
        task["body"] += "\n- [x] User added a result"
        body = task["body"]
        self.github.runs.reverse()
        self.sync()
        self.assertEqual(len(self.github.issues), 1)
        self.assertEqual(task["body"], body)
        self.assertEqual(len(self.github.writes), 1)

    def test_manually_closed_task_is_not_reopened_or_duplicated(self):
        self.github.candidate()
        task = self.github.task(state="closed")
        self.sync()
        self.assertEqual(task["state"], "closed")
        self.assertEqual(self.github.writes, [])

    def test_duplicate_task_closes_and_keeps_canonical_checklist(self):
        self.github.candidate()
        canonical = self.github.task()
        duplicate = self.github.task(number=10)
        body = canonical["body"]
        self.sync()
        self.assertEqual(canonical["state"], "open")
        self.assertEqual(canonical["body"], body)
        self.assertEqual(duplicate["state"], "closed")
        self.assertEqual(duplicate["state_reason"], "not_planned")
        self.assertIn(canonical["html_url"], self.github.comments[10][0]["body"])

    def test_create_failure_preserves_previous_open_task(self):
        self.github.candidate()
        previous = self.github.task()
        self.github.candidate(124, 11)
        self.github.fail_create = True
        with self.assertRaisesRegex(RuntimeError, "creation"):
            self.sync()
        self.assertEqual(previous["state"], "open")
        self.assertEqual(self.github.comments, {})

    def test_terminal_or_unavailable_latest_does_not_fall_back_to_old_run(self):
        for state, expired, missing in (("failure", False, False), ("success", False, False),
                                        (None, False, False), ("pending", True, False),
                                        ("pending", False, True)):
            with self.subTest(state=state, expired=expired, missing=missing):
                self.github = GitHub()
                self.github.candidate()
                self.github.task(state="closed")
                run, artifact = self.github.candidate(124, 11, state=state)
                if expired:
                    artifact["expires_at"] = "2026-09-01T00:00:00Z"
                if missing:
                    self.github.artifacts[run["id"]] = []
                with patch.object(issues, "api", side_effect=self.github.api):
                    self.sync()
                self.assertEqual(self.github.writes, [])

    def test_rejection_closes_latest_without_publishing(self):
        self.github.candidate(state="failure")
        task = self.github.task()
        self.sync()
        self.assertEqual(task["state"], "closed")
        self.assertEqual(task["state_reason"], "not_planned")
        self.assertIn("отклонён", self.github.comments[9][0]["body"])
        self.assertTrue(all(endpoint.startswith("issues/") for _, endpoint, _ in self.github.writes))

    def test_rejected_latest_also_supersedes_older_task_after_missed_prepare_event(self):
        self.github.candidate()
        old = self.github.task()
        self.github.candidate(124, 11, state="failure")
        self.sync()
        self.assertEqual(old["state"], "closed")
        self.assertEqual(old["state_reason"], "not_planned")
        self.assertEqual(len(self.github.issues), 1)

    def test_own_publication_closes_as_completed_even_if_status_is_still_pending(self):
        self.github.candidate()
        task = self.github.task()
        self.github.published()
        self.sync()
        self.assertEqual(task["state"], "closed")
        self.assertEqual(task["state_reason"], "completed")
        self.assertEqual(len(self.github.issues), 1)

    def test_newer_publication_closes_older_task_without_creating_a_replacement(self):
        self.github.candidate()
        task = self.github.task()
        self.github.published(125, 14)
        self.sync()
        self.assertEqual(task["state_reason"], "not_planned")
        self.assertEqual(len(self.github.issues), 1)

    def test_draft_and_older_publications_do_not_close_pending_review(self):
        self.github.candidate()
        task = self.github.task()
        self.github.published(125, 14, draft=True)
        self.github.published(122, 11)
        self.sync()
        self.assertEqual(task["state"], "open")
        self.assertEqual(self.github.writes, [])

    def test_unrelated_issues_pull_requests_and_ambiguous_markers_are_untouched(self):
        self.github.candidate()
        self.github.task(number=1, body="Check a different feature")
        self.github.task(number=2, run_id=100, code=1, pull_request={"url": "pull/2"})
        self.github.task(number=3, body="<!-- kanji-hour-review:100:1 -->\n<!-- kanji-hour-review:101:2 -->")
        self.github.task(number=4, body="<!-- kanji-hour-review:0:1 -->")
        self.sync()
        self.assertTrue(all(task["state"] == "open" for task in self.github.issues))
        self.assertEqual(len(self.github.writes), 1)

    def test_existing_newer_task_prevents_resurrection_when_run_history_is_deleted(self):
        self.github.candidate()
        task = self.github.task(run_id=125, code=14, state="closed")
        self.sync()
        self.assertEqual(task["state"], "closed")
        self.assertEqual(self.github.writes, [])

    def test_retry_after_comment_succeeds_but_close_fails_does_not_duplicate_comment(self):
        self.github.candidate(state="failure")
        task = self.github.task()
        body = task["body"]
        self.github.fail_close = True
        with self.assertRaisesRegex(RuntimeError, "Closing"):
            self.sync()
        self.sync()
        self.assertEqual(task["state"], "closed")
        self.assertEqual(task["body"], body)
        self.assertEqual(len(self.github.comments[9]), 1)

    def test_artifact_origin_or_digest_failure_cannot_create_or_close_tasks(self):
        self.github.candidate()
        previous = self.github.task()
        _, artifact = self.github.candidate(124, 11)
        artifact["workflow_run"]["id"] = 999
        with self.assertRaisesRegex(ValueError, "origin"):
            self.sync()
        artifact["workflow_run"]["id"] = 124
        artifact["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ValueError, "digest"):
            self.sync()
        self.assertEqual(previous["state"], "open")
        self.assertEqual(self.github.writes, [])

    def test_pagination_finds_late_runs_statuses_artifacts_and_existing_task(self):
        for index in range(100):
            self.github.candidate(1000 + index, 20 + index, conclusion="failure")
            self.github.task(number=1000 + index, body="Unrelated")
        run, artifact = self.github.candidate()
        self.github.task()
        self.github.statuses[run["head_sha"]][:0] = [
            {"context": f"unrelated/{i}", "state": "success"} for i in range(100)]
        self.github.artifacts[run["id"]][:0] = [
            dict(artifact, id=i, name=f"unrelated-{i}") for i in range(100)]
        self.sync()
        self.assertEqual(self.github.writes, [])
        for path in ("actions/workflows/prepare-release.yml/runs", "commits/", "artifacts", "issues?"):
            self.assertTrue(any(path in read and "page=2" in read for read in self.github.reads), path)

    def test_pagination_reads_release_metadata_beyond_first_page(self):
        self.github.candidate()
        task = self.github.task()
        self.github.releases.extend({"draft": True, "body": ""} for _ in range(100))
        self.github.published()
        self.sync()
        self.assertEqual(task["state_reason"], "completed")
        self.assertIn("releases?per_page=100&page=2", self.github.reads)


if __name__ == "__main__":
    unittest.main()
