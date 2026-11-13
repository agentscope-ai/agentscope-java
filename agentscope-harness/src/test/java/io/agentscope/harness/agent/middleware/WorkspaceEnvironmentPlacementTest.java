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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prefix-cache contract for the environment block: stable deployment facts stay in the SYSTEM
 * head, while per-call volatile facts (session id, resolved date) ride the tail runtime channel
 * so the head does not shift when they change.
 */
class WorkspaceEnvironmentPlacementTest {

    @Test
    void sessionFactsRideTailRuntimeState_headKeepsStableEnvironment(@TempDir Path workspace)
            throws Exception {
        try (WorkspaceManager wm = new WorkspaceManager(workspace)) {
            WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);
            RuntimeContext rc = RuntimeContext.builder().sessionId("sess-42").build();

            String head = WorkspacePromptTestSupport.render(mw, rc, "BASE");
            WorkspaceContextMaterials materials = rc.get(WorkspaceContextMaterials.class);

            assertTrue(head.contains("## Runtime Environment"));
            assertTrue(head.contains("The host operating system is:"));
            assertFalse(head.contains("We are setting up"), "scaffolding first person must go");
            assertFalse(head.contains("My operating system"), "first person must go");
            assertFalse(head.contains("Today's date"), "the date is per-call state");
            assertFalse(head.contains("sess-42"), "the session id is per-call state");

            ContextItem state =
                    materials.items().stream()
                            .filter(item -> "session_state".equals(item.kind()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("session_state item missing"));
            assertEquals(ContextItem.Placement.RUNTIME, state.placement());
            assertFalse(state.required(), "runtime state must stay droppable under budget");
            assertTrue(state.content().contains("Today's date"));
            assertTrue(state.content().contains("sess-42"));
            assertTrue(
                    state.content().contains("resolved at the start of this conversation turn"),
                    "the date must state its provenance");
        }
    }
}
