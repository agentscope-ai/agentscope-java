#!/usr/bin/env python3
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
"""Convert Mintlify-flavoured .mdx in docs/v2 to MyST-compatible .md.

Mintlify components are rewritten to sphinx-design / MyST equivalents:
  <Note>/<Tip>/<Warning>           -> :::{note}/:::{tip}/:::{warning}
  <CardGroup>/<Card>               -> ::::{grid}/:::{grid-item-card}
  <Accordion>/<AccordionGroup>     -> :::{dropdown}
  <Steps>/<Step>                   -> ### Title sub-headings
  <Tree>/<Tree.Folder>/<Tree.File> -> ASCII tree in a ```text fenced block
  <CodeGroup>                      -> ::::{tab-set}/:::{tab-item}
  <Badge>                          -> **inline text**
  <ParamField>                     -> bold definition paragraph
  <Expandable>                     -> :::{dropdown} Details

Absolute Mintlify hrefs ``/v2/<rest>`` are rewritten to
``/v2/<lang>/docs/<rest>.html`` based on the source file's language.

Run from the repo root:
    python docs/scripts/convert_v2_mdx.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

V2_ROOT = Path(__file__).resolve().parent.parent / "v2"


# ---------------------------------------------------------------------------
# Generic JSX-ish element parser
# ---------------------------------------------------------------------------

# Tag-name boundary: the next char after a JSX tag name must be whitespace,
# ``/`` (self-close) or ``>``. Plain ``\b`` would treat ``<Tree.Folder>`` as a
# match for ``<Tree`` because ``.`` is a non-word character; this stricter
# lookahead avoids that.
def _tag_open_re(tag: str) -> re.Pattern:
    return re.compile(rf"<{re.escape(tag)}(?=[\s/>])")


def _tag_close_re(tag: str) -> re.Pattern:
    return re.compile(rf"</{re.escape(tag)}>")


# Attribute values may contain literal ``>`` (Java generics like
# ``type="List<ToolUseBlock>"``). Allow quoted strings to consume ``>``.
_ATTR_CONSUME = r"""(?:"[^"]*"|'[^']*'|\{[^}]*\}|[^>"'])"""


def _open_tag_re(tag: str) -> re.Pattern:
    return re.compile(
        rf"<{re.escape(tag)}(?=[\s/>])((?:{_ATTR_CONSUME})*?)(/?)>",
        re.DOTALL,
    )


def _find_balanced(text: str, start: int, tag: str):
    """Given ``text[start]`` is the ``<`` of ``<tag ...>``, return
    ``(open_end, close_start, close_end)`` for the matching ``</tag>``.

    Handles nesting of the same tag and self-closing ``<tag .../>``. Returns
    ``None`` if the open tag does not match or no closing tag is found.
    """
    open_tag_re = _open_tag_re(tag)
    m = open_tag_re.match(text, start)
    if not m:
        return None
    open_end = m.end()
    if m.group(2) == "/":
        # Self-closing; no body.
        return (open_end, open_end - 1, open_end)
    open_pat = _tag_open_re(tag)
    close_pat = _tag_close_re(tag)
    depth = 1
    pos = open_end
    while True:
        no = open_pat.search(text, pos)
        nc = close_pat.search(text, pos)
        if not nc:
            return None
        if no and no.start() < nc.start():
            mm = open_tag_re.match(text, no.start())
            if mm and mm.group(2) != "/":
                depth += 1
            pos = mm.end() if mm else no.end()
        else:
            depth -= 1
            if depth == 0:
                return (open_end, nc.start(), nc.end())
            pos = nc.end()


def _convert_tag_once(text: str, tag: str, replace_fn) -> str:
    """One pass over ``text`` replacing every ``<tag ...>...</tag>``.

    ``attrs_chunk`` passed to ``replace_fn`` is the substring between
    ``<tag`` and ``>``.
    """
    out_parts: list[str] = []
    open_tag_re = _open_tag_re(tag)
    i = 0
    while True:
        m = open_tag_re.search(text, i)
        if not m:
            out_parts.append(text[i:])
            return "".join(out_parts)
        out_parts.append(text[i:m.start()])
        balanced = _find_balanced(text, m.start(), tag)
        if not balanced:
            out_parts.append(text[m.start():m.end()])
            i = m.end()
            continue
        open_end, close_start, close_end = balanced
        attrs_chunk = m.group(1)
        body = text[open_end:close_start] if close_start >= open_end else ""
        out_parts.append(replace_fn(attrs_chunk, body))
        i = close_end


def _convert_tag(text: str, tag: str, replace_fn) -> str:
    """Repeatedly apply ``_convert_tag_once`` until no change, so that
    self-nesting tags (e.g. ``<ParamField>`` inside ``<ParamField>``) all
    get converted."""
    prev = None
    while prev != text:
        prev = text
        text = _convert_tag_once(text, tag, replace_fn)
    return text


_ATTR_RE = re.compile(r'(\w+)\s*=\s*(?:"([^"]*)"|\{([^}]*)\})')


def _attrs(chunk: str) -> dict:
    out: dict = {}
    for name, qval, bval in _ATTR_RE.findall(chunk):
        out[name] = qval if qval else bval
    return out


def _has_flag(chunk: str, flag: str) -> bool:
    # Bare attribute flag like `required` (no `=`).
    return bool(re.search(rf"(?<![A-Za-z0-9_]){flag}(?![A-Za-z0-9_=])", chunk))


# ---------------------------------------------------------------------------
# Individual component converters
# ---------------------------------------------------------------------------

def _admonition(kind: str):
    def fn(attrs_chunk: str, body: str) -> str:
        return f":::{{{kind}}}\n{body.strip()}\n:::"
    return fn


def _badge_fn(attrs_chunk: str, body: str) -> str:
    text = body.strip()
    return f"**{text}**" if text else ""


def _card_fn(attrs_chunk: str, body: str) -> str:
    a = _attrs(attrs_chunk)
    title = a.get("title", "").strip()
    href = a.get("href", "").strip()
    parts = [f":::{{grid-item-card}} {title}".rstrip()]
    if href:
        parts.append(f":link: {href}")
    parts.append("")
    parts.append(body.strip())
    parts.append(":::")
    return "\n".join(parts)


def _cardgroup_fn(attrs_chunk: str, body: str) -> str:
    a = _attrs(attrs_chunk)
    cols = a.get("cols", "2").strip() or "2"
    inner = body.strip()
    return f"::::{{grid}} {cols}\n\n{inner}\n\n::::"


def _accordion_fn(attrs_chunk: str, body: str) -> str:
    a = _attrs(attrs_chunk)
    title = a.get("title", "").strip()
    return f":::{{dropdown}} {title}\n{body.strip()}\n:::"


def _accordiongroup_fn(attrs_chunk: str, body: str) -> str:
    # Just unwrap; rely on each Accordion having become its own dropdown.
    return body.strip()


def _expandable_fn(attrs_chunk: str, body: str) -> str:
    a = _attrs(attrs_chunk)
    title = a.get("title", "Details").strip() or "Details"
    return f":::{{dropdown}} {title}\n{body.strip()}\n:::"


def _paramfield_fn(attrs_chunk: str, body: str) -> str:
    a = _attrs(attrs_chunk)
    required = _has_flag(attrs_chunk, "required")
    path = a.get("path", "").strip()
    typ = a.get("type", "").strip()
    head_bits: list[str] = []
    if path:
        head_bits.append(f"`{path}`")
    if typ:
        head_bits.append(f"`{typ}`")
    if required:
        head_bits.append("*required*")
    head = " · ".join(head_bits)
    body_s = body.strip()
    if body_s:
        # Indent body for readability under the bold head.
        return f"- **{head}** — {body_s}"
    return f"- **{head}**"


def _step_fn(attrs_chunk: str, body: str) -> str:
    a = _attrs(attrs_chunk)
    title = a.get("title", "").strip()
    body_s = body.strip()
    if title:
        return f"### {title}\n\n{body_s}\n"
    return f"{body_s}\n"


def _steps_fn(attrs_chunk: str, body: str) -> str:
    # Just unwrap.
    return body.strip()


_CODE_BLOCK_RE = re.compile(
    r"```([A-Za-z0-9_+-]+)([ \t]+([^\n]+))?\n(.*?)```",
    re.DOTALL,
)


def _codegroup_fn(attrs_chunk: str, body: str) -> str:
    blocks = []
    for m in _CODE_BLOCK_RE.finditer(body):
        lang = m.group(1)
        title = (m.group(3) or "").strip() or lang
        code = m.group(4)
        blocks.append((lang, title, code))
    if not blocks:
        return body
    parts = ["::::{tab-set}"]
    for lang, title, code in blocks:
        parts.append(f":::{{tab-item}} {title}")
        parts.append(f"```{lang}")
        parts.append(code.rstrip("\n"))
        parts.append("```")
        parts.append(":::")
    parts.append("::::")
    return "\n".join(parts)


# Tree.* handling -------------------------------------------------------------

_TREE_TAG_RE = re.compile(r"<Tree\.(Folder|File)\b([^>]*?)(/?)>", re.DOTALL)


def _parse_tree(text: str, pos: int = 0):
    """Return ``(nodes, end_pos)`` parsing siblings from ``text[pos:]`` until
    we hit ``</Tree.Folder>`` or ``</Tree>``.
    """
    nodes: list = []
    while pos < len(text):
        # Skip whitespace / commas / etc.
        ws = re.match(r"\s+", text[pos:])
        if ws:
            pos += ws.end()
            continue
        # Stop on closing tag.
        if text[pos:pos + len("</Tree.Folder>")] == "</Tree.Folder>":
            return nodes, pos
        if text[pos:pos + len("</Tree>")] == "</Tree>":
            return nodes, pos
        m = _TREE_TAG_RE.match(text, pos)
        if not m:
            # Unknown content inside <Tree>; bail.
            return nodes, pos
        kind = m.group(1).lower()
        a = _attrs(m.group(2))
        name = a.get("name", "").strip()
        self_close = m.group(3) == "/"
        node = {"type": kind, "name": name, "children": []}
        pos = m.end()
        if not self_close:
            close_tag = f"</Tree.{m.group(1)}>"
            if kind == "folder":
                children, end = _parse_tree(text, pos)
                node["children"] = children
                pos = end
            # Skip explicit close (for <Tree.File>foo</Tree.File> or folder).
            idx = text.find(close_tag, pos)
            if idx >= 0:
                pos = idx + len(close_tag)
        nodes.append(node)
    return nodes, pos


def _render_tree(nodes, prefix: str = "") -> list[str]:
    lines: list[str] = []
    for idx, node in enumerate(nodes):
        is_last = idx == len(nodes) - 1
        connector = "└── " if is_last else "├── "
        suffix = "/" if node["type"] == "folder" else ""
        lines.append(prefix + connector + node["name"] + suffix)
        if node["children"]:
            child_prefix = prefix + ("    " if is_last else "│   ")
            lines.extend(_render_tree(node["children"], child_prefix))
    return lines


def _tree_fn(attrs_chunk: str, body: str) -> str:
    nodes, _ = _parse_tree(body, 0)
    if not nodes:
        return f"```text\n{body.strip()}\n```"
    lines = _render_tree(nodes)
    return "```text\n" + "\n".join(lines) + "\n```"


# ---------------------------------------------------------------------------
# Whole-file passes
# ---------------------------------------------------------------------------

def _rewrite_hrefs(text: str, lang: str) -> str:
    """Translate Mintlify-style absolute paths to Sphinx-built URLs.

    ``/v2/<rest>`` -> ``/v2/<lang>/docs/<rest>.html``. Only acts on hrefs that
    do not already point at .html (avoids double-suffixing).
    """
    def sub(m: re.Match) -> str:
        prefix = m.group(1)
        rest = m.group(2)
        # Strip trailing slash for cleanliness.
        rest = rest.rstrip("/")
        if rest.endswith(".html"):
            target = rest
        else:
            target = f"{rest}.html"
        return f'{prefix}"/v2/{lang}/docs/{target}"'

    return re.sub(
        r'(href\s*=\s*|:link:\s*)"/v2/([^"]*)"',
        sub,
        text,
    )


def convert(text: str, lang: str) -> str:
    # Inline first.
    text = _convert_tag(text, "Badge", _badge_fn)
    # Innermost block elements.
    text = _convert_tag(text, "Expandable", _expandable_fn)
    text = _convert_tag(text, "ParamField", _paramfield_fn)
    # Single-purpose containers.
    text = _convert_tag(text, "Tree", _tree_fn)
    text = _convert_tag(text, "CodeGroup", _codegroup_fn)
    # Step/Card/Accordion → wrappers.
    text = _convert_tag(text, "Step", _step_fn)
    text = _convert_tag(text, "Steps", _steps_fn)
    text = _convert_tag(text, "Accordion", _accordion_fn)
    text = _convert_tag(text, "AccordionGroup", _accordiongroup_fn)
    text = _convert_tag(text, "Card", _card_fn)
    text = _convert_tag(text, "CardGroup", _cardgroup_fn)
    # Admonitions.
    text = _convert_tag(text, "Note", _admonition("note"))
    text = _convert_tag(text, "Tip", _admonition("tip"))
    text = _convert_tag(text, "Warning", _admonition("warning"))

    text = _rewrite_hrefs(text, lang)
    return text


def main() -> int:
    if not V2_ROOT.is_dir():
        print(f"v2 root not found: {V2_ROOT}", file=sys.stderr)
        return 1

    converted = 0
    for mdx in sorted(V2_ROOT.rglob("*.mdx")):
        rel = mdx.relative_to(V2_ROOT)
        lang = rel.parts[0] if rel.parts else "en"
        src = mdx.read_text(encoding="utf-8")
        dst = convert(src, lang)
        target = mdx.with_suffix(".md")
        target.write_text(dst, encoding="utf-8")
        mdx.unlink()
        print(f"converted {rel} -> {target.relative_to(V2_ROOT)}")
        converted += 1
    print(f"done: {converted} files converted")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
