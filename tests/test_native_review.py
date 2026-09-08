"""Native approval/rejection and retry regressions; GitHub responses are fixtures."""
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import release_ci as release
import release_issues as issues
from test_release_ci import INFO, REPO, RUN, SHA, candidate_zip


class NativeReviewTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.event = {"action": "completed", "repository": {"full_name": REPO}, "workflow_run": copy.deepcopy(RUN)}
        self.event_path = self.root / "event.json"
        self.event_path.write_text(json.dumps(self.event))
        self.comment = f"SHA-256: {INFO['apk_sha256']}\nPOCO / Android 14: passed"
        self.history = [{"state": "approved", "comment": self.comment,
                         "environments": [{"name": "release"}],
                         "user": {"type": "User", "login": "phone-reviewer"}}]
        self.prior = "pending"
        self.writes = []
        self.run_record = copy.deepcopy(RUN)
        self.raw = candidate_zip()
        env = {"GITHUB_EVENT_NAME": "workflow_run", "GITHUB_REF": "refs/heads/trunk",
               "GITHUB_REPOSITORY": REPO, "GITHUB_SHA": "b" * 40, "GITHUB_RUN_ID": "999",
               "GITHUB_RUN_ATTEMPT": "1", "GITHUB_ACTOR": "build-initiator",
               "GITHUB_EVENT_PATH": str(self.event_path), "GITHUB_STEP_SUMMARY": str(self.root / "summary"),
               # Old fields must never authorize publication.
               "CANDIDATE_RUN_ID": "123", "DECISION": "approve",
               "PHONE_REPORT": "POCO / Android 14: passed", "TESTED_SHA256": INFO["apk_sha256"]}
        self.enterContext(patch.dict(os.environ, env))
        self.enterContext(patch.object(release, "ROOT", self.root))
        self.api_mock = self.enterContext(patch.object(release, "api", side_effect=self.api))
        self.publish = self.enterContext(patch.object(release, "publish", return_value="https://github.com/example/release"))
        self.enterContext(patch.object(release, "run", side_effect=AssertionError("Must not build or sign during review")))

    def api(self, repo, endpoint, data=None, **kwargs):
        self.assertEqual(repo, REPO)
        if endpoint == "actions/runs/123": return copy.deepcopy(self.run_record)
        if endpoint == "actions/workflows/prepare-release.yml": return {"id": 9}
        if endpoint == "actions/runs/999/approvals": return copy.deepcopy(self.history)
        if endpoint.startswith("compare/"): return {"status": "ahead"}
        if endpoint.startswith("commits/"): return [{"context": "release/phone/123", "state": self.prior}]
        if endpoint.startswith("releases?"): return []
        if endpoint.startswith("actions/runs/123/artifacts"):
            return {"artifacts": [{"id": 77, "name": "release-candidate-123", "expired": False,
                    "workflow_run": {"id": 123, "head_sha": SHA}, "digest": "sha256:" + release.digest(self.raw)}]}
        if endpoint == "actions/artifacts/77/zip": return self.raw
        if endpoint.startswith("statuses/"):
            self.writes.append(data)
            return data
        raise AssertionError(endpoint)

    def blocked(self, message):
        with self.assertRaisesRegex(ValueError, message):
            release.review(self.root / "candidate")
        self.publish.assert_not_called()

    def test_approval_uses_real_reviewer_and_exact_bytes(self):
        release.review(self.root / "candidate")
        self.publish.assert_called_once()
        self.assertEqual(self.publish.call_args.kwargs["reviewer"], "phone-reviewer")
        self.assertEqual((self.root / "candidate" / release.APK).read_bytes(), b"tested APK")
        report = json.loads((self.root / "build/review/review.json").read_text())
        self.assertEqual((report["actor"], report["candidate_run_id"]), ("phone-reviewer", 123))
        self.assertEqual(self.writes[-1]["state"], "success")

    def test_no_native_approval_ignores_old_manual_fields(self):
        self.history = []
        self.blocked("approval is missing")
        self.assertFalse(self.writes)

    def test_legacy_manual_dispatch_cannot_publish(self):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        self.blocked("completed Prepare release event")
        self.api_mock.assert_not_called()

    def test_other_environment_and_non_approved_state_are_rejected(self):
        for change in ({"environments": [{"name": "signing"}]}, {"state": "bypassed"}):
            with self.subTest(change=change):
                original = copy.deepcopy(self.history)
                self.history[0].update(change)
                self.blocked("approval is missing")
                self.history = original

    def test_comment_requires_checksum_and_phone_report(self):
        for comment in ("Approved on phone", "SHA-256: " + INFO["apk_sha256"],
                        self.comment + "\nSHA-256: " + INFO["apk_sha256"], "x" * 4001):
            with self.subTest(comment=comment[:80]):
                self.history[0]["comment"] = comment
                self.blocked("comment|phone|long")

    def test_wrong_tested_checksum_cannot_publish(self):
        self.history[0]["comment"] = self.comment.replace(INFO["apk_sha256"], "0" * 64)
        self.blocked("different APK")

    def test_bot_is_not_a_human_reviewer(self):
        self.history[0]["user"]["type"] = "Bot"
        self.blocked("Human deployment reviewer")

    def test_rejection_records_failure_without_publication(self):
        self.history[0].update(state="rejected", comment="Widget failed on the phone")
        with self.assertRaisesRegex(ValueError, "rejected"):
            release.review(self.root / "candidate", reject_only=True)
        self.publish.assert_not_called()
        self.assertEqual(self.writes[-1]["state"], "failure")

    def test_rejected_history_wins_even_if_previous_status_write_failed(self):
        approved = copy.deepcopy(self.history[0])
        rejected = dict(approved, state="rejected", comment="Rejected on phone")
        os.environ["GITHUB_RUN_ATTEMPT"] = "2"
        for history in ([rejected, approved], [approved, rejected]):
            with self.subTest(history=history):
                self.history = history
                self.blocked("rejected")
                self.assertEqual(self.writes[-1]["state"], "failure")

    def test_finalizer_does_not_publish_or_invent_a_rejection(self):
        for history in ([], self.history):
            with self.subTest(history=history):
                self.history = history
                release.review(self.root / "candidate", reject_only=True)
                self.publish.assert_not_called()
                self.assertFalse(self.writes)

    def test_retry_accepts_only_consistent_native_approvals(self):
        os.environ["GITHUB_RUN_ATTEMPT"] = "2"
        self.history.append(copy.deepcopy(self.history[0]))
        release.review(self.root / "candidate")
        self.publish.assert_called_once()
        self.publish.reset_mock()
        self.history[0]["comment"] = self.comment.replace(INFO["apk_sha256"], "0" * 64)
        self.blocked("Conflicting APK")

    def test_event_cannot_switch_candidates_on_retry(self):
        self.event["workflow_run"]["head_sha"] = "c" * 40
        self.event_path.write_text(json.dumps(self.event))
        self.blocked("Trigger no longer matches")

    def test_foreign_event_and_untrusted_branch_stop_before_api_writes(self):
        self.event["repository"]["full_name"] = "foreign/repository"
        self.event_path.write_text(json.dumps(self.event))
        self.blocked("Foreign")
        os.environ["GITHUB_REF"] = "refs/heads/feature"
        self.blocked("trusted trunk")
        self.api_mock.assert_not_called()

    def test_failed_and_rerun_preparations_are_ineligible(self):
        for changes in ({"conclusion": "failure"}, {"run_attempt": 2}, {"path": "other.yml"}):
            with self.subTest(changes=changes):
                self.run_record = dict(RUN, **changes)
                self.blocked("checks|Re-run|workflow")

    def test_api_failure_never_publishes(self):
        self.api_mock.side_effect = RuntimeError("GitHub unavailable")
        with self.assertRaises(RuntimeError):
            release.review(self.root / "candidate")
        self.publish.assert_not_called()
        self.assertFalse(self.writes)

    def test_crlf_comment_from_windows_is_accepted(self):
        self.history[0]["comment"] = self.comment.replace("\n", "\r\n")
        release.review(self.root / "candidate")
        self.publish.assert_called_once()

    def test_issue_instructions_use_native_buttons_not_the_old_form(self):
        artifact = {"id": 77, "expires_at": "2026-09-22T08:19:00Z",
                    "digest": "sha256:" + release.digest(self.raw)}
        def issue_api(repo, endpoint, data=None, **kwargs):
            if endpoint == "actions/artifacts/77/zip": return self.raw
            self.assertEqual(endpoint, "issues")
            return data
        with patch.object(issues, "api", side_effect=issue_api):
            result = issues.new_issue(REPO, {"run": RUN, "artifact": artifact})
        body = result["body"]
        for expected in ("Review APK candidate 123", "Review deployments", "Approve and deploy",
                         "Reject", INFO["apk_sha256"], "`candidate_run_id`: `123`"):
            self.assertIn(expected, body)
        for removed in ("`decision`:", "`tested_sha256`:", "`phone_report`:"):
            self.assertNotIn(removed, body)


class NativeWorkflowTests(unittest.TestCase):
    """Regression assertions for this layout, not a server-settings audit."""
    def setUp(self):
        root = Path(__file__).resolve().parents[1]
        self.prepare = (root / ".github/workflows/prepare-release.yml").read_text()
        self.review = (root / ".github/workflows/review-release.yml").read_text()

    def test_only_native_trigger_and_post_approval_publication(self):
        self.assertIn("    workflows: [Prepare release]", self.review)
        self.assertNotIn("workflow_dispatch", self.review)
        self.assertNotIn("inputs.", self.review)
        self.assertNotIn("secrets.", self.review)
        acceptance, rest = self.review.split("\n  acceptance:\n", 1)[1].split("\n  publish:\n", 1)
        publish, rejected = rest.split("\n  rejected:\n", 1)
        self.assertIn("      name: release\n", acceptance)
        self.assertNotIn("write", acceptance)
        self.assertIn("    needs: acceptance\n", publish)
        self.assertIn("      contents: write\n", publish)
        self.assertNotIn("contents: write", rejected)
        self.assertIn("always()", rejected)
        self.assertIn("python3 tools/release_ci.py reject", rejected)
        self.assertNotIn("\nconcurrency:", self.review)
        self.assertIn("    concurrency:\n", publish)

    def test_signing_secrets_are_not_used_before_or_after_sign(self):
        before, rest = self.prepare.split("\n  sign:\n", 1)
        sign, after = rest.split("\n  smoke:\n", 1)
        self.assertNotIn("secrets.", before + after)
        self.assertIn("    environment: signing\n", sign)
        self.assertIn("    needs: build\n", sign)
        self.assertNotIn("environment: release", self.prepare)
        self.assertIn("ref: ${{ github.sha }}", self.review)
        self.assertNotIn("ref: ${{ github.event.workflow_run.head_sha }}", self.review)


if __name__ == "__main__":
    unittest.main()
