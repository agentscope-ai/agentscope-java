#!/usr/bin/env python3
"""Generate HTML meta-refresh stubs for the 1.x -> 2.0 v1 docs path migration.

In the 1.x branch the v1 docs were published at:
    https://java.agentscope.io/<lang>/<section>/<page>.html

After the v2 migration on the v2_dev branch the same content lives at:
    https://java.agentscope.io/v1/<lang>/[docs/]<section>/<page>.html

GitHub Pages serves static files only and cannot return a real HTTP 302.
The static-host equivalent is a tiny HTML page with `<meta http-equiv="refresh">`
plus `location.replace(...)`; major search engines (Google included) treat
`content="0"` meta-refresh as equivalent to a 301.

This script walks the current `docs/v1/...` tree, derives the legacy
1.x URL for each page, and writes a redirect stub at that legacy URL inside
the built HTML site so that external links pointing at the old URLs no
longer 404.

Run after `jupyter-book build .`:

    python scripts/generate_v1_redirects.py _build/html

The script is idempotent: re-running it overwrites existing stubs and
skips any path that the build already produced (so it cannot clobber real
content).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# (1.x url prefix under /<lang>/, v2 path prefix under docs/v1/<lang>/)
# e.g. legacy /en/harness/overview.html lived as docs/en/harness/overview.md on 1.x;
# the same file now lives as docs/v1/en/docs/harness/overview.md and is published at
# /v1/en/docs/harness/overview.html.
SECTION_MAP = [
    ("blogs", "blogs"),
    ("community", "community"),
    ("integration", "integration"),
    ("harness", "docs/harness"),
    ("quickstart", "docs/quickstart"),
    ("task", "docs/task"),
    ("multi-agent", "docs/multi-agent"),
]

LANGS = ("en", "zh")

STUB_TEMPLATE = """<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<title>Redirecting&hellip;</title>
<link rel="canonical" href="{canonical}">
<meta http-equiv="refresh" content="0; url={target}">
<script>location.replace({target_json});</script>
<style>body{{font-family:system-ui,sans-serif;max-width:32em;margin:4em auto;padding:0 1em;color:#444}}</style>
</head>
<body>
<p>This page has moved. Redirecting to <a href="{target}">{target}</a>&hellip;</p>
</body>
</html>
"""


def discover_pairs(repo_root: Path) -> list[tuple[Path, Path]]:
    """Yield (legacy_rel, new_rel) pairs that should be redirected.

    legacy_rel: build-relative path under the 1.x URL layout
                (e.g. ``en/harness/overview.html``)
    new_rel:    build-relative path under the v2 URL layout
                (e.g. ``v1/en/docs/harness/overview.html``)
    """
    v1_docs = repo_root / "docs" / "v1"
    pairs: list[tuple[Path, Path]] = []

    for lang in LANGS:
        lang_dir = v1_docs / lang
        if not lang_dir.is_dir():
            continue

        # intro.md lives at the language root
        if (lang_dir / "intro.md").exists():
            pairs.append(
                (
                    Path(f"{lang}/intro.html"),
                    Path(f"v1/{lang}/intro.html"),
                )
            )

        for legacy_section, new_section in SECTION_MAP:
            src_root = lang_dir.joinpath(*new_section.split("/"))
            if not src_root.is_dir():
                continue
            for md_path in sorted(src_root.rglob("*.md")):
                rel = md_path.relative_to(src_root).with_suffix(".html")
                legacy = Path(f"{lang}/{legacy_section}") / rel
                new = Path(f"v1/{lang}/{new_section}") / rel
                pairs.append((legacy, new))

    return pairs


def render_stub(lang: str, root_relative_target: str, site_base: str) -> str:
    canonical = (site_base.rstrip("/") + root_relative_target) if site_base else root_relative_target
    # JSON-encode for embedding in JS so any future special chars survive
    import json

    return STUB_TEMPLATE.format(
        lang=lang,
        target=root_relative_target,
        target_json=json.dumps(root_relative_target),
        canonical=canonical,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "build_dir",
        type=Path,
        help="Path to the built HTML root, e.g. docs/_build/html (or _build/html when run from docs/).",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Repository root. Defaults to two levels above this script.",
    )
    parser.add_argument(
        "--site-base",
        default="https://java.agentscope.io",
        help="Absolute site URL used for the <link rel=canonical> tag. "
        "Pass an empty string to omit. Defaults to %(default)s.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print planned redirects, write nothing.")
    args = parser.parse_args(argv)

    build_dir: Path = args.build_dir.resolve()
    if not args.dry_run and not build_dir.is_dir():
        print(f"error: build dir not found: {build_dir}", file=sys.stderr)
        return 2

    pairs = discover_pairs(args.repo_root.resolve())
    if not pairs:
        print("warning: no redirect pairs discovered — check docs/v1 layout", file=sys.stderr)
        return 1

    written = 0
    skipped_existing = 0

    for legacy_rel, new_rel in pairs:
        target = "/" + new_rel.as_posix()
        lang = legacy_rel.parts[0] if legacy_rel.parts else "en"

        if args.dry_run:
            print(f"  {legacy_rel}  ->  {target}")
            continue

        out_path = build_dir / legacy_rel
        if out_path.exists():
            # Never clobber a real page that the build actually produced.
            skipped_existing += 1
            continue
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(render_stub(lang, target, args.site_base), encoding="utf-8")
        written += 1

    if args.dry_run:
        print(f"v1 redirects (dry run): {len(pairs)} pairs would be considered.")
    else:
        print(
            f"v1 redirects: {written} stub(s) written, "
            f"{skipped_existing} skipped (real page already in build).",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
