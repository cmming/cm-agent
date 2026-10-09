import os
import pathlib
import zipfile

# 固定输出目录来自服务端引导程序，不能由模型输入覆盖。
output = pathlib.Path(os.environ["CM_AGENT_ARTIFACT_DIR"]) / "测试文档.docx"
parts = {
    "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    "_rels/.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
    "word/document.xml": '<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>技能沙箱文件产物验收</w:t></w:r></w:p><w:sectPr/></w:body></w:document>',
}
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as document:
    for name, content in parts.items():
        document.writestr(name, content.encode("utf-8"))
print("已生成测试文档，文件通过控制台鉴权下载。")
