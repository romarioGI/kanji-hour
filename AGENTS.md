# Repository workflow

- Never commit or push directly to `trunk`, including docs, CI, version bumps and
  hotfixes. Use a short-lived branch and a PR to `trunk` for every change.
- Run `python3 tools/check.py`; merge through the PR only after `Checks` succeeds
  and review comments are addressed. Prefer squash merge.
- Keep the existing `Prepare release` / `Review release` flow: fixed candidate SHA,
  then `v<versionName>` on the tested commit. Never move/reuse release tags or
  approve a release without the user's real phone-test result.
- These instructions do not enable server-side protection. See CONTRIBUTING.md.
