#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "scripts/android-test-suites.json"
CATEGORIES = ("smoke", "full_only", "jvm_candidate", "review_required")
MIGRATION_COUNTS = dict(smoke=73, full_only=170, jvm_candidate=9, review_required=0)
SMOKE_ANNOTATION = "com.thinkcanvas.test.PrSmoke"
FLAKY_ANNOTATION = "androidx.test.filters.FlakyTest"
LANE_COUNTS = {"smoke": 56, "non-quarantined": 214, "quarantine": 38, "full": 252}
IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_]*"
IDENTITY_RE = re.compile(rf"^(?:{IDENTIFIER}\.)+{IDENTIFIER}#{IDENTIFIER}$")


def fail(code: str, detail: str | None = None):
    print(f"{code}: {detail}" if detail else code, file=sys.stderr)
    raise SystemExit(1)


def validate_manifest(path: Path) -> dict:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        fail("ANDROID_TEST_SUITE_MANIFEST_INVALID", str(exc))
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        fail("ANDROID_TEST_SUITE_MANIFEST_INVALID")
    suites = manifest.get("suites")
    if not isinstance(suites, dict) or set(suites) != set(CATEGORIES):
        fail("ANDROID_TEST_SUITE_MANIFEST_CATEGORIES")
    partition: list[str] = []
    for category in CATEGORIES:
        values = suites[category]
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            fail("ANDROID_TEST_SUITE_MANIFEST_LIST", category)
        if values != sorted(values):
            fail("ANDROID_TEST_SUITE_MANIFEST_SORT_ORDER", category)
        if any(not IDENTITY_RE.fullmatch(identity) for identity in values):
            fail("ANDROID_TEST_SUITE_MANIFEST_IDENTITY", category)
        partition.extend(values)
    if len(partition) != len(set(partition)):
        fail("ANDROID_TEST_SUITE_MANIFEST_DUPLICATE")
    # These counts freeze the migration snapshot, not actual runner discovery.
    if {category: len(suites[category]) for category in CATEGORIES} != MIGRATION_COUNTS:
        fail("ANDROID_TEST_SUITE_MIGRATION_SNAPSHOT_DRIFT")
    quarantine = manifest.get("quarantine", {})
    expected = quarantine.get("expected")
    if (quarantine.get("issue") != 106 or quarantine.get("review_date") != "2026-10-23"
            or not isinstance(expected, list) or not all(isinstance(v, str) for v in expected)):
        fail("ANDROID_TEST_SUITE_QUARANTINE_INVALID")
    if (len(expected) != 38 or expected != sorted(set(expected))
            or not set(expected) <= set(partition)
            or len(set(expected) & set(suites["smoke"])) != 17):
        fail("ANDROID_TEST_SUITE_QUARANTINE_SNAPSHOT_DRIFT")
    return manifest


def receipt_payload(manifest: dict, lane: str = "smoke") -> dict:
    full = {identity for values in manifest["suites"].values() for identity in values}
    quarantine = set(manifest["quarantine"]["expected"])
    expected = {
        "smoke": set(manifest["suites"]["smoke"]) - quarantine,
        "non-quarantined": full - quarantine,
        "quarantine": quarantine,
        "full": full,
    }[lane]
    # Static receipts verify runner results; they never generate an execution selector.
    return {
        "schema_version": manifest["schema_version"],
        "planning_count": sum(len(values) for values in manifest["suites"].values()),
        "planning_counts": {category: len(manifest["suites"][category]) for category in CATEGORIES},
        "lane": lane,
        "annotation": SMOKE_ANNOTATION if lane == "smoke" else FLAKY_ANNOTATION if lane == "quarantine" else None,
        "notAnnotation": FLAKY_ANNOTATION if lane in ("smoke", "non-quarantined") else None,
        "expected_count": LANE_COUNTS[lane],
        "expected": sorted(expected),
        "sha": os.environ.get("GITHUB_SHA"),
        "run_id": os.environ.get("GITHUB_RUN_ID"),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "event": os.environ.get("GITHUB_EVENT_NAME"),
    }


def command_validate(args: argparse.Namespace) -> None:
    payload = receipt_payload(validate_manifest(args.manifest), args.suite)
    if args.receipt_out:
        args.receipt_out.parent.mkdir(parents=True, exist_ok=True)
        args.receipt_out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2))
    print("ANDROID_TEST_SUITE_MANIFEST_PASS")


def command_verify_suite(args: argparse.Namespace) -> None:
    payload = receipt_payload(validate_manifest(args.manifest), args.suite)
    summary = {**payload, "sample_status": "infra-invalid", "reason": "XML missing/incomplete",
               "tests": None, "failures": None, "errors": None, "skipped": None}

    def save() -> None:
        args.verification_out.parent.mkdir(parents=True, exist_ok=True)
        args.verification_out.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    def reject(code: str) -> None:
        summary["reason"] = code
        save()
        fail(code)

    save()
    try:
        started = int(args.started_at.read_text(encoding="utf-8").strip())
        if started <= 0:
            raise ValueError("start time must be positive")
    except (OSError, ValueError):
        reject("ANDROID_TEST_SUITE_STARTED_AT_INVALID")
    result_dir = ROOT / "app/build/outputs/androidTest-results/managedDevice"
    expected_file = result_dir / "debug/pixel9Api37/TEST-pixel9Api37.xml"
    files = sorted(result_dir.rglob("TEST-*.xml")) if result_dir.exists() else []
    if files != [expected_file] or not expected_file.is_file() or expected_file.stat().st_mtime < started:
        reject("ANDROID_TEST_SUITE_FRESH_XML_REQUIRED")
    try:
        xml = expected_file.read_text(encoding="utf-8-sig")
        if "<!DOCTYPE" in xml.upper() or "<!ENTITY" in xml.upper():
            reject("ANDROID_TEST_SUITE_INVALID_XML")
        root = ET.fromstring(xml)
    except (OSError, UnicodeError, ET.ParseError):
        reject("ANDROID_TEST_SUITE_INVALID_XML")
    cases = root.findall(".//testcase")
    actual = [f'{case.get("classname")}#{case.get("name")}' for case in cases]
    summary.update({
        "observed": sorted(actual),
        "identity_match": Counter(actual) == Counter(payload["expected"]),
        "tests": len(cases),
        "failures": sum(len(case.findall("failure")) for case in cases),
        "errors": sum(len(case.findall("error")) for case in cases),
        "skipped": sum(len(case.findall("skipped")) for case in cases),
    })
    save()
    if not summary["identity_match"]:
        reject("ANDROID_TEST_SUITE_INVENTORY_MISMATCH")
    if root.tag != "testsuites" or any(
        not re.fullmatch(r"[0-9]+", root.get(key, "")) or int(root.get(key)) != summary[key]
        for key in ("tests", "failures", "errors", "skipped")
    ):
        reject("ANDROID_TEST_SUITE_COUNTER_MISMATCH")
    suites = root.findall("testsuite")
    if not suites or any(
        not re.fullmatch(r"[0-9]+", suite.get(key, "")) or int(suite.get(key)) != value
        for suite in suites
        for key, value in {
            "tests": len(suite.findall("testcase")),
            "failures": sum(len(case.findall("failure")) for case in suite.findall("testcase")),
            "errors": sum(len(case.findall("error")) for case in suite.findall("testcase")),
            "skipped": sum(len(case.findall("skipped")) for case in suite.findall("testcase")),
        }.items()
    ):
        reject("ANDROID_TEST_SUITE_INCOMPLETE_COUNTERS")
    devices = root.findall('.//property[@name="device"]')
    if not devices or any(device.get("value") != "_app_pixel9Api37DebugAndroidTest" for device in devices):
        reject("ANDROID_TEST_SUITE_DEVICE_MISMATCH")
    if summary["skipped"]:
        reject("ANDROID_TEST_SUITE_SKIPPED")
    summary.update(sample_status="valid", reason="complete fresh XML with exact inventory")
    save()
    print(json.dumps({k: v for k, v in summary.items() if k not in ("expected", "observed")}, indent=2))
    if summary["failures"] or summary["errors"]:
        fail("ANDROID_TEST_SUITE_TEST_FAILURE")
    print("ANDROID_TEST_SUITE_EXACT_IDENTITIES_PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("--suite", choices=LANE_COUNTS, default="smoke")
    validate.add_argument("--receipt-out", type=Path)
    validate.set_defaults(func=command_validate)
    verify = sub.add_parser("verify-smoke")
    verify.add_argument("--started-at", type=Path, required=True)
    verify.add_argument("--verification-out", type=Path, required=True)
    verify.set_defaults(func=command_verify_suite, suite="smoke")
    verify_suite = sub.add_parser("verify-suite")
    verify_suite.add_argument("--suite", choices=LANE_COUNTS, required=True)
    verify_suite.add_argument("--started-at", type=Path, required=True)
    verify_suite.add_argument("--verification-out", type=Path, required=True)
    verify_suite.set_defaults(func=command_verify_suite)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
