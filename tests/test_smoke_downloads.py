"""Exercise previous-release downloads through the real API wrapper, with fake gh I/O."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import release_ci as release
import smoke_apk as smoke

REPO = "romarioGI/kanji-hour"


class PreviousReleaseDownloadsTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name) / "previous"
        self.apk = b"PK\x03\x04\x00\xff\x80tested APK"
        self.info = {"package": release.PACKAGE, "version_code": 5,
                     "apk_sha256": release.digest(self.apk)}
        self.assets = [{"id": 11, "name": "build-info.json"}, {"id": 12, "name": release.APK}]
        self.error = None
        self.requests = []
        self.enterContext(patch.object(release.subprocess, "run", side_effect=self.command))

    def command(self, args, **kwargs):
        self.assertEqual(args[:4], ["gh", "api", "--method", "GET"])
        endpoint = args[-1].removeprefix(f"repos/{REPO}/")
        headers = [args[i + 1] for i, value in enumerate(args[:-1]) if value == "-H"]
        accept, = [header for header in headers if header.startswith("Accept:")]
        self.requests.append((endpoint, accept))
        if self.error:
            return subprocess.CompletedProcess(args, 1, stdout=b"", stderr=self.error)
        if endpoint == "releases/latest":
            self.assertEqual(accept, "Accept: application/vnd.github+json")
            content = json.dumps({"assets": self.assets}).encode()
        else:
            asset = next(a for a in self.assets if endpoint == f"releases/assets/{a['id']}")
            # GitHub's default JSON response describes the asset; it is NOT the file.
            # This reproduces KeyError('package') when the binary Accept is omitted.
            content = json.dumps(asset).encode()
            if accept == "Accept: application/octet-stream":
                content = json.dumps(self.info).encode() if asset["id"] == 11 else self.apk
        return subprocess.CompletedProcess(args, 0, stdout=content, stderr=b"")

    def test_downloads_metadata_file_and_unchanged_apk_bytes(self):
        self.assertEqual(smoke.previous(REPO, self.directory), self.info)
        self.assertEqual((self.directory / release.APK).read_bytes(), self.apk)
        self.assertEqual(self.requests, [
            ("releases/latest", "Accept: application/vnd.github+json"),
            ("releases/assets/11", "Accept: application/octet-stream"),
            ("releases/assets/12", "Accept: application/octet-stream"),
        ])

    def test_wrong_package_or_checksum_is_not_saved_or_skipped(self):
        good = dict(self.info)
        for changes in ({"package": "other.app"}, {"apk_sha256": "0" * 64}):
            with self.subTest(changes=changes):
                self.info = dict(good, **changes)
                with self.assertRaisesRegex(ValueError, "Previous release checksum mismatch"):
                    smoke.previous(REPO, self.directory)
                self.assertFalse(self.directory.exists())

    def test_missing_assets_fail_instead_of_skipping_upgrade(self):
        for missing in (11, 12):
            with self.subTest(missing=missing):
                self.assets = [a for a in (
                    {"id": 11, "name": "build-info.json"}, {"id": 12, "name": release.APK}
                ) if a["id"] != missing]
                with self.assertRaisesRegex(ValueError, "lacks APK/metadata"):
                    smoke.previous(REPO, self.directory)
                self.assertFalse(self.directory.exists())

    def test_no_published_release_is_the_only_skip(self):
        self.error = b"gh: Not Found (HTTP 404)"
        self.assertIsNone(smoke.previous(REPO, self.directory))
        self.assertEqual(len(self.requests), 1)
        self.assertFalse(self.directory.exists())

    def test_api_error_is_not_mistaken_for_no_previous_release(self):
        self.error = b"gh: Internal Server Error (HTTP 500)"
        with self.assertRaisesRegex(RuntimeError, "GitHub API failed"):
            smoke.previous(REPO, self.directory)
        self.assertFalse(self.directory.exists())


if __name__ == "__main__":
    unittest.main()
