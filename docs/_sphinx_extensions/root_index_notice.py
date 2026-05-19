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

"""Replace minimal Jupyter Book root index.html with a page that shows docs_site_notice.

The default root ``index.html`` is only a meta-refresh stub and never runs ``page.html``,
so the notice bar does not appear when users open ``/`` or ``index.html``. This hook
rebuilds that file when it looks like a redirect stub, using the same ``html_context``
``docs_site_notice`` data as the Jinja template.
"""

from __future__ import annotations

import html
import json
import re
from pathlib import Path


def _notice_enabled(dn: dict) -> bool:
    if not dn:
        return False
    flag = dn.get("enabled")
    if flag is True:
        return True
    if isinstance(flag, str) and flag.lower() in ("true", "1", "yes", "on"):
        return True
    return False


def _notice_html(dn: dict, redirect_target: str) -> str:
    """Return inner HTML (body content) for the notice, or empty string."""
    if not _notice_enabled(dn):
        return ""
    nid = str(dn.get("id") or "").strip()
    if not nid:
        return ""
    msg = str(dn.get("message_en") or "").strip() or str(dn.get("message_zh") or "").strip()
    if not msg:
        return ""
    variant = dn.get("variant") or "brand"
    if variant not in ("brand", "neutral", "accent"):
        variant = "brand"
    href = str(dn.get("link_en") or "").strip()
    link_text = str(dn.get("link_text_en") or "").strip()
    link_html = ""
    if href and link_text:
        if href.startswith("http://") or href.startswith("https://"):
            link_html = (
                f'<a class="docs-site-notice__link" href="{html.escape(href, quote=True)}" '
                f'rel="noopener noreferrer" target="_blank">{html.escape(link_text)}</a>'
            )
        else:
            safe = html.escape(href.lstrip("/"), quote=True)
            link_html = f'<a class="docs-site-notice__link" href="{safe}.html">{html.escape(link_text)}</a>'

    esc_id = html.escape(nid, quote=True)
    esc_msg = html.escape(msg)
    id_json = json.dumps(nid)
    return f"""    <script>
      (function () {{
        try {{
          var id = {id_json};
          if (localStorage.getItem("agentscope-docs-site-notice") === id) {{
            document.documentElement.classList.add("docs-site-notice-dismissed");
          }}
        }} catch (e) {{}}
      }})();
    </script>
    <div class="docs-site-notice docs-site-notice--{variant}" data-notice-id="{esc_id}" role="region" aria-label="Site notice">
      <div class="docs-site-notice__inner">
        <p class="docs-site-notice__text">{esc_msg}</p>
        {link_html}
        <button type="button" class="docs-site-notice__close" aria-label="Dismiss" data-notice-dismiss>
          <span aria-hidden="true">&times;</span>
        </button>
      </div>
    </div>
    <p class="docs-root-redirect-hint"><a href="{html.escape(redirect_target, quote=True)}">Continue to documentation</a></p>
"""


def _is_redirect_stub(text: str) -> bool:
    t = text.strip().lower()
    if not t:
        return False
    if "<html" in t or "<body" in t:
        return False
    if "docs-site-notice" in t:
        return False
    return "refresh" in t and "url=" in t


def _redirect_target(text: str) -> str | None:
    m = re.search(r"url\s*=\s*([^\"'>\s]+)", text, re.I)
    if not m:
        return None
    return m.group(1).strip()


def _redirect_target_from_patched(text: str) -> str | None:
    m = re.search(r"location\.replace\(\s*\"([^\"]+)\"\s*\)", text)
    if m:
        return m.group(1).strip()
    m = re.search(r'location\.replace\(\s*("|\')([^"\']+)(\1)\s*\)', text)
    if m:
        return m.group(2).strip()
    return None


def _minimal_redirect_stub(target: str) -> str:
    return f'<meta http-equiv="Refresh" content="0; url={target}" />\n'


def _patch_root_index(app, _exception) -> None:
    if _exception is not None:
        return
    if app.builder.format != "html":
        return
    outdir = Path(app.outdir)
    index = outdir / "index.html"
    if not index.is_file():
        return
    raw = index.read_text(encoding="utf-8")
    target = _redirect_target(raw) or _redirect_target_from_patched(raw) or "en/intro.html"

    ctx = getattr(app.config, "html_context", None) or {}
    dn = ctx.get("docs_site_notice") if isinstance(ctx, dict) else None
    if not isinstance(dn, dict):
        dn = {}

    notice_block = _notice_html(dn, target)
    if not notice_block.strip():
        if "docs-site-notice" in raw or "docs-root-redirect-hint" in raw:
            index.write_text(_minimal_redirect_stub(target), encoding="utf-8")
        return

    if not (_is_redirect_stub(raw) or ("docs-site-notice" in raw and "docs-root-redirect-hint" in raw)):
        return

    title = html.escape(str(getattr(app.config, "project", "Documentation")))
    target_json = json.dumps(target)
    body = f"""{notice_block}
    <script src="_static/notice-bar.js"></script>
    <script>
      setTimeout(function () {{ location.replace({target_json}); }}, 80);
    </script>
"""

    replacement = f"""<!DOCTYPE html>
<html class="no-js" lang="en" data-content_root="./">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{title}</title>
  <link rel="stylesheet" href="_static/custom.css" />
  <link rel="shortcut icon" href="_static/logo.svg" />
</head>
<body>
{body}
</body>
</html>
"""
    index.write_text(replacement, encoding="utf-8")


def setup(app):
    app.connect("build-finished", _patch_root_index)
    return {
        "version": "1.0.0",
        "parallel_read_safe": True,
        "parallel_write_safe": True,
    }
