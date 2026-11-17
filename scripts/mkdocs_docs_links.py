"""MkDocs 构建钩子：把无法由站点承载的相对链接改写为 GitHub 地址。

文档目录保持原样，源码链接仍指回仓库；目录链接在没有索引页时也指向
GitHub 目录列表。该转换只发生在构建过程中，避免为了发布站点而复制
代码、脚本或根目录文档。
"""

from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import quote, unquote


_REPO_URL = "https://github.com/cmming/cm-agent"
_BRANCH = "master"
_LINK_PATTERN = re.compile(
    r'(?<!!)\[(?P<label>[^\]]*)\]'
    r'\((?P<target>[^)\s]+)(?P<title>\s+"[^"]*")?\)'
)
_FENCE_PATTERN = re.compile(r"^ {0,3}(?P<marker>`{3,}|~{3,})")
_INDEX_FILES = ("README.md", "index.md")


def _github_url(
    target: str,
    source_path: Path,
    docs_root: Path,
    repo_root: Path,
) -> str | None:
    """返回应由 GitHub 承载的目标地址；站点可承载的目标返回 None。"""
    if target.startswith(("#", "/", "http://", "https://", "mailto:", "tel:", "data:")):
        return None

    raw_path, has_fragment, fragment = target.partition("#")
    if not raw_path:
        return None

    resolved = (source_path.parent / unquote(raw_path)).resolve()

    if not resolved.is_relative_to(repo_root):
        return None
    if resolved.is_relative_to(docs_root) and not (
        resolved.is_dir()
        and not any((resolved / index_file).is_file() for index_file in _INDEX_FILES)
    ):
        return None

    relative_path = resolved.relative_to(repo_root).as_posix()
    link_kind = "tree" if resolved.is_dir() else "blob"
    url = f"{_REPO_URL}/{link_kind}/{_BRANCH}/{quote(relative_path, safe='/')}"
    if has_fragment:
        url = f"{url}#{fragment}"
    return url


def _rewrite_line(
    line: str,
    source_path: Path,
    docs_root: Path,
    repo_root: Path,
) -> str:
    """仅改写当前行中的 Markdown 相对链接，保留标签和可选标题。"""

    def replace(match: re.Match[str]) -> str:
        target = match.group("target")
        replacement = _github_url(target, source_path, docs_root, repo_root)
        if replacement is None:
            return match.group(0)
        title = match.group("title") or ""
        return f"[{match.group('label')}]({replacement}{title})"

    return _LINK_PATTERN.sub(replace, line)


def on_page_markdown(
    markdown: str,
    page: object,
    config: object,
    files: object,
) -> str:
    """在 Markdown 渲染前处理站外链接，跳过围栏代码块。"""
    del files
    source_path = Path(page.file.abs_src_path).resolve()
    docs_root = Path(config.docs_dir).resolve()
    repo_root = Path(config.config_file_path).resolve().parent

    lines = markdown.splitlines(keepends=True)
    fence_character: str | None = None
    fence_length = 0

    for index, line in enumerate(lines):
        fence = _FENCE_PATTERN.match(line)
        if fence_character is None:
            if fence is not None:
                marker = fence.group("marker")
                fence_character = marker[0]
                fence_length = len(marker)
                continue
            lines[index] = _rewrite_line(line, source_path, docs_root, repo_root)
            continue

        if fence is None:
            continue
        marker = fence.group("marker")
        if marker[0] == fence_character and len(marker) >= fence_length:
            fence_character = None
            fence_length = 0

    return "".join(lines)
