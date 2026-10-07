#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "app/src/androidTest/java"
DEFAULT_MANIFEST = ROOT / "scripts/android-test-suites.json"
CATEGORIES = ("smoke", "full_only", "jvm_candidate", "review_required")
IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_]*"
IDENTITY_RE = re.compile(rf"^(?:{IDENTIFIER}\.)+{IDENTIFIER}#{IDENTIFIER}$")
PACKAGE_RE = re.compile(r"^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$", re.M)
TEST_ANNOTATION_RE = re.compile(r"@(?:org\.junit\.)?Test\b")
TEST_RE = re.compile(r"@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
CLASS_RE = re.compile(rf"^[ \t]*(?:(?:public|internal)\s+)?class\s+({IDENTIFIER})\s*\{{", re.M)
NON_CODE_START_RE = re.compile(r'//|/\*|"""|"|\'|`')


def fail(code: str, detail: str | None = None):
    if detail:
        print(f"{code}: {detail}", file=sys.stderr)
    else:
        print(code, file=sys.stderr)
    raise SystemExit(1)


def structural_source(text: str, detail: str) -> str:
    # Preserve offsets/newlines while masking comments and literals. This is only
    # a brace/annotation ownership check, not a general Kotlin declaration parser.
    masked = list(text)
    offset = 0
    while start := NON_CODE_START_RE.search(text, offset):
        token = start.group()
        end = start.end()
        if token == "//":
            newline = text.find("\n", end)
            end = len(text) if newline < 0 else newline
        elif token == "/*":
            depth = 1
            while depth:
                boundary = re.search(r"/\*|\*/", text[end:])
                if not boundary:
                    fail("ANDROID_TEST_SUITE_UNSUPPORTED_TEST_STRUCTURE", detail)
                depth += 1 if boundary.group() == "/*" else -1
                end += boundary.end()
        else:
            while True:
                if end >= len(text):
                    fail("ANDROID_TEST_SUITE_UNSUPPORTED_TEST_STRUCTURE", detail)
                if text.startswith(token, end):
                    end += len(token)
                    break
                if token in ('"', "'") and text[end] == "\\":
                    end += 2
                else:
                    end += 1
        for index in range(start.start(), end):
            if text[index] not in "\r\n":
                masked[index] = " "
        offset = end
    return "".join(masked)


def check_test_ownership(text: str, simple_class: str, detail: str) -> None:
    classes = [match for match in CLASS_RE.finditer(text) if match.group(1) == simple_class]
    if len(classes) != 1:
        fail("ANDROID_TEST_SUITE_CLASS_MISMATCH", detail)
    class_open = classes[0].end() - 1
    braces: list[int] = []
    for token in re.finditer(r"[{}]|@(?:org\.junit\.)?Test\b", text):
        if token.group() == "{":
            if token.start() == class_open and braces:
                fail("ANDROID_TEST_SUITE_UNSUPPORTED_TEST_OWNERSHIP", detail)
            braces.append(token.start())
        elif token.group() == "}":
            if not braces:
                fail("ANDROID_TEST_SUITE_UNSUPPORTED_TEST_STRUCTURE", detail)
            braces.pop()
        elif braces != [class_open]:
            # All tests must be direct members of the sole stem-matching
            # top-level class. Other helper/nested classes may have no @Test.
            fail("ANDROID_TEST_SUITE_UNSUPPORTED_TEST_OWNERSHIP", detail)
    if braces:
        fail("ANDROID_TEST_SUITE_UNSUPPORTED_TEST_STRUCTURE", detail)


def source_identities() -> list[str]:
    identities: list[str] = []
    for path in sorted(TEST_ROOT.rglob("*.kt")):
        detail = str(path.relative_to(ROOT))
        text = structural_source(path.read_text(encoding="utf-8"), detail)
        annotations = TEST_ANNOTATION_RE.findall(text)
        if not annotations:
            continue
        methods = TEST_RE.findall(text)
        # The manifest must fail closed if Kotlin test declarations move beyond
        # the deliberately narrow parser shape. Otherwise a valid new @Test
        # (for example @Test + @LargeTest + fun, or @Test + public fun) could
        # silently disappear from the suite census.
        if len(methods) != len(annotations):
            fail(
                "ANDROID_TEST_SUITE_UNSUPPORTED_TEST_DECLARATION",
                str(path.relative_to(ROOT)),
            )
        package_match = PACKAGE_RE.search(text)
        if not package_match:
            fail("ANDROID_TEST_SUITE_PACKAGE_NOT_FOUND", str(path.relative_to(ROOT)))
        simple_class = path.stem
        check_test_ownership(text, simple_class, detail)
        if len(methods) != len(set(methods)):
            fail("ANDROID_TEST_SUITE_DUPLICATE_METHOD", str(path.relative_to(ROOT)))
        class_name = f"{package_match.group(1)}.{simple_class}"
        identities.extend(f"{class_name}#{method}" for method in methods)
    if len(identities) != len(set(identities)):
        fail("ANDROID_TEST_SUITE_DUPLICATE_SOURCE_IDENTITY")
    return sorted(identities)


def load_manifest(path: Path) -> dict:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail("ANDROID_TEST_SUITE_MANIFEST_INVALID", str(exc))
    suites = manifest.get("suites")
    if not isinstance(suites, dict) or set(suites) != set(CATEGORIES):
        fail("ANDROID_TEST_SUITE_MANIFEST_CATEGORIES")
    for category in CATEGORIES:
        values = suites.get(category)
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            fail("ANDROID_TEST_SUITE_MANIFEST_LIST", category)
        if values != sorted(values):
            fail("ANDROID_TEST_SUITE_MANIFEST_SORT_ORDER", category)
        for identity in values:
            if not IDENTITY_RE.fullmatch(identity):
                fail("ANDROID_TEST_SUITE_MANIFEST_IDENTITY", identity)
    return manifest


def validate_manifest(path: Path) -> tuple[dict, list[str], str]:
    manifest = load_manifest(path)
    source = source_identities()
    suites = manifest["suites"]
    partition = [identity for category in CATEGORIES for identity in suites[category]]
    duplicates = sorted(identity for identity, count in Counter(partition).items() if count > 1)
    if duplicates:
        fail("ANDROID_TEST_SUITE_MANIFEST_DUPLICATE", ", ".join(duplicates[:5]))
    partition_set = set(partition)
    source_set = set(source)
    missing = sorted(source_set - partition_set)
    stale = sorted(partition_set - source_set)
    if missing or stale:
        fail(
            "ANDROID_TEST_SUITE_MANIFEST_DRIFT",
            json.dumps({"missing": missing, "stale": stale}, ensure_ascii=False),
        )
    smoke = suites["smoke"]
    if not smoke:
        fail("ANDROID_TEST_SUITE_SMOKE_EMPTY")
    pattern = r"^(?:" + "|".join(re.escape(identity) for identity in smoke) + r")$"
    if len(pattern) > 16 * 1024:
        fail("ANDROID_TEST_SUITE_SMOKE_REGEX_TOO_LARGE", str(len(pattern)))
    selected = sorted(identity for identity in source if re.search(pattern, identity))
    if selected != smoke:
        fail("ANDROID_TEST_SUITE_SMOKE_REGEX_MISMATCH")
    return manifest, source, pattern


def selection_payload(manifest: dict, source: list[str], pattern: str) -> dict:
    suites = manifest["suites"]
    return {
        "schema_version": manifest.get("schema_version"),
        "source_count": len(source),
        "suite_counts": {category: len(suites[category]) for category in CATEGORIES},
        "expected": suites["smoke"],
        "regex": pattern,
        "regex_length": len(pattern),
        "sha": os.environ.get("GITHUB_SHA"),
        "run_id": os.environ.get("GITHUB_RUN_ID"),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
    }


def command_validate(args: argparse.Namespace) -> None:
    manifest, source, pattern = validate_manifest(args.manifest)
    payload = selection_payload(manifest, source, pattern)
    if args.selection_out:
        args.selection_out.parent.mkdir(parents=True, exist_ok=True)
        args.selection_out.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    printable = dict(payload)
    printable.pop("regex", None)
    print(json.dumps(printable, indent=2, ensure_ascii=False))
    print("ANDROID_TEST_SUITE_MANIFEST_PASS")


def read_selection(path: Path) -> dict:
    try:
        selection = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail("ANDROID_TEST_SUITE_SELECTION_INVALID", str(exc))
    expected = selection.get("expected")
    pattern = selection.get("regex")
    if not isinstance(expected, list) or not expected or not isinstance(pattern, str):
        fail("ANDROID_TEST_SUITE_SELECTION_INCOMPLETE")
    return selection


def command_run_smoke(args: argparse.Namespace) -> None:
    selection = read_selection(args.selection)
    expected_sha = selection.get("sha")
    current_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if expected_sha and current_sha != expected_sha:
        fail("ANDROID_TEST_SUITE_CHECKOUT_SHA_MISMATCH", f"{current_sha} != {expected_sha}")
    result_dir = ROOT / "app/build/outputs/androidTest-results/managedDevice"
    if result_dir.exists():
        fail("ANDROID_TEST_SUITE_EXISTING_RESULTS")
    env = os.environ.copy()
    env.pop("ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.class", None)
    env.pop("ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.tests_regex", None)
    # The Android test engine ultimately rebuilds an adb shell command. Keep literal
    # single quotes in the property value so the device shell receives the regex as
    # one token. Pass the property as one subprocess argv item; never interpolate it
    # into a host shell command.
    transport = "'" + selection["regex"] + "'"
    command = [
        args.gradle,
        args.task,
        "--rerun",
        f"-Pandroid.testInstrumentationRunnerArguments.tests_regex={transport}",
        "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect",
        "--no-daemon",
    ]
    print(json.dumps({"argv_without_selector": [
                          args.gradle, args.task, "--rerun",
                          "-Pandroid.testInstrumentationRunnerArguments.tests_regex=<quoted fixed manifest regex>",
                          "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect",
                          "--no-daemon"],
                      "expected_tests": len(selection["expected"]),
                      "regex_length": len(selection["regex"])}, indent=2))
    subprocess.run(command, cwd=ROOT, env=env, check=True)


def command_verify_smoke(args: argparse.Namespace) -> None:
    selection = read_selection(args.selection)
    try:
        started = int(args.started_at.read_text(encoding="utf-8").strip())
    except (OSError, ValueError) as exc:
        fail("ANDROID_TEST_SUITE_STARTED_AT_INVALID", str(exc))
    result_dir = ROOT / "app/build/outputs/androidTest-results/managedDevice"
    expected_file = result_dir / "debug/pixel9Api37/TEST-pixel9Api37.xml"
    files = sorted(result_dir.rglob("TEST-*.xml")) if result_dir.exists() else []
    if files != [expected_file] or not expected_file.is_file() or expected_file.stat().st_mtime < started:
        fail("ANDROID_TEST_SUITE_FRESH_XML_REQUIRED")
    xml = expected_file.read_bytes()
    if b"<!DOCTYPE" in xml.upper() or b"<!ENTITY" in xml.upper():
        fail("ANDROID_TEST_SUITE_INVALID_XML")
    root = ET.fromstring(xml)
    cases = root.findall(".//testcase")
    actual = [f'{case.get("classname")}#{case.get("name")}' for case in cases]
    summary = {
        "sha": selection.get("sha"),
        "expected": sorted(selection["expected"]),
        "observed": sorted(actual),
        "tests": len(cases),
        "failures": sum(len(case.findall("failure")) for case in cases),
        "errors": sum(len(case.findall("error")) for case in cases),
        "skipped": sum(len(case.findall("skipped")) for case in cases),
    }
    args.verification_out.parent.mkdir(parents=True, exist_ok=True)
    args.verification_out.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k not in ("expected", "observed")}, indent=2))
    if not actual or Counter(actual) != Counter(selection["expected"]):
        fail("ANDROID_TEST_SUITE_SMOKE_SELECTOR_NOT_ENFORCED")
    if root.tag != "testsuites" or any(
        int(root.get(key, "-1")) != summary[key]
        for key in ("tests", "failures", "errors", "skipped")
    ):
        fail("ANDROID_TEST_SUITE_COUNTER_MISMATCH")
    devices = root.findall('.//property[@name="device"]')
    if not devices or any(device.get("value") != "_app_pixel9Api37DebugAndroidTest" for device in devices):
        fail("ANDROID_TEST_SUITE_DEVICE_MISMATCH")
    if summary["skipped"]:
        fail("ANDROID_TEST_SUITE_SMOKE_SKIPPED")
    if summary["failures"] or summary["errors"]:
        if args.allow_test_failures:
            print("ANDROID_TEST_SUITE_SMOKE_TEST_FAILURE_OBSERVED")
        else:
            fail("ANDROID_TEST_SUITE_SMOKE_FAILURE")
    print("ANDROID_TEST_SUITE_SMOKE_EXACT_IDENTITIES_PASS")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate")
    validate.add_argument("--selection-out", type=Path)
    validate.set_defaults(func=command_validate)

    run = sub.add_parser("run-smoke")
    run.add_argument("--selection", type=Path, required=True)
    run.add_argument("--gradle", default="./gradlew")
    run.add_argument("--task", default=":app:pixel9Api37DebugAndroidTest")
    run.set_defaults(func=command_run_smoke)

    verify = sub.add_parser("verify-smoke")
    verify.add_argument("--selection", type=Path, required=True)
    verify.add_argument("--started-at", type=Path, required=True)
    verify.add_argument("--verification-out", type=Path, required=True)
    verify.add_argument("--allow-test-failures", action="store_true")
    verify.set_defaults(func=command_verify_smoke)
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
