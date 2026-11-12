"""Copies the reporting engine (reporter/reporting) into a suite so it stays self-contained
(double-click and cloud runs need no access to this repo).

Usage:
    python reporter/sync.py --into <suite folder> [--into <another suite>]
    python reporter/sync.py --check --into <suite folder>     # report drift only, change nothing
"""
from __future__ import annotations

import argparse
import filecmp
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ENGINE = HERE / "reporting"
DOCS = HERE / "docs" / "REPORTING.md"


def _diff(a: Path, b: Path) -> list[str]:
    if not b.exists():
        return ["(missing)"]
    cmp = filecmp.dircmp(a, b, ignore=["__pycache__"])
    out = [f"changed: {f}" for f in cmp.diff_files] + [f"missing: {f}" for f in cmp.left_only] + \
          [f"extra: {f}" for f in cmp.right_only]
    for sub in cmp.common_dirs:
        out += [f"{sub}/{x}" for x in _diff(a / sub, b / sub)]
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--into", type=Path, action="append", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    drift = False
    for suite in args.into:
        target = suite.resolve() / "reporting"
        changes = _diff(ENGINE, target)
        if args.check:
            drift |= bool(changes)
            print(f"{suite}: {'in sync' if not changes else '; '.join(changes)}")
            continue
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(ENGINE, target, ignore=shutil.ignore_patterns("__pycache__"))
        if (suite / "docs").is_dir():
            shutil.copy2(DOCS, suite / "docs" / "REPORTING.md")
        print(f"{suite}: engine {(ENGINE / 'VERSION').read_text().strip()} synced")
    sys.exit(1 if drift else 0)


if __name__ == "__main__":
    main()
