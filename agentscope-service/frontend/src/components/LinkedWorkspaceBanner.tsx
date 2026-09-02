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

import React from 'react';
import { Link } from 'react-router-dom';
import { type TranslationKey, useT } from '../i18n';

const TAB_FOR: Record<string, string> = {
  skills: 'skills',
  tools: 'tools',
  subagents: 'subagents',
  files: 'agentsmd',
  settings: 'agentsmd',
};

const RESOURCE_LABELS: Record<string, TranslationKey> = {
  skills: 'linkedWorkspace.resource.skills',
  tools: 'linkedWorkspace.resource.tools',
  subagents: 'linkedWorkspace.resource.subagents',
  files: 'linkedWorkspace.resource.files',
  settings: 'linkedWorkspace.resource.settings',
};

export default function LinkedWorkspaceBanner({
  workspaceId,
  workspaceName,
  resource,
}: {
  workspaceId: string;
  workspaceName?: string;
  resource: 'skills' | 'tools' | 'subagents' | 'files' | 'settings';
}) {
  const t = useT();
  const label = workspaceName || workspaceId;
  const tab = TAB_FOR[resource] || 'agentsmd';
  const href = `/workspaces/${encodeURIComponent(workspaceId)}?tab=${encodeURIComponent(tab)}`;
  return (
    <div
      style={{
        padding: '10px 24px',
        fontSize: '0.82rem',
        color: '#3730a3',
        background: '#eef2ff',
        borderBottom: '1px solid #c7d2fe',
        display: 'flex',
        gap: 12,
        alignItems: 'center',
        flexWrap: 'wrap',
      }}
    >
      <span>
        {t('linkedWorkspace.prefix')} <strong>{label}</strong>
        {t('linkedWorkspace.suffix', { resource: t(RESOURCE_LABELS[resource]) })}
      </span>
      <Link
        to={href}
        style={{
          color: '#4338ca',
          fontWeight: 700,
          textDecoration: 'none',
          padding: '4px 10px',
          borderRadius: 999,
          border: '1px solid #c7d2fe',
          background: '#ffffff',
        }}
      >
        {t('linkedWorkspace.edit')} →
      </Link>
    </div>
  );
}
