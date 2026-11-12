"""Collect and copy test attachments (screenshots, traces) into the run folder."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"}
KEEP_EXTS = IMAGE_EXTS | {".webm", ".mp4", ".zip", ".txt", ".log", ".html"}
_PATH_LINE = re.compile(
    r"^(?P<p>(?:[A-Za-z]:)?(?:/|\\)[^\n]+?\.(?:png|jpe?g|webp|gif|bmp|webm|mp4|zip|txt|log|html))\s*$",
    re.IGNORECASE,
)


def feature_slug(name: str) -> str:
    raw = (name or "Unassigned").strip() or "Unassigned"
    out = re.sub(r"[^a-zA-Z0-9]+", "-", raw).strip("-").lower()
    return (out or "unassigned")[:80]


def case_slug(case_id: str) -> str:
    raw = (case_id or "case").strip() or "case"
    out = re.sub(r"[^a-zA-Z0-9._-]+", "_", raw).strip("._")
    return (out or "case")[:80]


def _kind(path: Path) -> str:
    return "screenshot" if path.suffix.lower() in IMAGE_EXTS else "file"


def _from_structured(raw) -> list[tuple[Path, str | None]]:
    """Return (path, preferred_name) pairs from the structured attachments field."""
    items: list[tuple[Path, str | None]] = []
    if not raw:
        return items
    if isinstance(raw, str):
        raw = [raw]
    for item in raw:
        if isinstance(item, dict):
            p = item.get("path") or item.get("file") or ""
            name = item.get("name") or None
        else:
            p, name = item, None
        if p:
            items.append((Path(str(p)), str(name) if name else None))
    return items


def _from_log(log: str) -> list[tuple[Path, str | None]]:
    """Parse absolute paths listed under an `attachments:` block in the test log."""
    items: list[tuple[Path, str | None]] = []
    in_block = False
    for line in (log or "").splitlines():
        stripped = line.strip()
        if stripped.lower().startswith("attachments:"):
            in_block = True
            rest = stripped.split(":", 1)[1].strip()
            if rest:
                items.append((Path(rest), None))
            continue
        if not in_block:
            continue
        match = _PATH_LINE.match(stripped)
        if match:
            items.append((Path(match.group("p")), None))
            continue
        if not stripped:
            continue
        in_block = False
    return items


def collect_sources(test: dict) -> list[tuple[Path, str | None]]:
    """Absolute paths (+ optional display name) from `attachments` or from the log fallback."""
    seen: set[str] = set()
    out: list[tuple[Path, str | None]] = []
    for src, name in _from_structured(test.get("attachments")) + _from_log(test.get("log") or ""):
        key = str(src)
        if key in seen:
            continue
        seen.add(key)
        out.append((src, name))
    return out


def materialize(tests: list[dict], out_dir: Path) -> None:
    """Copy attachment files into out_dir/attachments/<case>/ and rewrite each test's attachments.

    Paths stored on the test are relative to the run folder (for index/Dev/QA links).
    Feature detail pages live one level deeper and use ../ + path.
    Absolute sources are copied in; paths already under the run folder are kept in place.
    """
    root = out_dir.resolve()
    att_root = root / "attachments"
    staged: dict[str, list[dict]] = {}
    for test in tests:
        sources = collect_sources(test)
        copied: list[dict] = []
        dest_dir: Path | None = None
        used_names: set[str] = set()
        for i, (src, preferred) in enumerate(sources):
            resolved = (src if src.is_absolute() else root / src).resolve()
            if not resolved.is_file():
                continue
            if resolved.suffix.lower() not in KEEP_EXTS:
                continue
            try:
                rel_existing = resolved.relative_to(root)
            except ValueError:
                rel_existing = None
            if rel_existing is not None and rel_existing.parts and rel_existing.parts[0] == "attachments":
                name = preferred or resolved.name
                copied.append({"name": name, "path": rel_existing.as_posix(), "kind": _kind(resolved)})
                continue
            if dest_dir is None:
                dest_dir = att_root / case_slug(str(test.get("case_id") or "case"))
                dest_dir.mkdir(parents=True, exist_ok=True)
            name = preferred or resolved.name
            if Path(name).suffix.lower() not in KEEP_EXTS:
                name = f"{Path(name).stem}{resolved.suffix}"
            if name in used_names:
                stem, ext = Path(name).stem, Path(name).suffix
                name = f"{stem}-{i}{ext}"
            used_names.add(name)
            dest = dest_dir / name
            if resolved != dest.resolve():
                shutil.copy2(resolved, dest)
            copied.append({"name": name, "path": dest.relative_to(root).as_posix(), "kind": _kind(dest)})
        staged[str(test.get("case_id"))] = copied
        test["attachments"] = copied
        test["screenshots"] = [a for a in copied if a["kind"] == "screenshot"]

    if att_root.exists():
        keep = {
            Path(a["path"]).parts[1]
            for rows in staged.values()
            for a in rows
            if len(Path(a["path"]).parts) > 1
        }
        for child in list(att_root.iterdir()):
            if child.is_dir() and child.name not in keep:
                shutil.rmtree(child, ignore_errors=True)
        if not any(att_root.iterdir()):
            att_root.rmdir()


def enrich_features(features: list[dict], tests: list[dict], *, link: bool = True) -> None:
    """Attach slug, optional href, and the full test list to each feature row."""
    by_name: dict[str, list[dict]] = {}
    for t in tests:
        name = (t.get("feature") or "").strip() or "Unassigned"
        by_name.setdefault(name, []).append(t)
    for row in features:
        name = row["feature"]
        slug = feature_slug(name)
        row["slug"] = slug
        row["tests"] = by_name.get(name, [])
        row["href"] = f"features/{slug}.html" if link else ""
        row["screenshot_count"] = sum(len(t.get("screenshots") or []) for t in row["tests"])
