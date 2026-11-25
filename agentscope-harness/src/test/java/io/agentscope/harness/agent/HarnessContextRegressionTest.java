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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.middleware.InboxMiddleware;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.tool.WaitAsyncResultsTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HarnessContextRegressionTest {
    @TempDir Path workspace;
    private final String dynamicSession = UUID.randomUUID().toString();

    @Test
    void ordinaryCompactionKeepsTheLatestUserMessageAfterSeveralToolSteps() {
        Msg constraint =
                user("Use the exact label RELEASE-07. Wait for my approval before publishing.");
        Msg notice =
                user("A background review completed.")
                        .withMetadata(Map.of(Msg.METADATA_SYNTHETIC, true));
        AtomicInteger reasoningCalls = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages -> {
                            int call = reasoningCalls.getAndIncrement();
                            if (call < 3) {
                                return List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("probe-" + call)
                                                                        .name("inspectRelease")
                                                                        .input(Map.of())
                                                                        .content("{}")
                                                                        .build()))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("Ready for approval.")
                                                                    .build()))
                                            .build());
                        });
        MockModel summary = new MockModel("Earlier work and release checks were reviewed.");
        ReleaseProbe probe = new ReleaseProbe();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(probe);
        try (var agent =
                HarnessAgent.builder()
                        .workspace(workspace)
                        .model(model)
                        .toolkit(toolkit)
                        .disableDefaultWorkspaceSkills()
                        .compaction(
                                CompactionConfig.builder()
                                        .model(summary)
                                        .triggerMessages(8)
                                        .keepMessages(2)
                                        .keepTokens(0)
                                        .build())
                        .build()) {
            agent.call(
                            List.of(user("Earlier review notes. ".repeat(300)), constraint, notice),
                            context())
                    .block(Duration.ofSeconds(15));

            assertEquals(3, probe.calls.get());
            assertEquals(4, model.getCallCount());
            assertEquals(1, summary.getCallCount());
            List<Msg> request = model.getLastMessages();
            assertTrue(
                    request.stream()
                            .anyMatch(
                                    m ->
                                            ConversationCompactor.SUMMARY_MSG_NAME.equals(
                                                    m.getName())));
            assertEquals(
                    1,
                    request.stream()
                            .filter(
                                    m ->
                                            m.getRole() == MsgRole.USER
                                                    && constraint.getId().equals(m.getId())
                                                    && constraint
                                                            .getTextContent()
                                                            .equals(m.getTextContent()))
                            .count(),
                    "compacted request must contain the original user message");
            assertFalse(request.stream().anyMatch(m -> notice.getId().equals(m.getId())));
        }
    }

    public static class ReleaseProbe {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "Inspect the release checklist", readOnly = true)
        public String inspectRelease() {
            calls.incrementAndGet();
            return "Release checklist examined.";
        }
    }

    private enum DeliveryScenario {
        NORMAL,
        OLD_SENDER,
        FAILED_WRITE
    }

    @ParameterizedTest
    @CsvSource({
        "false,false,true,NORMAL,false",
        "true,false,true,NORMAL,false",
        "true,true,true,NORMAL,false",
        "false,false,false,NORMAL,false",
        "true,false,true,OLD_SENDER,false",
        "true,true,true,FAILED_WRITE,false",
        "true,true,false,NORMAL,false",
        "true,true,true,NORMAL,true",
        "true,true,false,NORMAL,true",
        "true,true,false,FAILED_WRITE,true"
    })
    void backgroundResultSurvivesCompactionAndIsDeliveredOnce(
            boolean dynamicSubagents,
            boolean overflow,
            boolean journalEnabled,
            DeliveryScenario scenario,
            boolean stream) {
        String result = "registry evidence".repeat(100) + "EVIDENCE_END";
        String legacyPayload = "legacy result copy";
        var calls = new AtomicInteger();
        var model =
                new MockModel(
                        messages -> {
                            if (calls.getAndIncrement() == 0 && overflow) {
                                throw new IllegalStateException("context_length_exceeded");
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });
        var summaryModel = new MockModel("Earlier staging checks were reviewed.");
        var rejectedWrites = new AtomicInteger();
        var filesystem =
                new LocalFilesystem(workspace) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
                        if (scenario == DeliveryScenario.FAILED_WRITE) {
                            for (var file : files) {
                                if (file.getKey().contains("/tasks/")
                                        && new String(file.getValue(), StandardCharsets.UTF_8)
                                                .contains(result)) {
                                    rejectedWrites.incrementAndGet();
                                    return List.of(
                                            FileUploadResponse.fail(file.getKey(), "write failed"));
                                }
                            }
                        }
                        return super.uploadFiles(rc, files);
                    }
                };
        var rc = RuntimeContext.builder().userId("alice").sessionId(dynamicSession).build();
        try (var bus = new WorkspaceMessageBus(filesystem, workspace.resolve("bus").toString())) {
            var builder =
                    HarnessAgent.builder()
                            .workspace(workspace)
                            .abstractFilesystem(filesystem)
                            .messageBus(bus)
                            .journalEnabled(journalEnabled)
                            .model(model)
                            .disableDefaultWorkspaceSkills()
                            .compaction(
                                    CompactionConfig.builder()
                                            .model(summaryModel)
                                            .triggerMessages(overflow ? 1000 : 2)
                                            .triggerTokens(1_000_000)
                                            .keepMessages(1)
                                            .keepTokens(0)
                                            .build());
            if (!dynamicSubagents) builder.disableDynamicSubagents();
            try (var agent = builder.build()) {
                var repository = agent.getTaskRepository();
                repository.putTask(
                        rc,
                        "review",
                        "general-purpose",
                        dynamicSession,
                        new TaskRunSpec.AdoptedTaskRunSpec(
                                CompletableFuture.completedFuture(result)));
                if (scenario == DeliveryScenario.OLD_SENDER) {
                    bus.inboxDrain(dynamicSession, 10).block();
                    bus.inboxPush(dynamicSession, legacySubagentHint("review", legacyPayload))
                            .block();
                }
                if (scenario == DeliveryScenario.FAILED_WRITE) {
                    assertEquals(1, rejectedWrites.get());
                    assertTrue(repository.findPendingDeliveries(rc, dynamicSession).isEmpty());
                }
                var wait = new WaitAsyncResultsTool(bus, repository).waitForResults(1, rc);
                assertTrue(wait.getOutput().toString().contains("Async results have arrived"));
                assertFalse(repository.isDelivered(rc, dynamicSession, "review"));

                invoke(
                        agent,
                        List.of(
                                user("older question " + "x".repeat(4000)),
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .textContent("previous answer " + "x".repeat(4000))
                                        .build(),
                                user("continue")),
                        rc,
                        stream);
                assertEquals(overflow ? 2 : 1, model.getCallCount());
                assertEquals(1, summaryModel.getCallCount());
                for (var request : model.getRecordedMessages()) {
                    var resultMessages =
                            request.stream()
                                    .filter(message -> message.getTextContent().contains(result))
                                    .toList();
                    assertEquals(1, resultMessages.size());
                    String text = resultMessages.get(0).getTextContent();
                    assertEquals(text.indexOf(result), text.lastIndexOf(result));
                    assertTrue(
                            request.stream()
                                    .noneMatch(
                                            message -> {
                                                String body = message.getTextContent();
                                                return body.contains(legacyPayload)
                                                        || body.contains(
                                                                "Automated background event"
                                                                        + " from subagent_task");
                                            }));
                }
                var lastRequest = model.getRecordedMessages().get(model.getCallCount() - 1);
                assertTrue(
                        lastRequest.stream()
                                .anyMatch(
                                        message ->
                                                message.getTextContent()
                                                        .contains(
                                                                "Earlier staging checks were"
                                                                        + " reviewed.")));
                assertTrue(
                        lastRequest.stream()
                                .anyMatch(message -> message.getTextContent().equals("continue")));
                assertEquals(
                        scenario != DeliveryScenario.FAILED_WRITE,
                        repository.isDelivered(rc, dynamicSession, "review"));
                assertEquals(
                        1,
                        agent.getDelegate().getAgentState(rc).getContext().stream()
                                .filter(message -> message.getTextContent().contains(result))
                                .count());
            }
        }
    }

    @Test
    void newSubagentResultIsReadableByLegacyInbox() {
        String result = "worker found a compatible result";
        var filesystem = new LocalFilesystem(workspace);
        var rc = RuntimeContext.builder().userId("alice").sessionId(dynamicSession).build();
        var legacyModel = new MockModel("received");
        try (var bus = new WorkspaceMessageBus(filesystem, workspace.resolve("bus").toString());
                var sender =
                        HarnessAgent.builder()
                                .workspace(workspace)
                                .abstractFilesystem(filesystem)
                                .messageBus(bus)
                                .model(new MockModel("unused"))
                                .disableDefaultWorkspaceSkills()
                                .build();
                // The standalone inbox follows the old receiver contract: only a textual hint.
                var legacyReceiver =
                        ReActAgent.builder()
                                .model(legacyModel)
                                .journalEnabled(false)
                                .middleware(new InboxMiddleware(bus))
                                .build()) {
            sender.getTaskRepository()
                    .putTask(
                            rc,
                            "review",
                            "general-purpose",
                            dynamicSession,
                            new TaskRunSpec.AdoptedTaskRunSpec(
                                    CompletableFuture.completedFuture(result)));
            legacyReceiver.call(List.of(user("continue")), rc).block(Duration.ofSeconds(10));
            var request = legacyModel.getRecordedMessages().get(0);
            assertEquals(
                    1,
                    request.stream()
                            .filter(message -> message.getTextContent().contains(result))
                            .count());
            assertTrue(
                    request.stream()
                            .anyMatch(
                                    message ->
                                            message.getTextContent()
                                                    .contains(
                                                            "Automated background event from"
                                                                    + " subagent_task")));
        }
    }

    private static Map<String, Object> legacySubagentHint(String taskId, String result) {
        return Map.of(
                "type",
                "hint",
                "id",
                "legacy-1",
                "source",
                "subagent_task",
                "hint",
                "<system-reminder>Background subagent task '"
                        + taskId
                        + "' (agent=general-purpose) has completed.\n\nResult:\n\n"
                        + result
                        + "</system-reminder>");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void smallSkillCatalogSupportsDirectLoading(boolean dynamicSkills) throws Exception {
        var names = List.of("release-check", "incident-notes", "test-plan");
        String description =
                "Collect the evidence needed to apply the chosen procedure. "
                        .repeat(4)
                        .stripTrailing();
        String body = "Confirm the release checksum is SAMPLE-27.";
        for (String name : names) {
            Path dir = Files.createDirectories(workspace.resolve("skills").resolve(name));
            Files.writeString(
                    dir.resolve("SKILL.md"),
                    "---\nname: %s\ndescription: %s %s\n---\n%s\n"
                            .formatted(
                                    name,
                                    name,
                                    description,
                                    name.equals("release-check") ? body : "Other procedure."));
        }

        String skillId = "release-check_workspace-namespaced";
        var load =
                ToolUseBlock.builder()
                        .id("load-release-check")
                        .name("load_skill_through_path")
                        .input(Map.of("skillId", skillId, "path", "SKILL.md"))
                        .build();
        var finalText = TextBlock.builder().text("SAMPLE-27").build();
        var calls = new AtomicInteger();
        var model =
                new MockModel(
                        messages -> {
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    calls.getAndIncrement() == 0
                                                            ? List.of(load)
                                                            : List.of(finalText))
                                            .build());
                        });
        var builder =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableSubagents()
                        .disableCompaction();
        if (!dynamicSkills) builder.disableDynamicSkills();

        try (var agent = builder.build()) {
            Msg answer =
                    agent.call(
                                    List.of(
                                            user(
                                                    "Use the release-check skill to find the"
                                                            + " checksum.")),
                                    context())
                            .block(Duration.ofSeconds(10));
            assertEquals("SAMPLE-27", answer.getTextContent());
        }

        var requests = model.getRecordedMessages();
        assertEquals(2, requests.size(), "one load and one final response");
        String catalog =
                requests.get(0).stream()
                        .map(Msg::getTextContent)
                        .filter(text -> text != null && text.contains("<available_skills>"))
                        .findFirst()
                        .orElseThrow();
        for (String name : names) {
            assertTrue(catalog.contains("<name>" + name + "</name>"), catalog);
            assertTrue(catalog.contains(name + " " + description), catalog);
            assertTrue(
                    catalog.contains("<skill-id>" + name + "_workspace-namespaced</skill-id>"),
                    catalog);
        }
        assertFalse(catalog.contains("Catalog entries"));
        assertFalse(catalog.contains("load_skill_through_path(query="));
        assertFalse(catalog.contains(body));

        var results =
                requests.get(1).stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                        .toList();
        assertEquals(1, results.size());
        assertTrue(
                results.get(0).getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .anyMatch(text -> text.getText().contains(body)),
                results.get(0).getOutput().toString());
    }

    @ParameterizedTest
    @CsvSource({
        "false,true,false,true,false",
        "true,true,false,true,false",
        "true,false,false,true,false",
        "false,true,true,true,false",
        "true,true,true,true,false",
        "true,false,true,true,false",
        "false,true,true,false,false",
        "true,true,true,false,false",
        "true,false,true,false,false",
        "false,true,true,true,true",
        "true,true,true,true,true",
        "true,false,true,true,true",
        "false,true,true,false,true",
        "true,true,true,false,true",
        "true,false,true,false,true"
    })
    void compactionFlushRequiresMemoryOptIn(
            boolean memory, boolean flush, boolean overflow, boolean journal, boolean stream) {
        var calls = new AtomicInteger();
        var model =
                new MockModel(
                        messages -> {
                            if (overflow && calls.getAndIncrement() == 0) {
                                throw new IllegalStateException("context_length_exceeded");
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });
        var compactionModel = new MockModel("[user] prefers blue");
        var builder =
                HarnessAgent.builder()
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .journalEnabled(journal)
                        .disableDefaultWorkspaceSkills()
                        .disableMemoryHooks()
                        .compaction(
                                CompactionConfig.builder()
                                        .model(compactionModel)
                                        .triggerMessages(overflow ? 1000 : 2)
                                        .triggerTokens(1_000_000)
                                        .keepMessages(1)
                                        .keepTokens(0)
                                        .flushBeforeCompact(flush)
                                        .build());
        if (memory) builder.enableLongTermMemory();
        var rc = RuntimeContext.builder().userId("alice").sessionId("compaction").build();
        try (var agent = builder.build()) {
            Msg reply =
                    invoke(
                            agent,
                            List.of(
                                    user("old " + "x".repeat(4000)),
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("blue " + "x".repeat(4000))
                                            .build(),
                                    user("continue")),
                            rc,
                            stream);
            assertEquals("done", reply.getTextContent());
            assertEquals(overflow ? 2 : 1, model.getCallCount());
            assertEquals(memory && flush ? 2 : 1, compactionModel.getCallCount());
            var files = agent.getWorkspaceManager().listMemoryFilePaths(rc);
            assertEquals(memory && flush, !files.isEmpty());
            if (!files.isEmpty())
                assertTrue(
                        agent.getWorkspaceManager()
                                .readMemoryFileUtf8(rc, files.get(0))
                                .contains("prefers blue"));
            assertFalse(
                    agent.getDelegate().getAgentState("alice", "compaction").getContext().stream()
                            .anyMatch(m -> m.getTextContent().contains("x".repeat(4000))));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dynamicSpawnResumesInNewRuntimeContext(boolean allow) throws Exception {
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/worker.md"),
                "---\ndescription: worker\n---\nDo work.\n");
        var calls = new AtomicInteger();
        var model =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        calls.getAndIncrement() == 0
                                                                ? List.of(
                                                                        ToolUseBlock.builder()
                                                                                .id("spawn-1")
                                                                                .name("agent_spawn")
                                                                                .input(
                                                                                        Map.of(
                                                                                                "agent_id",
                                                                                                "worker",
                                                                                                "reusable",
                                                                                                true))
                                                                                .build())
                                                                : List.of(
                                                                        TextBlock.builder()
                                                                                .text("done")
                                                                                .build()))
                                                .build()));
        var permissions =
                PermissionContextState.builder()
                        .addAskRule(
                                "agent_spawn",
                                new PermissionRule(
                                        "agent_spawn", null, PermissionBehavior.ASK, "test"))
                        .build();
        try (var agent =
                HarnessAgent.builder()
                        .workspace(workspace)
                        .model(model)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDefaultWorkspaceSkills()
                        .permissionContext(permissions)
                        .build()) {
            var first = agent.call(List.of(user("spawn")), context()).block(Duration.ofSeconds(10));
            assertEquals(GenerateReason.PERMISSION_ASKING, first.getGenerateReason());
            var confirm =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.of(
                                                    new ConfirmResult(
                                                            allow,
                                                            first.getContentBlocks(
                                                                            ToolUseBlock.class)
                                                                    .get(0)))))
                            .build();
            agent.call(List.of(confirm), context()).block(Duration.ofSeconds(10));
            var results =
                    agent.getDelegate().getAgentState("alice", dynamicSession).getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();
            assertEquals(1, results.size());
            assertEquals(
                    allow ? ToolResultState.SUCCESS : ToolResultState.DENIED,
                    results.get(0).getState());
            assertFalse(results.get(0).getOutput().toString().contains("Unknown agent_id"));
            assertEquals(allow, results.get(0).getOutput().toString().contains("agent_key"));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void memoryToolVisibilityInheritedByBothFactories(boolean memory, boolean disabled) {
        var declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("worker")
                        .inlineAgentsBody("work")
                        .build();
        var builder =
                HarnessAgent.builder()
                        .model(new MockModel("ok"))
                        .workspace(workspace)
                        .disableDefaultWorkspaceSkills()
                        .disableMemoryHooks();
        if (memory) builder.enableLongTermMemory();
        if (disabled) builder.disableMemoryTools();
        try (var parent = builder.build();
                var general =
                        (HarnessAgent)
                                HarnessAgentBuilderSupport.buildGeneralPurposeFactory(
                                                builder, workspace, null)
                                        .create(RuntimeContext.empty());
                var declared =
                        (HarnessAgent)
                                HarnessAgentBuilderSupport.buildDeclaredFactory(
                                                builder, declaration, workspace, null)
                                        .create(RuntimeContext.empty())) {
            for (var agent : List.of(parent, general, declared)) {
                assertEquals(
                        !disabled, agent.getToolkit().getToolNames().contains("thread_history"));
                assertEquals(
                        memory && !disabled,
                        agent.getToolkit().getToolNames().contains("memory_search"));
                agent.call(user("hello")).block(Duration.ofSeconds(10));
                var requested =
                        ((MockModel) agent.getDelegate().getModel())
                                .getLastTools().stream().map(schema -> schema.getName()).toList();
                assertEquals(!disabled, requested.contains("thread_history"));
                assertEquals(memory && !disabled, requested.contains("memory_search"));
            }
        }
    }

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("alice").sessionId(dynamicSession).build();
    }

    private static Msg invoke(
            HarnessAgent agent, List<Msg> input, RuntimeContext rc, boolean stream) {
        return (stream
                        ? agent.streamEvents(input, rc)
                                .ofType(AgentResultEvent.class)
                                .single()
                                .map(AgentResultEvent::getResult)
                        : agent.call(input, rc))
                .block(Duration.ofSeconds(15));
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
