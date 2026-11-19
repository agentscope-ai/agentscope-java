/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.context;

import io.agentscope.core.agent.RuntimeContext;
import java.util.LinkedHashMap;
import java.util.List;

/** Per-agent-call authorized workspace snapshot. Never shared in a global cross-user cache. */
public record WorkspaceContextMaterials(List<ContextItem> items) {
    public WorkspaceContextMaterials {
        items = List.copyOf(items);
    }

    /** Replace sources by ID, preserving other providers in this call-scoped snapshot. */
    public static void register(RuntimeContext context, List<ContextItem> additions) {
        var merged = new LinkedHashMap<String, ContextItem>();
        var previous = context.get(WorkspaceContextMaterials.class);
        if (previous != null) previous.items().forEach(item -> merged.put(item.sourceId(), item));
        additions.forEach(item -> merged.put(item.sourceId(), item));
        context.put(
                WorkspaceContextMaterials.class,
                new WorkspaceContextMaterials(List.copyOf(merged.values())));
    }
}
