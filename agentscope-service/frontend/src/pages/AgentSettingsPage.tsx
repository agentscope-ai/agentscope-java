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
import { useOutletContext } from 'react-router-dom';
import { AgentDefinition } from '../api/agents';
import AgentSettingsForm from '../components/AgentSettingsForm';

export default function AgentSettingsPage() {
  const { agent, refreshAgent } = useOutletContext<{
    agentId: string;
    agent: AgentDefinition | null;
    refreshAgent?: () => void | Promise<unknown>;
  }>();
  if (!agent) {
    return <div style={{ padding: '24px 28px', color: '#64748b' }}>Loading…</div>;
  }
  return <AgentSettingsForm agent={agent} onSaved={refreshAgent} />;
}
