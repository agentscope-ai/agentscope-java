// Validate repository-only JEV documentation; performs no network or model calls.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { inspectPage } from '../scripts/check-docs.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const files = [];
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, e.name);
    if (e.isDirectory()) walk(file);
    else files.push(file);
  }
}
walk(root);
let pages = 0;
let links = 0;
for (const file of files.filter(f => f.endsWith('.md'))) {
  const page = inspectPage(fs.readFileSync(file, 'utf8'));
  if (!page.title) throw new Error(`Missing title: ${file}`);
  pages++;
  for (const href of page.links) {
    if (/^[a-z][a-z0-9+.-]*:/i.test(href)) continue;
    const [base, anchor] = href.split('#');
    const target = base ? path.resolve(path.dirname(file), decodeURIComponent(base)) : file;
    if (!fs.existsSync(target)) throw new Error(`Missing link: ${file} -> ${href}`);
    if (anchor && target.endsWith('.md')) {
      const other = inspectPage(fs.readFileSync(target, 'utf8'));
      if (!other.anchors.has(decodeURIComponent(anchor))) throw new Error(`Missing anchor: ${href}`);
    }
    links++;
  }
}
for (const file of files.filter(f => f.endsWith('.json.txt'))) JSON.parse(fs.readFileSync(file, 'utf8'));
const cases = fs.readFileSync(path.join(root, 'examples/scenarios.jsonl.txt'), 'utf8').trim().split('\n').map(JSON.parse);
if (new Set(cases.map(x => x.id)).size !== cases.length) throw new Error('Duplicate case id');
for (const item of cases) {
  if (!item.state || !item.expected_actions?.length) throw new Error(`Incomplete case: ${item.id}`);
  if (Object.keys(item.questions).sort().join() !== Object.keys(item.expected).sort().join()) throw new Error(`Label mismatch: ${item.id}`);
  for (const [id, question] of Object.entries(item.questions)) {
    if (question.type === 'noul' && typeof item.expected[id] !== 'boolean') throw new Error(`Invalid noul label: ${item.id}`);
    if (question.type === 'choice' && !(item.expected[id] in question.criteria)) throw new Error(`Invalid choice label: ${item.id}`);
  }
}
const summary = JSON.parse(fs.readFileSync(path.join(root, 'evidence/jevbench-summary.json.txt'), 'utf8'));
if (summary.overall.jev.n !== 111 || summary.overall.qwen.n !== 111) throw new Error('Incomplete benchmark evidence');
console.log(`PASS: ${pages} MDX pages, ${links} local links, ${cases.length} synthetic cases, JSON and benchmark summary. No API requests made.`);
