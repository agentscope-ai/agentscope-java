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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.ContextManifest;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessSkillMiddlewareContextIsolationTest {

    @TempDir Path workspace;

    @Test
    void usesCallContextWhenSharedActiveContextPointsToAnotherSession() throws IOException {
        RuntimeContext alice =
                RuntimeContext.builder().sessionId("alice-session").put("tenant", "alice").build();
        NamespaceFactory namespaces =
                ctx -> List.of("tenants", ctx != null ? (String) ctx.get("tenant") : "anonymous");
        LocalFilesystem filesystem = new LocalFilesystem(workspace, false, 64, namespaces);
        writeSkill("alice", "alice-skill", "Alice private description", "Alice private body");
        writeSkill("bob", "bob-skill", "Bob private description", "Bob private body");

        // The repository no longer carries a shared "current" context: per-call context flows
        // through mergeRepositories, so alice's catalog never resolves bob's namespace.
        WorkspaceSkillRepository repository =
                new WorkspaceSkillRepository(filesystem, "skills", "workspace", false);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(List.of(repository), new Toolkit());

        String prompt = middleware.onSystemPrompt(null, alice, "").block();

        // The system prompt stays untouched: the skills block rides a tail runtime material so
        // the head keeps byte-stable across catalog changes.
        assertEquals("", prompt);
        WorkspaceContextMaterials materials = alice.get(WorkspaceContextMaterials.class);
        assertNotNull(materials);
        ContextItem skills =
                materials.items().stream()
                        .filter(item -> "harness:skills".equals(item.sourceId()))
                        .findFirst()
                        .orElseThrow();
        String skillsBlock = skills.content();
        assertTrue(skillsBlock.contains("<available_skills>"));
        assertTrue(skillsBlock.contains("Alice private description"));
        assertFalse(skillsBlock.contains("Bob private description"));
        assertEquals(ContextItem.Placement.RUNTIME, skills.placement());
        assertTrue(
                middleware.runtime().currentCatalog(alice).ids().stream()
                        .anyMatch(id -> id.startsWith("alice-skill_")));
        assertFalse(
                middleware.runtime().currentCatalog(alice).ids().stream()
                        .anyMatch(id -> id.startsWith("bob-skill_")));
    }

    @Test
    void skillsRemainVisibleInTheFirstRequestAfterCompaction() throws IOException {
        AgentState state = AgentState.builder().build();
        List<Msg> history =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("x".repeat(20_000)).build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("investigating").build(),
                        Msg.builder().role(MsgRole.USER).textContent("continue").build());
        state.contextMutable().addAll(history);
        RuntimeContext alice =
                RuntimeContext.builder()
                        .sessionId("alice-session")
                        .agentState(state)
                        .put("tenant", "alice")
                        .build();
        NamespaceFactory namespaces = ctx -> List.of("tenants", (String) ctx.get("tenant"));
        LocalFilesystem filesystem = new LocalFilesystem(workspace, false, 64, namespaces);
        writeSkill("alice", "alice-skill", "Alice private description", "Alice private body");
        var middleware =
                new HarnessSkillMiddleware(
                        List.of(
                                new WorkspaceSkillRepository(
                                        filesystem, "skills", "workspace", false)),
                        new Toolkit());
        middleware.onSystemPrompt(null, alice, "").block();

        Model summary =
                new Model() {
                    public String getModelName() {
                        return "summary";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Continue the audit.")
                                                                .build()))
                                        .build());
                    }
                };
        var config =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepMessages(1)
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .build();
        List<ContextManifest> manifests = new ArrayList<>();
        var policy =
                new ContextPolicy(2000, 20, 1, ContextTokenEstimator.approximate(), manifests::add);
        Model main = mock(Model.class);
        when(main.getModelName()).thenReturn("main");
        when(main.getContextWindowSize()).thenReturn(4000);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("audit");

        ModelCallInput prepared =
                new HarnessContextBuilder(
                                policy, new CompactionMiddleware(null, summary, config), null)
                        .prepare(
                                agent,
                                alice,
                                new ModelCallInput(history, List.of(), null, main),
                                "first",
                                ModelRequestPreparer.Purpose.REASONING)
                        .block();

        assertTrue(manifests.get(0).transforms().contains("history_compaction"));
        assertTrue(
                prepared.messages().stream()
                        .anyMatch(
                                msg -> msg.getTextContent().contains("Alice private description")),
                "the first request after compaction must still advertise the available skill");
    }

    private void writeSkill(String tenant, String name, String description, String body)
            throws IOException {
        Path directory =
                workspace.resolve("tenants").resolve(tenant).resolve("skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + body + "\n");
    }
}
