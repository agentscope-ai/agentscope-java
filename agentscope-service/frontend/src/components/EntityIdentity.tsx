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

/* eslint-disable react-refresh/only-export-components */
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";

import {
  entityIdentityKey,
  normalizeEntityRef,
  resolveEntityIdentities,
  type EntityIdentity,
  type EntityRef,
} from "@/api/entityIdentities";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { cn } from "@/lib/utils";

export type EntityIdentityMap = Map<string, EntityIdentity>;

export function useEntityIdentities(refs: EntityRef[]): EntityIdentityMap {
  const scope = useControlPlaneScope();
  const serialized = useMemo(() => {
    const unique = new Map<string, Required<EntityRef>>();
    for (const input of refs) {
      const normalized = normalizeEntityRef(input);
      if (normalized.type && normalized.ref) unique.set(entityIdentityKey(normalized), normalized);
    }
    return JSON.stringify([...unique.values()].sort((a, b) => entityIdentityKey(a).localeCompare(entityIdentityKey(b))));
  }, [refs]);
  const normalizedRefs = useMemo(() => JSON.parse(serialized) as Required<EntityRef>[], [serialized]);
  const query = useQuery({
    queryKey: ["entity-identities", scope.tenant, scope.namespace, serialized],
    queryFn: () => resolveEntityIdentities(normalizedRefs),
    enabled: normalizedRefs.length > 0,
    staleTime: 30_000,
  });
  return useMemo(() => new Map((query.data?.items || []).map((item) => [entityIdentityKey(item), item])), [query.data?.items]);
}

export function getEntityIdentity(identities: EntityIdentityMap, type?: string, ref?: string): EntityIdentity | undefined {
  if (!type || !ref) return undefined;
  return identities.get(entityIdentityKey({ type, ref }));
}

export function entityDisplayName(identities: EntityIdentityMap, type?: string, ref?: string): string {
  if (!ref) return type === "system" ? "AgentScope" : type || "Unknown";
  const identity = getEntityIdentity(identities, type, ref);
  if (identity?.resolved) return identity.name;
  const normalized = normalizeEntityRef({ type, ref });
  if (["human"].includes(normalized.type)) return normalized.ref;
  return normalized.ref.length > 12 ? normalized.ref.slice(0, 8) : normalized.ref;
}

export function EntityIdentityText({
  identities,
  type,
  entityRef,
  secondary = false,
  className,
}: {
  identities: EntityIdentityMap;
  type?: string;
  entityRef?: string;
  secondary?: boolean;
  className?: string;
}) {
  if (!entityRef) return <span className={cn("text-muted-foreground", className)}>—</span>;
  const identity = getEntityIdentity(identities, type, entityRef);
  const name = entityDisplayName(identities, type, entityRef);
  return (
    <span className={cn("min-w-0", className)} title={`${type || "entity"}: ${entityRef}`}>
      <span className="truncate">{name}</span>
      {secondary && identity?.secondary && identity.secondary !== name && (
        <span className="ml-1 text-muted-foreground">· {identity.secondary}</span>
      )}
    </span>
  );
}
