"""Candidate-cut regression tests; these do not assert live server protection."""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import release_refs as refs

SHA = "a" * 40
ENV = {
    "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/trunk",
    "GITHUB_RUN_ATTEMPT": "1", "GITHUB_REPOSITORY": "owner/repo",
    "GITHUB_SHA": SHA, "GITHUB_RUN_ID": "123",
}


class FakeApi:
    def __init__(self):
        self.status = "ahead"
        self.tags = {}
        self.calls = []
        self.created_sha = None

    def __call__(self, repo, endpoint, data=None, **kwargs):
        self.calls.append((repo, endpoint, copy.deepcopy(data), kwargs))
        if endpoint.startswith("compare/"):
            return {"status": self.status}
        if endpoint.startswith("git/ref/tags/"):
            return copy.deepcopy(self.tags.get(endpoint.removeprefix("git/ref/tags/")))
        if endpoint == "git/refs":
            tag = data["ref"].removeprefix("refs/tags/")
            if tag in self.tags:
                raise AssertionError("Attempted to recreate an existing tag")
            self.tags[tag] = {"ref": data["ref"], "object": {
                "type": "commit", "sha": self.created_sha or data["sha"]}}
            return copy.deepcopy(self.tags[tag])
        raise AssertionError(endpoint)

    @property
    def writes(self):
        return [call for call in self.calls if call[2] is not None]


class CandidateRefsTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeApi()

    def test_cut_pins_dispatch_sha_even_when_trunk_advanced(self):
        self.assertEqual(refs.cut(self.api, ENV), "rc/123")
        self.assertEqual(self.api.tags["rc/123"]["object"]["sha"], SHA)
        self.assertEqual(len(self.api.writes), 1)
        self.assertEqual(self.api.writes[0][1:3],
                         ("git/refs", {"ref": "refs/tags/rc/123", "sha": SHA}))

    def test_identical_trunk_is_accepted(self):
        self.api.status = "identical"
        self.assertEqual(refs.cut(self.api, ENV), "rc/123")

    def test_identical_existing_tag_is_verified_without_writes(self):
        refs.cut(self.api, ENV)
        self.api.calls.clear()
        self.assertEqual(refs.cut(self.api, ENV), "rc/123")
        self.assertFalse(self.api.writes)

    def test_existing_tag_is_never_retargeted(self):
        refs.cut(self.api, ENV)
        self.api.tags["rc/123"]["object"]["sha"] = "b" * 40
        self.api.calls.clear()
        with self.assertRaisesRegex(ValueError, "refusing to move/reuse"):
            refs.cut(self.api, ENV)
        self.assertFalse(self.api.writes)
        self.assertEqual(self.api.tags["rc/123"]["object"]["sha"], "b" * 40)

    def test_annotated_or_malformed_tag_is_not_reused(self):
        for change in ({"object": {"type": "tag", "sha": SHA}},
                       {"object": {}}, {"ref": "refs/tags/rc/999"}):
            with self.subTest(change=change):
                self.api = FakeApi()
                self.api.tags["rc/123"] = {"ref": "refs/tags/rc/123", "object": {
                    "type": "commit", "sha": SHA}}
                self.api.tags["rc/123"].update(change)
                with self.assertRaises(ValueError):
                    refs.cut(self.api, ENV)
                self.assertFalse(self.api.writes)

    def test_created_tag_is_read_back_and_checked(self):
        self.api.created_sha = "b" * 40
        with self.assertRaisesRegex(ValueError, "refusing to move/reuse"):
            refs.cut(self.api, ENV)
        self.assertEqual(len(self.api.writes), 1)
        self.assertEqual(self.api.calls[-1][1], "git/ref/tags/rc/123")

    def test_missing_tag_after_post_is_not_success(self):
        api = Mock(side_effect=[{"status": "ahead"}, None, {}, None])
        with self.assertRaises(ValueError):
            refs.cut(api, ENV)

    def test_non_trunk_events_and_reruns_are_rejected_before_api(self):
        for key, value in (("GITHUB_REF", "refs/heads/feature"),
                           ("GITHUB_REF", "refs/tags/v1.0.0"),
                           ("GITHUB_EVENT_NAME", "pull_request"),
                           ("GITHUB_EVENT_NAME", "push"),
                           ("GITHUB_RUN_ATTEMPT", "2")):
            with self.subTest(key=key, value=value):
                api = Mock()
                with self.assertRaises(ValueError):
                    refs.cut(api, dict(ENV, **{key: value}))
                api.assert_not_called()

    def test_dispatch_commit_must_still_belong_to_trunk(self):
        for status in ("behind", "diverged", "unknown"):
            with self.subTest(status=status):
                api = FakeApi()
                api.status = status
                with self.assertRaisesRegex(ValueError, "ancestor"):
                    refs.cut(api, ENV)
                self.assertFalse(api.writes)

    def test_invalid_identity_is_rejected_before_api(self):
        for key, value in (("GITHUB_RUN_ID", "0"), ("GITHUB_RUN_ID", "../123"),
                           ("GITHUB_RUN_ID", "1" * 21), ("GITHUB_SHA", "trunk"),
                           ("GITHUB_SHA", "A" * 40), ("GITHUB_REPOSITORY", "one/two/three")):
            with self.subTest(key=key, value=value):
                api = Mock()
                with self.assertRaises(ValueError):
                    refs.cut(api, dict(ENV, **{key: value}))
                api.assert_not_called()

    def test_new_run_creates_distinct_tag_without_touching_old_one(self):
        refs.cut(self.api, ENV)
        refs.cut(self.api, dict(ENV, GITHUB_RUN_ID="124", GITHUB_SHA="b" * 40))
        self.assertEqual(self.api.tags["rc/123"]["object"]["sha"], SHA)
        self.assertEqual(self.api.tags["rc/124"]["object"]["sha"], "b" * 40)
        self.assertEqual(set(self.api.tags), {"rc/123", "rc/124"})
        self.assertTrue(all(call[1] == "git/refs" for call in self.api.writes))

    def test_api_error_is_not_treated_as_missing_ref(self):
        api = Mock(side_effect=[{"status": "ahead"}, RuntimeError("HTTP 403")])
        with self.assertRaisesRegex(RuntimeError, "403"):
            refs.cut(api, ENV)
        self.assertEqual(api.call_count, 2)

    def test_cli_error_returns_failure(self):
        transport = Mock()
        transport.api.side_effect = RuntimeError("HTTP 403")
        with patch.dict(sys.modules, {"release_ci": transport}), patch.dict("os.environ", ENV), \
                patch("sys.stderr"):
            self.assertEqual(refs.main(), 1)
        transport.summary.assert_not_called()

    def test_cli_success_reports_snapshot_not_approval(self):
        transport = Mock()
        transport.api.side_effect = self.api
        with patch.dict(sys.modules, {"release_ci": transport}), patch.dict("os.environ", ENV):
            self.assertEqual(refs.main(), 0)
        self.assertIn("rc/123", transport.summary.call_args.args[0])
        self.assertIn("не означает", transport.summary.call_args.args[0])


class DesiredRulesetTests(unittest.TestCase):
    """Validate checked-in desired configuration, not live GitHub enforcement."""
    def load(self, name):
        return json.loads((ROOT / f".github/rulesets/{name}.json").read_text())

    def test_trunk_requires_pr_and_has_no_bypass_or_exclusions(self):
        config = self.load("trunk")
        self.assertEqual(config["target"], "branch")
        self.assertEqual(config["enforcement"], "active")
        self.assertEqual(config["bypass_actors"], [])
        self.assertEqual(config["conditions"]["ref_name"], {
            "include": ["refs/heads/trunk"], "exclude": []})
        rules = {rule["type"]: rule for rule in config["rules"]}
        self.assertTrue({"pull_request", "deletion", "non_fast_forward", "required_linear_history"} <= rules.keys())
        self.assertNotIn("update", rules)  # Would also block ordinary PR merges.
        self.assertEqual(rules["pull_request"]["parameters"]["allowed_merge_methods"], ["squash"])
        self.assertTrue(rules["pull_request"]["parameters"]["required_review_thread_resolution"])

    def test_required_check_is_existing_actions_check_not_phone_acceptance(self):
        rules = {rule["type"]: rule for rule in self.load("trunk")["rules"]}
        params = rules["required_status_checks"]["parameters"]
        self.assertEqual(params["required_status_checks"], [{"context": "Checks", "integration_id": 15368}])
        self.assertTrue(params["strict_required_status_checks_policy"])
        self.assertFalse(params["do_not_enforce_on_create"])

    def test_no_impossible_self_approval_requirement(self):
        params = next(rule["parameters"] for rule in self.load("trunk")["rules"]
                      if rule["type"] == "pull_request")
        self.assertEqual(params["required_approving_review_count"], 0)
        self.assertFalse(params["require_last_push_approval"])
        self.assertFalse(params["require_code_owner_review"])

    def test_release_tags_cannot_move_or_disappear_but_creation_remains_possible(self):
        config = self.load("release-tags")
        self.assertEqual(config["target"], "tag")
        self.assertEqual(config["enforcement"], "active")
        self.assertEqual(config["bypass_actors"], [])
        self.assertEqual(config["conditions"]["ref_name"], {
            "include": ["refs/tags/v*", "refs/tags/rc/*"], "exclude": []})
        self.assertEqual({rule["type"] for rule in config["rules"]}, {"update", "deletion"})

    def test_build_depends_on_fixed_sha_snapshot_job(self):
        workflow = (ROOT / ".github/workflows/prepare-release.yml").read_text()
        snapshot = workflow.split("\n  snapshot:\n", 1)[1].split("\n  build:\n", 1)[0]
        build = workflow.split("\n  build:\n", 1)[1].split("\n  sign:\n", 1)[0]
        self.assertIn("needs: preflight", snapshot)
        self.assertIn("contents: write", snapshot)
        self.assertIn("ref: ${{ github.sha }}", snapshot)
        self.assertIn("persist-credentials: false", snapshot)
        self.assertIn("run: python3 tools/release_refs.py", snapshot)
        self.assertNotIn("secrets.", snapshot)
        self.assertIn("needs: snapshot", build)
        self.assertNotIn("needs: preflight", build)
        self.assertIn("ref: ${{ github.sha }}", build)


if __name__ == "__main__":
    unittest.main()
