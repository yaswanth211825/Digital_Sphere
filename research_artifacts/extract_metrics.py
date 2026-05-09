#!/usr/bin/env python3
"""
Reconstruct publication-ready metrics and figures from Digital Sphere JSON reports.

The script is intentionally stdlib-only so it can run in a clean desktop
environment without extra plotting dependencies.
"""

from __future__ import annotations

import csv
import itertools
import json
import math
import re
import statistics
import sys
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Iterable


DEFAULT_LOGS = [
    Path("/Users/yash_2111825/Downloads/report_20260407_112902_student_1234.json"),
    Path("/Users/yash_2111825/Downloads/report_20260407_114459_student_cs101.json (1)"),
]

OUTPUT_DIR = Path(__file__).resolve().parent
FIG_DIR = OUTPUT_DIR / "figures"

NUMBER_RE = re.compile(r"(-?\d+(?:\.\d+)?)")


@dataclass
class DeviceInfo:
    manufacturer: str
    model: str
    android: str
    sdk: int
    ble_advertise_support: str = ""
    ble_scan_support: str = ""
    mic_permission: str = ""
    unprocessed_audio_support: str = ""
    barometer_present: str = ""
    audiorecord_state: str = ""


@dataclass
class Attempt:
    global_attempt_index: int
    log_file: str
    device_model: str
    session_name: str
    scan_session_index: int
    attempt_index: int
    expected_token: int
    ms_since_inrange: float | None = None
    baro_ms: float | None = None
    ultra_ms: float | None = None
    ultra_token: float | None = None
    ultra_conf: float | None = None
    audio_ms: float | None = None
    audio_cosine: float | None = None
    dsvf_ms: float | None = None
    fusion_score: float | None = None
    status: str = ""
    total_verify_ms: float | None = None
    attendance_marked_ms: float | None = None
    attendance_status: str = ""
    marked: bool = False
    ultrasound_mode: str = ""
    ultra_detected: bool = False
    ultra_match: bool = False


@dataclass
class ScanSession:
    log_file: str
    device_model: str
    session_name: str
    scan_session_index: int
    expected_token: int
    first_beacon_ms: float | None = None
    in_range_ms: float | None = None
    trigger_rssi: float | None = None
    marked: bool = False
    marked_attempt_index: int | None = None
    marked_latency_ms: float | None = None
    attempts: list[Attempt] = field(default_factory=list)


def parse_number(text: str | None) -> float | None:
    match = NUMBER_RE.search(text or "")
    return float(match.group(1)) if match else None


def java_hash(value: str) -> int:
    h = 0
    for ch in value:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    if h & 0x80000000:
        h -= 0x100000000
    return h


def session_token(session_name: str) -> int:
    return (java_hash(session_name) & 0x7FFFFFFF) % 16


def mean(values: Iterable[float]) -> float:
    values = list(values)
    return statistics.mean(values) if values else 0.0


def median(values: Iterable[float]) -> float:
    values = list(values)
    return statistics.median(values) if values else 0.0


def pct(part: float, whole: float) -> float:
    return 0.0 if whole == 0 else 100.0 * part / whole


def exact_mann_whitney_p(group_a: list[float], group_b: list[float]) -> tuple[float, float]:
    """
    Exact two-sided Mann-Whitney p-value by enumerating all rank assignments.

    The datasets in this task are tiny, so brute force is simple and exact.
    """
    combined = [(value, 0) for value in group_a] + [(value, 1) for value in group_b]
    combined.sort(key=lambda item: item[0])
    n_a = len(group_a)
    n_b = len(group_b)
    observed_ranks = [idx + 1 for idx, (_, group) in enumerate(combined) if group == 0]
    observed_rank_sum = sum(observed_ranks)
    observed_u = observed_rank_sum - n_a * (n_a + 1) / 2

    all_us = []
    total_ranks = n_a + n_b
    for combo in itertools.combinations(range(1, total_ranks + 1), n_a):
        rank_sum = sum(combo)
        u_value = rank_sum - n_a * (n_a + 1) / 2
        all_us.append(u_value)

    lower_tail = sum(1 for u_value in all_us if u_value <= observed_u) / len(all_us)
    upper_tail = sum(1 for u_value in all_us if u_value >= (n_a * n_b - observed_u)) / len(all_us)
    return observed_u, min(1.0, 2.0 * min(lower_tail, upper_tail))


def cliffs_delta(group_a: list[float], group_b: list[float]) -> float:
    if not group_a or not group_b:
        return 0.0
    comparisons = 0
    score = 0
    for a_value in group_a:
        for b_value in group_b:
            comparisons += 1
            if a_value > b_value:
                score += 1
            elif a_value < b_value:
                score -= 1
    return score / comparisons


def load_reports(paths: list[Path]) -> tuple[list[DeviceInfo], list[ScanSession], list[Attempt]]:
    devices: list[DeviceInfo] = []
    sessions: list[ScanSession] = []
    attempts: list[Attempt] = []
    global_attempt_index = 0

    for path in paths:
        report = json.loads(path.read_text())
        device = DeviceInfo(
            manufacturer=report["dev"]["mfr"],
            model=report["dev"]["model"],
            android=report["dev"]["android"],
            sdk=int(report["dev"]["sdk"]),
        )

        current_session: ScanSession | None = None
        session_index = 0

        for timestamp, event, detail, value, result in report["ev"]:
            if event == "BLE_ADVERTISE_SUPPORT":
                device.ble_advertise_support = result
            elif event == "BLE_SCAN_SUPPORT":
                device.ble_scan_support = result
            elif event == "MIC_PERMISSION":
                device.mic_permission = result
            elif event == "UNPROCESSED_AUDIO_SUPPORT":
                device.unprocessed_audio_support = result
            elif event == "BAROMETER_PRESENT":
                device.barometer_present = result
            elif event == "AUDIORECORD_STATE":
                device.audiorecord_state = result

            if event == "SCAN_STARTED":
                if current_session is not None:
                    sessions.append(current_session)
                session_index += 1
                current_session = ScanSession(
                    log_file=path.name,
                    device_model=device.model,
                    session_name=report["session"],
                    scan_session_index=session_index,
                    expected_token=session_token(report["session"]),
                )
                continue

            if current_session is None:
                continue

            if event == "FIRST_BEACON_SEEN":
                current_session.first_beacon_ms = parse_number(detail)
                continue

            if event == "IN_RANGE_TRIGGERED":
                current_session.trigger_rssi = parse_number(detail)
                current_session.in_range_ms = parse_number(value)
                continue

            if event == "VERIFY_FLOW_START":
                global_attempt_index += 1
                attempt = Attempt(
                    global_attempt_index=global_attempt_index,
                    log_file=path.name,
                    device_model=device.model,
                    session_name=report["session"],
                    scan_session_index=session_index,
                    attempt_index=len(current_session.attempts) + 1,
                    expected_token=session_token(report["session"]),
                    ms_since_inrange=parse_number(detail),
                )
                current_session.attempts.append(attempt)
                attempts.append(attempt)
                continue

            if not current_session.attempts:
                continue

            attempt = current_session.attempts[-1]

            if event == "BARO_STEP_DONE":
                attempt.baro_ms = parse_number(detail)
            elif event == "ULTRA_STEP_DONE":
                attempt.ultra_ms = parse_number(detail)
                attempt.ultra_token = parse_number(value)
                attempt.ultra_conf = parse_number(result)
            elif event == "AUDIO_STEP_DONE":
                attempt.audio_ms = parse_number(detail)
                attempt.audio_cosine = parse_number(value)
            elif event == "DSVF_DONE":
                attempt.dsvf_ms = parse_number(detail)
                attempt.fusion_score = parse_number(value)
                attempt.status = result
            elif event == "TOTAL_VERIFY_TIME":
                attempt.total_verify_ms = parse_number(detail)
            elif event == "ATTENDANCE_MARKED":
                attempt.attendance_marked_ms = parse_number(detail)
                attempt.attendance_status = result
                attempt.marked = True
                current_session.marked = True
                current_session.marked_attempt_index = attempt.attempt_index
                current_session.marked_latency_ms = attempt.attendance_marked_ms

        if current_session is not None:
            sessions.append(current_session)
        devices.append(device)

    for attempt in attempts:
        attempt.ultra_detected = (
            attempt.ultra_token is not None
            and attempt.ultra_token >= 0
            and (attempt.ultra_conf or 0.0) > 0.0
        )
        attempt.ultra_match = (
            attempt.ultra_detected
            and int(attempt.ultra_token) == attempt.expected_token
            and (attempt.ultra_conf or 0.0) >= 0.30
        )
        if attempt.ultra_match:
            attempt.ultrasound_mode = "matched_token"
        elif attempt.ultra_detected:
            attempt.ultrasound_mode = "mismatched_token"
        else:
            attempt.ultrasound_mode = "timeout_or_absent"

    return devices, sessions, attempts


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def ensure_dirs() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)


def color_for_status(status: str) -> str:
    palette = {
        "PRESENT": "#1b9e77",
        "FLAGGED": "#d95f02",
        "REJECTED_SCORE": "#7570b3",
        "REJECTED_ROOM": "#e7298a",
        "REJECTED_FLOOR": "#66a61e",
    }
    return palette.get(status, "#555555")


def svg_bar_chart(
    labels: list[str],
    values: list[float],
    path: Path,
    title: str,
    y_label: str,
    width: int = 920,
    height: int = 540,
    colors: list[str] | None = None,
    y_max: float | None = None,
    suffix: str = "",
) -> None:
    colors = colors or ["#4c78a8"] * len(values)
    y_max = y_max if y_max is not None else max(values) * 1.15 if values else 1.0

    margin_left = 90
    margin_right = 30
    margin_top = 70
    margin_bottom = 110
    plot_width = width - margin_left - margin_right
    plot_height = height - margin_top - margin_bottom
    bar_width = plot_width / max(1, len(values)) * 0.55

    def x_pos(index: int) -> float:
        return margin_left + (index + 0.5) * (plot_width / max(1, len(values))) - bar_width / 2

    def y_pos(value: float) -> float:
        return margin_top + plot_height - (value / y_max) * plot_height

    elements = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<style>text{font-family:Helvetica,Arial,sans-serif;fill:#222} .small{font-size:12px} .label{font-size:14px} .title{font-size:22px;font-weight:700} .axis{stroke:#444;stroke-width:1.2} .grid{stroke:#d0d0d0;stroke-width:1} </style>',
        f'<text x="{width/2}" y="34" text-anchor="middle" class="title">{title}</text>',
    ]

    for tick in range(6):
        value = y_max * tick / 5
        y = margin_top + plot_height - plot_height * tick / 5
        elements.append(f'<line x1="{margin_left}" y1="{y:.2f}" x2="{width-margin_right}" y2="{y:.2f}" class="grid"/>')
        elements.append(f'<text x="{margin_left-12}" y="{y+4:.2f}" text-anchor="end" class="small">{value:.0f}{suffix}</text>')

    elements.append(f'<line x1="{margin_left}" y1="{margin_top}" x2="{margin_left}" y2="{margin_top+plot_height}" class="axis"/>')
    elements.append(f'<line x1="{margin_left}" y1="{margin_top+plot_height}" x2="{width-margin_right}" y2="{margin_top+plot_height}" class="axis"/>')
    elements.append(f'<text x="20" y="{margin_top + plot_height/2}" transform="rotate(-90 20 {margin_top + plot_height/2})" text-anchor="middle" class="label">{y_label}</text>')

    for index, (label, value, color) in enumerate(zip(labels, values, colors)):
        x = x_pos(index)
        y = y_pos(value)
        h = margin_top + plot_height - y
        elements.append(f'<rect x="{x:.2f}" y="{y:.2f}" width="{bar_width:.2f}" height="{h:.2f}" fill="{color}" rx="4"/>')
        elements.append(f'<text x="{x + bar_width/2:.2f}" y="{y - 8:.2f}" text-anchor="middle" class="label">{value:.1f}{suffix}</text>')
        elements.append(f'<text x="{x + bar_width/2:.2f}" y="{height - 68}" text-anchor="middle" class="label">{label}</text>')

    elements.append("</svg>")
    path.write_text("\n".join(elements))


def svg_attempt_timeline(attempts: list[Attempt], path: Path) -> None:
    width, height = 980, 560
    margin_left, margin_right, margin_top, margin_bottom = 90, 30, 70, 110
    plot_width = width - margin_left - margin_right
    plot_height = height - margin_top - margin_bottom
    max_y = max((attempt.total_verify_ms or 0) for attempt in attempts) * 1.12

    def x_pos(index: int) -> float:
        return margin_left + (index - 1) / max(1, len(attempts) - 1) * plot_width

    def y_pos(value: float) -> float:
        return margin_top + plot_height - (value / max_y) * plot_height

    elements = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<style>text{font-family:Helvetica,Arial,sans-serif;fill:#222} .small{font-size:12px} .label{font-size:14px} .title{font-size:22px;font-weight:700} .axis{stroke:#444;stroke-width:1.2} .grid{stroke:#d0d0d0;stroke-width:1} .line{fill:none;stroke:#4c78a8;stroke-width:2.2} </style>',
        '<text x="490" y="34" text-anchor="middle" class="title">Attempt-Level Verification Latency</text>',
    ]

    for tick in range(6):
        value = max_y * tick / 5
        y = margin_top + plot_height - plot_height * tick / 5
        elements.append(f'<line x1="{margin_left}" y1="{y:.2f}" x2="{width-margin_right}" y2="{y:.2f}" class="grid"/>')
        elements.append(f'<text x="{margin_left-12}" y="{y+4:.2f}" text-anchor="end" class="small">{value/1000:.1f}s</text>')

    elements.append(f'<line x1="{margin_left}" y1="{margin_top}" x2="{margin_left}" y2="{margin_top+plot_height}" class="axis"/>')
    elements.append(f'<line x1="{margin_left}" y1="{margin_top+plot_height}" x2="{width-margin_right}" y2="{margin_top+plot_height}" class="axis"/>')
    elements.append(f'<text x="20" y="{margin_top + plot_height/2}" transform="rotate(-90 20 {margin_top + plot_height/2})" text-anchor="middle" class="label">Total Verify Time</text>')
    elements.append(f'<text x="{margin_left + plot_width/2}" y="{height - 40}" text-anchor="middle" class="label">Chronological attempt index</text>')

    points = []
    for attempt in attempts:
        x = x_pos(attempt.global_attempt_index)
        y = y_pos(attempt.total_verify_ms or 0.0)
        points.append(f"{x:.2f},{y:.2f}")
        elements.append(
            f'<circle cx="{x:.2f}" cy="{y:.2f}" r="6.5" fill="{color_for_status(attempt.status)}" stroke="white" stroke-width="1.2"/>'
        )
        elements.append(
            f'<text x="{x:.2f}" y="{margin_top + plot_height + 24}" text-anchor="middle" class="small">{attempt.global_attempt_index}</text>'
        )

    elements.append(f'<polyline points="{" ".join(points)}" class="line"/>')

    legend_items = ["PRESENT", "FLAGGED", "REJECTED_SCORE", "REJECTED_ROOM"]
    legend_x = width - 255
    legend_y = 78
    for idx, status in enumerate(legend_items):
        y = legend_y + idx * 24
        elements.append(f'<circle cx="{legend_x}" cy="{y}" r="6" fill="{color_for_status(status)}"/>')
        elements.append(f'<text x="{legend_x + 14}" y="{y + 4}" class="label">{status}</text>')

    elements.append("</svg>")
    path.write_text("\n".join(elements))


def svg_stacked_latency(groups: dict[str, dict[str, float]], path: Path) -> None:
    width, height = 980, 500
    margin_left, margin_right, margin_top, margin_bottom = 180, 30, 70, 80
    plot_width = width - margin_left - margin_right
    plot_height = height - margin_top - margin_bottom
    colors = {
        "baro_ms": "#9ecae1",
        "ultra_ms": "#3182bd",
        "audio_ms": "#fd8d3c",
        "dsvf_ms": "#31a354",
    }
    order = ["baro_ms", "ultra_ms", "audio_ms", "dsvf_ms"]
    labels = {
        "baro_ms": "Barometer",
        "ultra_ms": "Ultrasound",
        "audio_ms": "Audio",
        "dsvf_ms": "DSVF",
    }
    max_total = max(sum(group[k] for k in order) for group in groups.values()) * 1.08

    def x_scale(value: float) -> float:
        return margin_left + (value / max_total) * plot_width

    row_height = plot_height / max(1, len(groups))
    bar_height = row_height * 0.42

    elements = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<style>text{font-family:Helvetica,Arial,sans-serif;fill:#222} .small{font-size:12px} .label{font-size:14px} .title{font-size:22px;font-weight:700} .axis{stroke:#444;stroke-width:1.2} .grid{stroke:#d0d0d0;stroke-width:1} </style>',
        '<text x="490" y="34" text-anchor="middle" class="title">Mean Stage Latency by Ultrasound Outcome</text>',
    ]

    for tick in range(6):
        value = max_total * tick / 5
        x = margin_left + plot_width * tick / 5
        elements.append(f'<line x1="{x:.2f}" y1="{margin_top}" x2="{x:.2f}" y2="{height-margin_bottom}" class="grid"/>')
        elements.append(f'<text x="{x:.2f}" y="{height-margin_bottom+24}" text-anchor="middle" class="small">{value/1000:.1f}s</text>')

    elements.append(f'<line x1="{margin_left}" y1="{height-margin_bottom}" x2="{width-margin_right}" y2="{height-margin_bottom}" class="axis"/>')
    elements.append(f'<text x="{margin_left + plot_width/2}" y="{height - 20}" text-anchor="middle" class="label">Mean verification latency</text>')

    for idx, (group_name, stage_values) in enumerate(groups.items()):
        y_center = margin_top + row_height * idx + row_height / 2
        y = y_center - bar_height / 2
        elements.append(f'<text x="{margin_left-14}" y="{y_center+5:.2f}" text-anchor="end" class="label">{group_name}</text>')
        running = 0.0
        total = sum(stage_values[key] for key in order)
        for key in order:
            stage_value = stage_values[key]
            x0 = x_scale(running)
            x1 = x_scale(running + stage_value)
            elements.append(
                f'<rect x="{x0:.2f}" y="{y:.2f}" width="{max(1.0, x1-x0):.2f}" height="{bar_height:.2f}" fill="{colors[key]}" rx="4"/>'
            )
            running += stage_value
        elements.append(
            f'<text x="{x_scale(total)+10:.2f}" y="{y_center+5:.2f}" class="label">{total/1000:.2f}s</text>'
        )

    legend_x = width - 270
    legend_y = 80
    for idx, key in enumerate(order):
        y = legend_y + idx * 24
        elements.append(f'<rect x="{legend_x}" y="{y-10}" width="12" height="12" fill="{colors[key]}"/>')
        elements.append(f'<text x="{legend_x+18}" y="{y}" class="label">{labels[key]}</text>')

    elements.append("</svg>")
    path.write_text("\n".join(elements))


def generate_figures(summary: dict, attempts: list[Attempt]) -> None:
    svg_bar_chart(
        labels=["First attempt", "Strict ultrasound", "Observed"],
        values=[
            summary["rates"]["session_completion_first_attempt_pct"],
            summary["rates"]["session_completion_strict_ultrasound_pct"],
            summary["rates"]["session_completion_observed_pct"],
        ],
        colors=["#bdbdbd", "#6baed6", "#238b45"],
        path=FIG_DIR / "session_success_baselines.svg",
        title="Session Completion Across Baselines",
        y_label="Sessions completed (%)",
        y_max=100.0,
        suffix="%",
    )

    status_counts = summary["outcomes"]["attempt_status_counts"]
    ordered_statuses = ["REJECTED_SCORE", "REJECTED_ROOM", "FLAGGED", "PRESENT"]
    svg_bar_chart(
        labels=[status.replace("_", " ").title() for status in ordered_statuses],
        values=[float(status_counts.get(status, 0)) for status in ordered_statuses],
        colors=[color_for_status(status) for status in ordered_statuses],
        path=FIG_DIR / "attempt_status_distribution.svg",
        title="Attempt Outcome Distribution",
        y_label="Attempt count",
        y_max=max(status_counts.values()) + 1.0,
    )

    svg_attempt_timeline(attempts, FIG_DIR / "attempt_latency_timeline.svg")

    svg_stacked_latency(
        groups={
            "Timeout / absent token": summary["latency_ms"]["timeout_stage_means"],
            "Token detected": summary["latency_ms"]["detected_stage_means"],
        },
        path=FIG_DIR / "stage_latency_by_ultrasound.svg",
    )


def round_float(value: float, digits: int = 4) -> float:
    return round(float(value), digits)


def summarise(devices: list[DeviceInfo], sessions: list[ScanSession], attempts: list[Attempt]) -> dict:
    timeout_attempts = [attempt for attempt in attempts if attempt.ultrasound_mode == "timeout_or_absent"]
    detected_attempts = [attempt for attempt in attempts if attempt.ultra_detected]
    matched_attempts = [attempt for attempt in attempts if attempt.ultra_match]
    successful_attempts = [attempt for attempt in attempts if attempt.marked]
    fallback_successes = [
        session
        for session in sessions
        if session.marked
        and session.marked_attempt_index is not None
        and not session.attempts[session.marked_attempt_index - 1].ultra_match
    ]
    strict_ultrasound_successes = [
        session for session in sessions if any(attempt.marked and attempt.ultra_match for attempt in session.attempts)
    ]

    timeout_verify = [attempt.total_verify_ms for attempt in timeout_attempts if attempt.total_verify_ms is not None]
    detected_verify = [attempt.total_verify_ms for attempt in detected_attempts if attempt.total_verify_ms is not None]
    mann_whitney_u, mann_whitney_p = exact_mann_whitney_p(detected_verify, timeout_verify)

    summary = {
        "dataset": {
            "log_files": [str(path) for path in DEFAULT_LOGS],
            "device_count": len(devices),
            "devices": [asdict(device) for device in devices],
            "scan_session_count": len(sessions),
            "verification_attempt_count": len(attempts),
            "barometer_unavailable_devices": sum(device.barometer_present == "NO" for device in devices),
            "barometer_unavailable_device_pct": round_float(
                100.0 * sum(device.barometer_present == "NO" for device in devices) / max(1, len(devices)), 2
            ),
        },
        "rates": {
            "session_completion_observed_pct": round_float(pct(sum(session.marked for session in sessions), len(sessions)), 2),
            "session_completion_first_attempt_pct": round_float(
                pct(sum(bool(session.attempts and session.attempts[0].marked) for session in sessions), len(sessions)),
                2,
            ),
            "session_completion_strict_ultrasound_pct": round_float(
                pct(len(strict_ultrasound_successes), len(sessions)),
                2,
            ),
            "observed_vs_first_attempt_relative_gain_pct": round_float(
                (
                    (
                        pct(sum(session.marked for session in sessions), len(sessions))
                        - pct(sum(bool(session.attempts and session.attempts[0].marked) for session in sessions), len(sessions))
                    )
                    / max(1e-9, pct(sum(bool(session.attempts and session.attempts[0].marked) for session in sessions), len(sessions)))
                )
                * 100.0,
                2,
            ),
            "observed_vs_strict_ultrasound_relative_gain_pct": round_float(
                (
                    (
                        pct(sum(session.marked for session in sessions), len(sessions))
                        - pct(len(strict_ultrasound_successes), len(sessions))
                    )
                    / max(1e-9, pct(len(strict_ultrasound_successes), len(sessions)))
                )
                * 100.0,
                2,
            ),
            "attempt_acceptance_pct": round_float(pct(sum(attempt.marked for attempt in attempts), len(attempts)), 2),
            "ultrasound_timeout_attempt_pct": round_float(pct(len(timeout_attempts), len(attempts)), 2),
            "successful_sessions_requiring_fallback_pct": round_float(
                pct(len(fallback_successes), sum(session.marked for session in sessions)),
                2,
            ),
        },
        "latency_ms": {
            "first_beacon_mean": round_float(mean(session.first_beacon_ms for session in sessions if session.first_beacon_ms is not None), 2),
            "first_beacon_median": round_float(median(session.first_beacon_ms for session in sessions if session.first_beacon_ms is not None), 2),
            "in_range_mean": round_float(mean(session.in_range_ms for session in sessions if session.in_range_ms is not None), 2),
            "in_range_median": round_float(median(session.in_range_ms for session in sessions if session.in_range_ms is not None), 2),
            "total_verify_mean": round_float(mean(attempt.total_verify_ms for attempt in attempts if attempt.total_verify_ms is not None), 2),
            "total_verify_median": round_float(median(attempt.total_verify_ms for attempt in attempts if attempt.total_verify_ms is not None), 2),
            "baro_mean": round_float(mean(attempt.baro_ms for attempt in attempts if attempt.baro_ms is not None), 3),
            "ultra_mean": round_float(mean(attempt.ultra_ms for attempt in attempts if attempt.ultra_ms is not None), 3),
            "audio_mean": round_float(mean(attempt.audio_ms for attempt in attempts if attempt.audio_ms is not None), 3),
            "dsvf_mean": round_float(mean(attempt.dsvf_ms for attempt in attempts if attempt.dsvf_ms is not None), 3),
            "attendance_marked_mean": round_float(
                mean(session.marked_latency_ms for session in sessions if session.marked_latency_ms is not None),
                2,
            ),
            "attendance_marked_median": round_float(
                median(session.marked_latency_ms for session in sessions if session.marked_latency_ms is not None),
                2,
            ),
            "detected_total_mean": round_float(mean(detected_verify), 2),
            "detected_total_median": round_float(median(detected_verify), 2),
            "timeout_total_mean": round_float(mean(timeout_verify), 2),
            "timeout_total_median": round_float(median(timeout_verify), 2),
            "detected_vs_timeout_mean_reduction_pct": round_float(
                pct(mean(timeout_verify) - mean(detected_verify), mean(timeout_verify)),
                2,
            ),
            "stage_share_pct": {
                "barometer": round_float(pct(mean(attempt.baro_ms for attempt in attempts if attempt.baro_ms is not None), mean(attempt.total_verify_ms for attempt in attempts if attempt.total_verify_ms is not None)), 2),
                "ultrasound": round_float(pct(mean(attempt.ultra_ms for attempt in attempts if attempt.ultra_ms is not None), mean(attempt.total_verify_ms for attempt in attempts if attempt.total_verify_ms is not None)), 2),
                "audio": round_float(pct(mean(attempt.audio_ms for attempt in attempts if attempt.audio_ms is not None), mean(attempt.total_verify_ms for attempt in attempts if attempt.total_verify_ms is not None)), 2),
                "dsvf": round_float(pct(mean(attempt.dsvf_ms for attempt in attempts if attempt.dsvf_ms is not None), mean(attempt.total_verify_ms for attempt in attempts if attempt.total_verify_ms is not None)), 2),
            },
            "timeout_stage_means": {
                "baro_ms": round_float(mean(attempt.baro_ms for attempt in timeout_attempts if attempt.baro_ms is not None), 3),
                "ultra_ms": round_float(mean(attempt.ultra_ms for attempt in timeout_attempts if attempt.ultra_ms is not None), 3),
                "audio_ms": round_float(mean(attempt.audio_ms for attempt in timeout_attempts if attempt.audio_ms is not None), 3),
                "dsvf_ms": round_float(mean(attempt.dsvf_ms for attempt in timeout_attempts if attempt.dsvf_ms is not None), 3),
            },
            "detected_stage_means": {
                "baro_ms": round_float(mean(attempt.baro_ms for attempt in detected_attempts if attempt.baro_ms is not None), 3),
                "ultra_ms": round_float(mean(attempt.ultra_ms for attempt in detected_attempts if attempt.ultra_ms is not None), 3),
                "audio_ms": round_float(mean(attempt.audio_ms for attempt in detected_attempts if attempt.audio_ms is not None), 3),
                "dsvf_ms": round_float(mean(attempt.dsvf_ms for attempt in detected_attempts if attempt.dsvf_ms is not None), 3),
            },
        },
        "outcomes": {
            "attempt_status_counts": {
                status: sum(attempt.status == status for attempt in attempts)
                for status in sorted({attempt.status for attempt in attempts})
            },
            "accepted_attempt_status_counts": {
                status: sum(attempt.marked and attempt.status == status for attempt in attempts)
                for status in sorted({attempt.status for attempt in attempts if attempt.marked})
            },
            "matched_ultrasound_attempt_count": len(matched_attempts),
            "detected_ultrasound_attempt_count": len(detected_attempts),
            "timeout_ultrasound_attempt_count": len(timeout_attempts),
            "fallback_success_session_count": len(fallback_successes),
            "strict_ultrasound_success_session_count": len(strict_ultrasound_successes),
        },
        "statistics": {
            "ultrasound_latency_mann_whitney_u": round_float(mann_whitney_u, 4),
            "ultrasound_latency_mann_whitney_p_two_sided": round_float(mann_whitney_p, 6),
            "ultrasound_latency_cliffs_delta": round_float(cliffs_delta(detected_verify, timeout_verify), 4),
        },
    }

    return summary


def write_outputs(devices: list[DeviceInfo], sessions: list[ScanSession], attempts: list[Attempt], summary: dict) -> None:
    write_csv(OUTPUT_DIR / "device_capabilities.csv", [asdict(device) for device in devices])

    session_rows = []
    for session in sessions:
        session_rows.append(
            {
                "log_file": session.log_file,
                "device_model": session.device_model,
                "session_name": session.session_name,
                "scan_session_index": session.scan_session_index,
                "expected_token": session.expected_token,
                "attempt_count": len(session.attempts),
                "first_beacon_ms": session.first_beacon_ms,
                "in_range_ms": session.in_range_ms,
                "trigger_rssi": session.trigger_rssi,
                "marked": session.marked,
                "marked_attempt_index": session.marked_attempt_index,
                "marked_latency_ms": session.marked_latency_ms,
                "first_attempt_marked": bool(session.attempts and session.attempts[0].marked),
                "strict_ultrasound_success": any(attempt.marked and attempt.ultra_match for attempt in session.attempts),
                "fallback_success": (
                    session.marked
                    and session.marked_attempt_index is not None
                    and not session.attempts[session.marked_attempt_index - 1].ultra_match
                ),
            }
        )
    write_csv(OUTPUT_DIR / "session_metrics.csv", session_rows)
    write_csv(OUTPUT_DIR / "attempt_metrics.csv", [asdict(attempt) for attempt in attempts])
    (OUTPUT_DIR / "metrics_summary.json").write_text(json.dumps(summary, indent=2))
    generate_figures(summary, attempts)


def main(argv: list[str]) -> int:
    ensure_dirs()
    paths = [Path(arg).expanduser().resolve() for arg in argv] if argv else DEFAULT_LOGS
    devices, sessions, attempts = load_reports(paths)
    summary = summarise(devices, sessions, attempts)
    write_outputs(devices, sessions, attempts, summary)
    print(f"Wrote research artifacts to {OUTPUT_DIR}")
    print(f"Parsed {len(devices)} devices, {len(sessions)} scan sessions, and {len(attempts)} verification attempts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
