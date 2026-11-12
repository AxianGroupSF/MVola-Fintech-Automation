"""Dependency-free inline SVG charts for the HTML reports.
Charts are drawn at fixed pixel sizes so text never scales with the amount of data; containers scroll instead."""
from __future__ import annotations

import math
from html import escape

COLORS = {"passed": "#1e9e5a", "failed": "#d64545", "broken": "#e08a1e", "skipped": "#9aa3ad",
          "Critical": "#b3261e", "High": "#e0662a", "Medium": "#e0a91e", "Low": "#6b8fb3"}


def donut(slices: list[tuple[str, int, str]], center_text: str, center_sub: str = "", size: int = 190) -> str:
    total = sum(v for _, v, _ in slices) or 1
    r, stroke = size / 2 - 16, 26
    c = 2 * math.pi * r
    offset = 0.0
    parts = [f'<svg viewBox="0 0 {size} {size}" width="{size}" height="{size}" role="img">',
             f'<circle cx="{size/2}" cy="{size/2}" r="{r}" fill="none" stroke="#eef1f4" stroke-width="{stroke}"/>']
    for label, value, color in slices:
        if not value:
            continue
        length = c * value / total
        parts.append(
            f'<circle cx="{size/2}" cy="{size/2}" r="{r}" fill="none" stroke="{color}" stroke-width="{stroke}" '
            f'stroke-dasharray="{length:.2f} {c - length:.2f}" stroke-dashoffset="{-offset:.2f}" '
            f'transform="rotate(-90 {size/2} {size/2})"><title>{escape(label)}: {value}</title></circle>')
        offset += length
    parts.append(f'<text x="50%" y="48%" text-anchor="middle" font-size="26" font-weight="700" fill="#1d2733">'
                 f'{escape(center_text)}</text>')
    if center_sub:
        parts.append(f'<text x="50%" y="61%" text-anchor="middle" font-size="12" fill="#5b6773">{escape(center_sub)}</text>')
    parts.append("</svg>")
    return "".join(parts)


def stacked_bars(rows: list[dict], width: int = 470) -> str:
    """rows: [{label, passed, failed, broken, skipped}] -> horizontal stacked bars, 30 px per row."""
    bar_h, gap, label_w = 18, 12, 190
    height = max(1, len(rows)) * (bar_h + gap) + 6
    usable = width - label_w - 36
    parts = [f'<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img">']
    for i, row in enumerate(rows):
        y = 4 + i * (bar_h + gap)
        total = sum(row.get(k, 0) for k in ("passed", "failed", "broken", "skipped")) or 1
        parts.append(f'<text x="0" y="{y + 13}" font-size="12" fill="#1d2733">{escape(row["label"][:30])}'
                     f'<title>{escape(row["label"])}</title></text>')
        x = label_w
        for key in ("passed", "failed", "broken", "skipped"):
            v = row.get(key, 0)
            if not v:
                continue
            w = usable * v / total
            parts.append(f'<rect x="{x:.1f}" y="{y}" width="{w:.1f}" height="{bar_h}" fill="{COLORS[key]}" rx="3">'
                         f'<title>{key}: {v}</title></rect>')
            x += w
        parts.append(f'<text x="{label_w + usable + 6}" y="{y + 13}" font-size="12" fill="#5b6773">{total}</text>')
    parts.append("</svg>")
    return "".join(parts)


def run_columns(runs: list[dict], height: int = 200) -> str:
    """One 100%-stacked column per run (oldest -> newest). Fixed 64 px per run; the container scrolls."""
    slot, bw, top, bottom = 64, 30, 10, 44
    width = max(1, len(runs)) * slot + 10
    usable = height - top - bottom
    parts = [f'<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img">']
    for i, r in enumerate(runs):
        x = 10 + i * slot + (slot - bw) / 2
        y = top
        total = r["total"] or 1
        for key in ("passed", "failed", "broken", "skipped"):
            v = r.get(key, 0)
            if not v:
                continue
            h = usable * v / total
            parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw}" height="{h:.1f}" fill="{COLORS[key]}">'
                         f'<title>{escape(r["started_at"])} - {key}: {v}</title></rect>')
            y += h
        date, _, time = r["started_at"].partition(", ")
        cx = x + bw / 2
        parts.append(f'<text x="{cx:.1f}" y="{height - 26}" text-anchor="middle" font-size="12" fill="#1d2733">'
                     f'{escape(date[:6])}</text>')
        parts.append(f'<text x="{cx:.1f}" y="{height - 11}" text-anchor="middle" font-size="12" fill="#5b6773">'
                     f'{escape(time)}</text>')
    parts.append("</svg>")
    return "".join(parts)


def column_bars(items: list[tuple[str, int, str]], width: int = 360, height: int = 190) -> str:
    peak = max([v for _, v, _ in items] + [1])
    n = max(1, len(items))
    slot = width / n
    bw = min(56, slot * 0.6)
    parts = [f'<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img">']
    for i, (label, value, color) in enumerate(items):
        h = (height - 45) * value / peak
        x = i * slot + (slot - bw) / 2
        y = height - 25 - h
        parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw:.1f}" height="{h:.1f}" fill="{color}" rx="4"/>')
        parts.append(f'<text x="{x + bw/2:.1f}" y="{y - 5:.1f}" text-anchor="middle" font-size="13" '
                     f'font-weight="700" fill="#1d2733">{value}</text>')
        parts.append(f'<text x="{x + bw/2:.1f}" y="{height - 8}" text-anchor="middle" font-size="12" '
                     f'fill="#5b6773">{escape(label)}</text>')
    parts.append("</svg>")
    return "".join(parts)
