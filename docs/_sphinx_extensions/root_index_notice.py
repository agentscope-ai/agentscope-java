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

It also writes a small index stub at each version directory declared in
``docs_versions`` (e.g. ``v1/index.html``, ``v2/index.html``), so URLs like
``/v2/`` resolve to the correct ``<slug>/<lang>/intro.html`` instead of 404ing on
static hosts (GitHub Pages has no try_files / rewrite). The stub respects the
``preferred-language`` localStorage key set by ``_static/language.js``.
"""

from __future__ import annotations

import html
import json
import re
from pathlib import Path
from urllib.parse import urlparse


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


_VERSION_SLUG_RE = re.compile(r"^[A-Za-z0-9._-]+$")


def _version_slug(url_value: str) -> str | None:
    """Extract the last path segment from a docs_versions URL.

    Accepts both ``https://java.agentscope.io/v2/`` and plain ``v2`` / ``/v2``.
    Returns ``None`` when the value does not look like a single, safe slug
    (avoids writing to arbitrary paths if someone configures a weird URL).
    """
    if not url_value:
        return None
    raw = url_value.strip()
    if "://" in raw:
        path = urlparse(raw).path or ""
    else:
        path = raw
    parts = [seg for seg in path.split("/") if seg]
    if not parts:
        return None
    slug = parts[-1]
    if not _VERSION_SLUG_RE.match(slug):
        return None
    return slug


def _version_stub_html(slug: str, languages: list[str]) -> str:
    """Generate the redirect stub for ``<outdir>/<slug>/index.html``.

    The page picks a language target by, in order:
      1. ``localStorage["preferred-language"]`` (set by _static/language.js)
      2. ``navigator.language`` prefix (``zh*`` → zh)
      3. fallback to the first language in ``languages``
    The fallback language is also used as the ``<meta http-equiv="refresh">``
    target so the page still works without JS.
    """
    if not languages:
        languages = ["en"]
    fallback = languages[0]
    fallback_target = f"{fallback}/intro.html"
    langs_json = json.dumps(languages)
    title = html.escape(f"AgentScope Java {slug}")
    return f"""<!DOCTYPE html>
<html lang="{html.escape(fallback, quote=True)}">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{title}</title>
  <meta name="robots" content="noindex" />
  <meta http-equiv="refresh" content="0; url={html.escape(fallback_target, quote=True)}" />
  <script>
    (function () {{
      try {{
        var languages = {langs_json};
        var preferred = null;
        try {{ preferred = localStorage.getItem("preferred-language"); }} catch (e) {{}}
        if (!preferred || languages.indexOf(preferred) === -1) {{
          var nav = (navigator.language || navigator.userLanguage || "").toLowerCase();
          if (nav.indexOf("zh") === 0 && languages.indexOf("zh") !== -1) {{
            preferred = "zh";
          }}
        }}
        if (!preferred || languages.indexOf(preferred) === -1) {{
          preferred = languages[0];
        }}
        location.replace(preferred + "/intro.html");
      }} catch (e) {{}}
    }})();
  </script>
</head>
<body>
  <p><a href="{html.escape(fallback_target, quote=True)}">Continue to documentation</a></p>
</body>
</html>
"""


def _patch_version_indexes(app, _exception) -> None:
    if _exception is not None:
        return
    if app.builder.format != "html":
        return
    ctx = getattr(app.config, "html_context", None) or {}
    if not isinstance(ctx, dict):
        return
    versions = ctx.get("docs_versions") or []
    if not isinstance(versions, list):
        return

    languages_default = ["en", "zh"]
    outdir = Path(app.outdir)
    for entry in versions:
        if not isinstance(entry, dict):
            continue
        slug = _version_slug(str(entry.get("url") or ""))
        if not slug:
            continue
        version_dir = outdir / slug
        if not version_dir.is_dir():
            continue
        target = version_dir / "index.html"
        # Only write the stub when the version dir has no real index yet.
        # Sphinx never creates one for a sub-folder; if a future build does, we
        # do not want to clobber it.
        if target.exists():
            continue
        langs = [l for l in languages_default if (version_dir / l).is_dir()]
        if not langs:
            continue
        target.write_text(_version_stub_html(slug, langs), encoding="utf-8")


def setup(app):
    app.connect("build-finished", _patch_root_index)
    app.connect("build-finished", _patch_version_indexes)
    return {
        "version": "1.1.0",
        "parallel_read_safe": True,
        "parallel_write_safe": True,
    }
