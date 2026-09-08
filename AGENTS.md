# Repository workflow

- `trunk` is the integration branch. Never commit or push directly to it, including
  documentation, CI changes, version bumps, hotfixes and bot-generated changes.
- Start a short-lived branch from current `trunk`, commit there, and open a PR to
  `trunk`. Do not bypass checks, reviews or unresolved review threads. Merge only
  through the PR after `Checks` succeeds; prefer squash merge and remove the
  merged feature branch when it is no longer needed.
- Run `python3 tools/check.py`; the PR CI also builds and checks an unsigned APK.
  Do not describe mocked/JVM tests as Android device tests.
- Releases use fixed commits from `trunk`, not a long-lived release branch.
  `Prepare release` creates `rc/<run_id>` before building. `Review release`
  publishes the accepted APK bytes under `v<versionName>` without rebuilding.
- Never move, delete or reuse candidate/release tags. Fix through another PR and
  prepare a new candidate. Do not auto-approve a release or invent a phone report.
- Never commit signing keys, credentials or APKs. Do not change repository
  visibility, billing, or permissions as a workaround for unavailable protection.
- JSON files in `.github/rulesets/` are desired server configuration, not active
  protection by themselves. Report the distinction accurately. See
  [docs/BRANCHING.md](docs/BRANCHING.md) and open issue #3.
