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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract markers for the parent-facing {@code ## Subagents} section: it must describe the real
 * lifecycle (one result per invocation, follow-up sessions where supported, persistence depending
 * on declaration and backend) instead of the blanket ephemeral/single-result claim, while keeping
 * the agent_key/task_id distinction, timeout-promotion and push/wait guidance. Marker-level only —
 * behavior is proven by the AgentSpawnTool* tests, not by verbatim prompt snapshots.
 */
class SubagentsMiddlewareSubagentSectionContractTest {

    private static final String SECTION =
            SubagentsMiddleware.renderSubagentSection(List.of(), false);

    @Test
    void noBlanketEphemeralClaim_lifecycleIsPerInvocation() {
        assertFalse(SECTION.contains("are ephemeral"), SECTION);
        assertTrue(SECTION.contains("invocation"), SECTION);
        assertTrue(SECTION.contains("follow-up"), SECTION);
        assertTrue(SECTION.contains("persistSession"), SECTION);
        assertTrue(SECTION.contains("backend"), SECTION);
        assertTrue(SECTION.contains("remote backends may start a new"), SECTION);
    }

    @Test
    void taskMustBeSelfContained_childDoesNotSeeParentConversation() {
        assertTrue(SECTION.contains("self-contained"), SECTION);
        assertTrue(SECTION.contains("fresh conversation"), SECTION);
    }

    @Test
    void handleAndDeliveryGuidanceIsPreserved() {
        assertTrue(SECTION.contains("agent_key"), SECTION);
        assertTrue(SECTION.contains("task_id"), SECTION);
        assertTrue(SECTION.contains("timeout_promoted"), SECTION);
        assertTrue(SECTION.contains("Do NOT retry"), SECTION);
        assertTrue(SECTION.contains("task_output"), SECTION);
        assertTrue(SECTION.contains("wait_async_results"), SECTION);
        assertFalse(
                SECTION.contains("### Available agent ids"),
                "the catalog is runtime state and must stay out of the stable section");
    }
}
