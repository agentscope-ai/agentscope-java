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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Grant-layer coverage of the harness {@code capabilityResolver(...)} pass-through: the
 * resolver runs once per call at the harness entry, the resolved surface trims the
 * model-visible tools, a pre-existing (inherited) surface is never overwritten in place, and
 * marketplace skill staging already sees the resolved surface — closing the prestage timing
 * hole where ungranted skill files were materialised into {@code .skills-cache} before the
 * surface reached the rc.
 *
 * @author shanhongyu
 */
@HarnessQuiescence
class HarnessAgentCapabilityResolverTest {

    @TempDir Path workspace;

    /** Captures the tool schemas the model was offered per reasoning round. */
    private static final class RecordingModel implements Model {
        private final List<List<ToolSchema>> toolBatches = new CopyOnWriteArrayList<>();
        private final String reply;

        RecordingModel(String reply) {
            this.reply = reply;
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            toolBatches.add(List.copyOf(tools));
            ChatResponse chunk =
                    new ChatResponse(
                            "stub-id",
                            List.of(TextBlock.builder().text(reply).build()),
                            null,
                            Map.of(),
                            "stop");
            return Flux.just(chunk);
        }
    }

    private static AgentTool mockTool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn("mock tool " + name);
        when(tool.getParameters()).thenReturn(Map.of("type", "object", "properties", Map.of()));
        return tool;
    }

    private static Msg userText(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private HarnessAgent.Builder baseBuilder(RecordingModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(mockTool("secret_probe"));
        return HarnessAgent.builder()
                .name("grant-harness")
                .model(model)
                .workspace(workspace)
                .toolkit(toolkit)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .disableMemoryTools()
                .disableMemoryHooks();
    }

    private static boolean offered(RecordingModel model, String toolName) {
        return model.toolBatches.stream()
                .flatMap(List::stream)
                .anyMatch(t -> toolName.equals(t.getName()));
    }

    @Test
    void resolverRunsOnceAndTrimsModelVisibleTools() {
        RecordingModel model = new RecordingModel("ok");
        AtomicInteger resolutions = new AtomicInteger();
        HarnessAgent agent =
                baseBuilder(model)
                        .capabilityResolver(
                                (ctx, a) -> {
                                    resolutions.incrementAndGet();
                                    // Tools dimension explicitly empty: zero named-tool grants.
                                    return new CapabilitySurface(null, null, Set.of());
                                })
                        .build();

        agent.call(
                        List.of(userText("hi")),
                        RuntimeContext.builder().userId("u1").sessionId("s1").build())
                .block();

        assertEquals(1, resolutions.get(), "resolver must run exactly once per call");
        assertTrue(!offered(model, "secret_probe"), "denied tool must not reach the model");
    }

    @Test
    void resolverNullResultMeansNoTrimming() {
        RecordingModel model = new RecordingModel("ok");
        HarnessAgent agent = baseBuilder(model).capabilityResolver((ctx, a) -> null).build();

        agent.call(
                        List.of(userText("hi")),
                        RuntimeContext.builder().userId("u1").sessionId("s1").build())
                .block();

        assertTrue(offered(model, "secret_probe"), "null surface = no trimming");
    }

    @Test
    void throwingResolverAbortsTheCall() {
        RecordingModel model = new RecordingModel("ok");
        HarnessAgent agent =
                baseBuilder(model)
                        .capabilityResolver(
                                (ctx, a) -> {
                                    throw new IllegalStateException("authz down");
                                })
                        .build();

        assertThrows(
                IllegalStateException.class,
                () ->
                        agent.call(
                                        List.of(userText("hi")),
                                        RuntimeContext.builder()
                                                .userId("u1")
                                                .sessionId("s1")
                                                .build())
                                .block());
    }

    @Test
    void inheritedSurfaceIsUsedButNeverOverwrittenInPlace() {
        RecordingModel model = new RecordingModel("ok");
        // Pre-seeded surface (as a parent agent would leave on the rc): denies the tool.
        CapabilitySurface inherited = new CapabilitySurface(null, null, Set.of());
        // The harness's own resolver grants everything.
        HarnessAgent agent = baseBuilder(model).capabilityResolver((ctx, a) -> null).build();
        RuntimeContext rc = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        rc.put(CapabilitySurface.class, inherited);

        agent.call(List.of(userText("hi")), rc).block();

        assertTrue(
                offered(model, "secret_probe"),
                "the harness resolver's surface must be the effective one for this call");
        assertSame(
                inherited,
                rc.get(CapabilitySurface.class),
                "a derived copy must be used; the caller's rc is never written in place");
    }

    @Test
    void marketplacePrestageAlreadySeesTheResolvedSurface() throws Exception {
        Path ws = workspace;
        Files.createDirectories(ws);
        AgentSkill granted =
                new AgentSkill(
                        "alpha",
                        "granted skill",
                        "# alpha",
                        Map.of("run.sh", "echo alpha"),
                        "market");
        AgentSkill denied =
                new AgentSkill(
                        "beta", "denied skill", "# beta", Map.of("run.sh", "echo beta"), "market");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("staging-harness")
                        .model(new RecordingModel("ok"))
                        .workspace(ws.toAbsolutePath().normalize().toString())
                        .filesystem(new InMemorySandboxFilesystemSpec())
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(
                                        new LocalFilesystem(ws.resolve("journal"))))
                        .skillRepositories(
                                List.of(new StubRepo(List.of(granted, denied), "market")))
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .capabilityResolver(
                                (ctx, a) -> new CapabilitySurface(Set.of("alpha"), null, null))
                        .build();

        agent.call(
                        List.of(userText("hi")),
                        RuntimeContext.builder().userId("u1").sessionId("s1").build())
                .block();

        Path cache = ws.resolve(".skills-cache").resolve("u1").resolve("market");
        assertTrue(
                Files.exists(cache.resolve("alpha").resolve("run.sh")),
                "granted skill resources must be staged for sandbox projection");
        assertTrue(
                Files.notExists(cache.resolve("beta")),
                "ungranted skill files must NOT be materialised into .skills-cache");
    }

    /** Minimal repository stub serving a fixed skill list from one source. */
    private static final class StubRepo implements AgentSkillRepository {
        private final List<AgentSkill> skills;
        private final String source;

        StubRepo(List<AgentSkill> skills, String source) {
            this.skills = skills;
            this.source = source;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        }

        @Override
        public List<String> getAllSkillNames() {
            return skills.stream().map(AgentSkill::getName).toList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skills;
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return skills.stream().anyMatch(s -> s.getName().equals(skillName));
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(source, "", false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
