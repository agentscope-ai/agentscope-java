import { api } from "@/lib/apiClient";

export interface EntityRef {
  type?: string;
  ref?: string;
}

export interface EntityIdentity {
  type: string;
  ref: string;
  name: string;
  secondary?: string;
  resolved: boolean;
}

export interface EntityIdentityResponse {
  items: EntityIdentity[];
}

const MAX_REFS_PER_REQUEST = 200;

const aliases: Record<string, string> = {
  workflow: "orchestration_definition",
  orchestration: "orchestration_definition",
  workflow_revision: "orchestration_revision",
  "workflow-revision": "orchestration_revision",
  "orchestration-revision": "orchestration_revision",
  execution: "orchestration_run",
  "orchestration-run": "orchestration_run",
  task: "agent_task",
  "agent-task": "agent_task",
  attempt: "execution_attempt",
  "execution-attempt": "execution_attempt",
  endpoint_job: "endpoint_invocation",
  endpoint_source: "endpoint_invocation",
  "endpoint-invocation": "endpoint_invocation",
  "runtime-profile": "runtime_profile",
  "runtime-pool": "runtime_pool",
  "runtime-host": "runtime_host",
  "work-source": "work_source",
};

const prefixedTypes = new Set([
  "agent", "team", "endpoint", "endpoint_invocation", "endpoint_job", "endpoint_source",
  "workflow", "workflow_revision", "orchestration", "orchestration_definition",
  "orchestration_revision", "execution", "orchestration_run", "run", "task", "agent_task",
  "attempt", "execution_attempt", "issue", "work_source", "runtime_profile", "runtime_pool",
  "runtime_host", "human", "system", "automation",
  "workflow-revision", "orchestration-revision", "orchestration-run", "agent-task",
  "execution-attempt", "endpoint-invocation", "runtime-profile", "runtime-pool",
  "runtime-host", "work-source",
]);

export function normalizeEntityRef(input: EntityRef): Required<EntityRef> {
  let type = (input.type || "").trim().toLowerCase();
  let ref = (input.ref || "").trim();
  const colon = ref.indexOf(":");
  if (colon > 0) {
    const prefix = ref.slice(0, colon).toLowerCase();
    if (prefixedTypes.has(prefix)) {
      type = prefix;
      ref = ref.slice(colon + 1).trim();
    }
  }
  return { type: aliases[type] || type, ref };
}

export function entityIdentityKey(input: EntityRef): string {
  const normalized = normalizeEntityRef(input);
  return `${normalized.type}\u0000${normalized.ref}`;
}

export async function resolveEntityIdentities(refs: EntityRef[]): Promise<EntityIdentityResponse> {
  if (refs.length === 0) return { items: [] };
  const requests: Array<Promise<EntityIdentityResponse>> = [];
  for (let index = 0; index < refs.length; index += MAX_REFS_PER_REQUEST) {
    requests.push(api.post<EntityIdentityResponse>("/api/v1/entity-identities:resolve", {
      refs: refs.slice(index, index + MAX_REFS_PER_REQUEST),
    }));
  }
  const responses = await Promise.all(requests);
  return { items: responses.flatMap((response) => response.items) };
}
