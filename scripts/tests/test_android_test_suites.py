"""Runner XML の inventory / freshness / failure semantics を検証する。"""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "check-android-test-suites.py"
spec = importlib.util.spec_from_file_location("suites", SCRIPT)
suites = importlib.util.module_from_spec(spec)
spec.loader.exec_module(suites)


class RunnerXmlContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.xml = self.root / "app/build/outputs/androidTest-results/managedDevice/debug/pixel9Api37/TEST-pixel9Api37.xml"
        self.xml.parent.mkdir(parents=True)
        self.started = self.root / "started"
        self.started.write_text("100", encoding="utf-8")
        self.result = self.root / "verification.json"
        self.manifest = suites.validate_manifest(suites.DEFAULT_MANIFEST)
        self.patch = patch.object(suites, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def document(self, lane="quarantine"):
        identities = suites.receipt_payload(self.manifest, lane)["expected"]
        root = ET.Element("testsuites", tests=str(len(identities)), failures="0", errors="0", skipped="0")
        child = ET.SubElement(root, "testsuite", tests=str(len(identities)), failures="0", errors="0", skipped="0")
        properties = ET.SubElement(child, "properties")
        ET.SubElement(properties, "property", name="device", value="_app_pixel9Api37DebugAndroidTest")
        for identity in identities:
            cls, method = identity.split("#")
            ET.SubElement(child, "testcase", classname=cls, name=method)
        return root

    def verify(self, root=None, lane="quarantine", stale=False):
        if root is not None:
            ET.ElementTree(root).write(self.xml, encoding="utf-8", xml_declaration=True)
            os.utime(self.xml, (99 if stale else 101, 99 if stale else 101))
        args = argparse.Namespace(manifest=suites.DEFAULT_MANIFEST, suite=lane,
                                  started_at=self.started, verification_out=self.result)
        code = 0
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                suites.command_verify_suite(args)
            except SystemExit as exc:
                code = exc.code
        return code, json.loads(self.result.read_text(encoding="utf-8"))

    def test_all_four_exact_lanes(self):
        for lane, count in suites.LANE_COUNTS.items():
            with self.subTest(lane=lane):
                code, receipt = self.verify(self.document(lane), lane)
                self.assertEqual((code, receipt["sample_status"], receipt["tests"]), (0, "valid", count))

    def test_test_failure_is_valid_red(self):
        root = self.document()
        for element in (root, root.find("testsuite")):
            element.set("failures", "1")
        ET.SubElement(root.find(".//testcase"), "failure", message="IME visibility timeout")
        code, receipt = self.verify(root)
        self.assertEqual((code, receipt["sample_status"], receipt["failures"]), (1, "valid", 1))

    def test_test_error_is_valid_red(self):
        root = self.document()
        for element in (root, root.find("testsuite")):
            element.set("errors", "1")
        ET.SubElement(root.find(".//testcase"), "error")
        code, receipt = self.verify(root)
        self.assertEqual((code, receipt["sample_status"], receipt["errors"]), (1, "valid", 1))

    def test_same_count_wrong_identity_is_invalid(self):
        root = self.document()
        root.find(".//testcase").set("name", "wrongIdentity")
        self.assert_invalid(self.verify(root))

    def test_duplicate_identity_is_invalid(self):
        root = self.document()
        first, second = root.findall(".//testcase")[:2]
        second.attrib.update(first.attrib)
        self.assert_invalid(self.verify(root))

    def test_full_in_smoke_is_invalid(self):
        self.assert_invalid(self.verify(self.document("full"), "smoke"))

    def test_missing_xml_is_invalid(self):
        code, receipt = self.verify()
        self.assertEqual((code, receipt["sample_status"], receipt["tests"]), (1, "infra-invalid", None))

    def test_stale_xml_is_invalid(self):
        self.assert_invalid(self.verify(self.document(), stale=True))

    def test_partial_xml_is_invalid(self):
        self.xml.write_text("<testsuites>", encoding="utf-8")
        self.assert_invalid(self.verify())

    def test_root_counter_mismatch_is_invalid(self):
        root = self.document()
        root.set("tests", "39")
        self.assert_invalid(self.verify(root))

    def test_child_counter_mismatch_is_invalid(self):
        root = self.document()
        root.find("testsuite").set("tests", "39")
        self.assert_invalid(self.verify(root))

    def test_wrong_device_is_invalid(self):
        root = self.document()
        root.find(".//property").set("value", "another-device")
        self.assert_invalid(self.verify(root))

    def test_skipped_is_incomplete(self):
        root = self.document()
        for element in (root, root.find("testsuite")):
            element.set("skipped", "1")
        ET.SubElement(root.find(".//testcase"), "skipped")
        self.assert_invalid(self.verify(root))

    def assert_invalid(self, result):
        code, receipt = result
        self.assertEqual((code, receipt["sample_status"]), (1, "infra-invalid"))


if __name__ == "__main__":
    unittest.main()
