"""生成 CM Agent 单技能导入 ZIP；归档仅包含经过校验的文本资源。"""

import argparse
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent
FILES = ("SKILL.md", "scripts/main.py", "references/input-format.md", "examples/sample.json")


def package(destination):
    source = ROOT / "docx-stdlib"
    payload = {}
    for name in FILES:
        content = (source / name).read_bytes()
        content.decode("utf-8")
        maximum = 32768 if name == "SKILL.md" else 262144
        if len(content) > maximum:
            raise ValueError("技能资源大小超过默认上传限制：" + name)
        payload[name] = content
    destination.parent.mkdir(parents=True, exist_ok=True)
    # 固定入口与显式清单避免将测试产物、缓存或无关文件混入技能导入包。
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in payload.items():
            archive.writestr(name, content)
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() or set(archive.namelist()) != set(FILES):
            raise ValueError("归档完整性校验失败")
    print("已生成：" + str(destination.resolve()))
    print("SHA-256：" + hashlib.sha256(destination.read_bytes()).hexdigest())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="打包标准库 DOCX 技能")
    parser.add_argument("--output", type=Path, required=True, help="交付 ZIP 路径")
    package(parser.parse_args().output)
