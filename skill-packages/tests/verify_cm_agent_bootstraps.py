"""仅在 Rocky 运行，以服务端原始引导代码验证真实 stdin 和产物帧契约。"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import uuid
import xml.etree.ElementTree as ET
import zipfile


def execute(bootstrap, files, stdin, artifacts=False, output_ready=False):
    payload = {"files": files, "path": "scripts/main.py", "stdin": stdin}
    if artifacts:
        payload["artifactPolicy"] = {"text": 32768, "types": [".docx"], "file": 4194304,
                                     "count": 8, "total": 8388608, "scriptTimeout": 15,
                                     "collectionTimeout": 5}
    command = ["docker", "run", "--rm", "--name", "cm-agent-docx-verify-" + uuid.uuid4().hex,
               "--pull=never", "--network=none", "--read-only", "--cap-drop=ALL",
               "--security-opt=no-new-privileges", "--user=65534:65534", "--pids-limit=32",
               "--memory=128m", "--memory-swap=128m", "--cpus=0.5", "--ulimit=nofile=64:64",
               "--log-driver=none", "--tmpfs=/workspace:rw,noexec,nosuid,nodev,size=16m,mode=1777,uid=65534,gid=65534",
               "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777,uid=65534,gid=65534",
               "--workdir=/workspace", "-i"]
    if output_ready:
        # 文本模式没有收集目录，只用容器已有 /tmp 验证 StringIO 兼容，不交付文件。
        command += ["--env", "CM_AGENT_ARTIFACT_DIR=/tmp"]
    command += ["--entrypoint=python", "python:3.12-alpine", "-I", "-B", "-u", "-c", bootstrap]
    return subprocess.run(command, input=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                          capture_output=True, timeout=30)


def contents(path):
    with zipfile.ZipFile(path) as package:
        return {name: package.read(name).decode("utf-8") for name in package.namelist()}


def verify(args):
    text_boot = args.text_bootstrap.read_text(encoding="utf-8")
    artifact_boot = args.artifact_bootstrap.read_text(encoding="utf-8")
    files = contents(args.zip)
    stdin = files["examples/sample.json"]
    if args.previous_zip:
        previous = execute(text_boot, contents(args.previous_zip), stdin, output_ready=True)
        assert previous.returncode == 2
        assert json.loads(previous.stdout)["errorCode"] == "DOCX_INTERNAL_ERROR"
        print("PREVIOUS_STRINGIO_FAILURE_REPRODUCED")
    current = execute(text_boot, files, stdin, output_ready=True)
    assert current.returncode == 0, "文本引导验证失败"
    assert json.loads(current.stdout)["status"] == "created"
    print("CURRENT_TEXT_BOOTSTRAP_OK")
    disabled = execute(text_boot, files, stdin)
    assert disabled.returncode == 2
    assert json.loads(disabled.stdout)["errorCode"] == "DOCX_OUTPUT_NOT_READY"
    print("ARTIFACTS_DISABLED_CONFIGURATION_FAILURE_CONFIRMED")
    current = execute(artifact_boot, files, stdin, artifacts=True)
    assert current.returncode == 0, "文件产物引导验证失败"
    stream = io.BytesIO(current.stdout)
    assert stream.readline() == b"CMA1\n"
    head = json.loads(stream.readline())
    assert head["kind"] == "text"
    result = json.loads(stream.read(head["size"]))
    assert result["status"] == "created"
    file_header = json.loads(stream.readline())
    assert file_header["kind"] == "file"
    assert file_header["name"] == result["filename"]
    data = stream.read(file_header["size"])
    assert len(data) == result["bytes"]
    assert hashlib.sha256(data).hexdigest() == result["sha256"]
    with zipfile.ZipFile(io.BytesIO(data)) as document:
        assert document.testzip() is None
        for name in document.namelist():
            ET.fromstring(document.read(name))
        body = ET.fromstring(document.read("word/document.xml"))
        assert "项目周报" in "".join(body.itertext())
    assert json.loads(stream.readline()) == {"kind": "end"}
    assert stream.read() == b""
    print("CURRENT_ARTIFACT_BOOTSTRAP_CMA1_DOCX_OK bytes=" + str(len(data)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="验证 CM Agent 引导程序与 DOCX 技能")
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument("--previous-zip", type=Path)
    parser.add_argument("--text-bootstrap", type=Path, required=True)
    parser.add_argument("--artifact-bootstrap", type=Path, required=True)
    verify(parser.parse_args())
