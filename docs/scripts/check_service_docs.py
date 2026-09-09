#!/usr/bin/env python3
"""Check tracked TOC sources, then built Service pages before website publication."""
import argparse
from html.parser import HTMLParser
from pathlib import Path
import subprocess
from urllib.parse import unquote, urlsplit

import yaml


def toc_documents(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key in ('root', 'file') and isinstance(child, str):
                yield child
            else:
                yield from toc_documents(child)
    elif isinstance(value, list):
        for child in value:
            yield from toc_documents(child)


def check_sources(docs, toc, tracked):
    """Use the published TOC, not unrelated Markdown notes in the same directory."""
    errors = []
    service = {'en': [], 'zh': []}
    for docname in dict.fromkeys(toc_documents(toc)):
        source = docs / docname
        if source.suffix not in ('.md', '.rst', '.ipynb'):
            candidates = [docs / (docname + suffix) for suffix in ('.md', '.rst', '.ipynb')]
            source = next((candidate for candidate in candidates if candidate.is_file()), candidates[0])
        relative = source.relative_to(docs).as_posix()
        if not source.is_file():
            errors.append(f'TOC source missing: {relative}')
        elif relative not in tracked:
            errors.append(f'TOC source is not tracked by Git (missing in CI): {relative}')
        for language in service:
            prefix = f'v2/{language}/service/'
            if relative.startswith(prefix):
                service[language].append(source)
    names = {
        language: {p.relative_to(docs / 'v2' / language / 'service').with_suffix('').as_posix() for p in pages}
        for language, pages in service.items()
    }
    if not names['en'] or names['en'] != names['zh']:
        errors.append(
            f'Service TOC language pages differ: English only={sorted(names["en"] - names["zh"])}; '
            f'Chinese only={sorted(names["zh"] - names["en"])}; empty={not names["en"]}'
        )
    return service, errors


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.ids = set()
        self.in_article = False
        self.service_tab = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'article':
            self.in_article = True
        if attrs.get('id'):
            self.ids.add(attrs['id'])
        if attrs.get('data-tab-id') == 'service':
            self.service_tab = True
        if self.in_article and tag in ('a', 'img'):
            value = attrs.get('href' if tag == 'a' else 'src')
            if value:
                self.links.append(value)

    def handle_endtag(self, tag):
        if tag == 'article':
            self.in_article = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('built', nargs='?', type=Path, help='Built HTML directory')
    parser.add_argument('--source-only', action='store_true', help='Validate TOC inputs before Sphinx runs')
    args = parser.parse_args()
    docs = Path(__file__).resolve().parents[1]
    built = args.built.resolve() if args.built else docs / '_build/html'
    tracked = set(subprocess.check_output(['git', 'ls-files', '-z', '--', '.'], cwd=docs, text=True).split('\0'))
    sources_by_language, errors = check_sources(docs, yaml.safe_load((docs / '_toc.yml').read_text()), tracked)
    if errors:
        raise SystemExit('\n'.join(errors))
    if args.source_only:
        print('Documentation TOC: all sources exist and are tracked; Service language pages match.')
        return
    for language, sources in sources_by_language.items():
        for source in sources:
            page = built / source.relative_to(docs).with_suffix('.html')
            if not page.is_file():
                errors.append(f'Missing page: {page}')
                continue
            parsed = Page()
            parsed.feed(page.read_text())
            if not parsed.service_tab:
                errors.append(f'Missing Service navigation: {page}')
            for link in parsed.links:
                url = urlsplit(link)
                if url.scheme or url.netloc:
                    continue
                target = (built / unquote(url.path).lstrip('/')) if url.path.startswith('/') else page.parent / unquote(url.path) if url.path else page
                if not target.is_file():
                    errors.append(f'{page.name}: missing target {link}')
                elif url.fragment and target.suffix == '.html':
                    other = Page()
                    other.feed(target.read_text())
                    if unquote(url.fragment) not in other.ids:
                        errors.append(f'{page.name}: missing anchor {link}')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Service docs: {sum(map(len, sources_by_language.values()))} pages, navigation and local links passed.')


if __name__ == '__main__':
    main()
