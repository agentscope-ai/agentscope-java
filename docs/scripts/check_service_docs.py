#!/usr/bin/env python3
"""Check built Service pages and their local links before website publication."""
from html.parser import HTMLParser
from pathlib import Path
import sys
from urllib.parse import unquote, urlsplit


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
    docs = Path(__file__).resolve().parents[1]
    built = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else docs / '_build/html'
    errors = []
    counts = []
    for language in ('en', 'zh'):
        sources = sorted((docs / 'v2' / language / 'service').glob('*.md'))
        counts.append(len(sources))
        for source in sources:
            page = built / 'v2' / language / 'service' / (source.stem + '.html')
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
    if not counts[0] or counts[0] != counts[1]:
        errors.append(f'Language page counts differ: {counts}')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Service docs: {sum(counts)} pages, navigation and local links passed.')


if __name__ == '__main__':
    main()
