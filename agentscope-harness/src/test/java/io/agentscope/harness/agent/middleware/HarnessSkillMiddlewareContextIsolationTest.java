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
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessSkillMiddlewareContextIsolationTest {

    @TempDir Path workspace;

    @Test
    void largeCatalogIsNotAbbreviatedWhenTheLoaderIsHiddenOrExternal() throws IOException {
        for (int i = 0; i < 100; i++) {
            Path skill = Files.createDirectories(workspace.resolve("skills/skill-" + i));
            Files.writeString(
                    skill.resolve("SKILL.md"),
                    "---\nname: skill-"
                            + i
                            + "\ndescription: "
                            + "full description ".repeat(30)
                            + "\n---\nbody");
        }
        var fs = new LocalFilesystem(workspace);
        var repository = new WorkspaceSkillRepository(fs, "skills", "workspace", false);
        var toolkit = new Toolkit();
        var middleware = new HarnessSkillMiddleware(List.of(repository), toolkit);
        Agent agent = mock(Agent.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        var external =
                new SchemaOnlyTool(
                        ToolSchema.builder()
                                .name("load_skill_through_path")
                                .description("external")
                                .parameters(Map.of("type", "object"))
                                .build());
        for (var request :
                List.of(
                        ToolRequestConfig.NONE,
                        new ToolRequestConfig(Map.of(), ToolMergeMode.EXTERNAL_ONLY),
                        new ToolRequestConfig(
                                Map.of("load_skill_through_path", external),
                                ToolMergeMode.MERGE_EXTERNAL_PRIORITY))) {
            var rc = RuntimeContext.builder().toolRequestConfig(request).build();
            middleware.onSystemPrompt(agent, rc, "").block();
            String rendered =
                    rc.get(WorkspaceContextMaterials.class).items().stream()
                            .filter(i -> i.sourceId().equals("harness:skills"))
                            .findFirst()
                            .orElseThrow()
                            .content();
            if (request == ToolRequestConfig.NONE) assertTrue(rendered.length() <= 12000);
            else {
                assertTrue(rendered.contains("<name>skill-99</name>"));
                assertTrue(rendered.contains("full description ".repeat(29)));
                assertFalse(rendered.contains("Catalog entries"));
            }
        }
    }

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

        // The context builder receives the catalog separately from system instructions.
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
                        .anyMatch(id -> id.startsWith("alice-skill")));
        assertFalse(
                middleware.runtime().currentCatalog(alice).ids().stream()
                        .anyMatch(id -> id.startsWith("bob-skill")));
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
        Msg catalog = prepared.messages().get(0);
        assertEquals(MsgRole.USER, catalog.getRole());
        assertTrue(
                catalog.getTextContent().contains("Alice private description"),
                "the first request after compaction should start with the catalog before history");
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
