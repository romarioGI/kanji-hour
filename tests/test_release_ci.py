"""Tests for provenance, manual decisions, and publication without rebuilding."""
import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import release_ci as release
import build_native
from check import check_data

REPO = "romarioGI/kanji-hour"
SHA = "a" * 40
RUN = {"id": 123, "repository": {"full_name": REPO}, "workflow_id": 9,
       "path": release.WORKFLOW, "event": "workflow_dispatch", "head_branch": "trunk",
       "status": "completed", "conclusion": "success", "run_attempt": 1,
       "head_sha": SHA, "run_number": 10}
INFO = {"schema": 1, "repository": REPO, "run_id": 123, "run_attempt": 1, "commit": SHA,
        "version_name": "0.3.0", "version_code": release.VERSION_BASE + 10,
        "package": release.PACKAGE, "certificate_sha256": release.CERTIFICATE,
        "min_sdk": 34, "target_sdk": 34, "apk_sha256": release.digest(b"tested APK")}


def candidate_zip(info=None, apk=b"tested APK", extra=None):
    info = INFO if info is None else info
    files = {release.APK: apk, "build-info.json": json.dumps(info).encode(),
             release.APK + ".sha256": (info["apk_sha256"] + "  " + release.APK + "\n").encode()}
    if extra:
        files.update(extra)
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, content in files.items():
            archive.writestr(name, content)
    return stream.getvalue()


class CandidateTests(unittest.TestCase):
    def test_good_run(self):
        release.validate_run(RUN, REPO, 9)

    def test_ineligible_runs(self):
        for key, value in (("event", "pull_request"), ("head_branch", "feature"),
                           ("conclusion", "failure"), ("status", "in_progress"),
                           ("run_attempt", 2), ("workflow_id", 10), ("path", "other.yml"),
                           ("repository", {"full_name": "foreign/repository"})):
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.validate_run(dict(RUN, **{key: value}), REPO, 9)

    def test_candidate_bytes_preserved(self):
        raw = candidate_zip()
        with tempfile.TemporaryDirectory() as temp:
            info = release.unpack(raw, "sha256:" + release.digest(raw), RUN, REPO, Path(temp))
            self.assertEqual(info, INFO)
            self.assertEqual((Path(temp) / release.APK).read_bytes(), b"tested APK")

    def test_wrong_archive_digest(self):
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(ValueError):
            release.unpack(candidate_zip(), "sha256:" + "0" * 64, RUN, REPO, Path(temp))

    def test_wrong_apk_and_metadata(self):
        bad = [candidate_zip(apk=b"other APK"), candidate_zip(dict(INFO, commit="b" * 40)),
               candidate_zip(dict(INFO, version_code=1)), candidate_zip(dict(INFO, run_id=999)),
               candidate_zip(dict(INFO, certificate_sha256="0" * 64)),
               candidate_zip(extra={"../key.p12": b"forbidden"})]
        for raw in bad:
            with self.subTest(raw=release.digest(raw)), tempfile.TemporaryDirectory() as temp, self.assertRaises(ValueError):
                release.unpack(raw, "sha256:" + release.digest(raw), RUN, REPO, Path(temp))

    def test_invalid_ids(self):
        for value in ("0", "-1", "1; echo bad", "1e3", "1.5", "../123", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.number(value)

    def test_dispatch_guard(self):
        env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/trunk", "GITHUB_RUN_ATTEMPT": "1"}
        with patch.dict(os.environ, env):
            release.guard(True)
        for key, value in (("GITHUB_REF", "refs/heads/feature"), ("GITHUB_EVENT_NAME", "push"), ("GITHUB_RUN_ATTEMPT", "2")):
            with patch.dict(os.environ, dict(env, **{key: value})), self.assertRaises(ValueError):
                release.guard(True)

    def test_no_version_reuse_or_downgrade(self):
        for row in ({"tag_name": "v0.3.0", "body": "other candidate", "draft": True},
                    {"tag_name": "v0.4.0", "body": "<!-- kanji-hour-version-code:100 -->", "draft": False},
                    {"tag_name": "v0.1.0", "body": "no metadata", "draft": False}):
            with self.subTest(row=row), self.assertRaises(ValueError):
                release.publishable([row], INFO)

    def test_own_draft_allows_retry(self):
        own = {"tag_name": "v0.3.0", "body": release.marker(INFO), "draft": True}
        self.assertIs(release.publishable([own], INFO), own)

    def test_missing_signing_key_never_falls_back(self):
        env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/trunk",
               "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": SHA, "GITHUB_RUN_ID": "123",
               "KANJI_SIGNING_KEY_B64": "", "KANJI_SIGNING_PASSWORD": ""}
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, env), patch.object(release, "run") as run:
            path = Path(temp)
            (path / "build-info.json").write_text(json.dumps(INFO))
            with self.assertRaisesRegex(ValueError, "repository secrets"):
                release.sign(path, path, path / "signed")
            run.assert_not_called()

    def test_native_version_bounds(self):
        for code in (0, -1, 2100000001):
            with self.subTest(code=code), patch.object(build_native, "find_java") as java, self.assertRaises(RuntimeError):
                build_native.build(Path("missing"), Path("missing.apk"), False, True, code)
            java.assert_not_called()

    def test_dictionary_rejects_duplicates(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "kanji.tsv"
            path.write_text("glyph\ton\tkun\tmeaning\n日\tニチ\tひ\tдень\n", encoding="utf-8")
            self.assertEqual(check_data(path), 1)
            path.write_text(path.read_text() + "日\tニチ\tひ\tдень\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                check_data(path)


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.raw = candidate_zip()
        self.prior = "pending"
        self.expired = False
        self.releases = []
        self.writes = []
        self.env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/trunk",
                    "GITHUB_REPOSITORY": REPO, "GITHUB_SHA": "b" * 40, "GITHUB_RUN_ID": "999",
                    "GITHUB_ACTOR": "reviewer", "GITHUB_STEP_SUMMARY": str(self.root / "summary"),
                    "CANDIDATE_RUN_ID": "123", "DECISION": "approve", "PHONE_REPORT": "POCO / Android 14: passed",
                    "TESTED_SHA256": INFO["apk_sha256"]}
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, self.env).start()
        patch.object(release, "ROOT", self.root).start()
        patch.object(release, "api", side_effect=self.api).start()
        self.publish = patch.object(release, "publish", return_value="https://github.com/release").start()

    def api(self, repo, endpoint, data=None, **kwargs):
        if endpoint == "actions/runs/123": return copy.deepcopy(RUN)
        if endpoint == "actions/workflows/prepare-release.yml": return {"id": 9}
        if endpoint.startswith("compare/"): return {"status": "ahead"}
        if endpoint.startswith("commits/"): return [{"context": "release/phone/123", "state": self.prior}]
        if endpoint.startswith("releases?"): return self.releases
        if endpoint.startswith("actions/runs/123/artifacts"):
            return {"artifacts": [{"id": 77, "name": "release-candidate-123", "expired": self.expired,
                    "workflow_run": {"id": 123, "head_sha": SHA}, "digest": "sha256:" + release.digest(self.raw)}]}
        if endpoint == "actions/artifacts/77/zip": return self.raw
        if endpoint.startswith("statuses/"):
            self.writes.append(data)
            return data
        raise AssertionError(endpoint)

    def test_approval_publishes_tested_bytes(self):
        release.review(self.root / "candidate")
        self.publish.assert_called_once()
        self.assertEqual((self.root / "candidate" / release.APK).read_bytes(), b"tested APK")
        self.assertEqual(self.writes[-1]["state"], "success")

    def test_rejection_fails_without_publication(self):
        os.environ["DECISION"] = "reject"
        with self.assertRaisesRegex(ValueError, "rejected"):
            release.review(self.root / "candidate")
        self.publish.assert_not_called()
        self.assertEqual(self.writes[-1]["state"], "failure")
        self.assertTrue((self.root / "build/review/review.json").is_file())

    def test_rejected_candidate_cannot_be_approved(self):
        self.prior = "failure"
        with self.assertRaisesRegex(ValueError, "permanently"):
            release.review(self.root / "candidate")
        self.publish.assert_not_called()

    def test_expired_candidate_cannot_be_published(self):
        self.expired = True
        with self.assertRaisesRegex(ValueError, "expired"):
            release.review(self.root / "candidate")
        self.publish.assert_not_called()

    def test_wrong_tested_checksum_blocks_publication(self):
        os.environ["TESTED_SHA256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "different APK"):
            release.review(self.root / "candidate")
        self.publish.assert_not_called()

    def test_publication_cannot_be_rejected_even_if_status_write_failed(self):
        os.environ["DECISION"] = "reject"
        self.releases = [{"draft": False, "body": release.marker(INFO)}]
        with self.assertRaisesRegex(ValueError, "Already published"):
            release.review(self.root / "candidate")
        self.assertFalse(self.writes)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        raw = candidate_zip()
        release.unpack(raw, "sha256:" + release.digest(raw), RUN, REPO, self.directory)
        self.draft = None
        self.ref = None
        self.assets = {}
        self.uploads = 0
        self.interrupt_tag = False
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {"GITHUB_ACTOR": "reviewer"}).start()
        patch.object(release, "api", side_effect=self.api).start()
        patch.object(release, "run", side_effect=self.upload).start()

    def api(self, repo, endpoint, data=None, **kwargs):
        if endpoint.startswith("git/ref/tags/"): return self.ref
        if endpoint == "git/refs":
            if self.interrupt_tag:
                self.interrupt_tag = False
                raise RuntimeError("Network error while creating tag")
            self.ref = {"object": {"type": "commit", "sha": data["sha"]}}
            return self.ref
        if endpoint == "releases":
            self.draft = dict(data, id=1, html_url="https://github.com/release")
            return self.draft
        if endpoint == "releases/1":
            self.draft.update(data)
            return self.draft
        if endpoint.startswith("releases/1/assets"):
            return [{"id": name, "name": name} for name in self.assets]
        if endpoint.startswith("releases/assets/"):
            return self.assets[endpoint.removeprefix("releases/assets/")]
        raise AssertionError(endpoint)

    def upload(self, args):
        self.assertEqual(args[:3], ["gh", "release", "upload"])
        path = Path(args[4])
        self.assertNotIn(path.name, self.assets)
        self.assets[path.name] = path.read_bytes()
        self.uploads += 1

    def publish(self):
        return release.publish(REPO, INFO, self.directory, "POCO / Android 14: passed",
                               [self.draft] if self.draft else [])

    def test_publication_is_idempotent_and_pins_tested_commit(self):
        url = self.publish()
        self.assertFalse(self.draft["draft"])
        self.assertEqual(self.ref["object"]["sha"], SHA)
        self.assertEqual(self.assets[release.APK], b"tested APK")
        self.assertEqual(self.publish(), url)
        self.assertEqual(self.uploads, 3)

    def test_retry_after_draft_created_but_tag_failed(self):
        self.interrupt_tag = True
        with self.assertRaises(RuntimeError):
            self.publish()
        self.assertTrue(self.draft["draft"])
        self.assertIsNone(self.ref)
        self.publish()
        self.assertFalse(self.draft["draft"])

    def test_existing_asset_is_never_overwritten(self):
        self.publish()
        self.assets[release.APK] = b"changed by someone else"
        with self.assertRaisesRegex(ValueError, "refusing overwrite"):
            self.publish()
        self.assertEqual(self.uploads, 3)


if __name__ == "__main__":
    unittest.main()
