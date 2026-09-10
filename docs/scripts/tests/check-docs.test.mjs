import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { checkSite, inspectPage, navigationPages } from '../check-docs.mjs';

function fixture(t, options = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agentscope-docs-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'v2/en'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'v2/en/intro.md'), '---\ntitle: Home\n---\n\n## Setup\n' + (options.body || ''));
  fs.writeFileSync(path.join(dir, 'docs.json'), JSON.stringify({
    navigation: { languages: [{ language: 'en', versions: [{ version: 'v2', tabs: [{ tab: 'Home', pages: options.pages || ['v2/en/intro'] }] }] }] },
    redirects: options.redirects || [],
  }));
  return dir;
}

test('nested versions, languages and group roots are included', () => {
  assert.deepEqual(navigationPages({ languages: [{ versions: [{ groups: [{ root: 'index', pages: ['quickstart', { group: 'Tools', pages: ['tools/mcp'] }] }] }] }] }), ['index', 'quickstart', 'tools/mcp']);
});

test('code examples are not interpreted as site links or directives', () => {
  assert.deepEqual(inspectPage('---\ntitle: Test\n---\n```md\n[example](/missing)\n:::{note}\n```').links, []);
});

test('MDX attributes, images and reference links are checked', () => {
  const page = inspectPage('<Card href="/guide" />\n\n<img src={"/image.png"} />\n\n[Guide][ref]\n\n[ref]: /reference');
  assert.deepEqual(page.links, ['/guide', '/image.png', '/reference']);
});

test('missing navigation pages and orphan content both fail', (t) => {
  const result = checkSite(fixture(t, { pages: ['v2/en/missing'] }));
  assert(result.errors.some((e) => e.includes('Missing navigation page')));
  assert(result.errors.some((e) => e.includes('Page missing from navigation')));
});

test('broken assets and anchors fail even when the page exists', (t) => {
  const result = checkSite(fixture(t, { body: '[Missing](#unknown)\n\n![Image](/imgs/missing.png)' }));
  assert.equal(result.errors.length, 2);
  assert(result.errors.some((e) => e.includes('missing anchor')));
  assert(result.errors.some((e) => e.includes('missing local target')));
});

test('legacy HTML redirects preserve valid fragments', (t) => {
  const result = checkSite(fixture(t, { body: '[Old link](/v2/en/intro.html#setup)', redirects: [{ source: '/v2/en/intro.html', destination: '/v2/en/intro' }] }));
  assert.deepEqual(result.errors, []);
});

test('redirect cycles and duplicate sources fail', (t) => {
  const result = checkSite(fixture(t, { redirects: [{ source: '/a', destination: '/b' }, { source: '/b', destination: '/a' }, { source: '/a', destination: '/b' }] }));
  assert(result.errors.some((e) => e.includes('Duplicate redirect')));
  assert(result.errors.some((e) => e.includes('redirect cycle')));
});

test('MyST directives cannot silently become visible prose', () => {
  assert.throws(() => inspectPage(':::{note}\nA warning\n:::'), /Unconverted MyST/);
});

test('wildcards resolve nested legacy links and preserve anchors', (t) => {
  const result = checkSite(fixture(t, {
    body: '[Legacy](/en/intro#setup)',
    redirects: [{ source: '/en/:slug*', destination: '/v2/en/:slug*' }],
  }));
  assert.deepEqual(result.errors, []);
});

test('exact HTML redirects take precedence over wildcard fallbacks', (t) => {
  const result = checkSite(fixture(t, {
    body: '[Legacy](/en/intro.html#setup)',
    redirects: [
      { source: '/en/intro.html', destination: '/v2/en/intro' },
      { source: '/en/:slug*', destination: '/v2/en/:slug*' },
    ],
  }));
  assert.deepEqual(result.errors, []);
});

test('wildcards reject missing destinations, page shadowing and growing cycles', (t) => {
  const result = checkSite(fixture(t, { redirects: [
    { source: '/missing/:slug*', destination: '/absent/:slug*' },
    { source: '/v2/en/:slug*', destination: '/v2/en/nested/:slug*' },
  ] }));
  assert(result.errors.some((e) => e.includes('no destination pages')));
  assert(result.errors.some((e) => e.includes('shadows page')));
  assert(result.errors.some((e) => e.includes('redirect cycle')));
});
