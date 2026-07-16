# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Generate version-scoped llms.txt files for AgentScope Java."""

from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urljoin


VERSIONS = ("v1", "v2")
LANGUAGE = "en"
EXCLUDED_SEGMENTS = ("/blogs/", "/community/")


def _normalize_docname(value: str) -> str:
    docname = value.replace("\\", "/").strip()
    if docname.endswith(".md"):
        docname = docname[:-3]
    return docname.strip("/")


def _unquote_yaml_scalar(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def _load_ordered_docs(srcdir: Path, version: str) -> list[tuple[str, str]]:
    toc_path = srcdir / "_toc.yml"
    prefix = f"{version}/{LANGUAGE}/"
    docs: list[tuple[str, str]] = []
    seen: set[str] = set()
    active_index: int | None = None

    for line in toc_path.read_text(encoding="utf-8").splitlines():
        file_match = re.match(r"^\s*-\s*file:\s*(.+?)\s*$", line)
        if file_match:
            docname = _normalize_docname(_unquote_yaml_scalar(file_match.group(1)))
            should_keep = (
                docname.startswith(prefix)
                and not any(segment in f"/{docname}/" for segment in EXCLUDED_SEGMENTS)
                and docname not in seen
            )
            if should_keep:
                seen.add(docname)
                docs.append((docname, ""))
                active_index = len(docs) - 1
            else:
                active_index = None
            continue

        title_match = re.match(r"^\s*title:\s*(.+?)\s*$", line)
        if title_match and active_index is not None and not docs[active_index][1]:
            docname, _old_title = docs[active_index]
            docs[active_index] = (docname, _unquote_yaml_scalar(title_match.group(1)))

    docnames = {docname for docname, _ in docs}
    filtered: list[tuple[str, str]] = []
    for docname, toc_title in docs:
        parent = docname.rsplit("/", 1)[0] if "/" in docname else ""
        sibling_overview = f"{parent}/overview"
        is_duplicate_index = (
            docname.endswith("/index")
            and docname != f"{version}/{LANGUAGE}/docs/index"
            and sibling_overview in docnames
        )
        if not is_duplicate_index:
            filtered.append((docname, toc_title))
    return filtered


def _source_path(srcdir: Path, docname: str) -> Path:
    return srcdir / f"{docname}.md"


def _extract_frontmatter(text: str) -> tuple[dict[str, str], str]:
    if not text.startswith("---\n"):
        return {}, text
    end = text.find("\n---", 4)
    if end == -1:
        return {}, text
    raw = text[4:end].strip()
    body = text[end + len("\n---") :].lstrip()
    metadata: dict[str, str] = {}
    for line in raw.splitlines():
        match = re.match(r"^\s*([A-Za-z0-9_-]+):\s*(.*?)\s*$", line)
        if not match:
            continue
        metadata[match.group(1)] = _unquote_yaml_scalar(match.group(2))
    return metadata, body


def _first_markdown_heading(text: str) -> str:
    match = re.search(r"^#\s+(.+?)\s*$", text, flags=re.MULTILINE)
    if not match:
        return ""
    return re.sub(r"\s+#*$", "", match.group(1)).strip()


def _page_metadata(srcdir: Path, docname: str, toc_title: str) -> tuple[str, str]:
    source = _source_path(srcdir, docname)
    if not source.is_file():
        return toc_title or docname.rsplit("/", 1)[-1], ""
    raw = source.read_text(encoding="utf-8")
    frontmatter, body = _extract_frontmatter(raw)
    title = frontmatter.get("title") or _first_markdown_heading(body) or toc_title
    description = frontmatter.get("description", "")
    return title.strip() or docname.rsplit("/", 1)[-1], description.strip()


def _strip_raw_html_blocks(text: str) -> str:
    def replace(match: re.Match[str]) -> str:
        html = match.group(1)
        html = re.sub(r"<script\b.*?</script>", "", html, flags=re.IGNORECASE | re.DOTALL)
        html = re.sub(r"<style\b.*?</style>", "", html, flags=re.IGNORECASE | re.DOTALL)
        html = re.sub(r"<[^>]+>", " ", html)
        return re.sub(r"\s+", " ", html).strip()

    return re.sub(r"```\{raw\}\s+html\s*\n(.*?)\n```", replace, text, flags=re.DOTALL)


def _clean_body(title: str, body: str) -> str:
    body = _strip_raw_html_blocks(body).strip()
    if not body:
        return f"# {title}\n"
    if re.search(r"^#\s+", body, flags=re.MULTILINE):
        return body + "\n"
    return f"# {title}\n\n{body}\n"


def _source_url(base_url: str, docname: str) -> str:
    return urljoin(base_url, f"_sources/{docname}.md")


def _version_intro(version: str) -> str:
    if version == "v2":
        return (
            "AgentScope Java 2.0 documentation for new projects and production "
            "agent engineering with HarnessAgent."
        )
    return (
        "AgentScope Java 1.x documentation for existing applications and "
        "compatibility-oriented maintenance."
    )


def _write_llms_index(outdir: Path, base_url: str, version: str, pages: list[tuple[str, str, str]]) -> None:
    target = outdir / version / "llms.txt"
    target.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# AgentScope Java {version}",
        "",
        f"> {_version_intro(version)}",
        "",
        "## Docs",
        "",
    ]
    for docname, title, description in pages:
        suffix = f": {description}" if description else ""
        lines.append(f"- [{title}]({_source_url(base_url, docname)}){suffix}")
    target.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def _write_llms_full(
    srcdir: Path,
    outdir: Path,
    base_url: str,
    version: str,
    pages: list[tuple[str, str, str]],
) -> None:
    target = outdir / version / "llms-full.txt"
    target.parent.mkdir(parents=True, exist_ok=True)
    sections = [
        f"# AgentScope Java {version} Documentation",
        "",
        _version_intro(version),
    ]
    for docname, title, _description in pages:
        source = _source_path(srcdir, docname)
        if not source.is_file():
            continue
        raw = source.read_text(encoding="utf-8")
        _frontmatter, body = _extract_frontmatter(raw)
        sections.extend(
            [
                "",
                "---",
                "",
                f"Source: {_source_url(base_url, docname)}",
                "",
                _clean_body(title, body).strip(),
            ]
        )
    target.write_text("\n".join(sections).rstrip() + "\n", encoding="utf-8")


def _remove_root_llms(outdir: Path) -> None:
    for filename in ("llms.txt", "llms-full.txt"):
        target = outdir / filename
        if target.exists():
            target.unlink()


def _generate_versioned_llms(app, exception) -> None:
    if exception is not None:
        return
    if app.builder.format != "html":
        return

    srcdir = Path(app.srcdir)
    outdir = Path(app.outdir)
    base_url = str(getattr(app.config, "html_baseurl", "") or "").rstrip("/") + "/"

    for version in VERSIONS:
        docs = _load_ordered_docs(srcdir, version)
        pages: list[tuple[str, str, str]] = []
        for docname, toc_title in docs:
            title, description = _page_metadata(srcdir, docname, toc_title)
            pages.append((docname, title, description))
        _write_llms_index(outdir, base_url, version, pages)
        _write_llms_full(srcdir, outdir, base_url, version, pages)

    _remove_root_llms(outdir)


def setup(app):
    app.connect("build-finished", _generate_versioned_llms)
    return {
        "version": "1.0.0",
        "parallel_read_safe": True,
        "parallel_write_safe": True,
    }
