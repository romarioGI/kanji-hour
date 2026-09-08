#!/usr/bin/env python3
"""Cut a candidate tag on the dispatch SHA; never update or delete a Git ref."""
from collections.abc import Callable, Mapping
import os
import re
import subprocess
import sys

Api = Callable[..., dict | None]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def ensure_candidate_tag(api: Api, repo: str, run_id: str, sha: str) -> str:
    """Create rc/<run ID> once, or verify an identical existing lightweight tag."""
    require(re.fullmatch(r"[1-9][0-9]{0,19}", run_id) is not None, "Invalid run ID")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "Invalid commit SHA")
    tag = f"rc/{run_id}"
    endpoint = f"git/ref/tags/{tag}"
    ref = api(repo, endpoint, missing_ok=True)
    if ref is None:
        api(repo, "git/refs", {"ref": f"refs/tags/{tag}", "sha": sha})
        # Read back the stored ref, not just a successful POST response.
        ref = api(repo, endpoint)
    require(isinstance(ref, dict) and ref.get("ref") == f"refs/tags/{tag}"
            and ref.get("object", {}).get("type") == "commit"
            and ref.get("object", {}).get("sha") == sha,
            "Candidate tag differs from the dispatch SHA; refusing to move/reuse it")
    return tag


def cut(api: Api, env: Mapping[str, str]) -> str:
    """Only a new manual trunk run may cut a snapshot, before build/signing."""
    require(env.get("GITHUB_EVENT_NAME") == "workflow_dispatch", "Manual dispatch required")
    require(env.get("GITHUB_REF") == "refs/heads/trunk", "Run from trunk only")
    require(env.get("GITHUB_RUN_ATTEMPT") == "1", "Start a new candidate; do not re-run one")
    repo, sha, run_id = env["GITHUB_REPOSITORY"], env["GITHUB_SHA"], env["GITHUB_RUN_ID"]
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) is not None,
            "Invalid repository")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "Invalid commit SHA")
    require(re.fullmatch(r"[1-9][0-9]{0,19}", run_id) is not None, "Invalid run ID")
    # trunk may advance after dispatch; never replace the pinned SHA with its tip.
    compared = api(repo, f"compare/{sha}...trunk")
    require(isinstance(compared, dict) and compared.get("status") in ("ahead", "identical"),
            "Dispatch commit is no longer an ancestor of trunk")
    return ensure_candidate_tag(api, repo, run_id, sha)


def main() -> int:
    # Share the release pipeline's authenticated transport without adding a dependency
    # to the pure ref logic or requiring signing credentials in this job.
    from release_ci import api, summary
    try:
        tag = cut(api, os.environ)
        summary(f"## Отвод кандидата: `{tag}`\n\n"
                f"Коммит: `{os.environ['GITHUB_SHA']}`. "
                "Тег фиксирует исходники, но не означает успешную сборку или приёмку. "
                "Релизный тег `v<versionName>` появится только после Review release.")
    except (ValueError, RuntimeError, OSError, KeyError, subprocess.SubprocessError) as error:
        print(f"Candidate cut stopped: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
