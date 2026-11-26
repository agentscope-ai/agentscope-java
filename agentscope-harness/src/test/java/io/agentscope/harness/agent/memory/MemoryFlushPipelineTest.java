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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** A deterministic pipeline test, not evidence that a real LLM follows provenance rules. */
class MemoryFlushPipelineTest {
    @TempDir Path workspace;

    @Test
    void datedProvenanceAndBothConflictSidesFlowThroughDailyConsolidationAndNewSessionRead() {
        String oldOwner = "owner=Alice; source=user/old-session; date=2026-09-01";
        String newOwner = "owner=Bob; source=user/current-session; date=2026-09-08";
        String preference = "preference=tea; source=user/current-session; date=2026-09-08";
        String observed = "region=eu-west; source=file/config.yaml; date=2026-09-08";
        String inferred = "hypothesis=latency; source=assistant/current-session; date=2026-09-08";
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("same-user")
                        .sessionId("current-session")
                        .put("tenant", "first")
                        .put(
                                Clock.class,
                                Clock.fixed(Instant.parse("2026-09-08T10:15:30Z"), ZoneOffset.UTC))
                        .build();
        RuntimeContext other = RuntimeContext.builder(rc).put("tenant", "other").build();
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem filesystem =
                new RemoteFilesystem(
                        store,
                        context -> List.of("memory-pipeline", context.get("tenant", String.class)));
        try (WorkspaceManager manager = new WorkspaceManager(workspace, filesystem)) {
            manager.writeMemoryFileUtf8(rc, "MEMORY.md", "- [user] " + oldOwner);
            manager.writeMemoryFileUtf8(other, "MEMORY.md", "other private memory");
            manager.writeMemoryFileUtf8(other, "memory/2026-09-08.md", "other private daily");
            manager.writeMemoryFileUtf8(
                    other, "memory/.consolidation_state", "2026-09-07T00:00:00Z");
            List<String> otherNamespace = List.of("memory-pipeline", "other");
            var beforeMemory = store.get(otherNamespace, "MEMORY.md");
            var beforeDaily = store.get(otherNamespace, "memory/2026-09-08.md");
            var beforeState = store.get(otherNamespace, "memory/.consolidation_state");

            var inputs = new ArrayList<List<Msg>>();
            Model fake =
                    new Model() {
                        @Override
                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            inputs.add(List.copyOf(messages));
                            assertEquals(2, messages.size());
                            assertTrue(tools.isEmpty());
                            String system = messages.get(0).getTextContent();
                            String payload = messages.get(1).getTextContent();
                            String body;
                            if (inputs.size() == 1) {
                                assertTrue(system.contains("Label every fact with its provenance"));
                                assertTrue(system.contains("keep both entries"));
                                assertTrue(system.contains("ISO date"));
                                assertTrue(payload.contains("MEMORY.md (read-only"));
                                assertTrue(payload.contains("- [user] " + oldOwner));
                                assertTrue(payload.contains("[USER]: " + newOwner));
                                assertTrue(
                                        payload.contains(
                                                "[TOOL]: [tool_result: read_file] " + observed));
                                assertTrue(payload.contains("[ASSISTANT]: " + inferred));
                                // Derive output from the actual supplied conversation, never from a
                                // prewritten successful answer that could hide missing inputs.
                                body =
                                        "- [user] "
                                                + fact(payload, "owner", 1)
                                                + "\n- [user] "
                                                + fact(payload, "preference", 0)
                                                + "\n- [observed] "
                                                + fact(payload, "region", 0)
                                                + "\n- [inferred] "
                                                + fact(payload, "hypothesis", 0);
                            } else {
                                assertEquals(
                                        2,
                                        inputs.size(),
                                        "only flush and consolidation model calls");
                                assertTrue(system.contains("Preserve provenance markers"));
                                assertTrue(
                                        system.contains(
                                                "keep both entries with their sources and dates"));
                                assertTrue(payload.contains("### 2026-09-08.md"));
                                assertTrue(payload.contains("Memory Flush — 2026-09-08T10:15:30Z"));
                                assertTrue(payload.contains("- [user] " + oldOwner));
                                assertTrue(payload.contains("- [user] " + newOwner));
                                assertTrue(payload.contains("- [observed] " + observed));
                                assertTrue(payload.contains("- [inferred] " + inferred));
                                body =
                                        payload.lines()
                                                .filter(line -> line.startsWith("- ["))
                                                .distinct()
                                                .collect(Collectors.joining("\n"));
                                assertTrue(body.contains(oldOwner) && body.contains(newOwner));
                            }
                            int middle = body.length() / 2;
                            return Flux.just(
                                    response(body.substring(0, middle), null),
                                    response(body.substring(middle), "stop"));
                        }

                        @Override
                        public String getModelName() {
                            return "fake-provenance-pipeline";
                        }
                    };
            List<Msg> conversation =
                    List.of(
                            Msg.builderForRole(MsgRole.USER)
                                    .content(
                                            TextBlock.builder()
                                                    .text(newOwner + "\n" + preference)
                                                    .build())
                                    .build(),
                            Msg.builderForRole(MsgRole.TOOL)
                                    .content(
                                            ToolResultBlock.of(
                                                    "file-observation",
                                                    "read_file",
                                                    TextBlock.builder().text(observed).build()))
                                    .build(),
                            Msg.builderForRole(MsgRole.ASSISTANT)
                                    .content(TextBlock.builder().text(inferred).build())
                                    .build());
            new MemoryFlushManager(manager, fake).flushMemories(rc, conversation).block();
            String daily = manager.readMemoryFileUtf8(rc, "memory/2026-09-08.md");
            assertTrue(daily.contains("Memory Flush — 2026-09-08T10:15:30Z"));
            assertTrue(daily.contains("- [user] " + newOwner));
            assertTrue(daily.contains("- [user] " + preference));
            assertTrue(daily.contains("- [observed] " + observed));
            assertTrue(daily.contains("- [inferred] " + inferred));
            assertFalse(
                    daily.contains(oldOwner),
                    "the older conflict side comes from curated memory, not the fake flush answer");
            assertEquals("- [user] " + oldOwner, manager.readMemoryMd(rc));

            MemoryConsolidator consolidator = new MemoryConsolidator(manager, fake);
            consolidator.consolidate(rc).block();
            assertTrue(consolidator.readWatermark(rc).isAfter(Instant.EPOCH));
            RuntimeContext newSession = RuntimeContext.builder(rc).sessionId("new-session").build();
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new MemoryGetTool(manager));
            Map<String, Object> input = Map.of("path", "MEMORY.md", "startLine", 1, "endLine", 20);
            ToolResultBlock result =
                    toolkit.callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(
                                                    ToolUseBlock.builder()
                                                            .id("new-session-read")
                                                            .name("memory_get")
                                                            .input(input)
                                                            .build())
                                            .input(input)
                                            .runtimeContext(newSession)
                                            .build())
                            .block(Duration.ofSeconds(10));
            assertEquals(ToolResultState.SUCCESS, result.getState());
            String recalled = ((TextBlock) result.getOutput().get(0)).getText();
            for (String fact :
                    List.of(
                            "[user] " + oldOwner,
                            "[user] " + newOwner,
                            "[user] " + preference,
                            "[observed] " + observed,
                            "[inferred] " + inferred))
                assertTrue(recalled.contains(fact), recalled);
            assertEquals(2, inputs.size());
            assertEquals(beforeMemory, store.get(otherNamespace, "MEMORY.md"));
            assertEquals(beforeDaily, store.get(otherNamespace, "memory/2026-09-08.md"));
            assertEquals(beforeState, store.get(otherNamespace, "memory/.consolidation_state"));
        }
    }

    private static String fact(String payload, String field, int occurrence) {
        Matcher matcher =
                Pattern.compile(
                                Pattern.quote(field)
                                        + "=[^;\n]+; source=[^;\n]+; date=\\d{4}-\\d{2}-\\d{2}")
                        .matcher(payload);
        for (int i = 0; i <= occurrence; i++)
            assertTrue(matcher.find(), "missing source fact " + field);
        return matcher.group();
    }

    private static ChatResponse response(String text, String finish) {
        return ChatResponse.builder()
                .id("pipeline-chunk")
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason(finish)
                .build();
    }
}
