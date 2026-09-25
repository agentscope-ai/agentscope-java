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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@code disableKnowledgeContext()} set on the main agent is propagated to the
 * automatic subagent factories (general-purpose and declared), covering both branches of the
 * flag checks in {@code HarnessAgentBuilderSupport}.
 */
@HarnessQuiescence
class DisableKnowledgeContextSubagentPropagationTest {

    @TempDir Path workspace;

    @Test
    void generalPurposeSubagent_inheritsDisabledKnowledgeContext() throws Exception {
        HarnessAgent.Builder parent =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .disableKnowledgeContext();

        SubagentEntry entry =
                parent.buildSubagentEntries(workspace).stream()
                        .filter(e -> "general-purpose".equals(e.name()))
                        .findFirst()
                        .orElseThrow();

        try (HarnessAgent child = (HarnessAgent) createChild(entry)) {
            WorkspaceContextMiddleware mw = knowledgeMiddleware(child);
            assertTrue(mw.isDisableKnowledgeContext());
        }
    }

    @Test
    void declaredSubagent_inheritsDisabledKnowledgeContext() throws Exception {
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("reviewer")
                        .description("Reviews code")
                        .inlineAgentsBody("Review without modifying files.")
                        .workspaceMode(WorkspaceMode.SHARED)
                        .build();

        HarnessAgent.Builder parent =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .subagent(declaration)
                        .disableKnowledgeContext();

        SubagentEntry entry =
                parent.buildSubagentEntries(workspace).stream()
                        .filter(e -> declaration.getName().equals(e.name()))
                        .findFirst()
                        .orElseThrow();

        try (HarnessAgent child = (HarnessAgent) createChild(entry)) {
            WorkspaceContextMiddleware mw = knowledgeMiddleware(child);
            assertTrue(mw.isDisableKnowledgeContext());
        }
    }

    @Test
    void generalPurposeSubagent_defaultsToKnowledgeEnabled() throws Exception {
        HarnessAgent.Builder parent =
                HarnessAgent.builder()
                        .model(new MockModel("unused"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore());

        SubagentEntry entry =
                parent.buildSubagentEntries(workspace).stream()
                        .filter(e -> "general-purpose".equals(e.name()))
                        .findFirst()
                        .orElseThrow();

        try (HarnessAgent child = (HarnessAgent) createChild(entry)) {
            WorkspaceContextMiddleware mw = knowledgeMiddleware(child);
            assertFalse(mw.isDisableKnowledgeContext());
        }
    }

    private Object createChild(SubagentEntry entry) {
        RuntimeContext context =
                RuntimeContext.builder().userId("user").sessionId("child-session").build();
        return entry.factory().create(context);
    }

    private WorkspaceContextMiddleware knowledgeMiddleware(HarnessAgent child) {
        List<MiddlewareBase> middlewares = child.getDelegate().getMiddlewares();
        for (MiddlewareBase mw : middlewares) {
            if (mw instanceof WorkspaceContextMiddleware wcm) {
                return wcm;
            }
        }
        throw new AssertionError(
                "WorkspaceContextMiddleware not found among child middlewares: " + middlewares);
    }
}
