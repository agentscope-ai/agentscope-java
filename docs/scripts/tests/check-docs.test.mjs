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
  if (options.zhPage) {
    fs.mkdirSync(path.join(dir, 'v2/zh'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'v2/zh/intro.md'), options.zhPage);
  }
  const languages = [{ language: 'en', versions: [{ version: 'v2', tabs: [{ tab: 'Home', pages: options.pages || ['v2/en/intro'] }] }] }];
  if (options.zhPage) languages.push({ language: 'zh', versions: [{ version: 'v2', tabs: [{ tab: '首页', pages: ['v2/zh/intro'] }] }] });
  fs.writeFileSync(path.join(dir, 'docs.json'), JSON.stringify({
    navigation: { languages },
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

test('translated pages require exact reciprocal language links', (t) => {
  const dir = fixture(t, { zhPage: '---\ntitle: 首页\nen_link: /v2/en/wrong\n---\n' });
  let result = checkSite(dir);
  assert(result.errors.includes('/v2/en/intro: zh_link must be /v2/zh/intro'));
  assert(result.errors.includes('/v2/zh/intro: en_link must be /v2/en/intro'));

  fs.writeFileSync(path.join(dir, 'v2/en/intro.md'), '---\ntitle: Home\nzh_link: /v2/zh/intro\n---\n');
  fs.writeFileSync(path.join(dir, 'v2/zh/intro.md'), '---\ntitle: 首页\nen_link: /v2/en/intro\n---\n');
  result = checkSite(dir);
  assert.deepEqual(result.errors, []);
});

test('legacy HTML redirects preserve valid fragments', (t) => {
  const result = checkSite(fixture(t, { body: '[Old link](/v2/en/intro.html#setup)', redirects: [{ source: '/v2/en/intro.html', destination: '/v2/en/intro' }] }));
  assert.deepEqual(result.errors, []);
});

test('stable, future and dev versions validate links within their own translation pair', (t) => {
  const dir = fixture(t);
  const config = JSON.parse(fs.readFileSync(path.join(dir, 'docs.json'), 'utf8'));
  config.navigation.languages[0].versions[0].version = '2.0';
  config.navigation.languages.push({ language: 'zh', versions: [] });
  for (const version of ['2.1', '3', 'dev']) {
    const directory = version === 'dev' ? version : `v${version}`;
    for (const language of ['en', 'zh']) {
      const other = language === 'en' ? 'zh' : 'en';
      const route = `${directory}/${language}/intro`;
      fs.mkdirSync(path.join(dir, `${directory}/${language}`), { recursive: true });
      fs.writeFileSync(path.join(dir, route + '.md'),
        `---\ntitle: Home\n${other}_link: /${directory}/${other}/intro\n---\n[Previous version](/v2/en/intro#setup)\n`);
      config.navigation.languages.find((item) => item.language === language)
        .versions.push({ version, pages: [route] });
    }
  }
  fs.writeFileSync(path.join(dir, 'docs.json'), JSON.stringify(config));
  const result = checkSite(dir);
  assert.deepEqual(result.errors, []);
  assert.equal(result.pageCount, 7);

  fs.writeFileSync(path.join(dir, 'v2.1/en/intro.md'),
    '---\ntitle: Home\nzh_link: /v3/zh/intro\n---\n[Missing](/v2.1/en/missing)\n');
  const errors = checkSite(dir).errors;
  assert(errors.includes('/v2.1/en/intro: zh_link must be /v2.1/zh/intro'));
  assert(errors.includes('/v2.1/en/intro: missing local target /v2.1/en/missing'));

  fs.writeFileSync(path.join(dir, 'dev/en/intro.md'),
    '---\ntitle: Dev\nzh_link: /v2/zh/intro\n---\n[Missing](/dev/en/missing)\n');
  const devErrors = checkSite(dir).errors;
  assert(devErrors.includes('/dev/en/intro: zh_link must be /dev/zh/intro'));
  assert(devErrors.includes('/dev/en/intro: missing local target /dev/en/missing'));
});

test('historical source links reject moving branches and allow release tags', (t) => {
  const dir = fixture(t, { body: [
    '[Source](https://github.com/agentscope-ai/agentscope-java/blob/main/pom.xml)',
    '<Card href="https://github.com/agentscope-ai/agentscope-java/tree/Mintlify/agentscope-core" />',
    '[Raw](https://raw.githubusercontent.com/agentscope-ai/agentscope-java/HEAD/pom.xml)',
    '[Tagged](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/pom.xml)',
    '[Legacy](https://github.com/agentscope-ai/agentscope-java/tree/v1.0.11/agentscope-core)',
    '[Other project](https://github.com/agentscope-ai/agentscope/blob/main/README.md)',
  ].join('\n\n') });
  const errors = checkSite(dir).errors;
  assert.equal(errors.length, 3);
  assert(errors.every((error) => error.includes('historical source link must use a release tag or commit')));
});

test('dev source links may follow the current branch', (t) => {
  const dir = fixture(t);
  fs.mkdirSync(path.join(dir, 'dev/en'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'dev/en/intro.md'), '---\ntitle: Dev\n---\n[Source](https://github.com/agentscope-ai/agentscope-java/blob/main/pom.xml)\n');
  const config = JSON.parse(fs.readFileSync(path.join(dir, 'docs.json'), 'utf8'));
  config.navigation.languages[0].versions.push({ version: 'dev', pages: ['dev/en/intro'] });
  fs.writeFileSync(path.join(dir, 'docs.json'), JSON.stringify(config));
  assert.deepEqual(checkSite(dir).errors, []);
});

test('new version directories cannot bypass navigation checks', (t) => {
  const dir = fixture(t);
  fs.mkdirSync(path.join(dir, 'v2.1/en'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'v2.1/en/intro.md'), '---\ntitle: Home\n---\n');
  assert(checkSite(dir).errors.includes('Page missing from navigation: /v2.1/en/intro'));
  fs.mkdirSync(path.join(dir, 'dev/en'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'dev/en/intro.md'), '---\ntitle: Dev\n---\n');
  assert(checkSite(dir).errors.includes('Page missing from navigation: /dev/en/intro'));
});

test('redirect cycles and duplicate sources fail', (t) => {
  const result = checkSite(fixture(t, { redirects: [{ source: '/a', destination: '/b' }, { source: '/b', destination: '/a' }, { source: '/a', destination: '/b' }] }));
  assert(result.errors.some((e) => e.includes('Duplicate redirect')));
  assert(result.errors.some((e) => e.includes('redirect cycle')));
});

test('external Helm index redirects are valid local link targets', (t) => {
  const result = checkSite(fixture(t, {
    body: '[Chart index](/helm/index.yaml)',
    redirects: [{ source: '/helm/index.yaml', destination: 'https://chickenlj.github.io/helm-charts/index.yaml' }],
  }));
  assert.deepEqual(result.errors, []);
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


test('publishing ignores exclude repository drafts and honor explicit negation', (t) => {
  const dir = fixture(t, { pages: ['v2/en/intro', 'v2/en/draft-public'] });
  fs.writeFileSync(path.join(dir, '.mintignore'), '  # Keep private design notes out of the site.\r\n  v2/en/draft-*.md  \r\n !v2/en/draft-public.md \r\n');
  fs.writeFileSync(path.join(dir, 'v2/en/draft-private.md'), '<not-valid-mdx');
  fs.writeFileSync(path.join(dir, 'v2/en/draft-public.md'), '---\ntitle: Public\n---\n');
  assert.deepEqual(checkSite(dir).errors, []);
  fs.writeFileSync(path.join(dir, 'v2/en/unlisted.md'), '---\ntitle: Unlisted\n---\n');
  assert(checkSite(dir).errors.includes('Page missing from navigation: /v2/en/unlisted'));
});

test('navigation cannot publish a page excluded by Mintlify', (t) => {
  const dir = fixture(t);
  fs.writeFileSync(path.join(dir, '.mintignore'), 'v2/en/intro.md\n');
  assert(checkSite(dir).errors.includes('Missing navigation page: /v2/en/intro'));
});

test('public links cannot target excluded files even when the files exist', (t) => {
  const dir = fixture(t, { body: '[Design](/v2/en/Internal%20Design.md)' });
  fs.writeFileSync(path.join(dir, '.mintignore'), 'v2/en/Internal Design.md\n');
  fs.writeFileSync(path.join(dir, 'v2/en/Internal Design.md'), '---\ntitle: Design\n---\n');
  assert(checkSite(dir).errors.some((e) => e.includes('local target excluded by .mintignore')));
});
