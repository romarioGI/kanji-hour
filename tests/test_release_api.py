"""Exercise the gh transport, not just mocked high-level API responses."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import release_ci as release

REPO = "romarioGI/kanji-hour"


class ReleaseApiTests(unittest.TestCase):
    def assert_accept(self, args, expected):
        headers = [args[i + 1] for i, value in enumerate(args[:-1]) if value == "-H"]
        self.assertEqual([h for h in headers if h.startswith("Accept:")], ["Accept: " + expected])

    def test_artifact_download_keeps_api_accept_and_raw_bytes(self):
        raw = b"PK\x03\x04\x00\xff"
        with patch.object(release.subprocess, "run", return_value=
                          subprocess.CompletedProcess([], 0, stdout=raw, stderr=b"")) as command:
            self.assertEqual(release.api(REPO, "actions/artifacts/77/zip", binary=True), raw)
        args = command.call_args.args[0]
        self.assertEqual(args[:4], ["gh", "api", "--method", "GET"])
        self.assertIn(f"repos/{REPO}/actions/artifacts/77/zip", args)
        self.assert_accept(args, "application/vnd.github+json")

    def test_json_request_and_response_still_work(self):
        payload = {"title": "Review candidate"}
        with patch.object(release.subprocess, "run", return_value=
                          subprocess.CompletedProcess([], 0, stdout=b'{"number": 7}', stderr=b"")) as command:
            self.assertEqual(release.api(REPO, "issues", payload), {"number": 7})
        args = command.call_args.args[0]
        self.assertEqual(args[:4], ["gh", "api", "--method", "POST"])
        self.assertEqual(args[-2:], ["--input", "-"])
        self.assertEqual(json.loads(command.call_args.kwargs["input"]), payload)
        self.assert_accept(args, "application/vnd.github+json")

    def test_publication_downloads_new_and_existing_assets_as_octet_stream(self):
        info = {"version_name": "0.3.0", "version_code": 12, "commit": "a" * 40,
                "run_id": 123, "apk_sha256": release.digest(b"APK\x00\xff")}
        files = {release.APK: b"APK\x00\xff", "build-info.json": json.dumps(info).encode(),
                 release.APK + ".sha256": (info["apk_sha256"] + "  " + release.APK + "\n").encode()}
        names = dict(enumerate(sorted(files), 1))
        own = {"id": 1, "tag_name": "v0.3.0", "draft": True, "body": release.marker(info),
               "html_url": f"https://github.com/{REPO}/releases/tag/v0.3.0"}
        for already_uploaded in (False, True):
            with self.subTest(already_uploaded=already_uploaded), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                for name, content in files.items():
                    (directory / name).write_bytes(content)
                assets = dict(files) if already_uploaded else {}
                downloads, uploads = [], []

                def command(args, **kwargs):
                    if args[:3] == ["gh", "release", "upload"]:
                        path = Path(args[4])
                        self.assertNotIn(path.name, assets)
                        assets[path.name] = path.read_bytes()
                        uploads.append(path.name)
                        return subprocess.CompletedProcess(args, 0, stdout=b"", stderr=b"")
                    self.assertEqual(args[:2], ["gh", "api"])
                    endpoint = next(a for a in args if a.startswith(f"repos/{REPO}/"))
                    endpoint = endpoint.removeprefix(f"repos/{REPO}/")
                    if endpoint.startswith("releases/assets/"):
                        self.assert_accept(args, "application/octet-stream")
                        name = names[int(endpoint.rsplit("/", 1)[1])]
                        downloads.append(name)
                        return subprocess.CompletedProcess(args, 0, stdout=assets[name], stderr=b"")
                    self.assert_accept(args, "application/vnd.github+json")
                    if endpoint == "git/ref/tags/v0.3.0":
                        result = {"object": {"type": "commit", "sha": info["commit"]}}
                    elif endpoint == "releases/1/assets?per_page=100":
                        result = [{"id": i, "name": name} for i, name in names.items() if name in assets]
                    elif endpoint == "releases/1":
                        self.assertEqual(args[3], "PATCH")
                        self.assertFalse(json.loads(kwargs["input"])["draft"])
                        result = dict(own, draft=False)
                    else:
                        self.fail("Unexpected API endpoint: " + endpoint)
                    return subprocess.CompletedProcess(args, 0, stdout=json.dumps(result).encode(), stderr=b"")

                with patch.object(release.subprocess, "run", side_effect=command):
                    self.assertEqual(release.publish(REPO, info, directory, "Phone checked", [own]), own["html_url"])
                self.assertEqual(assets, files)
                self.assertCountEqual(uploads, [] if already_uploaded else files)
                self.assertCountEqual(downloads, list(files) * (2 if already_uploaded else 1))


if __name__ == "__main__":
    unittest.main()
