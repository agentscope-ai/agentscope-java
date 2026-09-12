/** Validate every published page, navigation entry, redirect and local asset. */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createProcessor } from '@mdx-js/mdx';
import remarkGfm from 'remark-gfm';
import matter from 'gray-matter';
import GithubSlugger from 'github-slugger';

function walk(node, visit) {
  visit(node);
  for (const child of node.children || []) walk(child, visit);
}

export function navigationPages(value) {
  if (Array.isArray(value)) return value.flatMap(navigationPages);
  if (!value || typeof value !== 'object') return [];
  const result = typeof value.root === 'string' ? [value.root] : [];
  for (const [key, child] of Object.entries(value)) {
    if (key === 'pages') {
      result.push(...child.flatMap((item) => typeof item === 'string' ? [item] : navigationPages(item)));
    } else if (key !== 'root') result.push(...navigationPages(child));
  }
  return result;
}

function plainText(node) {
  if (node.type === 'text' || node.type === 'inlineCode') return node.value;
  return (node.children || []).map(plainText).join('');
}

function attributeValue(attribute) {
  if (typeof attribute.value === 'string') return attribute.value;
  const expression = attribute.value?.data?.estree?.body?.[0]?.expression;
  return expression?.type === 'Literal' && typeof expression.value === 'string' ? expression.value : null;
}

export function inspectPage(source) {
  const { data, content } = matter(source);
  const tree = createProcessor({ remarkPlugins: [remarkGfm] }).parse(content);
  const links = [];
  const anchors = new Set();
  const definitions = new Map();
  const slugger = new GithubSlugger();
  walk(tree, (node) => {
    if (node.type === 'definition') definitions.set(node.identifier, node.url);
    if (node.type === 'heading') anchors.add(slugger.slug(plainText(node)));
    if (node.type === 'link' || node.type === 'image') links.push(node.url);
    if (node.type === 'code' && /^\{/.test(node.lang || '')) throw new Error('Unconverted Sphinx code directive');
    if (node.type === 'text' && /(?:^|\n)\s*:{3,}(?:\{|$)/.test(node.value)) throw new Error('Unconverted MyST directive');
    if (node.type === 'mdxJsxFlowElement' || node.type === 'mdxJsxTextElement') {
      if (node.name === 'script') throw new Error('Move page scripts into a shared JavaScript file');
      for (const attr of node.attributes || []) {
        const value = attributeValue(attr);
        if (value && ['href', 'src'].includes(attr.name)) links.push(value);
        if (value && attr.name === 'id') anchors.add(value);
      }
    }
  });
  walk(tree, (node) => {
    if (node.type === 'linkReference' || node.type === 'imageReference') {
      const target = definitions.get(node.identifier);
      if (!target) throw new Error(`Missing link definition: ${node.identifier}`);
      links.push(target);
    }
  });
  return { links, anchors, title: data.title };
}

function pageFiles(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? pageFiles(file) : /\.mdx?$/.test(entry.name) ? [file] : [];
  });
}

export function checkSite(docs) {
  const config = JSON.parse(fs.readFileSync(path.join(docs, 'docs.json'), 'utf8'));
  const errors = [];
  const pages = new Map();
  const routes = navigationPages(config.navigation);
  for (const file of ['v1', 'v2'].flatMap((version) => pageFiles(path.join(docs, version)))) {
    const route = '/' + path.relative(docs, file).replace(/\.mdx?$/, '').split(path.sep).join('/');
    try {
      if (pages.has(route)) errors.push(`Duplicate page route: ${route}`);
      const page = inspectPage(fs.readFileSync(file, 'utf8'));
      pages.set(route, page);
      if (!page.title) errors.push(`${route}: missing title`);
    } catch (error) { errors.push(`${route}: ${error.message}`); }
  }
  const published = new Set(routes.map((route) => '/' + route));
  for (const route of published) if (!pages.has(route)) errors.push(`Missing navigation page: ${route}`);
  for (const route of pages.keys()) if (!published.has(route)) errors.push(`Page missing from navigation: ${route}`);
  const redirects = new Map();
  const wildcards = [];
  for (const { source, destination } of config.redirects || []) {
    if (redirects.has(source)) errors.push(`Duplicate redirect: ${source}`);
    if (pages.has(source)) errors.push(`Redirect shadows page: ${source}`);
    redirects.set(source, destination);
    if (source.includes('*') || destination.includes('*')) {
      if (!source.endsWith('/:slug*') || !destination.endsWith('/:slug*') ||
          /[:*]/.test(source.slice(0, -7) + destination.slice(0, -7))) {
        errors.push(`Unsupported wildcard redirect: ${source}`);
        continue;
      }
      wildcards.push({ source: source.slice(0, -7), destination: destination.slice(0, -7) });
    }
  }
  const matchesPrefix = (route, prefix) => route === prefix || route.startsWith(prefix + '/');
  function nextRedirect(target) {
    if (redirects.has(target)) return redirects.get(target);
    const rule = wildcards.find(({ source }) => matchesPrefix(target, source));
    return rule ? rule.destination + target.slice(rule.source.length) : undefined;
  }
  for (const rule of wildcards) {
    for (const route of pages.keys()) {
      if (matchesPrefix(route, rule.source)) errors.push(`Redirect shadows page: ${route}`);
    }
    if (![...pages.keys()].some((route) => matchesPrefix(route, rule.destination))) {
      errors.push(`Wildcard redirect has no destination pages: ${rule.source}`);
    }
  }
  function resolve(target) {
    const seen = new Set();
    while (nextRedirect(target) !== undefined) {
      if (seen.has(target) || seen.size > redirects.size) return null;
      seen.add(target);
      target = nextRedirect(target);
    }
    return target;
  }
  function checkLink(from, link) {
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(link)) return;
    const url = new URL(link, 'https://docs.invalid' + from);
    const pathname = decodeURIComponent(url.pathname);
    const target = resolve(pathname);
    if (target === null) { errors.push(`${from}: redirect cycle at ${link}`); return; }
    const page = pages.get(target);
    if (page) {
      if (url.hash && !page.anchors.has(decodeURIComponent(url.hash.slice(1)))) errors.push(`${from}: missing anchor ${link}`);
      return;
    }
    const file = path.resolve(docs, '.' + target);
    if (!file.startsWith(path.resolve(docs) + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      errors.push(`${from}: missing local target ${link}`);
    }
  }
  for (const [route, page] of pages) for (const link of page.links) checkLink(route, link);
  for (const [source] of redirects) {
    if (!source.includes('*')) checkLink(source, source);
  }
  for (const rule of wildcards) {
    if (resolve(rule.source + '/__redirect_probe__') === null) errors.push(`redirect cycle at ${rule.source}`);
    for (const route of pages.keys()) {
      if (matchesPrefix(route, rule.destination)) checkLink(rule.source, rule.source + route.slice(rule.destination.length));
    }
  }
  for (const asset of [config.favicon, config.logo?.light, config.logo?.dark]) if (asset) checkLink('/docs.json', asset);
  return { errors: [...new Set(errors)], pageCount: pages.size, redirectCount: redirects.size };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const docs = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const { errors, pageCount, redirectCount } = checkSite(docs);
  if (errors.length) {
    console.error(errors.join('\n'));
    console.error(`${errors.length} documentation error(s)`);
    process.exitCode = 1;
  } else console.log(`${pageCount} pages, ${redirectCount} redirects: navigation, syntax, local links, anchors and assets passed.`);
}
