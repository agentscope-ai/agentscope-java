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
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class MemoryFlushManagerTest {

    @TempDir Path workspace;

    @Test
    void flushMemories_skipsCompactionSummariesWhenRealMessagesRemain() {
        RecordingModel model = new RecordingModel();
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);

            flushManager
                    .flushMemories(
                            rc,
                            List.of(
                                    compactionSummary("internal summary should not be flushed"),
                                    message(MsgRole.USER, "real follow-up")))
                    .block();
        }

        String userPrompt = model.userPrompt(0);
        assertFalse(userPrompt.contains("internal summary should not be flushed"));
        assertTrue(userPrompt.contains("real follow-up"));
    }

    @Test
    void flushMemories_summaryOnlyInputDoesNotCallModel() {
        RecordingModel model = new RecordingModel();
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);

            flushManager.flushMemories(rc, List.of(compactionSummary("internal only"))).block();
        }

        assertTrue(model.inputs.isEmpty());
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder().role(role).content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg compactionSummary(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String text(Msg message) {
        return ((TextBlock) message.getContent().get(0)).getText();
    }

    private static final class RecordingModel implements Model {

        private final List<List<Msg>> inputs = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .id("flush-response")
                            .content(List.of(TextBlock.builder().text("NO_REPLY").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }

        private String userPrompt(int index) {
            assertEquals(2, inputs.get(index).size());
            return text(inputs.get(index).get(1));
        }
    }
}
