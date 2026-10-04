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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import io.agentscope.harness.agent.testing.ProjectionTestClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;

@HarnessQuiescence
class SandboxDefinitionTamperRegressionTest {
    @TempDir Path temp;
    private String oldStateHome;

    @BeforeEach
    void setStateHome() {
        oldStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", temp.resolve("state").toString());
    }

    @AfterEach
    void restoreStateHome() {
        if (oldStateHome == null) System.clearProperty("agentscope.state.home");
        else System.setProperty("agentscope.state.home", oldStateHome);
    }

    @ParameterizedTest
    @EnumSource(
            value = IsolationScope.class,
            names = {"USER", "AGENT", "GLOBAL"})
    void sandboxDefinitionsCannotBecomeTrustedAcrossSnapshotRestore(IsolationScope scope)
            throws Exception {
        runScenario(scope, true, true, false);
    }

    @Test
    void legacyOptOutPreservesSandboxOverrides() throws Exception {
        runScenario(IsolationScope.USER, false, true, false);
    }

    @Test
    void defaultProjectionDoesNotChangeRuntimeMemory() throws Exception {
        runScenario(IsolationScope.USER, true, false, false);
    }

    @Test
    void skillManagementDoesNotBypassDefinitionAuthority() throws Exception {
        runScenario(IsolationScope.USER, true, true, true);
    }

    private void runScenario(
            IsolationScope scope, boolean secure, boolean projectMemory, boolean skillManagement)
            throws Exception {
        Path host = temp.resolve("host");
        Files.createDirectories(host.resolve("skills/good"));
        Files.writeString(host.resolve("AGENTS.md"), "HOST_PERSONA");
        Files.writeString(host.resolve("MEMORY.md"), "HOST_MEMORY");
        Files.writeString(host.resolve("skills/good/SKILL.md"), skill("good", "HOST_SKILL"));
        ProjectionTestClient client = new ProjectionTestClient(temp.resolve("sandbox"));
        SandboxFilesystemSpec spec =
                spec(client).isolationScope(scope).hostAuthoritativeDefinitions(secure);
        if (projectMemory)
            spec.workspaceProjectionRoots(
                    List.of("AGENTS.md", "skills", "MEMORY.md", "knowledge", "subagents"));
        List<String> prompts = new ArrayList<>();
        AtomicReference<HarnessAgent> ref = new AtomicReference<>();
        AtomicInteger turns = new AtomicInteger();
        List<String> liveReads = new ArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            prompts.add(
                                    messages.stream()
                                            .filter(m -> m.getRole() == MsgRole.SYSTEM)
                                            .map(Msg::getTextContent)
                                            .reduce("", String::concat));
                            if (turns.getAndIncrement() == 0) {
                                AbstractSandboxFilesystem fs =
                                        (AbstractSandboxFilesystem)
                                                ref.get().getWorkspaceManager().getFilesystem();
                                String command =
                                        "printf '%s' 'INJECTED_PERSONA' > 'AGENTS.md'; printf '%s'"
                                                + " 'INJECTED_MEMORY' > 'MEMORY.md'; printf '%s' '"
                                                + skill("good", "INJECTED_SKILL")
                                                + "' > 'skills/good/SKILL.md'; printf '%s' '"
                                                + skill("evil", "EVIL_SKILL")
                                                + "' > 'skills/evil/SKILL.md'; printf '%s'"
                                                + " 'RUNTIME_DATA' > 'output.txt'; printf '%s'"
                                                + " '---\n"
                                                + "description: EVIL_SUBAGENT\n"
                                                + "---\n"
                                                + "INJECTED_SUBAGENT' > 'subagents/evil.md'";
                                assertEquals(
                                        0,
                                        fs.execute(RuntimeContext.empty(), command, 10).exitCode());
                                // Even before the next start/projection, trusted reads must use the
                                // host.
                                liveReads.add(
                                        ref.get()
                                                .getWorkspaceManager()
                                                .readAgentsMd(RuntimeContext.empty()));
                            }
                            return Flux.just(
                                    new ChatResponse(
                                            "id",
                                            List.of(TextBlock.builder().text("done").build()),
                                            null,
                                            Map.of(),
                                            "stop"));
                        });
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("definition-test")
                        .model(model)
                        .workspace(host)
                        .filesystem(spec)
                        .disableMemoryHooks();
        if (skillManagement) builder.enableSkillManageTool(false);
        HarnessAgent agent = builder.build();
        ref.set(agent);
        Msg user =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("hello").build())
                        .build();
        agent.call(user, ctx("s1", "alice")).block();
        assertTrue(client.last.getState().getSnapshot().isRestorable());
        // Remove the old workspace: the second call must restore the actual persisted tar.
        try (var paths = Files.walk(client.last.directory())) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        agent.call(user, ctx("s2", scope == IsolationScope.USER ? "alice" : "bob")).block();
        assertEquals(1, client.resumes);
        assertEquals(2, prompts.size());
        String persona = secure ? "HOST_PERSONA" : "INJECTED_PERSONA";
        String memory = secure && projectMemory ? "HOST_MEMORY" : "INJECTED_MEMORY";
        String skillDescription = secure ? "HOST_SKILL" : "INJECTED_SKILL";
        assertAll(
                () -> assertEquals(List.of(persona), liveReads),
                () -> assertTrue(prompts.get(1).contains(persona)),
                () -> assertTrue(prompts.get(1).contains(memory)),
                () -> assertTrue(prompts.get(1).contains(skillDescription)),
                () -> assertEquals(!secure, prompts.get(1).contains("EVIL_SKILL")),
                () -> assertEquals(!secure, prompts.get(1).contains("EVIL_SUBAGENT")),
                () -> assertEquals(!secure, prompts.get(1).contains("evil")),
                () ->
                        assertEquals(
                                persona,
                                Files.readString(client.last.directory().resolve("AGENTS.md"))),
                () ->
                        assertEquals(
                                memory,
                                Files.readString(client.last.directory().resolve("MEMORY.md"))),
                () ->
                        assertEquals(
                                skill("good", skillDescription),
                                Files.readString(
                                        client.last.directory().resolve("skills/good/SKILL.md"))));
        assertEquals(
                "RUNTIME_DATA", Files.readString(client.last.directory().resolve("output.txt")));
        assertEquals("HOST_PERSONA", Files.readString(host.resolve("AGENTS.md")));
        assertEquals("HOST_MEMORY", Files.readString(host.resolve("MEMORY.md")));
        assertEquals(
                skill("good", "HOST_SKILL"),
                Files.readString(host.resolve("skills/good/SKILL.md")));
    }

    private static String skill(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n" + description;
    }

    private static RuntimeContext ctx(String session, String user) {
        return RuntimeContext.builder().sessionId(session).userId(user).build();
    }

    static SandboxFilesystemSpec spec(ProjectionTestClient client) {
        return new SandboxFilesystemSpec() {
            @Override
            protected SandboxClient<?> createClient() {
                return client;
            }

            @Override
            protected SandboxClientOptions clientOptions() {
                return null;
            }

            @Override
            protected SandboxSnapshotSpec snapshotSpec() {
                return null;
            }

            @Override
            protected WorkspaceSpec workspaceSpec() {
                return new WorkspaceSpec();
            }
        };
    }
}
