import { chromium, expect } from '@playwright/test';
import { createServer } from 'vite';
import { mkdir, rename, rm, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installFixtures, timestamp } from './fixtures.mjs';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const output = resolve(frontend, '../../docs/imgs/service');
const staging = await mkdtemp(join(tmpdir(), 'agentscope-docs-capture-'));
const server = await createServer({ root: frontend, server: { host: '127.0.0.1', port: 5188, strictPort: true, open: false } });
let browser;
const shots = [];
try {
  await server.listen();
  browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, deviceScaleFactor: 1, colorScheme: 'light', locale: 'en-US', timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce', serviceWorkers: 'block' });
  const unexpected = await installFixtures(context);
  const page = await context.newPage();
  await page.clock.setFixedTime(new Date(timestamp));
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  async function capture(name, path, ready, prepare) {
    try {
      await page.setViewportSize({ width: 1440, height: ['chat', 'inbox', 'teams'].includes(name) ? 1120 : 960 });
      await page.goto(`http://127.0.0.1:5188${path}${path.includes('?') ? '&' : '?'}tenant=default&namespace=demo`);
      await expect(name === 'workflows' ? page.getByRole('button', { name: 'Run Workflow', exact: true }) : page.getByRole('heading', { name: ready, exact: true }).first()).toBeVisible();
      if (prepare) await prepare(page);
      await page.evaluate(() => document.fonts.ready);
      await page.mouse.move(0, 0);
      await expect(page.getByText('Loading references…', { exact: true })).toHaveCount(0);
      await expect(page.locator('[role="alert"]').filter({ hasText: /\S/ })).toHaveCount(0);
      if (errors.length || unexpected.size) throw new Error(JSON.stringify({ errors, unexpected: [...unexpected] }, null, 2));
      await page.screenshot({ path: join(staging, `${name}.png`), animations: 'disabled', fullPage: ['chat', 'inbox', 'teams'].includes(name) });
      shots.push(name);
      console.log(`Captured ${name}`);
    } catch (error) {
      console.error(JSON.stringify({ errors, unexpected: [...unexpected], page: await page.locator('body').innerText() }, null, 2));
      throw error;
    }
  }
  await capture('chat', '/work/chat?chat=planning', 'Plan the weekly research brief', async page => {
    await expect(page.getByText('Key findings', { exact: true })).toBeVisible();
    await expect(page.getByPlaceholder('Message Research assistant…')).toBeInViewport({ ratio: 1 });
  });
  await capture('issues', '/work/issues', 'Issues', async page => {
    await expect(page.getByRole('link', { name: 'Prepare the weekly research brief', exact: true })).toBeVisible();
  });
  await capture('inbox', '/work/inbox?item=review-request', 'Prepare the weekly research brief', async page => {
    await expect(page.getByText('The weekly brief is ready for review.', { exact: false })).toBeVisible();
  });
  await capture('automation', '/work/automations?automation=weekly-brief', 'Weekly research brief', async page => {
    await page.getByRole('button', { name: 'Edit', exact: true }).click();
    await expect(page.getByRole('dialog').getByLabel('Runbook')).toHaveValue(/Read the weekly source notes/);
  });
  await page.getByRole('dialog').getByLabel('Time zone', { exact: true }).scrollIntoViewIfNeeded();
  await expect(page.getByRole('dialog').getByText('Next runs', { exact: true })).toBeInViewport();
  await page.screenshot({ path: join(staging, 'automation-schedule.png'), animations: 'disabled' });
  shots.push('automation-schedule');
  console.log('Captured automation-schedule');
  await capture('agents', '/agent-center/agents', 'Agents', async page => {
    await expect(page.getByRole('heading', { name: 'Support assistant', exact: true })).toBeVisible();
    await expect(page.getByText('hosted', { exact: true })).toBeVisible();
  });
  await capture('teams', '/agent-center/teams', 'Teams', async page => {
    await page.getByRole('button', { name: 'New team', exact: true }).click();
    await page.getByPlaceholder('Team name').fill('Research team');
    await page.getByPlaceholder('What is this Team responsible for?').fill('Research, review and deliver a concise report.');
    await page.getByRole('combobox', { name: 'Team leader Agent' }).click();
    await page.getByRole('option', { name: /Research assistant/ }).click();
    await page.getByRole('combobox', { name: 'Additional Team members' }).click();
    await page.getByRole('option', { name: /Code reviewer/ }).click();
    await page.getByRole('heading', { name: 'Create Team', exact: true }).click();
  });
  await capture('workflows', '/agent-center/workflows/research-brief?tab=design', 'Research brief', async page => {
    await expect(page.getByRole('button', { name: 'Publish saved draft', exact: true })).toBeVisible();
  });
  await capture('channels', '/agent-center/entrypoints', 'Channels', async page => {
    await expect(page.getByText('team-feishu', { exact: true })).toBeVisible();
  });
  await capture('workspaces', '/agent-center/workspaces', 'Workspaces', async page => {
    await expect(page.getByText('Research workspace', { exact: true })).toBeVisible();
  });
  await capture('environments', '/agent-center/environments', 'Environments', async page => {
    await expect(page.getByText('Research worker', { exact: true })).toBeVisible();
  });
  await capture('memory', '/agent-center/memory', 'Memory Stores', async page => {
    await page.getByText('Research knowledge', { exact: true }).click();
    await expect(page.getByText('guides/research-brief.md', { exact: true })).toBeVisible();
  });
  await capture('vault', '/agent-center/vaults', 'Vaults', async page => {
    await page.getByText('Research credentials', { exact: true }).click();
    await expect(page.getByText('Documentation API', { exact: true })).toBeVisible();
  });
  if (errors.length || unexpected.size) throw new Error(JSON.stringify({ errors, unexpected: [...unexpected] }));
  await mkdir(output, { recursive: true });
  for (const name of shots) await rename(join(staging, `${name}.png`), join(output, `${name}.png`));
  console.log(`Published ${shots.length} screenshots to ${output}`);
} finally {
  await browser?.close();
  await server.close();
  await rm(staging, { recursive: true, force: true });
}
