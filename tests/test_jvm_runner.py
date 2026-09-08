"""Regression tests for test discovery; run the real runner in isolated source copies."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class JvmRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kanji-runner-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(ROOT / "app/src/main/java", self.root / "app/src/main/java")
        for source in (ROOT / "tests").rglob("*.java"):
            target = self.root / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        (self.root / "tools").mkdir()
        shutil.copyfile(ROOT / "tools/test.py", self.root / "tools/test.py")

    def add_test(self, path, body, package=""):
        target = self.root / "tests" / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            ("package " + package + ";\n" if package else "")
            + "public final class " + target.stem
            + " { public static void main(String[] args) { " + body + " } }\n",
            encoding="utf-8")

    def execute(self):
        return subprocess.run([sys.executable, str(self.root / "tools/test.py")],
                              cwd=self.root, capture_output=True, text=True, timeout=90)

    def test_new_plain_failure_fails_runner(self):
        self.add_test("ReviewCanaryTest.java", 'throw new AssertionError("PLAIN_CANARY_FAILED");')
        result = self.execute()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("PLAIN_CANARY_FAILED", result.stderr)

    def test_new_packaged_controller_failure_fails_runner(self):
        self.add_test("controller/extra/ReviewCanaryTest.java",
                      'throw new AssertionError("CONTROLLER_CANARY_FAILED");', "extra")
        result = self.execute()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("CONTROLLER_CANARY_FAILED", result.stderr)

    def test_java_assertions_are_enabled(self):
        self.add_test("AssertionCanaryTest.java", 'assert false : "ASSERTION_CANARY_FAILED";')
        result = self.execute()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("ASSERTION_CANARY_FAILED", result.stderr)

    def test_new_tests_run_once_in_both_groups(self):
        self.add_test("nested/ExtraTest.java", 'System.out.println("EXTRA_PLAIN_OK");', "nested")
        self.add_test("controller/extra/ExtraTest.java", 'System.out.println("EXTRA_CONTROLLER_OK");', "extra")
        result = self.execute()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.count("EXTRA_PLAIN_OK"), 1)
        self.assertEqual(result.stdout.count("EXTRA_CONTROLLER_OK"), 1)

    def test_empty_group_is_an_error(self):
        spec = importlib.util.spec_from_file_location("jvm_runner", ROOT / "tools/test.py")
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        with self.assertRaisesRegex(ValueError, "No .*Test.java"):
            runner.test_classes([])


if __name__ == "__main__":
    unittest.main()
