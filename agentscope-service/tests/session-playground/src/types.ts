// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
// Local Session API v1 types for the test client.
export interface ManagedSession {
  id: string;
  ownerId?: string;
  runtimeKind?: string;
  agentId: string;
  agentOwnerId?: string;
  agentVersion?: number | null;
  agentRefType?: string;
  agentOverridesJson?: string | null;
  environmentId: string;
  memoryStoreIds?: string[];
  vaultIds?: string[];
  status: string;
  stopReason?: Record<string, unknown> | null;
  createdAt: number;
  updatedAt: number;
  archivedAt?: number | null;
  externalKey?: string | null;
}

export interface AgentSessionEvent {
  schema_version?: number;
  id?: string;
  type: string;
  session_id: string;
  created_at?: number | string;
  cursor?: string;
  ephemeral?: boolean;
  data: Record<string, unknown>;
}
export interface AgentTurn {
  id: string;
  sessionId: string;
  status: string;
  createdAt: number;
  updatedAt?: number;
  admissionSequence?: number;
  deliveryStatus?: string;
  deliveryError?: string;
  errorCode?: string;
  error?: { code: string; message?: string };
}
export interface AgentSessionSnapshot {
  session: ManagedSession;
  as_of: string;
  items: AgentSessionEvent[];
  turns: AgentSessionEvent[];
  runs: AgentSessionEvent[];
  steps?: AgentSessionEvent[];
  required_actions: AgentSessionEvent[];
  tools?: AgentSessionEvent[];
  inputs?: AgentSessionEvent[];
  action_commands?: AgentSessionEvent[];
  artifacts?: AgentSessionEvent[];
  subagents?: AgentSessionEvent[];
  usage?: Record<string, unknown>;
  resource_pages?: Record<string, AgentResourcePage>;
}
export interface AgentResourcePage {
  snapshot_id: string;
  as_of: string;
  expires_at: number;
  data: AgentSessionEvent[];
  next_cursor: string | null;
  has_more: boolean;
}
