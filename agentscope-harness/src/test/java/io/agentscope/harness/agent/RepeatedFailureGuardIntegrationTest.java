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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * End-to-end check that tool failure metadata reaches the model request through {@link
 * io.agentscope.harness.agent.middleware.RepeatedFailureGuard}.
 */
@HarnessQuiescence
class RepeatedFailureGuardIntegrationTest {

    @TempDir Path workspace;

    @Test
    void repeatedReadFailuresAddOneReminderToTheNextModelRequest() {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 3; i++) {
            model.script(toolUse("read-" + i, "read_file", Map.of("path", "missing.txt")));
        }
        model.script(text("done"));

        try (HarnessAgent agent = agent(model, false)) {
            agent.call(user("find the file"), RuntimeContext.builder().sessionId(session()).build())
                    .block();
        }

        assertEquals(4, model.inputs().size());
        assertFalse(hasReminder(model.inputs().get(1)), "one failure is not a loop");
        assertFalse(hasReminder(model.inputs().get(2)), "two failures are not a loop");
        assertTrue(hasReminder(model.inputs().get(3)), "next request carries the reminder");

        Msg reminder =
                model.inputs().get(3).stream()
                        .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                        .findFirst()
                        .orElseThrow();
        String text = reminder.getTextContent();
        assertTrue(text.contains("read_file"), text);
        assertTrue(text.contains("not_found"), text);
        assertTrue(text.contains("missing.txt"), text);
    }

    @Test
    void disablingTheGuardLeavesTheRequestUnchanged() {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 3; i++) {
            model.script(toolUse("read-" + i, "read_file", Map.of("path", "missing.txt")));
        }
        model.script(text("done"));

        try (HarnessAgent agent = agent(model, true)) {
            agent.call(user("find the file"), RuntimeContext.builder().sessionId(session()).build())
                    .block();
        }

        assertEquals(4, model.inputs().size());
        assertFalse(hasReminder(model.inputs().get(3)));
    }

    private static String session() {
        return "guard-" + UUID.randomUUID();
    }

    private HarnessAgent agent(Model model, boolean disableGuard) {
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("guard-it-" + UUID.randomUUID())
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .journalEnabled(false)
                        .disableSubagents();
        if (disableGuard) {
            builder.disableRepeatedFailureGuard();
        }
        return builder.build();
    }

    private static boolean hasReminder(List<Msg> messages) {
        return messages.stream().anyMatch(RepeatedFailureGuardIntegrationTest::isReminder);
    }

    private static boolean isReminder(Msg message) {
        Map<String, Object> metadata = message.getMetadata();
        return metadata != null
                && "repeated_failure".equals(metadata.get("reminderKind"))
                && Boolean.TRUE.equals(metadata.get("synthetic"));
    }

    private static Msg user(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Flux<ChatResponse> text(String text) {
        return Flux.just(
                new ChatResponse(
                        "scripted-model",
                        List.of(TextBlock.builder().text(text).build()),
                        null,
                        Map.of(),
                        "stop"));
    }

    private static Flux<ChatResponse> toolUse(String id, String name, Map<String, Object> input) {
        ToolUseBlock use =
                ToolUseBlock.builder().id(id).name(name).input(input).content("{}").build();
        return Flux.just(
                new ChatResponse("scripted-model", List.of(use), null, Map.of(), "tool_use"));
    }

    private static final class ScriptedModel implements Model {
        private final Queue<Flux<ChatResponse>> script = new ConcurrentLinkedQueue<>();
        private final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();

        void script(Flux<ChatResponse> response) {
            script.add(response);
        }

        List<List<Msg>> inputs() {
            return inputs;
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            Flux<ChatResponse> scripted = script.poll();
            return scripted != null ? scripted : text("done");
        }
    }
}
