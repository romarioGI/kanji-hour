"""Smoke orchestration contracts; adb is simulated, not the Android platform race."""
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import smoke_apk as smoke


class SmokeScenarioTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.candidate = self.root / "candidate"
        self.candidate.mkdir()
        self.apk = self.candidate / smoke.APK
        self.apk.write_bytes(b"candidate APK")
        self.info = {"package": smoke.PACKAGE, "version_code": 7,
                     "apk_sha256": smoke.digest(self.apk.read_bytes())}
        (self.candidate / "build-info.json").write_text(json.dumps(self.info))
        self.old = dict(self.info, version_code=5)
        self.commands, self.installs = [], []
        self.dirty = False
        self.crash_after = None
        self.crash_text = "FATAL EXCEPTION: main\nProcess: " + smoke.PACKAGE + ", PID: 12\nreal crash"
        self.diagnostics_fail = False
        self.enterContext(patch.object(smoke, "LOGS", self.root / "logs"))
        self.enterContext(patch.object(smoke, "adb", side_effect=self.adb))
        self.enterContext(patch.object(smoke.time, "sleep"))
        self.enterContext(patch.dict(smoke.os.environ, {"GITHUB_REPOSITORY": "romarioGI/kanji-hour"}))
        self.previous = self.enterContext(patch.object(smoke, "previous", side_effect=self.download_old))

    def download_old(self, repo, directory):
        directory.mkdir(parents=True, exist_ok=True)
        (directory / smoke.APK).write_bytes(b"previous APK")
        return self.old

    def adb(self, *args):
        args = tuple(map(str, args))
        self.commands.append(args)
        if args == ("shell", "getprop", "ro.build.version.sdk"): return "34\n"
        if args == ("shell", "pm", "list", "packages", smoke.PACKAGE):
            return "package:" + smoke.PACKAGE + "\n" if self.dirty else ""
        if args[:1] == ("install",):
            self.installs.append(Path(args[-1]))
            return "Success\n"
        if args[:3] == ("shell", "am", "start"): return "Status: ok\n"
        if args == ("shell", "pidof", smoke.PACKAGE): return "44\n"
        if args == ("shell", "dumpsys", "package", smoke.PACKAGE):
            code = 7 if self.installs[-1] == self.apk else 5
            return f"versionCode={code} minSdk=34\n"
        if args == ("logcat", "-b", "all", "-c"): return ""
        if args == ("logcat", "-d", "-b", "crash"):
            return self.crash_text if self.crash_after == len(self.installs) else ""
        if args == ("logcat", "-d", "-b", "all"):
            if self.diagnostics_fail:
                raise RuntimeError("diagnostic adb unavailable")
            return "Full system log\n" + self.crash_text
        raise AssertionError("Unexpected/destructive adb command: " + repr(args))

    def execute(self, scenario):
        with patch.object(sys, "argv", ["smoke_apk.py", "--scenario", scenario,
                                         "--directory", str(self.candidate)]):
            smoke.main()

    def report(self):
        return json.loads((self.root / "logs/report.json").read_text())

    def test_fresh_installs_only_candidate_without_fetching_previous(self):
        self.execute("fresh")
        self.assertEqual(self.installs, [self.apk])
        self.previous.assert_not_called()
        self.assertTrue(self.report()["fresh_install"])
        self.assertEqual(self.report()["upgrade"], "not run")

    def test_upgrade_starts_from_old_and_never_uninstalls_or_clears_between(self):
        self.execute("upgrade")
        self.assertEqual([p.read_bytes() for p in self.installs], [b"previous APK", b"candidate APK"])
        self.assertFalse(self.report()["fresh_install"])
        self.assertEqual(self.report()["upgrade"], "passed")
        first_install = next(i for i, c in enumerate(self.commands) if c[0] == "install")
        for command in self.commands[first_install + 1:]:
            self.assertNotIn("uninstall", command)
            self.assertNotIn("clear", command)
            self.assertNotIn("force-stop", command)
            self.assertNotIn("-c", command)

    def test_crashes_in_each_scenario_are_not_filtered_or_retried(self):
        for scenario, after in (("fresh", 1), ("upgrade", 1), ("upgrade", 2)):
            with self.subTest(scenario=scenario, after=after):
                self.installs.clear()
                self.crash_after = after
                with self.assertRaisesRegex(ValueError, "App crash recorded"):
                    self.execute(scenario)
                self.assertEqual(len(self.installs), after)
                self.assertNotEqual(self.report()["upgrade"], "passed")
                self.assertIn("App crash recorded", self.report()["failure"])
                self.assertIn(self.crash_text, (self.root / "logs/crash.txt").read_text())

    def test_no_previous_release_is_an_explicit_skip(self):
        self.previous.side_effect = None
        self.previous.return_value = None
        with contextlib.redirect_stdout(io.StringIO()):
            self.execute("upgrade")
        self.assertEqual(self.installs, [])
        self.assertEqual(self.report()["upgrade"], "skipped: no previous published release")

    def test_download_failure_does_not_turn_into_skip_or_success(self):
        self.previous.side_effect = RuntimeError("download failed")
        with self.assertRaisesRegex(RuntimeError, "download failed"):
            self.execute("upgrade")
        self.assertEqual(self.installs, [])
        self.assertEqual(self.report()["upgrade"], "not run")
        self.assertEqual(self.report()["stage"], "download previous")

    def test_existing_installation_is_rejected_not_deleted(self):
        self.dirty = True
        with self.assertRaisesRegex(ValueError, "clean emulator"):
            self.execute("fresh")
        self.assertEqual(self.installs, [])
        self.previous.assert_not_called()

    def test_wrong_candidate_checksum_blocks_installation(self):
        self.apk.write_bytes(b"wrong APK")
        with self.assertRaisesRegex(ValueError, "Candidate checksum mismatch"):
            self.execute("fresh")
        self.assertEqual(self.installs, [])

    def test_candidate_must_be_newer_than_previous(self):
        self.old["version_code"] = 7
        with self.assertRaisesRegex(ValueError, "not newer"):
            self.execute("upgrade")
        self.assertEqual(self.installs, [])

    def test_diagnostic_error_does_not_replace_primary_failure(self):
        self.crash_after = 1
        self.diagnostics_fail = True
        with self.assertRaisesRegex(ValueError, "App crash recorded"):
            self.execute("fresh")
        self.assertIn("diagnostic adb unavailable", str(self.report()["diagnostic_errors"]))
        self.assertTrue((self.root / "logs/crash.txt").is_file())

    def test_scenario_is_required_not_silently_omitted(self):
        with patch.object(sys, "argv", ["smoke_apk.py"]), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as stopped:
                smoke.main()
        self.assertEqual(stopped.exception.code, 2)
        self.assertFalse(self.commands)


class SmokeWorkflowTests(unittest.TestCase):
    def test_both_scenarios_on_both_apis_gate_ready_and_have_separate_artifacts(self):
        text = (Path(__file__).resolve().parents[1] / ".github/workflows/prepare-release.yml").read_text()
        job, ready = text.split("\n  smoke:\n", 1)[1].split("\n  ready:\n", 1)
        self.assertIn("api: [34, 35]", job)
        self.assertIn("scenario: [fresh, upgrade]", job)
        self.assertIn("script: python3 tools/smoke_apk.py --scenario ${{ matrix.scenario }}", job)
        self.assertIn("name: smoke-${{ matrix.scenario }}-api-${{ matrix.api }}-${{ github.run_id }}", job)
        self.assertIn("needs: [build, sign, smoke]", ready)
        self.assertNotIn("continue-on-error", job)


if __name__ == "__main__":
    unittest.main()
