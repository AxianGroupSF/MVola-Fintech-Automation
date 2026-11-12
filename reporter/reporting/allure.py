"""Generates the Allure dashboard (optional report) from <run_dir>/allure-results."""
from __future__ import annotations

import os
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ALLURE_VERSION = "2.32.0"
ALLURE_URL = (f"https://repo.maven.apache.org/maven2/io/qameta/allure/allure-commandline/"
              f"{ALLURE_VERSION}/allure-commandline-{ALLURE_VERSION}.zip")


def find_allure(tools_dir: Path, say=print) -> str | None:
    found = shutil.which("allure")
    if found:
        return found
    exe = "allure.bat" if os.name == "nt" else "allure"
    local = tools_dir / f"allure-{ALLURE_VERSION}" / "bin" / exe
    if local.exists():
        return str(local)
    if not shutil.which("java"):
        say("Java is not installed, so the Allure dashboard cannot be generated. Install Java 11+ (https://adoptium.net).")
        return None
    try:
        say(f"Downloading Allure {ALLURE_VERSION} (one-time)...")
        tools_dir.mkdir(parents=True, exist_ok=True)
        archive = tools_dir / "allure.zip"
        urllib.request.urlretrieve(ALLURE_URL, archive)
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(tools_dir)
        archive.unlink()
        if os.name != "nt":
            local.chmod(0o755)
        return str(local)
    except Exception as exc:  # noqa: BLE001
        say(f"Could not download Allure ({exc}). Allure dashboard skipped.")
        return None


def generate(run_dir: Path, tools_dir: Path, say=print) -> bool:
    results = run_dir / "allure-results"
    if not results.is_dir() or not any(results.iterdir()):
        say("No allure-results in this run folder, so the Allure dashboard cannot be generated.")
        return False
    allure = find_allure(tools_dir, say)
    if not allure:
        return False
    say("Generating Allure dashboard...")
    subprocess.run([allure, "generate", str(results), "-o", str(run_dir / "allure-report"), "--clean", "--single-file"],
                   check=False, stdout=subprocess.DEVNULL)
    return (run_dir / "allure-report" / "index.html").exists()
