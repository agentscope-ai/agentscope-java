/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SRC = path.join(ROOT, 'src');
const errors = [];

const catalogSpecs = [
  ['src/i18n/messages/en.ts', 'en', 'src/i18n/messages/zh.ts', 'zh'],
  [
    'src/i18n/messages/managed.ts',
    'managedEn',
    'src/i18n/messages/managed.ts',
    'managedZh',
  ],
  [
    'src/i18n/messages/operate.ts',
    'operateEn',
    'src/i18n/messages/operate.ts',
    'operateZh',
  ],
  [
    'src/i18n/messages/teams.ts',
    'teamsEn',
    'src/i18n/messages/teams.ts',
    'teamsZh',
  ],
];

const technicalCopyAllowlist = new Set([
  'src/app/AppShell.tsx\0attr\0AgentScope',
  'src/app/AppShell.tsx\0jsx\0aistio',
  'src/components/AgentSettingsForm.tsx\0jsx\0agentscope.json',
  'src/components/AgentSettingsForm.tsx\0conditional\0· AGENTS.md',
  'src/components/ChannelBindingTable.tsx\0attr\0admin, support',
  'src/components/SkillsWorkspacePanel.tsx\0jsx\0workspace/skills/&lt;name&gt;/SKILL.md',
  'src/components/SkillsWorkspacePanel.tsx\0jsx\0KB',
  'src/components/SkillsWorkspacePanel.tsx\0jsx\0references/',
  'src/components/SkillsWorkspacePanel.tsx\0jsx\0scripts/',
  'src/components/SkillsWorkspacePanel.tsx\0jsx\0/SKILL.md',
  'src/components/ToolsActivePanel.tsx\0conditional\0mcp',
  'src/components/ToolsCatalogPanel.tsx\0jsx\0&middot;',
  'src/features/operate/OperateAgentDetailPage.tsx\0jsx\0GET /api/v1/agents/:name/subagents',
  'src/features/operate/OperateAgentDetailPage.tsx\0jsx\0GET /api/v1/agents/:name/workspaces',
  'src/features/operate/components/SessionEventsPanel.tsx\0jsx\0claw.aistio.enable-events: false',
  'src/features/teams/TeamCreatePage.tsx\0attr\0research',
  'src/features/teams/TeamCreatePage.tsx\0attr\0default',
  'src/features/teams/TeamCreatePage.tsx\0attr\0agent-name',
  'src/features/teams/TeamDetailPage.tsx\0jsx\0ns=',
  'src/features/teams/TeamDetailPage.tsx\0attr\0agentRef',
  'src/pages/AgentCreatePage.tsx\0conditional\0AGENTS.md ·',
  'src/pages/WorkspaceDetailPage.tsx\0conditional\0· AGENTS.md',
  'src/pages/WorkspacesHubPage.tsx\0conditional\0· AGENTS.md',
]);

const visibleCopy = /[A-Za-z]{2,}|[\u4e00-\u9fff]/;
const copyAttributes = new Set(['placeholder', 'title', 'aria-label', 'alt']);
const copyProperties = new Set([
  'label',
  'title',
  'description',
  'placeholder',
  'help',
  'hint',
  'emptyText',
  'message',
  'ariaLabel',
  'buttonText',
  'text',
]);

function relative(file) {
  return path.relative(ROOT, file).split(path.sep).join('/');
}

function normalizeCopy(value) {
  return value.replace(/\s+/g, ' ').trim();
}

function unwrapExpression(node) {
  let current = node;
  while (
    current &&
    (ts.isAsExpression(current) ||
      ts.isSatisfiesExpression(current) ||
      ts.isParenthesizedExpression(current))
  ) {
    current = current.expression;
  }
  return current;
}

function readCatalogObject(fileName, variableName) {
  const file = path.join(ROOT, fileName);
  const source = fs.readFileSync(file, 'utf8');
  const sourceFile = ts.createSourceFile(
    file,
    source,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  );

  for (const statement of sourceFile.statements) {
    if (!ts.isVariableStatement(statement)) continue;
    for (const declaration of statement.declarationList.declarations) {
      if (!ts.isIdentifier(declaration.name) || declaration.name.text !== variableName) {
        continue;
      }
      const initializer = unwrapExpression(declaration.initializer);
      if (!initializer || !ts.isObjectLiteralExpression(initializer)) {
        errors.push(`${fileName}: ${variableName} must be an object literal`);
        return new Map();
      }

      const messages = new Map();
      for (const property of initializer.properties) {
        if (ts.isSpreadAssignment(property)) continue;
        if (
          !ts.isPropertyAssignment(property) ||
          !ts.isStringLiteral(property.name) ||
          !ts.isStringLiteralLike(property.initializer)
        ) {
          errors.push(`${fileName}: ${variableName} contains a non-literal message`);
          continue;
        }
        const key = property.name.text;
        if (messages.has(key)) {
          errors.push(`${fileName}: duplicate key ${key} in ${variableName}`);
        }
        messages.set(key, property.initializer.text);
      }
      return messages;
    }
  }

  errors.push(`${fileName}: missing ${variableName}`);
  return new Map();
}

function interpolationTokens(value) {
  return Array.from(value.matchAll(/\{(\w+)\}/g), (match) => match[1]).sort();
}

function checkCatalogs() {
  const english = new Map();
  const chinese = new Map();

  for (const [enFile, enName, zhFile, zhName] of catalogSpecs) {
    const enMessages = readCatalogObject(enFile, enName);
    const zhMessages = readCatalogObject(zhFile, zhName);

    for (const [key, value] of enMessages) {
      if (english.has(key)) errors.push(`duplicate English key across catalogs: ${key}`);
      english.set(key, value);
    }
    for (const [key, value] of zhMessages) {
      if (chinese.has(key)) errors.push(`duplicate Chinese key across catalogs: ${key}`);
      chinese.set(key, value);
    }
  }

  for (const [key, enValue] of english) {
    if (!enValue.trim()) errors.push(`empty English message: ${key}`);
    if (!chinese.has(key)) {
      errors.push(`missing Chinese message: ${key}`);
      continue;
    }
    const zhValue = chinese.get(key);
    if (!zhValue.trim()) errors.push(`empty Chinese message: ${key}`);
    if (interpolationTokens(enValue).join('\0') !== interpolationTokens(zhValue).join('\0')) {
      errors.push(`interpolation token mismatch: ${key}`);
    }
  }
  for (const key of chinese.keys()) {
    if (!english.has(key)) errors.push(`extra Chinese message: ${key}`);
  }

  return english;
}

function walk(directory, predicate, files = []) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (file !== path.join(SRC, 'i18n', 'messages')) walk(file, predicate, files);
    } else if (predicate(entry.name)) {
      files.push(file);
    }
  }
  return files;
}

function callName(expression) {
  if (ts.isIdentifier(expression)) return expression.text;
  if (ts.isPropertyAccessExpression(expression)) return expression.name.text;
  return '';
}

function isTranslationCall(expression) {
  if (ts.isIdentifier(expression)) {
    return expression.text === 't' || expression.text === 'tr';
  }
  return (
    ts.isPropertyAccessExpression(expression) &&
    expression.name.text === 'current' &&
    ts.isIdentifier(expression.expression) &&
    expression.expression.text === 'tRef'
  );
}

function literalObjectKeys(node) {
  const object = unwrapExpression(node);
  if (!object || !ts.isObjectLiteralExpression(object)) return null;

  const keys = [];
  for (const property of object.properties) {
    if (ts.isSpreadAssignment(property)) return null;
    if (ts.isShorthandPropertyAssignment(property)) {
      keys.push(property.name.text);
      continue;
    }
    if (
      ts.isPropertyAssignment(property) &&
      (ts.isIdentifier(property.name) || ts.isStringLiteral(property.name))
    ) {
      keys.push(property.name.text);
      continue;
    }
    return null;
  }
  return keys.sort();
}

function checkUiCopy(englishMessages) {
  const usedAllowlist = new Set();
  const sourceFiles = walk(SRC, (name) => /\.(ts|tsx)$/.test(name) && !name.includes('.test.'));

  for (const file of sourceFiles) {
    const fileName = relative(file);
    const source = fs.readFileSync(file, 'utf8');
    const sourceFile = ts.createSourceFile(
      file,
      source,
      ts.ScriptTarget.Latest,
      true,
      file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
    );

    const report = (node, kind, rawValue) => {
      const value = normalizeCopy(rawValue);
      if (!visibleCopy.test(value)) return;
      const allowKey = `${fileName}\0${kind}\0${value}`;
      if (technicalCopyAllowlist.has(allowKey)) {
        usedAllowlist.add(allowKey);
        return;
      }
      const position = sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile));
      errors.push(
        `${fileName}:${position.line + 1}:${position.character + 1} ` +
          `hard-coded ${kind} copy: ${JSON.stringify(value)}`,
      );
    };

    const reportConditionalBranch = (node) => {
      if (ts.isStringLiteralLike(node)) report(node, 'conditional', node.text);
    };

    const checkTranslationParams = (node) => {
      if (!isTranslationCall(node.expression) || !ts.isStringLiteralLike(node.arguments[0])) {
        return;
      }

      const key = node.arguments[0].text;
      const template = englishMessages.get(key);
      if (template == null) return;

      const expected = interpolationTokens(template);
      const supplied = node.arguments[1] ? literalObjectKeys(node.arguments[1]) : [];
      if (supplied == null) return;

      const missing = expected.filter((name) => !supplied.includes(name));
      const unexpected = supplied.filter((name) => !expected.includes(name));
      if (missing.length === 0 && unexpected.length === 0) return;

      const position = sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile));
      const details = [];
      if (missing.length > 0) details.push(`missing ${missing.join(', ')}`);
      if (unexpected.length > 0) details.push(`unexpected ${unexpected.join(', ')}`);
      errors.push(
        `${fileName}:${position.line + 1}:${position.character + 1} ` +
          `translation params for ${key}: ${details.join('; ')}`,
      );
    };

    const visit = (node) => {
      if (ts.isJsxText(node)) report(node, 'jsx', node.text);

      if (ts.isCallExpression(node)) checkTranslationParams(node);

      if (
        ts.isJsxAttribute(node) &&
        copyAttributes.has(node.name.text) &&
        node.initializer &&
        ts.isStringLiteral(node.initializer)
      ) {
        report(node, 'attr', node.initializer.text);
      }

      if (
        ts.isCallExpression(node) &&
        ['confirm', 'alert', 'prompt'].includes(callName(node.expression)) &&
        node.arguments[0] &&
        ts.isStringLiteralLike(node.arguments[0])
      ) {
        report(node, 'call', node.arguments[0].text);
      }

      if (
        ts.isJsxExpression(node) &&
        node.expression &&
        (ts.isJsxElement(node.parent) || ts.isJsxFragment(node.parent))
      ) {
        if (ts.isStringLiteralLike(node.expression)) {
          report(node, 'expr', node.expression.text);
        } else if (ts.isConditionalExpression(node.expression)) {
          reportConditionalBranch(node.expression.whenTrue);
          reportConditionalBranch(node.expression.whenFalse);
        } else if (
          ts.isBinaryExpression(node.expression) &&
          node.expression.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken
        ) {
          reportConditionalBranch(node.expression.right);
        }
      }

      if (ts.isPropertyAssignment(node)) {
        const name =
          ts.isIdentifier(node.name) || ts.isStringLiteral(node.name) ? node.name.text : '';
        if (copyProperties.has(name) && ts.isStringLiteralLike(node.initializer)) {
          report(node, 'property', node.initializer.text);
        }
      }

      ts.forEachChild(node, visit);
    };

    visit(sourceFile);
  }

  for (const allowKey of technicalCopyAllowlist) {
    if (!usedAllowlist.has(allowKey)) {
      errors.push(`stale technical-copy allowlist entry: ${allowKey.replaceAll('\0', ' | ')}`);
    }
  }

  return sourceFiles.length;
}

const englishMessages = checkCatalogs();
const sourceFileCount = checkUiCopy(englishMessages);

if (errors.length > 0) {
  console.error(`i18n check failed with ${errors.length} problem(s):`);
  for (const error of errors) console.error(`- ${error}`);
  process.exitCode = 1;
} else {
  console.log(
    `i18n check passed: ${englishMessages.size} paired messages, ` +
      `${sourceFileCount} source files, no unapproved hard-coded UI copy or invalid params.`,
  );
}
