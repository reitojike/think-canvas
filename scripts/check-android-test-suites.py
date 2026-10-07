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
    return manifest


def receipt_payload(manifest: dict) -> dict:
    return {
        "schema_version": manifest["schema_version"],
        "planning_count": sum(len(values) for values in manifest["suites"].values()),
        "planning_counts": {category: len(manifest["suites"][category]) for category in CATEGORIES},
        "annotation": SMOKE_ANNOTATION,
        "expected": manifest["suites"]["smoke"],
        "sha": os.environ.get("GITHUB_SHA"),
        "run_id": os.environ.get("GITHUB_RUN_ID"),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
    }


def command_validate(args: argparse.Namespace) -> None:
    payload = receipt_payload(validate_manifest(args.manifest))
    if args.receipt_out:
        args.receipt_out.parent.mkdir(parents=True, exist_ok=True)
        args.receipt_out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2))
    print("ANDROID_TEST_SUITE_MANIFEST_PASS")


def command_verify_smoke(args: argparse.Namespace) -> None:
    payload = receipt_payload(validate_manifest(args.manifest))
    try:
        started = int(args.started_at.read_text(encoding="utf-8").strip())
        if started <= 0:
            raise ValueError("start time must be positive")
    except (OSError, ValueError) as exc:
        fail("ANDROID_TEST_SUITE_STARTED_AT_INVALID", str(exc))
    result_dir = ROOT / "app/build/outputs/androidTest-results/managedDevice"
    expected_file = result_dir / "debug/pixel9Api37/TEST-pixel9Api37.xml"
    files = sorted(result_dir.rglob("TEST-*.xml")) if result_dir.exists() else []
    if files != [expected_file] or not expected_file.is_file() or expected_file.stat().st_mtime < started:
        fail("ANDROID_TEST_SUITE_FRESH_XML_REQUIRED")
    try:
        xml = expected_file.read_text(encoding="utf-8-sig")
        if "<!DOCTYPE" in xml.upper() or "<!ENTITY" in xml.upper():
            fail("ANDROID_TEST_SUITE_INVALID_XML")
        root = ET.fromstring(xml)
    except (OSError, UnicodeError, ET.ParseError) as exc:
        fail("ANDROID_TEST_SUITE_INVALID_XML", str(exc))
    cases = root.findall(".//testcase")
    actual = [f'{case.get("classname")}#{case.get("name")}' for case in cases]
    summary = {
        **payload,
        "observed": sorted(actual),
        "identity_match": Counter(actual) == Counter(payload["expected"]),
        "tests": len(cases),
        "failures": sum(len(case.findall("failure")) for case in cases),
        "errors": sum(len(case.findall("error")) for case in cases),
        "skipped": sum(len(case.findall("skipped")) for case in cases),
    }
    args.verification_out.parent.mkdir(parents=True, exist_ok=True)
    args.verification_out.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k not in ("expected", "observed")}, indent=2))
    if not summary["identity_match"]:
        fail("ANDROID_TEST_SUITE_SMOKE_SELECTOR_NOT_ENFORCED")
    if root.tag != "testsuites" or any(
        not re.fullmatch(r"[0-9]+", root.get(key, "")) or int(root.get(key)) != summary[key]
        for key in ("tests", "failures", "errors", "skipped")
    ):
        fail("ANDROID_TEST_SUITE_COUNTER_MISMATCH")
    devices = root.findall('.//property[@name="device"]')
    if not devices or any(device.get("value") != "_app_pixel9Api37DebugAndroidTest" for device in devices):
        fail("ANDROID_TEST_SUITE_DEVICE_MISMATCH")
    if summary["skipped"]:
        fail("ANDROID_TEST_SUITE_SMOKE_SKIPPED")
    if summary["failures"] or summary["errors"]:
        fail("ANDROID_TEST_SUITE_SMOKE_FAILURE")
    print("ANDROID_TEST_SUITE_SMOKE_EXACT_IDENTITIES_PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("--receipt-out", type=Path)
    validate.set_defaults(func=command_validate)
    verify = sub.add_parser("verify-smoke")
    verify.add_argument("--started-at", type=Path, required=True)
    verify.add_argument("--verification-out", type=Path, required=True)
    verify.set_defaults(func=command_verify_smoke)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
