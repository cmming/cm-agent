"""通过固定 JSON 契约生成 DOCX；运行时仅依赖 Python 3.10+ 标准库。"""

import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
REL = "http://schemas.openxmlformats.org/package/2006/relationships"
CT = "http://schemas.openxmlformats.org/package/2006/content-types"
ET.register_namespace("w", W)
ET.register_namespace("r", R)
MAX_INPUT = 32768
MAX_FILE = 4 * 1024 * 1024


def node(parent, tag, **attrs):
    return ET.SubElement(parent, "{" + W + "}" + tag,
                         {"{" + W + "}" + k: str(v) for k, v in attrs.items()})


def text(value, label, maximum=8000):
    if not isinstance(value, str) or len(value) > maximum:
        raise ValueError(label + "必须是长度合规的文本")
    # XML 1.0 不接受部分控制字符和孤立代理项；拒绝而非静默丢失用户内容。
    if any(not (c in "\t\n\r" or 0x20 <= ord(c) <= 0xD7FF
                or 0xE000 <= ord(c) <= 0xFFFD or 0x10000 <= ord(c) <= 0x10FFFF)
           for c in value):
        raise ValueError(label + "包含 XML 不允许的字符")
    return value


def fields(value, allowed, label):
    if not isinstance(value, dict) or set(value) - set(allowed):
        raise ValueError(label + "必须是对象且不能包含未知字段")


def paragraph(parent, value, style=None, bold=False, number=None):
    p = node(parent, "p")
    props = node(p, "pPr")
    if style:
        node(props, "pStyle", val=style)
    if number:
        np = node(props, "numPr")
        node(np, "ilvl", val=0)
        node(np, "numId", val=number)
    run = node(p, "r")
    if bold:
        node(node(run, "rPr"), "b")
    # 分行和制表符用 OOXML 专用元素，避免依赖阅读器对 w:t 内换行的解释。
    for piece in re.split(r"(\n|\t)", value.replace("\r\n", "\n").replace("\r", "\n")):
        if piece == "\n":
            node(run, "br")
        elif piece == "\t":
            node(run, "tab")
        elif piece:
            t = node(run, "t")
            t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
            t.text = piece
    return p


def styles():
    root = ET.Element("{" + W + "}styles")
    defaults = node(root, "docDefaults")
    rp = node(node(defaults, "rPrDefault"), "rPr")
    node(rp, "rFonts", ascii="Calibri", hAnsi="Calibri", eastAsia="宋体")
    node(rp, "sz", val=22)
    node(rp, "szCs", val=22)
    node(rp, "lang", val="zh-CN", eastAsia="zh-CN")
    pp = node(node(defaults, "pPrDefault"), "pPr")
    node(pp, "spacing", after=120, line=276, lineRule="auto")
    node(pp, "widowControl")
    for name, size in [("Normal", 22), ("Title", 36), ("Subtitle", 24),
                       ("Heading1", 30), ("Heading2", 26), ("Heading3", 24)]:
        st = node(root, "style", type="paragraph", styleId=name)
        if name == "Normal":
            st.set("{" + W + "}default", "1")
        node(st, "name", val=name)
        if name != "Normal":
            node(st, "basedOn", val="Normal")
            node(st, "next", val="Normal")
        node(st, "qFormat")
        p = node(st, "pPr")
        if name != "Normal":
            node(p, "keepNext")
            node(p, "keepLines")
        if name.startswith("Heading"):
            node(p, "spacing", before=240, after=120)
            node(p, "outlineLvl", val=int(name[-1]) - 1)
        rp = node(st, "rPr")
        if name == "Title" or name.startswith("Heading"):
            node(rp, "rFonts", eastAsia="黑体")
            node(rp, "b")
        node(rp, "color", val="000000")
        node(rp, "sz", val=size)
        node(rp, "szCs", val=size)
    return root


def add_numbering(root, num_id, ordered):
    abstract = node(root, "abstractNum", abstractNumId=num_id)
    node(abstract, "multiLevelType", val="singleLevel")
    level = node(abstract, "lvl", ilvl=0)
    node(level, "start", val=1)
    node(level, "numFmt", val="decimal" if ordered else "bullet")
    node(level, "lvlText", val="%1." if ordered else "•")
    node(level, "lvlJc", val="left")
    p = node(level, "pPr")
    node(node(p, "tabs"), "tab", val="num", pos=420)
    node(p, "ind", left=420, hanging=210)
    num = node(root, "num", numId=num_id)
    node(num, "abstractNumId", val=num_id)


def table(body, block, width):
    headers = block.get("headers")
    rows = block.get("rows")
    if not isinstance(headers, list) or not 1 <= len(headers) <= 8:
        raise ValueError("表格 headers 必须包含 1 至 8 个列标题")
    if not isinstance(rows, list) or len(rows) > 100:
        raise ValueError("表格 rows 必须是最多 100 行的数组")
    for row in [headers] + rows:
        if not isinstance(row, list) or len(row) != len(headers):
            raise ValueError("表格每行单元格数量必须与表头一致")
        for cell in row:
            text(cell, "表格单元格", 2000)
    weights = block.get("widths", [1] * len(headers))
    if (not isinstance(weights, list) or len(weights) != len(headers)
            or any(type(x) is not int or not 1 <= x <= 100 for x in weights)):
        raise ValueError("widths 必须与列数相同且每项为 1 至 100 的整数")
    widths = [width * x // sum(weights) for x in weights]
    widths[-1] += width - sum(widths)
    tbl = node(body, "tbl")
    props = node(tbl, "tblPr")
    node(props, "tblW", w=width, type="dxa")
    borders = node(props, "tblBorders")
    for edge in ["top", "left", "bottom", "right", "insideH", "insideV"]:
        node(borders, edge, val="single", sz=4, color="D9D9D9")
    node(props, "tblLayout", type="fixed")
    margins = node(props, "tblCellMar")
    for edge in ["top", "left", "bottom", "right"]:
        node(margins, edge, w=100, type="dxa")
    grid = node(tbl, "tblGrid")
    for col in widths:
        node(grid, "gridCol", w=col)
    for index, row in enumerate([headers] + rows):
        tr = node(tbl, "tr")
        if index == 0:
            node(node(tr, "trPr"), "tblHeader")
        for col, cell in enumerate(row):
            tc = node(tr, "tc")
            prop = node(tc, "tcPr")
            node(prop, "tcW", w=widths[col], type="dxa")
            if index == 0:
                node(prop, "shd", val="clear", fill="E7EEF5")
            node(prop, "vAlign", val="center")
            paragraph(tc, cell, bold=index == 0)
    paragraph(body, "")


def xml(root):
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def build(spec):
    """校验内容并返回完整 DOCX 字节；不读取输入指定的路径或执行表达式。"""
    fields(spec, ["filename", "title", "subtitle", "page_size", "orientation", "blocks"], "输入")
    filename = text(spec.get("filename", "文档.docx"), "filename", 80)
    if (not filename.endswith(".docx") or len(filename) <= 5
            or re.search(r'[<>:"/\\|?*\x00-\x1f]', filename) or filename.startswith(".")
            or filename[:-5].endswith((" ", "."))
            or re.match(r"(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", filename)):
        raise ValueError("filename 必须是安全的单个 .docx 文件名")
    page = spec.get("page_size", "A4")
    orientation = spec.get("orientation", "portrait")
    if page not in ("A4", "Letter") or orientation not in ("portrait", "landscape"):
        raise ValueError("页面仅支持 A4/Letter 和 portrait/landscape")
    title = text(spec.get("title", ""), "title", 200)
    subtitle = text(spec.get("subtitle", ""), "subtitle", 500)
    blocks = spec.get("blocks")
    if not isinstance(blocks, list) or not 1 <= len(blocks) <= 200:
        raise ValueError("blocks 必须包含 1 至 200 个内容块")
    pw, ph = (11906, 16838) if page == "A4" else (12240, 15840)
    if orientation == "landscape":
        pw, ph = ph, pw
    doc = ET.Element("{" + W + "}document")
    body = node(doc, "body")
    numbering = ET.Element("{" + W + "}numbering")
    list_id = 0
    if title:
        paragraph(body, title, "Title")
    if subtitle:
        paragraph(body, subtitle, "Subtitle")
    allowed = {
        "paragraph": ["type", "text"], "heading": ["type", "text", "level"],
        "list": ["type", "items", "ordered"],
        "table": ["type", "headers", "rows", "widths"], "page_break": ["type"]}
    for block in blocks:
        if not isinstance(block, dict) or not isinstance(block.get("type"), str) or block["type"] not in allowed:
            raise ValueError("内容块 type 不受支持")
        kind = block["type"]
        fields(block, allowed[kind], "内容块")
        if kind in ("paragraph", "heading"):
            value = text(block.get("text"), "text")
            level = block.get("level", 1)
            if kind == "heading" and (type(level) is not int or level not in (1, 2, 3)):
                raise ValueError("标题 level 必须是 1、2 或 3")
            paragraph(body, value, "Heading" + str(level) if kind == "heading" else None)
        elif kind == "list":
            items, ordered = block.get("items"), block.get("ordered", False)
            if not isinstance(items, list) or not 1 <= len(items) <= 100 or type(ordered) is not bool:
                raise ValueError("列表 items 需包含 1 至 100 项，ordered 必须是布尔值")
            list_id += 1
            add_numbering(numbering, list_id, ordered)
            for item in items:
                paragraph(body, text(item, "列表项", 2000), number=list_id)
        elif kind == "table":
            table(body, block, pw - 2880)
        else:
            node(node(node(body, "p"), "r"), "br", type="page")
    # numbering 的 OOXML 顺序为全部 abstractNum 在前、全部 num 在后。
    for item in list(numbering):
        if item.tag == "{" + W + "}num":
            numbering.remove(item)
            numbering.append(item)
    section = node(body, "sectPr")
    ref = node(section, "footerReference", type="default")
    ref.set("{" + R + "}id", "rId3")
    node(section, "pgSz", w=pw, h=ph, orient=orientation)
    node(section, "pgMar", top=1440, right=1440, bottom=1440, left=1440,
         header=720, footer=720, gutter=0)
    footer = ET.Element("{" + W + "}ftr")
    p = node(footer, "p")
    node(node(p, "pPr"), "jc", val="center")
    f = node(p, "fldSimple", instr=" PAGE ")
    node(node(f, "r"), "t").text = "1"
    types = ET.Element("{" + CT + "}Types")
    for ext, content_type in [("rels", "application/vnd.openxmlformats-package.relationships+xml"),
                              ("xml", "application/xml")]:
        ET.SubElement(types, "{" + CT + "}Default", Extension=ext, ContentType=content_type)
    for part, suffix in [("document", "document.main"), ("styles", "styles"),
                         ("numbering", "numbering"), ("footer1", "footer")]:
        ET.SubElement(types, "{" + CT + "}Override", PartName="/word/" + part + ".xml",
                      ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml." + suffix + "+xml")
    rootrels = ET.Element("{" + REL + "}Relationships")
    ET.SubElement(rootrels, "{" + REL + "}Relationship", Id="rId1",
                  Type=R + "/officeDocument", Target="word/document.xml")
    docrels = ET.Element("{" + REL + "}Relationships")
    for rid, kind, target in [("rId1", "styles", "styles.xml"),
                              ("rId2", "numbering", "numbering.xml"),
                              ("rId3", "footer", "footer1.xml")]:
        ET.SubElement(docrels, "{" + REL + "}Relationship", Id=rid, Type=R + "/" + kind, Target=target)
    parts = {"[Content_Types].xml": types, "_rels/.rels": rootrels,
             "word/document.xml": doc, "word/styles.xml": styles(),
             "word/numbering.xml": numbering, "word/footer1.xml": footer,
             "word/_rels/document.xml.rels": docrels}
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as package:
        for name, content in parts.items():
            package.writestr(name, xml(content))
    data = stream.getvalue()
    if len(data) > MAX_FILE:
        raise ValueError("生成文件超过 4 MiB 限制")
    return filename, data


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON 不允许重复字段")
        result[key] = value
    return result


def main():
    # Windows 重定向默认编码可能是 GBK；协议始终输出 UTF-8，保持跨平台 JSON 一致。
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        raw = sys.stdin.buffer.read(MAX_INPUT + 1)
        if len(raw) > MAX_INPUT:
            raise ValueError("JSON 输入超过 32768 字节")
        spec = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("JSON 不允许非有限数值")))
        filename, data = build(spec)
        directory = os.environ.get("CM_AGENT_ARTIFACT_DIR")
        if not directory or not Path(directory).is_dir():
            emit_error("DOCX_OUTPUT_NOT_READY", "系统文件产物目录未就绪，请管理员检查文件产物配置")
            return 2
        # 目录仅取可信引导环境，模型只能选择经过校验的文件名；拒绝覆盖已有文件。
        destination = Path(directory) / filename
        created = False
        try:
            with destination.open("xb") as output:
                created = True
                output.write(data)
        except FileExistsError:
            emit_error("DOCX_FILE_EXISTS", "同名文件已存在，请选择其他文件名")
            return 2
        except OSError:
            # 写入中断时移除本次部分文件，避免收集器将残缺 DOCX 当成产物。
            if created:
                destination.unlink(missing_ok=True)
            raise
        print(json.dumps({"status": "created", "filename": filename, "bytes": len(data),
                          "sha256": hashlib.sha256(data).hexdigest()}, ensure_ascii=False))
        return 0
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        emit_error("DOCX_INPUT_INVALID", "请输入有效的 UTF-8 JSON 对象")
    except ValueError as error:
        emit_error("DOCX_INPUT_INVALID", str(error))
    except OSError:
        emit_error("DOCX_WRITE_FAILED", "文档写入失败，请检查产物目录和可用空间")
    except Exception:
        # 未知异常不向模型披露路径、输入正文和堆栈；关联编号供执行记录定位。
        emit_error("DOCX_INTERNAL_ERROR", "文档生成失败，请使用错误编号联系维护人员")
    return 2


def emit_error(code, message):
    print(json.dumps({"status": "error", "errorCode": code,
                      "errorId": "docx-" + uuid.uuid4().hex, "message": message}, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
