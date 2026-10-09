# JSON 输入契约

UTF-8 JSON 对象通过标准输入传入。Python 3.10+，无需 pip、python-docx、lxml、Node.js 或 Office。仅执行固定 `scripts/main.py`，不接受 Python 代码、模板路径、任意输出目录或网络链接。

| 字段 | 约束 |
|---|---|
| filename | 可选；默认 `文档.docx`，最多 80 字符，单个安全文件名，禁止路径、保留设备名及覆盖 |
| title | 可选，最多 200 字符 |
| subtitle | 可选，最多 500 字符 |
| page_size | 可选，`A4`（默认）或 `Letter` |
| orientation | 可选，`portrait`（默认）或 `landscape` |
| blocks | 必填，1～200 个内容块 |

内容块：

- `{"type":"paragraph","text":"正文"}`：最多 8000 字符；换行和制表符保留。
- `{"type":"heading","level":1,"text":"标题"}`：level 可省略，取 1；允许 1～3。
- `{"type":"list","ordered":false,"items":["项目一","项目二"]}`：1～100 项，每项最多 2000 字符；ordered 可省略。
- `{"type":"table","headers":["项","结果"],"rows":[["甲","通过"]],"widths":[1,3]}`：1～8 列，最多 100 个正文行，每格最多 2000 字符，行列数一致。widths 可省略；指定时每项为 1～100 的整数比例，总宽度按可用页面宽度分配。
- `{"type":"page_break"}`：显式换页。

未知字段、重复 JSON 键、不支持类型、XML 非法字符、NaN/Infinity 及超过 32768 字节的输入均拒绝。内容块按顺序输出，表格后留正常段落；每个编号列表从 1 开始。默认无作者/时间元数据，避免擅自填入个人信息。

成功：`{"status":"created","filename":"文档.docx","bytes":1234,"sha256":"..."}`。失败：`status=error`、中文 `message`、`errorCode`、`errorId`，进程退出码 2。输出不含文档字节或内部目录。系统执行错误由 CM Agent 提供其自身错误编号。

生成器拼装标准 OOXML ZIP，参考 [Microsoft WordprocessingML 文档结构](https://learn.microsoft.com/en-us/office/open-xml/word/structure-of-a-wordprocessingml-document)。标准库负责结构创建与检查，不能渲染页面；复杂分页及字体效果应另用 Word/WPS/LibreOffice 检查。
