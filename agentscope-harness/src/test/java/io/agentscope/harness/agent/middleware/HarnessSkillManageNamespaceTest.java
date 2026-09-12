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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Regression tests for namespaced {@code skill_manage} operations: with the runId-indexed
 * active-context registry, a call's {@link RuntimeContext} must still drive the namespace of
 * skill create/edit/delete — either resolved from the single active call (legacy supplier path)
 * or taken from the tool call's own {@link ToolCallParam#getRuntimeContext()} under concurrency.
 */
class HarnessSkillManageNamespaceTest {

    @TempDir Path workspace;

    @Test
    void skillManageCreateEditDeleteFollowTheActiveCallNamespace() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test Agent\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(neverRespondingModel())
                        .workspace(workspace)
                        .enableSkillManageTool(SkillManageConfig.defaults())
                        .build()) {

            RuntimeContext alice = RuntimeContext.builder().sessionId("s1").userId("alice").build();

            Disposable call =
                    agent.call(userMsg("hi"), alice)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
            try {
                awaitActive(agent, alice.getRunId());
                AgentTool skillManage = agent.getToolkit().getTool("skill_manage");
                assertNotNull(skillManage, "skill_manage tool should be registered");

                // ---- create: lands under alice's namespace, drafts dir by default ----
                ToolResultBlock created =
                        skillManage
                                .callAsync(
                                        paramOf(
                                                alice,
                                                args(
                                                        "action", "create",
                                                        "name", "ns-probe",
                                                        "content",
                                                                skillMd(
                                                                        "ns-probe",
                                                                        "Probe skill"))))
                                .block(Duration.ofSeconds(30));
                assertNotNull(created);
                assertFalse(isError(created), "create failed: " + text(created));

                Path aliceDraft = workspace.resolve("alice/skills/_drafts/ns-probe/SKILL.md");
                assertTrue(Files.isRegularFile(aliceDraft), "draft should be in alice's namespace");
                assertTrue(
                        Files.readString(aliceDraft).contains("Probe skill"),
                        "draft content should be persisted");
                assertFalse(
                        Files.exists(workspace.resolve("skills/_drafts/ns-probe")),
                        "no default-namespace leak when the active call has a userId");

                // ---- edit: overwrite stays in alice's namespace ----
                ToolResultBlock edited =
                        skillManage
                                .callAsync(
                                        paramOf(
                                                alice,
                                                args(
                                                        "action", "edit",
                                                        "name", "ns-probe",
                                                        "content",
                                                                skillMd(
                                                                        "ns-probe",
                                                                        "Edited skill"))))
                                .block(Duration.ofSeconds(30));
                assertNotNull(edited);
                assertFalse(isError(edited), "edit failed: " + text(edited));
                assertTrue(
                        Files.readString(aliceDraft).contains("Edited skill"),
                        "edit should overwrite the draft in place");
                assertFalse(
                        Files.exists(workspace.resolve("skills/_drafts/ns-probe")),
                        "edit must not leak into the default namespace");

                // ---- delete: archived within alice's namespace ----
                ToolResultBlock deleted =
                        skillManage
                                .callAsync(
                                        paramOf(
                                                alice,
                                                args("action", "delete", "name", "ns-probe")))
                                .block(Duration.ofSeconds(30));
                assertNotNull(deleted);
                assertFalse(isError(deleted), "delete failed: " + text(deleted));
                assertFalse(Files.exists(aliceDraft), "draft should be archived away");
                Path archiveDir = workspace.resolve("alice/skills/_drafts/.archive");
                assertTrue(
                        Files.isDirectory(archiveDir)
                                && Files.list(archiveDir)
                                        .anyMatch(
                                                p ->
                                                        p.getFileName()
                                                                .toString()
                                                                .startsWith("ns-probe")),
                        "archived copy should live under alice's namespace");
            } finally {
                call.dispose();
            }
        }
    }

    @Test
    void concurrentCallsSkillManageUsesEachToolCallOwnNamespace() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test Agent\n");

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(neverRespondingModel())
                        .workspace(workspace)
                        .enableSkillManageTool(SkillManageConfig.defaults())
                        .build()) {

            RuntimeContext alice = RuntimeContext.builder().sessionId("s1").userId("alice").build();
            RuntimeContext bob = RuntimeContext.builder().sessionId("s2").userId("bob").build();

            Disposable callAlice =
                    agent.call(userMsg("hi"), alice)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
            Disposable callBob =
                    agent.call(userMsg("hi"), bob)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
            try {
                awaitActive(agent, alice.getRunId());
                awaitActive(agent, bob.getRunId());
                assertEqualsBoth(
                        2,
                        agent.getActiveRuntimeContexts().size(),
                        "both calls should be in flight");

                AgentTool skillManage = agent.getToolkit().getTool("skill_manage");
                assertNotNull(skillManage);

                // With two active contexts the shared supplier is ambiguous, but the tool call's
                // own RuntimeContext is not: each create lands in its own namespace.
                ToolResultBlock forBob =
                        skillManage
                                .callAsync(
                                        paramOf(
                                                bob,
                                                args(
                                                        "action", "create",
                                                        "name", "bobs-skill",
                                                        "content",
                                                                skillMd("bobs-skill", "Bob only"))))
                                .block(Duration.ofSeconds(30));
                assertNotNull(forBob);
                assertFalse(isError(forBob), "create under concurrency failed: " + text(forBob));

                assertTrue(
                        Files.isRegularFile(
                                workspace.resolve("bob/skills/_drafts/bobs-skill/SKILL.md")),
                        "bob's skill must land in bob's namespace");
                assertFalse(
                        Files.exists(workspace.resolve("alice/skills/_drafts/bobs-skill")),
                        "bob's skill must not leak into alice's namespace");
                assertFalse(
                        Files.exists(workspace.resolve("skills/_drafts/bobs-skill")),
                        "bob's skill must not leak into the default namespace");
            } finally {
                callAlice.dispose();
                callBob.dispose();
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void assertEqualsBoth(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(
                    message + " (expected " + expected + " but was " + actual + ")");
        }
    }

    private static Model neverRespondingModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        AtomicInteger id = new AtomicInteger();
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            ChatResponse chunk =
                                    new ChatResponse(
                                            "stub-" + id.incrementAndGet(),
                                            List.of(TextBlock.builder().text("parked").build()),
                                            null,
                                            Map.of(),
                                            "stop");
                            // Keeps the call in flight (its RC registered) until disposed.
                            return Flux.just(chunk).delayElements(Duration.ofMinutes(10));
                        });
        return model;
    }

    private static void awaitActive(HarnessAgent agent, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (agent.getRuntimeContext(runId) == null) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "RuntimeContext for runId " + runId + " never became active");
            }
            Thread.sleep(10);
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static ToolCallParam paramOf(RuntimeContext ctx, Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(
                        io.agentscope.core.message.ToolUseBlock.builder()
                                .id("tc-" + System.nanoTime())
                                .name("skill_manage")
                                .build())
                .input(input)
                .runtimeContext(ctx)
                .build();
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String skillMd(String name, String description) {
        return "---\nname: "
                + name
                + "\ndescription: "
                + description
                + "\n---\n# "
                + name
                + "\nBody.\n";
    }

    private static boolean isError(ToolResultBlock block) {
        return text(block).startsWith("Error:");
    }

    private static String text(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        if (block.getOutput() == null) {
            return "";
        }
        block.getOutput()
                .forEach(
                        b -> {
                            if (b instanceof TextBlock t) {
                                sb.append(t.getText());
                            }
                        });
        return sb.toString();
    }
}
