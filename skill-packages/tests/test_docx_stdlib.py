"""验证隔离标准库执行、DOCX 内容保真和失败时不交付文件。"""

import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "docx-stdlib/scripts/main.py"
SAMPLE = ROOT / "docx-stdlib/examples/sample.json"
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}


class DocxStdlibTest(unittest.TestCase):
    def run_script(self, raw, directory, env_ready=True):
        env = os.environ.copy()
        env.pop("CM_AGENT_ARTIFACT_DIR", None)
        if env_ready:
            env["CM_AGENT_ARTIFACT_DIR"] = str(directory)
        return subprocess.run([sys.executable, "-I", "-S", str(SCRIPT)], input=raw,
                              capture_output=True, env=env, timeout=10)

    def check_failure(self, raw, code="DOCX_INPUT_INVALID", env_ready=True):
        with tempfile.TemporaryDirectory() as directory:
            process = self.run_script(raw, directory, env_ready)
            self.assertEqual(process.returncode, 2)
            result = json.loads(process.stdout)
            self.assertEqual(result["errorCode"], code)
            self.assertRegex(result["errorId"], r"^docx-[0-9a-f]{32}$")
            self.assertEqual(process.stderr, b"")
            self.assertEqual(list(Path(directory).iterdir()), [])
            self.assertNotIn(directory, process.stdout.decode("utf-8"))
            return result

    def test_sample_content_relationships_and_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            process = self.run_script(SAMPLE.read_bytes(), directory)
            self.assertEqual(process.returncode, 0, process.stdout)
            result = json.loads(process.stdout)
            path = Path(directory) / result["filename"]
            self.assertEqual(result["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual(result["bytes"], path.stat().st_size)
            with zipfile.ZipFile(path) as archive:
                self.assertIsNone(archive.testzip())
                for name in archive.namelist():
                    ET.fromstring(archive.read(name))
                body = ET.fromstring(archive.read("word/document.xml"))
                content = "".join(body.itertext())
                self.assertIn("中文、英文以及特殊符号 <、>、&", content)
                self.assertEqual(len(body.findall(".//w:tbl", NS)), 1)
                self.assertEqual(len(body.findall(".//w:numPr", NS)), 5)
                self.assertEqual(len(body.findall(".//w:tblHeader", NS)), 1)
                numbering = ET.fromstring(archive.read("word/numbering.xml"))
                self.assertEqual([x.tag.split("}")[1] for x in numbering],
                                 ["abstractNum", "abstractNum", "num", "num"])
                rels = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
                for relationship in rels:
                    self.assertIn("word/" + relationship.get("Target"), archive.namelist())

    def test_imports_are_standard_library(self):
        tree = ast.parse(SCRIPT.read_text(encoding="utf-8"))
        modules = set()
        for item in ast.walk(tree):
            if isinstance(item, ast.Import):
                modules.update(alias.name.split(".")[0] for alias in item.names)
            elif isinstance(item, ast.ImportFrom):
                modules.add(item.module.split(".")[0])
        self.assertLessEqual(modules, sys.stdlib_module_names)

    def test_reject_path_and_reserved_names(self):
        sample = json.loads(SAMPLE.read_bytes())
        for filename in ["../escape.docx", "C:\\secret.docx", ".docx", "CON.docx", "a?.docx"]:
            with self.subTest(filename=filename):
                sample["filename"] = filename
                self.check_failure(json.dumps(sample).encode())

    def test_reject_invalid_table_without_file(self):
        self.check_failure(json.dumps({"blocks": [{"type": "table", "headers": ["a", "b"],
                                                   "rows": [["one"]]}]}).encode())

    def test_reject_unknown_fields_and_code(self):
        self.check_failure(b'{"blocks": [{"type":"paragraph","text":"x"}],"code":"print(1)"}')

    def test_reject_duplicate_keys(self):
        self.check_failure(b'{"blocks":[],"blocks":[{"type":"paragraph","text":"x"}]}')

    def test_reject_invalid_utf8_and_json(self):
        for raw in [b"\xff", b"{", b'{"blocks": NaN}', b"[]"]:
            with self.subTest(raw=raw):
                self.check_failure(raw)

    def test_reject_invalid_xml_and_bool_level(self):
        for block in [{"type": "paragraph", "text": "bad\x01"},
                      {"type": "heading", "level": True, "text": "x"}]:
            with self.subTest(block=block):
                self.check_failure(json.dumps({"blocks": [block]}).encode())

    def test_reject_input_over_budget(self):
        self.check_failure(b" " * 32769)

    def test_missing_output_directory_is_explicit(self):
        self.check_failure(SAMPLE.read_bytes(), "DOCX_OUTPUT_NOT_READY", False)

    def test_existing_file_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "标准库文档示例.docx"
            target.write_bytes(b"keep-me")
            process = self.run_script(SAMPLE.read_bytes(), directory)
            self.assertEqual(process.returncode, 2)
            self.assertEqual(json.loads(process.stdout)["errorCode"], "DOCX_FILE_EXISTS")
            self.assertEqual(target.read_bytes(), b"keep-me")

    def test_landscape_line_break_and_separate_lists(self):
        spec = {"orientation": "landscape", "blocks": [
            {"type": "paragraph", "text": "第一行\n第二行\t末尾"},
            {"type": "page_break"}, {"type": "list", "ordered": True, "items": ["甲"]},
            {"type": "list", "ordered": True, "items": ["乙"]}]}
        with tempfile.TemporaryDirectory() as directory:
            process = self.run_script(json.dumps(spec).encode(), directory)
            self.assertEqual(process.returncode, 0)
            with zipfile.ZipFile(Path(directory) / "文档.docx") as archive:
                doc = ET.fromstring(archive.read("word/document.xml"))
                self.assertEqual(len(doc.findall(".//w:br", NS)), 2)
                self.assertEqual(len(doc.findall(".//w:tab", NS)), 1)
                page = doc.find(".//w:pgSz", NS)
                self.assertGreater(int(page.get("{" + NS["w"] + "}w")), int(page.get("{" + NS["w"] + "}h")))


if __name__ == "__main__":
    unittest.main()
